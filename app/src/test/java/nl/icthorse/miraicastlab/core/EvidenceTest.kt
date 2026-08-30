package nl.icthorse.miraicastlab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the project's central invariant (spec section 20).
 *
 * If these fail, the report can no longer be trusted to distinguish "we did not test it" from
 * "it does not work" - which is the one thing this application exists to get right.
 */
class EvidenceTest {

    @Test
    fun `only confirmed and observed are positive claims`() {
        assertTrue(LabStatus.CONFIRMED.isPositiveClaim)
        assertTrue(LabStatus.OBSERVED.isPositiveClaim)
        assertFalse(LabStatus.INFERRED.isPositiveClaim)
        assertFalse(LabStatus.UNSUPPORTED.isPositiveClaim)
        assertFalse(LabStatus.NOT_TESTED.isPositiveClaim)
        assertFalse(LabStatus.ERROR.isPositiveClaim)
    }

    @Test
    fun `not tested and error permit no conclusion`() {
        assertTrue(LabStatus.NOT_TESTED.isSilent)
        assertTrue(LabStatus.ERROR.isSilent)
        // UNSUPPORTED is NOT silent: the platform answered, and that answer is usable evidence.
        assertFalse(LabStatus.UNSUPPORTED.isSilent)
        assertFalse(LabStatus.CONFIRMED.isSilent)
        assertFalse(LabStatus.OBSERVED.isSilent)
        assertFalse(LabStatus.INFERRED.isSilent)
    }

    @Test
    fun `not tested and unsupported are distinct constants`() {
        // Deliberately trivial. It exists so that a future refactor collapsing the two - the exact
        // mistake spec section 20 forbids - fails a test instead of shipping.
        assertFalse(LabStatus.NOT_TESTED == LabStatus.UNSUPPORTED)
        assertEquals(6, LabStatus.values().size)
    }

    @Test
    fun `every status explains itself`() {
        LabStatus.values().forEach {
            assertTrue("empty explanation for " + it.name, it.explanation.length > 10)
            assertEquals(it.name, it.label)
        }
    }

    @Test
    fun `companion helpers produce the status they claim`() {
        assertEquals(LabStatus.CONFIRMED, Observation.confirmed("k", 1, LabCategory.DEVICE).status)
        assertEquals(LabStatus.OBSERVED, Observation.observed("k", 1, LabCategory.DEVICE).status)
        assertEquals(LabStatus.UNSUPPORTED, Observation.unsupported("k", LabCategory.DEVICE).status)
        assertEquals(LabStatus.NOT_TESTED, Observation.notTested("k", LabCategory.DEVICE).status)
    }

    @Test
    fun `confirmed renders null as the string null rather than dropping the observation`() {
        // A probe that reads a platform value must still report when that value was null; silently
        // omitting it would make an absent field indistinguishable from an unrun probe.
        assertEquals("null", Observation.confirmed("k", null, LabCategory.DEVICE).value)
    }

    @Test
    fun `error observation preserves the throwable type and message and stays silent`() {
        val o = Observation.error("k", IllegalStateException("boom"), LabCategory.DISPLAY)
        assertEquals(LabStatus.ERROR, o.status)
        assertTrue(o.value.contains("IllegalStateException"))
        assertTrue(o.value.contains("boom"))
        assertTrue(o.status.isSilent)
        assertTrue(o.note!!.contains("untested"))
    }

    @Test
    fun `error observation survives a throwable with no message`() {
        val o = Observation.error("k", RuntimeException(), LabCategory.DISPLAY)
        assertTrue(o.value.contains("no message"))
    }
}
