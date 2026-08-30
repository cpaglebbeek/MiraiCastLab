package nl.icthorse.miraicastlab.net

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Connectivity & interfaces.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object ConnectivityProbe : Probe {
    override val id = "connectivity"
    override val title = "Connectivity & interfaces"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "connectivity.not_implemented",
            LabCategory.NETWORK,
            "This probe has not been implemented yet.",
        ),
    )
}
