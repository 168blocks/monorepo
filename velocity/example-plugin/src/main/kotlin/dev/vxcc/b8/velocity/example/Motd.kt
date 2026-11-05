package dev.vxcc.b8.velocity.example

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** Server list text; the end-to-end tests look for [MOTD_MARKER] in it. */
const val MOTD_MARKER = "168blocks"

fun motd(online: Int): Component = Component.text(MOTD_MARKER, NamedTextColor.GOLD)
    .append(Component.text(" — $online online", NamedTextColor.GRAY))
