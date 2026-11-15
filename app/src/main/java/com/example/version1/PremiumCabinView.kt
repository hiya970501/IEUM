package com.example.version1

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.filament.EntityManager
import com.google.android.filament.Engine
import com.google.android.filament.Box
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset

import com.google.android.filament.utils.Bookmark
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.BudgetModelViewer
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import com.google.android.filament.View as FilamentView

/**
 * Budgeted PBR vehicle preview with small authored setting illustrations. A whole-car preview
 * retains no duplicate reference interior. Assets require offline validation and process-memory
 * admission, and replacements release the old model before any new input is allocated.
 */
class PremiumCabinView(context: Context) : FrameLayout(context) {
    private val presentation = PreviewPresentationGate()
    private var presentationWaitStarted = 0L
    private var textureUpdateCount = 0L
    private var lastTextureTimestamp = 0L
    private var acceptedFrameCount = 0L
    private var lastRenderDiagnosticAt = 0L
    private fun coverPreview() {
        presentation.begin(); presentationWaitStarted = 0L
        message.text = "선택한 차량을 불러오는 중"
        message.visibility = VISIBLE
        contentDescription = message.text
        Log.i(TAG, "Vehicle preview requested: requested=$vehicleProfileId, displayRightHandDrive=$rightHandDrive")
    }
    /** Avoid competing startup work while the first native model is importing.
     * Failure also releases readers; an unavailable visualization must not disable
     * actual vehicle state. Caller cancellation belongs to the foreground screen.
     */
    suspend fun awaitInitialLoad() {
        kotlinx.coroutines.withTimeoutOrNull(20_000) {
            while ((loadedShellProfileId == null || presentation.covered) && !shellLoadFailed && !presentation.failed) {
                kotlinx.coroutines.delay(50)
            }
        }
    }
    private var modelViewer: BudgetModelViewer? = null
    private var renderSurface: TextureView? = null
    private var surfaceUiHelper: UiHelper? = null
    private var referenceFullVehicle: Boolean? = null
    private var referenceGeneration = 0
    private var resumeCameraBookmark: Bookmark? = null
    private var loadScope: CoroutineScope? = null
    private val modelIoMutex = Mutex()
    private var loadJob: Job? = null
    private var shellLoadJob: Job? = null
    private var shellLoading = false
    private var shellLoadFailed = false
    private var shellGeneration = 0
    private var selectionAttempt = 0L
    private var budgetRecoveryUsed = false
    private var importGarbageRecovery = java.util.concurrent.atomic.AtomicBoolean(false)
    private var budgetRetryRunnable: Runnable? = null
    private var budgetRetryJob: Job? = null
    private var vehicleProfileId = VehicleCatalog.GENERIC_ID
    private var loadedShellProfileId: String? = null
    private var rightHandDrive = false
    private var exteriorTransparency = 55
    private var exteriorMaterials = emptyList<ExteriorMaterial>()
    private data class ThermalMaterial(val instance: MaterialInstance, val base: FloatArray, val wheel: Boolean)
    private var thermalMaterials = emptyList<ThermalMaterial>()
    private var thermalUniformWriteCount = 0L
    private var effectLayerWriteCount = 0L
    private var loadedShellRightHandDrive: Boolean? = null
    private var loadedPlacement: VehiclePlacement? = null
    private var shellAsset: FilamentAsset? = null
    private var shellAssetLoader: AssetLoader? = null

    private val sceneTransform = FloatArray(16).apply {
        // A single metre-based framing transform preserves size differences between models.
        // Never independently normalize the shell and cabin: their authored coordinates align.
        Matrix.setIdentityM(this, 0)
        Matrix.scaleM(this, 0, .50f, .50f, .50f)
        Matrix.translateM(this, 0, 0f, -.62f, 0f)
    }
    private data class VehicleVisual(
        val id: String,
        val assetPath: String,
        val interiorScale: FloatArray,
        val interiorTranslation: FloatArray,
        val shellTransform: FloatArray,
        val fullVehicle: Boolean,
        val sourceRightHandDrive: Boolean,
        val interiorMirrorRoot: String?,
        val variantHandedness: Boolean?,
        val exteriorColors: Map<String, ExteriorColor>,
        val thermalGeometry: Map<String, List<ThermalPrimitive>>,
        val memoryProfile: VehicleMemoryProfile,
        val sourceBytes: ByteArray?,
        val inputBufferCopies: Int,
        val buffer: ByteBuffer
    )
    private data class ExteriorColor(val red: Float, val green: Float, val blue: Float, val alphaScale: Float)
    private data class ExteriorMaterial(val instance: MaterialInstance, val color: ExteriorColor)
    private class BudgetAdmissionException(val userMessage: String, detail: String,
        val retryable: Boolean = false) : IllegalStateException(detail)
    private data class VehiclePlacement(
        val interiorScale: FloatArray,
        val interiorTranslation: FloatArray,
        val fullVehicle: Boolean,
        val sourceRightHandDrive: Boolean,
        val interiorRootEntity: Int,
        val interiorBaseTransform: FloatArray,
        val variantHandedness: Boolean?
    )
    private data class PreviewMetadata(val modifiedAt: Long, val size: Long, val fullVehicle: Boolean)
    private val previewMetadataCache = mutableMapOf<String, PreviewMetadata>()
    private var state = CabinSceneState()
    // The Activity explicitly grants foreground ownership before native allocation.
    private var resumed = false
    private var scheduled = false
    private var loading = false
    private val frameBudget = RenderFrameBudget()
    private var touchDown = false
    private var generation = 0
    private var projectionReady = false
    private var firstRenderMeasured = false
    private var projectionWidth = 0
    private var projectionHeight = 0
    private var lastMemoryCheckAt = 0L
    private var memoryCheckJob: Job? = null
    private var appliedState: CabinSceneState? = null
    private var lastSubmittedState: CabinSceneState? = null
    private var cameraManipulator: Manipulator? = null
    private var initialCameraBookmark: Bookmark? = null
    private var effectEntities = emptyList<Pair<Int, String>>()
    private var extraLights = emptyList<Int>()
    private var ownedSkybox: Skybox? = null
    private var ownedIndirectLight: IndirectLight? = null
    private val resetGesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetCamera()
            return true
        }
    })
    @SuppressLint("AppCompatCustomView") // Native Activity/theme; this cover needs no AppCompat tinting.
    private val message = object : TextView(context) {
        // Keep loading opaque. The non-opaque hints allow the TextureView below to
        // consume buffers while the actual presentation gate still covers old frames.
        override fun isOpaque(): Boolean = false
    }.apply {
        text = "실내 모델 준비 중"
        setTextColor(PleosDesign.secondary)
        typeface = PleosDesign.typeface(context)
        textSize = 24f
        gravity = Gravity.CENTER
        background = object : ColorDrawable(Color.rgb(12, 21, 27)) {
            @Deprecated("Android opacity hint") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            val viewer = modelViewer ?: return
            if (!resumed || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
            if (presentation.failed) return
            if (detailsVisible) {
                stopFrames()
                return
            }
            // Both reference and car resources load synchronously before presentation.
            // Avoid allocating hidden frame targets while import still needs its headroom.
            if (viewer.asset == null) {
                if (loading || loadJob?.isActive == true) scheduleFrame()
                return
            }
            checkMemoryBudget()
            if (loading || shellLoading || shellAsset == null) {
                if (loading || shellLoading || loadJob?.isActive == true || shellLoadJob?.isActive == true) scheduleFrame()
                return
            }
            applyVehicleEffects(viewer)
            try {
                // TextureView layout can precede the native surface callback. Only Filament's
                // positive viewport dimensions guarantee a finite aspect ratio for lens setters.
                // UiHelper 1.75's first availability callback reports the full TextureView size
                // even with a desired buffer size. Correct its viewport before the first render.
                surfaceUiHelper?.let { helper ->
                    val current = viewer.view.viewport
                    if (current.width > 0 && current.height > 0 &&
                        (current.width != helper.desiredWidth || current.height != helper.desiredHeight)) {
                        helper.setDesiredSize(helper.desiredWidth, helper.desiredHeight)
                    }
                }
                val viewport = viewer.view.viewport
                if (viewport.width > 0 && viewport.height > 0 &&
                    (!projectionReady || viewport.width != projectionWidth || viewport.height != projectionHeight)) {
                    // Preserve horizontal framing as the vehicle's split-screen window narrows.
                    // Wide layouts retain the studio lens; narrow layouts widen its field of view.
                    val aspect = viewport.width.toFloat() / viewport.height
                    viewer.cameraFocalLength = (38f * min(1f, aspect / 1.35f)).coerceAtLeast(14f)
                    viewer.cameraNear = .03f
                    viewer.cameraFar = 100f
                    projectionReady = true
                    projectionWidth = viewport.width
                    projectionHeight = viewport.height
                }
                val ticket = presentation.current
                val ready = !loading && !shellLoading && !shellLoadFailed && shellAsset != null &&
                    loadedShellProfileId == vehicleProfileId && loadedShellRightHandDrive == rightHandDrive
                if (ready && ticket != null) {
                    presentation.markReady(ticket)
                    if (presentation.awaitingTexture && presentationWaitStarted == 0L) {
                        presentationWaitStarted = SystemClock.elapsedRealtime()
                    }
                }
                val accepted = if (!firstRenderMeasured) {
                    firstRenderMeasured = true
                    PerformanceMarks.measure("scene.firstRenderCall") { viewer.render(frameTimeNanos) }
                } else viewer.render(frameTimeNanos)
                frameBudget.submitted(accepted, SystemClock.elapsedRealtime())
                if (ticket != null) presentation.submitted(ticket, accepted, viewer.framePacer.expectedPresentationTimeNanos)
                if (accepted) {
                    acceptedFrameCount++
                    lastSubmittedState = state
                }
                if (accepted && frameBudget.remaining == 0 && !touchDown && !presentation.covered &&
                    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    Log.i(TAG, "Frame burst done: attempts=${viewer.renderAttemptCount}, submitted=${viewer.submittedFrameCount}, " +
                        "gpuSkips=${viewer.gpuBacklogSkipCount}, cadenceSkips=${viewer.cadenceSkipCount}")
                }
                if (!accepted && presentation.awaitingTexture && SystemClock.elapsedRealtime() - lastRenderDiagnosticAt >= 1000) {
                    lastRenderDiagnosticAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Vehicle frame waiting: surfaceReady=${surfaceUiHelper?.isReadyToRender} " +
                        "gpuBehind=${viewer.renderer.hasGpuFallenBehind()} pacing=${viewer.framePacer.pacingStatus} " +
                        "frame=$frameTimeNanos expected=${viewer.framePacer.expectedPresentationTimeNanos} " +
                        "deadline=${viewer.framePacer.renderingDeadlineNanos} accepted=$acceptedFrameCount updates=$textureUpdateCount")
                }
                if (loading && viewer.asset != null && viewer.progress >= .999f) {
                    loading = false
                    if (!shellLoadFailed) {
                        if (shellLoading && shellAsset == null) {
                            message.text = "차량 모델 준비 중"
                            message.visibility = VISIBLE
                        }
                    }
                    frameBudget.request(12, SystemClock.elapsedRealtime())
                }
                if (presentation.awaitingTexture && presentationWaitStarted > 0L &&
                    SystemClock.elapsedRealtime() - presentationWaitStarted > 15_000L) {
                    Log.e(TAG, "Vehicle presentation failed for $vehicleProfileId: updates=$textureUpdateCount, last=$lastTextureTimestamp, required=${presentation.requiredTimestamp}, accepted=$acceptedFrameCount")
                    showFailure("차량 화면을 확인하지 못했어요. 다른 차량을 선택하거나 앱을 다시 열어주세요.")
                    stopFrames(); return
                }
            } catch (error: Exception) {
                Log.e(TAG, "Cabin render failed", error)
                loading = false
                frameBudget.clear()
                showFailure("실내 모델을 표시할 수 없습니다")
                return
            }
            if (!loading && !shellLoading && !presentation.covered && frameBudget.stalled(SystemClock.elapsedRealtime())) {
                Log.e(TAG, "Vehicle setting frame update stalled for $vehicleProfileId")
                showFailure("차량 화면을 갱신하지 못했어요. 앱을 다시 열어주세요.")
                stopFrames(); return
            }
            if (loading || presentation.awaitingTexture || touchDown || frameBudget.remaining > 0) scheduleFrame()
        }
    }

    init {
        setBackgroundColor(Color.rgb(12, 21, 27))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = "차량 실내 3D 미리보기. 드래그하여 회전하고 두 손가락으로 확대할 수 있습니다. 두 번 탭하면 시점을 초기화합니다."
    }

    fun update(value: CabinSceneState) {
        val wasFlowing = airflowOn(state)
        val temperatureDescription = value.driverTemperature?.let { "운전석 설정 온도 ${it}도" } ?: "운전석 설정 온도 미확인"
        val description = if (presentation.covered) message.text else if (value.connected) {
            "차량 실내 3D 미리보기. $temperatureDescription, " +
                "풍량 ${value.fan}, 열선 ${value.heat}, 통풍 ${value.ventilation}, 냉각 ${value.seatCooling}, 핸들 열선 ${value.steeringHeat ?: "미확인"}, 송풍 ${value.airDirection?.label ?: "미확인"}. 드래그하여 회전. 두 번 탭하여 시점 초기화."
        } else "차량 실내 3D 미리보기. 현재 차량 데이터를 확인할 수 없습니다. 드래그하여 회전. 두 번 탭하여 시점 초기화."
        if (contentDescription != description) contentDescription = description
        if (state == value && appliedState != null) return
        state = value
        if (wasFlowing && !airflowOn(value) && !loading) {
            // Clear any queued old setting before submitting the new static paths.
            stopFrames()
            requestFrames(1)
        } else {
            // A setting is applied before its accepted submission; TextureView delivers
            // that buffer asynchronously. Do not render the same static setting twice.
            // Loading and direct manipulation retain their
            // separate frame budgets. Static authored flow paths show direction;
            // decorative particle motion no longer redraws the whole vehicle.
            requestFrames(if (appliedState == null) 12 else 1)
        }
    }

    /** End the previous vehicle's entire provider lifetime before starting another. */
    fun setVehicle(profileId: String) {
        val safeId = profileId.takeIf { it.matches(Regex("[a-z0-9_]+")) } ?: VehicleCatalog.GENERIC_ID
        if (vehicleProfileId == safeId) return
        beginSelectionAttempt()
        vehicleProfileId = safeId
        coverPreview()
        disposeViewer()
        if (resumed && isAttachedToWindow) createViewer()
    }

    /** Requested display layout, independent of the source vehicle's market specification. */
    fun setRightHandDrive(enabled: Boolean) {
        if (rightHandDrive == enabled) return
        beginSelectionAttempt()
        rightHandDrive = enabled
        coverPreview()
        // Invalidate an in-flight read even when the currently displayed vehicle can be reused.
        shellLoadJob?.cancel()
        shellLoadJob = null
        shellGeneration++
        val viewer = modelViewer?.takeIf { it.asset != null } ?: run {
            if (modelViewer == null && resumed && isAttachedToWindow) createViewer()
            return
        }
        val placement = loadedPlacement
        val realVariantAvailable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            File(File(context.filesDir, "vehicle-previews"),
                "$vehicleProfileId.${if (enabled) "rhd" else "lhd"}.glb").isFile
        val canReuse = loadedShellProfileId == vehicleProfileId && shellAsset != null && placement != null &&
            (!realVariantAvailable || placement.variantHandedness == enabled) &&
            (!placement.fullVehicle || placement.sourceRightHandDrive == enabled || placement.interiorRootEntity != 0)
        if (canReuse) {
            shellLoading = false
            shellLoadFailed = false
            applyVehiclePlacement(viewer, requireNotNull(placement), enabled)
            loadedShellRightHandDrive = enabled
            requestFrames(18)
        } else {
            disposeViewer()
            if (resumed && isAttachedToWindow) createViewer()
        }
    }

    /** Body transparency only: upholstery, dashboard, wheels, lamps and live effects stay intact. */
    fun setExteriorTransparency(percent: Int) {
        val value = percent.coerceIn(0, 100)
        if (exteriorTransparency == value) return
        exteriorTransparency = value
        applyExteriorTransparency(exteriorMaterials)
        requestFrames(18)
    }

    /** Presence only, for the containing screen's explicit preview-source label. */
    fun hasLocalVehiclePreview(profileId: String): Boolean {
        if (!profileId.matches(Regex("[a-z0-9_]+")) ||
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return false
        val directory = File(context.filesDir, "vehicle-previews")
        return File(directory, "$profileId.json").isFile &&
            listOf("$profileId.glb", "$profileId.lhd.glb", "$profileId.rhd.glb").any { File(directory, it).isFile }
    }

    /** The sidecar explicitly distinguishes an actual whole vehicle from an exterior preview. */
    fun hasFullVehiclePreview(profileId: String): Boolean {
        if (!hasLocalVehiclePreview(profileId)) {
            previewMetadataCache.remove(profileId)
            return false
        }
        val sidecar = File(File(context.filesDir, "vehicle-previews"), "$profileId.json")
        val modifiedAt = sidecar.lastModified()
        val size = sidecar.length()
        previewMetadataCache[profileId]?.let { cached ->
            if (cached.modifiedAt == modifiedAt && cached.size == size) return cached.fullVehicle
        }
        val fullVehicle = try {
            JSONObject(sidecar.readText()).optBoolean("fullVehicle", false)
        } catch (_: Exception) {
            false
        }
        previewMetadataCache[profileId] = PreviewMetadata(modifiedAt, size, fullVehicle)
        return fullVehicle
    }

    private var restrictedFrame:android.graphics.Bitmap?=null
    private var restrictedImage:android.widget.ImageView?=null
    private fun clearRestrictedFrame() {
        restrictedImage?.let { it.setImageDrawable(null);removeView(it) };restrictedImage=null
        restrictedFrame?.recycle();restrictedFrame=null
    }
    fun pauseScene(keepLastFrame:Boolean=false) {
        if(keepLastFrame && restrictedFrame==null && !presentation.covered) {
            val texture=renderSurface
            if(texture!=null && texture.isAvailable && texture.width>0 && texture.height>0) {
                // A bounded still image replaces the released renderer; never keep a hidden engine alive.
                val scale=minOf(1f,640f/maxOf(texture.width,texture.height))
                val bitmap=runCatching { texture.getBitmap(maxOf(1,(texture.width*scale).toInt()),maxOf(1,(texture.height*scale).toInt())) }.getOrNull()
                if(bitmap!=null) {
                    restrictedFrame=bitmap
                    restrictedImage=android.widget.ImageView(context).apply {
                        setImageBitmap(bitmap);scaleType=android.widget.ImageView.ScaleType.FIT_XY
                        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }.also { addView(it,LayoutParams(-1,-1)) }
                }
            }
        } else if(!keepLastFrame) clearRestrictedFrame()
        resumed = false
        NativeRenderLifecycle.cancel(this)
        disposeViewer()
    }

    fun resumeScene() {
        clearRestrictedFrame()
        if (!resumed) beginSelectionAttempt()
        resumed = true
        if (modelViewer == null && isAttachedToWindow) createViewer()
        requestFrames(18)
    }

    /** Also called by the containing screen's poll, including when this scene is idle. */
    fun checkMemoryBudget() {
        val scope = loadScope ?: return
        val now = SystemClock.elapsedRealtime()
        if (memoryCheckJob?.isActive == true || now - lastMemoryCheckAt < 2_000L) return
        lastMemoryCheckAt = now
        val thisGeneration = generation
        memoryCheckJob = scope.launch {
            val memory = withContext(Dispatchers.IO) { ProcessMemoryReader.read() }
            if (thisGeneration == generation && memory != null && MemoryBudgetPolicy.shouldRelease(memory)) {
                Log.w(TAG, "Releasing 3D resources at process RSS=${memory.rssBytes}, PSS=${memory.pssBytes}")
                disposeViewer()
                showFailure("메모리 예산을 위해 3D 표시를 중지했습니다")
            }
        }
    }

    fun trimMemory(level: Int) {
        if(MemoryTrimPolicy.shouldReleaseForPressure(level,foreground=resumed)) clearRestrictedFrame()
        if (modelViewer == null) return // Already released by the foreground lifecycle.
        if (MemoryTrimPolicy.shouldReleaseForPressure(level, foreground = resumed)) {
            releaseForMemoryPressure("memory trim level=$level")
        } else {
            // A queued background notice must not destroy the new foreground scene.
            // The independent actual RSS/PSS ceiling still applies after reentry.
            PerformanceMarks.event("scene.trim.notice.recheck")
            checkMemoryBudget()
        }
    }

    fun lowMemory() = releaseForMemoryPressure("critical low memory")

    private fun releaseForMemoryPressure(reason: String) {
        clearRestrictedFrame()
        if (modelViewer == null) return
        Log.w(TAG, "Releasing 3D resources on $reason")
        disposeViewer()
        showFailure("메모리 예산을 위해 3D 표시를 중지했습니다")
    }

    /** Reset the inspection camera without resetting the health or vehicle settings. */
    fun resetCamera() {
        initialCameraBookmark?.let { bookmark -> cameraManipulator?.jumpToBookmark(bookmark) }
        requestFrames(12)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (resumed && modelViewer == null) createViewer()
        requestFrames(24)
    }

    override fun onDetachedFromWindow() {
        clearRestrictedFrame()
        disposeViewer()
        super.onDetachedFromWindow()
    }

    /** Removing the child runs our native cleanup before BudgetModelViewer's engine detach listener. */
    private fun disposeViewer() {
        NativeRenderLifecycle.cancel(this)
        presentation.detach(); presentationWaitStarted = 0L
        cancelBudgetRetry()
        stopFrames()
        generation++
        referenceGeneration++
        shellGeneration++
        loadJob?.cancel()
        shellLoadJob?.cancel()
        loadScope?.cancel()
        loadScope = null
        loadJob = null
        shellLoadJob = null
        memoryCheckJob = null
        resumeCameraBookmark = cameraManipulator?.currentBookmark ?: resumeCameraBookmark
        val surface = renderSurface
        modelViewer = null
        cameraManipulator = null
        initialCameraBookmark = null
        renderSurface = null
        surfaceUiHelper = null
        referenceFullVehicle = null
        loading = false
        shellLoading = false
        shellLoadFailed = false
        effectEntities = emptyList()
        appliedState = null
        lastSubmittedState = null
        if (surface != null) removeView(surface)
    }

    /** Only a new explicit selection or foreground entry receives another recovery attempt. */
    private fun beginSelectionAttempt() {
        cancelBudgetRetry()
        selectionAttempt++
        budgetRecoveryUsed = false
        importGarbageRecovery = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private fun cancelBudgetRetry() {
        budgetRetryRunnable?.let(::removeCallbacks)
        budgetRetryRunnable = null
        budgetRetryJob?.cancel()
        budgetRetryJob = null
    }

    private fun retryBudgetAdmission(error: Exception): Boolean {
        if (error !is BudgetAdmissionException || !error.retryable || budgetRecoveryUsed ||
            !resumed || !isAttachedToWindow || !isShown) return false
        budgetRecoveryUsed = true
        val attempt = selectionAttempt
        val profileId = vehicleProfileId
        val handedness = rightHandDrive
        Log.w(TAG, "Retrying vehicle load after dynamic memory budget rejection: attempt=$attempt, profile=$profileId")
        // This invalidates every old continuation and destroys the whole engine, including the
        // reference shader archive and driver pools. Its catch/finally must not reuse that engine.
        disposeViewer()
        val retryGeneration = generation
        message.text = "차량 모델 준비 중"
        message.visibility = VISIBLE
        val retry = object : Runnable {
            override fun run() {
                if (budgetRetryRunnable !== this) return
                if (attempt != selectionAttempt || retryGeneration != generation ||
                    vehicleProfileId != profileId || rightHandDrive != handedness ||
                    !resumed || !isAttachedToWindow || !isShown || modelViewer != null) {
                    budgetRetryRunnable = null; return
                }
                if (!NativeRenderLifecycle.nativeReady) {
                    // disposeViewer() requests asynchronous teardown. Its return does not
                    // mean native owners or their managed wrappers can be reclaimed yet.
                    NativeRenderLifecycle.whenReady(this@PremiumCabinView,
                        ready = { if (budgetRetryRunnable === this) post(this) },
                        blocked = {
                            if (budgetRetryRunnable === this) {
                                budgetRetryRunnable = null
                                showFailure("이전 3D 화면의 정리를 확인하지 못했어요")
                            }
                        })
                    return
                }
                budgetRetryRunnable = null
                // The failed import has left its catch/finally AND actual native release
                // has completed. Native pressure need not trigger ART's managed collector.
                // Request it once off-main; this is only a hint, never admission credit.
                budgetRetryJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val before = ProcessMemoryReader.read()
                            // The old engine's actual backend release has completed above.
                            // Its now-unused allocator pages can be purged before constructing
                            // the replacement engine, rather than only inside that engine.
                            PerformanceMarks.measure("scene.memory.reclaim") {
                                Runtime.getRuntime().gc()
                                NativeHeapReclaimer.releaseUnused()
                            }
                            val after = ProcessMemoryReader.read()
                            Log.i(TAG, "Memory recovery hint: beforeRss=${before?.rssBytes}, afterRss=${after?.rssBytes}, afterPss=${after?.pssBytes}")
                        }
                        if (attempt != selectionAttempt || retryGeneration != generation ||
                            vehicleProfileId != profileId || rightHandDrive != handedness ||
                            !resumed || !isAttachedToWindow || !isShown || modelViewer != null) return@launch
                        // A real new engine/import attempt creates new bounded parsing
                        // garbage. Give it one pressure reclaim too; the outer recovery
                        // remains limited to one retry per explicit selection/foreground.
                        importGarbageRecovery = java.util.concurrent.atomic.AtomicBoolean(false)
                        // createViewer and both import boundaries re-read actual RSS/PSS.
                        createViewer()
                    } finally {
                        if (budgetRetryJob === coroutineContext[Job]) budgetRetryJob = null
                    }
                }
            }
        }
        budgetRetryRunnable = retry
        if (!post(retry)) {
            budgetRetryRunnable = null
            showFailure(error.userMessage)
        }
        return true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) requestFrames(18) else stopFrames()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) requestFrames(18) else stopFrames()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeRenderBuffer(w, h)
        requestFrames(18)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createViewer() {
        if (!NativeRenderLifecycle.nativeReady) {
            message.text = "이전 차량 화면 정리 중"; message.visibility = VISIBLE
            NativeRenderLifecycle.whenReady(this,
                ready = { if (resumed && isAttachedToWindow && modelViewer == null) createViewer() },
                blocked = { if (resumed && isAttachedToWindow) showFailure("이전 3D 화면의 정리를 확인하지 못했어요") })
            return
        }
        val memory = PerformanceMarks.measure("scene.memory.read") { ProcessMemoryReader.read() }
        PerformanceMarks.memory("scene.viewer.before", memory)
        if (memory == null || MemoryBudgetPolicy.shouldRelease(memory)) {
            showFailure("메모리 예산에 맞춰 3D 표시를 준비할 수 없습니다")
            return
        }
        generation++
        val presentationSurface = presentation.attach()
        textureUpdateCount = 0L; lastTextureTimestamp = 0L; acceptedFrameCount = 0L; lastRenderDiagnosticAt = 0L
        coverPreview()
        renderSurface?.let { removeView(it) }
        // A detached BudgetModelViewer cannot be reused. A fresh TextureView also drops old listeners.
        while (childCount > 1) removeViewAt(0)
        val surface = TextureView(context).apply {
            isOpaque = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        renderSurface = surface
        try {
            PerformanceMarks.measure("scene.native.init") { Utils.init() }
            val manipulator = Manipulator.Builder()
                .targetPosition(0f, -.08f, .15f)
                .orbitHomePosition(-.95f, 1.30f, 1.28f)
                .upVector(0f, 1f, 0f)
                .orbitSpeed(.0038f, .0038f)
                .zoomSpeed(.006f)
                .viewport(width.coerceAtLeast(1), height.coerceAtLeast(1))
                .build(Manipulator.Mode.ORBIT)
            cameraManipulator = manipulator
            // homeBookmark resets orbit angles to zero; retain the configured studio viewpoint.
            initialCameraBookmark = manipulator.currentBookmark
            resumeCameraBookmark?.let(manipulator::jumpToBookmark)
            // Detach stops submissions immediately. Destructive owned-resource
            // cleanup belongs inside the viewer's tracked backend-idle release.
            surface.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    stopFrames()
                }
            })
            val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            surfaceUiHelper = uiHelper
            resizeRenderBuffer(width, height)
            val viewer = PerformanceMarks.measure("scene.viewer.create") {
                // This view does not consume Filament's GPU-complete profiling history.
                // Avoid the extra fence-waiting profiler worker during renderer teardown;
                // normal rendering fences and SurfaceTexture presentation checks stay active.
                // Vulkan capability alone does not establish repeat-load memory safety.
                // The full audio/power sequence exceeded our unchanged admission budget
                // on that backend. Keep one validated backend for the whole lifecycle;
                // never retry an admission failure by overlapping another live engine.
                val engine = Engine.Builder().backend(Engine.Backend.OPENGL)
                    .feature("engine.frame_info.disable_gpu_complete_metric", true).build()
                Log.i(TAG, "Renderer backend: preferred=OPENGL actual=${engine.backend}")
                try {
                    BudgetModelViewer(surface, engine = engine, uiHelper = uiHelper, manipulator = manipulator)
                } catch (error: Throwable) {
                    DeferredRendererRelease.schedule(engine) { engine.destroy() }
                    throw error
                }
            }
            Log.i(TAG, "Memory budget renderer: all devices use <=1152x648 buffers, HDR LOW, FXAA, no MSAA/SSAO/shadow maps")
            viewer.setOwnedResourceCleanup {
                releaseVehicleShell(viewer)
                releaseStudio(viewer)
            }
            modelViewer = viewer
            PerformanceMarks.memory("scene.viewer.after", ProcessMemoryReader.read())
            val nativeListener = checkNotNull(surface.surfaceTextureListener)
            surface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) =
                    nativeListener.onSurfaceTextureAvailable(texture, width, height)
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) =
                    nativeListener.onSurfaceTextureSizeChanged(texture, width, height)
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean =
                    nativeListener.onSurfaceTextureDestroyed(texture)
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    nativeListener.onSurfaceTextureUpdated(texture)
                    textureUpdateCount++; lastTextureTimestamp = texture.timestamp
                    if (modelViewer === viewer && renderSurface === surface && resumed &&
                        presentation.updated(presentationSurface, texture.timestamp)) {
                        message.visibility = GONE; presentationWaitStarted = 0L
                        PerformanceMarks.event("scene.presented")
                        Log.i(TAG, "Vehicle preview presented: requested=$vehicleProfileId, displayRightHandDrive=$rightHandDrive, " +
                            "bufferNanos=${texture.timestamp}, requiredNanos=${presentation.requiredTimestamp}")
                        update(state)
                    }
                }
            }
            projectionReady = false
            firstRenderMeasured = false
            projectionWidth = 0
            projectionHeight = 0
            viewer.autoPlayAnimations = false
            viewer.framePacer.configure(24f, 55_000_000L)
            viewer.camera.setExposure(16f, 1f / 125f, 100f)
            PerformanceMarks.measure("scene.quality") { configureQuality(viewer) }
            PerformanceMarks.measure("scene.studio") { configureStudio(viewer) }
            surface.setOnTouchListener { _, event ->
                touchDown = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
                parent?.requestDisallowInterceptTouchEvent(touchDown)
                resetGesture.onTouchEvent(event)
                val viewport = viewer.view.viewport
                if (surface.width > 0 && surface.height > 0 && viewport.width > 0 && viewport.height > 0) {
                    val scaled = MotionEvent.obtain(event)
                    try {
                        scaled.transform(android.graphics.Matrix().apply {
                            setScale(viewport.width.toFloat() / surface.width, viewport.height.toFloat() / surface.height)
                        })
                        viewer.onTouchEvent(scaled)
                    } finally { scaled.recycle() }
                } else viewer.onTouchEvent(event)
                requestFrames(18)
                true
            }
            addView(surface, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            PerformanceMarks.measure("scene.select") { loadSelectedScene(viewer) }
        } catch (error: LinkageError) {
            Log.e(TAG, "Filament native library unavailable", error)
            showFailure("이 기기에서 3D 표시를 시작할 수 없습니다")
        } catch (error: Exception) {
            Log.e(TAG, "Filament viewer creation failed", error)
            showFailure("실내 모델 표시를 준비할 수 없습니다")
        }
    }

    /** Cap both Android native buffers and Filament's viewport, on every target device. */
    private fun resizeRenderBuffer(w: Int, h: Int) {
        val width = w.coerceAtLeast(1)
        val height = h.coerceAtLeast(1)
        // The smaller-buffer trial did not resolve CPU peaks or repeated-load
        // admission failures. Preserve the established full-car presentation quality.
        val scale = min(1f, min(1152f / width, 648f / height))
        // Keep native attachments and post-processing targets on the same small
        // pixel grid. Height-limited windows can otherwise produce odd widths
        // (for example 1101x648), unlike the established 1152x456 main target.
        // Round down only; never exceed the universal cap or enlarge tiny views.
        fun aligned(value: Int) = if (value >= 8) value / 8 * 8 else value.coerceAtLeast(1)
        val bufferWidth = aligned((width * scale).toInt())
        val bufferHeight = aligned((height * scale).toInt())
        surfaceUiHelper?.setDesiredSize(bufferWidth, bufferHeight)
        Log.i(TAG, "Vehicle render buffer: view=${width}x$height, buffer=${bufferWidth}x$bufferHeight")
    }

    private fun loadSelectedScene(viewer: BudgetModelViewer) {
        val full = hasFullVehiclePreview(vehicleProfileId)
        if (referenceFullVehicle != full || (viewer.asset == null && loadJob?.isActive != true))
            loadReferenceCabin(viewer, full)
        else if (viewer.asset != null) loadVehicleShell(viewer)
    }

    /** Whole cars retain only the small authored effects asset, never another vehicle interior. */
    private fun loadReferenceCabin(viewer: BudgetModelViewer, fullVehicle: Boolean) {
        coverPreview()
        val scope = loadScope ?: return
        loadJob?.cancel()
        if (!releaseVehicleShell(viewer)) { recycleDrainingViewer(); return }
        if (viewer.asset != null) {
            viewer.destroyModel()
            if (!DeferredRendererRelease.drainWithin(viewer.engine, 100_000_000L)) {
                recycleDrainingViewer(); return
            }
        }
        referenceFullVehicle = fullVehicle
        val thisReferenceGeneration = ++referenceGeneration
        val thisViewerGeneration = generation
        loading = true
        appliedState = null
        effectEntities = emptyList()
        message.text = "차량 모델 준비 중"
        message.visibility = VISIBLE
        val assetName = if (fullVehicle) "cabin-effects" else "cabin-budget"
        loadJob = scope.launch {
            try {
                val model = withContext(Dispatchers.IO) { modelIoMutex.withLock {
                    coroutineContext.ensureActive()
                    val document = context.assets.open("$assetName.json").bufferedReader().use { JSONObject(it.readText()) }
                    val profile = readMemoryProfile(document)
                    requireMemoryAdmission(profile, profile.glbBytes)
                    val bytes = context.assets.open("$assetName.glb").use { input ->
                        readExactModel(input, profile.glbBytes)
                    }
                    coroutineContext.ensureActive()
                    profile to ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                        put(bytes)
                        rewind()
                    }
                } }
                if (thisViewerGeneration != generation || thisReferenceGeneration != referenceGeneration ||
                    modelViewer !== viewer || !resumed || !isAttachedToWindow) return@launch
                requireMemoryAdmission(model.first, model.first.glbBytes)
                PerformanceMarks.measure("scene.base.import") { viewer.loadModelGlbSynchronous(model.second) }
                val asset = checkNotNull(viewer.asset) { "Reference GLB asset creation returned null" }
                loading = false // No async loader needs render() calls to complete this reference.
                val tm = viewer.engine.transformManager
                tm.setTransform(tm.getInstance(asset.root), sceneTransform)
                effectEntities = asset.entities.asIterable().mapNotNull { entity ->
                    val name = asset.getName(entity)?.lowercase() ?: return@mapNotNull null
                    if (name.startsWith("afterglow_")) entity to name else null
                }
                // Outlet paths explain a confirmed setting; they are not physical ducts.
                // Keep the real seats/dashboard opaque while the thin airflow illustration
                // stays legible in a footwell hidden by the default overhead camera.
                val renderables=viewer.engine.renderableManager
                for((entity,name) in effectEntities) if(name.startsWith("afterglow_airflow")) {
                    val instance=renderables.getInstance(entity)
                    if(instance==0) continue
                    renderables.setPriority(instance,6)
                    for(primitive in 0 until renderables.getPrimitiveCount(instance)) {
                        val material=renderables.getMaterialInstanceAt(instance,primitive)
                        if(material.name.startsWith("Afterglow ")) {
                            material.setDepthCulling(false)
                            material.setDepthWrite(false)
                        }
                    }
                }
                setReferenceCabinVisible(viewer, !fullVehicle)
                applyVehicleEffects(viewer)
                Log.i(TAG, "Budget reference loaded: $assetName, ${asset.renderableEntities.size} renderables")
                loadVehicleShell(viewer)
                requestFrames(45)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (thisViewerGeneration == generation && thisReferenceGeneration == referenceGeneration) {
                    if (retryBudgetAdmission(error)) return@launch
                    loading = false
                    shellLoadFailed = true
                    Log.e(TAG, "Budget reference load rejected", error)
                    showFailure(if (error is BudgetAdmissionException) error.userMessage
                        else "차량 모델을 불러올 수 없습니다")
                    requestFrames(3)
                }
            }
        }
        requestFrames(24)
    }

    private fun readMemoryProfile(document: JSONObject): VehicleMemoryProfile {
        try {
            val value = document.getJSONObject("memoryBudget")
            return VehicleMemoryProfile(value.getInt("schemaVersion"), value.getBoolean("validated"),
                value.getLong("glbBytes"), value.getLong("jsonBytes"), value.getLong("decodedTextureBytes"),
                value.getLong("mipmappedTextureBytes"), value.getLong("geometryBytes"))
        } catch (error: org.json.JSONException) {
            throw BudgetAdmissionException("선택한 차량의 3D 모델을 확인할 수 없습니다",
                "Missing or invalid vehicle memory profile: ${error.message}")
        }
    }

    private fun requireMemoryAdmission(profile: VehicleMemoryProfile, fileBytes: Long, residentInputBytes: Long = 0L,
                                       inputBufferCopies: Int = 2, stagingOnly: Boolean = false) {
        MemoryBudgetPolicy.rejectionReason(profile, fileBytes)?.let { reason ->
            throw BudgetAdmissionException("선택한 차량의 3D 모델을 확인할 수 없습니다", reason)
        }
        val memory = ProcessMemoryReader.read() ?: throw BudgetAdmissionException(
            "차량 3D 표시를 위한 메모리를 확인할 수 없습니다", "Process memory could not be measured")
        PerformanceMarks.memory(if (stagingOnly) "scene.input.admission" else "scene.import.admission", memory,
            if (stagingOnly) MemoryBudgetPolicy.estimatedStagingBytes(profile, inputBufferCopies)
            else MemoryBudgetPolicy.estimatedImportBytes(profile, inputBufferCopies) - residentInputBytes)
        val allowed = if (stagingOnly) MemoryBudgetPolicy.canStageInput(memory, profile, inputBufferCopies)
            else MemoryBudgetPolicy.canLoad(memory, profile, residentInputBytes, inputBufferCopies)
        if (!allowed) {
            throw BudgetAdmissionException("차량 3D 표시를 위한 메모리가 부족합니다",
                "Vehicle allocation rejected at RSS=${memory.rssBytes}, PSS=${memory.pssBytes}", retryable = true)
        }
    }

    /** IO preflight has no staging input to credit. Earlier Activity/decoder garbage may
     * still be resident, so share the same one-shot pressure recovery as native entry.
     */
    private fun requireModelReadAdmission(profile: VehicleMemoryProfile, fileBytes: Long, inputBufferCopies: Int,
                                          recovery: java.util.concurrent.atomic.AtomicBoolean) {
        try { requireMemoryAdmission(profile, fileBytes, inputBufferCopies = inputBufferCopies, stagingOnly = true) }
        catch (pressure: BudgetAdmissionException) {
            if (!pressure.retryable || !recovery.compareAndSet(false, true)) throw pressure
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            PerformanceMarks.measure("scene.read.pressure.reclaim") {
                Runtime.getRuntime().gc()
                NativeHeapReclaimer.releaseUnused()
            }
            requireMemoryAdmission(profile, fileBytes, inputBufferCopies = inputBufferCopies, stagingOnly = true)
        }
    }

    /** An exact validated allocation avoids readBytes() growth copies or an unbounded stream. */
    private fun readExactModel(input: java.io.InputStream, size: Long): ByteArray {
        require(size in 20L..Int.MAX_VALUE.toLong())
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            check(count > 0) { "Vehicle model ended before its validated size" }
            offset += count
        }
        check(input.read() == -1) { "Vehicle model exceeds its validated size" }
        return bytes
    }

    private fun configureQuality(viewer: BudgetModelViewer) {
        with(viewer.view) {
            renderQuality = renderQuality.apply {
                hdrColorBuffer = FilamentView.QualityLevel.LOW
            }
            antiAliasing = FilamentView.AntiAliasing.FXAA
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
                enabled = false
                sampleCount = 1
            }
            ambientOcclusionOptions = ambientOcclusionOptions.apply {
                enabled = false
            }
            setShadowingEnabled(false)
            // No depth-of-field blur: seats and console must remain readable together.
            bloomOptions = bloomOptions.apply { enabled = false }
            dynamicResolutionOptions = dynamicResolutionOptions.apply { enabled = false }
        }
    }

    private fun loadVehicleShell(viewer: BudgetModelViewer) {
        val scope = loadScope ?: return
        val importRecovery = importGarbageRecovery
        val requestedId = vehicleProfileId
        val requestedRightHandDrive = rightHandDrive
        coverPreview()
        shellLoadJob?.cancel()
        shellLoadJob = null
        shellGeneration++
        // A -> B (still reading) -> A must invalidate B even though A is already displayed.
        if (loadedShellProfileId == requestedId && loadedShellRightHandDrive == requestedRightHandDrive && shellAsset != null) {
            shellLoading = false
            shellLoadFailed = false
            requestFrames(18)
            return
        }
        // A replacement must never overlap two complete vehicle assets or importer caches.
        // Release GPU allocations before reading or decoding the next source model.
        if (!releaseVehicleShell(viewer)) { recycleDrainingViewer(); return }
        val thisShellGeneration = ++shellGeneration
        shellLoading = true
        shellLoadFailed = false
        message.text = "차량 모델 준비 중"
        message.visibility = VISIBLE
        val thisViewerGeneration = generation
        shellLoadJob = scope.launch {
            var candidate: FilamentAsset? = null
            // Ownership lives outside withContext: prompt cancellation can discard its result.
            // IO completes before this coroutine's finally unmaps the staging allocation.
            var mappedInput: MappedModelInput? = null
            try {
                // Asset I/O and manifest parsing happen off the UI thread. Every Filament call
                // below stays on the viewer's main thread, including replacement and disposal.
                val visual = withContext(Dispatchers.IO) { modelIoMutex.withLock {
                    coroutineContext.ensureActive()
                    val previewDirectory = File(context.filesDir, "vehicle-previews")
                    val handedVariant = File(previewDirectory,
                        "$requestedId.${if (requestedRightHandDrive) "rhd" else "lhd"}.glb")
                    val previewModel = handedVariant.takeIf { it.isFile } ?: File(previewDirectory, "$requestedId.glb")
                    val previewSidecar = File(previewDirectory, "$requestedId.json")
                    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    val usePreview = debuggable && (previewModel.exists() || previewSidecar.exists())
                    val id: String
                    val profile: JSONObject
                    val assetPath: String
                    val memoryProfile: VehicleMemoryProfile
                    val sourceLength: Long
                    val openModel: () -> java.io.InputStream
                    if (usePreview) {
                        // Research previews are supplied through run-as to a debuggable install.
                        // No external vehicle resource is packaged, fetched, or enabled in release.
                        check(previewModel.isFile && previewSidecar.isFile) { "Debug vehicle preview requires both GLB and JSON" }
                        id = requestedId
                        require(previewSidecar.length() <= 512_000L) { "Vehicle metadata is too large" }
                        profile = JSONObject(previewSidecar.readText())
                        memoryProfile = readMemoryProfile(profile)
                        assetPath = "debug-private-preview/${previewModel.name}"
                        sourceLength = previewModel.length()
                        openModel = { previewModel.inputStream() }
                    } else {
                        val manifest = context.assets.open("vehicle-shells/manifest.json").bufferedReader().use {
                            JSONObject(it.readText()).getJSONObject("profiles")
                        }
                        id = if (manifest.has(requestedId)) requestedId else VehicleCatalog.GENERIC_ID
                        profile = manifest.getJSONObject(id)
                        memoryProfile = readMemoryProfile(profile)
                        assetPath = profile.getString("asset")
                        require(assetPath.startsWith("vehicle-shells/") && !assetPath.contains(".."))
                        sourceLength = memoryProfile.glbBytes
                        openModel = { context.assets.open(assetPath) }
                    }
                    fun vector(name: String): FloatArray {
                        val array = profile.getJSONArray(name)
                        require(array.length() == 3)
                        return FloatArray(3) { index ->
                            array.getDouble(index).toFloat().also { require(it.isFinite()) }
                        }
                    }
                    val scale = vector("interiorScale")
                    require(scale.all { it in .5f..1.8f })
                    val translation = vector("interiorTranslation")
                    require(translation.all { kotlin.math.abs(it) <= 1f })
                    val shellTransform = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
                    profile.optJSONArray("shellTransform")?.let { array ->
                        require(array.length() == 16) { "shellTransform must be a column-major 4x4 matrix" }
                        for (index in 0 until 16) {
                            shellTransform[index] = array.getDouble(index).toFloat().also { require(it.isFinite()) }
                        }
                    }
                    val compatibility = profile.optJSONObject("compatibility")
                    val palette = profile.optJSONObject("exteriorPalette")
                    val validatedFastPath = memoryProfile.validated && palette != null && compatibility != null &&
                        listOf("noSheen", "noDraco", "noMorphTargets", "attributesValidated")
                            .all { compatibility.optBoolean(it, false) }
                    // This path is the same on every device. Only assets that need no repair
                    // can avoid the full managed input copy; compatibility loading keeps it.
                    val inputCopies = if (validatedFastPath) 1 else 2
                    coroutineContext.ensureActive()
                    requireModelReadAdmission(memoryProfile, sourceLength, inputCopies, importRecovery)
                    val compatibleBytes = if (validatedFastPath) null else openModel().use {
                        compatibleVehicleGlb(readExactModel(it, memoryProfile.glbBytes))
                    }
                    val buffer = if (validatedFastPath) openModel().use {
                        val owned = MappedModelInput.allocate(memoryProfile.glbBytes)
                        mappedInput = owned
                        DirectModelInput.fill(it, memoryProfile.glbBytes, owned.buffer)
                    } else ByteBuffer.allocateDirect(requireNotNull(compatibleBytes).size).order(ByteOrder.nativeOrder()).apply {
                        put(compatibleBytes); rewind()
                    }
                    coroutineContext.ensureActive()
                    val exteriorColors = if (validatedFastPath) readExteriorPalette(requireNotNull(palette))
                        else readExteriorColors(requireNotNull(compatibleBytes))
                    val thermalGeometry = PerformanceMarks.measure("scene.car.thermal") {
                        PrecomputedThermalProjection.read(profile, buffer) ?: ThermalGeometry.fromGlb(buffer)
                    }
                    val fullVehicle = usePreview && profile.optBoolean("fullVehicle", false)
                    val variantHandedness = if (usePreview && previewModel == handedVariant) requestedRightHandDrive else null
                    val sourceHandedness = profile.optString("sourceHandedness")
                    val sourceRightHandDrive = variantHandedness ?: when {
                        sourceHandedness.equals("right", true) || sourceHandedness.equals("RHD", true) -> true
                        sourceHandedness.equals("left", true) || sourceHandedness.equals("LHD", true) -> false
                        else -> fullVehicle && profile.optBoolean("mirrorEffectsX", false)
                    }
                    val interiorMirrorRoot = if (fullVehicle) profile.optString("interiorMirrorRoot",
                        "afterglow_interior_handedness").takeIf { it.isNotBlank() } else null
                    VehicleVisual(id, assetPath, scale, translation, shellTransform, fullVehicle,
                        sourceRightHandDrive, interiorMirrorRoot, variantHandedness, exteriorColors, thermalGeometry,
                        memoryProfile, compatibleBytes, inputCopies, buffer)
                } }
                if (thisViewerGeneration != generation || thisShellGeneration != shellGeneration ||
                    vehicleProfileId != requestedId || rightHandDrive != requestedRightHandDrive ||
                    modelViewer !== viewer || !isAttachedToWindow) return@launch
                // The fully written direct input and any compatibility array remain strongly
                // referenced through import. Credit only those exact, actually resident inputs.
                val residentInputBytes = if (visual.memoryProfile.validated &&
                    visual.buffer.isDirect && visual.buffer.position() == 0 &&
                    visual.buffer.limit().toLong() == visual.memoryProfile.glbBytes &&
                    visual.buffer.capacity().toLong() == visual.memoryProfile.glbBytes &&
                    ((visual.inputBufferCopies == 1 && visual.sourceBytes == null) ||
                        (visual.inputBufferCopies == 2 && visual.sourceBytes?.size?.toLong() == visual.memoryProfile.glbBytes))) {
                    visual.memoryProfile.glbBytes * visual.inputBufferCopies
                } else 0L
                try {
                    try {
                        requireMemoryAdmission(visual.memoryProfile, visual.memoryProfile.glbBytes, residentInputBytes,
                            visual.inputBufferCopies)
                    } catch (pressure: BudgetAdmissionException) {
                        if (!pressure.retryable || !importRecovery.compareAndSet(false, true)) throw pressure
                        // Bounded IO parsing has finished. Its temporary JSON/metadata objects
                        // need not survive until a native allocation triggers ART pressure.
                        // Retain this exact input, collect once off-main, then measure again.
                        // No bytes are credited just because collection was requested.
                        withContext(Dispatchers.IO) {
                            PerformanceMarks.measure("scene.import.pressure.reclaim") {
                                Runtime.getRuntime().gc()
                                NativeHeapReclaimer.releaseUnused()
                            }
                        }
                        if (thisViewerGeneration != generation || thisShellGeneration != shellGeneration ||
                            vehicleProfileId != requestedId || rightHandDrive != requestedRightHandDrive ||
                            modelViewer !== viewer || !isAttachedToWindow) return@launch
                        requireMemoryAdmission(visual.memoryProfile, visual.memoryProfile.glbBytes, residentInputBytes,
                            visual.inputBufferCopies)
                    }
                    if (shellAssetLoader == null) {
                        shellAssetLoader = viewer.createOwnedAssetLoader()
                    }
                    val loader = requireNotNull(shellAssetLoader)
                    val newAsset = PerformanceMarks.measure("scene.car.import") {
                        checkNotNull(loader.createAsset(visual.buffer)) { "Vehicle shell GLB returned null" }
                    }
                    candidate = newAsset
                    // The viewer and this importer share one provider for this vehicle lifetime.
                    // Both are destroyed on vehicle replacement, before the next import.
                    // Source data remains valid through
                    // tangent generation and all resource uploads; no old-asset cache is reused.
                    PerformanceMarks.measure("scene.car.resources") { viewer.loadOwnedResources(newAsset) }
                    val tm = viewer.engine.transformManager
                    val shellRootTransform = FloatArray(16)
                    Matrix.multiplyMM(shellRootTransform, 0, sceneTransform, 0, visual.shellTransform, 0)
                    tm.setTransform(tm.getInstance(newAsset.root), shellRootTransform)
                    val interiorRootEntity = visual.interiorMirrorRoot?.let(newAsset::getFirstEntityByName) ?: 0
                    val interiorRootInstance = if (interiorRootEntity != 0) tm.getInstance(interiorRootEntity) else 0
                    check(!visual.fullVehicle || visual.sourceRightHandDrive == requestedRightHandDrive || interiorRootInstance != 0) {
                        "Vehicle preview has no interior-only handedness conversion root"
                    }
                    val interiorBaseTransform = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
                    if (interiorRootInstance != 0) tm.getTransform(interiorRootInstance, interiorBaseTransform)
                    val placement = VehiclePlacement(visual.interiorScale, visual.interiorTranslation,
                        visual.fullVehicle, visual.sourceRightHandDrive,
                        if (interiorRootInstance != 0) interiorRootEntity else 0, interiorBaseTransform,
                        visual.variantHandedness)
                    val renderableManager = viewer.engine.renderableManager
                    newAsset.renderableEntities.forEach { entity ->
                        val instance = renderableManager.getInstance(entity)
                        if (instance != 0) {
                            // The ghost shell must never cast a dark roof shadow over the interior.
                            renderableManager.setCastShadows(instance, false)
                            renderableManager.setReceiveShadows(instance, false)
                            renderableManager.setScreenSpaceContactShadows(instance, false)
                        }
                    }
                    val newExteriorMaterials = newAsset.instance.materialInstances.mapNotNull { material ->
                        visual.exteriorColors[material.name]?.let { ExteriorMaterial(material, it) }
                    }
                    applyExteriorTransparency(newExteriorMaterials)
                    newAsset.releaseSourceData()
                    viewer.scene.addEntities(newAsset.renderableEntities)
                    shellAsset = newAsset
                    // Never retain native MaterialInstances belonging to an asset being destroyed.
                    exteriorMaterials = newExteriorMaterials
                    candidate = null
                    applyVehiclePlacement(viewer, placement, requestedRightHandDrive)
                    thermalMaterials = createThermalMaterials(viewer, newAsset, visual.thermalGeometry)
                    appliedState = null
                    lastSubmittedState = null
                    applyVehicleEffects(viewer)
                    loadedPlacement = placement
                    setReferenceCabinVisible(viewer, !visual.fullVehicle)
                    loadedShellProfileId = requestedId
                    loadedShellRightHandDrive = requestedRightHandDrive
                    shellLoading = false
                    shellLoadFailed = false
                    PerformanceMarks.event("scene.model.ready")
                    Log.i(TAG, "Vehicle visual ready: requested=$requestedId, geometry=${visual.id}, " +
                        "asset=${visual.assetPath}, fullVehicle=${visual.fullVehicle}, " +
                        "sourceRightHandDrive=${visual.sourceRightHandDrive}, displayRightHandDrive=$requestedRightHandDrive, " +
                        "interiorScale=${visual.interiorScale.contentToString()}")
                    // Synchronous resources and stateless FXAA need no 45-frame
                    // warm-up. Discard placeholder/loading requests; the independent
                    // TextureView gate keeps submitting until this exact vehicle is
                    // actually presented, including rejected GPU submissions.
                    frameBudget.clear()
                    requestFrames(1)
                } finally {
                    // Keep both credited inputs alive even when JIT liveness analysis would
                    // otherwise discard them before the measurement or native upload finishes.
                    java.lang.ref.Reference.reachabilityFence(visual.sourceBytes)
                    java.lang.ref.Reference.reachabilityFence(visual.buffer)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (thisViewerGeneration == generation && thisShellGeneration == shellGeneration) {
                    if (retryBudgetAdmission(error)) return@launch
                    Log.e(TAG, "Vehicle shell load failed for $requestedId", error)
                    shellLoading = false
                    shellLoadFailed = true
                    showFailure(if (error is BudgetAdmissionException) error.userMessage
                        else "선택한 차량 구조를 불러올 수 없습니다")
                    requestFrames(3)
                }
            } finally {
                try {
                    if (thisViewerGeneration == generation && thisShellGeneration == shellGeneration && modelViewer === viewer) {
                        candidate?.let { shellAssetLoader?.destroyAsset(it) }
                        if (shellAsset == null) destroyVehicleImporter(viewer)
                    }
                } finally {
                    mappedInput?.let {
                        val bytes = it.buffer.capacity()
                        it.close()
                        PerformanceMarks.event("scene.input.unmapped")
                        Log.i(TAG, "Vehicle staging input unmapped: bytes=$bytes")
                    }
                }
            }
        }
    }

    /**
     * Filament 1.75's archived provider can fall back to a material without sheen uniforms,
     * then unconditionally set sheenColorIndex. Drop only optional KHR_materials_sheen before
     * createAsset; the standard PBR material remains, and every BIN byte stays identical.
     * A model requiring that extension fails safely rather than silently changing its contract.
     */
    private fun compatibleVehicleGlb(bytes: ByteArray): ByteArray {
        require(bytes.size >= 20) { "Vehicle preview is not a GLB" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getInt(0) == 0x46546c67 && header.getInt(4) == 2 && header.getInt(8) == bytes.size) {
            "Expected a complete GLB 2 vehicle preview"
        }
        val jsonSize = header.getInt(12)
        require(header.getInt(16) == 0x4e4f534a && jsonSize > 0 && jsonSize <= bytes.size - 20)
        val jsonText = String(bytes, 20, jsonSize, Charsets.UTF_8)
        val extension = "KHR_materials_sheen"
        // Non-sheen models need no JSON parse here. Escaped Unicode keys still take the full
        // parser path so a valid extension written with \u escapes cannot bypass the guard.
        if (!jsonText.contains(extension) && !jsonText.contains("\\u")) return bytes
        val document = JSONObject(jsonText)
        document.optJSONArray("extensionsRequired")?.let { required ->
            require((0 until required.length()).none { required.optString(it) == extension }) {
                "This vehicle requires a sheen shader that is unavailable in the compatibility renderer"
            }
        }
        val materials = document.optJSONArray("materials") ?: return bytes
        var removed = 0
        for (index in 0 until materials.length()) {
            val material = materials.getJSONObject(index)
            val extensions = material.optJSONObject("extensions") ?: continue
            if (extensions.remove(extension) != null) removed++
            if (extensions.length() == 0) material.remove("extensions")
        }
        if (removed == 0) return bytes
        document.optJSONArray("extensionsUsed")?.let { used ->
            for (index in used.length() - 1 downTo 0) {
                if (used.optString(index) == extension) used.remove(index)
            }
            if (used.length() == 0) document.remove("extensionsUsed")
        }
        val json = document.toString().toByteArray(Charsets.UTF_8)
        val paddedSize = (json.size + 3) and -4
        val tailOffset = 20 + jsonSize
        val totalSize = 20L + paddedSize + bytes.size - tailOffset
        require(totalSize <= Int.MAX_VALUE) { "Vehicle preview is too large" }
        val result = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(0x46546c67).putInt(2).putInt(totalSize.toInt())
        result.putInt(paddedSize).putInt(0x4e4f534a).put(json)
        repeat(paddedSize - json.size) { result.put(0x20.toByte()) }
        result.put(bytes, tailOffset, bytes.size - tailOffset)
        Log.i(TAG, "Vehicle material compatibility: omitted optional sheen from $removed materials; geometry and textures unchanged")
        return result.array()
    }

    /** Read authored linear RGBA on the I/O dispatcher; Filament has no float parameter getter. */
    private fun readExteriorPalette(palette: JSONObject): Map<String, ExteriorColor> = buildMap {
        require(palette.length() <= 2_000) { "Exterior palette is too large" }
        val names = palette.keys()
        while (names.hasNext()) {
            val name = names.next()
            require(isExteriorMaterialName(name)) { "Exterior palette includes a non-exterior material" }
            val factor = palette.getJSONArray(name)
            require(factor.length() == 4)
            val rgba = FloatArray(4) { factor.getDouble(it).toFloat() }
            require(rgba.all { it.isFinite() && it >= 0f }) { "Invalid exterior palette factor" }
            put(name, ExteriorColor(rgba[0], rgba[1], rgba[2], rgba[3]))
        }
    }

    private fun isExteriorMaterialName(name: String) = name.endsWith("__afterglow_exterior") ||
        name.endsWith("__afterglow_roof") || name.endsWith("__afterglow_glass") ||
        name in setOf("shell_paint_translucent", "shell_roof_translucent", "shell_glass_translucent",
            "shell_contour", "shell_detail")

    private fun readExteriorColors(bytes: ByteArray): Map<String, ExteriorColor> {
        require(bytes.size >= 20) { "Vehicle preview is not a GLB" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getInt(0) == 0x46546c67 && header.getInt(4) == 2) { "Expected GLB 2" }
        val jsonSize = header.getInt(12)
        require(header.getInt(16) == 0x4e4f534a && jsonSize > 0 && jsonSize <= bytes.size - 20)
        val materials = JSONObject(String(bytes, 20, jsonSize, Charsets.UTF_8)).optJSONArray("materials")
            ?: return emptyMap()
        val authored = mutableListOf<Pair<String, FloatArray>>()
        for (index in 0 until materials.length()) {
            val material = materials.getJSONObject(index)
            val name = material.optString("name")
            // Explicit role names keep shared interior and opaque recognition materials out.
            if (!isExteriorMaterialName(name) || material.optString("alphaMode") != "BLEND") continue
            val factor = material.optJSONObject("pbrMetallicRoughness")?.optJSONArray("baseColorFactor")
            val rgba = FloatArray(4) { channel -> factor?.optDouble(channel, 1.0)?.toFloat() ?: 1f }
            require(rgba.all { it.isFinite() && it >= 0f }) { "Invalid exterior baseColorFactor" }
            authored += name to rgba
        }
        val bodyAlpha = authored.firstOrNull { (name, _) ->
            name.endsWith("__afterglow_exterior") || name == "shell_paint_translucent"
        }?.second?.get(3)?.takeIf { it > 0f } ?: .45f
        return authored.associate { (name, rgba) ->
            val body = name.endsWith("__afterglow_exterior") || name == "shell_paint_translucent"
            name to ExteriorColor(rgba[0], rgba[1], rgba[2], if (body) 1f else rgba[3] / bodyAlpha)
        }
    }

    private fun applyExteriorTransparency(materials: List<ExteriorMaterial>) {
        val bodyAlpha = 1f - exteriorTransparency / 100f
        materials.forEach { (instance, color) ->
            // glTF baseColorFactor is already linear: the float4 overload preserves its RGB.
            instance.setParameter("baseColorFactor", color.red, color.green, color.blue,
                (bodyAlpha * color.alphaScale).coerceIn(0f, 1f))
        }
    }

    /** Duplicate only driver-row upholstery primitives. Shared passenger materials stay untouched. */
    private fun createThermalMaterials(viewer: BudgetModelViewer, asset: FilamentAsset, geometry: Map<String, List<ThermalPrimitive>>): List<ThermalMaterial> {
        data class Candidate(val name: String, val renderable: Int, val primitive: Int, val material: MaterialInstance,
            val bounds: ThermalBounds, val base: FloatArray, val wheel: Boolean) {
            val point = bounds.center
        }
        val rm = viewer.engine.renderableManager; val tm = viewer.engine.transformManager
        val candidates = mutableListOf<Candidate>()
        for (entity in asset.renderableEntities) {
            val r = rm.getInstance(entity); if (r == 0) continue
            val name = asset.getName(entity) ?: continue
            val metadata = geometry[name] ?: continue
            if (asset.getEntitiesByName(name).size != 1 || metadata.size != rm.getPrimitiveCount(r)) continue
            val world = FloatArray(16); tm.getWorldTransform(tm.getInstance(entity), world)
            for (primitive in 0 until rm.getPrimitiveCount(r)) {
                val material = rm.getMaterialInstanceAt(r, primitive)
                val source = metadata[primitive]
                if (source.material != material.name) continue
                val base = source.baseColor ?: continue
                val bounds = source.bounds?.world(world) ?: continue
                candidates += Candidate(name, r, primitive, material, bounds, base, source.wheel)
            }
        }
        val wheels = candidates.filter { it.wheel }
        val driverX = wheels.firstOrNull()?.point?.get(0) ?: 0f
        val seats = candidates.filter { !it.wheel && it.bounds.onSide(driverX) }
        val front = if (wheels.isNotEmpty()) seats.minByOrNull { seat ->
            wheels.minOf { wheel -> (seat.point[0] - wheel.point[0]) * (seat.point[0] - wheel.point[0]) +
                (seat.point[2] - wheel.point[2]) * (seat.point[2] - wheel.point[2]) }
        } else null
        // Unknown semantics are left uncolored; never paint an arbitrary rear seat as the driver.
        val selected = candidates.filter { it.wheel || (front != null && !it.wheel && it.bounds.onSide(driverX) &&
            kotlin.math.abs(it.point[0] - front.point[0]) < .24f && kotlin.math.abs(it.point[2] - front.point[2]) < .36f) }
            .take(96)
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            selected.forEach { item -> Log.i(TAG, "Thermal selection: node=${item.name} slot=${item.primitive} " +
                "wheel=${item.wheel} min=${item.bounds.minimum.contentToString()} max=${item.bounds.maximum.contentToString()}") }
        }
        val created = mutableListOf<ThermalMaterial>()
        try { selected.forEach { item ->
                val copy = MaterialInstance.duplicate(item.material, "afterglow_driver_thermal")
                created += ThermalMaterial(copy, item.base, item.wheel)
                rm.setMaterialInstanceAt(item.renderable, item.primitive, copy)
            }
        } catch (error: Exception) {
            selected.forEach { rm.setMaterialInstanceAt(it.renderable, it.primitive, it.material) }
            created.forEach { viewer.engine.destroyMaterialInstance(it.instance) }
            throw error
        }
        Log.i(TAG, "Thermal primitives: driverSeat=${created.count { !it.wheel }}, steering=${created.count { it.wheel }}")
        return created
    }

    private fun applyVehiclePlacement(viewer: BudgetModelViewer, placement: VehiclePlacement, displayRightHandDrive: Boolean) {
        val tm = viewer.engine.transformManager
        // The reference cabin and its effects were authored as LHD. Their driver side follows
        // the requested display setting, irrespective of the real preview's source market.
        viewer.asset?.let { cabin ->
            val transform = sceneTransform.copyOf()
            Matrix.translateM(transform, 0, placement.interiorTranslation[0],
                placement.interiorTranslation[1], placement.interiorTranslation[2])
            val scaleX = placement.interiorScale[0] * if (displayRightHandDrive) -1f else 1f
            Matrix.scaleM(transform, 0, scaleX, placement.interiorScale[1], placement.interiorScale[2])
            tm.setTransform(tm.getInstance(cabin.root), transform)
        }
        if (placement.interiorRootEntity != 0) {
            val instance = tm.getInstance(placement.interiorRootEntity)
            if (instance != 0) {
                val transform = if (placement.sourceRightHandDrive != displayRightHandDrive) {
                    val reflection = FloatArray(16).apply {
                        Matrix.setIdentityM(this, 0)
                        Matrix.scaleM(this, 0, -1f, 1f, 1f)
                    }
                    FloatArray(16).apply {
                        Matrix.multiplyMM(this, 0, reflection, 0, placement.interiorBaseTransform, 0)
                    }
                } else placement.interiorBaseTransform
                // This explicitly authored root contains the entire cabin only. Exterior body,
                // roof, wheels and logos remain outside it. Filament corrects mirrored winding
                // and normals per renderable, without changing the global view's front faces.
                tm.setTransform(instance, transform)
            }
        }
    }

    private fun setReferenceCabinVisible(viewer: BudgetModelViewer, visible: Boolean) {
        val cabin = viewer.asset ?: return
        val renderableManager = viewer.engine.renderableManager
        cabin.renderableEntities.forEach { entity ->
            val name = cabin.getName(entity).orEmpty()
            // Only these explicitly authored setting illustrations survive a whole-vehicle
            // preview. Seats, dashboard, door frames, steering wheel and glows all come from it.
            if (name.startsWith("afterglow_")) return@forEach
            val instance = renderableManager.getInstance(entity)
            if (instance != 0) {
                val show = visible && name != "modern-car-interior_windshield_glass"
                renderableManager.setLayerMask(instance, 0xff, if (show) 0x01 else 0x00)
            }
        }
    }

    /** Called by our earlier surface listener while BudgetModelViewer's native engine is still alive. */
    private fun recycleDrainingViewer() {
        disposeViewer()
        if (resumed && isAttachedToWindow) createViewer()
    }

    private fun releaseVehicleShell(viewer: BudgetModelViewer): Boolean {
        shellGeneration++
        shellLoadJob?.cancel()
        shellLoadJob = null
        shellLoading = false
        shellLoadFailed = false
        return destroyVehicleImporter(viewer)
    }

    private fun destroyVehicleImporter(viewer: BudgetModelViewer): Boolean {
        exteriorMaterials = emptyList()
        // Admission can reject a source before any importer exists. The old unconditional
        // flush waited for unrelated GPU work forever on that empty cleanup path (ANR).
        // Actual owned assets/loaders still require the full release + drain below.
        if (shellAsset == null && shellAssetLoader == null &&
            thermalMaterials.isEmpty()) {
            PerformanceMarks.event("scene.importer.release.empty")
            return true
        }
        // Each importer handles a single vehicle lifetime. Drain decoding and pending uploads
        // before deleting the asset, then release all caches and material archive instances.
        viewer.cancelOwnedResources()
        shellAsset?.let {
            viewer.scene.removeEntities(it.entities)
            shellAssetLoader?.destroyAsset(it)
        }
        thermalMaterials.forEach { viewer.engine.destroyMaterialInstance(it.instance) }
        thermalMaterials = emptyList()
        shellAsset = null
        loadedShellProfileId = null
        loadedShellRightHandDrive = null
        loadedPlacement = null
        shellAssetLoader?.destroy()
        shellAssetLoader = null
        return DeferredRendererRelease.drainWithin(viewer.engine, 100_000_000L)
    }

    private fun configureStudio(viewer: BudgetModelViewer) {
        val engine = viewer.engine
        val lm = engine.lightManager
        val key = lm.getInstance(viewer.light)
        lm.setDirection(key, -.45f, -1f, -.45f)
        lm.setIntensity(key, 70_000f)
        lm.setColor(key, 1f, .95f, .86f)
        lm.setShadowCaster(key, false)
        ownedSkybox = Skybox.Builder().color(.009f, .0095f, .011f, 1f).build(engine)
        viewer.scene.skybox = ownedSkybox
        // Spherical-harmonic fill needs no environment texture allocation.
        ownedIndirectLight = IndirectLight.Builder().irradiance(1, floatArrayOf(.44f, .48f, .53f))
            .intensity(25_000f).build(engine)
        viewer.scene.indirectLight = ownedIndirectLight
        // Directional key + spherical-harmonic ambient light illuminate the
        // complete genuine cabin without decorative punctual lights. This avoids
        // their per-frame clustered-light work; thermal material feedback and
        // exterior transparency remain part of the actual vehicle model.
        extraLights = emptyList()
    }

    private fun releaseStudio(viewer: BudgetModelViewer) {
        viewer.scene.indirectLight = null
        viewer.scene.skybox = null
        extraLights.forEach { entity ->
            viewer.scene.removeEntity(entity)
            viewer.engine.destroyEntity(entity)
            EntityManager.get().destroy(entity)
        }
        extraLights = emptyList()
        ownedIndirectLight?.let { viewer.engine.destroyIndirectLight(it) }
        ownedSkybox?.let { viewer.engine.destroySkybox(it) }
        ownedIndirectLight = null
        ownedSkybox = null
    }

    /** Only explicit authored effect nodes are controlled; original cabin materials stay intact. */
    private fun applyVehicleEffects(viewer: BudgetModelViewer) {
        if ((appliedState == state && lastSubmittedState == state) || viewer.asset == null) return
        // A skipped GPU submission has not acknowledged this setting. Reapply the
        // latest uniforms on the next attempt instead of treating a CPU write as
        // a submitted visual state. TextureView presentation remains independent.
        val effects = CabinEffectState.from(state)
        // Compare with the last accepted submission, never merely the latest CPU
        // write. GPU-skipped attempts still retry changed uniforms/layers. A new
        // asset has no appliedState and must initialize every authored effect.
        val previous = if (appliedState == null) null else lastSubmittedState?.let(CabinEffectState::from)
        val rm = viewer.engine.renderableManager
        thermalMaterials.forEach { material ->
            val levels = if (material.wheel) effects.wheel else effects.seat
            val prior = if (material.wheel) previous?.wheel else previous?.seat
            if (prior == levels) return@forEach
            val rgba = CabinThermalStyle.tint(material.base, levels.heat, levels.ventilation, levels.maximum)
            material.instance.setParameter("baseColorFactor", rgba[0], rgba[1], rgba[2], rgba[3])
            thermalUniformWriteCount++
        }
        for ((entity, name) in effectEntities) {
            val visible = effects.visible(name)
            if (previous?.visible(name) == visible) continue
            val instance = rm.getInstance(entity)
            if (instance == 0) continue
            rm.setLayerMask(instance, 0xff, if (visible) 0x01 else 0x00)
            effectLayerWriteCount++
        }
        appliedState = state
    }

    private fun airflowOn(value: CabinSceneState) = value.connected && value.powerOn && value.fan > 0

    private var detailsVisible = false
    private var deferredPanelFrames = 0
    fun setDetailsVisible(visible: Boolean) {
        if (detailsVisible == visible) return
        detailsVisible = visible
        if (visible) {
            // A popup can be opened before the first vehicle frame. Submitting
            // the loading scene behind it competes with the popup's own window.
            // No active-presentation deadline should include this deliberate pause.
            presentationWaitStarted = 0L
            deferredPanelFrames = maxOf(deferredPanelFrames, frameBudget.remaining)
            stopFrames()
        }
        // Apply the newest state once after a panel. The actual presentation gate
        // still owns loading/replacement completion; repeated static frames only
        // compete with the restored Android UI and the next touch.
        else {
            val pending = deferredPanelFrames
            deferredPanelFrames = 0
            // An unchanged, already presented texture survives the inline panel.
            // Do not submit a redundant native/GPU frame alongside the home UI.
            if (pending > 0) requestFrames(pending)
            else if (loading || shellLoading || presentation.covered || state != appliedState) requestFrames(1)
        }
    }
    private fun requestFrames(count: Int) {
        // Keep the latest telemetry and allow cancellable source preparation to
        // finish, but submit no 3D frames behind a modal, including startup.
        // Dismissal requests fresh frames; it never substitutes import completion
        // for the actual TextureView presentation gate.
        if (detailsVisible) {
            deferredPanelFrames = maxOf(deferredPanelFrames, count)
            frameBudget.clear()
            return
        }
        if (!scheduled && frameBudget.remaining == 0 && !loading && !shellLoading) {
            // The previous burst intentionally ended. Re-anchor the next submission
            // rather than recover a continuous-animation timeline across that idle gap.
            modelViewer?.framePacer?.resetPacing()
        }
        frameBudget.request(count, SystemClock.elapsedRealtime())
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (scheduled || !resumed || !isAttachedToWindow || !isShown || modelViewer == null || windowVisibility != VISIBLE) return
        scheduled = true
        Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, 33L)
    }

    private fun stopFrames() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        scheduled = false
        frameBudget.clear()
        touchDown = false
    }

    private fun showFailure(text: String) {
        presentation.fail(); presentationWaitStarted = 0L
        message.text = text
        message.visibility = VISIBLE
        message.bringToFront()
    }

    companion object { private const val TAG = "PremiumCabinView" }
}
