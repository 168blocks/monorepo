package dev.vxcc.b8.vodozemac

import de.connect2x.trixnity.vodozemac.PickleKey
import de.connect2x.trixnity.vodozemac.megolm.GroupSession
import de.connect2x.trixnity.vodozemac.megolm.InboundGroupSession
import de.connect2x.trixnity.vodozemac.olm.Account
import de.connect2x.trixnity.vodozemac.olm.OlmMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Trixnity's vodozemac API, running on the rewritten bindings and the Endive-hosted wasm. */
class VodozemacWasmTest {
    @Test
    fun `olm session round trip`() {
        val alice = Account()
        val bob = Account()
        bob.generateOneTimeKeys(1)
        val oneTimeKey = bob.oneTimeKeys.values.single()
        assertEquals(1, bob.oneTimeKeys.size)

        val aliceSession = alice.createOutboundSession(bob.curve25519Key, oneTimeKey)
        val first = aliceSession.encrypt("hello bob")
        assertIs<OlmMessage.PreKey.Text>(first)

        val (plaintext, bobSession) = bob.createInboundSession(first)
        assertEquals("hello bob", plaintext)
        assertEquals(aliceSession.sessionId, bobSession.sessionId)

        val reply = bobSession.encrypt("hello alice")
        assertEquals("hello alice", aliceSession.decrypt(reply))
        repeat(20) { i ->
            assertEquals("ping $i", bobSession.decrypt(aliceSession.encrypt("ping $i")))
        }
    }

    @Test
    fun `account pickle and signatures`() {
        val account = Account()
        val key = PickleKey(ByteArray(32) { it.toByte() })
        val restored = Account.fromPickle(account.pickle(key), key)
        assertEquals(account.curve25519Key.base64, restored.curve25519Key.base64)
        assertEquals(account.ed25519Key.base64, restored.ed25519Key.base64)

        val signature = restored.sign("signed by the original")
        account.ed25519Key.verify("signed by the original", signature)
    }

    @Test
    fun `megolm group session`() {
        val outbound = GroupSession()
        val inbound = InboundGroupSession(outbound.sessionKey)
        assertEquals(outbound.sessionId, inbound.sessionId)
        repeat(10) { i ->
            val (text, index) = inbound.decrypt(outbound.encrypt("message $i"))
            assertEquals("message $i", text)
            assertEquals(i, index)
        }
        assertTrue(outbound.pickle().isNotEmpty())
    }
}
