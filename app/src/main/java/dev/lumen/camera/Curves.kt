package dev.lumen.camera

import android.hardware.camera2.params.TonemapCurve
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

enum class Look(val label: String) {
    NATURAL("Natural"), CRISP("Crisp"), VIVID("Vivid"), WARM("Warm"),
    COOL("Cool"), FILM("Film"), MONO("B&W"), DLOG("D-Log"),
}

enum class FlashMode(val label: String) { OFF("Off"), AUTO("Auto"), ON("On"), TORCH("Torch") }

private fun srgb(x: Float): Float =
    if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

/** S-curve contrast; k = 0 is neutral, ~0.4 is punchy. Stays monotonic for k < 1. */
private fun contrast(y: Float, k: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return (y - k * sin(twoPi * y) / twoPi).coerceIn(0f, 1f)
}

/**
 * Filters and D-Log are tone curves applied inside the camera's own image pipeline, so they show
 * live in the preview, are baked into the JPEG, cost no extra processing and add no distortion.
 * Returns null for looks that don't use a curve.
 */
internal fun buildCurve(look: Look): TonemapCurve? {
    if (look == Look.NATURAL || look == Look.MONO) return null
    val n = 64
    val r = FloatArray(n * 2)
    val g = FloatArray(n * 2)
    val b = FloatArray(n * 2)
    for (i in 0 until n) {
        val x = i / (n - 1f)
        val y: Float
        var tr = 1f
        var tg = 1f
        var tb = 1f
        when (look) {
            Look.CRISP -> y = contrast(srgb(x), 0.22f)
            Look.VIVID -> {
                y = contrast(srgb(x), 0.42f)
                tg = 1.02f
            }
            Look.WARM -> {
                y = contrast(srgb(x), 0.12f)
                tr = 1.07f
                tb = 0.92f
            }
            Look.COOL -> {
                y = contrast(srgb(x), 0.12f)
                tr = 0.93f
                tb = 1.07f
            }
            Look.FILM -> {
                y = 0.05f + 0.90f * contrast(srgb(x), 0.18f)
                tr = 1.03f
                tb = 0.97f
            }
            Look.DLOG -> y = 0.07f + 0.88f * (ln(1f + 30f * x) / ln(31f))
            else -> y = srgb(x)
        }
        r[2 * i] = x; r[2 * i + 1] = (y * tr).coerceIn(0f, 1f)
        g[2 * i] = x; g[2 * i + 1] = (y * tg).coerceIn(0f, 1f)
        b[2 * i] = x; b[2 * i + 1] = (y * tb).coerceIn(0f, 1f)
    }
    return TonemapCurve(r, g, b)
}
