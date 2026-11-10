package dev.lumen.camera

import android.content.Intent
import android.hardware.camera2.CameraMetadata
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val Amber = Color(0xFFFFC857)

@Composable
fun CameraScreen(e: CameraEngine) {
    LaunchedEffect(e.message) {
        if (e.message != null) {
            delay(2500)
            e.message = null
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        TopBar(e)
        PreviewArea(e)
        ControlPanel(e)
        Spacer(Modifier.weight(1f))
        BottomBar(e)
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(e: CameraEngine) {
    val cp = e.caps
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (cp?.flashUnit == true) Pill("⚡ " + e.flash.label, e.flash != FlashMode.OFF) { e.cycleFlash() }
        Pill("⏱ " + (if (e.timer == 0) "Off" else "${e.timer}s"), e.timer != 0) { e.cycleTimer() }
        Pill("◐ " + e.look.label, e.look != Look.NATURAL) { e.cycleLook() }
        if (cp?.hasRaw == true) {
            Pill(listOf("JPG", "RAW", "RAW+JPG")[e.rawMode], e.rawMode != 0) { e.cycleRaw() }
        }
        val res = cp?.resChoices?.getOrNull(e.resIndex)
        if (res != null) Pill(res.label, false) { e.cycleRes() }
        Pill("▦ Grid", e.grid) { e.grid = !e.grid }
    }
}

// ------------------------------------------------------------------ preview

@Composable
private fun PreviewArea(e: CameraEngine) {
    LaunchedEffect(e.reticle) {
        if (e.reticle != null) {
            delay(3500)
            if (!e.afLock) e.reticle = null
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(10.dp)),
    ) {
        AndroidView(
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            e.onSurfaceCreated(h)
                        }

                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
                            e.onSurfaceChanged(h, w, hh)
                        }

                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            e.onSurfaceDestroyed()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(Modifier.fillMaxSize()) {
            if (e.grid) {
                val w = size.width
                val h = size.height
                for (i in 1..2) {
                    drawLine(Color(0x66FFFFFF), Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), 1.5f)
                    drawLine(Color(0x66FFFFFF), Offset(0f, h * i / 3f), Offset(w, h * i / 3f), 1.5f)
                }
            }
            val r = e.reticle
            if (r != null) {
                val cx = r.x * size.width
                val cy = r.y * size.height
                val s = 36.dp.toPx()
                val col = when (e.focusState) {
                    2 -> Color(0xFF7CFC9A)
                    3 -> Color(0xFFFF6B6B)
                    else -> Amber
                }
                drawRect(
                    col,
                    Offset(cx - s, cy - s),
                    androidx.compose.ui.geometry.Size(s * 2, s * 2),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }

        // touch layer: tap = focus, long-press = focus + lock, pinch = zoom
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o -> e.focusAt(o.x / size.width, o.y / size.height, false) },
                        onLongPress = { o -> e.focusAt(o.x / size.width, o.y / size.height, true) },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, z, _ -> if (z != 1f) e.zoomBy(z) }
                },
        )

        val info = buildString {
            append("ISO ").append(e.liveIso)
            append("  ").append(fmtShutter(e.liveNs))
            if (e.zoom > 1.05f) append("  ").append(String.format("%.1fx", e.zoom))
        }
        Text(
            info,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .background(Color(0x66000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (e.aeLock || e.afLock) {
            Text(
                (if (e.aeLock) "AE-L " else "") + (if (e.afLock) "AF-L" else ""),
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Amber, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (e.countdown > 0) {
            Text(
                "${e.countdown}",
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        val msg = e.message
        if (msg != null) {
            Text(
                msg,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(14.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// ------------------------------------------------------------ control panel

private val WbNames = mapOf(
    CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Tungsten",
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Fluoro",
    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Warm fl.",
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "Twilight",
    CameraMetadata.CONTROL_AWB_MODE_SHADE to "Shade",
)

@Composable
private fun ControlPanel(e: CameraEngine) {
    val cp = e.caps ?: return
    var tab by remember { mutableIntStateOf(0) }
    val tabs = buildList {
        add("EV")
        add("ISO")
        add("SHUTTER")
        add("FOCUS")
        add("WB")
        if (cp.apertures.size > 1) add("DEPTH")
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("AUTO", !e.proMode) { e.proMode = false }
            Pill("PRO", e.proMode) { e.proMode = true }
            Pill("AE-L", e.aeLock) { e.toggleAeLock() }
            Pill("AF-L", e.afLock) { e.toggleAfLock() }
            if (e.proMode) {
                tabs.forEachIndexed { i, name -> Pill(name, tab == i) { tab = i } }
            }
        }
        Spacer(Modifier.padding(top = 2.dp))

        val evRange = cp.evRange
        if (!e.proMode || tabs.getOrNull(tab) == "EV") {
            if (evRange != null && evRange.upper > evRange.lower && cp.evStep > 0f) {
                SliderRow(
                    "EV", fmtEv(e.ev, cp.evStep), e.ev.toFloat(),
                    { e.ev = it.roundToInt(); e.updatePreview() },
                    enabled = !e.manualExp,
                    range = evRange.lower.toFloat()..evRange.upper.toFloat(),
                )
            }
        } else {
            when (tabs.getOrNull(tab)) {
                "ISO" -> {
                    Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if (e.manualExp) "Manual exposure ON" else "Manual exposure OFF", e.manualExp) { e.toggleManual() }
                    }
                    val r = cp.isoRange
                    if (r != null && r.upper > r.lower) {
                        val lo = r.lower.toDouble()
                        val hi = r.upper.toDouble()
                        SliderRow(
                            "ISO", e.iso.toString(), toFrac(e.iso.toDouble(), lo, hi),
                            { f -> e.iso = fromFrac(f, lo, hi).roundToInt(); e.updatePreview() },
                            enabled = e.manualExp,
                        )
                    }
                }
                "SHUTTER" -> {
                    val r = cp.expRange
                    if (r != null) {
                        val lo = max(r.lower, 100_000L).toDouble()
                        val hi = min(r.upper, 30_000_000_000L).toDouble()
                        SliderRow(
                            "Shutter", fmtShutter(e.shutterNs), toFrac(e.shutterNs.toDouble(), lo, hi),
                            { f -> e.shutterNs = fromFrac(f, lo, hi).toLong(); e.updatePreview() },
                            enabled = e.manualExp,
                        )
                        Text(
                            if (e.manualExp) "Live preview is limited to 1/15s so it stays smooth; the photo uses your full shutter."
                            else "Turn on Manual exposure (ISO tab) to set the shutter.",
                            color = Color(0x99FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                }
                "FOCUS" -> {
                    Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("AF", !e.manualFocus) { if (e.manualFocus) e.toggleManualFocus() }
                        Pill("MF", e.manualFocus) { if (!e.manualFocus) e.toggleManualFocus() }
                    }
                    if (cp.minFocus > 0f) {
                        SliderRow(
                            "Focus",
                            if (e.focusDist < 0.05f) "∞" else String.format("%.2fm", 1f / e.focusDist),
                            e.focusDist / cp.minFocus,
                            { f -> e.focusDist = f * cp.minFocus; e.updatePreview() },
                            enabled = e.manualFocus,
                        )
                    }
                }
                "WB" -> {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WbNames.forEach { (mode, name) ->
                            if (cp.awbModes.contains(mode)) Pill(name, e.wb == mode) { e.wb = mode; e.updatePreview() }
                        }
                    }
                }
                "DEPTH" -> {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        cp.apertures.forEach { a ->
                            Pill("f/" + String.format("%.1f", a), e.aperture == a) {
                                e.aperture = a
                                if (!e.manualExp) e.toggleManual() else e.updatePreview()
                            }
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- bottom bar

@Composable
private fun BottomBar(e: CameraEngine) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            e.lenses.forEachIndexed { i, l -> Pill(l.label, i == e.lensIndex) { e.selectLens(i) } }
        }
        Box(
            Modifier
                .size(78.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .padding(7.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (e.busy && e.countdown == 0) 0.45f else 1f))
                .clickable { e.shoot() },
            contentAlignment = Alignment.Center,
        ) {
            if (e.countdown > 0) Text("${e.countdown}", color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Pill("▣ Photos", false) {
                val uri = e.lastUri
                if (uri != null) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------- small widgets

@Composable
private fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Amber else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xB3FFFFFF), fontSize = 12.sp, modifier = Modifier.width(54.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Color(0x33FFFFFF),
                disabledThumbColor = Color(0x55FFFFFF),
                disabledActiveTrackColor = Color(0x33FFFFFF),
                disabledInactiveTrackColor = Color(0x22FFFFFF),
            ),
        )
        Text(valueText, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
    }
}

private fun toFrac(v: Double, lo: Double, hi: Double): Float =
    (ln(v.coerceIn(lo, hi) / lo) / ln(hi / lo)).toFloat()

private fun fromFrac(f: Float, lo: Double, hi: Double): Double = lo * (hi / lo).pow(f.toDouble())

private fun fmtShutter(ns: Long): String {
    if (ns <= 0L) return "—"
    val s = ns / 1e9
    return if (s >= 0.5) String.format("%.1fs", s) else "1/" + (1.0 / s).roundToInt()
}

private fun fmtEv(steps: Int, stepSize: Float): String = String.format("%+.1f", steps * stepSize)
