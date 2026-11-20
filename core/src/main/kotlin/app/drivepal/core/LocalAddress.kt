package app.drivepal.core

/**
 * Whether a host names a computer on the local network (a laptop running the AI server) rather than the internet.
 * Android 17 only lets an app reach those if the driver grants it the local network permission, and an API key must
 * never travel to a public address without encryption. Judged from the text alone: nothing is looked up.
 */
object LocalAddress {
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6_GROUP = Regex("[0-9a-f]{1,4}")

    fun isLocal(host: String): Boolean {
        val name = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return when {
            name.isEmpty() -> false
            name == "localhost" || name.endsWith(".local") -> true
            IPV4.matches(name) -> isLocalV4(name.split('.').map { it.toInt() })
            ':' in name -> parseV6(name)?.let(::isLocalV6) ?: false
            else -> false
        }
    }

    private fun isLocalV4(octets: List<Int>): Boolean {
        if (octets.any { it > 255 }) return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 100 && b in 64..127) // the last is the shared range VPNs such as Tailscale use
    }

    private fun isLocalV6(groups: IntArray): Boolean =
        groups.dropLast(1).all { it == 0 } && groups.last() == 1 || // ::1
            (groups[0] and 0xfe00) == 0xfc00 ||                      // fc00::/7, unique local
            (groups[0] and 0xffc0) == 0xfe80                         // fe80::/10, link-local

    /** Eight 16-bit groups, or null if [text] is not an IPv6 address. Handles one "::" and no embedded IPv4. */
    private fun parseV6(text: String): IntArray? {
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? =
            if (part.isEmpty()) emptyList() else part.split(':').map { g -> if (IPV6_GROUP.matches(g)) g.toInt(16) else return null }
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val missing = 8 - head.size - tail.size
        if (halves.size == 1 && missing != 0 || missing < 0) return null
        return (head + List(missing) { 0 } + tail).toIntArray()
    }
}
