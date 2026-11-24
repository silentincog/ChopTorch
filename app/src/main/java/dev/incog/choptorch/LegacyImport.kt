package dev.incog.choptorch

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * Brings settings and the trained gesture model over from Chop Torch 3.x, which used the package
 * `com.kiro.choptorch`. Android treats `dev.incog.choptorch` as a different app, so each has its
 * own private storage; only root can read the old app's.
 *
 * The new app first creates its own destination files, then root copies the old contents into them
 * with `cat`. Writing into existing files keeps the new app's ownership and SELinux label, so no
 * `chown` or `restorecon` is needed.
 */
object LegacyImport {
    const val OLD_PACKAGE = "com.kiro.choptorch"
    const val PREFS_FILE = "chop.xml"
    val DATA_FILES = listOf("personal_model.bin", "learning_log.tsv", "auto_snippets.bin")

    class Result(val ok: Boolean, val message: String)

    /** Written after a successful import, read once after the restart to show the result. */
    private const val DONE_MARKER = "import_done"

    /** @return true once after a successful import */
    fun consumeDoneMarker(ctx: Context): Boolean {
        val f = File(ctx.applicationContext.createDeviceProtectedStorageContext().filesDir, DONE_MARKER)
        return f.exists().also { if (it) f.delete() }
    }

    fun oldAppInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(OLD_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Device-protected data directory of [pkg] for the same Android user as [ourDir]. */
    fun dataDirOf(ourDir: File, pkg: String): File = File(ourDir.parentFile, pkg)

    /**
     * The root script. Pure function, so it can be checked without a phone.
     * Paths come from Android, never from user input; they are quoted anyway.
     */
    fun script(oldDir: String, newDir: String): String {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val o = q(oldDir)
        val n = q(newDir)
        return buildString {
            appendLine("set -e")
            appendLine("O=$o; N=$n")
            appendLine("[ -d \"\$O\" ] || { echo 'old app data not found'; exit 3; }")
            appendLine("if [ -f \"\$O/shared_prefs/$PREFS_FILE\" ]; then cat \"\$O/shared_prefs/$PREFS_FILE\" > \"\$N/shared_prefs/$PREFS_FILE\"; echo 'copied settings'; fi")
            for (f in DATA_FILES) {
                appendLine("if [ -f \"\$O/files/$f\" ]; then cat \"\$O/files/$f\" > \"\$N/files/$f\"; echo 'copied $f'; fi")
            }
            // Stop the old app so both don't switch the torch; it stays stopped until opened again.
            appendLine("am force-stop $OLD_PACKAGE || true")
        }
    }

    /** Runs the import. Call off the main thread; stop the gesture service first. */
    fun run(ctx: Context): Result {
        if (!RootHelper.hasRoot()) return Result(false, "Root not granted. Allow Chop Torch in Magisk / KernelSU and try again.")
        val dp = ctx.applicationContext.createDeviceProtectedStorageContext()
        val newDir = dp.dataDir
        val oldDir = dataDirOf(newDir, OLD_PACKAGE)

        // Create the destination files ourselves, so they have our owner and SELinux label.
        Prefs.get(ctx).edit().putLong("import_started_at", System.currentTimeMillis()).commit()
        val files = dp.filesDir.apply { mkdirs() }
        DATA_FILES.forEach { File(files, it).createNewFile() }

        val (code, out) = RootHelper.runScript(script(oldDir.absolutePath, newDir.absolutePath))
        // Remove placeholders the old app didn't have.
        DATA_FILES.forEach { f -> File(files, f).takeIf { it.exists() && it.length() == 0L }?.delete() }
        return if (code == 0) {
            val copied = out.lines().count { it.startsWith("copied") }
            File(files, DONE_MARKER).writeText(copied.toString())
            Result(true, "Imported $copied item${if (copied == 1) "" else "s"} from Chop Torch 3.x.")
        } else {
            Result(false, "Import failed: " + out.trim().ifEmpty { "exit code $code" }.take(160))
        }
    }
}
