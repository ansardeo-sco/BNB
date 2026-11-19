package com.ansardeo.bnb.demo

import org.junit.Assert.*
import org.junit.Test

class BinanceSignerTest {
    @Test fun matchesRfc4231Vector() {
        // RFC 4231 test case 2 (independent of Binance).
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            BinanceSigner.hmacSha256Hex("Jefe", "what do ya want for nothing?"))
    }

    @Test fun signedQueryCoversExactlyTheSentQuery() {
        val q = BinanceSigner.signedQuery(listOf("symbol" to "BTCUSDT", "timestamp" to "1700000000000"), "testSecretKey123")
        assertEquals("symbol=BTCUSDT&timestamp=1700000000000&signature=5d371cc3a0e7f7842100f4641f4197e1498626750f4d53cf3994a5c8d6984992", q)
    }

    @Test fun valuesArePercentEncodedAndSigned() {
        val q = BinanceSigner.signedQuery(listOf("a" to "b c", "d" to "&=", "timestamp" to "5"), "testSecretKey123")
        assertEquals("a=b%20c&d=%26%3D&timestamp=5&signature=304478780dcae2e274ebe89c78f69e93c9ce8ee2a038cbf0a092353edd816687", q)
    }
}
