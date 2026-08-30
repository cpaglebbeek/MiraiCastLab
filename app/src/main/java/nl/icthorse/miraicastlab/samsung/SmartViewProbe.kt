package nl.icthorse.miraicastlab.samsung

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Samsung Smart View surface.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object SmartViewProbe : Probe {
    override val id = "smartview"
    override val title = "Samsung Smart View surface"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "smartview.not_implemented",
            LabCategory.SMART_VIEW,
            "This probe has not been implemented yet.",
        ),
    )
}
