package os.proximity.shared.storage

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FileStore] backed by the app's private storage.
 *
 * Files live in `filesDir/proximity`, which on Android is readable only by
 * this app and is excluded from backup (see `android:allowBackup="false"`)
 * — the audit log and trust decisions should not be silently copied off the
 * device.
 *
 * Writes are atomic: content goes to a temporary file which is then
 * renamed over the target. A crash mid-write therefore leaves either the
 * old file or the new one, never a truncated file that would parse as
 * valid-but-wrong. This matters most for the trust store, where a partial
 * write could otherwise drop someone's verified contacts.
 */
class AndroidFileStore(context: Context) : FileStore {

    private val directory: File =
        File(context.applicationContext.filesDir, "proximity").apply { mkdirs() }

    override suspend fun readText(name: String): String? = withContext(Dispatchers.IO) {
        val file = File(directory, name)
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    override suspend fun writeText(name: String, content: String) = withContext(Dispatchers.IO) {
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            // Rename can fail if the target exists on some filesystems.
            target.delete()
            temp.renameTo(target)
        }
        Unit
    }

    override suspend fun appendLine(name: String, line: String) = withContext(Dispatchers.IO) {
        File(directory, name).appendText(line + "\n")
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        File(directory, name).delete()
        Unit
    }
}
