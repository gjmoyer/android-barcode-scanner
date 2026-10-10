package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.domain.model.ScannerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [MsiVoteGate] — the consecutive-observation gate extracted
 * from [MsiNativeDecoder] so it runs on the JVM (the native library cannot
 * load under unit tests).
 */
@RunWith(JUnit4::class)
class MsiVoteGateTest {

    @Test
    fun requiredOne_emitsImmediately() {
        val gate = MsiVoteGate(1)
        assertTrue(gate.observe("1234567"))
        assertTrue(gate.observe("1234567"))
    }

    @Test
    fun requiredTwo_withholdsFirstRepeatEmits() {
        val gate = MsiVoteGate(2)
        assertFalse("first sighting must not emit", gate.observe("1234567"))
        assertTrue("repeat must emit", gate.observe("1234567"))
    }

    @Test
    fun confirm_resetsSoSameValueDoesNotReEmit() {
        val gate = MsiVoteGate(2)
        assertFalse(gate.observe("A"))
        assertTrue(gate.observe("A"))
        // Auto-reset on confirmation: the unchanged value starts over.
        assertFalse(gate.observe("A"))
        assertTrue(gate.observe("A"))
    }

    @Test
    fun differentValue_resetsCount() {
        val gate = MsiVoteGate(2)
        assertFalse(gate.observe("A"))
        assertFalse("new value restarts the count", gate.observe("B"))
        assertTrue(gate.observe("B"))
    }

    @Test
    fun reset_clearsPending() {
        val gate = MsiVoteGate(2)
        assertFalse(gate.observe("A"))
        gate.reset()
        assertFalse("reset must discard the banked sighting", gate.observe("A"))
        assertTrue(gate.observe("A"))
    }

    @Test
    fun requiredThree_needsThreeInARow() {
        val gate = MsiVoteGate(3)
        assertFalse(gate.observe("A"))
        assertFalse(gate.observe("A"))
        assertTrue(gate.observe("A"))
    }

    @Test
    fun separateGates_areIndependent() {
        // Regression: the ROI-crop path and the full-frame path shared one
        // gate, so a miss on one path reset the count the other was building.
        // Separate instances must not interact.
        val roi = MsiVoteGate(2)
        val full = MsiVoteGate(2)
        assertFalse(roi.observe("X"))
        assertFalse(full.observe("X"))
        full.reset() // a full-frame miss...
        assertTrue("ROI count must survive the other path's reset", roi.observe("X"))
    }

    @Test
    fun concurrentObserves_neverConfirmEarly() {
        // 20 threads hammer the same payload; exactly one confirmation per
        // pair of observations — total confirmations must equal floor(n/2).
        val gate = MsiVoteGate(2)
        val threads = 20
        val perThread = 50
        val confirmed = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) {
                    if (gate.observe("P")) confirmed.incrementAndGet()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("gate workers deadlocked", done.await(30, java.util.concurrent.TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(threads * perThread / 2, confirmed.get())
    }

    @Test
    fun policyRevalidation_doubleCheckImpliesSingle() {
        // Every Mod1010 codeword also validates as Mod10 by construction (the
        // second check IS Mod10 over payload+first check). The decoder must
        // re-validate reported digits under the configured policy — matching
        // on the reported scheme name would mis-route double-check labels.
        val payload = "1234567"
        val c1 = MsiChecksumValidator.mod10Check(payload)
        val c2 = MsiChecksumValidator.mod10Check(payload + c1)
        val full = "$payload$c1$c2"
        // Native reports the first validating scheme (mod10); both configs
        // must resolve exactly like the pre-native decoder did.
        val asSingle = MsiChecksumValidator.validate(full, ScannerConfig.MsiChecksumPolicy.MOD_10)
        assertTrue(asSingle.valid)
        assertEquals("$payload$c1", asSingle.payloadWithoutChecksum)
        val asDouble = MsiChecksumValidator.validate(full, ScannerConfig.MsiChecksumPolicy.MOD_10_10)
        assertTrue(asDouble.valid)
        assertEquals(payload, asDouble.payloadWithoutChecksum)
        // ...while a single-check label is rejected under a double policy.
        val single = "$payload$c1"
        assertTrue(MsiChecksumValidator.validate(single, ScannerConfig.MsiChecksumPolicy.MOD_10).valid)
        assertFalse(MsiChecksumValidator.validate(single, ScannerConfig.MsiChecksumPolicy.MOD_10_10).valid)
    }
}
