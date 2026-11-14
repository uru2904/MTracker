package com.uru2904.mtracker.sheets

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.uru2904.mtracker.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Resultado de pedir acceso: token listo o una pantalla de Google que el usuario tiene que aceptar. */
sealed class AuthStep {
    data class Token(val token: String) : AuthStep()
    data class NeedsConsent(val intent: PendingIntent) : AuthStep()
}

/** Lee la pestaña Comidas de la planilla (solo lectura) y la vuelca en la base local. */
class SheetsSync(private val context: Context, private val db: AppDatabase) {

    private val prefs = context.getSharedPreferences("sheets", Context.MODE_PRIVATE)

    var sheetId: String?
        get() = prefs.getString("sheet_id", null)
        set(v) { prefs.edit().putString("sheet_id", v).apply() }

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        private set(v) { prefs.edit().putLong("last_sync", v).apply() }

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/spreadsheets.readonly")))
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

    /** Descarga Comidas!A2:I y reemplaza las comidas que vinieron de la planilla. Devuelve cuántas quedaron. */
    suspend fun syncMeals(token: String): Int {
        val id = sheetId ?: error("Falta el link de la planilla")
        val rows = withContext(Dispatchers.IO) { fetch(id, "Comidas!A2:I", token) }
        val meals = SheetMealParser.parse(rows)
        db.meals().replaceSource("planilla", meals)
        lastSync = System.currentTimeMillis()
        return meals.size
    }

    private fun fetch(id: String, range: String, token: String): List<List<String>> {
        val url = URL("https://sheets.googleapis.com/v4/spreadsheets/$id/values/" + URLEncoder.encode(range, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(200))
            error("Google Sheets respondió $code: $msg")
        }
        val values = JSONObject(body).optJSONArray("values") ?: return emptyList()
        return (0 until values.length()).map { i ->
            val row = values.getJSONArray(i)
            (0 until row.length()).map { j -> row.optString(j, "") }
        }
    }
}
