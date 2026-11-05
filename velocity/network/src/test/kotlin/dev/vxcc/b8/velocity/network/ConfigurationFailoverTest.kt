package dev.vxcc.b8.velocity.network

import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.proxy.server.RegisteredServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertNull

class ConfigurationFailoverTest {
    private val lobby = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RegisteredServer::class.java)) { _, _, _ ->
        throw UnsupportedOperationException()
    } as RegisteredServer
    private val redirect = KickedFromServerEvent.RedirectPlayer.create(lobby)

    @Test
    fun aFailedConnectionDisconnectsInsteadOfRedirecting() {
        val r = ConfigurationFailover.decide(true, redirect, "survival", Component.text("configuration_error"))
        val text = PlainTextComponentSerializer.plainText().serialize(assertIs<KickedFromServerEvent.DisconnectPlayer>(r).reasonComponent)
        assertContains(text, "Could not connect you to survival: configuration_error")
        assertContains(text, "reconnect")
    }

    @Test
    fun playersAlreadyOnTheServerStillFailOver() =
        assertNull(ConfigurationFailover.decide(false, redirect, "survival", null))

    @Test
    fun otherResultsStay() {
        assertNull(ConfigurationFailover.decide(true, KickedFromServerEvent.Notify.create(Component.text("x")), "survival", null))
        assertNull(ConfigurationFailover.decide(true, KickedFromServerEvent.DisconnectPlayer.create(Component.text("x")), "survival", null))
    }
}
