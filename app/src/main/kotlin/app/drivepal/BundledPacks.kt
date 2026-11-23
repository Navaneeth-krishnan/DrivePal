package app.drivepal

import android.content.Context
import java.io.File

/**
 * The Region packs that come with the app, in its assets under `packs/`. SQLite cannot open a file inside the APK, so
 * each pack is copied out to private storage (not backed up), and copied again only when the app brings a different
 * one: when its manifest no longer matches the copy's. Copies of packs the app no longer brings are removed.
 */
object BundledPacks {
    private const val ASSET_FOLDER = "packs"
    private const val TARGET_FOLDER = "bundled-packs"

    /** Makes the copies current and returns the folder that holds them. */
    fun unpack(context: Context): File {
        val target = File(context.noBackupFilesDir, TARGET_FOLDER).apply { mkdirs() }
        val assets = context.assets.list(ASSET_FOLDER).orEmpty().toSet()
        val manifests = assets.filter { it.endsWith(".json") }
        manifests.forEach { manifest -> copyIfChanged(context, manifest, target) }
        val packs = manifests.flatMap { listOf(it, it.removeSuffix(".json") + ".drivepal") }.toSet()
        target.listFiles()?.filter { it.name !in packs }?.forEach { it.delete() }
        return target
    }

    private fun copyIfChanged(context: Context, manifest: String, target: File) {
        val bundledManifest = context.assets.open("$ASSET_FOLDER/$manifest").use { it.readBytes() }
        val copiedManifest = File(target, manifest)
        if (copiedManifest.isFile && copiedManifest.readBytes().contentEquals(bundledManifest)) return
        val pack = manifest.removeSuffix(".json") + ".drivepal"
        // The manifest goes last: a copy cut short (the app killed mid-way) never matches, so it is made again.
        copiedManifest.delete()
        val partial = File(target, "$pack.partial")
        context.assets.open("$ASSET_FOLDER/$pack").use { input -> partial.outputStream().use { input.copyTo(it) } }
        partial.renameTo(File(target, pack))
        copiedManifest.writeBytes(bundledManifest)
    }
}
