package nl.icthorse.miraicastlab.route

import android.content.Context
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Read-only part of the route research: which display is which, and what each privilege tier would
 * add.
 *
 * The routes themselves are not probes - they launch activities and show Presentations, which is
 * state change, and a Probe must never do that. Only the two things that can be answered by looking
 * belong here, so a plain capability scan already carries them into the report.
 */
object RouteProbe : Probe {
    override val id = "route"
    override val title = "Display roles and privilege tiers"

    override suspend fun observe(context: Context): List<Observation> =
        DisplayRole.observations(context) + PrivilegeTiers.observations()
}
