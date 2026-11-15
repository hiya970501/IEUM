package com.example.afterglow.wear

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.text.InputType
import android.view.Gravity
import android.widget.*
import com.example.afterglow.net.SecureRelayEndpoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/** Standalone watch client; no phone relay and no fake workout session. */
class WatchDirectActivity : Activity() {
    private lateinit var bpm:TextView
    private lateinit var status:TextView
    private lateinit var action:Button
    private lateinit var walking:WalkingPanel
    private var host=""
    private var token=""
    private lateinit var wifi:WatchWifiLink
    private val handler=Handler(Looper.getMainLooper())
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,SynchronousQueue(),ThreadPoolExecutor.AbortPolicy())
    @Volatile private var resumed=false
    private var pairing=false
    private var generation=0L
    private val refresh=object:Runnable {
        override fun run() {
            if(!resumed) return
            val fresh=System.currentTimeMillis()-WatchLiveState.measuredAt in 0..29_999
            bpm.text=WatchLiveState.bpm?.takeIf { fresh }?.let { "$it bpm" } ?: "— bpm"
            status.text=if(pairing) "차량 연결 확인 중" else if(WatchLiveState.running) {
                val receipt=WatchLiveState.deliveredAt.takeIf { it>0 }?.let { "\n차량 수신 ${(System.currentTimeMillis()-it)/1000}초 전" }.orEmpty()
                WatchLiveState.message+receipt
            } else if(token.isNotEmpty()) "연결 준비됨\n${WatchLiveState.message}" else WatchLiveState.message
            action.text=if(WatchLiveState.running) "측정·전송 중지" else "측정·전송 시작"
            action.isEnabled=!pairing
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        wifi=WatchWifiLink(this)
        host=getPreferences(MODE_PRIVATE).getString("host","").orEmpty().ifBlank { getString(R.string.ieum_development_relay_endpoint) }
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
            setPadding(28,42,28,42); setBackgroundColor(Color.rgb(15,27,29))
        }
        fun label(value:String,size:Float)=TextView(this).apply {
            text=value; textSize=size; gravity=Gravity.CENTER; setTextColor(Color.rgb(184,239,215))
        }
        layout.addView(label("IEUM",20f))
        bpm=label("— bpm",36f);layout.addView(bpm)
        status=label("차량 연결을 설정하세요",13f);layout.addView(status)
        layout.addView(Button(this).apply { text="차량 연결";setOnClickListener { configure() } })
        action=Button(this).apply { text="측정·전송 시작";setOnClickListener {
            if(WatchLiveState.running) stopService(Intent(this@WatchDirectActivity,LiveMeasurementService::class.java)) else begin()
        } };layout.addView(action)
        layout.addView(label("직접 연결 · 모바일 앱 불필요\n최소 5초 간격 전송 시도\n화면이 꺼져도 알림에서 중지 가능",11f))
        layout.addView(label("심박 측정·전송에 배터리를 사용합니다.\n표본 간격과 인터넷 상태에 따라\n차량 도착 간격은 달라집니다.",11f))
        walking=WalkingPanel(this,layout,wifi,{
            if(token.isNotEmpty()) WatchDestination(host,token) else null
        },{ resumed })
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    private fun configure() {
        if(pairing) return
        if(WatchLiveState.running || walking.active) { status.text="측정을 중지한 뒤 연결을 변경하세요";return }
        val fields=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(20,0,20,0) }
        val address=EditText(this).apply { hint="HTTPS 중계 주소";setText(host);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;filters=arrayOf(android.text.InputFilter.LengthFilter(2048)) }
        val code=EditText(this).apply { hint="6자리 일회용 연결 코드";inputType=InputType.TYPE_CLASS_NUMBER;filters=arrayOf(android.text.InputFilter.LengthFilter(6)) }
        val configured=runCatching { SecureRelayEndpoint(host) }.isSuccess
        fields.addView(TextView(this).apply { text=if(configured) "개발용 HTTPS 주소가 설정되어 있어요.\n차량의 6자리 코드만 입력하세요." else "개발용 HTTPS 주소를 먼저 설정하세요."; textSize=13f })
        fields.addView(code)
        fields.addView(Button(this).apply {
            text="연결 주소 변경";setOnClickListener { address.visibility=android.view.View.VISIBLE;address.requestFocus() }
        })
        address.visibility=if(configured) android.view.View.GONE else android.view.View.VISIBLE
        fields.addView(address)
        fun rememberAddress() {
            val candidate=address.text.toString().trim()
            if(runCatching { SecureRelayEndpoint(candidate) }.isSuccess) {
                // Public endpoint only; pairing codes and authorization tokens are never persisted.
                if(candidate!=host) token="" // Never send an old destination's authorization to a changed host.
                host=candidate;getPreferences(MODE_PRIVATE).edit().putString("host",host).apply()
            }
        }
        val d=AlertDialog.Builder(this).setTitle("HTTPS 차량 연결").setView(fields).setNegativeButton("취소",null).setPositiveButton("연결",null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val endpoint=address.text.toString().trim();val secret=code.text.toString().trim()
            if(runCatching { SecureRelayEndpoint(endpoint) }.isFailure) { address.visibility=android.view.View.VISIBLE;address.error="HTTPS 도메인 주소를 확인하세요";address.requestFocus();return@setOnClickListener }
            rememberAddress()
            if(!secret.matches(Regex("[0-9]{6}"))) { code.error="6자리 숫자를 입력하세요";return@setOnClickListener }
            if(pairing) return@setOnClickListener
            pairing=true; val revision=++generation;d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            runCatching { worker.execute {
                val result=runCatching { redeem(endpoint,secret) }
                runOnUiThread {
                    if(revision!=generation || isFinishing || isDestroyed) return@runOnUiThread
                    pairing=false
                    result.onSuccess { key ->
                        walking.pause();host=endpoint;token=key;getPreferences(MODE_PRIVATE).edit().putString("host",host).apply()
                        WatchLiveState.message="차량 연결 확인 · 측정 시작 전";d.dismiss()
                    }.onFailure { code.error="코드 만료/연결 실패 · 차량에서 새 코드 생성";d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true }
                }
            } }.onFailure { pairing=false;code.error="이전 요청 정리 중 · 잠시 후 재시도";d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true }
        } }
        d.setOnDismissListener { rememberAddress();generation++;pairing=false };d.show()
        if(configured) code.requestFocus()
        d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun redeem(host:String,code:String): String {
        val c=wifi.open(SecureRelayEndpoint(host).url("/pair-watch"))
        val deadline=java.util.Timer(true)
        deadline.schedule(object:java.util.TimerTask() { override fun run() { c.disconnect() } },8000)
        try {
            c.requestMethod="POST";c.connectTimeout=3000;c.readTimeout=3000;c.instanceFollowRedirects=false;c.doOutput=true
            c.setRequestProperty("Content-Type","application/json")
            val bytes=JSONObject().put("code",code).toString().toByteArray();c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) };check(c.responseCode==200)
            val body=c.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(256)
                while(true) { val count=input.read(buffer);if(count<0) break;check(out.size()+count<=1024);out.write(buffer,0,count) }
                out.toByteArray()
            }
            val raw=String(body,Charsets.UTF_8);com.example.afterglow.net.BoundedRelayJson.requireObject(raw,1024,2)
            val j=JSONObject(raw);check(j.getBoolean("ok"))
            return j.getString("token").also { check(it.matches(Regex("[0-9a-f]{32}"))) }
        } finally { deadline.cancel();c.disconnect() }
    }
    private fun begin() {
        if(!resumed) return
        if(token.isEmpty()) { configure();return }
        if(checkSelfPermission(Manifest.permission.BODY_SENSORS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS),10);return
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11);return
        }
        runCatching { startForegroundService(Intent(this,LiveMeasurementService::class.java).putExtra("host",host).putExtra("token",token)) }
            .onFailure { WatchLiveState.message="측정 시작 실패 · 센서 권한 확인" }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,results)
        if(requestCode==12) {
            if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED && resumed) walking.start()
            else WatchLiveState.message="걸음 측정은 신체 활동 권한이 필요해요"
            return
        }
        if(requestCode in 10..11 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED && resumed) begin()
        else WatchLiveState.message="센서·알림 권한을 허용한 뒤 시작하세요"
    }
    override fun onResume() { super.onResume();resumed=true;runCatching { wifi.start() }.onFailure { WatchLiveState.message="인터넷 사용 권한 확인" };refresh.run() }
    override fun onPause() { resumed=false;walking.pause();generation++;pairing=false;handler.removeCallbacks(refresh);wifi.close();super.onPause() }
    override fun onDestroy() { walking.close();generation++;worker.shutdownNow();handler.removeCallbacksAndMessages(null);wifi.close();super.onDestroy() }
}
