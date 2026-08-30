package nl.icthorse.miraicastlab.display

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Displays & topology.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object DisplayProbe : Probe {
    override val id = "display"
    override val title = "Displays & topology"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "display.not_implemented",
            LabCategory.DISPLAY,
            "This probe has not been implemented yet.",
        ),
    )
}
