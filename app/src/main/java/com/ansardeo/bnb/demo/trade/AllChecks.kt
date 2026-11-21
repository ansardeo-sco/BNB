package com.ansardeo.bnb.demo.trade

import android.content.Context
import com.ansardeo.bnb.demo.exec.HistoryEvents
import com.ansardeo.bnb.demo.exec.PktTime
import kotlin.coroutines.cancellation.CancellationException

enum class CheckStatus { OK, FAIL, INFO, NOT_RUN }

data class CheckResult(val ok: Boolean, val summary: String, val details: List<String> = emptyList())

/** One check of "Run all checks". [infoOnly] checks never fail the run and never stop it (their result is a report line only). */
class CheckStep(val name: String, val infoOnly: Boolean = false, val run: suspend () -> CheckResult)

data class CheckLine(val name: String, val status: CheckStatus, val summary: String, val details: List<String>)

class ChecksReport(val lines: List<CheckLine>, val atMillis: Long) {
    /** All required checks ran and passed (info-only lines do not count either way). */
    val passed: Boolean get() = lines.none { it.status == CheckStatus.FAIL } && lines.any { it.status == CheckStatus.OK }
    val firstFailure: CheckLine? get() = lines.firstOrNull { it.status == CheckStatus.FAIL }

    fun toText(): String = buildString {
        appendLine("BNB DEMO CHECKS (Binance Demo only, no position opened)")
        appendLine("Ran: " + PktTime.format(atMillis))
        appendLine("Result: " + (if (passed) "ALL REQUIRED CHECKS PASSED" else firstFailure?.let { "STOPPED at \"${it.name}\"" } ?: "INCOMPLETE"))
        lines.forEach { l ->
            appendLine(HistoryEvents.sanitize("[" + when (l.status) { CheckStatus.OK -> "OK"; CheckStatus.FAIL -> "FAIL"; CheckStatus.INFO -> "INFO"; CheckStatus.NOT_RUN -> "NOT RUN" } + "] " + l.name + " — " + l.summary))
            l.details.forEach { appendLine("    " + HistoryEvents.sanitize(it)) }
        }
    }
}

object AllChecks {
    /** Runs the steps in order. The first failing REQUIRED step stops the run (later required steps are "NOT RUN"); an info-only step is still run when everything before it passed. */
    suspend fun run(steps: List<CheckStep>, clock: () -> Long = { System.currentTimeMillis() }, onProgress: (String) -> Unit = {}): ChecksReport {
        val out = ArrayList<CheckLine>()
        var stopped = false
        for (s in steps) {
            if (stopped) { out += CheckLine(s.name, CheckStatus.NOT_RUN, "not run (an earlier check failed)", emptyList()); continue }
            onProgress("Running: ${s.name}…")
            val r = try { s.run() } catch (e: CancellationException) { throw e } catch (e: Exception) { CheckResult(false, "unexpected error (${e.javaClass.simpleName})") }
            when {
                r.ok -> out += CheckLine(s.name, CheckStatus.OK, r.summary, r.details)
                s.infoOnly -> out += CheckLine(s.name, CheckStatus.INFO, r.summary, r.details)
                else -> { out += CheckLine(s.name, CheckStatus.FAIL, r.summary, r.details); stopped = true }
            }
        }
        return ChecksReport(out, clock())
    }
}

/** Keeps the last combined report on the device (sanitized text only). */
object DemoAllChecksStore {
    private const val PREFS = "bnb_demo_all_checks"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun save(c: Context, r: ChecksReport) { prefs(c).edit().putString("text", r.toText()).putBoolean("passed", r.passed).putLong("at", r.atMillis).apply() }
    fun text(c: Context): String? = prefs(c).getString("text", null)
    fun at(c: Context): Long = prefs(c).getLong("at", 0L)
    fun passed(c: Context): Boolean = prefs(c).getBoolean("passed", false)
}
