package com.econostic.zebrabridge

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** État partagé entre le service et l'écran (lecture seule côté écran). */
object BridgeState {
    @Volatile var serviceRunning = false
    @Volatile var odooOk = false
    @Volatile var odooStatus = "Non démarré"
    @Volatile var deviceLabel = ""
    @Volatile var operators = ""
    @Volatile var printerOk = false
    @Volatile var printerStatus = "Aucune imprimante"
    @Volatile var printerInfo: PrinterInfo? = null
    @Volatile var printedToday = 0
    @Volatile var lastJob = ""

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val history = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun changed() {
        main.post { listeners.forEach { it() } }
    }

    fun log(message: String) {
        synchronized(history) {
            history.addFirst("${timeFmt.format(Date())}  $message")
            while (history.size > 40) history.removeLast()
        }
        changed()
    }

    fun historyText(): String = synchronized(history) { history.joinToString("\n") }
}
