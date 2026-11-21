package com.ansardeo.bnb.demo.trade

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AllChecksTest {
    private fun step(n: String, ok: Boolean, info: Boolean = false, log: MutableList<String>? = null) = CheckStep(n, info) { log?.add(n); CheckResult(ok, if (ok) "fine" else "broke") }

    @Test fun allPassInOrder() = runBlocking {
        val log = ArrayList<String>()
        val r = AllChecks.run(listOf(step("A", true, log = log), step("B", true, log = log), step("C", true, log = log)), { 5L })
        assertEquals(listOf("A", "B", "C"), log); assertTrue(r.passed); assertNull(r.firstFailure)
    }
    @Test fun stopsAtTheFirstFailAndLaterStepsAreNotRun() = runBlocking {
        val log = ArrayList<String>()
        val r = AllChecks.run(listOf(step("A", true, log = log), step("B", false, log = log), step("C", true, log = log), step("I", true, info = true, log = log)), { 5L })
        assertEquals(listOf("A", "B"), log); assertFalse(r.passed); assertEquals("B", r.firstFailure?.name)
        assertEquals(CheckStatus.NOT_RUN, r.lines[2].status); assertEquals(CheckStatus.NOT_RUN, r.lines[3].status)
        assertTrue(r.toText().contains("STOPPED at \"B\""))
    }
    @Test fun infoOnlyFailureIsAReportLineAndNeverFailsTheRun() = runBlocking {
        val r = AllChecks.run(listOf(step("A", true), step("Closing trailing (info)", false, info = true)), { 5L })
        assertTrue(r.passed); assertEquals(CheckStatus.INFO, r.lines[1].status); assertTrue(r.toText().contains("[INFO] Closing trailing (info)"))
    }
    @Test fun anExceptionBecomesAFailAndNeverLeaksItsMessage() = runBlocking {
        val r = AllChecks.run(listOf(CheckStep("A") { throw IllegalStateException("secret=abc") }), { 5L })
        assertFalse(r.passed); assertFalse(r.toText().contains("secret=abc")); assertTrue(r.toText().contains("IllegalStateException"))
    }
    @Test fun nothingRunMeansNotPassed() = runBlocking { assertFalse(AllChecks.run(emptyList(), { 1L }).passed) }
}
