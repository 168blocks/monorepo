package net.blocks168.e2e.bot

/**
 * Packet ids of Minecraft 26.3 (protocol 777) that the bot sends or reads, from the game's own data
 * generator report (`java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports`,
 * generated/reports/packets.json). A copy of that report is in the test resources; `PacketIdsTest`
 * checks these constants against it. Updating Minecraft: regenerate the report, replace the copy, fix
 * what the test reports (and check the formats of the parsed packets in Packets.kt).
 */
object PacketIds {
    const val PROTOCOL_VERSION = 777
    const val MINECRAFT_VERSION = "26.3"

    object Handshake {
        const val INTENTION = 0x00
        const val INTENT_LOGIN = 2
        /** login after a transfer packet (the server must accept transfers) */
        const val INTENT_TRANSFER = 3
    }

    object Login {
        // clientbound
        const val DISCONNECT = 0
        const val HELLO = 1
        const val LOGIN_FINISHED = 2
        const val LOGIN_COMPRESSION = 3
        const val CUSTOM_QUERY = 4
        const val COOKIE_REQUEST = 5
        // serverbound
        const val S_HELLO = 0
        const val S_CUSTOM_QUERY_ANSWER = 2
        const val S_LOGIN_ACKNOWLEDGED = 3
        const val S_COOKIE_RESPONSE = 4
    }

    object Configuration {
        // clientbound
        const val COOKIE_REQUEST = 0
        const val DISCONNECT = 2
        const val FINISH_CONFIGURATION = 3
        const val KEEP_ALIVE = 4
        const val PING = 5
        const val RESOURCE_PACK_PUSH = 9
        const val TRANSFER = 12
        const val SELECT_KNOWN_PACKS = 15
        const val CODE_OF_CONDUCT = 20
        // serverbound
        const val S_CLIENT_INFORMATION = 0
        const val S_COOKIE_RESPONSE = 1
        const val S_FINISH_CONFIGURATION = 3
        const val S_KEEP_ALIVE = 4
        const val S_PONG = 5
        const val S_RESOURCE_PACK = 6
        const val S_SELECT_KNOWN_PACKS = 7
        const val S_ACCEPT_CODE_OF_CONDUCT = 9
    }

    object Play {
        // clientbound
        const val ADD_ENTITY = 1
        const val CHUNK_BATCH_FINISHED = 11
        const val COOKIE_REQUEST = 21
        const val DISCONNECT = 32
        const val DISGUISED_CHAT = 33
        const val ENTITY_POSITION_SYNC = 35
        const val KEEP_ALIVE = 45
        const val LOGIN = 50
        const val MOVE_ENTITY_POS = 54
        const val MOVE_ENTITY_POS_ROT = 55
        const val PING = 62
        const val PLAYER_CHAT = 66
        const val PLAYER_POSITION = 73
        const val REMOVE_ENTITIES = 78
        const val RESOURCE_PACK_PUSH = 82
        const val RESPAWN = 84
        const val START_CONFIGURATION = 120
        const val SYSTEM_CHAT = 124
        const val TELEPORT_ENTITY = 128
        const val TRANSFER = 132
        // serverbound
        const val S_ACCEPT_TELEPORTATION = 0
        const val S_CHAT_COMMAND = 7
        const val S_CHAT = 9
        const val S_CHUNK_BATCH_RECEIVED = 11
        const val S_CLIENT_TICK_END = 13
        const val S_CONFIGURATION_ACKNOWLEDGED = 16
        const val S_COOKIE_RESPONSE = 21
        const val S_KEEP_ALIVE = 28
        const val S_MOVE_PLAYER_POS = 30
        const val S_MOVE_PLAYER_POS_ROT = 31
        const val S_PLAYER_LOADED = 44
        const val S_PONG = 45
        const val S_RESOURCE_PACK = 50
    }
}
