package nl.icthorse.miraicastlab.scan

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Device & platform.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object DeviceProbe : Probe {
    override val id = "device"
    override val title = "Device & platform"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "device.not_implemented",
            LabCategory.DEVICE,
            "This probe has not been implemented yet.",
        ),
    )
}
