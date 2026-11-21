package com.ansardeo.bnb.demo.exec

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DemoAccountViewTest {
    private val balance = """[{"asset":"BNB","balance":"1","availableBalance":"1","crossUnPnl":"0"},{"asset":"USDT","balance":"5000.00","availableBalance":"4700.25","crossUnPnl":"-12.5"}]"""
    private fun pos(sym: String, side: String, amt: String, upl: String, margin: String = "ISOLATED") =
        """{"symbol":"$sym","positionSide":"$side","positionAmt":"$amt","entryPrice":"100","markPrice":"101","unRealizedProfit":"$upl","marginType":"${margin.lowercase()}"}"""
    private fun risk(vararg rows: String) = rows.joinToString(",", "[", "]")
    private fun summary(vararg rows: String) = DemoAccountView.summarize(balance, risk(*rows))!!

    @Test fun parsesTheUsdtWalletAndNeverReadsCrossUnPnl() {
        val w = DemoAccountView.parseWallet(balance)!!
        assertEquals(BigDecimal("5000.00"), w.walletBalance); assertEquals(BigDecimal("4700.25"), w.available)
        assertNull(DemoAccountView.parseWallet("not json")); assertNull(DemoAccountView.parseWallet("""[{"asset":"BNB"}]"""))
        assertNull(DemoAccountView.parseWallet("""[{"asset":"USDT","availableBalance":"1"}]""")) // no wallet balance: unreadable, never zero
    }

    @Test fun noOpenPositionsMeansEquityEqualsWalletAndCrossUnPnlIsIgnored() {
        val s = summary(pos("BTCUSDT", "LONG", "0", "0"), pos("BTCUSDT", "SHORT", "0", "0"))
        assertEquals(0, BigDecimal.ZERO.compareTo(s.totalUnrealizedPnl!!)); assertEquals(0, BigDecimal("5000.00").compareTo(s.equity!!)); assertEquals(0, s.openPositions)
        assertEquals(0, BigDecimal("4700.25").compareTo(s.availableBalance))
    }

    @Test fun profitableIsolatedLong() {
        val s = summary(pos("BTCUSDT", "LONG", "0.010", "25.50"))
        assertEquals(0, BigDecimal("25.50").compareTo(s.totalUnrealizedPnl!!)); assertEquals(0, BigDecimal("5025.50").compareTo(s.equity!!)); assertEquals(1, s.openPositions)
    }

    @Test fun losingIsolatedLong() {
        val s = summary(pos("BTCUSDT", "LONG", "0.010", "-40.25"))
        assertEquals(0, BigDecimal("-40.25").compareTo(s.totalUnrealizedPnl!!)); assertEquals(0, BigDecimal("4959.75").compareTo(s.equity!!))
    }

    @Test fun profitableIsolatedShort() {
        val s = summary(pos("BTCUSDT", "SHORT", "-0.010", "12.00"))
        assertEquals(0, BigDecimal("12.00").compareTo(s.totalUnrealizedPnl!!)); assertEquals(0, BigDecimal("5012.00").compareTo(s.equity!!))
    }

    @Test fun losingIsolatedShort() {
        val s = summary(pos("BTCUSDT", "SHORT", "-0.010", "-7.75"))
        assertEquals(0, BigDecimal("-7.75").compareTo(s.totalUnrealizedPnl!!)); assertEquals(0, BigDecimal("4992.25").compareTo(s.equity!!))
    }

    @Test fun multipleLongAndShortPositionsAreAllSummed() {
        val s = summary(pos("BTCUSDT", "LONG", "0.01", "10"), pos("BTCUSDT", "SHORT", "-0.02", "-4"), pos("ETHUSDT", "LONG", "1", "3.5"), pos("ETHUSDT", "SHORT", "0", "0"), pos("XRPUSDT", "SHORT", "-100", "-1.25"))
        assertEquals(0, BigDecimal("8.25").compareTo(s.totalUnrealizedPnl!!)); assertEquals(0, BigDecimal("5008.25").compareTo(s.equity!!)); assertEquals(4, s.openPositions)
    }

    @Test fun crossAndIsolatedTogetherAreNotDoubleCounted() {
        // crossUnPnl in the balance row (-12.5) must NOT be added on top of the positionRisk total, which already contains the cross position.
        val s = summary(pos("BTCUSDT", "LONG", "0.01", "10", "ISOLATED"), pos("ETHUSDT", "LONG", "1", "-12.5", "CROSSED"))
        assertEquals(0, BigDecimal("-2.5").compareTo(s.totalUnrealizedPnl!!)); assertEquals(0, BigDecimal("4997.5").compareTo(s.equity!!))
    }

    @Test fun aRepeatedSymbolSideRowIsCountedOnce() {
        val s = summary(pos("BTCUSDT", "LONG", "0.01", "10"), pos("BTCUSDT", "LONG", "0.01", "10"))
        assertEquals(0, BigDecimal("10").compareTo(s.totalUnrealizedPnl!!)); assertEquals(1, s.openPositions)
    }

    @Test fun whenPositionRiskFailsEquityAndPnlAreUnavailableButTheWalletStaysCorrect() {
        val s = DemoAccountView.summarize(balance, null)!!
        assertNull(s.totalUnrealizedPnl); assertNull(s.equity); assertNull(s.openPositions)
        assertEquals(0, BigDecimal("5000.00").compareTo(s.walletBalance)); assertEquals(0, BigDecimal("4700.25").compareTo(s.availableBalance))
    }

    @Test fun anUnreadablePositionRiskAnswerIsAlsoUnavailableNotZero() {
        for (bad in listOf("not json", """{"code":-1}""", risk("""{"symbol":"BTCUSDT","positionSide":"LONG","positionAmt":"0.01"}"""))) {
            val s = DemoAccountView.summarize(balance, bad)!!
            assertNull(bad, s.equity); assertEquals(0, BigDecimal("5000.00").compareTo(s.walletBalance))
        }
    }

    @Test fun anUnreadableWalletGivesNoSummaryAtAll() {
        assertNull(DemoAccountView.summarize("oops", risk(pos("BTCUSDT", "LONG", "0.01", "10"))))
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
