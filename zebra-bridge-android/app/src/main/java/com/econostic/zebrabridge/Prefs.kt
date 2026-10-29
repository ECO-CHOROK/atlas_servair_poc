package com.econostic.zebrabridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Réglages persistants du pont (appairage Odoo + imprimante liée). */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("zebra_bridge", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var deviceName: String
        get() = sp.getString("device_name", "") ?: ""
        set(v) = sp.edit().putString("device_name", v).apply()

    /** "bluetooth" ou "tcp" */
    var printerTransport: String
        get() = sp.getString("printer_transport", "bluetooth") ?: "bluetooth"
        set(v) = sp.edit().putString("printer_transport", v).apply()

    var btAddress: String
        get() = sp.getString("bt_address", "") ?: ""
        set(v) = sp.edit().putString("bt_address", v.uppercase()).apply()

    var btName: String
        get() = sp.getString("bt_name", "") ?: ""
        set(v) = sp.edit().putString("bt_name", v).apply()

    var tcpHost: String
        get() = sp.getString("tcp_host", "") ?: ""
        set(v) = sp.edit().putString("tcp_host", v.trim()).apply()

    var tcpPort: Int
        get() = sp.getInt("tcp_port", 9100)
        set(v) = sp.edit().putInt("tcp_port", v).apply()

    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(v) = sp.edit().putBoolean("keep_awake", v).apply()

    val isPaired: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    val hasPrinter: Boolean
        get() = if (printerTransport == "tcp") tcpHost.isNotBlank() else btAddress.isNotBlank()

    /** Signature de la configuration imprimante : sert à détecter un changement d'imprimante. */
    val printerKey: String
        get() = if (printerTransport == "tcp") "tcp:$tcpHost:$tcpPort" else "bt:$btAddress"

    /**
     * Code d'appairage affiché par Odoo en QR code :
     * {"v":1,"url":"https://erp.client.ma","token":"...","name":"TC26 Réception 1"}
     */
    fun applyPairingPayload(raw: String): Boolean {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return false
        return try {
            val json = JSONObject(text.substring(start, end + 1))
            val url = json.optString("url")
            val tok = json.optString("token")
            if (!url.startsWith("http") || tok.length < 20) return false
            serverUrl = url
            token = tok
            deviceName = json.optString("name")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun unpair() {
        sp.edit().remove("token").remove("device_name").apply()
    }
}
