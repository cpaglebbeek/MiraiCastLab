package nl.icthorse.miraicastlab.net

import android.content.Context
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Wi-Fi Direct / P2P.
 *
 * // STUB - replaced by the implementation agent for this module.
 */
object WifiP2pProbe : Probe {
    override val id = "wifip2p"
    override val title = "Wi-Fi Direct / P2P"

    override suspend fun observe(context: Context): List<Observation> = listOf(
        Observation.notTested(
            "wifip2p.not_implemented",
            LabCategory.NETWORK,
            "This probe has not been implemented yet.",
        ),
    )
}
