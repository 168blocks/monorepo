package dev.vxcc.b8.staticguard.fixtures

/** The same patterns as Kotlin compiles them: object properties and top-level properties. */
object KotlinMod {
    var server: Server? = null
    val players = mutableListOf<String>()

    fun join(name: String) {
        players.add(name)
    }
}

lateinit var topLevelServer: Server
