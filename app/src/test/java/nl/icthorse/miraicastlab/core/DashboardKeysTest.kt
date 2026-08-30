package nl.icthorse.miraicastlab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard shows a fixed set of facts. A probe that stops emitting one of them must surface as
 * a visible gap rather than as a blank field the reader mistakes for "nothing to report".
 */
class DashboardKeysTest {

    @Test
    fun `missingFrom reports every key no probe produced`() {
        val produced = listOf(
            Observation.confirmed(DashboardKeys.MANUFACTURER, "samsung", LabCategory.DEVICE),
            Observation.confirmed(DashboardKeys.MODEL, "SM-F956B", LabCategory.DEVICE),
        )
        val missing = DashboardKeys.missingFrom(produced)
        assertEquals(DashboardKeys.ALL.size - 2, missing.size)
        assertTrue(missing.contains(DashboardKeys.ROOT))
        assertTrue(!missing.contains(DashboardKeys.MANUFACTURER))
    }

    @Test
    fun `nothing is missing when every key is produced`() {
        val all = DashboardKeys.ALL.map { Observation.confirmed(it, "x", LabCategory.DEVICE) }
        assertTrue(DashboardKeys.missingFrom(all).isEmpty())
    }

    @Test
    fun `a key produced with a silent status still counts as produced`() {
        // NOT_TESTED is a real answer and the dashboard shows its chip. It is the ABSENCE of any
        // observation that signals a broken probe, not a negative result.
        val all = DashboardKeys.ALL.map { Observation.notTested(it, LabCategory.DEVICE) }
        assertTrue(DashboardKeys.missingFrom(all).isEmpty())
    }

    @Test
    fun `key list has no duplicates`() {
        assertEquals(DashboardKeys.ALL.size, DashboardKeys.ALL.toSet().size)
    }
}
