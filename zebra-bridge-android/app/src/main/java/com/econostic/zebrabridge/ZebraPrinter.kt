package com.econostic.zebrabridge

import android.content.Context
import java.io.IOException

data class PrinterInfo(
    val name: String?,
    val serial: String?,
    val model: String?,
    val battery: Int?,
    val language: String?,
)

/** Résultat de ~HS (Host Status). [problem] est null si l'imprimante est prête. */
data class HostStatus(val paperOut: Boolean, val paused: Boolean, val headOpen: Boolean) {
    val problem: String?
        get() = when {
            headOpen -> "Capot ouvert"
            paperOut -> "Papier absent"
            paused -> "Imprimante en pause"
            else -> null
        }
}

/**
 * Accès unique (synchronisé) à l'imprimante liée, partagé entre le service et l'écran.
 * La liaison reste ouverte entre deux impressions et se reconnecte d'elle-même.
 */
object ZebraPrinter {
    private var transport: PrinterTransport? = null
    private var transportKey: String? = null

    private fun build(context: Context, prefs: Prefs): PrinterTransport? = when {
        !prefs.hasPrinter -> null
        prefs.printerTransport == "tcp" -> TcpTransport(prefs.tcpHost, prefs.tcpPort)
        else -> BluetoothTransport(context.applicationContext, prefs.btAddress)
    }

    @Synchronized
    private fun ensureOpen(context: Context): PrinterTransport {
        val prefs = Prefs(context)
        if (transportKey != prefs.printerKey) {
            transport?.close()
            transport = null
        }
        val t = transport ?: build(context, prefs)?.also {
            transport = it
            transportKey = prefs.printerKey
        } ?: throw IOException("Aucune imprimante liée")
        if (!t.isOpen) t.open()
        return t
    }

    /** Exécute [block] ; en cas de liaison rompue, reconnecte et réessaie une fois. */
    @Synchronized
    fun <T> use(context: Context, block: (PrinterTransport) -> T): T {
        return try {
            block(ensureOpen(context))
        } catch (e: IOException) {
            close()
            block(ensureOpen(context))
        }
    }

    @Synchronized
    fun close() {
        transport?.close()
        transport = null
        transportKey = null
    }

    @Synchronized
    fun isConnected(): Boolean = transport?.isOpen == true

    // ------------------------------------------------------------------
    // Commandes SGD / ZPL
    // ------------------------------------------------------------------
    private fun getVar(t: PrinterTransport, name: String): String? {
        t.drain()
        t.write("! U1 getvar \"$name\"\r\n".toByteArray(Charsets.US_ASCII))
        val raw = String(t.read(1500), Charsets.UTF_8).trim()
        val value = raw.trim().trim('"').trim()
        return value.takeIf { it.isNotEmpty() && it != "?" }
    }

    fun identify(context: Context): PrinterInfo = use(context) { t ->
        PrinterInfo(
            name = getVar(t, "device.friendly_name"),
            serial = getVar(t, "device.unique_id"),
            model = getVar(t, "device.product_name"),
            battery = getVar(t, "power.percent_full")?.filter { it.isDigit() }?.toIntOrNull(),
            language = getVar(t, "device.languages"),
        )
    }

    /** ~HS : null si l'imprimante ne répond pas (ex. mode line_print), sinon l'état décodé. */
    fun hostStatus(context: Context): HostStatus? = use(context) { t ->
        t.drain()
        t.write("~HS\r\n".toByteArray(Charsets.US_ASCII))
        parseHostStatus(String(t.read(1200), Charsets.US_ASCII))
    }

    fun parseHostStatus(raw: String): HostStatus? {
        // trois chaînes encadrées STX (0x02) ... ETX (0x03)
        val parts = raw.split('\u0002').map { it.substringBefore('\u0003').trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val s1 = parts[0].split(',')
        val s2 = parts[1].split(',')
        if (s1.size < 3 || s2.size < 3) return null
        return HostStatus(
            paperOut = s1[1].trim() == "1",
            paused = s1[2].trim() == "1",
            headOpen = s2[2].trim() == "1",
        )
    }

    fun print(context: Context, zpl: String, copies: Int = 1) {
        val data = zpl.toByteArray(Charsets.UTF_8)
        use(context) { t ->
            repeat(copies.coerceIn(1, 50)) { t.write(data) }
        }
    }

    /** Passe l'imprimante en langage ZPL (les ZQ5xx sortent parfois d'usine en line_print). */
    fun setZplMode(context: Context) = use(context) { t ->
        t.write("! U1 setvar \"device.languages\" \"zpl\"\r\n".toByteArray(Charsets.US_ASCII))
    }

    fun testLabel(deviceName: String, info: PrinterInfo?): String {
        fun safe(s: String?) = (s ?: "").replace('^', ' ').replace('~', ' ')
        val now = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE).format(java.util.Date())
        return buildString {
            append("^XA^CI28^PW576^LL320\n")
            append("^FO30,25^A0N,40,38^FDPont Zebra : test^FS\n")
            append("^FO30,80^A0N,26,26^FDPDA : ${safe(deviceName.ifBlank { android.os.Build.MODEL })}^FS\n")
            append("^FO30,115^A0N,26,26^FDImprimante : ${safe(info?.name)} ${safe(info?.serial)}^FS\n")
            append("^FO30,150^A0N,26,26^FD$now^FS\n")
            append("^FO30,195^BY2^BCN,80,Y,N,N^FDZEBRA-BRIDGE^FS\n")
            append("^XZ\n")
        }
    }
}
