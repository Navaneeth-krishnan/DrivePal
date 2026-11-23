package app.drivepal.core

private val MALAYALAM_SCRIPT = Regex("[\\u0D00-\\u0D7F]")

/**
 * A place's Malayalam name from its OpenStreetMap tags: `name:ml`, or else `name` when that is already written in
 * Malayalam script (as some places in Kerala are mapped). The Region pack builder follows the same rule.
 */
internal fun malayalamName(tag: (String) -> String?): String? =
    tag("name:ml") ?: tag("name")?.takeIf { MALAYALAM_SCRIPT.containsMatchIn(it) }
