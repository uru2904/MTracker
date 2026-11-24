package com.uru2904.mtracker.backup

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.sheets.AuthStep
import com.uru2904.mtracker.sheets.SheetsSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Una copia guardada en Drive. */
data class BackupFile(val id: String, val name: String, val created: Instant, val size: Long)

/**
 * Copia de seguridad en el Drive del usuario (como WhatsApp, pero visible y con versiones):
 * un .zip con la base completa y los ajustes, en la carpeta "MTracker – Copias de seguridad".
 * Se sube sola unos minutos después de cada cambio. Se guardan las últimas 20 y una por día del último mes.
 */
object DriveBackup {
    const val FOLDER = "MTracker – Copias de seguridad"
    private const val DB = "mtracker.db"
    private val PREF_FILES = listOf("settings", "sheets", "device")
    private const val KEEP_RECENT = 20
    private const val KEEP_DAYS = 30L

    private fun prefs(c: Context) = c.getSharedPreferences("backup", Context.MODE_PRIVATE)
    fun lastBackup(c: Context): Long = prefs(c).getLong("last", 0)
    fun lastError(c: Context): String? = prefs(c).getString("error", null)

    // ---------- Programación ----------

    /** Hubo un cambio en la base: copia en 5 minutos (los cambios seguidos se juntan en una sola copia). */
    fun onChange(c: Context) {
        val ctx = c.applicationContext
        prefs(ctx).edit().putLong("dirty", System.currentTimeMillis()).apply()
        val wm = WorkManager.getInstance(ctx)
        val pending = runCatching { wm.getWorkInfosForUniqueWork("backup").get() }.getOrDefault(emptyList())
        if (pending.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork("backup", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** true si la base no tiene datos propios todavía (app recién instalada). */
    suspend fun isEmpty(c: Context): Boolean {
        val db = AppDatabase.get(c)
        return db.meals().between("0000", "9999").isEmpty() && db.workouts().all().isEmpty() && db.weights().all().isEmpty()
    }

    private suspend fun token(c: Context): String? =
        (runCatching { SheetsSync(c, AppDatabase.get(c)).authorize(c) }.getOrNull() as? AuthStep.Token)?.token

    // ---------- Copia ----------

    /** Arma el .zip y lo sube. Devuelve null si salió bien, o el error. */
    suspend fun backupNow(c: Context, tokenArg: String? = null): String? = withContext(Dispatchers.IO) {
        val ctx = c.applicationContext
        val started = System.currentTimeMillis()
        val result = runCatching {
            val token = tokenArg ?: token(ctx) ?: error("Falta el permiso de Google (abrí Comidas y deslizá hacia abajo)")
            val zip = snapshot(ctx)
            val folder = folderId(ctx, token)
            val name = "MTracker " + Instant.now().atZone(ZoneId.systemDefault()).toLocalDateTime()
                .toString().take(16).replace("T", " ").replace(":", ".") + ".zip"
            upload(token, folder, name, zip)
            prune(token, folder)
        }
        prefs(ctx).edit().apply {
            if (result.isSuccess) { putLong("last", started); remove("error") }
            else putString("error", result.exceptionOrNull()?.message ?: "Error desconocido")
        }.apply()
        result.exceptionOrNull()?.let { it.message ?: "Error desconocido" }
    }

    /** Copia consistente de la base (aunque esté en uso) más los ajustes, en un .zip. */
    private fun snapshot(c: Context): ByteArray {
        val db = AppDatabase.get(c).openHelper.writableDatabase
        val tmp = File(c.cacheDir, "snap.db").apply { delete() }
        runCatching { db.execSQL("VACUUM INTO '" + tmp.absolutePath.replace("'", "''") + "'") }.onFailure {
            db.query("PRAGMA wal_checkpoint(FULL)").close()
            c.getDatabasePath(DB).copyTo(tmp, overwrite = true)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(DB)); tmp.inputStream().use { it.copyTo(z) }; z.closeEntry()
            z.putNextEntry(ZipEntry("prefs.json")); z.write(dumpPrefs(c).toString().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("meta.json"))
            z.write(JSONObject().put("dbVersion", db.version).put("created", System.currentTimeMillis()).toString().toByteArray())
            z.closeEntry()
        }
        tmp.delete()
        return out.toByteArray()
    }

    private fun dumpPrefs(c: Context): JSONObject {
        val all = JSONObject()
        PREF_FILES.forEach { f ->
            val o = JSONObject()
            c.getSharedPreferences(f, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> null }
                if (t != null) o.put(k, JSONObject().put("t", t).put("v", v))
            }
            all.put(f, o)
        }
        return all
    }

    private fun loadPrefs(c: Context, all: JSONObject) {
        PREF_FILES.forEach { f ->
            val o = all.optJSONObject(f) ?: return@forEach
            val e = c.getSharedPreferences(f, Context.MODE_PRIVATE).edit().clear()
            o.keys().forEach { k ->
                val x = o.getJSONObject(k)
                when (x.getString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v")); "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v")); "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "s" -> e.putString(k, x.getString("v"))
                }
            }
            e.commit()
        }
    }

    // ---------- Restaurar ----------

    suspend fun list(c: Context, tokenArg: String? = null): List<BackupFile> = withContext(Dispatchers.IO) {
        val token = tokenArg ?: token(c) ?: error("Falta el permiso de Google")
        files(token, folderId(c, token))
    }

    /**
     * Reemplaza todos los datos del celular por los de la copia [id] y reinicia la app.
     * Antes sube una copia de lo actual, así se puede volver atrás.
     */
    suspend fun restore(c: Context, id: String, tokenArg: String? = null): String? = withContext(Dispatchers.IO) {
        val ctx = c.applicationContext
        runCatching {
            val token = tokenArg ?: token(ctx) ?: error("Falta el permiso de Google")
            if (!isEmpty(ctx)) backupNow(ctx, token)?.let { error("No pude guardar lo actual antes de restaurar: $it") }
            val bytes = request("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media", token)
            var dbBytes: ByteArray? = null; var prefsJson: JSONObject? = null
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name) {
                        DB -> dbBytes = z.readBytes()
                        "prefs.json" -> prefsJson = JSONObject(String(z.readBytes()))
                    }
                }
            }
            val data = dbBytes ?: error("La copia no tiene la base")
            AppDatabase.closeForRestore()
            val path = ctx.getDatabasePath(DB)
            listOf("", "-wal", "-shm", "-journal").forEach { File(path.path + it).delete() }
            path.writeBytes(data)
            prefsJson?.let { loadPrefs(ctx, it) }
            prefs(ctx).edit().putLong("restored", System.currentTimeMillis()).commit()
        }.exceptionOrNull()?.let { it.message ?: "Error desconocido" }
    }

    /** Reabre la app desde cero (después de restaurar). */
    fun restartApp(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        c.startActivity(i)
        Runtime.getRuntime().exit(0)
    }

    // ---------- Drive ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun folderId(c: Context, token: String): String {
        prefs(c).getString("folder", null)?.let { id ->
            val ok = runCatching { JSONObject(String(request("GET", "https://www.googleapis.com/drive/v3/files/$id?fields=trashed", token))).optBoolean("trashed") == false }
            if (ok.getOrDefault(false)) return id
        }
        val q = enc("name = '$FOLDER' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
        val found = JSONObject(String(request("GET", "https://www.googleapis.com/drive/v3/files?q=$q&orderBy=createdTime&fields=files(id)", token)))
            .getJSONArray("files").let { if (it.length() > 0) it.getJSONObject(0).getString("id") else null }
        val id = found ?: JSONObject(String(request("POST", "https://www.googleapis.com/drive/v3/files?fields=id", token,
            JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray(), "application/json")))
            .getString("id")
        prefs(c).edit().putString("folder", id).apply()
        return id
    }

    private fun files(token: String, folder: String): List<BackupFile> {
        val out = mutableListOf<BackupFile>()
        var page: String? = null
        do {
            val q = enc("'$folder' in parents and trashed = false")
            val url = "https://www.googleapis.com/drive/v3/files?q=$q&orderBy=createdTime%20desc&pageSize=200&fields=nextPageToken,files(id,name,createdTime,size)" +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val r = JSONObject(String(request("GET", url, token)))
            val arr: JSONArray = r.getJSONArray("files")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let {
                out += BackupFile(it.getString("id"), it.getString("name"), Instant.parse(it.getString("createdTime")), it.optString("size", "0").toLongOrNull() ?: 0)
            }
            page = r.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out.sortedByDescending { it.created }
    }

    private fun upload(token: String, folder: String, name: String, zip: ByteArray) {
        val boundary = "mtracker" + UUID.randomUUID()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder)).put("mimeType", "application/zip")
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/zip\r\n\r\n".toByteArray())
            write(zip)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        request("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", token, body, "multipart/related; boundary=$boundary")
    }

    /** Deja las últimas [KEEP_RECENT] y la más nueva de cada día del último mes; borra el resto. */
    private fun prune(token: String, folder: String) {
        val all = files(token, folder)
        val zone = ZoneId.systemDefault()
        val keep = all.take(KEEP_RECENT).map { it.id }.toMutableSet()
        val limit = Instant.now().minus(java.time.Duration.ofDays(KEEP_DAYS))
        all.filter { it.created.isAfter(limit) }.groupBy { it.created.atZone(zone).toLocalDate() }
            .values.forEach { day -> keep += day.maxBy { it.created }.id }
        all.filter { it.id !in keep }.forEach { f ->
            runCatching { request("DELETE", "https://www.googleapis.com/drive/v3/files/${f.id}", token) }
        }
    }

    private fun request(method: String, url: String, token: String, body: ByteArray? = null, type: String? = null): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", type ?: "application/octet-stream")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        conn.disconnect()
        if (code !in 200..299) error("Drive respondió $code: " + String(bytes).take(200))
        return bytes
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        com.uru2904.mtracker.data.AppSettings.init(c)
        // Una app recién instalada (vacía) no pisa las copias buenas.
        if (DriveBackup.isEmpty(c)) return Result.success()
        val err = DriveBackup.backupNow(c)
        return if (err == null || runAttemptCount >= 3) Result.success() else Result.retry()
    }
}
