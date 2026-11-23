package app.drivepal

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.drivepal.core.PackDatabase
import java.io.File

/**
 * A Region pack's SQLite file, opened read-only with Android's own SQLite. The queries live in :core (`SqlRegionPack`).
 * Android binds every argument as text; SQLite turns it back into a number when it is compared with a number column.
 */
class AndroidPackDatabase(file: File) : PackDatabase, AutoCloseable {
    private val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    override fun query(sql: String, args: List<Any>): List<Map<String, Any?>> =
        database.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.columnNames.withIndex().associate { (index, name) -> name to cursor.valueAt(index) })
                }
            }
        }

    override fun close() = database.close()

    private fun Cursor.valueAt(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_STRING -> getString(index)
        Cursor.FIELD_TYPE_NULL -> null
        else -> getBlob(index)
    }
}
