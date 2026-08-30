package nl.icthorse.miraicastlab.auto

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Android Auto connection.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object AndroidAutoProbe : Probe {
    override val id = "androidauto"
    override val title = "Android Auto connection"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "androidauto.not_implemented",
            LabCategory.ANDROID_AUTO,
            "This probe has not been implemented yet.",
        ),
    )
}
