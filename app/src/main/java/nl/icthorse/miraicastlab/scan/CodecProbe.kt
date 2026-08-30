package nl.icthorse.miraicastlab.scan

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Codecs & H.264 capability.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object CodecProbe : Probe {
    override val id = "codec"
    override val title = "Codecs & H.264 capability"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "codec.not_implemented",
            LabCategory.CODEC,
            "This probe has not been implemented yet.",
        ),
    )
}
