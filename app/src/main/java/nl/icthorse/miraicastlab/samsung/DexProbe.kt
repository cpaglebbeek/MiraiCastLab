package nl.icthorse.miraicastlab.samsung

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Samsung DeX surface.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object DexProbe : Probe {
    override val id = "dex"
    override val title = "Samsung DeX surface"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "dex.not_implemented",
            LabCategory.DEX,
            "This probe has not been implemented yet.",
        ),
    )
}
