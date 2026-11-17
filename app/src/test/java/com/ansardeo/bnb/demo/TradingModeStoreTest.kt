package com.ansardeo.bnb.demo

import org.junit.Assert.*
import org.junit.Test

private class MapStore2 : KeyValueStore {
    private val m = HashMap<String, String>()
    override fun read(key: String): String? = m[key]
    override fun write(key: String, value: String) { m[key] = value }
    override fun erase(key: String) { m.remove(key) }
}

class TradingModeStoreTest {
    private fun prefs(hasKeys: Boolean = false, probeOk: Boolean = false) = TradingModePrefs(MapStore2(), { hasKeys }, { probeOk })

    @Test fun defaultsToVirtual() { assertEquals(TradingMode.VIRTUAL, prefs().get()) }

    @Test fun switchingToDemoIsRefusedWithoutKeysOrAProbe() {
        val p = prefs(hasKeys = false, probeOk = false)
        assertNotNull(p.trySet(TradingMode.DEMO)); assertEquals(TradingMode.VIRTUAL, p.get())
    }

    @Test fun keysAloneAreNotEnough() {
        val p = prefs(hasKeys = true, probeOk = false)
        assertNotNull(p.trySet(TradingMode.DEMO)); assertEquals(TradingMode.VIRTUAL, p.get())
    }

    @Test fun probeAloneIsNotEnough() {
        val p = prefs(hasKeys = false, probeOk = true)
        assertNotNull(p.trySet(TradingMode.DEMO)); assertEquals(TradingMode.VIRTUAL, p.get())
    }

    @Test fun bothKeysAndAPassedProbeAllowTheSwitch() {
        val p = prefs(hasKeys = true, probeOk = true)
        assertNull(p.trySet(TradingMode.DEMO)); assertEquals(TradingMode.DEMO, p.get())
    }

    @Test fun switchingBackToVirtualIsAlwaysAllowed() {
        val p = prefs(hasKeys = true, probeOk = true)
        p.trySet(TradingMode.DEMO)
        assertNull(p.trySet(TradingMode.VIRTUAL)); assertEquals(TradingMode.VIRTUAL, p.get())
    }
}
