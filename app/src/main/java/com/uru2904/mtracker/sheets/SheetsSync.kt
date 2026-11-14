package com.uru2904.mtracker.sheets

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.MealEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Resultado de pedir acceso: token listo o una pantalla de Google que el usuario tiene que aceptar. */
sealed class AuthStep {
    data class Token(val token: String) : AuthStep()
    data class NeedsConsent(val intent: PendingIntent) : AuthStep()
}

/**
 * Sincronización en dos direcciones con la pestaña Comidas de la planilla.
 * La planilla es la fuente única: primero se suben los cambios pendientes de la app
 * (altas y bajas, identificadas por la columna J = ID) y después se trae todo.
 */
class SheetsSync(private val context: Context, private val db: AppDatabase) {

    private val prefs = context.getSharedPreferences("sheets", Context.MODE_PRIVATE)

    var sheetId: String?
        get() = prefs.getString("sheet_id", null)
        set(v) { prefs.edit().putString("sheet_id", v).apply() }

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        private set(v) { prefs.edit().putLong("last_sync", v).apply() }

    private val request = AuthorizationRequest.builder()
        // drive.file: la app solo ve los archivos que ella misma creó (su planilla), nada más del Drive.
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
        .build()

    suspend fun authorize(activity: Activity): AuthStep {
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        val pending = result.pendingIntent
        return if (result.hasResolution() && pending != null) AuthStep.NeedsConsent(pending)
        else AuthStep.Token(result.accessToken ?: error("Google no devolvió acceso"))
    }

    fun tokenFromConsent(data: Intent?): String =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
            ?: error("No se otorgó el permiso")

    /** true si la planilla actual la creó la app (acceso limitado a ese archivo). */
    private var appOwned: Boolean
        get() = prefs.getBoolean("app_owned", false)
        set(v) { prefs.edit().putBoolean("app_owned", v).apply() }

    val sheetUrl: String? get() = sheetId?.let { "https://docs.google.com/spreadsheets/d/$it" }

    /** Crea la planilla "MTracker – Datos" con la pestaña Comidas y su encabezado. */
    private fun createSheet(token: String): String {
        val body = JSONObject()
            .put("properties", JSONObject().put("title", "MTracker – Datos"))
            .put("sheets", JSONArray().put(JSONObject().put("properties", JSONObject().put("title", "Comidas"))))
        val id = send("POST", "https://sheets.googleapis.com/v4/spreadsheets", token, body).getString("spreadsheetId")
        val header = JSONArray().put(JSONArray()
            .put("Fecha").put("Hora").put("Comida").put("Descripción").put("Calorías (kcal)")
            .put("Proteína (g)").put("Carbohidratos (g)").put("Grasas (g)").put("Confianza estimación").put("ID"))
        send("PUT", "https://sheets.googleapis.com/v4/spreadsheets/$id/values/" + enc("Comidas!A1:J1") + "?valueInputOption=RAW",
            token, JSONObject().put("values", header))
        return id
    }

    /** Reescribe la pestaña Gym con todas las sesiones importadas de Hevy (una fila por sesión). */
    suspend fun writeGym(token: String) {
        val id = sheetId ?: return
        val sessions = db.workouts().all()
        if (sessions.isEmpty()) return
        val sets = db.workouts().allSets().groupBy { it.sessionId }
        val zone = ZoneId.systemDefault()
        val values = JSONArray()
        sessions.forEach { w ->
            val t = Instant.ofEpochMilli(w.start).atZone(zone)
            val ss = sets[w.id].orEmpty()
            val volume = ss.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
            values.put(JSONArray()
                .put(t.toLocalDate().toString()).put(DateTimeFormatter.ofPattern("H:mm").format(t))
                .put(w.routine).put(w.durationMin).put(ss.map { it.exercise }.distinct().size)
                .put(ss.size).put(Math.round(volume)))
        }
        withContext(Dispatchers.IO) {
            val header = JSONArray().put(JSONArray().put("Fecha").put("Hora").put("Rutina").put("Duración (min)")
                .put("Ejercicios").put("Series").put("Volumen (kg)"))
            send("POST", "https://sheets.googleapis.com/v4/spreadsheets/$id/values/" + enc("Gym!A:G") + ":clear", token, JSONObject())
            send("PUT", "https://sheets.googleapis.com/v4/spreadsheets/$id/values/" + enc("Gym!A1") + "?valueInputOption=RAW",
                token, JSONObject().put("values", JSONArray().put(header.getJSONArray(0)).let { arr ->
                    for (i in 0 until values.length()) arr.put(values.getJSONArray(i)); arr }))
        }
    }

    /** Escribe (o actualiza por fecha) los resúmenes diarios en la pestaña Diario. */
    suspend fun writeDaily(token: String, days: List<com.uru2904.mtracker.health.DailySummary>) {
        val id = sheetId ?: return
        if (days.isEmpty()) return
        withContext(Dispatchers.IO) {
            val existing = get(id, "Diario!A:A", token).map { it.firstOrNull().orEmpty() }
            val now = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            fun row(d: com.uru2904.mtracker.health.DailySummary) = JSONArray()
                .put(d.date.toString()).put(d.steps).put(round1(d.sleepHours)).put(round1(d.napHours))
                .put(d.weightKg?.let { round1(it) } ?: "").put(d.restingHr ?: "").put(d.exercises).put(now)
            val updates = JSONArray()
            val appends = JSONArray()
            days.sortedBy { it.date }.forEach { d ->
                val idx = existing.indexOf(d.date.toString())
                if (idx > 0) updates.put(JSONObject().put("range", "Diario!A${idx + 1}:H${idx + 1}").put("values", JSONArray().put(row(d))))
                else appends.put(row(d))
            }
            if (updates.length() > 0) send("POST", "https://sheets.googleapis.com/v4/spreadsheets/$id/values:batchUpdate", token,
                JSONObject().put("valueInputOption", "RAW").put("data", updates))
            if (appends.length() > 0) send("POST",
                "https://sheets.googleapis.com/v4/spreadsheets/$id/values/" + enc("Diario!A:H") + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
                token, JSONObject().put("values", appends))
        }
    }

    /** Sube lo pendiente, trae la planilla y devuelve cuántas comidas hay. */
    suspend fun sync(token: String): Int = lock.withLock {
        if (!appOwned || sheetId == null) {
            // Primera vez (o venía de la planilla vieja): la app crea la suya y le sube todo lo que tiene.
            sheetId = withContext(Dispatchers.IO) { createSheet(token) }
            appOwned = true
            db.meals().markAllForUpload()
        }
        val id = sheetId!!
        withContext(Dispatchers.IO) { pushPending(id, token) }
        val rows = withContext(Dispatchers.IO) { get(id, "Comidas!A2:J", token) }
        val missing = fillMissingIds(id, token, rows)
        val meals = SheetMealParser.parse(rows.mapIndexed { i, r -> missing[i]?.let { padTo(r, 10).toMutableList().apply { set(9, it) } } ?: r })
        db.meals().replaceSynced(meals)
        lastSync = System.currentTimeMillis()
        meals.size
    }

    /** Filas cargadas sin ID (por ejemplo, a mano en la planilla): se les asigna uno. */
    private suspend fun fillMissingIds(id: String, token: String, rows: List<List<String>>): Map<Int, String> {
        val missing = rows.withIndex()
            .filter { (_, r) -> r.getOrNull(0)?.isNotBlank() == true && r.getOrNull(9).isNullOrBlank() }
            .associate { (i, _) -> i to UUID.randomUUID().toString() }
        if (missing.isEmpty()) return missing
        val data = JSONArray()
        missing.forEach { (i, uuid) ->
            data.put(JSONObject().put("range", "Comidas!J${i + 2}").put("values", JSONArray().put(JSONArray().put(uuid))))
        }
        withContext(Dispatchers.IO) {
            send("POST", "https://sheets.googleapis.com/v4/spreadsheets/$id/values:batchUpdate", token,
                JSONObject().put("valueInputOption", "RAW").put("data", data))
        }
        return missing
    }

    private suspend fun pushPending(id: String, token: String) {
        val pending = db.meals().pending()
        if (pending.isEmpty()) return

        val adds = pending.filter { it.pendingOp == "add" }
        if (adds.isNotEmpty()) {
            val values = JSONArray()
            adds.forEach { values.put(rowFor(it)) }
            send(
                "POST",
                "https://sheets.googleapis.com/v4/spreadsheets/$id/values/" + enc("Comidas!A:J") +
                    ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
                token, JSONObject().put("values", values),
            )
        }

        val deletes = pending.filter { it.pendingOp == "delete" }
        if (deletes.isNotEmpty()) {
            val ids = get(id, "Comidas!J:J", token).map { it.firstOrNull().orEmpty() }
            val rowIdx = deletes.mapNotNull { m -> ids.indexOf(m.remoteId).takeIf { it > 0 } }.sortedDescending()
            if (rowIdx.isNotEmpty()) {
                val gid = tabId(id, "Comidas", token)
                val requests = JSONArray()
                rowIdx.forEach { r ->
                    requests.put(JSONObject().put("deleteDimension", JSONObject().put("range",
                        JSONObject().put("sheetId", gid).put("dimension", "ROWS").put("startIndex", r).put("endIndex", r + 1))))
                }
                send("POST", "https://sheets.googleapis.com/v4/spreadsheets/$id:batchUpdate", token,
                    JSONObject().put("requests", requests))
            }
        }
        // Lo subido queda como sincronizado: la lectura que sigue lo trae de vuelta desde la planilla.
        pending.forEach { db.meals().delete(it) }
    }

    private fun rowFor(m: MealEntry): JSONArray {
        val t = Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault())
        return JSONArray()
            .put(m.logicalDate)
            .put(DateTimeFormatter.ofPattern("H:mm").format(t))
            .put(m.mealType)
            .put(m.description)
            .put(Math.round(m.kcal))
            .put(round1(m.protein))
            .put(round1(m.carbs))
            .put(round1(m.fat))
            .put(m.confidence ?: "Carga manual (app)")
            .put(m.remoteId ?: UUID.randomUUID().toString())
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun padTo(r: List<String>, n: Int) = r + List((n - r.size).coerceAtLeast(0)) { "" }

    private fun tabId(id: String, title: String, token: String): Int {
        val json = send("GET", "https://sheets.googleapis.com/v4/spreadsheets/$id?fields=sheets.properties", token, null)
        val sheets = json.getJSONArray("sheets")
        for (i in 0 until sheets.length()) {
            val p = sheets.getJSONObject(i).getJSONObject("properties")
            if (p.getString("title") == title) return p.getInt("sheetId")
        }
        error("No encontré la pestaña $title")
    }

    private fun get(id: String, range: String, token: String): List<List<String>> {
        val json = send("GET", "https://sheets.googleapis.com/v4/spreadsheets/$id/values/" + enc(range), token, null)
        val values = json.optJSONArray("values") ?: return emptyList()
        return (0 until values.length()).map { i ->
            val row = values.getJSONArray(i)
            (0 until row.length()).map { j -> row.optString(j, "") }
        }
    }

    private fun send(method: String, url: String, token: String, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
            error("Google Sheets respondió $code: $msg")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    companion object {
        private val lock = Mutex()
    }
}
