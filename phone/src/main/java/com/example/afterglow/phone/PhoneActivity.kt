package com.example.afterglow.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import com.example.afterglow.net.WorkoutHistoryEntry
import com.example.afterglow.net.SecureRelayEndpoint

/** Foreground, read-only Health Connect relay. No health records or pairing secret are persisted. */
class PhoneActivity : ComponentActivity() {
    private var vehicleHost = false
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var connect: Button
    private lateinit var consent: CheckBox
    private val relay = RelaySession()
    private val transport = RelayTransport()
    private val bluetooth by lazy { PhoneBluetoothTransport(this) }
    private val bluetoothPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && foreground) showBluetoothDevices()
        else if(::status.isInitialized) status.text="Bluetooth 연결 권한이 필요해요. 인터넷 연결은 계속 사용할 수 있어요."
    }
    private var pendingAuthorization: RelaySession.Authorization? = null
    private var permissionRequestInFlight = false
    private var poll: Job? = null
    private var pairingJob: Job? = null
    @Volatile private var pairingRevision = 0L
    @Volatile private var foreground = false
    private var lastSent = ""
    private val identityStore by lazy { AndroidPhoneIdentityStore.get(this) }
    private val connectionStore by lazy {PhoneConnectionStore(this)}
    private var connectionStorageError=false
    private var restoringConnection=false
    private var autoResumePending=false
    private var pendingAutomaticStart=false
    private var automaticSetupInFlight=false
    private val automaticHealthPermission=registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {granted->
        if(PhoneAutoHistoryService.BACKGROUND in granted && HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
            pendingAutomaticStart=true
            if(foreground) enableAutomaticHistory()
        } else if(::status.isInitialized) status.text="폰 화면을 닫아도 동기화하려면 운동·백그라운드 읽기를 허용해야 해요."
    }
    private val automaticNotificationPermission=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {granted->
        if(granted) {pendingAutomaticStart=true;if(foreground) enableAutomaticHistory()}
        else {pendingAutomaticStart=false;if(::status.isInitialized) status.text="자동연동 상태와 중지 버튼을 표시하려면 알림 권한이 필요해요."}
    }
    private var identityStorageError = false
    private var phoneIdentity: PhoneRelayIdentity? = null
    private val permissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )
    private val permissionRequest = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (vehicleHost) return@registerForActivityResult
        permissionRequestInFlight = false
        val ticket = pendingAuthorization
        pendingAuthorization = null
        connect.isEnabled = true
        if (ticket != null) {
            if (permissions.any { it in granted }) {
                if (relay.authorize(ticket)) lifecycleScope.launch {rememberAndPoll(ticket.destination)}
            } else status.text = "운동 또는 심박 중 전달할 항목의 읽기 권한을 허용하세요."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicleHost = VehicleCompanionGuard.showIfVehicle(this)
        if (vehicleHost) return
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        phoneIdentity = try { identityStore.load() }
        catch (_: Exception) { identityStorageError = true; null }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(52), dp(24), dp(40))
            setBackgroundColor(Color.rgb(17, 21, 27))
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(240, 246, 250))
            setPadding(0, dp(8), 0, dp(10))
        }
        layout.addView(text("IEUM / CONNECT", 15f))
        layout.addView(Button(this).apply {
            text = "‹ 닫기"; minHeight = dp(54)
            setOnClickListener { if(!PhoneAutoHistoryService.enabled(this@PhoneActivity)) disconnect();finish() }
        })
        layout.addView(text("운동 기록을\n차량으로 연결해요", 32f))
        layout.addView(text("워치 → 휴대폰 Health Connect → 이 차량\n최근 24시간 운동 목록과 최근 심박을 전달합니다.\n자동 추천은 최근 60분의 지원 운동만 사용합니다.", 17f))
        status = text("Bluetooth 직접 연결 또는 HTTPS 중계 연결을 선택하세요.", 18f)
        status.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        status.setBackgroundColor(Color.rgb(32, 49, 44))
        status.setPadding(dp(16), dp(16), dp(16), dp(16))
        summary = text("건강 기록을 아직 읽지 않았어요.", 18f)
        layout.addView(status); layout.addView(summary)
        consent = CheckBox(this).apply {
            text = "건강 기록을 연결된 차량에 전달하는 데 동의합니다."
            textSize = 17f; setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
        }
        layout.addView(consent)
        connect = Button(this).apply {
            text = "HTTPS 차량 연결 설정"; textSize = 18f; minHeight = dp(62)
            setOnClickListener { if (relay.transfer() != null) disconnect() else authorize() }
        }
        layout.addView(connect)
        layout.addView(Button(this).apply {
            text="폰 화면을 닫아도 운동 자동연동";minHeight=dp(62)
            setOnClickListener {enableAutomaticHistory()}
        })
        layout.addView(Button(this).apply {
            text="운동 자동연동 중지";minHeight=dp(54)
            setOnClickListener {PhoneAutoHistoryService.stop(this@PhoneActivity);status.text="운동 자동연동을 중지했어요."}
        })
        layout.addView(Button(this).apply {
            text="운동 읽기 권한 확인";minHeight=dp(54)
            setOnClickListener {authorize()}
        })
        layout.addView(Button(this).apply {
            text = "다른 차량 연결"; minHeight = dp(54)
            setOnClickListener { showSecurePairing() }
        })
        layout.addView(Button(this).apply {
            text = "Bluetooth 차량 직접 연결"; minHeight = dp(54)
            setOnClickListener { showBluetoothDevices() }
        })
        layout.addView(Button(this).apply {
            text="저장된 차량 연결 해제";minHeight=dp(54)
            setOnClickListener {
                disconnect();relay.clear();consent.isChecked=false
                connectionStorageError=runCatching {connectionStore.clear()}.isFailure
                status.text=if(connectionStorageError) "저장된 연결을 삭제하지 못했어요. 전송은 중단했습니다." else "저장된 차량 연결을 삭제했어요."
            }
        })
        layout.addView(Button(this).apply {
            text = "운전자 프로필 연결 설정"; minHeight = dp(54)
            setOnClickListener { showIdentitySettings() }
        })
        layout.addView(Button(this).apply {
            text = "정보 사용 안내"; minHeight = dp(54)
            setOnClickListener { startActivity(Intent(this@PhoneActivity, PrivacyActivity::class.java)) }
        })
        layout.addView(text("기본은 앱 화면에서 20초 간격으로 확인합니다. ‘폰 화면을 닫아도 운동 자동연동’을 켜고 별도 읽기·알림 권한을 허용하면 다른 앱을 보거나 화면을 꺼도 운동 목록을 갱신합니다. 실제 워치 기록의 Health Connect 동기화가 필요합니다.", 14f))
        setContentView(ScrollView(this).apply { addView(layout) })
        consent.setOnCheckedChangeListener { _, checked ->
            relay.consent(checked)
            if (!checked) {
                disconnect()
                summary.text = "건강 정보 전달 동의를 해제했어요."
            }
        }
        if (identityStorageError) status.text = "프로필 연결 정보를 열지 못했어요. 운전자 프로필 연결 설정에서 끄고 저장하거나 다시 등록하세요. 건강 정보 전송은 중단했어요."
        if(intent.data==null) restoreConnection() else acceptPairing(intent)
    }

    private fun restoreConnection() {
        restoringConnection=true
        val revision=pairingRevision
        lifecycleScope.launch {
            try {
                val target=withContext(Dispatchers.IO) {connectionStore.load()}
                if(target!=null && revision==pairingRevision && !relay.hasPairing()) {
                    relay.pair(target);consent.isChecked=true;autoResumePending=true
                    connect.text="권한을 확인하고 연결"
                    status.text="저장된 차량 연결 복원 · 건강 읽기 권한 확인 중"
                }
            } catch(error:CancellationException) {throw error}
            catch(_:Exception) {
                connectionStorageError=true
                status.text="저장된 차량 연결을 열지 못했어요. ‘저장된 차량 연결 해제’ 후 다시 연결해주세요."
            } finally {restoringConnection=false}
            resumeApprovedConnection()
        }
    }
    private fun resumeApprovedConnection() {
        if(!foreground || !autoResumePending || connectionStorageError || identityStorageError) return
        autoResumePending=false
        val ticket=relay.beginAuthorization() ?: return
        lifecycleScope.launch {
            try {
                if(HealthConnectClient.getSdkStatus(this@PhoneActivity)!=HealthConnectClient.SDK_AVAILABLE) {
                    status.text="Health Connect 설치와 읽기 권한을 확인해주세요.";return@launch
                }
                val granted=HealthConnectClient.getOrCreate(this@PhoneActivity).permissionController.getGrantedPermissions()
                if(!relay.canRequestPermission(ticket)) {autoResumePending=true;return@launch}
                if(permissions.none {it in granted}) {status.text="저장된 차량 연결 · 운동 읽기 권한을 다시 허용해주세요.";return@launch}
                if(relay.authorize(ticket)) {
                    startPolling()
                    if(PhoneAutoHistoryService.enabled(this@PhoneActivity)) enableAutomaticHistory()
                }
            } catch(_:Exception) {status.text="자동 조회를 시작하지 못했어요. 연결 버튼으로 다시 시도해주세요."}
        }
    }
    private suspend fun rememberAndPoll(target:RelayDestination) {
        try {
            val saved=withContext(Dispatchers.IO) {connectionStore.save(target) {relay.authorizedFor(target)}}
            if(!saved || !relay.authorizedFor(target)) return
            if(!foreground) autoResumePending=true
            startPolling()
            if(pendingAutomaticStart && foreground) enableAutomaticHistory()
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {
            connectionStorageError=true;disconnect()
            status.text="차량 연결을 저장하지 못해 전송을 중단했어요. 저장된 연결을 해제한 뒤 다시 연결해주세요."
        }
    }

    private fun enableAutomaticHistory() {
        if(!foreground || connectionStorageError || identityStorageError || automaticSetupInFlight) return
        if(relay.transfer()==null) {
            pendingAutomaticStart=true
            status.text="먼저 차량 연결과 건강 정보 전달 동의를 완료해주세요."
            return
        }
        if(!PhoneAutoHistoryService.notificationsAllowed(this)) {
            if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingAutomaticStart=true;automaticNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {pendingAutomaticStart=false;status.text="휴대폰 설정에서 IEUM 알림을 허용한 뒤 다시 눌러주세요."}
            return
        }
        automaticSetupInFlight=true
        lifecycleScope.launch {
            try {
                val client=HealthConnectClient.getOrCreate(this@PhoneActivity)
                if(!PhoneAutoHistoryService.backgroundSupported(client)) {
                    pendingAutomaticStart=false;status.text="이 Health Connect 버전은 백그라운드 읽기를 지원하지 않아요. 화면에서만 조회할 수 있어요.";return@launch
                }
                val required=setOf(PhoneAutoHistoryService.BACKGROUND,HealthPermission.getReadPermission(ExerciseSessionRecord::class))
                val granted=client.permissionController.getGrantedPermissions()
                if(!foreground || relay.transfer()==null) return@launch
                if(!granted.containsAll(required)) {
                    pendingAutomaticStart=false;automaticHealthPermission.launch(required);return@launch
                }
                pendingAutomaticStart=false
                PhoneAutoHistoryService.start(this@PhoneActivity)
                status.text="운동 자동연동 켜짐 · 이제 다른 앱을 보거나 폰 화면을 꺼도 됩니다."
            } catch(error:CancellationException) {throw error}
            catch(_:Exception) {pendingAutomaticStart=false;status.text="자동연동을 시작하지 못했어요. 연결·읽기·알림 권한을 확인해주세요."}
            finally {automaticSetupInFlight=false}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (vehicleHost) { intent.data = null; return }
        setIntent(intent); acceptPairing(intent)
    }

    private fun acceptPairing(incoming: Intent) {
        val uri = incoming.data ?: return
        incoming.data = null
        if (uri.scheme !in setOf("ieum", "afterglow") || uri.host != "pair") return
        if (uri.fragment != null || uri.queryParameterNames != setOf("endpoint")) {
            status.text = "이전 연결 링크는 사용할 수 없어요. HTTPS 주소와 일회용 코드로 연결하세요."; return
        }
        val endpoint = runCatching { SecureRelayEndpoint(uri.getQueryParameter("endpoint").orEmpty()) }.getOrNull()
        if (endpoint == null) { status.text = "유효한 HTTPS 연결 주소가 필요해요."; return }
        showSecurePairing(endpoint.address)
    }

    private fun cancelPairing() {
        pairingRevision++; pairingJob?.cancel(); pairingJob = null; transport.cancel();bluetooth.cancel()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showBluetoothDevices() {
        if(profileStorageBlocked() || !foreground) return
        if(!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH)) {
            showConnectionHelp("Bluetooth 미지원","이 기기에는 Bluetooth 직접 연결 기능이 없습니다.");return
        }
        if(android.os.Build.VERSION.SDK_INT>=31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT);return
        }
        val adapter=getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if(adapter?.isEnabled!=true) {
            showConnectionHelp("Bluetooth를 켜주세요","휴대폰 설정에서 Bluetooth를 켜고 차량과 먼저 페어링해주세요.");return
        }
        val devices=runCatching {adapter.bondedDevices.toList().sortedBy {it.name.orEmpty()}}.getOrElse {
            showConnectionHelp("Bluetooth 권한 확인","주변 기기 연결 권한을 허용해주세요.");return
        }
        if(devices.isEmpty()) {
            showConnectionHelp("차량과 먼저 페어링해주세요","휴대폰 Bluetooth 설정에서 차량을 등록한 후 다시 눌러주세요. 차량 IEUM의 Bluetooth 직접 연결도 열어두세요.");return
        }
        val dialog=AlertDialog.Builder(this).setTitle("페어링한 차량 선택")
            .setItems(devices.map { "${it.name ?: "이름 없는 기기"} · ${it.address.takeLast(5)}" }.toTypedArray()) {_,index->
                showBluetoothCode(devices[index].address)
            }.setNegativeButton("취소",null).create()
        dialog.setOnShowListener {dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}
        dialog.show()
    }

    private fun showBluetoothCode(address: String) {
        disconnect()
        val code=EditText(this).apply {
            hint="차량 Bluetooth 창의 6자리 코드"
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters=arrayOf(android.text.InputFilter.LengthFilter(6));minHeight=dp(60)
        }
        val dialog=AlertDialog.Builder(this).setTitle("Bluetooth 차량 승인")
            .setMessage("차량 IEUM에서 Bluetooth 직접 연결을 열어 코드를 확인하세요. 인터넷 주소는 필요하지 않습니다.")
            .setView(code).setNegativeButton("취소",null).setPositiveButton("연결",null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value=code.text.toString()
                if(!value.matches(Regex("[0-9]{6}"))) {code.error="6자리 코드를 입력하세요.";return@setOnClickListener}
                cancelPairing();relay.clear();consent.isChecked=false
                val revision=pairingRevision;code.text.clear();dialog.dismiss();connect.isEnabled=false
                status.text="Bluetooth 차량 승인 중 · 건강 기록은 아직 읽지 않아요."
                pairingJob=lifecycleScope.launch {
                    try {
                        val target=withContext(Dispatchers.IO) {
                            val context=currentCoroutineContext()
                            bluetooth.pair(address,value) {context.isActive && foreground && revision==pairingRevision}
                        }
                        if(!foreground || revision!=pairingRevision) return@launch
                        relay.pair(target)
                        status.text="Bluetooth 차량 연결 승인\n건강 기록 전달 동의 후 읽기 권한을 허용하세요."
                        connect.text="권한을 허용하고 연결"
                    } catch(error:CancellationException) {throw error}
                    catch(_:Exception) {if(revision==pairingRevision) status.text="Bluetooth 연결 실패 · 차량의 직접 연결 창, 페어링과 새 코드를 확인하세요."}
                    finally {if(revision==pairingRevision) connect.isEnabled=true}
                }
            }
        }
        dialog.show()
    }

    private fun postToVehicle(target:RelayDestination,requestId:String,payload:String,path:String,allowed:()->Boolean) {
        if(target.bluetoothAddress!=null) bluetooth.post(target,requestId,payload,path,allowed)
        else transport.post(target,requestId,payload,path,allowed)
    }

    private fun showSecurePairing(initialAddress: String = getString(R.string.ieum_development_relay_endpoint)) {
        if (profileStorageBlocked()) return
        disconnect()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val address = EditText(this).apply {
            hint = "https://중계주소/ingest/native"; setText(initialAddress)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(android.text.InputFilter.LengthFilter(2048)); minHeight = dp(60)
        }
        val code = EditText(this).apply {
            hint = "차량의 6자리 연결 코드"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(6)); minHeight = dp(60)
        }
        fields.addView(TextView(this).apply { text = "직접 확인한 중계 주소를 입력하세요. 승인한 주소를 통해 차량으로 건강 정보를 전송합니다. 6자리 코드와 건강 정보는 저장하지 않습니다. 승인한 차량의 연결 자격은 별도 키로 암호화해 보관하며 연결 중지 시 삭제합니다."; textSize = 17f })
        fields.addView(address); fields.addView(code)
        val dialog = AlertDialog.Builder(this).setTitle("HTTPS 차량 연결")
            .setView(fields).setNegativeButton("취소", null).setPositiveButton("연결", null).create()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val endpoint = runCatching { SecureRelayEndpoint(address.text.toString()) }.getOrNull()
                if (endpoint == null) { address.error = "인증된 HTTPS 도메인 주소를 입력하세요."; return@setOnClickListener }
                val oneTimeCode = code.text.toString()
                if (!oneTimeCode.matches(Regex("[0-9]{6}"))) { code.error = "6자리 코드를 입력하세요."; return@setOnClickListener }
                cancelPairing(); relay.clear(); consent.isChecked = false
                val revision = pairingRevision
                code.text.clear(); dialog.dismiss(); connect.isEnabled = false
                status.text = "${endpoint.host} 연결 승인 중 · 건강 기록은 아직 읽지 않아요."
                pairingJob = lifecycleScope.launch {
                    try {
                        val destination = withContext(Dispatchers.IO) {
                            val context = currentCoroutineContext()
                            transport.pair(endpoint, oneTimeCode) { context.isActive && foreground && revision == pairingRevision }
                        }
                        if (!foreground || revision != pairingRevision) return@launch
                        relay.pair(destination)
                        status.text = "차량 연결 승인 · ${destination.host}\n건강 기록 전달 동의 후 읽기 권한을 허용하세요."
                        connect.text = "권한을 허용하고 연결"
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        if (revision == pairingRevision) status.text = "연결하지 못했어요. HTTPS 주소와 차량의 새 연결 코드를 확인하세요."
                    } finally {
                        if (revision == pairingRevision) { connect.isEnabled = true; pairingJob = null }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun authorize() {
        if (profileStorageBlocked()) return
        if(restoringConnection) {status.text="저장된 차량 연결을 확인 중입니다.";return}
        if(connectionStorageError) {status.text="저장된 차량 연결 해제 후 다시 연결해주세요.";return}
        if (!relay.hasPairing()) {
            showSecurePairing()
            return
        }
        if (!consent.isChecked) { showConnectionHelp("정보 전달 동의가 필요해요", "차량에 운동·심박 정보를 전달하는 데 동의한 뒤 연결을 눌러 주세요."); return }
        if (permissionRequestInFlight) { status.text = "열려 있는 건강 권한 요청을 먼저 완료해 주세요."; return }
        val ticket = relay.beginAuthorization() ?: run { status.text = "차량 연결 설정을 먼저 완료하세요."; return }
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_UNAVAILABLE -> { showConnectionHelp("Health Connect를 사용할 수 없어요", "이 기기는 건강 기록 읽기를 지원하지 않습니다. 워치가 연결된 Android 휴대폰에서 실행해 주세요. iPhone·Apple Watch는 차량의 iPhone 인터넷 연결 안내를 이용하세요."); return }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                status.text = "Health Connect 설치 또는 업데이트가 필요해요."
                runCatching { startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }; return
            }
        }
        pendingAuthorization = ticket
        connect.isEnabled = false
        lifecycleScope.launch {
            var launchedPermissionRequest = false
            try {
                val client = HealthConnectClient.getOrCreate(this@PhoneActivity)
                val granted = client.permissionController.getGrantedPermissions()
                if (pendingAuthorization !== ticket) return@launch
                if (permissions.all { it in granted }) {
                    if (relay.authorize(ticket)) rememberAndPoll(ticket.destination)
                } else {
                    if (!relay.canRequestPermission(ticket)) return@launch
                    permissionRequestInFlight = true
                    try {
                        permissionRequest.launch(permissions)
                        launchedPermissionRequest = true
                    } catch (error: Exception) {
                        permissionRequestInFlight = false
                        throw error
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (pendingAuthorization === ticket) status.text = "Health Connect를 열지 못했어요. 설치와 권한을 확인하세요."
            } finally {
                if (!launchedPermissionRequest && pendingAuthorization === ticket) {
                    pendingAuthorization = null; connect.isEnabled = true
                }
            }
        }
    }

    private fun startPolling() {
        stopPolling()
        if (identityStorageError) return
        val ticket = relay.transfer() ?: return
        val identity = phoneIdentity
        connect.text = "연결 중지"
        poll = lifecycleScope.launch {
            while (isActive && relay.isCurrent(ticket)) {
                PhoneHistorySyncControl.gate.withLock {
                    if(relay.isCurrent(ticket) && PhoneHistorySyncControl.visible && syncConnection(ticket, identity)) syncLatest(ticket, identity)
                }
                delay(20_000)
            }
        }
    }

    private suspend fun syncConnection(ticket: RelaySession.Transfer, identity: PhoneRelayIdentity?): Boolean {
        try {
            if (!relay.isCurrent(ticket)) return false
            val requestId = "phone-${UUID.randomUUID()}"
            val payload = identity?.connectionPayload(requestId) ?: JSONObject()
                .put("source", "health-connect").put("requestId", requestId).toString()
            val granted=HealthConnectClient.getOrCreate(this).permissionController.getGrantedPermissions()
            val connectionPayload=JSONObject(payload).put("workoutSyncStatus",if(HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) "ready" else "permission_required").toString()
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                postToVehicle(ticket.destination, requestId, connectionPayload, path = "/connect") {
                    context.isActive && relay.isCurrent(ticket)
                }
            }
            if (!relay.isCurrent(ticket)) return false
            status.text = "차량 응답 확인 · 건강 기록을 확인합니다."
            return true
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (relay.isCurrent(ticket)) status.text = "차량 연결 실패 · ${if(ticket.destination.bluetoothAddress!=null) "Bluetooth와 차량 직접 연결" else "인터넷과 HTTPS 중계"}을 확인하세요. 건강 기록을 읽지 않았어요."
            return false
        }
    }

    private suspend fun syncLatest(ticket: RelaySession.Transfer, identity: PhoneRelayIdentity?) {
        try {
            if (!relay.isCurrent(ticket)) return
            val client = HealthConnectClient.getOrCreate(this)
            val granted = client.permissionController.getGrantedPermissions()
            if (!relay.isCurrent(ticket)) return
            val readExercise=HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted
            val readHeart=HealthPermission.getReadPermission(HeartRateRecord::class) in granted
            if (!readExercise && !readHeart) {
                disconnect(); status.text = "권한이 해제되어 전송을 중단했어요."; return
            }
            val now = Instant.now()
            val history = PagedWorkoutHistory(now.toEpochMilli())
            val sessions = PagedLatestChoice<ExerciseSessionRecord> { record ->
                record.endTime.toEpochMilli().takeIf {
                    record.endTime <= now && record.endTime >= now.minusSeconds(3600) &&
                        record.metadata.recordingMethod != Metadata.RECORDING_METHOD_MANUAL_ENTRY
                }
            }
            while (readExercise && sessions.canReadNextPage) {
                val result = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,
                    TimeRangeFilter.between(now.minusSeconds(86_400), now), pageSize = 1000,
                    pageToken = sessions.nextPageToken))
                if (!relay.isCurrent(ticket)) return
                sessions.acceptPage(result.records.asSequence(), result.pageToken)
                history.acceptPage(result.records.asSequence().map { record ->
                    WorkoutHistoryEntry(record.exerciseType,historyKind(record.exerciseType),record.startTime.toEpochMilli(),
                        record.endTime.toEpochMilli(),record.metadata.dataOrigin.packageName,
                        record.metadata.recordingMethod==Metadata.RECORDING_METHOD_MANUAL_ENTRY)
                },result.pageToken)
            }
            val historySnapshot=history.snapshot().takeIf { readExercise }
            val recent = sessions.latest.takeIf { readExercise }
            val kind = recent?.let { activityName(it.exerciseType) }
            val heartSamples = PagedLatestChoice<HeartRateRecord.Sample> { sample ->
                sample.time.toEpochMilli().takeIf {
                    sample.time <= now && sample.time >= now.minusSeconds(600) && sample.beatsPerMinute in 40..220
                }
            }
            while (readHeart && heartSamples.canReadNextPage) {
                val result = client.readRecords(ReadRecordsRequest(HeartRateRecord::class,
                    TimeRangeFilter.between(now.minusSeconds(600), now), pageSize = 1000,
                    pageToken = heartSamples.nextPageToken))
                if (!relay.isCurrent(ticket)) return
                heartSamples.acceptPage(result.records.asSequence()
                    .filter { it.metadata.recordingMethod != Metadata.RECORDING_METHOD_MANUAL_ENTRY }
                    .flatMap { it.samples.asSequence() }, result.pageToken)
            }
            // A complete workout is still useful when the heart-rate scan is incomplete.
            val sample = heartSamples.latest.takeIf { readHeart }
            val workout = if(kind!=null && recent!=null) WorkoutRelaySnapshot(kind.first, recent.endTime.toEpochMilli(),
                Duration.between(recent.startTime, recent.endTime), sample?.beatsPerMinute, sample?.time?.toEpochMilli(), identity) else null
            val pulse=sample?.let { HeartRateRelaySnapshot(it.beatsPerMinute,it.time.toEpochMilli(),identity) }
            summary.text = when {
                workout!=null -> "${kind!!.second} · ${workout.durationLabel}\n출처: ${recent!!.metadata.dataOrigin.packageName}"
                !readExercise -> "운동 읽기 권한 없음 · 운동은 조회하지 않아요."
                !sessions.complete -> "운동 조회 미완료 · 부분 운동 기록은 보내지 않아요."
                recent==null -> "최근 60분의 자동·활동 기록 없음"
                else -> "자동 추천 미지원 운동 · 운동은 보내지 않아요."
            } + "\n" + when {
                !readHeart -> "심박 읽기 권한 없음 · 심박은 조회하지 않아요."
                !heartSamples.complete -> "심박 조회 미완료 · 부분 심박 기록은 보내지 않아요."
                sample==null -> "최근 10분의 심박 표본 없음"
                else -> "Health Connect 심박: ${com.example.afterglow.net.HealthDisplayTime.age(Duration.between(sample.time, now).toMillis())}"
            }
            if(historySnapshot!=null) {
                val historyId="history-${UUID.randomUUID()}"
                val historyJson=historySnapshot.json(historyId).also { identity?.appendTo(it) }.toString()
                withContext(Dispatchers.IO) {
                    val context=currentCoroutineContext()
                    postToVehicle(ticket.destination,historyId,historyJson,"/workout-history") { context.isActive && relay.isCurrent(ticket) }
                }
                if(!relay.isCurrent(ticket)) return
                summary.text="최근 24시간 조회 ${historySnapshot.totalMatches}건 · 전송 ${historySnapshot.entries.size}건"+
                    (if(historySnapshot.truncated) " (최신24건만 표시)" else "")+"\n"+summary.text
                status.text="24시간 운동 조회 결과 차량 수신 확인 · ${historySnapshot.entries.size}건"
            }
            if(workout==null && pulse==null) {
                if(historySnapshot==null) status.text="허용된 항목의 새 건강 기록을 기다리고 있어요."
                return
            }
            val signature = workout?.fingerprint ?: pulse!!.fingerprint
            if (signature == lastSent) { if(historySnapshot==null) status.text = "동기화 중 · 새 기록을 기다리고 있어요."; return }
            val requestId = "phone-${UUID.randomUUID()}"
            val payload = workout?.payload(requestId) ?: pulse!!.payload(requestId)
            val path = if(workout!=null) "/workout" else "/health-heart-rate"
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                postToVehicle(ticket.destination, requestId, payload, path) {
                    context.isActive && relay.isCurrent(ticket)
                }
            }
            if (!relay.isCurrent(ticket)) return
            lastSent = signature
            status.text = "차량에 전달 완료 · Health Connect 수신으로 표시됩니다."
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (relay.isCurrent(ticket)) status.text = "동기화 실패 · ${if(ticket.destination.bluetoothAddress!=null) "Bluetooth 차량 연결" else "인터넷·HTTPS 중계"}과 건강 읽기 권한을 확인하세요."
            if(relay.isCurrent(ticket)) runCatching {
                val requestId="history-error-${UUID.randomUUID()}"
                val body=JSONObject().put("source","health-connect").put("requestId",requestId).put("workoutSyncStatus","query_failed").also {identity?.appendTo(it)}.toString()
                withContext(Dispatchers.IO) {postToVehicle(ticket.destination,requestId,body,"/connect") {relay.isCurrent(ticket)}}
            }
        }
    }

    private fun stopPolling() {
        poll?.cancel(); poll = null
        transport.cancel()
        bluetooth.cancel()
    }

    private fun showIdentitySettings() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val sharing = Switch(this).apply {
            text = "이 휴대폰으로 운전자 프로필 연결"; textSize = 18f; minHeight = dp(64)
            isChecked = phoneIdentity != null
        }
        val name = EditText(this).apply {
            hint = "별칭 · 예: 내 휴대폰"; setText(phoneIdentity?.displayName.orEmpty()); textSize = 18f
            filters = arrayOf(android.text.InputFilter.LengthFilter(24)); minHeight = dp(60)
            isEnabled = sharing.isChecked
        }
        fields.addView(sharing); fields.addView(name)
        fields.addView(TextView(this).apply {
            textSize = 16f; setPadding(0, dp(16), 0, dp(12))
            text = "기본은 꺼짐입니다. 동의하면 이 앱에서 만든 임의 식별자와 별칭을 차량에 보냅니다. 얼굴 인식이나 신원 인증은 아닙니다.\n\n식별자와 별칭은 이 휴대폰에 암호화해 저장합니다. 끄면 삭제하며 다시 켜면 차량에서 새로 연결해야 합니다. 차량에 남은 연결은 차량의 운전자 프로필 설정에서 해제하세요.\n\n프로필을 바꾸려면 차량에서도 휴대폰 연결을 확인하고 자동 선택을 켜야 합니다. 자동 Mode를 자동으로 시작하지 않습니다."
        })
        sharing.setOnCheckedChangeListener { _, enabled -> name.isEnabled = enabled }
        val dialog = AlertDialog.Builder(this).setTitle("선택적 프로필 연결")
            .setView(ScrollView(this).apply { addView(fields) }).setNegativeButton("취소", null)
            .setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = if (sharing.isChecked) runCatching {
                    PhoneRelayIdentity(phoneIdentity?.phoneId ?: UUID.randomUUID().toString(), name.text.toString())
                }.getOrElse { name.error = "별칭은 제어 문자 없이24자 이내로 입력하세요"; return@setOnClickListener } else null
                if (!identityStorageError && candidate?.phoneId == phoneIdentity?.phoneId && candidate?.displayName == phoneIdentity?.displayName) {
                    dialog.dismiss(); return@setOnClickListener
                }
                disconnect()
                try { identityStore.save(candidate) }
                catch (_: Exception) {
                    identityStorageError = true; phoneIdentity = null
                    status.text = "프로필 연결 설정을 저장하지 못해 건강 정보 전송을 중지했어요. 끄고 저장하거나 다시 시도해 주세요."
                    return@setOnClickListener
                }
                identityStorageError = false
                phoneIdentity = candidate
                status.text = if (candidate == null) "프로필 식별자를 삭제했어요. 건강 기록 연결은 다시 시작할 수 있어요."
                    else "프로필 연결 정보를 저장했어요. 연결을 시작한 뒤 차량에서 프로필 연결을 확인하세요."
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun disconnect() {
        PhoneAutoHistoryService.stop(this)
        pendingAutomaticStart=false
        autoResumePending=false
        cancelPairing()
        relay.stop(); pendingAuthorization = null
        runCatching {connectionStore.clear()}.onFailure {connectionStorageError=true}
        stopPolling(); lastSent = ""
        if (::connect.isInitialized) {
            connect.isEnabled = true; connect.text = if (relay.hasPairing()) "권한을 허용하고 연결" else "HTTPS 차량 연결 설정"
            status.text = "연결 중지 · 건강 데이터 저장 없음"
        }
    }

    private fun profileStorageBlocked(): Boolean {
        if (!identityStorageError) return false
        showConnectionHelp("프로필 연결 정보를 확인하세요", "저장 정보를 열지 못해 건강 정보 전송을 중단했어요. 운전자 프로필 연결 설정에서 끄고 저장하거나 다시 등록하세요.")
        return true
    }

    override fun onStart() { super.onStart(); foreground = true;PhoneHistorySyncControl.visible=true;relay.foreground(true);startPolling();resumeApprovedConnection();if(pendingAutomaticStart) enableAutomaticHistory() }
    override fun onStop() { foreground = false; cancelPairing(); if (::connect.isInitialized) connect.isEnabled = true; relay.foreground(false);stopPolling();PhoneHistorySyncControl.visible=false;lastSent="";super.onStop() }
    override fun onDestroy() { cancelPairing(); relay.clear(); stopPolling(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showConnectionHelp(title: String, message: String) {
        status.text = message
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("확인", null).show()
    }

    companion object {
        private fun historyKind(type: Int): String = when(type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hike"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walk"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycle"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength"
        else -> "other"
    }
    private fun activityName(type: Int): Pair<String, String>? = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run" to "러닝"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim" to "수영"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING, ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "hike" to "하이킹·걷기"
            else -> null
        }
    }
}

class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(Button(this).apply {
            text = "‹ 이전 화면으로 돌아가기"; textSize = 20f; minHeight = 64
            setOnClickListener {
                if (isTaskRoot) startActivity(Intent(this@PrivacyActivity, PhoneActivity::class.java))
                finish()
            }
        })
        column.addView(TextView(this@PrivacyActivity).apply {
            textSize = 18f; setPadding(30, 60, 30, 40)
            text = "IEUM 건강 정보 사용 안내\n\n운동 기록: 최근 24시간 조회 구간의 완료 운동에서 종목 코드, 시작·종료 시각, 기록 앱, 수동 입력 여부를 읽습니다. 차량에는 최신 24건과 전체 조회 건수를 전달합니다. 제목·이동 경로는 읽거나 전송하지 않습니다. 자동 추천에는 최근 60분의 수동 입력이 아닌 지원 운동만 사용합니다.\n\n심박: 최근 10분 안의 마지막 표본이 있으면 측정 시각과 함께 전달합니다. 차량은 오래된 표본을 실시간 심박으로 사용하지 않습니다.\n\n전달 대상: Bluetooth 직접 연결을 선택하면 사용자가 OS에서 페어링한 차량에 암호화된 RFCOMM으로 전달합니다. 인터넷 주소는 사용하지 않습니다. HTTPS 연결을 선택하면 직접 확인한 중계 주소를 거쳐 승인한 차량으로 전달하며 플랫폼 인증서를 검증합니다. 연결 코드는 차량에서 확인하며 단 한 번만 사용할 수 있습니다. Bluetooth 페어링은 운전자 신원이나 워치 센서의 출처를 증명하지 않습니다.\n\n저장: 건강 기록과 6자리 연결 코드는 파일에 저장하지 않습니다. 승인한 차량의 주소·연결 자격과 전달 동의는 Android Keystore의 독립 키로 암호화해 저장합니다. 앱 재실행·업데이트 후에도 연결을 복원하고 읽기 권한을 확인한 뒤 자동 조회합니다. 연결 중지 또는 저장된 차량 연결 해제로 삭제할 수 있습니다. 기본 화면 조회는 앱을 벗어나면 중단됩니다. 별도의 운동 자동연동을 켜고 백그라운드 읽기·알림 권한을 허용하면 알림을 표시하는 연결 서비스가 운동 목록을 계속 전송합니다. 알림 또는 앱의 자동연동 중지 버튼으로 끌 수 있습니다. Android가 앱을 강제 종료하면 다시 앱을 열어야 합니다. 연결 중지로 이후 전송을 끊을 수 있고, Health Connect 설정에서 읽기 권한을 언제든 철회할 수 있습니다.\n\n의료 목적: 진단이나 치료가 아닌 편의 환경 추천용 시제품입니다.\n\n다른 워치: 제조사 앱이 Health Connect에 공유한 정보만 읽을 수 있습니다. Apple Health는 이 Android 앱에서 읽지 않습니다."
            append("\n\n선택적 프로필 연결: 기본은 식별자 미전달입니다. 별도로 켠 경우에만 앱이 만든 임의 식별자와 별칭을 이 휴대폰에 암호화해 저장하고 차량에 보냅니다. 백업과 기기 이전에서는 제외합니다. 저장 정보를 열지 못하면 전송을 중단하며 설정에서 다시 등록하거나 끄고 삭제할 수 있습니다. 차량에 남은 연결은 차량의 운전자 프로필 설정에서 해제하세요. 휴대폰 응답은 건강 기록 전달·신원 인증과 구분합니다.")
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }
}
