package com.ansardeo.bnb.demo

import org.junit.Assert.*
import org.junit.Test

private class MapStore : KeyValueStore {
    val map = HashMap<String, String>()
    override fun read(key: String) = map[key]
    override fun write(key: String, value: String) { map[key] = value }
    override fun erase(key: String) { map.remove(key) }
}

/** Reversible fake: NOT encryption, only proves the repository never stores plain text itself. */
private class FakeCipher : SecretCipher {
    var keyDeleted = false; var broken = false
    override fun encrypt(plain: ByteArray) = byteArrayOf(0x7f) + plain.reversedArray().map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    override fun decrypt(data: ByteArray): ByteArray {
        if (broken) throw IllegalStateException("key lost")
        return data.copyOfRange(1, data.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray().reversedArray()
    }
    override fun deleteKey() { keyDeleted = true }
}

class DemoCredentialRepositoryTest {
    private val key = "AbCdEfGh1234567890AbCdEfGh1234567890AbCdEfGh1234567890AbCdEfGh12"
    private val secret = "ZyXwVuTs9876543210ZyXwVuTs9876543210ZyXwVuTs9876543210ZyXwVuTs98"
    private fun repo(store: MapStore = MapStore(), cipher: FakeCipher = FakeCipher()) = DemoCredentialRepository(store, cipher)

    @Test fun savesAndLoadsCredentials() {
        val r = repo(); assertNull(r.save(key, secret))
        val c = r.load()!!; assertEquals(key, c.apiKey); assertEquals(secret, c.secret)
        assertEquals(CredentialState.Saved("AbCd…Gh12"), r.state())
    }

    @Test fun nothingIsStoredInPlainText() {
        val s = MapStore(); repo(s).save(key, secret)
        val dump = s.map.values.joinToString("|")
        assertFalse(dump.contains(secret)); assertFalse(dump.contains(key))
        assertFalse(s.map.keys.any { it.contains("plain") })
        assertEquals("AbCd…Gh12", s.map["demo_api_key_hint"]) // only the masked hint is readable
    }

    @Test fun toStringNeverLeaksSecrets() {
        val c = DemoCredentials(key, secret)
        assertFalse(c.toString().contains(secret)); assertFalse(c.toString().contains(key))
    }

    @Test fun validationRejectsBadInputWithoutEchoingIt() {
        val r = repo()
        listOf("" to secret, key to "", "short" to secret, key to "short", "bad key with spaces 123456" to secret, key to "sec-ret_with-symbols_12345").forEach { (k, s) ->
            val err = r.save(k, s); assertNotNull(err)
            assertFalse(err!!.contains(k) && k.length > 5); assertFalse(err.contains(s) && s.length > 5)
        }
        assertEquals(CredentialState.None, r.state())
    }

    @Test fun whitespaceAroundPastedKeysIsTrimmed() {
        val r = repo(); assertNull(r.save("  $key\n", " $secret "))
        assertEquals(key, r.load()!!.apiKey)
    }

    @Test fun replaceOverwritesOldKeys() {
        val r = repo(); r.save(key, secret)
        val k2 = "QwErTyUiOp1234567890QwErTyUiOp1234567890QwErTyUiOp1234567890QwErTy"
        assertNull(r.save(k2, secret)); assertEquals(k2, r.load()!!.apiKey)
    }

    @Test fun deleteRemovesEverythingAndTheKeystoreKey() {
        val s = MapStore(); val ci = FakeCipher(); val r = DemoCredentialRepository(s, ci)
        r.save(key, secret); r.delete()
        assertTrue(s.map.isEmpty()); assertTrue(ci.keyDeleted); assertEquals(CredentialState.None, r.state()); assertNull(r.load()); assertFalse(r.hasCredentials())
    }

    @Test fun unreadableStoredKeysAreReportedNotCrashing() {
        val ci = FakeCipher(); val r = DemoCredentialRepository(MapStore(), ci)
        r.save(key, secret); ci.broken = true
        assertEquals(CredentialState.Unreadable, r.state()); assertNull(r.load())
    }

    @Test fun failedEncryptionLeavesNothingBehind() {
        val s = MapStore()
        val r = DemoCredentialRepository(s, object : SecretCipher {
            override fun encrypt(plain: ByteArray): ByteArray = throw IllegalStateException("keystore down")
            override fun decrypt(data: ByteArray) = data
            override fun deleteKey() {}
        })
        assertNotNull(r.save(key, secret)); assertTrue(s.map.isEmpty())
    }
}
