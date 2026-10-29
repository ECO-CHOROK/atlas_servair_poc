package com.econostic.zebrabridge

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AuthException(message: String) : IOException(message)

data class PrintJob(val id: Int, val name: String, val zpl: String, val copies: Int)

data class PollResponse(
    val device: String,
    val operators: List<String>,
    val pollInterval: Int,
    val jobs: List<PrintJob>,
)

/** Client de l'API du module Odoo « econostic_zebra_bridge ». */
class OdooClient(private val baseUrl: String, private val token: String) {

    fun poll(payload: JSONObject): PollResponse {
        val json = post("/zebra_bridge/v1/poll", payload)
        val jobs = mutableListOf<PrintJob>()
        val arr = json.optJSONArray("jobs")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                jobs.add(PrintJob(j.getInt("id"), j.optString("name"), j.getString("zpl"), j.optInt("copies", 1)))
            }
        }
        val ops = mutableListOf<String>()
        json.optJSONArray("operators")?.let { a -> for (i in 0 until a.length()) ops.add(a.optString(i)) }
        return PollResponse(
            device = json.optString("device"),
            operators = ops,
            pollInterval = json.optInt("poll_interval", 2).coerceIn(1, 60),
            jobs = jobs,
        )
    }

    fun ack(jobId: Int, done: Boolean, message: String? = null) {
        val body = JSONObject().put("status", if (done) "done" else "error")
        if (message != null) body.put("message", message)
        post("/zebra_bridge/v1/jobs/$jobId/ack", body)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            when {
                code == 401 -> throw AuthException("Jeton refusé par Odoo : ré-appairer le PDA")
                code == 404 && path.endsWith("/poll") -> throw IOException("Module Pont Zebra absent sur ce serveur Odoo (404)")
                code !in 200..299 -> throw IOException("Odoo a répondu $code")
            }
            return try { JSONObject(text) } catch (e: Exception) { throw IOException("Réponse Odoo illisible") }
        } finally {
            conn.disconnect()
        }
    }
}
