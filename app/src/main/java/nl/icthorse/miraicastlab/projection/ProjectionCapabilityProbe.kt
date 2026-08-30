package nl.icthorse.miraicastlab.projection

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * MediaProjection capability.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object ProjectionCapabilityProbe : Probe {
    override val id = "projection"
    override val title = "MediaProjection capability"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "projection.not_implemented",
            LabCategory.MEDIAPROJECTION,
            "This probe has not been implemented yet.",
        ),
    )
}
