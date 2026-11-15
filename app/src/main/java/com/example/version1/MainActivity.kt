package com.example.version1

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.lang.ref.WeakReference

/** The home screen has one primary action. Recovery has no countdown or scheduled stop. */
class MainActivity : Activity() {
    private val palette by lazy { PleosPalette.forNight(
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES) }
    private val bg get() = palette.background
    private val panel get() = palette.surface
    private val raised get() = palette.raised
    private val ink get() = palette.text
    private val muted get() = palette.secondary
    private val mint get() = palette.accent
    private val mintInk get() = palette.accentInk
    private val amber get() = palette.warning
    private val scope = MainScope()
    // Debug-only timestamps, no input coordinates or personal data. Actual click may
    // be posted after ACTION_UP; measuring dispatch alone misses that Main work.
    private var debugButtonEntered = 0L
    private var debugButtonHapticDone = 0L
    private var debugButtonFinished = 0L
    private var debugModeSoundStarted = 0L
    private var debugModeSoundFinished = 0L
    private var debugModeRenderStarted = 0L
    private var debugModeRenderFinished = 0L
    // Fixed stage durations only; no profile, preference value or health content.
    private val debugModePreparation = LongArray(4)
    private var startupBlocked = false
    private val prefs by lazy { ProtectedPreferences.get(this, "comfort_preferences") }
    private val vehicleStore by lazy { VehicleSelectionStore(this) }
    private val driverStore by lazy { DriverProfileStore(this) }
    private val zeroTap by lazy { ZeroTapStore(this) }
    private var driverSettings = DriverProfileSettings()
    private val preferenceEditors = PreferenceEditorTracker()
    private val learningStore by lazy { ComfortLearningStore(this) }
    private val learningWrites = LearningWriteCoordinator.shared
    private val learningPrivacy by lazy { LearningPrivacyStore(this, learningStore) }
    private var privacyRecovery: Job? = null
    private var privacyRefreshOwner: UiPanel? = null
    private var learningStoragePrepared = false
    private var learningStoragePreparationFailed = false
    private var learningStoragePreparation: Job? = null
    private var comfortMode = ComfortMode.AUTOMATIC
    private var learningConsent = false
    private var learningSession: String? = null
    private var pendingComfortRating: ComfortCase? = null
    private var ratingSaving = false
    private var learningStarted = 0L
    private var lastObservedSetting: LearnedSetting? = null
    private var learnedDecision = AdaptiveDecision(null, 0, "개인화 기록 없음 · 기본 선호")
    private var learnedCount = 0
    private var learningCases: List<ComfortCase> = emptyList()
    private var learningQueryBusy = false
    private var lastLearningQuery = 0L
    @Volatile private var learningRevision = 0L
    private var weather: ComfortWeather? = null
    private var weatherPlace: WeatherPlace? = null
    private var weatherMessage = "날씨 지역 미설정"
    private var weatherBusy = false
    private var weatherLocationJob: Job? = null
    private var weatherLocationTracker: WeatherLocationTracker? = null
    private var weatherFetchJob: Job? = null
    private var weatherFetchGeneration=0L
    private var latestWeatherPosition: WeatherPlace? = null
    private var weatherPositionVerified=false
    private var weatherPositionElapsed=0L
    private var weatherTrackerAttemptElapsed=0L
    private var weatherLocationGeneration = 0L
    private var pendingWeatherLocationPermission = false
    private var lastWeatherLocationAttempt = 0L
    private var lastWeatherAttempt = 0L
    private lateinit var learningLabel: TextView
    private val comfortModeButtons=linkedMapOf<ComfortMode,Button>()
    private lateinit var driverHeader: LinearLayout
    private var driverColumns: LinearLayout? = null
    private lateinit var driverInfo: LinearLayout
    private var appliedDriverSide: Boolean? = null
    private lateinit var drivingShade:FrameLayout
    private lateinit var drivingNotice:TextView
    private lateinit var homeContent:View
    private var lastPairedIdentity: PairedDriverIdentity? = null
    private var vehiclePreferences = VehicleSelectionPreferences()
    private var visualVariants: List<VehicleVisualVariant> = emptyList()
    private var vehicleIdentityState: VehicleIdentityState = VehicleIdentityState.Unavailable("차량 정보 조회 대기")
    private var vehicleReader: VehicleIdentityReader? = null
    private var vehicleReaderGeneration = 0L
    private var vehicleSettingsDialog: UiPanel? = null
    private var refreshVehicleSettings: (() -> Unit)? = null
    private var appUpdatePanel: UiPanel? = null
    private var refreshAppUpdatePanel: (() -> Unit)? = null
    private val secretStore by lazy { PairingSecretStore(this) }
    private lateinit var controller: PleosRecoveryController
    private lateinit var safety: CarSafetyGate
    private lateinit var sound: AmbientSound
    private lateinit var scene: PremiumCabinView
    private lateinit var modeLabel: TextView
    private lateinit var watchConnectionLabel: TextView
    private lateinit var sceneStatus: TextView
    private lateinit var vehicleLabel: TextView
    private lateinit var workoutLabel: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var planLabel: TextView
    private lateinit var driverLabel: TextView
    private val externalMusic by lazy { ExternalMusic(this) }
    private val emulatorConsole by lazy { EmulatorConsoleSession(this) }
    private var lastAutomaticRestoreAt = 0L
    private fun musicSource() = runCatching { MusicSource.valueOf(prefs.getString(preferenceKey("music_source"), MusicSource.AMBIENT.name)!!) }.getOrDefault(MusicSource.AMBIENT)
    private fun musicAutoResumeEnabled(provider: MusicSource) = provider != MusicSource.AMBIENT &&
        prefs.getBoolean(preferenceKey("music_auto_resume_${provider.name}"), false)
    private var autoMusicResume: AutoMusicResume? = null
    private var autoMusicGeneration = 0L
    private var autoMusicStatus = ""
    private lateinit var watchDetailLabel: TextView
    private lateinit var workoutHistoryCountLabel: TextView
    private lateinit var workoutHistoryRowsLabel: TextView
    private var historyRowsRenderKey=""
    private lateinit var telemetryLabel: TextView
    private lateinit var resultLabel: TextView
    private lateinit var autoMusicNotice: TextView
    private lateinit var restoreAction: Button
    private lateinit var settingsButton: Button
    private lateinit var connectButton: Button
    private lateinit var footerLabel: TextView
    private var modal: UiPanel? = null
    private var receiver: WatchReceiver? = null
    private var bluetoothHealthServer: BluetoothHealthServer? = null
    private var bluetoothStatus = "Bluetooth 직접 연결 꺼짐"
    private var receiverGeneration = 0L
    private var receivedWorkoutHistory: com.example.afterglow.net.WorkoutHistorySnapshot? = null
    private var workoutHistoryReceivedAt = 0L
    private var workoutInbox = RecoveryWorkoutInbox()
    private val recentWorkout: RecentWorkout? get() = workoutInbox.receivedWorkout
    private val pendingImportedWorkout: RecentWorkout? get() = workoutInbox.pendingImport
    private var selectedImportedWorkout: RecentWorkout? = null
    private var telemetry: CabinTelemetry? = null
    private var driverSeatState: DriverSeatState = DriverSeatState.Loading
    private val driverBinding: DriverSeatBinding? get() = (driverSeatState as? DriverSeatState.Available)?.binding
    private var driverSeatReader: DriverSeatReader? = null
    private var driverSeatGeneration = 0L
    private var workout = Workout(ActivityKind.RUN, 0, false)
    private var manualActivityKind = ActivityKind.RUN
    private var source = HeartRateSource.MANUAL
    private var watchBpm: Int? = null
    private var watchReceivedAt = 0L
    private var acceptedHealthAt = 0L
    private var followWorkout = true
    private var manualFeeling: ThermalFeeling? = null
    private var preferredTemperature = 24f
    private var preferredFan: Int? = null
    private var soundPreset = SoundPreset.WAVES
    private var lastEnabledSound = SoundPreset.WAVES
    private var recommendation = RecoveryPlan.recommend(ComfortRequest(workout))
    private var plan = recommendation.plan
    private var active = false
    private var busy = false
    private var parked = false
    private var lastKnownGear:VehicleGear?=null
    private var foreground = false
    private var sceneRunning = false
    private val foregroundGate = ForegroundResourceGate(Build.VERSION.SDK_INT >= 29)
    private var screenPower: ScreenPowerMonitor? = null
    private var soundPlaying = false
    private var progress = "차량 응답 확인 중"
    private var result = ""
    private var poll: Job? = null
    private var pendingBackgroundEnrollment: Pair<String, String>? = null
    private var interactionLatency: InteractionLatency? = null
    private var modalBackgroundAccessibility: Int? = null
    private var modalBackgroundView: View? = null
    private var modalBackgroundFocusability: Int? = null
    private var modalPreviousFocus: View? = null
    private var modalBackgroundScrolls: List<Triple<ScrollView, Int, Int>> = emptyList()
    private var modalBackgroundGeneration = 0L
    private var cancelInlineBack: (() -> Unit)? = null
    private var compactLayout = false
    private var settingsMenuCache: LinearLayout? = null
    private var settingsPanelCache: UiPanel? = null
    private var settingsPanelScrollCache: ScrollView? = null
    private var settingsPanelCacheWidth = 0
    private var settingsOpening = false
    private var settingsOpeningGeneration = 0L
    private var lastTouchDownUptime = 0L
    internal var settingsProgressDownAt = 0L
        private set
    internal var settingsProgressCommittedAt = 0L
        private set
    internal var panelCloseDownAt = 0L
        private set
    internal var panelCloseCommittedAt = 0L
        private set
    internal var panelCloseProgressDownAt = 0L
        private set
    internal var panelCloseProgressCommittedAt = 0L
        private set
    private var updateCheck: Job? = null
    private var updateCheckedThisSession = false
    private var publishedVersion: PublishedAppVersion? = null
    private var updatePrompted = false
    private var updateStatus = "업데이트를 확인할 수 있어요."
    private var updateCheckGeneration = 0L
    private var driverWriteInFlight = false
    private fun driverDirectoryWritePending() = driverWriteInFlight || DriverProfileStore.writePending
    private var driverConsentStatusView = WeakReference<TextView>(null)
    private fun canChange() = !driverDirectoryWritePending() && !vehicleControlArbiter.backgroundActive && !busy && !active && !controller.hasPending && safeToAct()
    private fun canChangeVisualization() = !driverDirectoryWritePending() && !vehicleControlArbiter.backgroundActive && !busy && safeToAct()
    private fun canChooseVehicleVisual() = canChangeVisualization() && !active
    private fun safeToAct() = parked && safety.allowed
    private fun now() = System.currentTimeMillis()
    private fun activitySelection() = RecoveryActivitySelection.resolve(manualActivityKind, followWorkout,
        recentWorkout, selectedImportedWorkout, workout.kind.takeIf { source == HeartRateSource.DEMO }, now())
    private fun recent() = activitySelection().record
    private fun preferenceKey(key: String) = driverSettings.selected.preferencePrefix + key
    private fun selectableVehicles() = VehicleVisualCatalog.selectableProfiles(visualVariants)
    private fun vehicleSelection() = VehicleSelectionResolver.resolve(vehiclePreferences, vehicleIdentityState,
        selectableVehicles().map { it.id }.toSet())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home and inline public panels contain no editors. Native editing
        // dialogs own separate IME-enabled windows via DialogImePolicy.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        updatePrompted = savedInstanceState?.getBoolean("app.updatePrompted", false) ?: false
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val protectedSettings = runCatching {
            listOf("comfort_preferences", "driver_profiles", "vehicle_selection", "zero_tap", "learning_database_keys")
                .forEach { ProtectedPreferences.get(this, it).all }
            driverStore.load()
        }
        if (protectedSettings.isFailure) {
            startupBlocked = true
            PerformanceMarks.event("storage.blocked.no-native")
            setContentView(col().apply {
                gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)); setBackgroundColor(bg)
                add(text("보호된 설정을 읽지 못했어요", 30f, amber, true).apply {
                    PleosTypography.minimumInkHeight(this, 5f)
                })
                add(text("저장 정보를 초기화하지 않았어요.", 24f), top = 20)
                add(text("차량 제어를 중단했어요. 연결 상태를 확인한 뒤 다시 실행하세요.", 24f, muted), top = 12)
                add(button("닫기") { finish() }, 80, 28)
            })
            return
        }
        interactionLatency = if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            InteractionLatency(window, true) else null
        PerformanceMarks.event("activity.create")
        window.statusBarColor = bg; window.navigationBarColor = bg

        driverSettings = protectedSettings.getOrThrow()
        if (!vehicleControlArbiter.backgroundActive) zeroTap.reconcileInterrupted(driverSettings.profiles.map { it.id })
        weatherPlace = if(automaticWeatherEnabled()) null else runCatching {
            val name = prefs.getString("weather_name", null) ?: return@runCatching null
            WeatherPlace(name, prefs.getString("weather_lat", "")!!.toDouble(), prefs.getString("weather_lon", "")!!.toDouble())
        }.getOrNull()
        followWorkout = prefs.getBoolean(preferenceKey("follow_workout"), true)
        vehiclePreferences = vehicleStore.load()
        if (!prefs.getBoolean("interior_focus_v1", false)) {
            vehiclePreferences = vehiclePreferences.copy(exteriorTransparency = 94)
            vehicleStore.save(vehiclePreferences)
            prefs.edit().putBoolean("interior_focus_v1", true).apply()
        }
        visualVariants = VehicleVisualCatalog.load(this)
        val availableVehicles = selectableVehicles()
        if (availableVehicles.isNotEmpty() && availableVehicles.none { it.id == vehiclePreferences.manualProfileId }) {
            vehiclePreferences = vehiclePreferences.copy(manualProfileId = availableVehicles.first().id)
            vehicleStore.save(vehiclePreferences)
        }
        manualActivityKind = ActivityKind.entries[prefs.getInt(preferenceKey("manual_kind"), 0).coerceIn(0, 2)]
        workout = Workout(manualActivityKind, 0, false)
        loadPreferences(); refreshRecommendation()
        safety = CarSafetyGate(this) { allowed -> runOnUiThread {
            if (!allowed) modal?.dismiss()
            if (!allowed && active) {
                active = false; if (::sound.isInitialized) sound.stop(); soundPlaying = false
                result = "주행 상태가 바뀌어 자동 Mode를 껐어요. P단 복귀 후 설정을 복원해 주세요."
            }
            if (::controller.isInitialized) render()
        } }
        controller = PerformanceMarks.measure("controller.create") {
            PleosRecoveryController(this, connectedDriver = { driverBinding },
                uxAllowed = { safety.refreshAllowed() }, log = { entry -> runOnUiThread { log(entry) } },
                ensureControlConnection = { emulatorConsole.requireReady() })
        }
        sound = AmbientSound(this).apply { onPlaybackChanged = { playing -> runOnUiThread {
            soundPlaying = playing
            if (foreground) render()
        } } }
        PerformanceMarks.measure("screen.build") { buildScreen() }
        PerformanceMarks.measure("screen.initialRender") { render() }
        screenPower = ScreenPowerMonitor(this) { interactive ->
            foregroundGate.interactive(interactive)
            reconcileForegroundResources()
        }
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                PerformanceMarks.event("ui.preDraw")
                window.decorView.post {
                    if (!isFinishing && !isDestroyed && settingsPanelCache == null)
                        PerformanceMarks.measure("settings.menu.prepare") { prepareSettingsPanel() }
                }
                return true
            }
        })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun widthDp() = ((if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.width()
        else resources.displayMetrics.widthPixels) / resources.displayMetrics.density).toInt()
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun surface(color: Int, radius: Int = 22) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 24f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = maxOf(size, 24f); setTextColor(color); includeFontPadding = false
        typeface = PleosDesign.typeface(this@MainActivity, if (bold) 700 else 400); setLineSpacing(dp(5).toFloat(), 1f)
        PleosTypography.minimumInkHeight(this,3f)
    }
    private fun pressFeedback(color: Int, radius: Int = PleosDesign.buttonMediumRadiusDp): StateListDrawable {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val channel = if (night) 255 else 0
        val pressed = androidx.core.graphics.ColorUtils.compositeColors(Color.argb(34, channel, channel, channel), color)
        // Immediate pressed/focus feedback avoids a circular ripple shader and
        // continuing RenderThread animation competing with the next panel.
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed), surface(pressed, radius))
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused),
                surface(pressed, radius).apply { setStroke(dp(2), ink) })
            addState(intArrayOf(), surface(color, radius))
        }
    }
    private fun button(value: String, click: () -> Unit) = Button(this).apply {
        text = value; textSize = 24f; isAllCaps = false; setTextColor(ink)
        typeface = PleosDesign.typeface(this@MainActivity, 600)
        PleosTypography.minimumInkHeight(this,3f)
        minHeight = dp(PleosDesign.buttonMediumHeightDp); minimumHeight = dp(PleosDesign.buttonMediumHeightDp)
        minWidth = dp(PleosDesign.buttonMediumMinWidthDp); minimumWidth = minWidth
        setPadding(dp(PleosDesign.buttonPaddingHorizontalDp), dp(PleosDesign.buttonPaddingVerticalDp), dp(PleosDesign.buttonPaddingHorizontalDp), dp(PleosDesign.buttonPaddingVerticalDp)); stateListAnimator = null; elevation = 0f
        background = pressFeedback(raised); setOnClickListener {
            if (interactionLatency != null) {
                debugButtonEntered = SystemClock.uptimeMillis()
                debugButtonFinished = 0L
                debugModeSoundStarted = 0L; debugModeSoundFinished = 0L
                debugModeRenderStarted = 0L; debugModeRenderFinished = 0L
                debugModePreparation.fill(0L)
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (interactionLatency != null) debugButtonHapticDone = SystemClock.uptimeMillis()
            try { PerformanceMarks.measure("ui.button.handler", click) }
            finally { if (interactionLatency != null) debugButtonFinished = SystemClock.uptimeMillis() }
        }
    }
    private fun toggle(value: String, checked: Boolean, large: Boolean = false,
                       changed: (Switch, Boolean) -> Unit) = Switch(this).apply {
        text = value; textSize = 24f; setTextColor(ink); minHeight = dp(80)
        typeface = PleosDesign.typeface(this@MainActivity, 600)
        compoundDrawablePadding = dp(24); background = null
        PleosSwitchStyle.apply(this, large)
        isChecked = checked
        setOnCheckedChangeListener { _, enabled ->
            if (isPressed) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            changed(this, enabled)
        }
    }
    private fun visualVariant(profile: VehicleProfile): VehicleVisualVariant? = VehicleVisualCatalog.resolve(
        visualVariants, profile.id, vehiclePreferences.visualVariantIds[profile.id], profile.modelYear)
    private fun LinearLayout.add(view: View, height: Int = -2, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { topMargin = dp(top) })
    }
    private fun buildScreen() {
        val wide = widthDp() >= 1150
        val sideBySide = widthDp() >= 780
        compactLayout = !sideBySide
        val root = col().apply { setBackgroundColor(bg); setPadding(dp(32), dp(24), dp(32), dp(20)) }
        root.add(row().apply {
                addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_ieum_mark);imageTintList=ColorStateList.valueOf(mint); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(38),dp(38)))
                addView(text("IEUM",28f,mint,true).apply { letterSpacing=.12f },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(10) })
        })
        val header=row().apply {
                listOf(ComfortMode.AUTOMATIC,ComfortMode.LEARNING).forEach { mode ->
                    val control=button(if(mode==ComfortMode.AUTOMATIC) "자동 Mode" else "학습 Mode") { selectComfortMode(mode) }.apply {
                        minWidth=0;minimumWidth=0;setPadding(dp(10),0,dp(10),0)
                    }
                    comfortModeButtons[mode]=control
                    addView(control,LinearLayout.LayoutParams(0,dp(64),1f).apply { if(mode==ComfortMode.LEARNING) marginStart=dp(10) })
                }
        }
        connectButton = button("워치 연결") {
            if (receiver?.connection != null) showConnectionDiagnostics() else configurePairing()
        }.apply { minWidth=0;minimumWidth=0;setPadding(dp(10),0,dp(10),0) }
        settingsButton = button("설정") { requestSettings() }.apply { minWidth=0;minimumWidth=0;setPadding(dp(10),0,dp(10),0) }
        header.addView(connectButton,LinearLayout.LayoutParams(dp(if(compactLayout)132 else 180),dp(64)).apply { marginStart=dp(10) })
        header.addView(settingsButton,LinearLayout.LayoutParams(dp(if(compactLayout)88 else 120),dp(64)).apply { marginStart=dp(10) })
        driverHeader = header
        root.add(header,top=12)
        val stage = col().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(panel, bg)).apply { cornerRadius = dp(28).toFloat() }
            setPadding(dp(28), dp(16), dp(28), dp(18))
        }
        vehicleLabel = text("차량 정보 확인 중", 22f, ink, true)
        sceneStatus = text("차량 상태 연결 중", 20f, muted)
        sceneStatus.visibility=View.GONE
        stage.add(vehicleLabel)
        scene = PremiumCabinView(this)
        planLabel = text("기본 선호로 준비", 22f, mint).apply {
            background = pressFeedback(raised, 18)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isFocusable = true
            maxLines=3
            setHorizontallyScrolling(true)
            setOnClickListener { if (safety.allowed && !busy) showRecoveryBrief() }
        }
        val info = col().apply {
            setPadding(dp(6), dp(if (compactLayout) 0 else 18), dp(18), dp(if (compactLayout) 8 else 12))
            driverLabel = text("", 23f, mint, true).apply { minHeight = dp(28); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }; add(driverLabel)
            learningLabel = text("자동 모드 · 기본 선호", 19f, mint).apply {
                setPadding(0, dp(12), 0, dp(12)); minHeight = dp(80)
                setOnClickListener { showLearningSettings() }
            }; add(learningLabel)
            add(row().apply {
                addView(col().apply {
                    if (!compactLayout) add(text("자동 Mode", 20f, muted, true))
                    modeLabel = text("OFF", if (compactLayout) 32f else 44f, ink, true); add(modeLabel, top = 6)
                }, LinearLayout.LayoutParams(0, -2, if (compactLayout) 1f else 1.1f))
                addView(col().apply {
                    if (!compactLayout) add(text("워치 연결", 20f, muted, true))
                    watchConnectionLabel = text("OFF", if (compactLayout) 32f else 44f, ink, true).apply {
                        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }; add(watchConnectionLabel, top = 6)
                }, LinearLayout.LayoutParams(0, -2, if (compactLayout) 1f else .9f).apply { marginStart = dp(12) })
            }, top = 84)
            watchDetailLabel = text("마지막 건강 데이터 수신 · 수신 전",20f,muted,true).apply {isSingleLine=true};add(watchDetailLabel,top=84)
            sourceLabel = text("심박 수신 전",20f,muted);add(sourceLabel,top=12)
            workoutLabel=text("최근(24시간 이내) 운동 기록",20f,muted,true);add(workoutLabel,top=84)
            workoutHistoryCountLabel=text("—건",if(compactLayout)32f else 44f,ink,true);add(workoutHistoryCountLabel,top=6)
            workoutHistoryRowsLabel=text("조회 전",19f,muted);add(workoutHistoryRowsLabel,top=12)
        }
        driverInfo = info
        if (sideBySide) stage.addView(row().apply {
            driverColumns = this
            addView(ScrollView(this@MainActivity).apply { addView(info); isVerticalScrollBarEnabled = false },
                LinearLayout.LayoutParams(0, -1, if (wide) .27f else .37f))
            addView(FrameLayout(this@MainActivity).apply {
                addView(scene, FrameLayout.LayoutParams(-1, -1))
                addView(planLabel, FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP or Gravity.END).apply { topMargin = dp(18); marginEnd = dp(18) })
            }, LinearLayout.LayoutParams(0, -1, if (wide) .73f else .63f))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        else {
            stage.add(info)
            stage.add(FrameLayout(this).apply {
                addView(scene, FrameLayout.LayoutParams(-1, -1))
                addView(planLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
                    .apply { topMargin = dp(12); marginEnd = dp(12) })
            }, 410, 12)
        }
        telemetryLabel = text("온도 —   ·   풍량 —   ·   열선 —   ·   통풍 —", 20f, ink).apply {
            setSingleLine(true)
            setPadding(dp(18), dp(18), dp(18), dp(18)); background = surface(raised, 18)
        }
        stage.add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false;isFillViewport=true
            addView(telemetryLabel,ViewGroup.LayoutParams(-2,-2))
            contentDescription="차량 공조 상태. 좌우로 밀어 전체 항목 확인"
        })
        if (sideBySide) root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(24) }) else root.add(stage, top = 20)
        resultLabel = text(result, 22f, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        root.add(resultLabel, top = 16)
        autoMusicNotice = text("", 24f, muted).apply {
            visibility = View.GONE; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.add(autoMusicNotice, top = 8)
        footerLabel = text("", 22f, muted).apply { visibility=View.GONE }
        root.add(footerLabel,top=8)
        restoreAction = button("설정 복원") { stopRecovery() }.apply { visibility = View.GONE }
        root.add(restoreAction,80,8)
        if (sideBySide) installHomeContent(root)
        else installHomeContent(ScrollView(this).apply { addView(root);isVerticalScrollBarEnabled=false })
    }
    private fun installHomeContent(content:View) {
        homeContent=content
        val host=FrameLayout(this)
        host.addView(content,FrameLayout.LayoutParams(-1,-1))
        drivingShade=FrameLayout(this).apply {
            setBackgroundColor(Color.argb(DrivingNotice.SHADE_ALPHA,0,0,0));isClickable=true;isFocusable=true
            visibility=View.GONE
            addView(row().apply {
                gravity=Gravity.CENTER
                setPadding(dp(24),dp(24),dp(24),dp(24))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_ieum_mark);imageTintList=ColorStateList.valueOf(Color.WHITE)
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(64),dp(64)))
                drivingNotice=text("차량 상태를 확인하고 있어요.",38f,Color.WHITE,true).apply {
                    setSingleLine(true);gravity=Gravity.CENTER
                    maxWidth=dp(maxOf(160,widthDp()-144))
                    // Never shrink a safety notice to fit. Use the short equivalent
                    // on narrow windows and keep the reported-DPI ink-height floor.
                    PleosTypography.minimumInkHeight(this,5f)
                }
                addView(drivingNotice,LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(16) })
            },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        }
        host.addView(drivingShade,FrameLayout.LayoutParams(-1,-1))
        setContentView(host)
    }
    private fun selectComfortMode(mode:ComfortMode) {
        if(!foreground || !safeToAct() || busy || driverDirectoryWritePending() || vehicleControlArbiter.backgroundActive) return
        if(active && mode==comfortMode) { stopRecovery();return }
        if(mode==ComfortMode.LEARNING && !learningConsent) {
            result="학습 기록 저장은 선택 동의가 필요해요.";render();showLearningSettings();return
        }
        if(active || controller.hasPending) { stopRecovery(nextMode=mode);return }
        comfortMode=mode
        modePreparation(2) { refreshRecommendation() };toggleRecovery()
    }
    private inline fun <T> modePreparation(stage:Int, work:()->T):T {
        if(interactionLatency==null) return work()
        val started=SystemClock.uptimeMillis()
        try { return work() }
        finally { debugModePreparation[stage]+=SystemClock.uptimeMillis()-started }
    }
    private fun reading(): HeartRateReading = when (source) {
        HeartRateSource.DEMO -> HeartRateReading.demo(workout.bpm)
        HeartRateSource.MANUAL -> HeartRateReading.missing()
        HeartRateSource.WATCH -> RecoveryHeartRateSelection.newestWatchReading(
            if (watchBpm != null)
                HeartRateReading.watch(watchBpm!!, (SystemClock.elapsedRealtime() - watchReceivedAt) / 1000) else null,
            recentWorkout?.heartRateAt(now()))
    }
    private fun loadPreferences() {
        learningConsent = prefs.getBoolean(preferenceKey("learning_consent"), false) &&
            !learningPrivacy.pending(driverSettings.selectedProfileId)
        comfortMode = runCatching { ComfortMode.valueOf(prefs.getString(preferenceKey("comfort_mode"), ComfortMode.AUTOMATIC.name)!!) }.getOrDefault(ComfortMode.AUTOMATIC)
        if (preferenceEditors.moveTo(PreferenceEditContext(driverSettings.selected.id, workout.kind))) {
            // A relay can change the activity while an editor is open. Its previous draft
            // belongs to the previous driver/activity even if that context returns later.
            modal?.dismiss()
        }
        val prefix = preferenceKey(workout.kind.name.lowercase(Locale.ROOT))
        preferredTemperature = prefs.getFloat("${prefix}_temperature", 24f).coerceIn(22f, 26f)
        preferredFan = prefs.getInt("${prefix}_fan", 0).takeIf { it in 2..3 }
        manualFeeling = prefs.getString("${prefix}_feeling", null)?.let { runCatching { ThermalFeeling.valueOf(it) }.getOrNull() }
        workout = workout.copy(wet = prefs.getBoolean("${prefix}_wet", false))
        soundPreset = runCatching { SoundPreset.valueOf(prefs.getString("${prefix}_sound", SoundPreset.WAVES.name)!!) }.getOrDefault(SoundPreset.WAVES)
        lastEnabledSound = if (soundPreset != SoundPreset.OFF) soundPreset else runCatching {
            SoundPreset.valueOf(prefs.getString("${prefix}_sound_last", SoundPreset.WAVES.name)!!)
        }.getOrDefault(SoundPreset.WAVES).takeUnless { it == SoundPreset.OFF } ?: SoundPreset.WAVES
    }
    private fun savePreferences() {
        val prefix = preferenceKey(workout.kind.name.lowercase(Locale.ROOT))
        prefs.edit().putFloat("${prefix}_temperature", preferredTemperature).putBoolean("${prefix}_wet", workout.wet)
            .putString("${prefix}_feeling", manualFeeling?.name).putString("${prefix}_sound", soundPreset.name)
            .putString("${prefix}_sound_last", lastEnabledSound.name)
            .putInt("${prefix}_fan", preferredFan ?: 0)
            .putBoolean(preferenceKey("follow_workout"), followWorkout).putInt(preferenceKey("manual_kind"), manualActivityKind.ordinal).apply()
    }
    private fun refreshRecommendation() {
        val selection = activitySelection()
        val record = selection.record
        if (selection.kind != workout.kind) { workout = workout.copy(kind = selection.kind); loadPreferences() }
        val defaults = AutomaticComfortPreferences.forActivity(if (record != null || source == HeartRateSource.DEMO || !followWorkout) workout.kind else null)
        recommendation = RecoveryPlan.recommend(ComfortRequest(workout.copy(bpm = reading().bpmForDisplay ?: 0),
            feeling = manualFeeling ?: defaults.feeling, preferredTemperature = preferredTemperature,
            heartRate = reading(), actualWorkoutMinutes = record?.validDurationMinutes, soundPreset = soundPreset,
            activityFromRecentRecord = record?.source == WorkoutRecordSource.WATCH_RELAY,
            usesSavedPreset = manualFeeling == null, preferredFan = preferredFan ?: defaults.preferredFan))
        plan = recommendation.plan
        learnedDecision = AdaptiveComfort.recommend(learningContext(), learningCases, now())
        if (comfortMode == ComfortMode.AUTOMATIC && learningConsent && learnedDecision.setting != null) {
            val learned = learnedDecision.setting!!
            plan = learned.asPlan()
            recommendation = recommendation.copy(plan = plan, explanation = listOf(learnedDecision.reason,
                "실내 측정값과 지역 날씨를 구분하며, 현재 실행 중에는 사용자가 바꾼 설정을 유지합니다."))
        }
        requestLearningRecommendation()
    }
    private fun fmt(value: Float) = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)
    private fun seatPlanLabel() = when {
        !plan.controlsSeat -> "시트 설정 유지"
        plan.seatCooling > 0 -> "냉각 ${plan.seatCooling}"
        plan.heat > 0 -> "열선 ${plan.heat}"
        plan.ventilation > 0 -> "통풍 ${plan.ventilation}"
        else -> "시트 꺼짐"
    } + (plan.steeringHeat?.let { " · 핸들 $it" } ?: "")
    private fun TextView.updateText(value: String) { if (text.toString() != value) text = value }
    /** Mirror placement, not language or the SDK's physical control-seat binding. */
    private fun applyDriverSideLayout(right: Boolean) {
        if (appliedDriverSide == right) return
        appliedDriverSide = right
        val direction = if (right) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        driverHeader.layoutDirection = direction
        for (index in 0 until driverHeader.childCount) {
            val child = driverHeader.getChildAt(index)
            child.layoutDirection = View.LAYOUT_DIRECTION_LTR
            val params = child.layoutParams as LinearLayout.LayoutParams
            params.marginStart = if (!right && index > 0) dp(10) else 0
            params.marginEnd = if (right && index > 0) dp(10) else 0
            child.layoutParams = params
        }
        driverColumns?.let { columns ->
            columns.layoutDirection = direction
            for (index in 0 until columns.childCount)
                columns.getChildAt(index).layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        driverInfo.setPadding(dp(if (right) 18 else 6), dp(if (compactLayout) 0 else 18),
            dp(if (right) 6 else 18), dp(if (compactLayout) 8 else 12))
    }
    private fun render() {
        if (autoMusicResume != null && (!foreground || !active || !safeToAct())) stopAutoMusic()
        vehicleControlArbiter.screen(this, foreground, busy || active || driverDirectoryWritePending(), if (foreground) ::receiveZeroTap else null)
        if (comfortModeButtons.isEmpty()) return
        val restricted=!safeToAct()
        if(::drivingShade.isInitialized) {
            val full=DrivingNotice.forState(lastKnownGear!=null,parked,safety.allowed)
            PleosTypography.safetyNotice(drivingNotice,full,
                DrivingNotice.compactForState(lastKnownGear!=null,parked,safety.allowed))
            drivingShade.visibility=if(restricted) View.VISIBLE else View.GONE
            homeContent.importantForAccessibility=if(restricted) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        comfortModeButtons.forEach { (mode,control) ->
            val selected=active && mode==comfortMode
            if(control.isSelected!=selected || control.tag!=selected) {
                control.isSelected=selected;control.tag=selected
                control.background=pressFeedback(if(selected) mint else raised)
                control.setTextColor(if(selected) mintInk else muted)
            }
            control.isEnabled=foreground && safeToAct() && !busy && !driverDirectoryWritePending() && !vehicleControlArbiter.backgroundActive && (active || driverBinding!=null)
            control.contentDescription="${if(mode==ComfortMode.AUTOMATIC) "자동 모드" else "학습 모드"} ${if(selected) "켜짐. 눌러서 종료" else "꺼짐. 눌러서 시작"}"
        }
        reconcileSceneSafety()
        val dockOperating = vehicleControlArbiter.backgroundActive
        scene.checkMemoryBudget()
        val vehicleSelection = vehicleSelection()
        val selectedVehicleName = if (vehicleSelection.profile.id == VehicleCatalog.GENERIC_ID) "차량 미지정" else vehicleSelection.profile.displayName
        vehicleLabel.updateText(selectedVehicleName)
        applyDriverSideLayout(vehiclePreferences.rightHandDrive)
        scene.setRightHandDrive(vehiclePreferences.rightHandDrive)
        scene.setVehicle(visualVariant(vehicleSelection.profile)?.assetId ?: vehicleSelection.profile.id)
        scene.setExteriorTransparency(vehiclePreferences.exteriorTransparency)
        val modeSummary=if(!active) "모드 OFF" else if(comfortMode==ComfortMode.LEARNING) "학습 중" else "자동 ON"
        val recordingSummary=if(learningConsent) "${learnedCount}회 기록" else "기록 OFF"
        val fullSummary="$modeSummary · $recordingSummary\n${currentWeather()?.let { "주변 기온 ${fmt(it.temperature)}°C · 습도 ${fmt(it.humidity)}%" } ?: weatherMessage}  ›"
        learningLabel.contentDescription=fullSummary
        learningLabel.updateText(if(!compactLayout && widthDp()<1150) {
            val weatherSummary=currentWeather()?.let { "${fmt(it.temperature)}°C · 습도${fmt(it.humidity)}%" } ?: "날씨 정보 없음"
            "$modeSummary\n$recordingSummary\n$weatherSummary ›"
        } else fullSummary)
        refreshVehicleSettings?.invoke()
        refreshAppUpdatePanel?.invoke()
        val record = recent()
        val automaticRunning=active && comfortMode==ComfortMode.AUTOMATIC
        val modeStatus = if (automaticRunning) "ON" else "OFF"
        modeLabel.updateText((if (compactLayout) "자동 Mode " else "") + modeStatus); modeLabel.setTextColor(if (automaticRunning) mint else ink)
        // This is the health-data relay state; a listening QR receiver or manual import
        // cannot turn it on. Expire the indicator when the relay stops sending updates.
        val bridge = receiver?.connection
        val watchConnected = receiver?.diagnostics?.hasLiveWatchSensor(now()) == true
        watchConnectionLabel.updateText((if (compactLayout) "워치 " else "") + if (watchConnected) "ON" else "OFF")
        watchConnectionLabel.setTextColor(if (watchConnected) mint else ink)
        watchConnectionLabel.contentDescription = "워치 센서 실시간 수신 ${if (watchConnected) "중" else "없음"}"
        val lastReceipt=acceptedHealthAt
        watchDetailLabel.updateText("마지막 건강 데이터 수신 · "+if(lastReceipt>0) HealthSummaryPresentation.age(now()-lastReceipt)
            else if(receiver!=null) "수신 대기" else "수신 전")
        val pulse = reading()
        sourceLabel.updateText(when {
            source == HeartRateSource.DEMO -> "샘플 심박 ${pulse.bpmForDisplay} BPM · 데모"
            source == HeartRateSource.WATCH && watchBpm != null -> "심박 $watchBpm BPM · ${HealthSummaryPresentation.age(SystemClock.elapsedRealtime()-watchReceivedAt)}"
            pulse.status == HeartRateStatus.LIVE -> "심박 ${pulse.bpmForDisplay} BPM · ${HealthSummaryPresentation.age(pulse.ageSeconds?.times(1000))}"
            else -> "심박 수신 전"
        })
        val historyRows=HealthSummaryPresentation.rows(receivedWorkoutHistory,now())
        workoutHistoryCountLabel.updateText(if(receivedWorkoutHistory==null) "—건" else "${historyRows.size}건")
        val night=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val historyText=android.text.SpannableStringBuilder()
        historyRows.forEachIndexed {index,row->
            if(index>0) historyText.append("\n")
            val start=historyText.length;historyText.append(row.text)
            if(row.recent) historyText.setSpan(android.text.style.ForegroundColorSpan(if(night) Color.rgb(255,150,150) else Color.rgb(176,35,35)),start,historyText.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if(historyRows.isEmpty()) historyText.append(if(receivedWorkoutHistory==null) when(receiver?.diagnostics?.workoutSyncStatus) {
            "ready" -> "운동 기록 자동 조회 중"
            "permission_required" -> "S23에서 운동 읽기 권한을 허용해주세요"
            "query_failed" -> "운동 기록 조회 실패 · 자동 재시도 중"
            else -> "운동 기록 연결 대기"
        } else "최근 24시간 운동 기록 없음")
        if(receivedWorkoutHistory?.truncated==true) historyText.append("\n최신 최대 24건 표시 · 조회 당시 전체 ${receivedWorkoutHistory?.totalMatches}건")
        val historyKey="$historyText|$night|${historyRows.joinToString {it.recent.toString()}}"
        if(historyRowsRenderKey!=historyKey) {workoutHistoryRowsLabel.text=historyText;historyRowsRenderKey=historyKey}
        val actual = telemetry
        val fresh = actual != null && now() - actual.readAtEpochMillis in 0..15_000 && parked
        planLabel.updateText(if (comfortMode == ComfortMode.LEARNING)
            "${if (active) "학습 관찰 중" else "학습 대기"}\n자동 공조 변경 없음\n${if (active) "만족한 시점 기록" else "직접 조절하며 학습"}"
            else "${if (active) "적용한 선호" else "추천"} ${fmt(plan.temperature)}°C\n풍량 ${plan.fan} · ${seatPlanLabel()}\n${if (musicSource() != MusicSource.AMBIENT) externalMusic.selectionLabel(musicSource()) else if (soundPreset == SoundPreset.OFF) "소리 꺼짐" else soundPreset.title}")
        planLabel.isEnabled = safety.allowed && !busy
        planLabel.contentDescription = "${planLabel.text}. 자동 Mode 설정 요약과 추천 근거 열기"
        val waitingForInput = foregroundGate.awaitingInputFocus && !foreground
        val fullStatus = when { !safety.allowed -> "정차 후 사용 가능"; !parked -> "P단 연결 확인 중"; fresh && actual!!.connected -> "P · 차량 응답 연결됨"; else -> "차량 상태 수신 중" }
        sceneStatus.updateText(if (compactLayout) when { !safety.allowed -> "정차 후 사용"; !parked -> "P단 확인 중"; fresh && actual!!.connected -> "P · 연결됨"; else -> "수신 중" } else fullStatus)
        sceneStatus.contentDescription = fullStatus
        val visibleActual = actual.takeIf { fresh }
        val controlSeat = driverBinding?.let { "${it.position.label}${if (it.manual) " · 수동 지정" else ""}" } ?: "제어 좌석 확인 중"
        telemetryLabel.updateText("${if (fresh) "현재 차량" else "수신 대기"} · $controlSeat · ${visibleActual?.driverTemperature?.let { "${fmt(it)}°C" } ?: "—"} · 풍량 ${visibleActual?.fan ?: "—"} · 시트 열선 ${visibleActual?.seatHeat ?: "—"} · 통풍 ${visibleActual?.ventilation ?: "—"} · 냉각 ${visibleActual?.seatCooling ?: "—"} · 핸들 열선 ${visibleActual?.steeringHeat ?: "—"} · 송풍 ${visibleActual?.airDirection?.label ?: "미확인"}")
        scene.update(CabinSceneState(driverTemperature = if (fresh) actual?.driverTemperature else null,
            fan = if (fresh) actual?.fan ?: 0 else 0, heat = if (fresh) actual?.seatHeat ?: 0 else 0,
            ventilation = if (fresh) actual?.ventilation ?: 0 else 0, powerOn = fresh && actual?.powerOn == true,
            active = fresh && actual?.powerOn == true, connected = fresh && actual?.connected == true,
            steeringHeat = actual?.steeringHeat.takeIf { fresh }, steeringHeatMax = actual?.steeringHeatMax ?: 3,
            seatCooling = if (fresh) actual?.seatCooling ?: 0 else 0,
            airDirection = actual?.airDirection.takeIf { fresh }))
        connectButton.updateText(when {
            watchConnected -> "워치 수신 중"
            bridge?.isRecent(now()) == true -> "휴대폰 응답 중"
            else -> "워치 연결"
        })
        connectButton.isEnabled = !dockOperating && !busy && safety.allowed
        settingsButton.isEnabled = foreground && !settingsOpening && !dockOperating && !busy && !driverDirectoryWritePending() && safeToAct() && modal == null
        val restorePending = controller.hasPending && !active && !dockOperating
        restoreAction.visibility = if (restorePending && !busy) View.VISIBLE else View.GONE
        restoreAction.isEnabled = !busy && safeToAct()
        // This action is hidden during an active mode or vehicle operation.
        // Its label requires an authenticated journal snapshot (Keystore IPC),
        // so read it only when the action is actually presented. The authoritative
        // pending check and every apply/restore transaction remain unchanged.
        if (restorePending && !busy)
            restoreAction.updateText(controller.pendingOperation?.restoreLabel ?: "설정 복원")
        val needsDriver = driverBinding == null && !active
        footerLabel.visibility = if (waitingForInput || ((restorePending || needsDriver) && !busy)) View.VISIBLE else View.GONE
        if(footerLabel.visibility==View.VISIBLE)
            footerLabel.updateText(if (waitingForInput) inputFocusNotice else if (restorePending) controller.pendingSummary.ifBlank { "P단에서 이전 설정을 복원해 주세요" }
                else if (needsDriver) "실제 운전석 위치 확인 필요 · 설정에서 확인하세요" else "")
        val visibleResult = if (dockOperating) "거치 기본 설정의 차량 응답을 확인하고 있어요."
            else if (busy) "차량의 실제 응답을 확인하고 있어요."
            else if (driverDirectoryWritePending()) "운전자 설정을 저장하고 있어요."
            else result
        resultLabel.updateText(visibleResult)
        resultLabel.visibility = if (visibleResult.isBlank()) View.GONE else View.VISIBLE
        resultLabel.setTextColor(if (controller.hasPending && !active) amber else muted)
        val musicNotice = if (foreground && active && comfortMode == ComfortMode.AUTOMATIC &&
            musicSource() != MusicSource.AMBIENT && autoMusicStatus.isNotBlank()) "${musicSource().title} · $autoMusicStatus" else ""
        autoMusicNotice.updateText(musicNotice)
        if (autoMusicNotice.contentDescription?.toString() != musicNotice) autoMusicNotice.contentDescription = musicNotice
        autoMusicNotice.visibility = if (musicNotice.isBlank()) View.GONE else View.VISIBLE
    }
    private fun showRecoveryBrief() {
        if (!safety.allowed || busy) return
        if (active && comfortMode == ComfortMode.LEARNING) { showLearningSettings(); return }
        val b = body()
        val pulse = reading()
        val record = recent()
        b.add(text("${driverSettings.selected.name} · ${workout.kind.title}", 27f, mint, true))
        b.add(text("${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())}에 확인한 상태", 20f, muted), top = 8)
        b.add(text("${if (active) "적용한 선호" else "추천 설정"}  ${fmt(plan.temperature)}°C · 풍량 ${plan.fan}\n${seatPlanLabel()} · ${soundPreset.title}", 26f), top = 18)
        b.add(text("사용한 기준", 24f, ink, true), top = 28)
        recommendation.explanation.forEach { explanation -> b.add(text(explanation, 22f, muted), top = 12) }
        b.add(text("최근 수신 데이터", 24f, ink, true), top = 28)
        val pulseEvidence = when (pulse.status) {
            HeartRateStatus.LIVE -> "심박 ${pulse.bpmForDisplay} bpm · 원본 측정 후 ${pulse.ageSeconds}초"
            HeartRateStatus.DEMO -> "데모 심박 · 실제 워치 측정 아님"
            HeartRateStatus.STALE -> "심박 표본 ${pulse.ageSeconds}초 경과 · 현재 보조 입력에서 제외"
            HeartRateStatus.INVALID -> "심박 범위 또는 측정 시각 확인 필요 · 보조 입력에서 제외"
            else -> "최근 심박 미수신 · 저장한 선호로 사용 가능"
        }
        b.add(text(pulseEvidence, 23f, mint), top = 12)
        b.add(text(record?.let { "${it.kind?.title ?: "종목 확인 필요"} · ${it.sourceLabel}\n${it.validDurationMinutes?.let { minutes -> "$minutes 분 · " } ?: ""}${it.ageSeconds(now())?.let { age -> "종료 후 ${age / 60}분" } ?: "종료 시각 미확인"}" }
            ?: "최근 운동 기록 없음 · 저장한 운동 선호 사용", 22f, muted), top = 10)
        val actual = telemetry?.takeIf { now() - it.readAtEpochMillis in 0..15_000 && parked }
        b.add(text("차량 응답", 24f, ink, true), top = 28)
        b.add(text("온도 ${actual?.driverTemperature?.let { "${fmt(it)}°C" } ?: "미확인"} · 풍량 ${actual?.fan ?: "미확인"}\n열선 ${actual?.seatHeat ?: "미확인"} · 통풍 ${actual?.ventilation ?: "미확인"} · 냉각 ${actual?.seatCooling ?: "미확인"}", 23f), top = 12)
        b.add(text(if (active) "현재 적용한 설정을 유지합니다. 새 건강 기록은 다음 실행의 추천에 반영합니다."
            else "추천과 차량 응답은 구분해 표시합니다. 실행하면 지원 범위를 확인하고 실제 적용 응답을 검사합니다.", 22f, muted), top = 18)
        dialog("회복 환경 요약", b)
    }

    private fun showAppHealth() {
        if (!safety.allowed || busy) return
        val b = body()
        val memory = ProcessMemoryReader.read()
        fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "버전 미확인"
        b.add(text("IEUM $version", 28f, mint, true))
        b.add(text("${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())}에 조회한 상태", 20f, muted), top = 8)
        b.add(text("메모리 상한 400 MB", 26f, ink, true), top = 24)
        b.add(text(memory?.let { "현재 RSS  ${mb(it.rssBytes)} MB\n현재 PSS  ${mb(it.pssBytes)} MB\n프로세스 RSS 최고 기록  ${if (it.peakRssBytes > 0) "${mb(it.peakRssBytes)} MB" else "미확인"}" }
            ?: "현재 메모리 수치를 확인할 수 없습니다.", 23f), top = 14)
        b.add(text("프로세스 내부 관측값입니다. 별도 GPU 메모리와 모든 순간의 최고값을 보증하는 검사는 아닙니다.", 21f, muted), top = 12)
        b.add(text("설치된 차량 자료  ${selectableVehicles().size}개 차종 · ${visualVariants.count { it.fullVehicle }}개 버전\n차량 설정 복원  ${if (controller.hasPending) "대기 항목 있음" else "대기 없음"}\n개인화 요약 저장  ${if (learningConsent) "동의됨 · 기기 내부" else "꺼짐"}", 23f), top = 24)
        dialog("앱 상태", b)
    }
    private fun body() = col().apply { setPadding(dp(28), dp(14), dp(28), dp(14)) }
    /** Construct the public panel detached after the first home frame. No window,
     * focus, accessibility ownership or vehicle operation changes until show(). */
    private fun prepareSettingsPanel() {
        val menu = settingsMenuBody()
        if (menu.parent != null) return
        val width = dp(minOf(1000, (widthDp() * .94f).toInt()))
        val scrolling = ScrollView(this).apply { addView(menu) }
        settingsPanelCache = createInlinePanel("설정", scrolling, width, "닫기", null, null)
        settingsPanelScrollCache = scrolling
        settingsPanelCacheWidth = width
    }
    private fun createInlinePanel(title: String, scrolling: ScrollView, width: Int,
        positive: String, negative: String?, onPositive: (() -> Unit)?): InlineUiPanel {
        val titleView = text(title, 32f, ink, true).apply { setPadding(dp(28), dp(24), dp(28), dp(14)) }
        lateinit var inline: InlineUiPanel
        fun footer(label: String, clicked: () -> Unit) = button(label, clicked).apply {
            background = pressFeedback(panel); setTextColor(mint); setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        inline = InlineUiPanel(this, title, titleView, scrolling,
            footer(positive) {
                if (positive == "닫기" && onPositive == null) requestPanelClose(inline)
                else { onPositive?.invoke(); inline.dismiss() }
            }, negative?.let { footer(it) { inline.dismiss() } }, width, surface(panel, 26))
        return inline
    }
    private fun dialog(title: String, body: View, positive: String = "닫기", onPositive: (() -> Unit)? = null,
        negative: String? = null, sensitive: Boolean = false, onDismissAction: (() -> Unit)? = null): UiPanel {
        modal?.dismiss()
        val width = dp(minOf(1000, (widthDp() * .94f).toInt()))
        val cacheable = title == "설정" && body === settingsMenuCache && !sensitive &&
            positive == "닫기" && onPositive == null && negative == null && onDismissAction == null
        val cached = settingsPanelCache?.takeIf { cacheable && settingsPanelCacheWidth == width && !it.isShowing }
        val d: UiPanel
        if (cached != null) {
            d = cached
            // Forget the previous menu focus before Android restores its viewport.
            // A focused item below the fold can otherwise undo scrollTo(0,0)
            // during the next layout on short windows.
            d.contentView.clearFocus()
            settingsPanelScrollCache?.scrollTo(0,0)
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                updateText("닫기"); contentDescription = null; isEnabled = true
            }
        } else {
        // A detached menu may be reused; never retain it in an obsolete ScrollView.
        (body.parent as? ViewGroup)?.removeView(body)
        val scrolling = ScrollView(this).apply { addView(body) }
        if (sensitive || DialogImePolicy.containsEditor(body)) {
            val titleView = text(title, 32f, ink, true).apply { setPadding(dp(28), dp(24), dp(28), dp(14)) }
            val native = AlertDialog.Builder(this).setCustomTitle(titleView).setView(scrolling)
                .setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
                .apply { if (negative != null) setNegativeButton(negative, null) }.create()
            if (sensitive) native.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            PerformanceMarks.measure("dialog.contents.create") { native.create() }
            native.window?.apply {
                setWindowAnimations(0); setBackgroundDrawable(surface(panel, 26)); setLayout(width, -2)
                DialogImePolicy.apply(this, body)
            }
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).forEach { which -> native.getButton(which)?.apply {
                textSize = 24f; minHeight = dp(80); setTextColor(mint); setPadding(dp(20), dp(12), dp(20), dp(12))
                typeface = PleosDesign.typeface(this@MainActivity, 600)
            } }
            d = NativeUiPanel(native)
        } else {
            d = createInlinePanel(title, scrolling, width, positive, negative, onPositive)
        }
            if (cacheable && d is InlineUiPanel) {
                settingsPanelCache = d; settingsPanelCacheWidth = width
                settingsPanelScrollCache = scrolling
            }
        }
        d.nativeWindow?.let { interactionLatency?.observeDialog(it) }
        d.nativeWindow?.let { dialogWindow ->
            val callback = dialogWindow.callback
            dialogWindow.callback = object : android.view.Window.Callback by callback {
                override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) lastTouchDownUptime = event.eventTime
                    interactionLatency?.input(event, observeMainFrame = false)
                    return try { callback.dispatchTouchEvent(event) } finally { interactionLatency?.handled() }
                }
            }
        }
        if (d is InlineUiPanel) d.onClosing = {
            // Register before removing the layer, not in the posted dismissal
            // notification, which can arrive after the first home frame.
            val closingDown = lastTouchDownUptime
            panelCloseDownAt = 0; panelCloseCommittedAt = 0
            val root = window.decorView
            if (Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback {
                    if (!d.isShowing) {
                        panelCloseDownAt = closingDown
                        panelCloseCommittedAt = SystemClock.uptimeMillis()
                        PerformanceMarks.event("panel.close.frameCommitted")
                        if (interactionLatency != null)
                            PerformanceMarks.inputContent("panelClose", closingDown, panelCloseCommittedAt)
                    }
                }
        }
        d.setOnDismissListener {
            if (vehicleSettingsDialog === d) { vehicleSettingsDialog = null; refreshVehicleSettings = null }
            if (appUpdatePanel === d) { appUpdatePanel = null; refreshAppUpdatePanel = null }
            if (modal === d) {
                if (d.nativeWindow != null) observeNativePanelCloseFrame(d)
                modal = null; scene.setDetailsVisible(false)
                settingsButton.isEnabled = foreground && safeToAct() && !busy && !driverDirectoryWritePending() &&
                    !vehicleControlArbiter.backgroundActive
                restorePanelBackground()
                if (driverDirectoryWritePending()) render()
            }
            onDismissAction?.invoke()
        }; modal = d; scene.setDetailsVisible(true)
        if (modalBackgroundView == null) {
            modalBackgroundView = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            modalBackgroundGeneration++
            val scrolls = mutableListOf<Triple<ScrollView, Int, Int>>()
            fun rememberScroll(view: View) {
                if (view is ScrollView) scrolls.add(Triple(view, view.scrollX, view.scrollY))
                if (view is ViewGroup) for (i in 0 until view.childCount) rememberScroll(view.getChildAt(i))
            }
            modalBackgroundView?.let(::rememberScroll)
            modalBackgroundScrolls = scrolls
            modalBackgroundAccessibility = modalBackgroundView?.importantForAccessibility
            modalBackgroundFocusability = (modalBackgroundView as? ViewGroup)?.descendantFocusability
            modalPreviousFocus = currentFocus
        }
        modalBackgroundView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        (modalBackgroundView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (d.nativeWindow == null) {
            if (Build.VERSION.SDK_INT >= 33 && cancelInlineBack == null)
                cancelInlineBack = UiPanelBackApi33.register(this) { modal?.dismiss() }
            interactionLatency?.observeInlinePanel(window.decorView)
        } else { cancelInlineBack?.invoke(); cancelInlineBack = null }
        settingsButton.isEnabled = false
        try { d.show() } catch (failure: RuntimeException) {
            if (modal === d) {
                modal = null
                restorePanelBackground()
                scene.setDetailsVisible(false)
            }
            throw failure
        }
        if(cacheable) {
            val generation=modalBackgroundGeneration
            settingsPanelScrollCache?.post {
                if(modal===d && d.isShowing && modalBackgroundGeneration==generation) {
                    settingsPanelScrollCache?.fullScroll(View.FOCUS_UP)
                }
            }
        }
        return d
    }
    private fun observeNativePanelCloseFrame(panel: UiPanel) {
        val down = lastTouchDownUptime
        panelCloseDownAt = 0; panelCloseCommittedAt = 0
        val root = window.decorView
        if (Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated) {
            root.viewTreeObserver.registerFrameCommitCallback {
                if (foreground && modal == null && !panel.isShowing) {
                    panelCloseDownAt = down; panelCloseCommittedAt = SystemClock.uptimeMillis()
                    PerformanceMarks.event("panel.close.frameCommitted")
                    if (interactionLatency != null)
                        PerformanceMarks.inputContent("panelClose", down, panelCloseCommittedAt)
                }
            }
            root.invalidate()
        }
    }
    private fun requestPanelClose(panel: UiPanel) {
        val close = panel.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        if (!panel.isShowing || !close.isEnabled || modal !== panel || !foreground) return
        // Dismiss immediately. onClosing records the actual restored-home frame;
        // there is no separate progress frame ahead of this local operation.
        panelCloseProgressDownAt = 0; panelCloseProgressCommittedAt = 0
        panel.dismiss()
    }
    private fun restorePanelBackground() {
        val background = modalBackgroundView
        val scrolls = modalBackgroundScrolls
        val generation = modalBackgroundGeneration
        modalBackgroundAccessibility?.let { modalBackgroundView?.importantForAccessibility = it }
        modalBackgroundFocusability?.let { (modalBackgroundView as? ViewGroup)?.descendantFocusability = it }
        modalBackgroundView = null; modalBackgroundAccessibility = null; modalBackgroundFocusability = null
        cancelInlineBack?.invoke(); cancelInlineBack = null
        if (foreground) modalPreviousFocus?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled }?.requestFocus()
        modalPreviousFocus = null
        modalBackgroundScrolls = emptyList()
        fun restoreViewport() {
            if (foreground && modal == null && generation == modalBackgroundGeneration)
                scrolls.forEach { (view, x, y) -> if (view.isAttachedToWindow) view.scrollTo(x, y) }
        }
        restoreViewport()
        // Focus restoration and content relayout can scroll the home after dismiss.
        // Restore its original viewport after layout, before the first visible frame.
        if (foreground && background?.isAttachedToWindow == true) {
            background.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (background.viewTreeObserver.isAlive) background.viewTreeObserver.removeOnPreDrawListener(this)
                    restoreViewport()
                    return true
                }
            })
            background.invalidate()
        }
    }

    private fun clearReceivedHealth() {
        learnedDecision = AdaptiveDecision(null, 0, "프로필 변경 · 기록 재조회"); learnedCount = 0; learningCases = emptyList()
        learningRevision++; lastLearningQuery = 0; learningSession = null; lastObservedSetting = null; pendingComfortRating = null
        receivedWorkoutHistory=null;workoutHistoryReceivedAt=0L;workoutInbox = RecoveryWorkoutInbox(); selectedImportedWorkout = null; watchBpm = null; watchReceivedAt = 0
        acceptedHealthAt = 0
        source = HeartRateSource.MANUAL
    }

    private fun persistDriverSettings(next: DriverProfileSettings): Boolean {
        // ProtectedPreferences rolls back a failed backing commit. Do not publish
        // a different in-memory identity until its durable write is confirmed.
        if (runCatching { driverStore.save(next) }.isFailure) {
            result = "운전자 설정을 저장하지 못했어요. 기존 프로필을 유지합니다."
            render()
            return false
        }
        driverSettings = next
        return true
    }

    /** Consent and phone bindings do not change identity. Keep disk/encryption
     * work off Main, publish only after durable acknowledgement, and reject an
     * intervening directory change rather than overwriting its newer state. */
    private fun persistDriverConsentAsync(next: DriverProfileSettings, completed: (Boolean) -> Unit) {
        val expected = driverSettings
        if (!canChange() || next.selectedProfileId != expected.selectedProfileId || next.profiles != expected.profiles) {
            completed(false); return
        }
        if (next == expected) { completed(true); return }
        val reservation = DriverProfileStore.reserveAsyncWrite() ?: run { completed(false); return }
        val store = driverStore
        val statusView = driverConsentStatusView.get()
        val controls = mutableListOf<Pair<View, Boolean>>()
        try {
            fun disableProfileControls(view: View) {
                if (view is Button || view is Switch) {
                    controls += view to view.isEnabled
                    view.isEnabled = false
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) disableProfileControls(view.getChildAt(index))
            }
            (statusView?.parent as? View)?.let(::disableProfileControls)
            driverWriteInFlight = true
            result = "운전자 설정을 저장하고 있어요."
            statusView?.apply { text = result; visibility = View.VISIBLE }
            // Publish the control interlock immediately, without relaying out the
            // covered home screen just to show progress in the open profile panel.
            // Closing that panel and all safety/foreground changes still render home.
            if (statusView?.isShown == true) {
                vehicleControlArbiter.screen(this, foreground, true, if (foreground) ::receiveZeroTap else null)
                settingsButton.isEnabled = false
                comfortModeButtons.values.forEach { it.isEnabled = false }
            } else render()
            val operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var acknowledged = false
                try {
                    acknowledged = withContext(Dispatchers.IO) {
                        runCatching { store.saveIfUnchanged(expected, next) }.getOrDefault(false)
                    }
                    if (acknowledged && driverSettings == expected) {
                        driverSettings = next
                        result = "운전자 설정을 저장했어요."
                    } else {
                        acknowledged = false
                        result = "운전자 설정을 저장하지 못했어요. 기존 프로필을 유지합니다."
                    }
                } finally {
                    driverWriteInFlight = false
                    reservation.close()
                    if (!isDestroyed && !isFinishing) {
                        statusView?.text = result
                        controls.forEach { (view, enabled) -> view.isEnabled = enabled }
                        render()
                        completed(acknowledged)
                    }
                }
            }
            // Idempotent fallback also covers cancellation before the first IO dispatch.
            operation.invokeOnCompletion { reservation.close() }
        } catch (failure: Throwable) {
            driverWriteInFlight = false
            reservation.close()
            controls.forEach { (view, enabled) -> view.isEnabled = enabled }
            throw failure
        }
    }

    private fun selectDriver(profileId: String, directory: DriverProfileSettings = driverSettings): Boolean {
        if (!canChange()) return false
        if (profileId == driverSettings.selectedProfileId && directory == driverSettings) return true
        if (!persistDriverSettings(directory.select(profileId))) return false
        // An open editor contains the previous driver's draft values.
        modal?.dismiss()
        stopAutoMusic()
        clearReceivedHealth()
        followWorkout = prefs.getBoolean(preferenceKey("follow_workout"), true)
        manualActivityKind = ActivityKind.entries[prefs.getInt(preferenceKey("manual_kind"), 0).coerceIn(0, 2)]
        workout = Workout(manualActivityKind, 0, false)
        loadPreferences(); refreshRecommendation()
        result = "${driverSettings.selected.name}의 회복 선호를 불러왔어요."
        render()
        return true
    }

    private fun showDriverProfiles() {
        if (!canChange()) return
        val b = body()
        b.add(text("운동 후 온도·풍량·소리를 운전자별로 저장합니다. 차량 모델을 바꿔도 이 기기에 저장한 선호를 유지해요.", 24f))
        b.add(text("운전자 설정을 저장하고 있어요.", 22f, muted).apply {
            // Reserve the single status row before an OS tap; GONE -> VISIBLE
            // otherwise remeasures the scrolling panel in the response frame.
            visibility = View.INVISIBLE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            driverConsentStatusView = WeakReference(this)
        }, top = 12)
        var reflectingConsent = false
        fun reflectConsent(control: Switch) {
            reflectingConsent = true
            try { control.isChecked = driverSettings.autoSelectPairedPhone }
            finally { reflectingConsent = false }
        }
        b.add(toggle("연결한 휴대폰으로 프로필 자동 선택", driverSettings.autoSelectPairedPhone) { control, enabled ->
            if (reflectingConsent) return@toggle
            if (canChange()) {
                val next = driverSettings.copy(autoSelectPairedPhone = enabled)
                control.isEnabled = false
                reflectConsent(control)
                persistDriverConsentAsync(next) {
                    reflectConsent(control)
                    control.isEnabled = canChange()
                }
            } else reflectConsent(control)
        }, 80, 18)
        driverSettings.profiles.forEach { profile ->
            b.add(button("${if (profile.id == driverSettings.selectedProfileId) "✓  " else ""}${profile.name}") {
                if (selectDriver(profile.id)) showDriverProfiles()
            }.apply { isSelected = profile.id == driverSettings.selectedProfileId }, 80, 12)
        }
        b.add(button("+ 새 운전자 프로필") { showAddDriverProfile() }.apply {
            isEnabled = driverSettings.profiles.size < DriverProfileSettings.MAX_PROFILES
        }, 80, 18)
        b.add(button("내 선호 내보내기") { if (canChange()) showPortableProfileExport() }, 80, 12)
        b.add(button("다른 기기의 선호 가져오기") { if (canChange()) showPortableProfileImport() }.apply {
            isEnabled = driverSettings.profiles.size < DriverProfileSettings.MAX_PROFILES
        }, 80, 12)
        val identity = lastPairedIdentity?.takeIf { it.isFresh(now()) }
        b.add(text(identity?.let { "최근 휴대폰 응답  ${it.displayName}" } ?: "프로필 연결에 사용할 휴대폰 응답을 기다립니다.", 22f, muted), top = 20)
        b.add(button("이 휴대폰을 ${driverSettings.selected.name}에 연결") {
            if (canChange() && identity != null && driverSettings.canBindPhone(identity, now())) {
                val owner = modal
                persistDriverConsentAsync(driverSettings.bindPhone(identity, now())) { saved ->
                    if (saved && foreground && owner != null && modal === owner && owner.isShowing) {
                        result = "휴대폰과 프로필을 연결했어요. 자동 선택을 켜면 다음 응답부터 적용됩니다."
                        render(); showDriverProfiles()
                    }
                }
            }
        }.apply { isEnabled = identity != null && driverSettings.canBindPhone(identity, now()) }, 80, 12)
        if (identity != null && identity.phoneId !in driverSettings.phoneBindings &&
            driverSettings.phoneBindings.size >= DriverProfileSettings.MAX_PHONE_BINDINGS) {
            b.add(text("등록 가능한 휴대폰 수에 도달했어요. 기존 휴대폰 프로필 연결을 해제한 뒤 다시 연결해 주세요.", 22f, muted), top = 12)
        }
        b.add(button("휴대폰 프로필 연결 해제") {
            if (canChange()) {
                val owner = modal
                persistDriverConsentAsync(driverSettings.unbindAllPhones()) { saved ->
                    if (saved && foreground && owner != null && modal === owner && owner.isShowing) showDriverProfiles()
                }
            }
        }.apply { isEnabled = driverSettings.phoneBindings.isNotEmpty() }, 80, 12)
        if (driverSettings.profiles.size > 1) b.add(button("현재 프로필 삭제") {
            if (canChange()) {
                val deletedProfile = driverSettings.selectedProfileId
                val remaining = driverSettings.remove(deletedProfile)
                if (runCatching { learningPrivacy.revoke(driverSettings.selected) }.isFailure) {
                    result = "학습 기록 삭제 요청을 저장하지 못했어요. 프로필 삭제를 보류했어요."; render(); return@button
                }
                learningConsent = false; learningRevision++; learningCases = emptyList(); learnedCount = 0; learningSession = null
                val cleanup = prefs.edit()
                DriverPreferenceKeys.ownedBy(driverSettings.selected).forEach { cleanup.remove(it) }
                if (!cleanup.commit()) {
                    result = "프로필 설정 삭제를 저장하지 못했어요. 다시 시도해 주세요."
                    render(); return@button
                }
                // Select through the normal path so another driver's cached health data is cleared.
                val next = remaining.selectedProfileId
                // Removal and selection are one directory commit; failed saves
                // retain the existing identity instead of publishing half a list.
                if (!selectDriver(next, remaining)) return@button
                zeroTap.remove(deletedProfile)
                if (!ZeroTapBackgroundService.enabled(this)) ZeroTapBackgroundService.stop(this)
                recoverLearningPrivacy()
                render(); showDriverProfiles()
            }
        }, 80, 12)
        b.add(text("휴대폰 연결은 운전자 생체 인증이 아니에요. 일반 연결은 프로필만 선택해요.", 22f, muted), top = 20)
        b.add(text("차량 기본 설정·메모리 시트 자동 호출은 제로탭에서 별도로 등록하세요.", 22f, muted), top = 12)
        dialog("운전자 프로필", b)
    }

    private fun showPortableProfileExport() {
        if (!canChange()) return
        val editor = preferenceEditors.capture()
        val data = PortableDriverProfileStore(this, driverStore).snapshot(driverSettings.selected)
        val code = runCatching { PortableDriverProfileCodec.encode(data) }.getOrElse {
            result = "선호를 내보내지 못했어요. 재생목록 링크를 확인해 주세요."; render(); return
        }
        val b = body()
        data.summary().lineSequence().filter(String::isNotBlank).forEach { b.add(text(it, 24f), top = 12) }
        b.add(text("다른 기기에서 이 코드를 가져오면 새 프로필을 만들어요.", 22f, muted), top = 18)
        b.add(text("건강 기록·학습 동의·휴대폰 인증·차량별 시트 위치는 포함하지 않아요.", 22f, muted), top = 12)
        b.add(text("코드에는 이름과 선호가 담겨요. 공유 전에 확인하세요.", 22f, muted), top = 12)
        if (code.length <= 1024) {
            runCatching { QrCodeFactory.create(code, 480) }.getOrNull()?.let { bitmap ->
                b.add(ImageView(this).apply { setImageBitmap(bitmap); contentDescription = "개인 선호 설정 코드 QR"; setBackgroundColor(Color.WHITE) }, 480, 18)
            }
        }
        b.add(button("설정 코드 복사") {
            if (canChange() && preferenceEditors.canCommit(editor)) {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                val clip = android.content.ClipData.newPlainText("IEUM 개인 선호", code)
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                clipboard.setPrimaryClip(clip)
                result = "설정 코드를 복사했어요. 다른 기기의 선호 가져오기에 붙여 넣으세요."; render()
            }
        }, 80, 18)
        dialog("${data.name} · 선호 내보내기", b)
    }

    private fun showPortableProfileImport() {
        if (!canChange()) return
        val editor = preferenceEditors.capture()
        val b = body()
        b.add(text("다른 기기에서 복사한 설정 코드를 붙여 넣으세요. 가져오기 전에 내용을 확인하며 기존 프로필은 덮어쓰지 않아요.", 24f), top = 12)
        val input = EditText(this).apply {
            hint = "AFTERGLOW1.로 시작하는 설정 코드"; textSize = 24f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(PortableDriverProfileCodec.MAX_CODE_CHARS)); minHeight = dp(88)
        }
        b.add(input, 96, 18)
        val d = dialog("다른 기기의 선호 가져오기", b, "내용 확인", negative = "취소")
        checkNotNull(d.getButton(AlertDialog.BUTTON_POSITIVE)).setOnClickListener {
            if (!canChange() || !preferenceEditors.canCommit(editor)) return@setOnClickListener
            val data = runCatching { PortableDriverProfileCodec.decode(input.text.toString().trim()) }.getOrNull()
            if (data == null) { input.error = "지원하는 설정 코드인지 확인해 주세요. 코드가 잘렸거나 바뀌었을 수 있어요."; return@setOnClickListener }
            showPortableProfileReview(data)
        }
    }

    private fun showPortableProfileReview(data: PortableDriverProfile) {
        if (!canChange()) return
        val editor = preferenceEditors.capture()
        val expected = driverSettings
        val b = body()
        data.summary().lineSequence().filter(String::isNotBlank).forEach { b.add(text(it, 24f), top = 12) }
        val savedName = portableImportedProfileName(data.name, expected.profiles.map { it.name })
        if (savedName != data.name) b.add(text("이 기기에서는 ‘$savedName’으로 구분해 저장해요.", 22f, muted), top = 12)
        b.add(text("이 기기에 새 프로필로 저장해요. 차량 설정은 지금 변경하지 않아요.", 22f, muted), top = 18)
        b.add(text("학습 기록과 휴대폰 연결은 새로 설정해야 해요.", 22f, muted), top = 12)
        b.add(text("다른 차량의 메모리 시트 번호는 가져오지 않아요.", 22f, muted), top = 12)
        val d = dialog("가져올 선호 확인", b, "새 프로필로 가져오기", negative = "취소")
        checkNotNull(d.getButton(AlertDialog.BUTTON_POSITIVE)).setOnClickListener {
            if (!canChange() || !preferenceEditors.canCommit(editor) || driverSettings != expected) return@setOnClickListener
            runCatching { PortableDriverProfileStore(this, driverStore).install(data, expected) }.onSuccess {
                driverSettings = it; result = "${it.profiles.last().name}의 선호를 가져왔어요. 사용할 프로필을 선택하세요."
                d.dismiss(); render(); showDriverProfiles()
            }.onFailure {
                result = "선호를 저장하지 못했어요. 운전자 목록을 다시 확인해 주세요."; render()
            }
        }
    }

    private fun showAddDriverProfile() {
        if (!canChange()) return
        val b = body()
        val input = EditText(this).apply {
            hint = "예: 나, 가족"; textSize = 26f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(24)); minHeight = dp(80)
        }
        b.add(input, 88)
        val d = dialog("새 운전자 프로필", b, "추가", negative = "취소")
        checkNotNull(d.getButton(AlertDialog.BUTTON_POSITIVE)).setOnClickListener {
            if (!canChange()) return@setOnClickListener
            val name = input.text.toString().trim()
            if (name.isBlank() || name.any { it.isISOControl() }) { input.error = "이름을 입력해 주세요"; return@setOnClickListener }
            val previous = driverSettings.selectedProfileId
            val added = driverSettings.add(name)
            if (!selectDriver(added.selectedProfileId, added.copy(selectedProfileId = previous))) return@setOnClickListener
            d.dismiss(); showDriverProfiles()
        }
    }

    private fun acceptDriverIdentity(identity: PairedDriverIdentity) {
        lastPairedIdentity = identity
        val choice = driverSettings.automaticSelection(identity, now(), mayChangePreferences = canChange())
        if (choice != null) selectDriver(choice.id)
    }

    private fun receiveZeroTap(event: ZeroTapEvent) {
        val profile = zeroTap.profileFor(event, driverSettings.profiles)
        if (profile == null) { log("제로탭 거부 · 미등록 기기"); return }
        try {
            val baseline = zeroTap.beginEvent(profile, event,
                foreground && canChange() && safeToAct() && driverBinding != null, now())
            if (!selectDriver(profile)) {
                publishZeroTapResult(profile, event.eventId, "프로필 저장 미확인 · 차량 설정 미적용")
                return
            }
            busy = true; progress = "제로탭 적용 중"; render()
            scope.launch {
                try {
                    val count = controller.applyZeroTapBaseline(baseline) { foreground && driverSettings.selectedProfileId == profile }
                    val memory = zeroTap.config(profile)?.optInt("memorySlot", -1) ?: -1
                    var memoryMessage = ""
                    var memoryFailure: String? = null
                    if (memory >= 0 && foreground && safeToAct()) {
                        try {
                            controller.requestZeroTapMemory(memory,
                                stillAllowed = { foreground && driverSettings.selectedProfileId == profile },
                                onFailure = { failure -> runOnUiThread {
                                    memoryFailure = failure
                                    publishZeroTapResult(profile, event.eventId, "기본 설정 $count 항목 확인 · 메모리 시트 요청 실패")
                                } })
                            memoryMessage = if (memoryFailure != null) " · 메모리 시트 요청 실패"
                                else " · 메모리 $memory 요청(이동 완료 미확인)"
                        } catch (error: Exception) { memoryMessage = " · 메모리 시트 미적용: ${error.message}" }
                    }
                    publishZeroTapResult(profile, event.eventId, "기본 설정 $count 항목 확인$memoryMessage")
                } catch (error: Exception) {
                    publishZeroTapResult(profile, event.eventId, "적용 중단 · ${error.message}")
                    runCatching { controller.restore() }.onFailure { log("제로탭 복원 대기 · ${it.javaClass.simpleName}") }
                } finally {
                    busy = false
                    telemetry = runCatching { controller.cabinTelemetry() }.getOrNull()
                    refreshRecommendation(); render()
                }
            }
        } catch (error: Exception) {
            // A rejected/replayed caller must not replace the last successful application status.
            log("제로탭 거부 · ${error.javaClass.simpleName}")
        }
    }

    private fun publishZeroTapResult(profile: String, eventId: String, summary: String) {
        if (!zeroTap.isCurrentEvent(profile, eventId)) return
        // Status storage is not the vehicle transaction. A disk error after verified commit
        // must not turn a successful baseline into a false 'rolled back' message.
        val saved = runCatching { zeroTap.recordResult(profile, eventId, summary) }
            .onFailure { log("제로탭 결과 저장 실패 · ${it.javaClass.simpleName}") }.getOrDefault(false)
        val message = "제로탭 · $summary" + if (saved) "" else " · 결과 저장 미확인"
        if (driverSettings.selectedProfileId == profile) { result = message; render() }
        log(message)
    }

    private fun showZeroTap() {
        if (!canChange()) return
        val profile = driverSettings.selectedProfileId
        val cfg = zeroTap.config(profile)
        val b = body()
        b.add(text("등록한 휴대폰이 거치 신호를 보내면 P단에서 기본 설정을 적용해요.", 23f))
        b.add(text("일반 연결이나 심박 전송만으로 실행되지 않아요.", 22f, muted), top = 12)
        b.add(text("최근 결과 · ${zeroTap.status(profile)}", 22f, muted), top = 18)
        b.add(button(if (cfg == null) "현재 차량 설정으로 휴대폰 등록" else "다시 등록 · 현재 차량 설정 저장") {
            if (!canChange() || driverSettings.selectedProfileId != profile) return@button
            modal?.dismiss(); busy = true; progress = "기본 설정 읽는 중"; render()
            scope.launch {
                try {
                    val baseline = controller.captureZeroTapBaseline()
                    check(foreground && safeToAct() && driverSettings.selectedProfileId == profile)
                    val registration = zeroTap.enroll(profile, baseline)
                    if (!ZeroTapBackgroundService.enabled(this@MainActivity)) ZeroTapBackgroundService.stop(this@MainActivity)
                    prefs.edit().putBoolean("bridge_allowed", true).apply()
                    startReceiver()
                    val instructions = registration.toString(2)
                    val details = body()
                    details.add(text("휴대폰에 저장할 연결 정보를 준비했어요.", 24f))
                    details.add(text("아래 버튼으로 복사해 휴대폰 단축어에 저장하세요. 재등록하면 이전 정보는 무효가 돼요.", 22f), top = 12)
                    details.add(text("등록 정보에는 연결 권한이 있어요. 다른 사람과 공유하지 마세요.", 22f, muted), top = 12)
                    details.add(button("휴대폰 등록 정보 복사") {
                        if (canChange() && driverSettings.selectedProfileId == profile &&
                            zeroTap.config(profile)?.optString("phoneId") == registration.optString("phoneId")) {
                            val clip = android.content.ClipData.newPlainText("IEUM 휴대폰 등록", instructions)
                            clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
                            result = "휴대폰 등록 정보를 복사했어요."; render()
                        }
                    }, 80, 18)
                    dialog("제로탭 휴대폰 등록 정보", details, sensitive = true)
                } catch (error: Exception) { result = "제로탭 등록 실패 · ${error.message}" }
                finally { busy = false; render() }
            }
        }, 80, 18)
        if (cfg != null) {
            b.add(button("휴대폰 중계 연결 코드") { showAppleInternetPairing(forZeroTap = true) }, 80, 12)
            b.add(toggle("이 휴대폰의 제로탭 자동 적용", cfg.optBoolean("enabled")) { control, enabled ->
                if (canChange() && driverSettings.selectedProfileId == profile) {
                    zeroTap.config(profile)?.let { latest ->
                        latest.put("enabled", enabled)
                        if (!enabled) latest.put("backgroundEnabled", false)
                        zeroTap.save(profile, latest)
                        if (!ZeroTapBackgroundService.enabled(this)) ZeroTapBackgroundService.stop(this)
                        showZeroTap()
                    }
                } else control.isChecked = cfg.optBoolean("enabled")
            }, 80, 12)
            b.add(toggle("다른 앱을 보는 동안에도 거치 연결", cfg.optBoolean("backgroundEnabled")) { control, enabled ->
                val latest = zeroTap.config(profile)
                if (!canChange() || !safeToAct() || driverSettings.selectedProfileId != profile || latest == null) {
                    control.isChecked = latest?.optBoolean("backgroundEnabled") == true
                } else if (!enabled) {
                    zeroTap.save(profile, latest.put("backgroundEnabled", false))
                    if (!ZeroTapBackgroundService.enabled(this)) ZeroTapBackgroundService.stop(this)
                    showZeroTap()
                } else if (!latest.optBoolean("enabled")) {
                    control.isChecked = false; result = "제로탭 자동 적용을 먼저 켜주세요."; render()
                } else if (!ZeroTapBackgroundService.notificationsAllowed(this)) {
                    control.isChecked = false
                    pendingBackgroundEnrollment = profile to latest.getString("phoneId")
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 412)
                } else enableBackgroundDock(profile, latest.getString("phoneId"))
            }, 80, 12)
            b.add(text("켜면 연결 알림을 유지하고, 다른 앱을 보는 동안에도 거치 신호를 받습니다. 심박·운동 데이터는 차량 앱이 열린 동안에만 받습니다.", 22f, muted), top = 12)
            b.add(button("메모리 시트 번호 · ${cfg.optInt("memorySlot", -1).takeIf { it >= 0 } ?: "사용 안 함"}") {
                showMemorySlotPicker(profile)
            }, 80, 12)
            b.add(button("제로탭 등록 해제") {
                if (canChange() && driverSettings.selectedProfileId == profile) {
                    zeroTap.remove(profile)
                    if (!ZeroTapBackgroundService.enabled(this)) ZeroTapBackgroundService.stop(this)
                    showZeroTap()
                }
            }, 80, 12)
        }
        b.add(text("기본 설정은 이 차량·운전석에만 적용됩니다. 메모리 시트는 별도로 선택하고, 이동 완료는 직접 확인해 주세요. 자동 Mode를 끄면 제로탭 적용 후의 기본 설정으로 돌아옵니다.", 22f, muted), top = 18)
        dialog("제로탭", b)
    }

    private fun showMemorySlotPicker(profile: String) {
        if (!canChange() || !safeToAct() || driverSettings.selectedProfileId != profile) return
        val phone = zeroTap.config(profile)?.optString("phoneId") ?: return
        modal?.dismiss(); busy = true; progress = "메모리 시트 지원 확인 중"; render()
        scope.launch {
            val query = runCatching { controller.zeroTapMemorySlots().take(16) }
            busy = false; render()
            if (!foreground || !safeToAct() || !canChange() || driverSettings.selectedProfileId != profile ||
                zeroTap.config(profile)?.optString("phoneId") != phone) return@launch
            val slots = query.getOrElse {
                result = "메모리 시트 지원 번호를 확인하지 못했어요."; render()
                val info = body()
                info.add(text("메모리 시트 지원 여부를 확인할 수 없어요. 기본 공조 설정은 별도로 사용할 수 있어요.", 23f))
                dialog("메모리 시트", info)
                return@launch
            }
            val b = body()
            fun select(slot: Int?) {
                if (!foreground || !safeToAct() || !canChange() || driverSettings.selectedProfileId != profile) return
                val latest = zeroTap.config(profile)?.takeIf { it.optString("phoneId") == phone } ?: return
                if (slot == null) latest.remove("memorySlot") else latest.put("memorySlot", slot)
                zeroTap.save(profile, latest); showZeroTap()
            }
            b.add(button("사용 안 함") { select(null) }, 80)
            slots.forEach { slot -> b.add(button("$slot 번") { select(slot) }, 80, 12) }
            b.add(text("번호만 저장합니다. 지금 시트를 이동하지 않아요.", 22f, muted), top = 16)
            dialog("이 차량의 운전석 메모리", b)
        }
    }

    private fun enableBackgroundDock(profile: String, phone: String) {
        val latest = zeroTap.config(profile) ?: return
        if (!foreground || !canChange() || !safeToAct() || driverSettings.selectedProfileId != profile ||
            latest.optString("phoneId") != phone || !latest.optBoolean("enabled")) return
        try {
            zeroTap.save(profile, latest.put("backgroundEnabled", true))
            ZeroTapBackgroundService.start(this)
            result = "거치 전용 연결을 켰어요. 다른 앱에서도 P단·등록 차량·운전석을 확인한 뒤 적용해요."
        } catch (error: Exception) {
            zeroTap.save(profile, latest.put("backgroundEnabled", false))
            result = "거치 연결을 시작하지 못했어요. ${error.message}"
        }
        render(); showZeroTap()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 412) {
            // The permission UI temporarily removes top-resumed ownership. Resume only
            // after actual foreground/P/driver readiness returns; retain enrollment identity.
            if (!ZeroTapBackgroundService.notificationsAllowed(this)) pendingBackgroundEnrollment = null
        }
        if (requestCode == 413) {
            pendingWeatherLocationPermission = WeatherLocation.permitted(this)
            if (!pendingWeatherLocationPermission) {
                prefs.edit().putBoolean("weather_location_enabled",false).apply()
                result = "위치 권한 없이도 도시를 직접 선택할 수 있어요."
                if(foreground) render()
            }
        }
        if(requestCode==414) {
            result=if(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED)
                "Bluetooth 권한을 허용했어요. 연결 관리에서 Bluetooth 직접 연결을 다시 눌러주세요."
                else "Bluetooth 연결 권한이 필요해요. 기존 인터넷 연결은 유지됩니다."
            if(foreground) render()
        }
    }

    private fun acceptsHealthIdentity(identity: PairedDriverIdentity?): Boolean {
        val bound = identity?.let { driverSettings.phoneBindings[it.phoneId] }
        if (bound == null && driverSettings.autoSelectPairedPhone && driverSettings.phoneBindings.isNotEmpty()) {
            result = "프로필에 연결되지 않은 휴대폰 데이터예요. 운전자 프로필의 휴대폰 연결을 확인해 주세요."
            render(); return false
        }
        if (bound != null && bound != driverSettings.selectedProfileId) {
            result = "다른 운전자의 휴대폰 데이터예요. 현재 프로필의 회복 설정을 유지합니다."
            render(); return false
        }
        return true
    }

    private fun showWorkoutHistory() {
        val b=body();val history=receivedWorkoutHistory
        fun stamp(value: Long)=SimpleDateFormat("MM.dd HH:mm",Locale.KOREA).format(Date(value))
        b.add(text("휴대폰 Health Connect에 공유된 기록이에요. 삼성 헬스와 동기화되지 않은 기록은 포함되지 않아요.",23f,muted))
        if(history==null) b.add(text("아직 24시간 운동 조회 결과를 받지 않았어요. S23 중계 앱에서 운동 읽기를 허용하고 전송하세요.",25f,mint),top=18)
        else {
            b.add(text("조회 구간 ${stamp(history.windowEnd-86_400_000)} ~ ${stamp(history.windowEnd)}\n차량 수신 ${stamp(workoutHistoryReceivedAt)}",23f,muted),top=16)
            b.add(text("조회 ${history.totalMatches}건 · 표시 ${history.entries.size}건"+(if(history.truncated) " · 최신24건만 표시" else ""),25f,mint),top=16)
            if(now()-history.windowEnd>=120_000) b.add(text("이 목록은 마지막 수신 당시의 조회 결과예요. 폰 앱에서 다시 동기화하세요.",23f,muted),top=14)
            if(history.entries.isEmpty()) b.add(text("조회 구간 내 공유된 완료 운동이 없어요. 운동 읽기 권한을 허용한 실제 조회 결과입니다.",25f,mint),top=20)
            history.entries.forEach { item ->
                val label=when(item.kind) { "run"->"달리기";"swim"->"수영";"hike"->"하이킹";"walk"->"걷기";"cycle"->"자전거";"strength"->"근력 운동";else->"기타 운동 (유형 ${item.type})" }
                b.add(text(label+(if(item.manual) " · 수동 입력" else ""),27f,mint,true),top=24)
                b.add(text("${stamp(item.startedAt)} ~ ${stamp(item.endedAt)}\n기록 구간 ${(item.endedAt-item.startedAt)/60_000}분",23f,muted),top=8)
                b.add(text("기록 앱 ${item.origin}",20f,muted),top=6)
            }
            b.add(text("24시간 목록 조회와 자동 추천은 별개예요. 오래된 운동이나 수동 입력만으로 차량 설정을 자동 적용하지 않아요.",23f,muted),top=24)
        }
        dialog("최근 24시간 운동 기록",b,sensitive=true)
    }
    private fun showConnectionDiagnostics() {
        val bridge = receiver
        val state = bridge?.diagnostics
        fun stamp(value: Long?) = value?.takeIf { it > 0 }?.let {
            SimpleDateFormat("MM.dd HH:mm:ss", Locale.KOREA).format(Date(it))
        } ?: "아직 없음"
        val b = body()
        b.add(text("수신 서버  ${if (bridge != null) "열림" else "닫힘"}\n휴대폰 응답  ${if (bridge?.connection?.isRecent(now()) == true) "최근 응답 확인" else "확인 전"}", 25f))
        b.add(text("네이티브 건강 기록 수신  ${stamp(state?.lastNativeHealthAtEpochMillis)}\n아이폰 단축어 심박 수신  ${stamp(state?.lastShortcutHealthAtEpochMillis)}", 23f, mint), top = 20)
        b.add(text("심박 출처  ${state?.lastHeartRateSource ?: "아직 없음"}\n현재 프로필 수신값  ${watchBpm?.let { "$it bpm" } ?: "아직 없음"}\n차량 심박 수신 시각  ${stamp(state?.lastHeartRateReceivedAtEpochMillis)}", 22f, muted), top = 18)
        state?.latestWatchActivity?.let { record ->
            b.add(text("갤럭시 워치 걸음  ${record.stepsSinceBaseline?.let { "센서 기준점 이후 ${it}걸음" } ?: "센서 표본 없음"}\n걸음 표본  ${stamp(record.stepsMeasuredAt)}\n차량 활동 수신  ${stamp(state.watchActivityReceivedAt)}", 22f, muted), top = 18)
            b.add(text("사용자 걷기 세션  ${if (record.endedAt != null) "종료" else "진행 중 마지막 보고"} · ${((record.endedAt ?: record.observedAt) - record.startedAt) / 1000}초\n삼성 헬스 이력·자동 운동 감지 아님 · 하루 총걸음 아님", 22f, muted), top = 18)
        }
        state?.latestWorkout?.let { receipt ->
            b.add(text("운동 출처  ${receipt.source}\n종료 시각  ${stamp(receipt.endedAtEpochMillis)}\n차량 수신  ${stamp(receipt.receivedAtEpochMillis)}", 22f, muted), top = 18)
        }
        b.add(text("심박 표본 시각  ${stamp(state?.lastHeartRateMeasuredAtEpochMillis)}\n${state?.heartRateAgeSeconds(now())?.let { "측정 후 ${it}초 · ${if (it < 30) "최근 심박" else "현재 심박으로 사용하지 않음"}" } ?: "수신 표본 없음"}", 22f, muted), top = 18)
        b.add(text("QR이 보이거나 휴대폰 페이지만 열렸다고 건강 데이터가 연결된 것은 아닙니다. 실제 전송 후 위 수신 시각과 휴대폰의 응답을 확인하세요.", 23f, muted), top = 20)
        b.add(text(bluetoothStatus,23f,muted),top=20)
        b.add(button("연결 관리 · 기기 추가") { configurePairing() }, 80, 20)
        dialog("실제 연결 진단", b)
    }
    private fun requestSettings() {
        if (!foreground || settingsOpening || !safeToAct() || busy || driverDirectoryWritePending() || modal != null || vehicleControlArbiter.backgroundActive) {
            PerformanceMarks.event("settings.request.rejected.notReady")
            return
        }
        settingsOpening = true
        val generation = ++settingsOpeningGeneration
        val downAt = lastTouchDownUptime
        settingsProgressDownAt = 0; settingsProgressCommittedAt = 0
        settingsButton.isEnabled = false
        scene.setDetailsVisible(true)
        try {
            // The cached menu is local and bounded: present its real content in
            // the next frame instead of spending a separate frame on progress.
            PerformanceMarks.measure("settings.navigation.dispatch") { showSettings() }
            val owner = modal
            val root = owner?.nativeWindow?.decorView ?: window.decorView
            if (owner != null && Build.VERSION.SDK_INT >= 29) {
                root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
                        if (modal === owner && owner.isShowing && root.isHardwareAccelerated)
                            root.viewTreeObserver.registerFrameCommitCallback {
                                if (modal === owner && owner.isShowing) {
                                    settingsProgressDownAt = downAt
                                    settingsProgressCommittedAt = SystemClock.uptimeMillis()
                                    PerformanceMarks.event("settings.contents.frameCommitted")
                                    if (interactionLatency != null)
                                        PerformanceMarks.inputContent("settingsOpen", downAt, settingsProgressCommittedAt)
                                }
                            }
                        return true
                    }
                })
                root.invalidate()
            }
        } finally {
            if (settingsOpeningGeneration == generation) cancelSettingsOpening()
            if (modal == null) scene.setDetailsVisible(false)
        }
    }
    private fun cancelSettingsOpening() {
        settingsOpening = false; settingsOpeningGeneration++
        settingsButton.updateText("설정"); settingsButton.contentDescription = null
        settingsButton.isEnabled = foreground && safeToAct() && !busy && !driverDirectoryWritePending() && modal == null &&
            !vehicleControlArbiter.backgroundActive
    }
    private fun showSettings() {
        if (!safeToAct() || busy) return
        val compositionStarted = SystemClock.elapsedRealtime()
        val b = settingsMenuBody()
        PerformanceMarks.elapsed("settings.contents.compose", compositionStarted)
        val showStarted = SystemClock.elapsedRealtime()
        dialog("설정", b)
        PerformanceMarks.elapsed("settings.dialog.show", showStarted)
    }
    private fun settingsMenuBody(): LinearLayout {
        settingsMenuCache?.let { cached ->
            (cached.getChildAt(1) as Button).updateText("운전자 프로필 · ${driverSettings.selected.name}")
            (cached.getChildAt(6) as Button).updateText("음악 · ${if (musicSource() == MusicSource.AMBIENT) soundPreset.title else musicSource().title}")
            for (index in 1..4) cached.getChildAt(index).isEnabled = canChange()
            return cached
        }
        val b = body()
        b.add(button("차량 · 미리보기 설정") { showVehicleSettings() }, 80)
        b.add(button("운전자 프로필 · ${driverSettings.selected.name}") { showDriverProfiles() }.apply { isEnabled = canChange() }, 80, 12)
        b.add(button("제로탭 · 휴대폰 거치 시 기본 설정") { showZeroTap() }.apply { isEnabled = canChange() }, 80, 12)
        b.add(button("운동 기록 자동 / 수동 선택") { showActivitySettings() }.apply { isEnabled = canChange() }, 80, 12)
        b.add(button("내 공조 선호 조정") { showComfortSettings() }.apply { isEnabled = canChange() }, 80, 12)
        b.add(button("학습·자동 모드 · 날씨") { showLearningSettings() }, 80, 12)
        b.add(button("음악 · ${if (musicSource() == MusicSource.AMBIENT) soundPreset.title else musicSource().title}") { showSoundSettings() }, 80, 12)
        b.add(button("워치 연결 · 데이터 관리") { showDataSettings() }, 80, 12)
        b.add(button("앱 정보 · 업데이트") { showAppUpdate() }, 80, 12)
        settingsMenuCache = b
        return b
    }
    private fun installedVersionCode(): Long = packageManager.getPackageInfo(packageName, 0).longVersionCode
    private fun checkAppUpdate(manual: Boolean = false, owner: UiPanel? = null) {
        if (updateCheck?.isActive == true || (!manual && updateCheckedThisSession)) return
        if (manual) updatePrompted = true
        updateCheckedThisSession = true
        updateStatus = "업데이트 확인 중"
        val generation=++updateCheckGeneration
        updateCheck = scope.launch {
            try {
                val endpoint = getString(R.string.update_metadata_url)
                if (endpoint.isBlank()) {
                    updateStatus = "업데이트 정보를 확인할 수 없어요."
                } else {
                    val published = withContext(Dispatchers.IO) { AppUpdateClient(endpoint, packageName).fetch() }
                    if(generation!=updateCheckGeneration || !foreground) return@launch
                    publishedVersion = published.takeIf { AppUpdatePolicy.available(installedVersionCode(), it) }
                    updateStatus = if (publishedVersion == null) "최신 버전입니다." else "새 버전 ${published.name}이 있어요."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if(generation==updateCheckGeneration) updateStatus = "연결을 확인한 뒤 다시 시도해 주세요." }
            finally { if(generation==updateCheckGeneration) updateCheck = null }
            if(generation!=updateCheckGeneration || !foreground) return@launch
            if (manual) {
                if (foreground && owner != null && modal === owner && appUpdatePanel === owner && owner.isShowing)
                    refreshAppUpdatePanel?.invoke()
            } else maybePromptUpdate()
        }
    }
    private fun maybePromptUpdate() {
        if (publishedVersion == null || updatePrompted || !foreground || !safeToAct() || busy ||
            active || controller.hasPending || modal != null || vehicleControlArbiter.backgroundActive) return
        updatePrompted = true
        val b = body()
        b.add(text("최신 버전이 존재합니다.", 26f))
        b.add(text("업데이트를 위해 페이지를 이동할까요?", 24f), top = 12)
        dialog("앱 업데이트", b, positive = "이동", negative = "나중에", onPositive = { openAppMarket() })
    }
    private fun openAppMarket() {
        if (!foreground || !safeToAct() || busy || active || controller.hasPending) return
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(AppUpdatePolicy.marketUri(packageName)))
        try { startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            result = "앱 마켓을 열 수 없어요. 설치 상태를 확인해 주세요."; render()
        } catch (_: SecurityException) { result = "앱 마켓 접근이 제한됐어요."; render() }
    }
    private fun showAppUpdate() {
        if (!foreground || !safety.allowed || busy) return
        val version = packageManager.getPackageInfo(packageName, 0)
        val b = body()
        b.add(text("IEUM ${version.versionName}", 26f, ink, true))
        val status = text(if (!updateCheckedThisSession) "업데이트를 확인할 수 있어요." else updateStatus, 24f)
        b.add(status, top = 16)
        lateinit var refresh: Button
        refresh = button("업데이트 확인") {
            if (updateCheck?.isActive == true) return@button
            refresh.isEnabled = false
            status.updateText("업데이트 확인 중")
            checkAppUpdate(manual = true, owner = modal)
        }.apply { isEnabled = updateCheck?.isActive != true }
        b.add(refresh, 80, 16)
        val market = button("앱 마켓에서 업데이트") { openAppMarket() }
        b.add(market,80,12)
        val blocked = text("자동 Mode와 설정 복원을 마친 뒤 업데이트하세요.",22f,muted)
        b.add(blocked,top=12)
        val info = dialog("앱 정보 · 업데이트", b)
        appUpdatePanel = info
        if (publishedVersion != null) updatePrompted = true
        refreshAppUpdatePanel = {
            if (modal === info && info.isShowing) {
                status.updateText(if (!updateCheckedThisSession) "업데이트를 확인할 수 있어요." else updateStatus)
                refresh.isEnabled = updateCheck?.isActive != true && !busy && safety.allowed
                market.visibility = if (publishedVersion != null) View.VISIBLE else View.GONE
                market.isEnabled = foreground && safeToAct() && !busy && !active && !controller.hasPending
                blocked.visibility = if (publishedVersion != null && (active || controller.hasPending)) View.VISIBLE else View.GONE
            }
        }
        refreshAppUpdatePanel?.invoke()
    }
    private fun showVehicleSettings() {
        if (!safety.allowed || busy) return
        val b = body()
        val vehicleSection = col().apply {
            background = surface(bg, 20); setPadding(dp(24), dp(18), dp(24), dp(22))
        }
        val automatic = Switch(this).apply {
            text = "차량 자동 선택"; textSize = 28f; setTextColor(ink)
            typeface = PleosDesign.typeface(this@MainActivity, 600)
            minHeight = dp(80); switchMinWidth = dp(80); compoundDrawablePadding = dp(20)
            isChecked = vehiclePreferences.autoEnabled
            PleosSwitchStyle.apply(this)
            showText = false
        }
        val automaticStatus = text("", 22f, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val selectedVehicle = text("", 26f, ink, true)
        val lookupVehicle = text("", 22f, muted)
        val modelInfo = text("", 20f, muted)
        val modelVersion = button("연식 · 모델 버전") { showVehicleVersionPicker() }.apply {
            textSize = 24f; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        val rightHandDrive = toggle("우핸들 버전", vehiclePreferences.rightHandDrive) { control, enabled ->
            if (enabled != vehiclePreferences.rightHandDrive) {
                if (canChangeVisualization()) {
                    vehiclePreferences = vehiclePreferences.copy(rightHandDrive = enabled)
                    vehicleStore.save(vehiclePreferences)
                    render()
                } else control.isChecked = vehiclePreferences.rightHandDrive
            }
        }
        val handednessStatus = text("", 20f, muted)
        val controlSeat = button("실제 제어 운전석") { showControlSeatSettings() }
        val transparencyLabel = text("", 24f, ink, true)
        val transparency = SeekBar(this).apply {
            max = 100; progress = vehiclePreferences.exteriorTransparency
            minimumHeight = dp(80); splitTrack = false
            progressTintList = ColorStateList.valueOf(mint)
            thumbTintList = ColorStateList.valueOf(ink)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(control: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (!canChangeVisualization()) { control.progress = vehiclePreferences.exteriorTransparency; return }
                    vehiclePreferences = vehiclePreferences.copy(exteriorTransparency = value)
                    transparencyLabel.updateText("외형 투명도  $value%")
                    scene.setExteriorTransparency(value)
                }
                override fun onStartTrackingTouch(control: SeekBar) = Unit
                override fun onStopTrackingTouch(control: SeekBar) {
                    if (canChangeVisualization()) vehicleStore.save(vehiclePreferences)
                }
            })
            contentDescription = "외형 투명도. 왼쪽은 불투명, 오른쪽은 투명"
        }
        val manualVehicle = button("차량 수동 선택") { showManualVehiclePicker() }.apply {
            textSize = 24f; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        vehicleSection.add(automatic, 80)
        vehicleSection.add(automaticStatus, top = 4)
        vehicleSection.add(View(this).apply { setBackgroundColor(PleosDesign.line) }, 1, 20)
        vehicleSection.add(selectedVehicle, top = 20)
        vehicleSection.add(lookupVehicle, top = 10)
        vehicleSection.add(manualVehicle, 80, 18)
        vehicleSection.add(modelVersion, 80, 12)
        vehicleSection.add(rightHandDrive, 80, 18)
        vehicleSection.add(handednessStatus, top = 4)
        vehicleSection.add(controlSeat, 80, 14)
        vehicleSection.add(transparencyLabel, top = 20)
        vehicleSection.add(transparency, 80, 4)
        vehicleSection.add(text("차체만 조절 · 실내는 선명하게 유지", 20f, muted), top = 2)
        vehicleSection.add(modelInfo, top = 12)
        b.add(vehicleSection)
        val d = dialog("차량 · 미리보기 설정", b)
        vehicleSettingsDialog = d
        refreshVehicleSettings = {
            if (modal === d) {
                val selection = vehicleSelection()
                automatic.isEnabled = canChange()
                automatic.contentDescription = "차량 자동 선택 ${if (vehiclePreferences.autoEnabled) "켜짐" else "꺼짐"}"
                automaticStatus.updateText(when {
                    !vehiclePreferences.autoEnabled -> "OFF · 직접 선택한 차량을 사용합니다."
                    vehicleIdentityState is VehicleIdentityState.Loading -> "ON · 차량 정보를 조회하고 있어요."
                    selection.source == VehicleSelectionSource.AUTOMATIC -> "ON · 조회한 차량으로 자동 선택했어요."
                    else -> "ON · ${selection.message}"
                })
                selectedVehicle.updateText("현재 선택  ${selection.profile.displayName}")
                val variant = visualVariant(selection.profile)
                val assetId = variant?.assetId ?: selection.profile.id
                modelVersion.isEnabled = canChooseVehicleVisual() && visualVariants.any { it.profileId == selection.profile.id }
                modelVersion.updateText("연식 · 모델 버전  ${variant?.label ?: "자료 없음"}     ›")
                rightHandDrive.isEnabled = canChangeVisualization()
                if (rightHandDrive.isChecked != vehiclePreferences.rightHandDrive)
                    rightHandDrive.isChecked = vehiclePreferences.rightHandDrive
                rightHandDrive.contentDescription = "우핸들 버전 ${if (vehiclePreferences.rightHandDrive) "켜짐" else "꺼짐"}"
                handednessStatus.updateText(if (vehiclePreferences.rightHandDrive) "우핸들 화면 · 실제 제어 위치와 별개" else "좌핸들 화면 · 실제 제어 위치와 별개")
                controlSeat.updateText("실제 제어 운전석 · " + (driverBinding?.let {
                    "${it.position.label} · ${if (it.manual) "이번 연결 수동" else "차량 조회"}"
                } ?: if (driverSeatState == DriverSeatState.Loading) "조회 중" else "위치 확인 필요"))
                controlSeat.isEnabled = canChange()
                transparency.isEnabled = canChangeVisualization()
                if (!transparency.isPressed) transparency.progress = vehiclePreferences.exteriorTransparency
                transparencyLabel.updateText("외형 투명도  ${vehiclePreferences.exteriorTransparency}%")
                val reflected = variant?.fullVehicle == true && variant.sourceHandedness in setOf("left", "right") &&
                    ((variant.sourceHandedness == "right") != vehiclePreferences.rightHandDrive)
                modelInfo.updateText(when {
                    scene.hasFullVehiclePreview(assetId) -> "시각화 자료: ${selection.profile.brand} 공식 쇼룸의 외형·${if (variant?.interiorDetail == "basic") "기본 " else ""}실내 모델." +
                        if (reflected) " 운전석 방향은 실내를 반사 변환한 참고 표시입니다." else " 원본 운전석 배치를 사용합니다."
                    scene.hasLocalVehiclePreview(assetId) -> "시각화 자료: 현대 쇼룸의 외형 모델. 실내는 공통 참고 모델입니다."
                    else -> "시각화 자료: 차종 비례와 공통 실내를 참고한 컨셉 모델입니다."
                })
                lookupVehicle.updateText(selection.identity?.let { "조회 정보  ${it.displayName}" }
                    ?: if (vehiclePreferences.autoEnabled) "제조사·차종·연식만 조회합니다." else "자동 선택을 켜면 차량에서 정보를 확인합니다.")
                manualVehicle.isEnabled = canChooseVehicleVisual()
                manualVehicle.updateText("차량 수동 선택     ›")
            }
        }
        automatic.setOnCheckedChangeListener { _, enabled ->
            if (enabled == vehiclePreferences.autoEnabled) return@setOnCheckedChangeListener
            if (canChange()) {
                vehiclePreferences = vehiclePreferences.copy(autoEnabled = enabled)
                vehicleStore.save(vehiclePreferences)
                restartVehicleReader()
                render()
            } else automatic.isChecked = vehiclePreferences.autoEnabled
        }
        refreshVehicleSettings?.invoke()
    }
    private fun showVehicleVersionPicker() {
        if (!canChooseVehicleVisual()) return
        val selection = vehicleSelection()
        val available = visualVariants.filter { it.profileId == selection.profile.id }
        if (available.isEmpty()) return
        val b = body()
        b.add(text("확보한 실제 모델 버전만 표시합니다. 공식 연식이 확인되지 않은 자료는 세대·버전명으로 구분합니다.", 24f, muted))
        fun select(assetId: String?) {
            if (!canChooseVehicleVisual()) return
            val saved = vehiclePreferences.visualVariantIds.toMutableMap()
            if (assetId == null) saved.remove(selection.profile.id) else saved[selection.profile.id] = assetId
            vehiclePreferences = vehiclePreferences.copy(visualVariantIds = saved)
            vehicleStore.save(vehiclePreferences)
            result = ""
            modal?.dismiss()
            render()
            showSettings()
        }
        val explicit = vehiclePreferences.visualVariantIds[selection.profile.id]
        b.add(button("${if (explicit == null) "✓  " else ""}자동 선택") { select(null) }.apply { isSelected = explicit == null }, 80, 20)
        available.forEach { variant ->
            val years = variant.modelYears.sorted().joinToString(" · ") { "${it}년형" }
            b.add(button("${if (explicit == variant.assetId) "✓  " else ""}${variant.label}${if (years.isBlank()) "" else " · $years"}") {
                select(variant.assetId)
            }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; isSelected = explicit == variant.assetId }, 80, 12)
        }
        dialog("${selection.profile.model} · 연식과 모델 버전", b, "취소")
    }
    private fun startDriverSeatReader() {
        if (!foreground || driverSeatReader != null) return
        val request = ++driverSeatGeneration
        driverSeatReader = DriverSeatReader(this) { state ->
            if (foreground && request == driverSeatGeneration) {
                if (state is DriverSeatState.Available) PerformanceMarks.event("control.driver.available")
                val previous = driverBinding
                driverSeatState = state; telemetry = null
                if (active && previous !== driverBinding) {
                    active = false; sound.stop(); soundPlaying = false
                    result = "운전석 연결 정보가 바뀌어 자동 Mode를 껐어요. P단에서 이전 설정을 복원해 주세요."
                }
                render()
            }
        }.also { it.start() }
    }
    private fun showControlSeatSettings() {
        if (!canChange()) return
        val b = body()
        val binding = driverBinding
        b.add(text(binding?.let { "${it.position.label} · ${if (it.manual) "이번 연결에서 직접 지정" else "차량에서 조회한 운전석"}" }
            ?: (driverSeatState as? DriverSeatState.Unavailable)?.reason ?: "운전석 위치를 조회하고 있어요.", 25f))
        b.add(text("이 위치로 공조와 시트를 제어합니다. 차종·우핸들 미리보기 설정으로 제어 위치를 추정하지 않습니다.", 23f, muted), top = 18)
        if (binding?.manual != false && driverSeatState != DriverSeatState.Loading) {
            b.add(text("차량 조회가 불가능할 때만 실제 운전석을 직접 지정하세요. 앱을 닫거나 백그라운드로 이동하면 해제됩니다.", 22f, muted), top = 18)
            DriverSeatPosition.entries.take(3).forEach { position ->
                b.add(button(position.label) {
                    if (canChange() && foreground && driverBinding?.manual != false && driverSeatState != DriverSeatState.Loading) {
                        driverSeatState = DriverSeatState.Available(DriverSeatBinding(position, manual = true))
                        telemetry = null; render(); modal?.dismiss()
                    }
                }, 80, 12)
            }
        }
        b.add(button("차량 위치 다시 조회") {
            if (canChange()) {
                driverSeatGeneration++; driverSeatReader?.close(); driverSeatReader = null
                driverSeatState = DriverSeatState.Loading; telemetry = null
                modal?.dismiss(); startDriverSeatReader(); render()
            }
        }, 80, 20)
        dialog("실제 제어 운전석", b)
    }
    private fun showManualVehiclePicker(brand: String? = null) {
        if (!canChooseVehicleVisual()) return
        val selected = VehicleCatalog.find(vehiclePreferences.manualProfileId)
        val available = selectableVehicles()
        if (available.isEmpty()) {
            dialog("차량 자료 없음", body().apply { add(text("이 기기에 설치된 실제 3D 차량 자료가 없습니다. 차량 자료 설치를 확인해 주세요.", 24f)) })
            return
        }
        val brands = available.map { it.brand }.distinct()
        val currentBrand = brand ?: selected.brand.takeIf { it in brands } ?: brands.first()
        val b = body()
        b.add(text("실제 3D 자료가 설치된 차종만 표시합니다. 직접 선택하면 자동 선택이 꺼집니다.", 24f, muted))
        val tabs = row()
        brands.forEachIndexed { i, name ->
            tabs.addView(button(name) { showManualVehiclePicker(name) }.apply {
                textSize = 24f; setPadding(dp(12), dp(12), dp(12), dp(12))
                if (name == currentBrand) { background = pressFeedback(mint); setTextColor(mintInk) }
                isSelected = name == currentBrand
            }, LinearLayout.LayoutParams(0, dp(80), 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        b.add(tabs, 80, 22)
        available.filter { it.brand == currentBrand }.forEach { profile ->
            b.add(button("${if (selected.id == profile.id) "✓  " else ""}${profile.model}") {
                selectManualVehicle(profile)
            }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                isSelected = selected.id == profile.id
                if (selected.id == profile.id) {
                    background = pressFeedback(raised)
                    setTextColor(mint)
                    contentDescription = "${profile.displayName}, 현재 수동 선택"
                }
            }, 80, 12)
        }
        b.add(text("선택하면 해당 차량의 실제 외형·실내 모델로 바뀝니다.", 20f, muted), top = 16)
        dialog("차량 수동 선택", b, "취소")
    }
    private fun selectManualVehicle(profile: VehicleProfile) {
        if (!canChooseVehicleVisual()) return
        vehiclePreferences = vehiclePreferences.copy(autoEnabled = false, manualProfileId = profile.id)
        vehicleStore.save(vehiclePreferences)
        restartVehicleReader()
        result = ""
        modal?.dismiss()
        render()
    }
    private fun restartVehicleReader() {
        val generation = ++vehicleReaderGeneration
        vehicleReader?.close(); vehicleReader = null
        vehicleIdentityState = VehicleIdentityState.Unavailable("차량 정보 조회 대기")
        if (!foreground || !vehiclePreferences.autoEnabled) return
        vehicleReader = VehicleIdentityReader(this) { state ->
            if (foreground && vehiclePreferences.autoEnabled && generation == vehicleReaderGeneration) {
                vehicleIdentityState = state
                render()
            }
        }.also { it.start() }
    }
    private fun showActivitySettings() {
        if (!canChange()) return
        val editor = preferenceEditors.capture()
        val b = body(); b.add(text("평소에는 기록을 자동으로 사용하고, 기록이 없거나 다를 때만 직접 지정하세요.", 24f, muted))
        val manualButtons = mutableListOf<Button>()
        b.add(toggle("최근 운동 자동 사용", followWorkout) { control, enabled ->
            if (!canChange() || !preferenceEditors.canCommit(editor)) { control.isChecked = followWorkout; return@toggle }
            selectedImportedWorkout = null; followWorkout = enabled
            if (source == HeartRateSource.DEMO) source = HeartRateSource.MANUAL
            savePreferences(); refreshRecommendation(); render()
            if (!preferenceEditors.canCommit(editor)) { showActivitySettings(); return@toggle }
            manualButtons.forEach { it.isEnabled = !enabled }
        }, 80, 18)
        ActivityKind.entries.forEach { kind ->
            val choice = button("${kind.title} 직접 지정") {
            if (!canChange() || !preferenceEditors.canCommit(editor)) return@button
            selectedImportedWorkout = null; followWorkout = false; manualActivityKind = kind; source = HeartRateSource.MANUAL
            refreshRecommendation(); savePreferences(); render(); showSettings()
            }.apply { isEnabled = !followWorkout; isSelected = !followWorkout && manualActivityKind == kind }
            manualButtons += choice
            b.add(choice, 80, 12)
        }; dialog("운동 기록 사용", b)
    }
    private fun showComfortSettings() {
        if (!canChange()) return
        val editor = preferenceEditors.capture()
        var selectedTemp = preferredTemperature; var selectedFeeling = manualFeeling; var wet = workout.wet
        var selectedFan = preferredFan
        val b = body(); b.add(text("${workout.kind.title} 후 선호를 저장해요. 체감 미지정 시 활동별 기본 프리셋을 사용해요.", 24f, muted))
        val temperatureTitle = text("", 26f, ink, true)
        b.add(temperatureTitle, top = 20)
        val temps = (22..26).map { v -> button("${v}°C") { }.apply { setPadding(dp(8), dp(12), dp(8), dp(12)) } }
        fun paintTemps() {
            temperatureTitle.updateText("기준 온도 · ${fmt(selectedTemp)}°C")
            temps.forEachIndexed { i, v ->
                v.isSelected = selectedTemp == 22f + i
                v.background = pressFeedback(if (v.isSelected) mint else raised)
                v.setTextColor(if (v.isSelected) mintInk else ink)
            }
        }
        temps.forEachIndexed { i, v -> v.setOnClickListener {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectedTemp = 22f + i; paintTemps()
        } }
        b.add(row().apply { temps.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, dp(80), 1f).apply { if (i > 0) marginStart = dp(8) }) } }, top = 12); paintTemps()
        val fanTitle = text("", 26f, ink, true)
        b.add(fanTitle, top = 22)
        val fanValues = listOf<Int?>(null, 2, 3)
        val fanButtons = fanValues.map { value -> button(value?.let { "$it 단계" } ?: "자동") {} }
        fun paintFans() {
            fanTitle.updateText("선호 풍량 · ${selectedFan?.let { "$it 단계" } ?: "자동"}")
            fanButtons.forEachIndexed { i, view ->
                view.isSelected = fanValues[i] == selectedFan
                view.background = pressFeedback(if (view.isSelected) mint else raised)
                view.setTextColor(if (view.isSelected) mintInk else ink)
            }
        }
        fanButtons.forEachIndexed { i, view -> view.setOnClickListener {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectedFan = fanValues[i]; paintFans()
        } }
        b.add(row().apply { fanButtons.forEachIndexed { i, view -> addView(view, LinearLayout.LayoutParams(0, dp(80), 1f).apply { if (i > 0) marginStart = dp(8) }) } }, top = 12)
        paintFans()
        b.add(text("체감 보정", 26f, ink, true), top = 22)
        val choices = listOf<ThermalFeeling?>(null, ThermalFeeling.HOT, ThermalFeeling.NEUTRAL, ThermalFeeling.COLD)
        val feel = choices.map { f -> button(f?.title ?: "자동") { }.apply { setPadding(dp(8), dp(12), dp(8), dp(12)) } }
        fun paintFeel() = feel.forEachIndexed { i, v ->
            v.isSelected = selectedFeeling == choices[i]
            v.background = pressFeedback(if (v.isSelected) mint else raised)
            v.setTextColor(if (v.isSelected) mintInk else ink)
        }
        feel.forEachIndexed { i, v -> v.setOnClickListener {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            selectedFeeling = choices[i]; paintFeel()
        } }
        b.add(row().apply { feel.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, dp(80), 1f).apply { if (i > 0) marginStart = dp(8) }) } }, top = 12); paintFeel()
        b.add(Switch(this).apply {
            text = "옷이나 머리가 젖었어요"; textSize = 24f; setTextColor(ink); minHeight = dp(80)
            typeface = PleosDesign.typeface(this@MainActivity); PleosSwitchStyle.apply(this)
            isChecked = wet; setOnCheckedChangeListener { _, value -> wet = value }
        }, 80, 22)
        dialog("${driverSettings.selected.name} · 공조 선호", b, "저장", { if (canChange() && preferenceEditors.canCommit(editor)) { preferredTemperature = selectedTemp; preferredFan = selectedFan; manualFeeling = selectedFeeling; workout = workout.copy(wet = wet); savePreferences(); refreshRecommendation(); render() } }, "취소")
    }

    private fun learningContext(): ComfortContext {
        val w = currentWeather()
        val actual = telemetry?.takeIf { now() - it.readAtEpochMillis in 0..15_000 }
        val pulse = reading().takeIf { it.status == HeartRateStatus.LIVE }
        val age = recent()?.ageSeconds(now())?.div(60)?.toInt()?.takeIf { it in 0..1440 }
        return ComfortContext(driverSettings.selectedProfileId, vehicleSelection().profile.id,
            activitySelection().record?.kind?.name ?: manualActivityKind.name.takeIf { !followWorkout }, workout.wet,
            if (learningSession != null && now() - learningStarted >= 300_000) ComfortPhase.SETTLED else ComfortPhase.ARRIVAL,
            w?.temperature, w?.humidity, w?.radiation, actual?.measuredCabinTemperature,
            pulse?.bpm, age, w?.observedAt)
    }

    /** Capture UI-owned context first. The returned work only touches its captured
     * thread-safe preference store, never Activity/vehicle state, on an IO worker. */
    private fun learningSessionPreparation(): (() -> Pair<String,Long>)? {
        if (!learningConsent || !learningStoragePrepared) return null
        // OFF/ON and process restarts in the same two-hour exercise context are not new evidence.
        val marker = "${vehicleSelection().profile.id}:${recent()?.endedAtEpochMillis ?: "unknown"}"
        val idKey = preferenceKey("learning_trip_id")
        val startedKey=preferenceKey("learning_trip_started")
        val markerKey=preferenceKey("learning_trip_marker")
        val store=prefs
        val at=now()
        return {
            val started=store.getLong(startedKey,0)
            val reuse=store.getString(markerKey,null)==marker && at-started in 0..7_199_999
            val previous=if(reuse) store.getString(idKey,null) else null
            if(previous!=null) previous to started
            else {
                val id=java.util.UUID.randomUUID().toString()
                store.edit().putString(idKey,id).putString(markerKey,marker).putLong(startedKey,at).apply()
                id to at
            }
        }
    }

    private fun actualLearningSetting(): LearnedSetting? {
        val t = telemetry?.takeIf { now() - it.readAtEpochMillis in 0..15_000 && parked } ?: return null
        return LearnedSetting(t.driverTemperature ?: return null, t.fan ?: return null,
            t.heat, t.ventilation, t.steeringHeat).takeIf { it.valid() }
    }

    private fun recoverLearningPrivacy(refreshLearningDialog: Boolean = false) {
        if (refreshLearningDialog) privacyRefreshOwner = modal
        if (privacyRecovery?.isActive == true || !learningPrivacy.hasPending()) return
        privacyRecovery = scope.launch {
            var succeeded = false
            try {
                val completed = withContext(Dispatchers.IO) { learningPrivacy.recover() }
                succeeded = true
                if (driverSettings.selectedProfileId in completed && result == "학습 기록 삭제 중 · 새 기록 저장 중단") {
                    result = "학습 기록을 삭제했어요. 기록 저장 동의도 해제했어요."
                    if (foreground) render()
                }
                val owner = privacyRefreshOwner
                if (foreground && owner != null && modal === owner && safeToAct() && canChange()) showLearningSettings()
            } catch (_: Exception) {
                result = "학습 기록 삭제가 남아 있어요. 저장·학습은 중단했어요."
                if (foreground) render()
            } finally {
                privacyRefreshOwner = null
                privacyRecovery = null
                if (succeeded && learningPrivacy.hasPending()) recoverLearningPrivacy()
            }
        }
    }

    private fun requestLearningRecommendation() {
        if (!learningConsent || learningPrivacy.pending(driverSettings.selectedProfileId) || learningQueryBusy || now() - lastLearningQuery < 15_000) return
        if (!learningStoragePrepared) { prepareLearningStorageIfNeeded(); return }
        lastLearningQuery = now(); learningQueryBusy = true
        val context = learningContext(); val revision = learningRevision
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { learningStore.candidates(context) to learningStore.count(context.profile) }
                if (revision != learningRevision || context.profile != driverSettings.selectedProfileId || context.vehicle != vehicleSelection().profile.id) return@launch
                learningCases = result.first; learnedCount = result.second
                if (!active && !busy) refreshRecommendation()
                render()
            } catch (_: Exception) { log("개인화 기록 조회 실패 · 기본 선호 유지") }
            finally { learningQueryBusy = false }
        }
    }

    private fun observeLearningAdjustment() {
        if (!active || busy || !parked || !learningConsent || source == HeartRateSource.DEMO) return
        val current = actualLearningSetting() ?: return
        val previous = lastObservedSetting
        lastObservedSetting = current
        // Never label the first automatic readback as a user correction or satisfaction.
        if (previous != null && previous != current) saveLearningCase(current, false)
    }

    private fun saveLearningCase(setting: LearnedSetting, satisfied: Boolean) {
        val session = learningSession ?: return
        if (!learningConsent || learningPrivacy.pending(driverSettings.selectedProfileId) || source == HeartRateSource.DEMO) return
        val context = learningContext(); val revision = learningRevision
        // Timestamp the observation here, not when an IO worker happens to run.
        val observation = ComfortCase(session, now(), context, setting, satisfied)
        scope.launch {
            try {
                learningWrites.write { learningStore.save(observation) { revision == learningRevision } }
                if (revision == learningRevision && driverSettings.selectedProfileId == context.profile) {
                    lastLearningQuery = 0; requestLearningRecommendation()
                    if (satisfied) result = "현재 설정에 대한 만족을 기록했어요. 같은 탑승은 반복 횟수로 늘리지 않아요."
                    render()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == learningRevision && foreground && driverSettings.selectedProfileId == context.profile) {
                    result = "개인화 기록을 저장하지 못했어요."; render()
                }
            }
        }
    }

    private fun refreshWeatherIfNeeded(force: Boolean = false) {
        if(automaticWeatherEnabled()) {
            syncWeatherTracking()
            if(force || lastWeatherLocationAttempt==0L || now()-lastWeatherLocationAttempt>=WeatherRegionPolicy.REFRESH_MS)
                refreshWeatherLocation()
            return
        }
        val place = weatherPlace ?: return
        if (weatherBusy || !foreground || (!force && now() - lastWeatherAttempt < 900_000)) return
        fetchWeather(place)
    }

    private fun automaticWeatherEnabled()=prefs.getBoolean("weather_location_enabled",true)
    private fun currentWeather()=weather?.takeIf { it.fresh(now()) && (!it.place.fromDeviceLocation ||
        weatherPositionVerified && WeatherRegionPolicy.positionFresh(android.os.SystemClock.elapsedRealtime(),weatherPositionElapsed)) }

    private fun fetchWeather(place:WeatherPlace) {
        weatherFetchJob?.cancel()
        val generation=++weatherFetchGeneration
        weatherBusy = true; lastWeatherAttempt = now(); weatherMessage = "지역 날씨 조회 중"
        weatherFetchJob=scope.launch {
            try {
                val updated = ComfortWeatherClient.fetch(place)
                if (foreground && generation==weatherFetchGeneration && weatherPlace == place &&
                    (!place.fromDeviceLocation || automaticWeatherEnabled() && WeatherLocation.permitted(this@MainActivity))) {
                    weather = updated; weatherMessage = ""; lastLearningQuery = 0
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation==weatherFetchGeneration && weatherPlace == place) weatherMessage = "주변 날씨 조회 실패 · 다시 확인 중" }
            finally {
                if(generation==weatherFetchGeneration) {
                    weatherBusy = false; weatherFetchJob=null
                    if (foreground && safeToAct()) { if (!active && !busy) refreshRecommendation(); render() }
                }
            }
        }
    }

    private fun stopWeatherTracking(clearRegion:Boolean) {
        weatherLocationTracker?.close();weatherLocationTracker=null
        weatherLocationGeneration++;weatherLocationJob?.cancel();weatherLocationJob=null
        weatherFetchGeneration++;weatherFetchJob?.cancel();weatherFetchJob=null;weatherBusy=false
        latestWeatherPosition=null
        weatherPositionVerified=false
        weatherPositionElapsed=0L;weatherTrackerAttemptElapsed=0L
        if(clearRegion && weatherPlace?.fromDeviceLocation==true) { weather=null;weatherPlace=null }
    }

    private fun syncWeatherTracking() {
        if(!foreground || !automaticWeatherEnabled() || !WeatherLocation.permitted(this)) {
            if(weatherLocationTracker!=null || weatherLocationJob!=null || weatherPlace?.fromDeviceLocation==true) stopWeatherTracking(true)
            if(automaticWeatherEnabled()) weatherMessage="주변 날씨 자동 연결 · 위치 권한 필요"
            return
        }
        if(weatherLocationTracker==null) {
            val elapsed=android.os.SystemClock.elapsedRealtime()
            if(weatherTrackerAttemptElapsed!=0L && elapsed-weatherTrackerAttemptElapsed in 0 until WeatherRegionPolicy.MIN_RETRY_MS) return
            weatherTrackerAttemptElapsed=elapsed
            val tracker=WeatherLocationTracker(this,::acceptWeatherPosition,unavailable={
                if(foreground) {
                    weatherPositionVerified=false;latestWeatherPosition=null;weatherMessage="현재 위치 신호 확인 중"
                    if(safeToAct()) render()
                }
            })
            weatherLocationTracker=tracker
            if(!tracker.start()) {
                tracker.close();weatherLocationTracker=null
                weatherMessage="위치 기능을 사용할 수 없어요."
            }
        }
    }

    private fun acceptWeatherPosition(position:WeatherPosition) {
        if(!foreground || !automaticWeatherEnabled() || !WeatherLocation.permitted(this)) return
        if(!WeatherRegionPolicy.positionFresh(android.os.SystemClock.elapsedRealtime(),position.elapsedRealtimeMs)) return
        val place=position.place
        latestWeatherPosition=place
        weatherPositionVerified=true
        weatherPositionElapsed=position.elapsedRealtimeMs
        val moved=WeatherRegionPolicy.moved(weatherPlace,place)
        if(moved) {
            // Never carry the previous city's climate into the next region's recommendation.
            weatherFetchGeneration++;weatherFetchJob?.cancel();weatherFetchJob=null;weatherBusy=false
            weatherPlace=place;weather=null;lastLearningQuery=0
            weatherMessage="이동한 지역의 날씨 확인 중"
        }
        if(!weatherBusy && WeatherRegionPolicy.due(now(),lastWeatherAttempt,weather?.fetchedAt ?: 0L,weather==null))
            fetchWeather(weatherPlace ?: place.also { weatherPlace=it })
        if(safeToAct()) { if(!active && !busy) refreshRecommendation();render() }
    }

    private fun refreshWeatherLocation() {
        if(!foreground || weatherLocationJob?.isActive==true || !automaticWeatherEnabled()) return
        if(!WeatherLocation.permitted(this)) {
            weather = null
            if(weatherPlace?.fromDeviceLocation==true) weatherPlace=null
            weatherMessage="주변 날씨 자동 연결 · 위치 권한 필요"; return
        }
        lastWeatherLocationAttempt=now(); weatherMessage="현재 위치 지역 확인 중"
        val generation=++weatherLocationGeneration
        weatherLocationJob=scope.launch {
            try {
                val place=WeatherLocation.current(this@MainActivity)
                if(generation!=weatherLocationGeneration || !foreground || !automaticWeatherEnabled()) return@launch
                // Exact fixes are neither persisted nor sent. A rounded, current region is used in memory.
                acceptWeatherPosition(place)
            } catch(_: TimeoutCancellationException) { if(generation==weatherLocationGeneration) weatherMessage="현재 위치 신호를 다시 확인하고 있어요." }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_:Exception) { if(generation==weatherLocationGeneration) weatherMessage="현재 위치를 확인하지 못했어요." }
            finally {
                if(generation==weatherLocationGeneration) {
                    weatherLocationJob=null
                    if(foreground && safeToAct()) { if(!active && !busy) refreshRecommendation(); render() }
                }
            }
        }
    }

    private fun showComfortRating() {
        val snapshot = pendingComfortRating ?: return
        if (!foreground || !safeToAct() || busy || active || controller.hasPending || ratingSaving) return
        if (!learningConsent || learningPrivacy.pending(driverSettings.selectedProfileId) ||
            snapshot.context.profile != driverSettings.selectedProfileId || snapshot.context.vehicle != vehicleSelection().profile.id ||
            now()-snapshot.at !in 0..900_000) { pendingComfortRating = null; return }
        val b = body()
        b.add(text("방금 자동 설정은 어땠나요?",26f,ink,true))
        b.add(text("1점 매우 불편 · 3점 보통 · 5점 매우 만족",22f,muted),top=12)
        b.add(text("4~5점은 추천에 반영하고, 1~2점 설정은 재추천을 줄여요.",22f,muted),top=8)
        val choices = row()
        for (stars in 1..5) choices.addView(button("$stars ★") {
            if (pendingComfortRating !== snapshot || ratingSaving || !safeToAct() || active || busy ||
                !learningConsent || learningPrivacy.pending(snapshot.context.profile) ||
                driverSettings.selectedProfileId != snapshot.context.profile || vehicleSelection().profile.id != snapshot.context.vehicle) return@button
            val revision = learningRevision
            ratingSaving = true
            modal?.dismiss()
            result = "평가 저장 중"; render()
            scope.launch {
                try {
                    learningWrites.write { learningStore.save(snapshot.copy(satisfied=stars>=4,rating=stars)) {
                        revision == learningRevision
                    } }
                    if (revision == learningRevision && pendingComfortRating === snapshot) {
                        pendingComfortRating = null; lastLearningQuery = 0; requestLearningRecommendation()
                        result = "${stars}점 평가를 저장했어요. 다음 추천에 반영해요."
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (revision == learningRevision) result = "평가를 저장하지 못했어요. 다시 시도해 주세요." }
                finally { ratingSaving = false; if (foreground) render() }
            }
        },LinearLayout.LayoutParams(0,dp(80),1f).apply { if(stars>1) marginStart=dp(6) })
        b.add(choices,top=20)
        dialog("자동 모드 평가",b,positive="나중에")
    }

    private fun enableLocationWeather() {
        if(!foreground || !safeToAct() || busy) return
        if(!WeatherLocation.permitted(this) && (active || controller.hasPending)) {
            result="자동 Mode와 복원을 마친 뒤 위치 권한을 허용하세요.";render();return
        }
        prefs.edit().putBoolean("weather_location_enabled",true).apply()
        weatherPositionVerified=false;lastWeatherLocationAttempt=0
        if(WeatherLocation.permitted(this)) { syncWeatherTracking();refreshWeatherLocation() }
        else requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION),413)
        modal?.dismiss()
    }

    private fun showWeatherSettings() {
        if(!safeToAct() || busy) return
        val b=body()
        b.add(text("차량의 현재 위치에 맞춰 주변 날씨를 자동으로 확인해요.",24f,ink,true))
        b.add(text("위치 권한을 한 번 허용하면 지역을 직접 선택할 필요가 없어요.",22f,muted),top=12)
        b.add(text("이동한 지역과 정차·앱 복귀 시 위치를 다시 확인해요.",22f,muted),top=8)
        b.add(toggle("주변 날씨 자동 갱신",automaticWeatherEnabled() && WeatherLocation.permitted(this)) { control,enabled ->
            if(enabled) {
                if(!WeatherLocation.permitted(this) && (active || controller.hasPending)) {
                    control.isChecked=false;result="자동 Mode와 복원을 마친 뒤 위치 권한을 허용하세요.";render()
                } else enableLocationWeather()
            } else {
                prefs.edit().putBoolean("weather_location_enabled",false).apply();stopWeatherTracking(true)
                weatherMessage="주변 날씨 자동 연결 꺼짐";lastLearningQuery=0;render()
            }
        },80,20)
        b.add(text(currentWeather()?.let { "현재 주변 ${fmt(it.temperature)}°C · 습도 ${fmt(it.humidity)}%" } ?: weatherMessage,22f,mint),top=12)
        b.add(button(if(WeatherLocation.permitted(this)) "현재 위치 다시 확인" else "위치 권한 허용하고 연결") {
            enableLocationWeather()
        }.apply { isEnabled=WeatherLocation.permitted(this@MainActivity) || (!active && !controller.hasPending) },80,16)
        b.add(text("약 1km 격자 좌표만 Open-Meteo 날씨 조회에 전송해요. 건강 정보와 이동 경로는 보내지 않아요.",20f,muted),top=20)
        b.add(text("앱을 벗어나거나 위치 사용을 끄면 위치 수신을 중단해요.",20f,muted),top=8)
        b.add(button("위치 사용이 어려울 때 · 도시 직접 선택") { showManualWeatherSettings() },80,20)
        dialog("주변 날씨 자동 연결",b)
    }

    private fun showManualWeatherSettings() {
        if (!safeToAct() || busy) return
        val b = body()
        b.add(text("지역 날씨는 외부 기상 모델 값이며 실내 센서값과 다릅니다. 검색어와 선택 지역 좌표만 Open-Meteo에 전송합니다. 건강 정보는 보내지 않습니다.", 22f, muted))
        b.add(text("이 보조 기능에서 선택한 도시는 이동해도 자동 변경되지 않아요.",22f,muted),top=12)
        val city = EditText(this).apply {
            hint = "도시 이름 (예: 서울, Busan)"; textSize = 24f; setTextColor(ink); setHintTextColor(muted); minHeight = dp(80)
            filters = arrayOf(android.text.InputFilter.LengthFilter(60)); setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_SEARCH
        }
        b.add(city, 88, 16)
        val status = text(weatherPlace?.name ?: "지역을 선택하지 않았습니다.", 22f, mint); b.add(status, top = 12)
        b.add(button("도시 검색") {
            if (city.text.toString().trim().length < 2) { city.error = "두 글자 이상 입력하세요"; return@button }
            val owner = modal; val query = city.text.toString(); status.text = "검색 중"
            scope.launch {
                runCatching { ComfortWeatherClient.search(query) }.onSuccess { places ->
                    if (!foreground || !safeToAct() || modal !== owner) return@onSuccess
                    if (places.isEmpty()) status.text = "검색 결과 없음 · 영문 도시명도 시도하세요"
                    else {
                        val list = body()
                        places.forEach { place -> list.add(button(place.name) {
                            if (!safeToAct()) return@button
                            stopWeatherTracking(true)
                            prefs.edit().putBoolean("weather_location_enabled",false).apply()
                            weatherPlace = place; weather = null; lastLearningQuery = 0
                            prefs.edit().putString("weather_name",place.name).putString("weather_lat",place.latitude.toString()).putString("weather_lon",place.longitude.toString()).apply()
                            refreshWeatherIfNeeded(true); modal?.dismiss(); render()
                        }, 80, 12) }
                        dialog("날씨 지역 선택", list)
                    }
                }.onFailure { if (modal === owner) status.text = "지역 검색 실패 · 네트워크 확인" }
            }
        }, 80, 12)
        b.add(text("날씨: Open-Meteo · 지역 검색: GeoNames. 직접 선택한 지역을 15분 간격으로 확인해요.",20f,muted),top=20)
        b.add(button("날씨 지역 설정 해제") {
            if (!safeToAct()) return@button
            weatherPlace = null; weather = null; weatherMessage = "날씨 지역 미설정"; lastLearningQuery = 0
            stopWeatherTracking(true); pendingWeatherLocationPermission=false
            prefs.edit().putBoolean("weather_location_enabled",false).remove("weather_name").remove("weather_lat").remove("weather_lon").apply()
            if (!active) refreshRecommendation(); render(); modal?.dismiss()
        }, 80, 12)
        dialog("도시 직접 선택 · 보조 기능", b)
    }

    private fun showLearningSettings() {
        if (!safeToAct() || busy) return
        val b = body()
        b.add(text("${driverSettings.selected.name} · ${if (comfortMode == ComfortMode.LEARNING) "학습 모드" else "자동 모드"}", 28f, mint, true))
        b.add(text("학습 ON: 공조를 유지하며 직접 조절한 설정을 관찰해요.", 22f, muted), top = 12)
        b.add(text("만족 버튼을 누를 때 기록해요. OFF는 관찰만 끝내요.", 22f, muted), top = 8)
        b.add(text("자동 ON: 충분한 만족 기록이 있으면 개인화 설정을, 없으면 기본 선호를 한 번 적용해요.", 22f, muted), top = 12)
        b.add(text("OFF는 앱 변경만 복원해요. 이후 직접 바꾼 값은 유지해요.", 22f, muted), top = 8)
        b.add(text("새 심박만으로 실행 중 공조를 반복 변경하지 않아요.", 22f, muted), top = 12)
        if (pendingComfortRating != null) b.add(button("지난 자동 모드 평가하기") { showComfortRating() },80,12)
        b.add(toggle("개인화 요약을 이 차량에 저장", learningConsent) { control, enabled ->
            if (enabled == learningConsent) return@toggle
            if (active || controller.hasPending) { control.isChecked = learningConsent; result = "자동 Mode를 끈 뒤 기록 저장을 변경하세요."; render(); return@toggle }
            if (enabled && learningPrivacy.pending(driverSettings.selectedProfileId)) {
                control.isChecked = false; result = "이전 기록 삭제를 마친 뒤 다시 동의할 수 있어요."; render(); return@toggle
            }
            learningConsent = enabled; learningRevision++; learningCases = emptyList(); lastLearningQuery = 0
            learnedCount = 0
            if (enabled) prefs.edit().putBoolean(preferenceKey("learning_consent"), true).apply()
            else {
                learningSession = null; lastObservedSetting = null; pendingComfortRating = null
                runCatching { learningPrivacy.revoke(driverSettings.selected) }.onFailure {
                    result = "동의 철회 저장을 확인하지 못했어요. 새 기록은 중단했어요."; render()
                }
                recoverLearningPrivacy(refreshLearningDialog = true)
            }
            refreshRecommendation(); render()
        }, 80, 16)
        b.add(text("선택 동의: 운동·환경·심박·공조·평가 요약을 이 기기의 개인화에 사용해요.", 20f, muted), top = 12)
        b.add(text("보유: 동의 철회·프로필 삭제까지, 프로필당 최대 1,200건이에요.", 20f, muted), top = 8)
        b.add(text("거부해도 기본 선호를 쓸 수 있어요. 원시 파형은 저장하지 않아요.", 20f, muted), top = 8)
        b.add(text(if (learningPrivacy.pending(driverSettings.selectedProfileId)) "동의를 철회했어요. 저장된 기록을 삭제 중이며 다시 실행해도 삭제를 이어가요."
            else "동의는 선택이에요. 끄면 새 기록을 중단하고 기존 학습 기록도 삭제해요.", 20f, muted), top = 8)
        b.add(text("메인 화면의 자동 Mode·학습 Mode 버튼으로 시작하고, 켜진 버튼을 다시 눌러 종료해요.",22f,muted),top=12)
        b.add(text("${learnedCount}회 학습 세션 · ${learnedDecision.reason}", 22f, mint), top = 18)
        b.add(text(weatherPlace?.name ?: "날씨 지역 미설정", 22f, mint), top = 8)
        b.add(text("실제 실내 ${telemetry?.measuredCabinTemperature?.let { "${fmt(it)}°C" } ?: "미확인"}", 22f, mint), top = 8)
        b.add(button("주변 날씨 자동 연결") { showWeatherSettings() }, 80, 12)
        if (active && learningConsent) b.add(button("지금 공조가 좋아요 · 만족 기록") {
            val setting = actualLearningSetting()
            if (setting == null) { result = "온도·풍량의 실제 응답이 필요해요. 미확인 좌석 기능은 학습하지 않습니다."; render() }
            else if (source == HeartRateSource.DEMO) { result = "데모 데이터는 개인 학습에 저장하지 않아요."; render() }
            else { saveLearningCase(setting, true); modal?.dismiss() }
        }, 80, 16)
        b.add(button("이 프로필의 학습 기록 삭제") {
            if (!canChange()) return@button
            val id = driverSettings.selectedProfileId
            val confirmation = body()
            confirmation.add(text("이 프로필의 환경·심박·공조 요약을 삭제하고, 기록 저장 동의를 해제해요.", 24f))
            confirmation.add(text("다른 운전자와 차량 기본 선호는 유지해요. 다시 저장하려면 새로 동의하세요.", 24f), top = 12)
            dialog("학습 기록 삭제", confirmation, "삭제", {
                if (!canChange() || driverSettings.selectedProfileId != id) return@dialog
                if (runCatching { learningPrivacy.revoke(driverSettings.selected) }.isFailure) {
                    result = "기록 삭제 요청을 저장하지 못했어요. 다시 시도해 주세요."; render()
                    return@dialog
                }
                learningConsent = false; learningRevision++; learningCases = emptyList(); learnedCount = 0
                learningSession = null; lastObservedSetting = null; lastLearningQuery = 0; pendingComfortRating = null
                result = "학습 기록 삭제 중 · 새 기록 저장 중단"
                recoverLearningPrivacy(); refreshRecommendation(); render()
            }, "취소")
        }.apply { isEnabled = canChange() }, 80, 18)
        dialog("학습·자동 모드", b)
    }
    private fun showSoundSettings() {
        if (busy || !safety.allowed) return
        val editor = preferenceEditors.capture()
        val b = body()
        val selectedSource = musicSource()
        MusicSource.entries.forEach { provider ->
            val access = externalMusic.access(provider)
            b.add(button("${if (provider == selectedSource) "✓ " else ""}${provider.title}${if (provider == MusicSource.AMBIENT) "" else " · ${access.label}"}") {
                if (busy || !safeToAct() || !preferenceEditors.canCommit(editor) || musicSource() != selectedSource) return@button
                prefs.edit().putString(preferenceKey("music_source"), provider.name).apply()
                stopAutoMusic()
                sound.stop(); soundPlaying = false
                if (active && provider == MusicSource.AMBIENT && comfortMode == ComfortMode.AUTOMATIC) soundPlaying = startSound()
                render(); showSoundSettings()
            }.apply { isSelected = provider == selectedSource }, 80, 12)
        }
        if (selectedSource != MusicSource.AMBIENT) {
            b.add(text("이 프로필의 서비스·재생목록 선택을 저장해요. 계정 추천과 재생은 해당 공식 앱이 처리해요.", 22f, muted), top = 18)
            b.add(text("음악 앱에 건강 정보를 보내지 않아요. 외부 음악의 중단은 해당 앱에서 조작하세요.", 22f, muted), top = 10)
            val autoEnabled = musicAutoResumeEnabled(selectedSource)
            b.add(toggle("자동 Mode에서 음악 재개", autoEnabled) { control, enabled ->
                if (!canChange() || !preferenceEditors.canCommit(editor) || musicSource() != selectedSource) {
                    control.isChecked = musicAutoResumeEnabled(selectedSource); return@toggle
                }
                prefs.edit().putBoolean(preferenceKey("music_auto_resume_${selectedSource.name}"), enabled).apply()
                stopAutoMusic()
                result = if (enabled) "다음 자동 Mode부터 마지막 일시정지 곡의 재개를 요청해요." else "음악 자동 재개를 껐어요."
                render()
            }.apply { isEnabled = canChange() }, 80, 12)
            b.add(text("선택한 서비스와 운전자에게만 적용해요. P단에서 자동 Mode를 켤 때 일시정지된 곡만 한 번 재개해요. 재생 중인 곡과 재생목록은 바꾸지 않아요.", 22f, muted), top = 10)
            if (autoMusicStatus.isNotBlank()) b.add(text(autoMusicStatus, 24f, ink).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            }, top = 10)
            val link = EditText(this).apply {
                hint = "공식 앱에서 공유한 재생목록 링크 (선택)"; textSize = 26f; setTextColor(ink); setHintTextColor(muted)
                setText(prefs.getString(preferenceKey("music_link_${selectedSource.name}"), ""))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
                filters = arrayOf(android.text.InputFilter.LengthFilter(2048)); minHeight = dp(80)
            }
            if (selectedSource in setOf(MusicSource.MELON, MusicSource.YOUTUBE_MUSIC)) {
                b.add(link, 96, 16)
                b.add(button("이 프로필의 재생목록 저장") {
                    if (busy || !safeToAct() || !preferenceEditors.canCommit(editor) || musicSource() != selectedSource) return@button
                    val value = link.text.toString().trim()
                    if (value.isNotEmpty() && !selectedSource.acceptsLink(value)) { link.error = "선택한 서비스의 HTTPS 공유 링크를 입력하세요"; return@button }
                    prefs.edit().putString(preferenceKey("music_link_${selectedSource.name}"), value).apply()
                    result = "음악 선호를 저장했어요."; render()
                }, 80, 12)
            }
            val access = externalMusic.access(selectedSource)
            b.add(button(when (access) {
                MusicAppAccess.OPENABLE -> "${selectedSource.title} 열기"
                MusicAppAccess.NOT_INSTALLED -> "${selectedSource.title} 앱이 설치되어 있지 않습니다"
                MusicAppAccess.UNSUPPORTED -> "이 차량에서 음악 앱 열기를 지원하지 않습니다"
            }) {
                if (!safeToAct() || busy || !preferenceEditors.canCommit(editor) || musicSource() != selectedSource) return@button
                val saved = prefs.getString(preferenceKey("music_link_${selectedSource.name}"), "").orEmpty()
                if (!externalMusic.open(selectedSource, saved)) { result = "음악 앱을 열지 못했어요. 설치와 로그인 상태를 확인하세요."; render() }
            }.apply { isEnabled = access == MusicAppAccess.OPENABLE && !active }, 80, 16)
            externalMusic.browserComponent(selectedSource)?.let { component ->
                b.add(button("곡 목록 · 재생 제어") {
                    if (safeToAct() && !busy && !active && preferenceEditors.canCommit(editor) && musicSource() == selectedSource)
                        showMusicBrowser(selectedSource, component)
                }.apply { isEnabled = !active }, 80, 12)
            }
            b.add(text("음악 앱은 정차·자동 Mode OFF 상태에서 엽니다. 재생을 선택한 뒤 돌아와 자동 Mode를 켜세요. 이 화면은 재생 성공이나 계정 연결 완료를 대신 표시하지 않습니다.", 21f, muted), top = 16)
            dialog("음악 서비스 연결", b); return
        }
        val presets = mutableListOf<Pair<SoundPreset, Button>>()
        fun refreshPresets() = presets.forEach { (preset, control) ->
            control.isEnabled = soundPreset != SoundPreset.OFF
            control.isSelected = lastEnabledSound == preset
            control.updateText("${if (lastEnabledSound == preset) "✓  " else ""}${preset.title}")
        }
        b.add(toggle("자동 Mode 사운드", soundPreset != SoundPreset.OFF) { control, enabled ->
            if (busy || !safety.allowed || !preferenceEditors.canCommit(editor)) { control.isChecked = soundPreset != SoundPreset.OFF; return@toggle }
            if (!enabled && soundPreset != SoundPreset.OFF) lastEnabledSound = soundPreset
            soundPreset = if (enabled) lastEnabledSound else SoundPreset.OFF
            savePreferences()
            if (active) { sound.stop(); soundPlaying = startSound() } else refreshRecommendation()
            refreshPresets(); render()
        }, 80)
        b.add(text("통화와 안내음에 오디오 포커스를 양보해요.", 22f, muted), top = 12)
        SoundPreset.entries.filter { it != SoundPreset.OFF }.forEach { preset ->
            val choice = button(preset.title) {
                if (busy || !safety.allowed || soundPreset == SoundPreset.OFF || !preferenceEditors.canCommit(editor)) return@button
                lastEnabledSound = preset; soundPreset = preset; savePreferences()
                if (active) { sound.stop(); soundPlaying = startSound() } else refreshRecommendation()
                refreshPresets(); render()
            }
            presets += preset to choice; b.add(choice, 80, 12)
        }
        refreshPresets(); dialog("자동 Mode 사운드", b)
    }

    private fun showMusicBrowser(provider: MusicSource, component: android.content.ComponentName) {
        if (busy || active || !safeToAct()) return
        val editor = preferenceEditors.capture()
        val b = body()
        val status = text("연결 중…", 24f, ink).apply {
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val rows = body()
        b.add(status, top = 12); b.add(rows, top = 16)
        b.add(text("선택한 음악 앱이 제공하는 목록과 재생 상태입니다. 계정 추천은 해당 앱에서 관리합니다. 화면을 닫아도 외부 음악은 중단하지 않아요.", 21f, muted), top = 16)
        fun allowed() = !busy && !active && safeToAct() && preferenceEditors.canCommit(editor) && musicSource() == provider
        var renderedRows: MusicSessionBrowser.Snapshot? = null
        lateinit var client: MusicSessionBrowser
        client = MusicSessionBrowser(this, component) { state ->
            val playback = musicPlaybackLabel(state.state)
            val label = MusicDisplayText.status(state.message, state.title, playback)
            if (status.text.toString() != label) status.text = label
            status.contentDescription = MusicDisplayText.accessibleStatus(state.message, state.title, playback)
            // Playback-position callbacks must not rebuild up to 100 focused/touchable rows.
            val rowState = state.copy(message = "", title = "", positionMillis = 0)
            if (renderedRows == rowState) return@MusicSessionBrowser
            renderedRows = rowState
            rows.removeAllViews()
            if (state.canBack) rows.add(button("상위 목록") { if (allowed()) client.back() }, 80, 10)
            if (state.connected) {
                val playing = state.state == android.media.session.PlaybackState.STATE_PLAYING
                val action = if (playing) android.media.session.PlaybackState.ACTION_PAUSE else android.media.session.PlaybackState.ACTION_PLAY
                rows.add(button(if (playing) "일시정지" else "재생") {
                    if (allowed()) {
                        val sent = if (playing) client.pause() else client.resume()
                        if (!sent) status.text = "음악 앱이 이 재생 명령을 지원하지 않아요."
                    }
                }.apply { isEnabled = state.actions and action != 0L }, 80, 10)
            }
            state.entries.forEach { entry ->
                rows.add(button(MusicDisplayText.title(entry.title, entry.browsable).ifBlank { "제목 없음" }) {
                    if (allowed()) {
                        if (entry.browsable) client.browse(entry)
                        else if (!client.play(entry)) status.text = "음악 앱에서 이 곡의 재생을 선택해 주세요."
                    }
                }.apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = entry.title
                    isEnabled = entry.browsable || (entry.playable && state.actions and android.media.session.PlaybackState.ACTION_PLAY_FROM_MEDIA_ID != 0L)
                }, 80, 10)
            }
        }
        dialog("${provider.title} · 곡 목록", b, onDismissAction = { client.close() })
        client.connect()
    }
    private fun startReceiver() {
        if (receiver != null || !foreground) return
        val generation = ++receiverGeneration
        val bridge = WatchReceiver(pairingToken = secretStore.getOrCreate()) { }
        bridge.zeroTapStatus = { zeroTap.status(driverSettings.selectedProfileId) }
        bridge.zeroTapReceipt = { zeroTap.receipt(driverSettings.selectedProfileId) }
        bridge.onZeroTap = { event -> runOnUiThread {
            if (foreground && generation == receiverGeneration) receiveZeroTap(event)
        } }
        val receivePulse: (Int, Long, PairedDriverIdentity?) -> Unit = { bpm, measuredAt, identity ->
            runOnUiThread {
            if (foreground && generation == receiverGeneration && acceptsHealthIdentity(identity)) {
                // A slower relay must not replace a newer direct sensor measurement.
                val incoming = DirectHeartRateSample(bpm,
                    SystemClock.elapsedRealtime() - (now() - measuredAt).coerceAtLeast(0))
                val selected = RecoveryHeartRateSelection.newestDirectSample(
                    watchBpm?.let { DirectHeartRateSample(it, watchReceivedAt) }, incoming)
                watchBpm = selected.bpm
                watchReceivedAt = selected.measuredAtElapsedMillis
                // Receipt evidence is independent of whether this was the newest sample.
                acceptedHealthAt = now()
                source = HeartRateSource.WATCH
                if (!active && !busy) refreshRecommendation(); render() }
        } }
        bridge.onTimedReading = receivePulse
        bridge.onShortcutReading = receivePulse
        bridge.onPhoneReading = receivePulse
        bridge.onDriverIdentity = { identity -> runOnUiThread {
            if (foreground && generation == receiverGeneration) acceptDriverIdentity(identity)
        } }
        bridge.onWorkoutHistory = { history, identity -> runOnUiThread {
            if(foreground && generation==receiverGeneration && acceptsHealthIdentity(identity)) {
                receivedWorkoutHistory=history;workoutHistoryReceivedAt=now()
                if(history.entries.isNotEmpty()) acceptedHealthAt=now()
                // History display never changes the current comfort preference or applies a vehicle command.
                result="최근 24시간 운동 조회 결과를 받았어요 · ${history.entries.size}건"
                render()
            }
        } }
        bridge.onWorkout = { record, identity ->
            runOnUiThread {
            if (foreground && generation == receiverGeneration &&
                (record.source == WorkoutRecordSource.MANUAL_IMPORT || acceptsHealthIdentity(identity))) {
                workoutInbox = workoutInbox.receive(record)
                if (record.source == WorkoutRecordSource.WATCH_RELAY) acceptedHealthAt = now()
                if (record.source != WorkoutRecordSource.MANUAL_IMPORT) source = HeartRateSource.WATCH
                if (!active && !busy) refreshRecommendation()
                result = when {
                    record.source == WorkoutRecordSource.MANUAL_IMPORT -> "직접 가져온 기록을 받았어요. 설정의 데이터 관리에서 내용을 확인하고 적용하세요."
                    record.source == WorkoutRecordSource.PHONE_AUTOMATION ->
                        if (record.canAutoSelect(now()) && followWorkout && !active && !busy)
                            "단축어 운동 종료 이벤트를 반영했어요. 운동 시간·거리를 조회한 기록과는 구분합니다."
                        else "단축어 운동 종료 이벤트를 받았어요. 현재 회복 선호는 유지합니다."
                    record.canAutoSelect(now()) && followWorkout && !active && !busy -> "최근 ${record.kind!!.title} 기록을 반영했어요. 자동 Mode만 켜면 됩니다."
                    active || busy -> "새 운동 기록을 받았어요. 현재 적용한 설정은 유지합니다."
                    else -> "운동 기록을 받았어요. 자동 선택이 꺼져 있거나 최근 자동 선택 조건에 맞지 않아 현재 선호를 유지해요."
                }
                render() }
        } }
        bridge.onConnection = { runOnUiThread { if (foreground && generation == receiverGeneration) render() } }
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            bridge.companionApkProvider = { assets.open("afterglow-phone.apk") }
        bridge.start(); receiver = bridge
    }
    private fun configurePairing() {
        if (busy || !safeToAct()) return
        if (receiver != null) { showWearableConnectionChoices(); return }
        val b = body()
        b.add(text("갤럭시 워치는 IEUM Watch 앱, Apple Watch는 iPhone 건강 앱·단축어로 전달해요.", 26f))
        b.add(text("지금은 별도 PC 중계와 HTTPS 터널을 사용하는 개발용 연결이에요.", 24f, muted), top = 20)
        b.add(text("원본 측정 시각과 실제 수신을 구분해요. 개인화 요약 저장은 별도 동의예요.", 24f, muted), top = 20)
        b.add(text("차량 화면을 나가면 건강 데이터 수신을 닫아요.", 24f, muted), top = 10)
        dialog("워치 데이터 수신", b, "동의하고 연결 방식 선택", {
            prefs.edit().putBoolean("bridge_allowed", true).apply()
            runCatching { startReceiver(); showWearableConnectionChoices() }.onFailure { result = "연결을 열지 못했어요. 잠시 후 다시 시도하세요."; log("연결 시작 실패 · ${it.javaClass.simpleName}"); render() }
        }, "취소")
    }
    private fun showWearableConnectionChoices() {
        val b = body()
        b.add(button("Bluetooth · 휴대폰 직접 연결") { showBluetoothPairing() },80)
        b.add(button("갤럭시 워치 · IEUM Watch 연결") { showGalaxyInternetPairing() }, 80,16)
        b.add(button("Apple Watch · iPhone 인터넷 중계") { showAppleInternetPairing() }, 80, 16)
        b.add(button("최근 24시간 운동 기록") { showWorkoutHistory() }, 80, 16)
        b.add(button("실제 수신 진단") { showConnectionDiagnostics() }, 80, 16)
        b.add(text("Apple Watch 데이터는 iPhone 건강 앱과 단축어로 전달해요.", 23f, muted), top = 20)
        b.add(text("워치 앱 직접 측정은 HTTPS 중계, 휴대폰의 Health Connect 운동·심박은 Bluetooth 직접 연결을 선택할 수 있어요. 차량의 Bluetooth 지원이 필요합니다.", 23f, muted), top = 10)
        dialog("워치 연결 방식", b)
    }
    private fun showBluetoothPairing() {
        if(!foreground || !safeToAct() || busy) return
        if(!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH)) {
            val b=body()
            b.add(text("이 차량 환경은 Bluetooth 직접 연결을 지원하지 않아요.",27f,ink,true))
            b.add(text("현재 에뮬레이터에서는 무선 연결을 시작할 수 없습니다. Bluetooth가 앱에 제공되는 차량 기기에서 검증해야 합니다. 기존 인터넷 연결은 계속 사용할 수 있어요.",24f,muted),top=20)
            dialog("Bluetooth 지원 확인",b);return
        }
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),414);return
        }
        val enabled=runCatching {getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled==true}.getOrDefault(false)
        if(!enabled) {
            val b=body();b.add(text("차량 설정에서 Bluetooth를 켜고 S23과 먼저 페어링해주세요.",26f))
            dialog("Bluetooth 켜기 필요",b);return
        }
        val bridge=receiver ?: return
        if(bluetoothHealthServer==null || bluetoothHealthServer?.isStopped==true) {
            val generation=receiverGeneration
            lateinit var owner:BluetoothHealthServer
            owner=BluetoothHealthServer(applicationContext,bridge.boundPort) { message->runOnUiThread {
                if(foreground && generation==receiverGeneration && bluetoothHealthServer===owner) {
                    bluetoothStatus=message
                    if(::resultLabel.isInitialized) {result=message;render()}
                }
            }}
            bluetoothHealthServer=owner;owner.start()
        }
        val code=bridge.openWatchPairing();val generation=bridge.watchPairingGeneration()
        val b=body()
        b.add(text("S23 → Bluetooth → 차량",27f,mint,true))
        b.add(text("S23의 IEUM 앱에서 ‘Bluetooth 차량 직접 연결’을 누르고 페어링한 차량을 선택하세요. 아래 코드는 2분간 한 번만 사용할 수 있어요.",24f),top=20)
        b.add(text("차량 연결 코드  $code",36f,mint,true),top=24)
        b.add(text("건강 정보 전달 동의와 운동·심박 읽기 권한은 휴대폰에서 허용합니다. 연결만으로 운전자 신원이나 센서 출처를 증명하지 않습니다. 차량 앱 화면을 벗어나면 Bluetooth 수신을 중단합니다.",23f,muted),top=20)
        b.add(button("Bluetooth 직접 연결 중지") {
            bluetoothHealthServer?.stop();bluetoothHealthServer=null;bluetoothStatus="Bluetooth 직접 연결 꺼짐"
            bridge.closeWatchPairing(generation);modal?.dismiss()
        },80,20)
        dialog("Bluetooth · 휴대폰 직접 연결",b,sensitive=true,onDismissAction={bridge.closeWatchPairing(generation)})
    }
    private fun showAppleInternetPairing(forZeroTap: Boolean = false) = showInternetPairing(forZeroTap, false)
    private fun showGalaxyInternetPairing() = showInternetPairing(false, true)
    private fun showInternetPairing(forZeroTap: Boolean, galaxyWatch: Boolean) {
        val bridge = receiver ?: return
        val b = body()
        val code = bridge.openWatchPairing()
        val pairingGeneration = bridge.watchPairingGeneration()
        b.add(text(if (galaxyWatch) "갤럭시 워치 → HTTPS 중계 → 차량" else if (forZeroTap) "휴대폰 → 인터넷 중계 → 차량" else "Apple Watch → iPhone → 인터넷 중계 → 차량", 27f, mint, true))
        b.add(text(if (galaxyWatch) "IEUM Watch 또는 S23의 IEUM 앱 연결 창에 HTTPS 중계 주소와 아래 코드를 입력하세요. 운동 기록 조회는 휴대폰 앱에서 연결합니다." else "PC 중계 도구에 아래 코드를 입력해 이 차량의 수신 권한을 연결하세요. 같은 Wi-Fi나 핫스팟은 필요하지 않습니다.", 24f), top = 18)
        b.add(text("차량 연결 코드  $code", 36f, mint, true), top = 24)
        b.add(text("코드는 2분간 한 번만 쓸 수 있어요. 워치 출처나 운전자 신원을 인증하는 코드는 아니에요.", 22f, muted), top = 18)
        b.add(text(if (galaxyWatch) "외부 HTTPS 중계를 경유하는 개발용 연결이에요. 워치에서 센서·신체 활동 권한과 전송을 허용해야 해요." else "외부 터널을 경유하는 개발용 연결이에요. 단축어의 건강 읽기·전송 허용이 필요해요.", 22f, muted), top = 10)
        if (forZeroTap) {
            b.add(text("제로탭에 휴대폰을 등록하고 충전·NFC 자동화를 설정하세요. 기본 설정은 P단에서만 적용해요.", 22f, muted), top = 18)
            b.add(text("다른 앱을 볼 때도 쓰려면 제로탭의 ‘다른 앱을 보는 동안에도 거치 연결’을 켜세요.", 22f, muted), top = 10)
        } else {
            b.add(text("실제 심박과 원본 측정 시각이 차량에 도착해야 수신으로 표시해요.", 22f, muted), top = 18)
            b.add(text("오래된 표본을 현재 심박으로 쓰거나 차량 설정을 자동 적용하지 않아요.", 22f, muted), top = 10)
        }
        dialog(if (galaxyWatch) "갤럭시 워치 · 인터넷 연결" else "Apple Watch · 인터넷 연결", b, sensitive = true,
            onDismissAction = { bridge.closeWatchPairing(pairingGeneration) })
    }
    private fun showDataSettings() {
        val editor = preferenceEditors.capture()
        val b = body(); b.add(text(sourceLabel.text.toString(), 26f, mint))
        b.add(button("실제 연결 · 수신 진단") { showConnectionDiagnostics() }, 80, 14)
        b.add(toggle("워치 데이터 수신", receiver != null) { control, enabled ->
            if (busy || !safety.allowed) { control.isChecked = receiver != null; return@toggle }
            if (enabled) {
                if (prefs.getBoolean("bridge_allowed", false)) {
                    runCatching { startReceiver() }.onFailure {
                        result = "워치 데이터 수신을 시작하지 못했어요."; log("연결 시작 실패 · ${it.javaClass.simpleName}")
                    }
                    control.isChecked = receiver != null
                    render()
                } else {
                    control.isChecked = false
                    configurePairing()
                }
            } else {
                stopReceiver(); prefs.edit().putBoolean("bridge_allowed", false).apply()
                source = HeartRateSource.MANUAL
                if (!active) refreshRecommendation()
                render()
            }
        }, 80, 16)
        b.add(text("화면을 나가거나 연결을 해제하면 운동 기록과 심박을 비워요. 운동 기록으로 젖음·체온을 확정하지 않아요. 최근 운동과 심박의 유효 시간은 별도로 확인해요.", 24f, muted), top = 18)
        pendingImportedWorkout?.let { imported ->
            val kind = imported.kind
            val ended = imported.endedAtEpochMillis?.let { SimpleDateFormat("MM월 dd일 HH:mm", Locale.KOREA).format(Date(it)) } ?: "미확인"
            b.add(text("직접 가져온 기록\n${kind?.title ?: "미지원 운동 종류"} · ${imported.validDurationMinutes?.let { "${it}분" } ?: "운동 시간 미입력"}\n종료 $ended", 24f, ink), top = 22)
            b.add(text("자동 감지로 처리하지 않아요. 적용을 누른 경우에만 이 기록의 운동 종류·시간을 사용하며, 파일의 심박을 현재 센서값으로 사용하지 않아요.", 22f, muted), top = 12)
            b.add(button(if (kind == null) "이 운동은 직접 지정이 필요해요" else "가져온 ${kind.title} 기록 적용") {
                if (canChange() && kind != null && preferenceEditors.canCommit(editor)) {
                    selectedImportedWorkout = imported; followWorkout = false; source = HeartRateSource.MANUAL
                    refreshRecommendation(); savePreferences()
                    result = "가져온 ${kind.title} 기록을 직접 선택했어요. 자동 Mode를 켜면 저장한 선호를 적용해요."
                    render(); modal?.dismiss()
                }
            }.apply { isEnabled = canChange() && kind != null }, 80, 16)
        }
        b.add(button("연결 QR 보기") { configurePairing() }, 80, 18)
        b.add(button("연결 해제 · 저장된 키 삭제") {
            ZeroTapBackgroundService.stop(this)
            stopReceiver(); secretStore.revoke(); prefs.edit().putBoolean("bridge_allowed", false).remove("bridge_host").apply(); source = HeartRateSource.MANUAL
            if (!active && !busy) refreshRecommendation(); result = "연결과 운동 데이터를 지웠어요."; render(); modal?.dismiss()
        }, 80, 12); dialog("데이터 연결 관리", b)
    }
    private fun startSound(): Boolean {
        stopAutoMusic()
        val provider = musicSource()
        if (provider != MusicSource.AMBIENT) {
            if (!musicAutoResumeEnabled(provider) || comfortMode != ComfortMode.AUTOMATIC || !active || !foreground || !safeToAct()) return false
            val component = runCatching { externalMusic.browserComponent(provider) }.getOrNull()
            if (component == null) { autoMusicStatus = "음악 앱의 재생 연결을 지원하지 않아요."; return false }
            val profile = driverSettings.selectedProfileId
            val vehicle = vehicleSelection().profile.id
            val generation = autoMusicGeneration
            autoMusicStatus = "음악 앱의 재생 상태를 확인 중이에요."
            autoMusicResume = AutoMusicResume(this, component, stillAllowed = {
                generation == autoMusicGeneration && foreground && active && safeToAct() &&
                    !vehicleControlArbiter.backgroundActive && comfortMode == ComfortMode.AUTOMATIC &&
                    driverSettings.selectedProfileId == profile && vehicleSelection().profile.id == vehicle &&
                    musicSource() == provider && musicAutoResumeEnabled(provider)
            }, finished = { message ->
                if (generation == autoMusicGeneration && foreground && active) {
                    autoMusicResume = null; autoMusicStatus = message; render()
                }
            }).also { it.start() }
            // External state is acknowledged asynchronously; dispatch is not playback proof.
            return false
        }
        when (soundPreset) {
            SoundPreset.OFF -> return false
            SoundPreset.WAVES -> sound.start(AmbientSound.Scene.WAVES)
            SoundPreset.RAIN -> sound.start(AmbientSound.Scene.RAIN)
            SoundPreset.FOREST -> sound.start(AmbientSound.Scene.FOREST)
        }
        return sound.isPlaying
    }
    private fun stopAutoMusic() {
        autoMusicGeneration++
        autoMusicResume?.close(); autoMusicResume = null
        autoMusicStatus = ""
    }
    private fun toggleRecovery() {
        if (vehicleControlArbiter.backgroundActive || busy || !safeToAct()) return
        if (active || controller.hasPending) { stopRecovery(); return }
        if (comfortMode == ComfortMode.LEARNING) {
            if (!learningConsent) { showLearningSettings(); return }
            if (!learningStoragePrepared) {
                result = "기록 보호가 준비되지 않아 학습을 시작할 수 없어요."; render(); return
            }
            val initial = actualLearningSetting()
            if (initial == null) { result = "차량의 실제 온도·풍량 응답을 확인한 뒤 학습을 시작할 수 있어요."; render(); return }
        }
        val prepareSession=learningSessionPreparation()
        val requestedMode=comfortMode
        val modeStore=prefs
        val modeKey=preferenceKey("comfort_mode")
        val requestedProfile=driverSettings.selectedProfileId
        val requestedVehicle=vehicleSelection().profile.id
        val requestedDriverGeneration=driverSeatGeneration
        lastObservedSetting = null
        modePreparation(2) { refreshRecommendation() }; busy = true
        progress=if(requestedMode==ComfortMode.LEARNING) "학습 준비 중" else "켜는 중"
        modePreparation(3) { render() }
        scope.launch {
            try {
                // Persist the trip marker durably without holding Main ahead of
                // the visible busy state. A background/profile/vehicle change
                // during IO must never turn into a delayed vehicle command.
                val prepared=withContext(Dispatchers.IO) {
                    modeStore.edit().putString(modeKey,requestedMode.name).apply()
                    prepareSession?.invoke()
                }
                if(!foreground || !safeToAct() || driverBinding==null || driverSeatGeneration!=requestedDriverGeneration ||
                    comfortMode!=requestedMode || driverSettings.selectedProfileId!=requestedProfile || vehicleSelection().profile.id!=requestedVehicle ||
                    (prepared!=null && (!learningConsent || !learningStoragePrepared || learningPrivacy.pending(requestedProfile)))) {
                    learningSession=null
                    result="화면 또는 차량 연결이 바뀌어 실행을 취소했어요."
                    return@launch
                }
                learningSession=prepared?.first
                if(prepared!=null) learningStarted=prepared.second
                if(requestedMode==ComfortMode.LEARNING) {
                    val initial=actualLearningSetting()
                    if(initial==null) {
                        result="차량의 실제 온도·풍량 응답을 확인한 뒤 학습을 시작할 수 있어요."
                        return@launch
                    }
                    lastObservedSetting=initial;active=true
                    result="학습 시작 · 차량 기본 공조에서 직접 조절하세요. 좋아진 시점은 학습 화면에서 확인해 주세요."
                    return@launch
                }
                val applied = controller.apply(plan)
                if (foreground && safeToAct()) { active = true; soundPlaying = startSound(); result = "자동 Mode ON · $applied 개 항목을 차량 응답으로 확인했어요."; log("자동 Mode ON · $applied 항목 확인 · 시간 제한 없음") }
                else controller.restore()
            } catch (e: Exception) {
                result = "설정을 적용하지 못했어요. 복원 상태를 확인해 주세요."; log("실행 실패 · ${e.javaClass.simpleName}")
                runCatching { controller.restore() }.onFailure { log("복원 대기 · ${it.javaClass.simpleName}") }
            } finally {
                telemetry = runCatching { controller.cabinTelemetry() }.getOrNull()
                busy = false
                if (!active) refreshRecommendation()
                render()
            }
        }
    }
    private fun stopRecovery(nextMode:ComfortMode?=null) {
        if (vehicleControlArbiter.backgroundActive || busy) return
        val requestedFromForeground = foreground
        val observationOnly = active && comfortMode == ComfortMode.LEARNING && !controller.hasPending
        val requestedProfile=driverSettings.selectedProfileId
        val requestedVehicle=vehicleSelection().profile.id
        val fallbackRestoration=if(active) VehicleSettingOperation.RECOVERY else VehicleSettingOperation.UNKNOWN
        if (active && comfortMode == ComfortMode.AUTOMATIC && learningConsent && source != HeartRateSource.DEMO) {
            val session = learningSession
            val actual = actualLearningSetting()
            pendingComfortRating = if (session != null && actual != null)
                ComfortCase(session,now(),learningContext(),actual,false) else null
        }
        learningSession = null; lastObservedSetting = null
        active = false; stopAutoMusic()
        if (interactionLatency != null) debugModeSoundStarted = SystemClock.uptimeMillis()
        sound.stop(); soundPlaying = false; busy = true
        if (interactionLatency != null) debugModeSoundFinished = SystemClock.uptimeMillis()
        progress = if (observationOnly) "학습 관찰 종료 중" else "차량 설정 복원 중"
        if (interactionLatency != null) debugModeRenderStarted = SystemClock.uptimeMillis()
        render()
        if (interactionLatency != null) debugModeRenderFinished = SystemClock.uptimeMillis()
        scope.launch {
            var restored=false
            try {
                // Decode authenticated operation metadata off Main. It changes
                // completion wording only; restore still validates the full journal.
                val restoration=withContext(Dispatchers.IO) { controller.pendingOperation ?: fallbackRestoration }
                restored=controller.restore();result = if (restored) {
                if (observationOnly) "학습 관찰을 종료했어요. 공조 설정은 변경하지 않았어요." else restoration.restoredMessage
            } else "복원이 남아 있어요. P단에서 다시 복원해 주세요." }
            catch (e: Exception) { result = "차량 설정 복원이 남아 있어요."; log("복원 대기 · ${e.javaClass.simpleName}") }
            finally {
                telemetry = runCatching { controller.cabinTelemetry() }.getOrNull()
                busy = false; refreshRecommendation(); render()
                if(nextMode!=null && restored && foreground && safeToAct() && !controller.hasPending &&
                    driverSettings.selectedProfileId==requestedProfile && vehicleSelection().profile.id==requestedVehicle && modal==null)
                    selectComfortMode(nextMode)
                else if (requestedFromForeground && nextMode==null && !observationOnly && foreground && safeToAct() && !controller.hasPending && modal == null) showComfortRating()
            }
        }
    }
    private fun log(message: String) {
        android.util.Log.i("AfterglowRecovery", message)
    }
    private fun stopReceiver() {
        bluetoothHealthServer?.stop();bluetoothHealthServer=null;bluetoothStatus="Bluetooth 직접 연결 꺼짐"
        receiverGeneration++
        receiver?.stop(); receiver = null; watchReceivedAt = 0; watchBpm = null
        receivedWorkoutHistory=null;workoutHistoryReceivedAt=0L;workoutInbox = RecoveryWorkoutInbox(); selectedImportedWorkout = null
        acceptedHealthAt = 0; lastPairedIdentity = null
    }
    override fun onStart() {
        super.onStart(); foregroundGate.start()
        PerformanceMarks.event("activity.start")
        reconcileForegroundResources()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("app.updatePrompted",updatePrompted)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume(); screenPower?.refresh(); foregroundGate.resume(); reconcileForegroundResources()
    }
    @androidx.annotation.RequiresApi(29)
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        foregroundGate.top(isTopResumedActivity); reconcileForegroundResources()
    }
    override fun onPause() {
        foregroundGate.pause(); reconcileForegroundResources()
        if (startupBlocked) NativeRenderLifecycle.background(this)
        super.onPause()
    }
    private val inputFocusNotice get() = "이 화면을 터치해 차량 연결을 재개하세요"
    private fun reconcileForegroundResources() {
        if (startupBlocked) return
        if (foregroundGate.allowed && !foreground) resumeForegroundResources()
        else if (!foregroundGate.allowed && foreground) pauseForegroundResources()
        if (foregroundGate.awaitingInputFocus && !foreground) {
            val notice = inputFocusNotice
            footerLabel.updateText(notice)
            footerLabel.visibility = View.VISIBLE
        }
    }
    private fun prepareLearningStorageIfNeeded() {
        if (!learningStoragePrepared && !learningStoragePreparationFailed && learningStoragePreparation?.isActive != true) {
            val consent = learningConsent
            val deletionPending = learningPrivacy.hasPending()
            learningStoragePreparation = scope.launch {
                try {
                    val prepared = withContext(Dispatchers.IO) {
                        if (learningStore.needsPreparation(consent, deletionPending)) {
                            learningStore.prepareProtectedStorage(); true
                        } else false
                    }
                    learningStoragePrepared = prepared
                    if (prepared && foreground) { refreshRecommendation(); render() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    learningStoragePreparationFailed = true
                    result = "개인화 기록을 열지 못했어요. 저장과 학습 추천을 사용할 수 없어요."
                    if (foreground) render()
                } finally { learningStoragePreparation = null }
            }
        }
    }
    private fun resumeForegroundResources() {
        foreground = true; parked = false; lastKnownGear=null; telemetry = null
        syncWeatherTracking()
        if(automaticWeatherEnabled() && WeatherLocation.permitted(this)) refreshWeatherLocation()
        NativeRenderLifecycle.foreground(this)
        learningStoragePreparationFailed = false
        // Protect legacy records / finish durable deletion even with learning OFF.
        // An already protected, inactive database stays closed until consent or cleanup.
        prepareLearningStorageIfNeeded()
        recoverLearningPrivacy()
        vehicleControlArbiter.screen(this, true, busy || active || driverDirectoryWritePending(), ::receiveZeroTap)
        if (ZeroTapBackgroundService.enabled(this) && ZeroTapBackgroundService.notificationsAllowed(this)) {
            runCatching { ZeroTapBackgroundService.start(this) }.onFailure { log("거치 연결 재개 실패") }
        }
        emulatorConsole.start()
        checkAppUpdate()
        PerformanceMarks.event("resources.start")
        PerformanceMarks.measure("vehicle.reader.start") { restartVehicleReader() }
        // Vehicle safety/identity readiness is independent of the optional 3D preview.
        // Unknown driver/gear still blocks events; never defer rejected dock events for replay.
        PerformanceMarks.measure("control.driver.start") { startDriverSeatReader() }
        if (!busy && !active) refreshRecommendation()
        reconcileSceneSafety()
        if (prefs.getBoolean("bridge_allowed", false)) runCatching { startReceiver() }.onFailure { log("연결 수신 재개 실패") }
        poll = scope.launch {
            PerformanceMarks.event("control.poll.start")
            var lastTelemetryRead = 0L
            var previousPreviewInputs = RecoveryPreviewInputs.capture(activitySelection(), reading())
            var previousControlAllowed: Boolean? = null
            while (isActive) {
                if (!busy) {
                    runCatching { controller.gear() }.onSuccess { gear ->
                        if (!parked && gear == VehicleGear.GEAR_PARK) PerformanceMarks.event("control.park.available")
                        lastKnownGear=DrivingNotice.knownGear(gear)
                        parked = gear == VehicleGear.GEAR_PARK
                        if (!safeToAct()) receiver?.closeWatchPairing()
                        if (active && !safeToAct()) { active = false; sound.stop(); soundPlaying = false; result = "주행 조건이 바뀌어 자동 Mode를 껐어요. P단에서 설정을 복원해 주세요." }
                    }.onFailure { parked = false;lastKnownGear=null; if (active) { active = false; sound.stop(); soundPlaying = false; result = "차량 연결이 끊겼어요. P단 연결 후 복원해 주세요." } }
                    syncWeatherTracking()
                    // This is a parked-only app. Keep the independent UX listener and
                    // gear reader alive for a safe return, but do not query comfort,
                    // learning or weather, or rebuild the screen, on every moving tick.
                    if (!safeToAct()) {
                        if (previousControlAllowed != false) { telemetry = null; render() }
                        previousControlAllowed = false
                        delay(1000)
                        continue
                    }
                    if(previousControlAllowed==false && automaticWeatherEnabled() && now()-lastWeatherLocationAttempt>=30_000)
                        refreshWeatherLocation()
                    previousControlAllowed = true
                    if(pendingWeatherLocationPermission) { pendingWeatherLocationPermission=false; refreshWeatherLocation() }
                    if (!vehicleControlArbiter.backgroundActive && parked && !active && controller.hasPending && now() - lastAutomaticRestoreAt >= 30_000) {
                        lastAutomaticRestoreAt = now()
                        stopRecovery()
                    }
                    if (!busy && now() - lastTelemetryRead >= 3000) {
                        telemetry = runCatching { controller.cabinTelemetry() }.getOrNull(); lastTelemetryRead = now()
                        observeLearningAdjustment()
                        if (now() - lastLearningQuery >= 15_000) requestLearningRecommendation()
                        refreshWeatherIfNeeded()
                    }
                    val previewInputs = RecoveryPreviewInputs.capture(activitySelection(), reading())
                    if (previewInputs.needsRefresh(previousPreviewInputs, active)) refreshRecommendation()
                    previousPreviewInputs = previewInputs; render()
                    pendingBackgroundEnrollment?.let { enrollment ->
                        if (ZeroTapBackgroundService.notificationsAllowed(this@MainActivity) && canChange() && safeToAct()) {
                            pendingBackgroundEnrollment = null
                            enableBackgroundDock(enrollment.first, enrollment.second)
                        }
                    }
                    if (!vehicleControlArbiter.backgroundActive && canChange()) {
                        // A previous write with an unconfirmed rollback must not
                        // crash this foreground loop or select a default driver.
                        val savedDirectory = runCatching { driverStore.load() }.getOrNull()
                        if (savedDirectory != null && savedDirectory != driverSettings) {
                            selectDriver(savedDirectory.selectedProfileId, savedDirectory)
                        }
                    }
                }
                maybePromptUpdate()
                delay(1000)
            }
        }; PerformanceMarks.measure("screen.startRender") { render() }
        PerformanceMarks.event("resources.start.done")
    }
    override fun onStop() {
        foregroundGate.stop(); reconcileForegroundResources(); super.onStop()
    }
    private fun pauseForegroundResources() {
        stopAutoMusic()
        stopWeatherTracking(false)
        PerformanceMarks.event("resources.stop")
        if(updateCheck?.isActive==true && updateStatus=="업데이트 확인 중") updateStatus="업데이트 확인이 중단됐어요."
        updateCheckGeneration++
        updateCheck?.cancel(); updateCheck = null
        cancelSettingsOpening()
        scene.setDetailsVisible(false)
        settingsButton.updateText("설정"); settingsButton.contentDescription = null
        driverSeatGeneration++; driverSeatReader?.close(); driverSeatReader = null
        driverSeatState = DriverSeatState.Loading
        vehicleReaderGeneration++; vehicleReader?.close(); vehicleReader = null
        vehicleIdentityState = VehicleIdentityState.Unavailable("차량 정보 조회 대기")
        foreground = false; preferenceEditors.invalidate(); modal?.dismiss(); poll?.cancel(); stopReceiver()
        vehicleControlArbiter.screen(this, false, busy || active || driverDirectoryWritePending(), null)
        if (source != HeartRateSource.DEMO) source = HeartRateSource.MANUAL
        if (!busy && (active || controller.hasPending)) stopRecovery()
        else if (!busy && !active) refreshRecommendation()
        sound.stop(); soundPlaying = false
        sceneRunning = false
        PerformanceMarks.measure("scene.pause") { scene.pauseScene() }
        NativeRenderLifecycle.background(this)
    }
    private fun reconcileSceneSafety() {
        val next = foreground && safeToAct()
        if (sceneRunning == next) return
        // Keep an existing viewer when a panel merely pauses submissions. A new
        // native engine must not be created behind a foreground panel or its
        // opening progress frame while gear readiness arrives asynchronously.
        if(next && (modal != null || settingsOpening)) return
        sceneRunning = next
        if (next) PerformanceMarks.measure("scene.resume") { scene.resumeScene() }
        else PerformanceMarks.measure("scene.safety.pause") { scene.pauseScene(keepLastFrame=foreground && !safeToAct()) }
    }
    @Deprecated("Deprecated by Android") override fun onBackPressed() {
        if (modal != null) modal?.dismiss() else moveTaskToBack(true)
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::scene.isInitialized) scene.trimMemory(level)
    }
    @Deprecated("Deprecated by Android") override fun onLowMemory() {
        super.onLowMemory()
        if (::scene.isInitialized) scene.lowMemory()
    }
    override fun onDestroy() {
        cancelInlineBack?.invoke(); cancelInlineBack = null
        screenPower?.close(); screenPower = null
        if (startupBlocked) { scope.cancel(); super.onDestroy(); return }
        interactionLatency?.close(); interactionLatency = null
        foregroundGate.stop(); reconcileForegroundResources()
        settingsPanelCache?.dismiss()
        settingsPanelCache = null; settingsPanelScrollCache = null
        driverSeatGeneration++; driverSeatReader?.close(); driverSeatReader = null
        vehicleReaderGeneration++; vehicleReader?.close(); vehicleReader = null
        scope.launch {
            // Admission is process-wide, but this screen releases its controller
            // once its own work ends; unrelated writers must not retain it.
            while (busy || driverWriteInFlight) delay(100)
            runCatching { if (!vehicleControlArbiter.backgroundActive && controller.hasPending) controller.restore() }
            vehicleControlArbiter.removeScreen(this@MainActivity)
            controller.release(); emulatorConsole.close(); safety.close()
            withContext(Dispatchers.IO) { runCatching { learningStore.close() } }
            scope.cancel()
        }
        super.onDestroy()
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) lastTouchDownUptime = event.eventTime
        interactionLatency?.input(event)
        return try { super.dispatchTouchEvent(event) } finally { interactionLatency?.handled() }
    }
}
