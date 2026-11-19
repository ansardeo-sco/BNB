package com.ansardeo.bnb.demo.exec

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DemoAccountViewTest {
    @Test fun parsesTheUsdtWallet() {
        val w = DemoAccountView.parseWallet("""[{"asset":"BNB","balance":"1","availableBalance":"1","crossUnPnl":"0"},{"asset":"USDT","balance":"4980.5","availableBalance":"4700.25","crossUnPnl":"-12.5"}]""")!!
        assertEquals(BigDecimal("4980.5"), w.walletBalance); assertEquals(BigDecimal("4700.25"), w.available); assertEquals(BigDecimal("4968.0"), w.equity)
        assertNull(DemoAccountView.parseWallet("not json")); assertNull(DemoAccountView.parseWallet("""[{"asset":"BNB"}]"""))
    }
    @Test fun netRealizedUsesOnlyTheRightCoinSideAndWindow() {
        val b = """[{"symbol":"BTCUSDT","positionSide":"LONG","time":1000,"realizedPnl":"0","commission":"0.4","commissionAsset":"USDT"},
            {"symbol":"BTCUSDT","positionSide":"LONG","time":2000,"realizedPnl":"10.5","commission":"0.5","commissionAsset":"USDT"},
            {"symbol":"BTCUSDT","positionSide":"SHORT","time":2000,"realizedPnl":"99","commission":"1","commissionAsset":"USDT"},
            {"symbol":"ETHUSDT","positionSide":"LONG","time":2000,"realizedPnl":"77","commission":"1","commissionAsset":"USDT"},
            {"symbol":"BTCUSDT","positionSide":"LONG","time":9000,"realizedPnl":"50","commission":"1","commissionAsset":"USDT"}]"""
        assertEquals(0, BigDecimal("9.6").compareTo(DemoAccountView.netRealized(b, "BTCUSDT", "LONG", 0, 3000)))
        assertNull(DemoAccountView.netRealized("x", "BTCUSDT", "LONG", 0, 1))
    }
}
