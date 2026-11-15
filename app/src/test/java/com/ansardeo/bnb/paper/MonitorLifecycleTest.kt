package com.ansardeo.bnb.paper

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM tests of the start / pause / resume / stop logic behind the foreground service.
 * The Android service itself (notification, startForeground, restart by the OS) is NOT covered here: that needs a device/emulator.
 */
class MonitorLifecycleTest {
    @Test fun firstStartLaunchesTheLoop() {
        val l = MonitorLifecycle()
        assertFalse(l.isRunning)
        assertTrue(l.requestStart())
        assertTrue(l.isRunning)
    }

    @Test fun duplicateStartsNeverLaunchASecondLoop() {
        val l = MonitorLifecycle()
        assertTrue(l.requestStart())
        repeat(10) { assertFalse("start #$it must be ignored", l.requestStart()) }
        assertTrue(l.isRunning)
    }

    @Test fun stopCancelsOnlyARunningLoopAndAllowsRestart() {
        val l = MonitorLifecycle()
        assertFalse(l.requestStop())
        assertTrue(l.requestStart())
        assertTrue(l.requestStop())
        assertFalse(l.isRunning)
        assertFalse(l.requestStop())
        assertTrue("a stopped monitor can be started again", l.requestStart())
    }

    @Test fun concurrentStartsLaunchExactlyOneLoop() {
        val l = MonitorLifecycle()
        val launched = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..16).map { Thread { if (l.requestStart()) launched.incrementAndGet() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, launched.get())
    }

    @Test fun notificationOffersPauseWhileRunningAndResumeWhilePaused() {
        val running = MonitorNotificationModel.build(paused = false)
        assertEquals("Pause", running.toggleActionLabel)
        assertTrue(running.toggleActionIsPause)
        assertTrue(running.title.contains("running"))
        val paused = MonitorNotificationModel.build(paused = true)
        assertEquals("Resume", paused.toggleActionLabel)
        assertFalse(paused.toggleActionIsPause)
        assertTrue(paused.title.contains("PAUSED"))
    }

    @Test fun notificationStatesThatNoRealOrdersExist() {
        listOf(false, true).forEach { assertTrue(MonitorNotificationModel.build(it).text.contains("No real orders", ignoreCase = true)) }
    }
}
