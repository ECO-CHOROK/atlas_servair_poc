package com.econostic.zebrabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar

/**
 * Service de premier plan : interroge Odoo toutes les N secondes, imprime les étiquettes
 * reçues sur l'imprimante liée et renvoie un accusé (imprimé / erreur) pour chaque travail.
 */
class BridgeService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val lock = Object()

    private var lastTelemetry = 0L
    private var dayOfYear = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).serviceEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (intent?.action == ACTION_WAKE) wake()
        if (!running) {
            running = true
            BridgeState.serviceRunning = true
            acquireWakeLock()
            worker = Thread(::loop, "zebra-bridge").also { it.start() }
            BridgeState.log("Service démarré")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wake()
        worker?.interrupt()
        ZebraPrinter.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        BridgeState.serviceRunning = false
        BridgeState.odooOk = false
        BridgeState.odooStatus = "Service arrêté"
        BridgeState.log("Service arrêté")
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification(this, "Pont Zebra actif", BridgeState.printerStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (!Prefs(this).keepAwake) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZebraBridge:poll").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun pause(ms: Long) {
        synchronized(lock) {
            try { lock.wait(ms) } catch (_: InterruptedException) {}
        }
    }

    // ------------------------------------------------------------------
    // Boucle principale
    // ------------------------------------------------------------------
    private fun loop() {
        var backoff = 2000L
        while (running) {
            val prefs = Prefs(this)
            if (!prefs.isPaired) {
                setOdoo(false, "PDA non appairé : scanner le QR code Odoo")
                pause(5000)
                continue
            }
            val client = OdooClient(prefs.serverUrl, prefs.token)
            try {
                refreshTelemetry(prefs)
                val response = client.poll(telemetryPayload(prefs))
                BridgeState.deviceLabel = response.device
                BridgeState.operators = response.operators.joinToString(", ")
                setOdoo(true, "Connecté à Odoo")
                backoff = 2000L
                for (job in response.jobs) {
                    if (!running) break
                    process(client, job)
                }
                pause(response.pollInterval * 1000L)
            } catch (e: AuthException) {
                setOdoo(false, e.message ?: "Jeton refusé")
                pause(15000)
            } catch (e: Exception) {
                setOdoo(false, "Odoo injoignable : ${networkReason(e)}")
                pause(backoff)
                backoff = (backoff * 2).coerceAtMost(15000)
            }
        }
    }

    /** Imprime un travail ; patiente jusqu'à ~60 s si l'imprimante est hors de portée, capot ouvert... */
    private fun process(client: OdooClient, job: PrintJob) {
        var lastError = "Imprimante indisponible"
        for (attempt in 1..20) {
            if (!running) return
            try {
                val status = try { ZebraPrinter.hostStatus(this) } catch (e: IOException) { throw e } catch (_: Exception) { null }
                val problem = status?.problem
                if (problem != null) {
                    lastError = problem
                    setPrinter(false, "$problem : ${job.name}")
                    pause(3000)
                    continue
                }
                ZebraPrinter.print(this, job.zpl, job.copies)
                setPrinter(true, "Prête")
                countPrinted(job)
                BridgeState.log("Imprimé : ${job.name}")
                ackSafely(client, job, true, null)
                return
            } catch (e: IOException) {
                lastError = e.message ?: "Liaison imprimante perdue"
                setPrinter(false, lastError)
                ZebraPrinter.close()
                pause(3000)
            }
        }
        BridgeState.log("Échec : ${job.name} ($lastError)")
        ackSafely(client, job, false, lastError)
    }

    private fun ackSafely(client: OdooClient, job: PrintJob, done: Boolean, message: String?) {
        for (i in 1..3) {
            try {
                client.ack(job.id, done, message)
                return
            } catch (e: Exception) {
                pause(1000L * i)
            }
        }
        BridgeState.log("Accusé non transmis pour ${job.name}")
    }

    private fun countPrinted(job: PrintJob) {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (today != dayOfYear) {
            dayOfYear = today
            BridgeState.printedToday = 0
        }
        val labels = Regex("\\^XA", RegexOption.IGNORE_CASE).findAll(job.zpl).count().coerceAtLeast(1)
        BridgeState.printedToday += labels * job.copies.coerceAtLeast(1)
        BridgeState.lastJob = job.name
    }

    /** Toutes les 60 s : identification de l'imprimante (nom, n° de série, batterie). */
    private fun refreshTelemetry(prefs: Prefs) {
        if (!prefs.hasPrinter) {
            setPrinter(false, "Aucune imprimante liée")
            return
        }
        val now = System.currentTimeMillis()
        val connected = ZebraPrinter.isConnected()
        // connectée : rafraîchir toutes les 60 s ; injoignable : retenter toutes les 15 s
        if (now - lastTelemetry < (if (connected) 60_000 else 15_000)) return
        lastTelemetry = now
        try {
            BridgeState.printerInfo = ZebraPrinter.identify(this)
            setPrinter(true, "Prête")
        } catch (e: Exception) {
            setPrinter(false, e.message ?: "Imprimante injoignable")
        }
    }

    private fun telemetryPayload(prefs: Prefs): JSONObject {
        val info = BridgeState.printerInfo
        val printer = JSONObject()
            .put("transport", prefs.printerTransport)
            .put("address", if (prefs.printerTransport == "tcp") "${prefs.tcpHost}:${prefs.tcpPort}" else prefs.btAddress)
            .put("name", info?.name ?: prefs.btName)
            .put("serial", info?.serial ?: "")
            .put("connected", BridgeState.printerOk)
            .put("status", BridgeState.printerStatus)
        info?.battery?.let { printer.put("battery", it) }
        return JSONObject()
            .put("app_version", BuildConfigCompat.versionName(this))
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("printer", if (prefs.hasPrinter) printer else JSONObject())
    }

    private fun networkReason(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "adresse du serveur introuvable (Wi-Fi ou DNS)"
        is java.net.ConnectException -> "serveur arrêté ou réseau indisponible"
        is java.net.SocketTimeoutException -> "le serveur ne répond pas"
        is javax.net.ssl.SSLException -> "certificat HTTPS refusé par le PDA"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun setOdoo(ok: Boolean, status: String) {
        val changed = BridgeState.odooOk != ok || BridgeState.odooStatus != status
        BridgeState.odooOk = ok
        BridgeState.odooStatus = status
        if (changed) {
            updateNotification()
            BridgeState.changed()
        }
    }

    private fun setPrinter(ok: Boolean, status: String) {
        val changed = BridgeState.printerOk != ok || BridgeState.printerStatus != status
        BridgeState.printerOk = ok
        BridgeState.printerStatus = status
        if (changed) {
            updateNotification()
            BridgeState.changed()
        }
    }

    private fun updateNotification() {
        val title = when {
            !BridgeState.odooOk -> "Odoo : ${BridgeState.odooStatus}"
            !BridgeState.printerOk -> "Imprimante : ${BridgeState.printerStatus}"
            else -> "Pont Zebra prêt"
        }
        val text = "Imprimante : ${BridgeState.printerStatus} · ${BridgeState.printedToday} étiquette(s) aujourd'hui"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(this, title, text))
    }

    companion object {
        const val ACTION_STOP = "com.econostic.zebrabridge.STOP"
        const val ACTION_WAKE = "com.econostic.zebrabridge.WAKE"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIF_ID = 42

        fun start(context: Context, action: String? = null) {
            Prefs(context).serviceEnabled = true
            val intent = Intent(context, BridgeService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            Prefs(context).serviceEnabled = false
            context.stopService(Intent(context, BridgeService::class.java))
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Pont d'impression", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}

object BuildConfigCompat {
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
