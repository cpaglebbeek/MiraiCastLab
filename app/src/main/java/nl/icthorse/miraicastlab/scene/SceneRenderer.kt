package nl.icthorse.miraicastlab.scene

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import nl.icthorse.miraicastlab.core.SessionLogger
import kotlin.math.max
import kotlin.math.min

/**
 * The diagnostic scene surface.
 *
 * Everything is generated in code: no drawable, no font asset, no video, nothing copyrighted and
 * nothing that could be mistaken for content the head unit is allowed to treat specially. What the
 * Toyota shows is exactly what this function drew.
 *
 * The whole scene is one [Canvas]. The draw lambda reads [SceneEngine.tick] first, which makes the
 * frame callback invalidate the draw phase only - no recomposition and no relayout per frame.
 */
@Composable
internal fun SceneSurface(engine: SceneEngine, modifier: Modifier = Modifier) {
    // The run id is burned into the picture so a photograph of the Toyota screen is self-identifying.
    val runTag = remember(SessionLogger.testRunId) {
        "TEST LAB " + SessionLogger.testRunId.take(8).uppercase()
    }
    Canvas(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { engine.onSurfaceSize(it.width, it.height) }
            .pointerInput(engine) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        engine.onTouch(
                            change.position.x,
                            change.position.y,
                            change.pressed,
                            pointerTypeName(change.type),
                        )
                    }
                }
            },
    ) {
        drawScene(engine, runTag)
    }
}

private fun pointerTypeName(type: PointerType): String = when (type) {
    PointerType.Touch -> "touch"
    PointerType.Mouse -> "mouse"
    PointerType.Stylus -> "stylus"
    PointerType.Eraser -> "eraser"
    else -> "unknown"
}

// --------------------------------------------------------------------------- palette

private val BandBg = Color(0xFF0D1116)
private val Accent = Color(0xFF00E5A0)
private val Amber = Color(0xFFFFC845)
private val GridLine = Color(0x3355708A)

// --------------------------------------------------------------------------- top level

private fun DrawScope.drawScene(e: SceneEngine, runTag: String) {
    // First statement on purpose: this snapshot read is the draw-phase subscription. Removing it
    // means the scene renders once and then freezes while still claiming 60 fps.
    val frame = e.tick.longValue
    e.onDrawn()

    val w = size.width
    val h = size.height
    if (w < 4f || h < 4f) return
    val u = min(w, h) / 100f
    e.ensureAssets()
    e.sizePaints(u)
    val c = drawContext.canvas.nativeCanvas
    val headerH = h * 0.135f

    when (e.pattern) {
        ScenePattern.DIAGNOSTIC -> {
            drawScrollBars(e, w, h, headerH)
            drawColourBand(e, c, w * 0.02f, h * 0.785f, w * 0.90f, h * 0.10f, u, detailed = false)
            drawBall(e, w * 0.26f, h * 0.42f, w * 0.66f, h * 0.34f, u)
            drawSweepDial(e, c, w * 0.12f, h * 0.30f, min(w * 0.09f, h * 0.115f), u, frame)
            drawStatsBlock(e, c, w * 0.26f, h * 0.185f, u)
            drawWatermark(e, c, w * 0.5f, h * 0.60f)
        }
        ScenePattern.MOTION -> {
            drawBall(e, w * 0.03f, headerH + h * 0.02f, w * 0.94f, h * 0.94f - headerH, u * 2.2f)
            drawSweepDial(e, c, w * 0.86f, h * 0.80f, min(w * 0.11f, h * 0.14f), u, frame)
            drawWatermark(e, c, w * 0.5f, h * 0.55f)
        }
        ScenePattern.STRIPES -> drawStripeField(e, c, w, h, headerH, u)
        ScenePattern.COLOUR -> drawColourBand(e, c, w * 0.03f, headerH + h * 0.04f, w * 0.94f, h * 0.72f, u, detailed = true)
        ScenePattern.LATENCY -> drawLatencyPattern(e, c, w, h, frame)
        ScenePattern.GRID -> drawGridPattern(e, c, w, h, u)
    }

    if (e.showGrid && e.pattern != ScenePattern.GRID) drawGridOverlay(w, h, u)
    drawHeader(e, c, w, headerH, u, frame)
    drawCorners(e, c, w, h, u, runTag)
    drawTouchIndicator(e, c, w, h, u)
    drawAudioPulse(e, w, h, u)
    drawLatencyFlash(e, c, w, h, u, frame)
}

// --------------------------------------------------------------------------- header

/**
 * The band that is present in every pattern: frame counter, monotonic timestamp and the frame
 * parity blocks. A photograph that catches this band is enough to identify the run, the frame and
 * the moment, which is what makes a picture of the head unit usable as evidence at all.
 */
private fun DrawScope.drawHeader(
    e: SceneEngine,
    c: NativeCanvas,
    w: Float,
    headerH: Float,
    u: Float,
    frame: Long,
) {
    drawRect(BandBg, Offset.Zero, Size(w, headerH))
    drawLine(Accent, Offset(0f, headerH), Offset(w, headerH), strokeWidth = max(2f, u * 0.25f))

    val labelY = headerH * 0.26f
    val valueY = headerH * 0.80f

    e.pSmall.textAlign = Paint.Align.LEFT
    e.pBig.textAlign = Paint.Align.LEFT
    e.label(c, "FRAME", w * 0.02f, labelY, e.pSmall)
    e.num(c, frame, w * 0.02f, valueY, e.pBig, minDigits = 6)

    // Monotonic timestamp, millisecond resolution, from SystemClock.elapsedRealtimeNanos.
    e.pMed.textAlign = Paint.Align.RIGHT
    e.pSmall.textAlign = Paint.Align.RIGHT
    val right = w - w * 0.02f
    e.label(c, "elapsedRealtime ms", right, labelY, e.pSmall)
    e.num(c, e.stampNanos / 1_000_000L, right, valueY, e.pMed, minDigits = 9)
    e.pMed.textAlign = Paint.Align.LEFT
    e.pSmall.textAlign = Paint.Align.LEFT

    drawParityBlocks(e, c, w * 0.50f, headerH * 0.18f, u, frame)
}

/**
 * Three blocks toggling every 1, 2 and 4 frames.
 *
 * A receiver that drops every other frame renders the 1-frame block as a stable colour instead of
 * a flicker, and a camera at 1/500 s sees which of the three still alternate. This distinguishes
 * "the phone rendered 60 fps and the link delivered 30" from "the phone rendered 30".
 */
private fun DrawScope.drawParityBlocks(e: SceneEngine, c: NativeCanvas, cx: Float, top: Float, u: Float, frame: Long) {
    val s = u * 4.5f
    val gap = u * 1.2f
    val startX = cx - (s * 3 + gap * 2) / 2f
    for (i in 0 until 3) {
        val period = 1 shl i
        val on = (frame / period) % 2L == 0L
        val x = startX + i * (s + gap)
        drawRect(if (on) Color.White else Color(0xFF12181F), Offset(x, top), Size(s, s))
        drawRect(GridLine, Offset(x, top), Size(s, s), style = Stroke(width = 1f))
    }
    e.pSmall.textAlign = Paint.Align.CENTER
    e.label(c, "PARITY /1 /2 /4", cx, top + s + u * 3.0f, e.pSmall)
    e.pSmall.textAlign = Paint.Align.LEFT
}

// --------------------------------------------------------------------------- sweep dial

/**
 * 60 positions, one advanced per drawn frame.
 *
 * Every position keeps the frame number at which it was last lit, so the ring shows a decaying
 * comet of the last 60 frames. At a true 60 Hz the comet is a continuous arc. If the link delivers
 * 30 fps the photograph shows every second position lit and the gaps are countable, which turns a
 * still photo of the Toyota screen into a frame-rate measurement of the *link* rather than of the
 * phone.
 */
private fun DrawScope.drawSweepDial(
    e: SceneEngine,
    c: NativeCanvas,
    cx: Float,
    cy: Float,
    r: Float,
    u: Float,
    frame: Long,
) {
    drawCircle(Color(0xFF141A22), r * 1.18f, Offset(cx, cy))
    drawCircle(GridLine, r * 1.18f, Offset(cx, cy), style = Stroke(width = max(1f, u * 0.12f)))
    val inner = r * 0.60f
    val stroke = max(2f, r * 0.075f)
    for (i in 0 until SceneEngine.SWEEP) {
        val age = frame - e.sweepFrame[i]
        val alpha = if (age in 0..SceneEngine.SWEEP.toLong()) {
            0.12f + 0.88f * (1f - age.toFloat() / SceneEngine.SWEEP)
        } else {
            0.10f
        }
        val ca = e.sweepCosAt(i)
        val sa = e.sweepSinAt(i)
        drawLine(
            Color.White.copy(alpha = alpha),
            Offset(cx + ca * inner, cy + sa * inner),
            Offset(cx + ca * r, cy + sa * r),
            strokeWidth = stroke,
        )
    }
    val i = e.sweepIndex
    drawLine(
        Accent,
        Offset(cx, cy),
        Offset(cx + e.sweepCosAt(i) * r * 0.92f, cy + e.sweepSinAt(i) * r * 0.92f),
        strokeWidth = max(3f, r * 0.10f),
    )
    drawCircle(Accent, max(2f, r * 0.08f), Offset(cx, cy))
    e.pMed.textAlign = Paint.Align.CENTER
    e.num(c, i.toLong(), cx, cy + r * 1.72f, e.pMed, minDigits = 2)
    e.pSmall.textAlign = Paint.Align.CENTER
    e.label(c, "1 POS / FRAME", cx, cy + r * 1.72f + u * 3.4f, e.pSmall)
    e.pMed.textAlign = Paint.Align.LEFT
    e.pSmall.textAlign = Paint.Align.LEFT
}

// --------------------------------------------------------------------------- statistics text

private fun DrawScope.drawStatsBlock(e: SceneEngine, c: NativeCanvas, x0: Float, y0: Float, u: Float) {
    val step = u * 5.0f
    var y = y0
    val dim = 0xFF93A3B4.toInt()
    val bright = 0xFFE8EEF4.toInt()

    e.pMed.color = bright
    var x = e.label(c, "FPS ", x0, y, e.pMed)
    x = e.num(c, SceneEngine.scaled(e.fps, 1), x, y, e.pMed, decimals = 1)
    x = e.label(c, "  P99 ", x, y, e.pMed)
    x = e.num(c, SceneEngine.scaled(e.p99FrameMs, 1), x, y, e.pMed, decimals = 1)
    e.label(c, " ms", x, y, e.pMed)

    y += step
    e.pSmall.color = dim
    x = e.label(c, "AVG ", x0, y, e.pSmall)
    x = e.num(c, SceneEngine.scaled(e.avgFrameMs, 2), x, y, e.pSmall, decimals = 2)
    x = e.label(c, " ms  MAX ", x, y, e.pSmall)
    x = e.num(c, SceneEngine.scaled(e.maxFrameNanos / 1_000_000f, 1), x, y, e.pSmall, decimals = 1)
    x = e.label(c, " ms  LONG ", x, y, e.pSmall)
    e.num(c, e.longFrames, x, y, e.pSmall)

    y += step
    x = e.label(c, "SURFACE ", x0, y, e.pSmall)
    x = e.num(c, e.surfaceW.toLong(), x, y, e.pSmall)
    x = e.label(c, "x", x, y, e.pSmall)
    x = e.num(c, e.surfaceH.toLong(), x, y, e.pSmall)
    x = e.label(c, " px  ", x, y, e.pSmall)
    e.label(c, if (e.orientationLandscape) "LANDSCAPE" else "PORTRAIT", x, y, e.pSmall)

    y += step
    x = e.label(c, "DISPLAY ", x0, y, e.pSmall)
    x = e.num(c, e.displayId.toLong(), x, y, e.pSmall)
    x = e.label(c, " ", x, y, e.pSmall)
    x = e.label(c, e.displayName, x, y, e.pSmall)
    x = e.label(c, "  ", x, y, e.pSmall)
    x = e.num(c, SceneEngine.scaled(e.refreshHz, 1), x, y, e.pSmall, decimals = 1)
    e.label(c, " Hz", x, y, e.pSmall)

    y += step
    x = e.label(c, "SPEED ", x0, y, e.pSmall)
    x = e.num(c, SceneEngine.scaled(e.speed, 2), x, y, e.pSmall, decimals = 2)
    x = e.label(c, "x  ", x, y, e.pSmall)
    x = e.label(c, if (e.playing) "PLAYING" else "PAUSED", x, y, e.pSmall)
    x = e.label(c, "  ", x, y, e.pSmall)
    e.label(c, e.pattern.label, x, y, e.pSmall)

    y += step
    x = e.label(c, "ANIM FRAMES ", x0, y, e.pSmall)
    x = e.num(c, e.animFrame, x, y, e.pSmall)
    x = e.label(c, "  SCENE ", x, y, e.pSmall)
    x = e.num(c, SceneEngine.scaled(e.sceneNanos / 1_000_000_000.0f, 1), x, y, e.pSmall, decimals = 1)
    e.label(c, " s", x, y, e.pSmall)

    y += step
    x = e.label(c, "TOUCH ", x0, y, e.pSmall)
    if (e.touchX < 0f) {
        e.label(c, "none", x, y, e.pSmall)
    } else {
        x = e.num(c, e.touchX.toLong(), x, y, e.pSmall)
        x = e.label(c, ",", x, y, e.pSmall)
        x = e.num(c, e.touchY.toLong(), x, y, e.pSmall)
        x = e.label(c, " ", x, y, e.pSmall)
        x = e.label(c, e.touchType, x, y, e.pSmall)
        x = e.label(c, if (e.touchDown) " DOWN n=" else " UP n=", x, y, e.pSmall)
        e.num(c, e.touchCount, x, y, e.pSmall)
    }
    if (e.inputCapture) {
        e.pSmall.color = Amber.toArgb()
        e.label(c, "INPUT CAPTURE LOGGING", x0, y + step, e.pSmall)
        e.pSmall.color = dim
    }
}

// --------------------------------------------------------------------------- scrolling stripe bars

/**
 * Blits three 1-pixel-accurate stripe frequencies, horizontally and vertically.
 *
 * The source rows are uniform in y, so stretching a row vertically is lossless while the x blit
 * stays strictly 1:1 with [FilterQuality.None]. That is the only way to put a genuine 1-pixel
 * stripe on screen from Compose without the GPU quietly filtering it first - and a filtered stripe
 * would make the receiver look better than it is.
 */
private fun DrawScope.drawScrollBars(e: SceneEngine, w: Float, h: Float, headerH: Float) {
    val sh = e.stripesH ?: return
    val sv = e.stripesV ?: return
    // 8 px is the least common period of the three rows, so scrolling modulo 8 is seamless.
    val scrollH = ((e.sceneNanos / 8_333_333L) % 8L).toInt()
    val scrollV = ((e.sceneNanos / 11_111_111L) % 8L).toInt()

    val barH = h * 0.095f
    val barTop = h - barH
    val barW = (w * 0.94f).toInt().coerceAtLeast(1)
    val band = (barH / 3f)
    for (row in 0 until 3) {
        drawImage(
            image = sh,
            srcOffset = IntOffset(scrollH, row),
            srcSize = IntSize(barW, 1),
            dstOffset = IntOffset(0, (barTop + row * band).toInt()),
            dstSize = IntSize(barW, band.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.None,
        )
    }

    val vbW = (w * 0.06f).toInt().coerceAtLeast(3)
    val vbTop = headerH.toInt()
    val vbH = (barTop - headerH).toInt().coerceAtLeast(1)
    val col = vbW / 3
    for (c in 0 until 3) {
        drawImage(
            image = sv,
            srcOffset = IntOffset(c, scrollV),
            srcSize = IntSize(1, vbH),
            dstOffset = IntOffset((w - vbW).toInt() + c * col, vbTop),
            dstSize = IntSize(col.coerceAtLeast(1), vbH),
            filterQuality = FilterQuality.None,
        )
    }
}

/** Full-screen stripe and checkerboard field: the scaling/interlacing pattern. */
private fun DrawScope.drawStripeField(e: SceneEngine, c: NativeCanvas, w: Float, h: Float, headerH: Float, u: Float) {
    val sh = e.stripesH ?: return
    val ck = e.checker ?: return
    val scroll = ((e.sceneNanos / 8_333_333L) % 8L).toInt()
    val top = headerH
    val usable = h - headerH
    val bandH = usable / 4f
    val fullW = w.toInt().coerceAtLeast(1)

    // Three bands of scrolling stripes, one per frequency, labelled with their pixel period.
    for (row in 0 until 3) {
        val y = top + row * bandH
        drawImage(
            image = sh,
            srcOffset = IntOffset(scroll, row),
            srcSize = IntSize(fullW, 1),
            dstOffset = IntOffset(0, y.toInt()),
            dstSize = IntSize(fullW, bandH.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.None,
        )
        e.pSmall.color = Amber.toArgb()
        e.label(c, if (row == 0) "1 px" else if (row == 1) "2 px" else "4 px", u * 1.5f, y + u * 4f, e.pSmall)
        e.pSmall.color = 0xFF93A3B4.toInt()
    }

    // Bottom band: 1-pixel checkerboard, blitted 1:1. Anything that rescales turns it into grey.
    val ckTop = (top + 3 * bandH).toInt()
    val ckH = bandH.toInt().coerceAtLeast(1)
    var x = 0
    while (x < fullW) {
        var y = 0
        while (y < ckH) {
            val cw = min(ck.width, fullW - x)
            val chh = min(ck.height, ckH - y)
            drawImage(
                image = ck,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(cw, chh),
                dstOffset = IntOffset(x, ckTop + y),
                dstSize = IntSize(cw, chh),
                filterQuality = FilterQuality.None,
            )
            y += ck.height
        }
        x += ck.width
    }
    e.pSmall.color = Amber.toArgb()
    e.label(c, "1 px CHECKERBOARD - flat grey here means the link rescaled the picture", u * 1.5f, ckTop + u * 5f, e.pSmall)
    e.pSmall.color = 0xFF93A3B4.toInt()
}

// --------------------------------------------------------------------------- colour reference

private val PrimaryBlocks = intArrayOf(
    0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
    0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFF00.toInt(), 0xFF000000.toInt(),
)

/**
 * Colour and level reference.
 *
 * The near-black and near-white steps are the important half: a link that converts to limited
 * range crushes 0..15 to a single black and clips 236..255 to a single white, and that shows up
 * here as adjacent steps becoming indistinguishable. The tester photographs this and counts.
 */
private fun DrawScope.drawColourBand(
    e: SceneEngine,
    c: NativeCanvas,
    x0: Float,
    y0: Float,
    bw: Float,
    bh: Float,
    u: Float,
    detailed: Boolean,
) {
    val rows = if (detailed) 4 else 2
    val rowH = bh / rows

    val pw = bw / PrimaryBlocks.size
    for (i in PrimaryBlocks.indices) {
        drawRect(Color(PrimaryBlocks[i]), Offset(x0 + i * pw, y0), Size(pw, rowH))
    }
    drawRect(GridLine, Offset(x0, y0), Size(bw, rowH), style = Stroke(width = 1f))

    val greySteps = 17
    val gw = bw / greySteps
    for (i in 0 until greySteps) {
        val v = (i * 255 / (greySteps - 1))
        drawRect(greyColor(v), Offset(x0 + i * gw, y0 + rowH), Size(gw, rowH))
        if (detailed) {
            e.pSmall.color = if (v > 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            e.pSmall.textAlign = Paint.Align.CENTER
            e.num(c, v.toLong(), x0 + i * gw + gw / 2f, y0 + rowH * 1.6f, e.pSmall)
            e.pSmall.textAlign = Paint.Align.LEFT
        }
    }

    if (!detailed) return

    // Near-black: 0,2,4,6,8,10,12,14 and near-white: 241..255 in steps of 2.
    val n = 8
    val dw = bw / n
    for (i in 0 until n) {
        val dark = i * 2
        drawRect(greyColor(dark), Offset(x0 + i * dw, y0 + rowH * 2), Size(dw, rowH))
        val light = 241 + i * 2
        drawRect(greyColor(light), Offset(x0 + i * dw, y0 + rowH * 3), Size(dw, rowH))
        e.pSmall.textAlign = Paint.Align.CENTER
        e.pSmall.color = 0xFF808080.toInt()
        e.num(c, dark.toLong(), x0 + i * dw + dw / 2f, y0 + rowH * 2 + u * 4f, e.pSmall)
        e.pSmall.color = 0xFF404040.toInt()
        e.num(c, light.toLong(), x0 + i * dw + dw / 2f, y0 + rowH * 3 + u * 4f, e.pSmall)
        e.pSmall.textAlign = Paint.Align.LEFT
    }
    e.pSmall.color = Amber.toArgb()
    e.label(c, "NEAR-BLACK 0..14 / NEAR-WHITE 241..255 - merged steps mean the link clipped levels",
        x0, y0 + bh + u * 4f, e.pSmall)
    e.pSmall.color = 0xFF93A3B4.toInt()
}

private fun greyColor(v: Int): Color {
    val f = v / 255f
    return Color(f, f, f, 1f)
}

// --------------------------------------------------------------------------- bouncing object

private fun DrawScope.drawBall(e: SceneEngine, ax: Float, ay: Float, aw: Float, ah: Float, u: Float) {
    drawRect(Color(0x14FFFFFF), Offset(ax, ay), Size(aw, ah), style = Stroke(width = 1f))
    val rMax = max(3f, u * 1.6f)
    val n = e.trailCount
    // Newest last so the head sits on top of its own trail.
    for (i in n - 1 downTo 0) {
        val a = 1f - i.toFloat() / n
        val x = ax + e.trailXAt(i) * aw
        val y = ay + e.trailYAt(i) * ah
        drawCircle(Accent.copy(alpha = a * a * 0.85f), rMax * (0.20f + 0.80f * a), Offset(x, y))
    }
    val bx = ax + e.ballX * aw
    val by = ay + e.ballY * ah
    drawCircle(Color.White, rMax * 1.5f, Offset(bx, by))
    drawCircle(Color(0xFFFF3D71), rMax * 0.55f, Offset(bx, by))
}

// --------------------------------------------------------------------------- overlays

private fun DrawScope.drawGridOverlay(w: Float, h: Float, u: Float) {
    val step = 10
    for (i in 1 until step) {
        val x = w * i / step
        val y = h * i / step
        drawLine(GridLine, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        drawLine(GridLine, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
    // 5% and 10% safe areas: what a receiver with overscan eats first.
    drawRect(Amber.copy(alpha = 0.55f), Offset(w * 0.05f, h * 0.05f), Size(w * 0.90f, h * 0.90f), style = Stroke(width = max(1f, u * 0.2f)))
    drawRect(Color(0xFFFF6B6B).copy(alpha = 0.55f), Offset(w * 0.10f, h * 0.10f), Size(w * 0.80f, h * 0.80f), style = Stroke(width = max(1f, u * 0.2f)))
}

/** Full-screen alignment pattern: grid, safe areas, corner markers, centre cross. */
private fun DrawScope.drawGridPattern(e: SceneEngine, c: NativeCanvas, w: Float, h: Float, u: Float) {
    drawGridOverlay(w, h, u)
    val len = u * 8f
    val t = max(2f, u * 0.5f)
    val corners = arrayOf(
        Offset(0f, 0f), Offset(w, 0f), Offset(0f, h), Offset(w, h),
    )
    corners.forEachIndexed { i, p ->
        val dx = if (i % 2 == 0) 1f else -1f
        val dy = if (i < 2) 1f else -1f
        drawLine(Color.White, p, Offset(p.x + dx * len, p.y), strokeWidth = t)
        drawLine(Color.White, p, Offset(p.x, p.y + dy * len), strokeWidth = t)
    }
    drawLine(Accent, Offset(w / 2f, h / 2f - len), Offset(w / 2f, h / 2f + len), strokeWidth = t)
    drawLine(Accent, Offset(w / 2f - len, h / 2f), Offset(w / 2f + len, h / 2f), strokeWidth = t)
    e.pSmall.textAlign = Paint.Align.CENTER
    e.pSmall.color = Amber.toArgb()
    e.label(c, "IF A CORNER MARKER IS MISSING THE RECEIVER CROPPED THE PICTURE", w / 2f, h * 0.62f, e.pSmall)
    e.pSmall.textAlign = Paint.Align.LEFT
    e.pSmall.color = 0xFF93A3B4.toInt()
}

/** Mostly black with an oversized frame number: the pattern to point a camera at. */
private fun DrawScope.drawLatencyPattern(e: SceneEngine, c: NativeCanvas, w: Float, h: Float, frame: Long) {
    e.pHuge.textAlign = Paint.Align.CENTER
    e.pHuge.color = 0xFFFFFFFF.toInt()
    e.num(c, frame, w / 2f, h * 0.55f, e.pHuge, minDigits = 6)
    e.pHuge.textAlign = Paint.Align.LEFT
    e.pSmall.textAlign = Paint.Align.CENTER
    e.label(
        c,
        if (e.latencyActive) "LATENCY SEQUENCE RUNNING - 1 FLASH PER SECOND" else "PRESS START LATENCY SEQUENCE ON THE PHONE",
        w / 2f,
        h * 0.68f,
        e.pSmall,
    )
    e.pSmall.textAlign = Paint.Align.LEFT
}

/**
 * A single-frame white flash.
 *
 * Held for exactly one drawn frame: if the receiver shows it at all, the receiver delivered that
 * frame. A multi-frame flash would be visible even through a 50% frame drop and would prove nothing.
 */
private fun DrawScope.drawLatencyFlash(e: SceneEngine, c: NativeCanvas, w: Float, h: Float, u: Float, frame: Long) {
    if (!e.latencyFlash) return
    drawRect(Color.White, Offset.Zero, Size(w, h))
    e.pHuge.color = 0xFF000000.toInt()
    e.pHuge.textAlign = Paint.Align.CENTER
    e.num(c, frame, w / 2f, h * 0.48f, e.pHuge, minDigits = 6)
    e.pMed.color = 0xFF000000.toInt()
    e.pMed.textAlign = Paint.Align.CENTER
    e.label(c, "SINGLE-FRAME FLASH", w / 2f, h * 0.60f, e.pMed)
    e.num(c, e.latencyIndex.toLong(), w / 2f, h * 0.60f + u * 6f, e.pMed)
    e.pMed.color = 0xFFE8EEF4.toInt()
    e.pMed.textAlign = Paint.Align.LEFT
    e.pHuge.color = 0xFFFFFFFF.toInt()
    e.pHuge.textAlign = Paint.Align.LEFT
}

/** Full-edge flash marking an audio pulse, so a video of the screen carries the audio cue too. */
private fun DrawScope.drawAudioPulse(e: SceneEngine, w: Float, h: Float, u: Float) {
    val age = e.pulseAgeNanos
    if (age < 0L || age > PULSE_VISIBLE_NANOS) return
    val a = 1f - age.toFloat() / PULSE_VISIBLE_NANOS
    val t = u * 3f
    val col = Amber.copy(alpha = a)
    drawRect(col, Offset.Zero, Size(w, t))
    drawRect(col, Offset(0f, h - t), Size(w, t))
    drawRect(col, Offset.Zero, Size(t, h))
    drawRect(col, Offset(w - t, 0f), Size(t, h))
}

private const val PULSE_VISIBLE_NANOS = 400_000_000L

/** Persistent crosshair at the last touch: it survives the finger lifting, on purpose. */
private fun DrawScope.drawTouchIndicator(e: SceneEngine, c: NativeCanvas, w: Float, h: Float, u: Float) {
    if (e.touchX < 0f) return
    val col = if (e.touchDown) Color(0xFFFF3D71) else Amber
    drawLine(col.copy(alpha = 0.45f), Offset(0f, e.touchY), Offset(w, e.touchY), strokeWidth = max(1f, u * 0.15f))
    drawLine(col.copy(alpha = 0.45f), Offset(e.touchX, 0f), Offset(e.touchX, h), strokeWidth = max(1f, u * 0.15f))
    drawCircle(col, u * 2.4f, Offset(e.touchX, e.touchY), style = Stroke(width = max(2f, u * 0.3f)))
    e.pSmall.color = col.toArgb()
    var x = e.label(c, "  ", e.touchX, e.touchY - u * 1.2f, e.pSmall)
    x = e.num(c, e.touchX.toLong(), x, e.touchY - u * 1.2f, e.pSmall)
    x = e.label(c, ",", x, e.touchY - u * 1.2f, e.pSmall)
    e.num(c, e.touchY.toLong(), x, e.touchY - u * 1.2f, e.pSmall)
    e.pSmall.color = 0xFF93A3B4.toInt()
}

private fun DrawScope.drawWatermark(e: SceneEngine, c: NativeCanvas, cx: Float, cy: Float) {
    e.pFaint.textAlign = Paint.Align.CENTER
    e.label(c, "TEST LAB", cx, cy, e.pFaint)
    e.pFaint.textAlign = Paint.Align.LEFT
}

/** Run identity in two opposite corners, so any crop of a photograph still carries it. */
private fun DrawScope.drawCorners(e: SceneEngine, c: NativeCanvas, w: Float, h: Float, u: Float, runTag: String) {
    e.pSmall.color = 0xFFB8C6D4.toInt()
    e.label(c, runTag, u * 1.5f, h - u * 1.4f, e.pSmall)
    e.pSmall.textAlign = Paint.Align.RIGHT
    e.label(c, runTag, w - u * 1.5f, h - u * 1.4f, e.pSmall)
    e.pSmall.textAlign = Paint.Align.LEFT
    e.pSmall.color = 0xFF93A3B4.toInt()
}
