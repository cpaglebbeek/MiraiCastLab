package nl.icthorse.miraicastlab.input

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Input devices.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object InputDeviceProbe : Probe {
    override val id = "input"
    override val title = "Input devices"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "input.not_implemented",
            LabCategory.INPUT,
            "This probe has not been implemented yet.",
        ),
    )
}
