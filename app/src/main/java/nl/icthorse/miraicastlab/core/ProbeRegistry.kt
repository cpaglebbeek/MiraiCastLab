package nl.icthorse.miraicastlab.core

import android.content.Context
import nl.icthorse.miraicastlab.audio.AudioRouteProbe
import nl.icthorse.miraicastlab.auto.AndroidAutoProbe
import nl.icthorse.miraicastlab.display.DisplayProbe
import nl.icthorse.miraicastlab.display.MediaRouteProbe
import nl.icthorse.miraicastlab.input.InputDeviceProbe
import nl.icthorse.miraicastlab.net.ConnectivityProbe
import nl.icthorse.miraicastlab.net.WifiP2pProbe
import nl.icthorse.miraicastlab.projection.ProjectionCapabilityProbe
import nl.icthorse.miraicastlab.route.RouteProbe
import nl.icthorse.miraicastlab.samsung.DexProbe
import nl.icthorse.miraicastlab.samsung.SmartViewProbe
import nl.icthorse.miraicastlab.scan.CodecProbe
import nl.icthorse.miraicastlab.scan.DeviceProbe

/**
 * Every capability probe in the lab, in scan order (spec section 6).
 *
 * The registry is intentionally the only place that knows about all modules, so a module can be
 * developed and reasoned about in isolation.
 */
object ProbeRegistry {

    val all: List<Probe> = listOf(
        DeviceProbe,
        CodecProbe,
        DisplayProbe,
        MediaRouteProbe,
        WifiP2pProbe,
        ConnectivityProbe,
        AudioRouteProbe,
        InputDeviceProbe,
        ProjectionCapabilityProbe,
        SmartViewProbe,
        DexProbe,
        AndroidAutoProbe,
        RouteProbe,
    )

    fun byId(id: String): Probe? = all.firstOrNull { it.id == id }

    /**
     * Runs every probe, logging each observation as it arrives.
     * A probe that fails contributes an ERROR observation and the scan continues.
     */
    suspend fun runAll(
        context: Context,
        onProgress: (Probe, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Observation> {
        val out = mutableListOf<Observation>()
        all.forEachIndexed { index, probe ->
            onProgress(probe, index, all.size)
            val found = probe.safeObserve(context)
            SessionLogger.logAll(found)
            out += found
        }
        SessionLogger.log(
            LabCategory.DEVICE,
            "capability_scan_complete",
            LabStatus.CONFIRMED,
            mapOf("probes" to all.size.toString(), "observations" to out.size.toString()),
        )
        return out
    }
}
