package nl.icthorse.miraicastlab.projection

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The geometry a [android.hardware.display.VirtualDisplay] would be created with.
 *
 * The probe and the live experiment both go through this one function on purpose: a report that
 * says "a VirtualDisplay would target 1280x1056" and an experiment that silently captured something
 * else would be a fabricated finding. One measurement path, one answer.
 *
 * Scaling rationale: mirroring the Fold's inner panel 1:1 through an ImageReader means moving
 * ~16 MB per frame across the GPU/CPU boundary, which measures the copy rather than the capture
 * path. Capping the long edge at 1280 keeps the measurement about MediaProjection. Both edges are
 * floored to a multiple of 16 because that is what AVC encoders align to; this shifts the aspect
 * ratio by well under a percent, and [aspectErrorPercent] states by how much rather than hiding it.
 */
data class CaptureTarget(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val densityDpi: Int,
    val width: Int,
    val height: Int,
    val source: String,
    val error: String? = null,
) {
    val sourceResolution: String get() = sourceWidth.toString() + "x" + sourceHeight
    val resolution: String get() = width.toString() + "x" + height

    /** Downscale factor applied to the long edge, 1.0 when the panel already fits. */
    val scale: Float
        get() {
            val longEdge = max(sourceWidth, sourceHeight)
            return if (longEdge <= 0) 0f else max(width, height).toFloat() / longEdge
        }

    /** How far the 16-aligned target drifts from the source aspect ratio, in percent. */
    val aspectErrorPercent: Float
        get() {
            if (sourceWidth <= 0 || sourceHeight <= 0 || height <= 0) return 0f
            val srcAr = sourceWidth.toFloat() / sourceHeight
            val dstAr = width.toFloat() / height
            return kotlin.math.abs(dstAr - srcAr) / srcAr * 100f
        }

    val isUsable: Boolean get() = width >= 16 && height >= 16

    fun toLogDetails(): Map<String, String> = buildMap {
        put("source", source)
        put("sourceResolution", sourceResolution)
        put("targetResolution", resolution)
        put("densityDpi", densityDpi.toString())
        put("scale", String.format("%.3f", scale))
        error?.let { put("readError", it) }
    }

    companion object {
        /** Long-edge cap for the capture surface. */
        const val MAX_LONG_EDGE = 1280

        /** Encoder-friendly alignment; also what most VirtualDisplay consumers prefer. */
        private const val ALIGN = 16

        /**
         * Reads the default display's full pixel size.
         *
         * Three sources are tried in order of authority and the one that answered is recorded, so
         * the report can never claim a resolution without saying where it came from. Resources'
         * DisplayMetrics is last because it may exclude system decorations, which would understate
         * what MediaProjection actually mirrors.
         */
        fun measure(context: Context, maxLongEdge: Int = MAX_LONG_EDGE): CaptureTarget {
            var w = 0
            var h = 0
            var dpi = 0
            var source = "none"
            val errors = mutableListOf<String>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val wm = context.getSystemService(WindowManager::class.java)
                    // maximumWindowMetrics reports the whole display area including cutouts and
                    // system bars, which is what a mirroring VirtualDisplay receives.
                    val bounds = wm?.maximumWindowMetrics?.bounds
                    if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                        w = bounds.width()
                        h = bounds.height()
                        source = "WindowManager#maximumWindowMetrics"
                    }
                } catch (t: Throwable) {
                    // Thrown when handed a non-visual context on some OEM builds. Not fatal: fall through.
                    errors += "maximumWindowMetrics: " + t::class.java.simpleName
                }
            }

            try {
                val display = context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION") // Deprecated in API 30, still the only non-visual-context path.
                    display.getRealMetrics(metrics)
                    dpi = metrics.densityDpi
                    if (w <= 0 || h <= 0) {
                        w = metrics.widthPixels
                        h = metrics.heightPixels
                        source = "Display#getRealMetrics"
                    }
                }
            } catch (t: Throwable) {
                errors += "getRealMetrics: " + t::class.java.simpleName
            }

            if (w <= 0 || h <= 0 || dpi <= 0) {
                try {
                    val dm = context.resources.displayMetrics
                    if (dpi <= 0) dpi = dm.densityDpi
                    if (w <= 0 || h <= 0) {
                        w = dm.widthPixels
                        h = dm.heightPixels
                        source = "Resources#displayMetrics (may exclude system decorations)"
                    }
                } catch (t: Throwable) {
                    errors += "resources: " + t::class.java.simpleName
                }
            }

            val longEdge = max(w, h)
            val factor = if (longEdge > maxLongEdge && longEdge > 0) {
                maxLongEdge.toFloat() / longEdge
            } else {
                1f
            }
            return CaptureTarget(
                sourceWidth = w,
                sourceHeight = h,
                densityDpi = if (dpi > 0) dpi else DisplayMetrics.DENSITY_DEFAULT,
                width = align((w * factor).roundToInt()),
                height = align((h * factor).roundToInt()),
                source = source,
                error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
            )
        }

        private fun align(v: Int): Int = max(ALIGN, (v / ALIGN) * ALIGN)
    }
}
