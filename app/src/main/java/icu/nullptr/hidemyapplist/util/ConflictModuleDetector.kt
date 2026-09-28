package icu.nullptr.hidemyapplist.util

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import icu.nullptr.hidemyapplist.common.Utils.conflictedModules
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manager-side diagnostics only: neither an installed APK nor an enabled
 * scope proves that an external module has hooked this system_server instance.
 * Never call from the injected backend or the main/UI thread.
 */
object ConflictModuleDetector {
    enum class State { INSTALLED_ONLY, NOT_ENABLED_FOR_SYSTEM, POTENTIAL_CONFLICT }

    data class Finding(val packageName: String, val label: String, val state: State)

    fun detect(context: Context): List<Finding> {
        val installed = conflictedModules.mapNotNull { pkg ->
            val info = try {
                context.packageManager.getApplicationInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            info?.let { pkg to context.packageManager.getApplicationLabel(it).toString() }
        }
        if (installed.isEmpty()) return emptyList()

        val states = readLsposedScope(context, installed.map { it.first })
        return installed.map { (pkg, label) ->
            Finding(pkg, label, states?.get(pkg) ?: State.INSTALLED_ONLY)
        }
    }

    private fun readLsposedScope(context: Context, packages: List<String>): Map<String, State>? {
        // Android framework SQLite handles both the database and its WAL. An
        // app cannot traverse /data/adb/lspd; use a short-lived private copy.
        // Unlike shell sqlite3, this works on devices without /system/bin/sqlite3.
        val directory = File(context.cacheDir, "lspd-snapshot-" + System.nanoTime())
        if (!directory.mkdir()) return null
        val dbFile = File(directory, "modules_config.db")
        try {
            if (!snapshotDatabase(context, dbFile)) return null
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // If the WAL changed while the files were copied, treat an
                // inconsistent snapshot as unknown rather than as "disabled".
                val consistent = db.rawQuery("PRAGMA quick_check", null).use {
                    it.moveToFirst() && it.getString(0) == "ok"
                }
                if (!consistent) return null
                val modern = hasColumns(db, "modules_state", "module_pkg_name", "enabled", "user_id") &&
                    hasColumns(db, "scope", "module_pkg_name", "app_pkg_name", "user_id")
                val legacy = hasColumns(db, "modules", "mid", "module_pkg_name", "enabled") &&
                    hasColumns(db, "scope", "mid", "app_pkg_name", "user_id")
                if (!modern && !legacy) return null

                return packages.associateWith { pkg ->
                    val enabledForSystem = if (modern) {
                        exists(db, """
                            SELECT 1 FROM modules_state ms JOIN scope s
                              ON s.module_pkg_name = ms.module_pkg_name AND s.user_id = ms.user_id
                            WHERE ms.module_pkg_name = ? AND ms.user_id = 0 AND ms.enabled = 1
                              AND s.app_pkg_name = 'system' LIMIT 1
                        """.trimIndent(), pkg)
                    } else {
                        exists(db, """
                            SELECT 1 FROM modules m JOIN scope s ON m.mid = s.mid
                            WHERE m.module_pkg_name = ? AND m.enabled = 1
                              AND s.user_id = 0 AND s.app_pkg_name = 'system' LIMIT 1
                        """.trimIndent(), pkg)
                    }
                    if (enabledForSystem) State.POTENTIAL_CONFLICT else State.NOT_ENABLED_FOR_SYSTEM
                }
            }
        } catch (_: Exception) {
            // Fail open for HMA functionality, but never falsely claim that
            // an unreadable framework database means the old module is disabled.
            return null
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun hasColumns(db: SQLiteDatabase, table: String, vararg names: String): Boolean {
        // All table names are fixed literals, not user-controlled SQL.
        val found = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(" + table + ")", null).use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) found.add(cursor.getString(nameColumn))
        }
        return names.all(found::contains)
    }

    private fun exists(db: SQLiteDatabase, query: String, pkg: String): Boolean =
        db.rawQuery(query, arrayOf(pkg)).use { it.moveToFirst() }

    private fun snapshotDatabase(context: Context, destination: File): Boolean {
        val source = "/data/adb/lspd/config/modules_config.db"
        val uid = context.applicationInfo.uid
        val copies = listOf("", "-wal", "-shm").joinToString("; ") { suffix ->
            val sourceFile = shellQuote(source + suffix)
            val targetFile = shellQuote(destination.path + suffix)
            if (suffix.isEmpty()) {
                "cp $sourceFile $targetFile || exit 1; chown $uid:$uid $targetFile || exit 1; chmod 0600 $targetFile || exit 1"
            } else {
                "if [ -f $sourceFile ]; then cp $sourceFile $targetFile || exit 1; " +
                    "chown $uid:$uid $targetFile || exit 1; chmod 0600 $targetFile || exit 1; fi"
            }
        }
        val process = try {
            ProcessBuilder("su", "-c", copies).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return false
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
