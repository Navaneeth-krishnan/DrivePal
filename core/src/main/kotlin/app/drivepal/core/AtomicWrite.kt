package app.drivepal.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes [text] to [file] so that a crash or a full disk cannot leave half a file: to a temporary file first, on disk
 * before it replaces the old one. Failing to save is a small loss and never worth stopping the app for, so it is swallowed.
 */
internal fun writeAtomically(file: File, text: String) {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    try {
        FileOutputStream(temporary).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) temporary.delete()
        }
    } catch (e: IOException) {
        temporary.delete()
    }
}
