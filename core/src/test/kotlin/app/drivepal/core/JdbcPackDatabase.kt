package app.drivepal.core

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** [PackDatabase] on the JVM, through sqlite-jdbc: lets the pack's SQL be tested without Android. */
class JdbcPackDatabase(file: File) : PackDatabase, AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    override fun query(sql: String, args: List<Any>): List<Map<String, Any?>> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                val columns = (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) }
                buildList { while (rows.next()) add(columns.associateWith { rows.getObject(it) }) }
            }
        }

    override fun close() = connection.close()
}

/**
 * Copies a fixture pack that scripts/build_region_pack.py builds from `packs/kerala-fixture.osm` (see that file),
 * so the reader is tested against the builder's own output. [formatVersion] other than 1 rewrites the copy's
 * version, to stand for a pack from a later builder.
 */
fun copyFixturePack(file: File, formatVersion: Int = 1, fixtureName: String = "kerala-fixture") {
    val fixture = JdbcPackDatabase::class.java.getResourceAsStream("/packs/$fixtureName.drivepal")
        ?: error("No fixture pack: build it with scripts/build_region_pack.py (see packs/kerala-fixture.osm)")
    fixture.use { input -> file.outputStream().use { input.copyTo(it) } }
    if (formatVersion != 1) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.prepareStatement("UPDATE meta SET value = ? WHERE key = 'format_version'").use {
                it.setString(1, formatVersion.toString())
                it.executeUpdate()
            }
        }
    }
}
