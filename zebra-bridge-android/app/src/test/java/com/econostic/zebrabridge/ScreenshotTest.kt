package com.econostic.zebrabridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ScrollView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

/** Captures d'écran de l'application pour le guide d'exploitation (rendu Android natif). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class ScreenshotTest {

    private val dir = File(System.getProperty("shots.dir") ?: "shots").apply { mkdirs() }

    private fun capture(view: View, name: String) {
        ShadowLooper.idleMainLooper()
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun scroll(activity: MainActivity, y: Int) {
        val sv = findScroll(activity.window.decorView)!!
        sv.scrollTo(0, y)
        ShadowLooper.idleMainLooper()
    }

    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun reset() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("zebra_bridge", 0).edit().clear().commit()
        BridgeState.serviceRunning = false
        BridgeState.odooOk = false
        BridgeState.odooStatus = "Non démarré"
        BridgeState.deviceLabel = ""
        BridgeState.operators = ""
        BridgeState.printerOk = false
        BridgeState.printerStatus = "Aucune imprimante"
        BridgeState.printerInfo = null
        BridgeState.printedToday = 0
        BridgeState.lastJob = ""
    }

    private fun paired(): Prefs {
        val p = Prefs(RuntimeEnvironment.getApplication())
        p.serverUrl = "https://odoo.atlas-servair.local"
        p.token = "x".repeat(43)
        p.deviceName = "TC26 Réception 1"
        p.printerTransport = "bluetooth"
        p.btAddress = "AC:3F:A4:52:19:0B"
        p.btName = "ZQ511 Réception"
        return p
    }

    @Test
    fun a_first_launch() {
        reset()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        capture(a.window.decorView, "app_01_premier_lancement")
    }

    @Test
    fun b_running() {
        reset()
        paired()
        BridgeState.serviceRunning = true
        BridgeState.odooOk = true
        BridgeState.odooStatus = "Connecté à Odoo"
        BridgeState.deviceLabel = "TC26 Réception 1"
        BridgeState.operators = "Karim Alaoui, Youssef Benali"
        BridgeState.printerOk = true
        BridgeState.printerStatus = "Prête"
        BridgeState.printerInfo = PrinterInfo("ZQ511 Réception", "XXZKJ231904", "ZQ511", 86, "zpl")
        BridgeState.printedToday = 47
        BridgeState.lastJob = "Étiquette lot + DLC : NOU/IN/00004"
        BridgeState.log("Service démarré")
        BridgeState.log("Imprimé : Étiquette lot + DLC : NOU/IN/00003")
        BridgeState.log("Imprimé : Étiquette lot + DLC : NOU/IN/00004")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        capture(a.window.decorView, "app_02_pont_actif")
        scroll(a, 2000)
        capture(a.window.decorView, "app_03_pont_actif_bas")
    }

    @Test
    fun c_printer_problem() {
        reset()
        paired()
        BridgeState.serviceRunning = true
        BridgeState.odooOk = true
        BridgeState.odooStatus = "Connecté à Odoo"
        BridgeState.deviceLabel = "TC26 Réception 1"
        BridgeState.operators = "Karim Alaoui, Youssef Benali"
        BridgeState.printerOk = false
        BridgeState.printerStatus = "Capot ouvert : Étiquette lot + DLC : NOU/IN/00008"
        BridgeState.printerInfo = PrinterInfo("ZQ511 Réception", "XXZKJ231904", "ZQ511", 41, "zpl")
        BridgeState.printedToday = 12
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        capture(a.window.decorView, "app_04_capot_ouvert")
    }

    @Test
    fun d_wifi() {
        reset()
        val p = paired()
        p.printerTransport = "tcp"
        p.tcpHost = "10.20.30.41"
        p.tcpPort = 9100
        BridgeState.serviceRunning = true
        BridgeState.odooOk = true
        BridgeState.odooStatus = "Connecté à Odoo"
        BridgeState.deviceLabel = "TC26 Chambre froide"
        BridgeState.operators = "Hamza Tazi"
        BridgeState.printerOk = true
        BridgeState.printerStatus = "Prête"
        BridgeState.printerInfo = PrinterInfo("ZQ521 Chambre froide", "XXZKL240115", "ZQ521", 64, "zpl")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        capture(a.window.decorView, "app_05_wifi")
    }

    @Test
    fun e_odoo_unreachable() {
        reset()
        paired()
        BridgeState.serviceRunning = true
        BridgeState.odooOk = false
        BridgeState.odooStatus = "Odoo injoignable : serveur arrêté ou réseau indisponible"
        BridgeState.deviceLabel = "TC26 Réception 1"
        BridgeState.printerOk = false
        BridgeState.printerStatus = "Connexion Bluetooth impossible (imprimante hors de portée ?)"
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        capture(a.window.decorView, "app_06_hors_ligne")
    }
}
