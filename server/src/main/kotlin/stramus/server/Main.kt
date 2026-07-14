package stramus.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = ServerConfig.fromEnv()
    val db = openServerDatabase(config)

    embeddedServer(Netty, port = config.port) {
        stramusModule(config, db)
    }.start(wait = true)
}
