package nl.icthorse.miraicastlab.display

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * MediaRouter routes.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object MediaRouteProbe : Probe {
    override val id = "mediaroute"
    override val title = "MediaRouter routes"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "mediaroute.not_implemented",
            LabCategory.DISPLAY,
            "This probe has not been implemented yet.",
        ),
    )
}
