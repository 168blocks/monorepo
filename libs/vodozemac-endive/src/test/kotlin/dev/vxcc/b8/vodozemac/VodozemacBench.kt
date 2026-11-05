package dev.vxcc.b8.vodozemac

import de.connect2x.trixnity.vodozemac.megolm.GroupSession
import de.connect2x.trixnity.vodozemac.megolm.InboundGroupSession
import de.connect2x.trixnity.vodozemac.olm.Account
import de.connect2x.trixnity.vodozemac.olm.OlmMessage

private fun <T> time(label: String, n: Int, block: () -> T) {
    repeat(n) { block() } // warm up
    val t0 = System.nanoTime()
    repeat(n) { block() }
    val us = (System.nanoTime() - t0) / 1000.0 / n
    println("%-40s %10.1f us/op".format(label, us))
}

/**
 * Rough timings of the vodozemac operations the Matrix bot uses: `./dev ./gradlew :libs:vodozemac-endive:bench`.
 * Run the same code on Trixnity's JNI library to compare (docs/matrix-bot.md has numbers for both).
 */
fun main() {
    val t0 = System.nanoTime()
    Account()
    println("first call (load + init): %.0f ms".format((System.nanoTime() - t0) / 1e6))
    time("Account() (keygen)", 500) { Account() }
    val bob = Account()
    time("generateOneTimeKeys(50)", 50) { bob.generateOneTimeKeys(50) }
    val alice = Account()
    bob.generateOneTimeKeys(1)
    val otk = bob.oneTimeKeys.values.first()
    time("olm createOutboundSession (3x DH)", 500) { alice.createOutboundSession(bob.curve25519Key, otk) }
    val s = alice.createOutboundSession(bob.curve25519Key, otk)
    val (_, bs) = bob.createInboundSession(s.encrypt("x") as OlmMessage.PreKey.Text)
    val msg = "a".repeat(1000)
    time("olm encrypt+decrypt 1 KB (ratchet)", 2000) { bs.decrypt(s.encrypt(msg)); s.decrypt(bs.encrypt(msg)) }
    val g = GroupSession()
    val ig = InboundGroupSession(g.sessionKey)
    time("megolm encrypt+decrypt 1 KB ", 5000) { ig.decrypt(g.encrypt(msg)) }
    time("ed25519 sign 1 KB", 2000) { alice.sign(msg) }
    val sig = alice.sign(msg)
    time("ed25519 verify 1 KB", 2000) { alice.ed25519Key.verify(msg, sig) }
    time("account pickle", 2000) { alice.pickle() }
}
