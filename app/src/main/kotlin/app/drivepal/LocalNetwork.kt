package app.drivepal

import java.net.InetAddress
import java.net.URI

private val IP_LITERAL = Regex("[0-9.]+|[0-9a-fA-F:]+")

/**
 * True when the configured AI server is a computer on the local network (such as Ollama on a laptop).
 * Android 17 only lets an app reach those addresses if the user grants it the local network permission.
 */
fun aiServerIsOnLocalNetwork(): Boolean {
    val host = URI(BuildConfig.AI_BASE_URL).host ?: return false
    if (host == "localhost" || host.endsWith(".local")) return true
    return IP_LITERAL.matches(host) && InetAddress.getByName(host).let { it.isSiteLocalAddress || it.isLoopbackAddress }
}
