package nl.icthorse.miraicastlab.audio

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Audio devices & routes.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object AudioRouteProbe : Probe {
    override val id = "audio"
    override val title = "Audio devices & routes"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "audio.not_implemented",
            LabCategory.AUDIO,
            "This probe has not been implemented yet.",
        ),
    )
}
