package dev.lumen.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.channels.Channels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Lens(val id: String, val chars: CameraCharacteristics, val label: String)

class Res(val size: Size, val maxRes: Boolean) {
    val pixels: Long get() = size.width.toLong() * size.height
    val label: String get() = "${(pixels / 1_000_000.0).roundToInt()}MP" + (if (maxRes) " MAX" else "")
}

private fun IntArray?.has(v: Int): Boolean = this?.contains(v) == true

/** Everything one lens can do, read once from the camera characteristics. */
class Caps(val lens: Lens) {
    private val c = lens.chars
    private val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    val sensorOrientation: Int = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val active: Rect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
    val evStep: Float = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
    val evRange: Range<Int>? = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val isoRange: Range<Int>? = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val expRange: Range<Long>? = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val minFocus: Float = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val apertures: FloatArray = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0)
    val awbModes: IntArray = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
    val afModes: IntArray = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    private val capabilities: IntArray = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    val hasRaw: Boolean = capabilities.has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    val hasManual: Boolean = capabilities.has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
        isoRange != null && expRange != null
    val toneModes: IntArray = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
    val effects: IntArray = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS) ?: IntArray(0)
    val flashUnit: Boolean = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val zoomRange: Range<Float>? =
        if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
    val maxAfRegions: Int = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val maxAeRegions: Int = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    val edgeModes: IntArray = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
    val nrModes: IntArray =
        c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
    val aberModes: IntArray =
        c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: IntArray(0)
    val distModes: IntArray = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: IntArray(0)
    val hotModes: IntArray = c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: IntArray(0)
    val shadeModes: IntArray = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: IntArray(0)
    val oisModes: IntArray = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)

    val jpegRes: List<Res> = buildRes(ImageFormat.JPEG)
    val rawRes: List<Res> = buildRes(ImageFormat.RAW_SENSOR)

    /** Sizes shown to the user: the biggest full-resolution (MAX) mode plus the 3 biggest normal ones. */
    val resChoices: List<Res> =
        (jpegRes.filter { it.maxRes }.take(1) + jpegRes.filter { !it.maxRes }.take(3)).sortedByDescending { it.pixels }
    val defaultRes: Int = resChoices.indexOfFirst { !it.maxRes }.coerceAtLeast(0)

    /** Preview stays modest (<= 1920 wide, 4:3) so it never lags, whatever the photo resolution. */
    val preview: Size = run {
        val sizes = map.getOutputSizes(SurfaceHolder::class.java)?.toList().orEmpty()
        sizes.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f && it.width <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1280, 960)
    }

    private fun buildRes(fmt: Int): List<Res> {
        val all = ArrayList<Res>()
        map.getOutputSizes(fmt)?.forEach { all.add(Res(it, false)) }
        map.getHighResolutionOutputSizes(fmt)?.forEach { all.add(Res(it, false)) }
        if (Build.VERSION.SDK_INT >= 31) {
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                ?.getOutputSizes(fmt)?.forEach { all.add(Res(it, true)) }
        }
        val wide = all.filter { abs(it.size.width.toFloat() / it.size.height - 4f / 3f) < 0.02f }
        return (if (wide.isEmpty()) all else wide)
            .distinctBy { "${it.size.width}x${it.size.height}" }
            .sortedByDescending { it.pixels }
    }
}

class CameraEngine(private val ctx: Context) {

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("lumen-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { r -> handler.post(r) }
    private val camScope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sound = MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }

    val lenses: List<Lens> = findLenses()
    private val capsList: List<Caps> = lenses.map { Caps(it) }

    // ---- state the UI reads / changes (change a value, then call updatePreview()) ----
    var caps by mutableStateOf<Caps?>(null)
    var lensIndex by mutableIntStateOf(0)
    var flash by mutableStateOf(FlashMode.OFF)
    var timer by mutableIntStateOf(0)
    var look by mutableStateOf(Look.NATURAL)
    var rawMode by mutableIntStateOf(0)            // 0 JPG, 1 RAW, 2 RAW+JPG
    var resIndex by mutableIntStateOf(0)
    var proMode by mutableStateOf(false)
    var manualExp by mutableStateOf(false)
    var iso by mutableIntStateOf(100)
    var shutterNs by mutableLongStateOf(8_000_000L)
    var ev by mutableIntStateOf(0)
    var manualFocus by mutableStateOf(false)
    var focusDist by mutableFloatStateOf(0f)
    var afLock by mutableStateOf(false)
    var aeLock by mutableStateOf(false)
    var wb by mutableIntStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    var aperture by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var grid by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var countdown by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var reticle by mutableStateOf<Offset?>(null)
    var focusState by mutableIntStateOf(0)         // 0 idle, 1 searching, 2 locked, 3 failed
    var liveIso by mutableIntStateOf(0)
    var liveNs by mutableLongStateOf(0L)
    var lastUri by mutableStateOf<Uri?>(null)
    @Volatile var deviceOrientation = 0

    // ---- camera-thread state ----
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var activeJpeg: Res? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    private var holder: SurfaceHolder? = null
    private var opening = false
    private var starting = false
    @Volatile private var afHold = false
    private var afRegions: Array<MeteringRectangle>? = null
    private var aeRegions: Array<MeteringRectangle>? = null
    @Volatile private var lastResult: CaptureResult? = null
    @Volatile private var lastAeState: Int? = null
    private var lastInfoMs = 0L
    private var timerJob: Job? = null
    private val pending = AtomicInteger(0)
    private var stamp = ""
    private var rawImage: Image? = null
    private var rawResult: TotalCaptureResult? = null
    private val curves = HashMap<Look, android.hardware.camera2.params.TonemapCurve?>()

    init {
        if (capsList.isNotEmpty()) {
            val start = lenses.indexOfFirst { it.label == "1x" }.coerceAtLeast(0)
            lensIndex = start
            caps = capsList[start]
            resetForLens()
        }
    }

    // ---------------------------------------------------------------- lenses

    private fun findLenses(): List<Lens> {
        return try {
            val found = ArrayList<Triple<String, CameraCharacteristics, Float>>()
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                if (!capabilities.has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                if (c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) == null) continue
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val eq = if (sz != null && f > 0f) f * 43.27f / hypot(sz.width, sz.height) else 26f
                found.add(Triple(id, c, eq))
            }
            if (found.isEmpty()) {
                emptyList<Lens>()
            } else {
                val mainEq = found.minByOrNull { abs(it.third - 26f) }!!.third
                found.sortedBy { it.third }.map { (id, c, eq) ->
                    val ratio = eq / mainEq
                    val label = if (abs(ratio - 1f) < 0.12f) {
                        "1x"
                    } else {
                        String.format(Locale.US, "%.1fx", ratio).replace(".0x", "x")
                    }
                    Lens(id, c, label)
                }
            }
        } catch (e: Throwable) {
            emptyList<Lens>()
        }
    }

    private fun resetForLens() {
        val cp = caps ?: return
        resIndex = cp.defaultRes
        zoom = 1f
        afRegions = null
        aeRegions = null
        afHold = false
        afLock = false
        aeLock = false
        manualFocus = false
        manualExp = false
        ev = 0
        wb = CameraMetadata.CONTROL_AWB_MODE_AUTO
        aperture = cp.apertures.firstOrNull() ?: 0f
        if (!cp.hasRaw) rawMode = 0
        if (flash != FlashMode.OFF && !cp.flashUnit) flash = FlashMode.OFF
        if (look !in availableLooks()) look = Look.NATURAL
        reticle = null
    }

    fun selectLens(i: Int) {
        if (i == lensIndex || i !in capsList.indices) return
        lensIndex = i
        caps = capsList[i]
        resetForLens()
        val p = capsList[i].preview
        main.post { holder?.setFixedSize(p.width, p.height) }
        handler.post {
            closeSession()
            closeDevice()
            openCamera()
        }
    }

    // -------------------------------------------------------------- lifecycle

    fun resume() {
        handler.post { openCamera() }
    }

    fun pause() {
        timerJob?.cancel()
        countdown = 0
        handler.post {
            closeSession()
            closeDevice()
        }
    }

    fun release() {
        pause()
        handler.post { thread.quitSafely() }
        uiScope.cancel()
        sound.release()
    }

    private fun closeDevice() {
        try {
            device?.close()
        } catch (e: Exception) {
            // ignore
        }
        device = null
        opening = false
    }

    private fun closeSession() {
        try {
            session?.close()
        } catch (e: Exception) {
            // ignore
        }
        session = null
        starting = false
        closeReaders()
    }

    private fun closeReaders() {
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
    }

    private fun openCamera() {
        if (device != null || opening) return
        val cp = caps ?: return
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        opening = true
        try {
            manager.openCamera(cp.lens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    opening = false
                    tryStart()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    opening = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    opening = false
                    toast("Camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            toast("Can't open camera: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- surface

    fun onSurfaceCreated(h: SurfaceHolder) {
        holder = h
        val p = caps?.preview
        if (p != null) h.setFixedSize(p.width, p.height)
    }

    fun onSurfaceChanged(h: SurfaceHolder, w: Int, hh: Int) {
        handler.post {
            previewSurface = h.surface
            surfaceW = w
            surfaceH = hh
            tryStart()
        }
    }

    fun onSurfaceDestroyed() {
        holder = null
        handler.post {
            previewSurface = null
            closeSession()
        }
    }

    // ---------------------------------------------------------------- session

    private fun tryStart() {
        val d = device ?: return
        val sf = previewSurface ?: return
        val cp = caps ?: return
        if (session != null || starting) return
        if (surfaceW != cp.preview.width || surfaceH != cp.preview.height) return
        val choice = cp.resChoices.getOrNull(resIndex)
        val jres = (if (choice != null && !choice.maxRes) choice else null)
            ?: cp.resChoices.firstOrNull { !it.maxRes }
            ?: cp.jpegRes.firstOrNull()
            ?: return
        val rres = if (rawMode != 0 && cp.hasRaw) cp.rawRes.firstOrNull { !it.maxRes } else null
        starting = true
        try {
            val jr = ImageReader.newInstance(
                jres.size.width, jres.size.height, ImageFormat.JPEG,
                if (jres.pixels > 60_000_000L) 1 else 2,
            )
            jr.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage()
                if (img != null) {
                    saveJpeg(img, jres)
                    img.close()
                }
            }, handler)
            jpegReader = jr
            activeJpeg = jres

            var rr: ImageReader? = null
            if (rres != null) {
                rr = ImageReader.newInstance(rres.size.width, rres.size.height, ImageFormat.RAW_SENSOR, 2)
                rr.setOnImageAvailableListener({ r ->
                    val img = r.acquireNextImage()
                    if (img != null) onRawImage(img)
                }, handler)
            }
            rawReader = rr

            val outs = ArrayList<OutputConfiguration>()
            outs.add(OutputConfiguration(sf))
            outs.add(OutputConfiguration(jr.surface))
            if (rr != null) outs.add(OutputConfiguration(rr.surface))

            d.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outs, executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            starting = false
                            session = s
                            sendPreview(false)
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            starting = false
                            closeReaders()
                            if (rawMode != 0) {
                                rawMode = 0
                                toast("RAW can't run together with this setup. Using JPG.")
                                tryStart()
                            } else {
                                toast("Couldn't start the camera at this resolution")
                            }
                        }
                    },
                ),
            )
        } catch (e: Throwable) {
            starting = false
            closeReaders()
            toast("Camera setup failed: ${e.message}")
        }
    }

    fun restartSession() {
        handler.post {
            closeSession()
            tryStart()
        }
    }

    fun updatePreview() {
        handler.post { sendPreview(false) }
    }

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            lastResult = result
            lastAeState = result.get(CaptureResult.CONTROL_AE_STATE)
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afHold && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
            ) {
                afHold = false
                val ok = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                main.post { focusState = if (ok) 2 else 3 }
                if (!afLock) sendPreview(false)
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastInfoMs > 400) {
                lastInfoMs = now
                val i = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                main.post {
                    liveIso = i
                    liveNs = e
                }
            }
        }
    }

    private fun sendPreview(trigger: Boolean) {
        val d = device ?: return
        val s = session ?: return
        val sf = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(sf)
            applyControls(b, false, null)
            if (trigger) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), previewCb, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            s.setRepeatingRequest(b.build(), previewCb, handler)
        } catch (e: Exception) {
            toast("Preview error: ${e.message}")
        }
    }

    // --------------------------------------------------------------- controls

    private fun mode(b: CaptureRequest.Builder, key: CaptureRequest.Key<Int>, avail: IntArray, high: Boolean, hq: Int, fast: Int) {
        val want = if (high) hq else fast
        if (avail.has(want)) {
            b.set(key, want)
        } else if (avail.has(hq)) {
            b.set(key, hq)
        } else if (avail.has(fast)) {
            b.set(key, fast)
        }
    }

    /** Applies all user settings. [capture] = true uses the highest-quality processing. */
    private fun applyControls(b: CaptureRequest.Builder, capture: Boolean, fixed: CaptureResult?) {
        val cp = caps ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Crisp, clean image processing (lighter in the live preview so it never lags)
        mode(b, CaptureRequest.EDGE_MODE, cp.edgeModes, capture, CameraMetadata.EDGE_MODE_HIGH_QUALITY, CameraMetadata.EDGE_MODE_FAST)
        mode(b, CaptureRequest.NOISE_REDUCTION_MODE, cp.nrModes, capture, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        mode(b, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, cp.aberModes, capture, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST)
        mode(b, CaptureRequest.DISTORTION_CORRECTION_MODE, cp.distModes, capture, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, CameraMetadata.DISTORTION_CORRECTION_MODE_FAST)
        mode(b, CaptureRequest.HOT_PIXEL_MODE, cp.hotModes, capture, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY, CameraMetadata.HOT_PIXEL_MODE_FAST)
        mode(b, CaptureRequest.SHADING_MODE, cp.shadeModes, capture, CameraMetadata.SHADING_MODE_HIGH_QUALITY, CameraMetadata.SHADING_MODE_FAST)
        if (cp.oisModes.has(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

        // Exposure
        val auto = fixed == null && !(manualExp && cp.hasManual)
        if (!auto && cp.isoRange != null && cp.expRange != null) {
            val useLast = fixed != null && !manualExp
            var ns = if (useLast) (fixed?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: shutterNs) else shutterNs
            val isoV = if (useLast) (fixed?.get(CaptureResult.SENSOR_SENSITIVITY) ?: iso) else iso
            if (!capture) ns = min(ns, 66_000_000L) // preview stays smooth: at most 1/15 s
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns.coerceIn(cp.expRange.lower, cp.expRange.upper))
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoV.coerceIn(cp.isoRange.lower, cp.isoRange.upper))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(33_333_333L, ns))
            if (cp.apertures.isNotEmpty() && aperture > 0f) b.set(CaptureRequest.LENS_APERTURE, aperture)
            b.set(
                CaptureRequest.FLASH_MODE,
                when {
                    flash == FlashMode.TORCH && cp.flashUnit -> CameraMetadata.FLASH_MODE_TORCH
                    flash == FlashMode.ON && capture && cp.flashUnit -> CameraMetadata.FLASH_MODE_SINGLE
                    else -> CameraMetadata.FLASH_MODE_OFF
                },
            )
        } else {
            val aeMode = when {
                !cp.flashUnit -> CameraMetadata.CONTROL_AE_MODE_ON
                capture && flash == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                capture && flash == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
            b.set(
                CaptureRequest.FLASH_MODE,
                if (flash == FlashMode.TORCH && cp.flashUnit) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
            )
            aeRegions?.let { b.set(CaptureRequest.CONTROL_AE_REGIONS, it) }
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, wb)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, aeLock)

        // Focus
        if (fixed != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            fixed.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { b.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        } else if (manualFocus && cp.minFocus > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist.coerceIn(0f, cp.minFocus))
        } else {
            val afMode = when {
                afHold || afLock -> CameraMetadata.CONTROL_AF_MODE_AUTO
                cp.afModes.has(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else -> CameraMetadata.CONTROL_AF_MODE_AUTO
            }
            b.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            afRegions?.let { b.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
        }

        // Zoom
        val zr = cp.zoomRange
        if (Build.VERSION.SDK_INT >= 30 && zr != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(zr.lower, zr.upper))
        }

        // Filter / D-Log (tone curve inside the camera pipeline)
        if (look == Look.MONO && cp.effects.has(CameraMetadata.CONTROL_EFFECT_MODE_MONO)) {
            b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_MONO)
        }
        val curve = if (cp.toneModes.has(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
            curves.getOrPut(look) { buildCurve(look) }
        } else {
            null
        }
        if (curve != null) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, curve)
        } else if (cp.toneModes.has(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        }
    }

    fun availableLooks(): List<Look> {
        val cp = caps ?: return listOf(Look.NATURAL)
        val hasCurves = cp.toneModes.has(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
        return Look.values().filter {
            when (it) {
                Look.NATURAL -> true
                Look.MONO -> cp.effects.has(CameraMetadata.CONTROL_EFFECT_MODE_MONO)
                else -> hasCurves
            }
        }
    }

    // ---- simple actions for the UI ----

    fun cycleFlash() {
        val cp = caps ?: return
        if (!cp.flashUnit) return
        flash = FlashMode.values()[(flash.ordinal + 1) % FlashMode.values().size]
        updatePreview()
    }

    fun cycleTimer() {
        timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
    }

    fun cycleLook() {
        val l = availableLooks()
        look = l[(l.indexOf(look) + 1) % l.size]
        updatePreview()
    }

    fun cycleRaw() {
        if (caps?.hasRaw != true) return
        rawMode = (rawMode + 1) % 3
        restartSession()
    }

    fun cycleRes() {
        val n = caps?.resChoices?.size ?: 0
        if (n < 2) return
        resIndex = (resIndex + 1) % n
        restartSession()
    }

    fun togglePro() {
        proMode = !proMode
    }

    fun toggleManual() {
        val cp = caps ?: return
        if (!cp.hasManual) {
            toast("This camera doesn't allow manual exposure")
            return
        }
        if (!manualExp) {
            if (liveIso > 0) iso = liveIso
            if (liveNs > 0) shutterNs = liveNs
            cp.isoRange?.let { iso = iso.coerceIn(it.lower, it.upper) }
            cp.expRange?.let { shutterNs = shutterNs.coerceIn(it.lower, it.upper) }
        }
        manualExp = !manualExp
        updatePreview()
    }

    fun toggleAeLock() {
        aeLock = !aeLock
        updatePreview()
    }

    fun toggleAfLock() {
        afLock = !afLock
        if (!afLock) afHold = false
        updatePreview()
    }

    fun toggleManualFocus() {
        val cp = caps ?: return
        if (cp.minFocus <= 0f) {
            toast("This lens has fixed focus")
            return
        }
        manualFocus = !manualFocus
        if (manualFocus) focusDist = liveFocusGuess(cp)
        updatePreview()
    }

    private fun liveFocusGuess(cp: Caps): Float =
        (lastResult?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f).coerceIn(0f, cp.minFocus)

    fun zoomBy(f: Float) {
        val r = caps?.zoomRange ?: return
        zoom = (zoom * f).coerceIn(r.lower, r.upper)
        updatePreview()
    }

    /** Tap = focus + meter there. Long-press = also lock focus and exposure. (u, v) are 0..1 on the preview. */
    fun focusAt(u: Float, v: Float, lock: Boolean) {
        val cp = caps ?: return
        reticle = Offset(u, v)
        focusState = if (manualFocus) 0 else 1
        if (lock) {
            afLock = true
            aeLock = true
        }
        val z = zoom
        handler.post {
            val rect = meteringRect(u, v, cp, z)
            if (cp.maxAfRegions > 0) afRegions = arrayOf(rect)
            if (cp.maxAeRegions > 0) aeRegions = arrayOf(rect)
            afHold = !manualFocus
            sendPreview(!manualFocus && cp.afModes.has(CameraMetadata.CONTROL_AF_MODE_AUTO))
        }
    }

    private fun meteringRect(u: Float, v: Float, cp: Caps, z: Float): MeteringRectangle {
        val a = cp.active
        val w = a.width()
        val h = a.height()
        val sx: Float
        val sy: Float
        when (cp.sensorOrientation) {
            90 -> { sx = v; sy = 1f - u }
            270 -> { sx = 1f - v; sy = u }
            180 -> { sx = 1f - u; sy = 1f - v }
            else -> { sx = u; sy = v }
        }
        val zz = max(1f, z)
        val cx = a.left + w * (0.5f + (sx - 0.5f) / zz)
        val cy = a.top + h * (0.5f + (sy - 0.5f) / zz)
        val half = (min(w, h) * 0.07f).toInt()
        val x = (cx.toInt() - half).coerceIn(a.left, a.right - 2 * half)
        val y = (cy.toInt() - half).coerceIn(a.top, a.bottom - 2 * half)
        return MeteringRectangle(x, y, half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    // ---------------------------------------------------------------- capture

    fun shoot() {
        if (busy) {
            if (countdown > 0) {
                timerJob?.cancel()
                countdown = 0
                busy = false
            }
            return
        }
        busy = true
        timerJob = uiScope.launch {
            var t = timer
            while (t > 0) {
                countdown = t
                delay(1000)
                t--
            }
            countdown = 0
            sound.play(MediaActionSound.SHUTTER_CLICK)
            camScope.launch { captureNow() }
        }
    }

    private fun jpegOrientation(): Int {
        val cp = caps ?: return 0
        val rot = ((deviceOrientation + 45) / 90 * 90) % 360
        return (cp.sensorOrientation + rot) % 360
    }

    private suspend fun captureNow() {
        val cp = caps ?: return fail("Camera not ready")
        val choice = cp.resChoices.getOrNull(resIndex)
        stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        if (choice != null && choice.maxRes && Build.VERSION.SDK_INT >= 31) {
            captureMaxRes(choice)
        } else {
            captureNormal()
        }
    }

    private suspend fun captureNormal() {
        val d = device ?: return fail("Camera not ready")
        val s = session ?: return fail("Camera not ready")
        val cp = caps ?: return fail("Camera not ready")
        val sf = previewSurface
        val wantRaw = rawMode != 0 && rawReader != null
        val wantJpeg = rawMode != 1 || rawReader == null
        pending.set((if (wantRaw) 1 else 0) + (if (wantJpeg) 1 else 0))
        try {
            // Flash needs a quick metering pre-flash before the real shot
            val needFlash = !manualExp && cp.flashUnit && (flash == FlashMode.AUTO || flash == FlashMode.ON) && sf != null
            if (needFlash && sf != null) {
                val pb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                pb.addTarget(sf)
                applyControls(pb, false, null)
                pb.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    if (flash == FlashMode.ON) CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH else CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH,
                )
                pb.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                s.capture(pb.build(), previewCb, handler)
                delay(200)
                val t0 = SystemClock.elapsedRealtime()
                while (lastAeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE && SystemClock.elapsedRealtime() - t0 < 1500) {
                    delay(30)
                }
            }

            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            if (wantJpeg) b.addTarget(jpegReader!!.surface)
            if (wantRaw) b.addTarget(rawReader!!.surface)
            applyControls(b, true, null)
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            rawImage = null
            rawResult = null
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (wantRaw) {
                        rawResult = result
                        writeDng()
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    fail("Capture failed")
                }
            }, handler)
        } catch (e: Throwable) {
            fail("Capture failed: ${e.message}")
        }
    }

    /** Full-resolution (e.g. 200/108/64/48 MP) mode: needs its own session, so the preview pauses briefly. */
    private suspend fun captureMaxRes(res: Res) {
        val d = device ?: return fail("Camera not ready")
        pending.set(1)
        val last = lastResult
        closeSession()
        delay(150)
        var reader: ImageReader? = null
        var s: CameraCaptureSession? = null
        try {
            reader = ImageReader.newInstance(res.size.width, res.size.height, ImageFormat.JPEG, 1)
            val oc = OutputConfiguration(reader.surface)
            oc.setSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            val gate = CompletableDeferred<CameraCaptureSession?>()
            d.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(oc), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            gate.complete(session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            gate.complete(null)
                        }
                    },
                ),
            )
            s = withTimeoutOrNull(5000) { gate.await() }
            if (s == null) {
                fail("This phone doesn't allow full-resolution capture in apps. Pick a smaller size.")
            } else {
                val saved = CompletableDeferred<Unit>()
                reader.setOnImageAvailableListener({ r ->
                    val img = r.acquireNextImage()
                    if (img != null) {
                        saveJpeg(img, res)
                        img.close()
                    }
                    saved.complete(Unit)
                }, handler)
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                applyControls(b, true, last)
                b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                        fail("Full-resolution capture failed")
                        saved.complete(Unit)
                    }
                }, handler)
                withTimeoutOrNull(25000) { saved.await() }
            }
        } catch (e: Throwable) {
            fail("Full-resolution capture failed: ${e.message}")
        } finally {
            try {
                s?.close()
            } catch (e: Exception) {
                // ignore
            }
            reader?.close()
            delay(100)
            tryStart() // bring the live preview back
        }
    }

    // ----------------------------------------------------------------- saving

    private fun onRawImage(img: Image) {
        rawImage = img
        writeDng()
    }

    @Synchronized
    private fun writeDng() {
        val img = rawImage ?: return
        val result = rawResult ?: return
        val cp = caps ?: return
        rawImage = null
        rawResult = null
        try {
            val uri = newUri("LUMEN_$stamp.dng", "image/x-adobe-dng") ?: throw IllegalStateException("no storage")
            val dng = DngCreator(cp.lens.chars, result)
            dng.setOrientation(exifOrientation(jpegOrientation()))
            ctx.contentResolver.openOutputStream(uri)?.use { dng.writeImage(it, img) }
            dng.close()
            img.close()
            publish(uri)
            finishOne("RAW (DNG) saved")
        } catch (e: Throwable) {
            img.close()
            fail("RAW save failed: ${e.message}")
        }
    }

    private fun exifOrientation(deg: Int): Int = when (deg) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun saveJpeg(image: Image, res: Res) {
        try {
            val uri = newUri("LUMEN_$stamp.jpg", "image/jpeg") ?: throw IllegalStateException("no storage")
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                val ch = Channels.newChannel(out)
                val buf = image.planes[0].buffer
                while (buf.hasRemaining()) ch.write(buf)
            }
            publish(uri)
            finishOne("${res.label} photo saved")
        } catch (e: Throwable) {
            fail("Save failed: ${e.message}")
        }
    }

    private fun newUri(name: String, mime: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LumenCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
    }

    private fun publish(uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, v, null, null)
        main.post { lastUri = uri }
    }

    private fun finishOne(msg: String) {
        if (pending.decrementAndGet() <= 0) {
            main.post {
                busy = false
                message = "✓ $msg"
            }
        }
    }

    private fun fail(msg: String) {
        pending.set(0)
        main.post {
            busy = false
            message = msg
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
