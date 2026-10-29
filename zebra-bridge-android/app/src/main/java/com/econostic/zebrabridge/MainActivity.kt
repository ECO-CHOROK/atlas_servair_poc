package com.econostic.zebrabridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var headerDevice: TextView
    private lateinit var odooStatus: TextView
    private lateinit var odooDetails: TextView
    private lateinit var pairingInput: EditText
    private lateinit var printerStatus: TextView
    private lateinit var printerDetails: TextView
    private lateinit var transportGroup: RadioGroup
    private lateinit var btBlock: LinearLayout
    private lateinit var tcpBlock: LinearLayout
    private lateinit var macInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var serviceSwitch: Switch
    private lateinit var counter: TextView
    private lateinit var history: TextView
    private lateinit var pairButtons: LinearLayout
    private lateinit var unpairButtons: LinearLayout

    private val green by lazy { getColor(R.color.green) }
    private val ink by lazy { getColor(R.color.ink) }
    private val danger by lazy { getColor(R.color.danger) }
    private val muted by lazy { getColor(R.color.muted) }

    private val refresher: () -> Unit = { render() }
    private var bindingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        handleNfc(intent)
        askPermissions()
        if (prefs.serviceEnabled && prefs.isPaired) BridgeService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfc(intent)
    }

    override fun onResume() {
        super.onResume()
        BridgeState.addListener(refresher)
        render()
    }

    override fun onPause() {
        BridgeState.removeListener(refresher)
        super.onPause()
    }

    // ------------------------------------------------------------------
    // Construction de l'écran (sans bibliothèque externe)
    // ------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        headerDevice = TextView(this).apply { setTextColor(green); textSize = 13f }
        header.addView(headerDevice)
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // --- Odoo -------------------------------------------------------
        val odoo = card(content, "Odoo")
        odooStatus = statusLine(odoo)
        odooDetails = small(odoo)
        pairingInput = EditText(this).apply {
            hint = "Scanner ici le QR code d'appairage affiché dans Odoo"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = 14f
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                ) { tryPair(text.toString()); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val t = s?.toString() ?: return
                    if (t.trim().endsWith("}") && t.contains("\"token\"")) tryPair(t)
                }
            })
        }
        odoo.addView(pairingInput)
        pairButtons = buttonRow(odoo, "Appairer" to { tryPair(pairingInput.text.toString()) })
        unpairButtons = buttonRow(odoo, "Désappairer ce PDA" to { confirmUnpair() })

        // --- Imprimante --------------------------------------------------
        val printer = card(content, "Imprimante Zebra")
        printerStatus = statusLine(printer)
        printerDetails = small(printer)

        transportGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbBt = RadioButton(this).apply { id = View.generateViewId(); text = "Bluetooth" }
        val rbTcp = RadioButton(this).apply { id = View.generateViewId(); text = "Wi-Fi" }
        transportGroup.addView(rbBt)
        transportGroup.addView(rbTcp)
        transportGroup.check(if (prefs.printerTransport == "tcp") rbTcp.id else rbBt.id)
        transportGroup.setOnCheckedChangeListener { _, checked ->
            prefs.printerTransport = if (checked == rbTcp.id) "tcp" else "bluetooth"
            ZebraPrinter.close()
            render()
            wakeService()
        }
        printer.addView(transportGroup)

        btBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        macInput = EditText(this).apply {
            hint = "Adresse MAC Bluetooth (saisie ou scan)"
            setSingleLine(true)
            textSize = 14f
            setText(prefs.btAddress)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> saveMac(text.toString()); true }
        }
        btBlock.addView(macInput)
        btBlock.addView(small(null).apply {
            text = "Astuce : poser le PDA sur le logo NFC de l'imprimante suffit à la lier."
        })
        buttonRow(btBlock,
            "Choisir parmi les appareils appairés" to { chooseBondedPrinter() },
        )
        buttonRow(btBlock, "Enregistrer l'adresse" to { saveMac(macInput.text.toString()) })
        printer.addView(btBlock)

        tcpBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hostInput = EditText(this).apply {
            hint = "Adresse IP de l'imprimante"
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.tcpHost)
        }
        portInput = EditText(this).apply {
            hint = "Port"
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.tcpPort.toString())
        }
        tcpBlock.addView(hostInput)
        tcpBlock.addView(portInput)
        buttonRow(tcpBlock, "Enregistrer" to {
            prefs.tcpHost = hostInput.text.toString()
            prefs.tcpPort = portInput.text.toString().toIntOrNull() ?: 9100
            ZebraPrinter.close()
            toast("Imprimante Wi-Fi enregistrée")
            identifyPrinter()
        })
        printer.addView(tcpBlock)

        buttonRow(printer,
            "Identifier" to { identifyPrinter() },
            "Étiquette test" to { testPrint() },
        )
        buttonRow(printer, "Passer l'imprimante en ZPL" to { setZpl() })

        // --- Service -----------------------------------------------------
        val service = card(content, "Pont d'impression")
        serviceSwitch = Switch(this).apply {
            text = "Imprimer automatiquement les étiquettes Odoo"
            textSize = 15f
            setOnCheckedChangeListener { _, on ->
                if (bindingSwitch) return@setOnCheckedChangeListener
                if (on) {
                    if (!prefs.isPaired) {
                        toast("Appairer d'abord le PDA avec Odoo")
                        bindingSwitch = true; isChecked = false; bindingSwitch = false
                    } else BridgeService.start(this@MainActivity)
                } else BridgeService.stop(this@MainActivity)
            }
        }
        service.addView(serviceSwitch)
        service.addView(CheckBox(this).apply {
            text = "Garder le PDA éveillé pendant le service"
            isChecked = prefs.keepAwake
            setOnCheckedChangeListener { _, v -> prefs.keepAwake = v }
        })
        counter = small(service)
        buttonRow(service, "Désactiver l'optimisation batterie" to { askBatteryExemption() })
        history = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        service.addView(history)

        return root
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.card))
                setStroke(dp(1), getColor(R.color.border))
                cornerRadius = dp(10).toFloat()
            }
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
        })
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return c
    }

    private fun statusLine(parent: LinearLayout) = TextView(this).apply {
        textSize = 15f
        setPadding(0, dp(6), 0, dp(2))
        parent.addView(this)
    }

    private fun small(parent: LinearLayout?) = TextView(this).apply {
        textSize = 13f
        setTextColor(muted)
        parent?.addView(this)
    }

    private fun buttonRow(parent: LinearLayout, vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        for ((label, action) in buttons) {
            row.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        parent.addView(row)
        return row
    }

    private fun render() {
        headerDevice.text = when {
            BridgeState.deviceLabel.isNotBlank() -> BridgeState.deviceLabel
            prefs.deviceName.isNotBlank() -> prefs.deviceName
            else -> "PDA non appairé"
        }
        val running = BridgeState.serviceRunning
        odooStatus.text = "● " + if (running) BridgeState.odooStatus else if (prefs.isPaired) "Appairé (service arrêté)" else "Non appairé"
        odooStatus.setTextColor(if (running && BridgeState.odooOk) green else if (running) danger else muted)
        val pairedNow = prefs.isPaired
        pairingInput.visibility = if (pairedNow) View.GONE else View.VISIBLE
        pairButtons.visibility = if (pairedNow) View.GONE else View.VISIBLE
        unpairButtons.visibility = if (pairedNow) View.VISIBLE else View.GONE
        odooDetails.text = buildString {
            if (prefs.serverUrl.isNotBlank()) append(prefs.serverUrl)
            if (BridgeState.operators.isNotBlank()) append("\nOpérateurs : ${BridgeState.operators}")
        }

        val tcp = prefs.printerTransport == "tcp"
        btBlock.visibility = if (tcp) View.GONE else View.VISIBLE
        tcpBlock.visibility = if (tcp) View.VISIBLE else View.GONE
        printerStatus.text = "● " + BridgeState.printerStatus
        printerStatus.setTextColor(if (BridgeState.printerOk) green else if (prefs.hasPrinter) danger else muted)
        val info = BridgeState.printerInfo
        printerDetails.text = buildString {
            append(when {
                !prefs.hasPrinter -> "Lier l'imprimante ci-dessous"
                tcp -> "Wi-Fi · ${prefs.tcpHost}:${prefs.tcpPort}"
                else -> "Bluetooth · ${prefs.btAddress}"
            })
            if (info != null) {
                append("\n")
                append(listOfNotNull(
                    info.name ?: prefs.btName.ifBlank { null },
                    info.model?.takeIf { m -> info.name?.contains(m) != true },
                    info.serial?.let { "n° $it" },
                    info.battery?.let { "batterie $it %" },
                ).joinToString(" · "))
                val lang = info.language
                if (lang != null && !lang.contains("zpl", ignoreCase = true))
                    append("\nLangage « $lang » : passer l'imprimante en ZPL")
            } else if (!tcp && prefs.btName.isNotBlank()) {
                append("\n${prefs.btName}")
            }
        }

        bindingSwitch = true
        serviceSwitch.isChecked = running || prefs.serviceEnabled
        bindingSwitch = false
        counter.text = "${BridgeState.printedToday} étiquette(s) imprimée(s) aujourd'hui" +
            if (BridgeState.lastJob.isNotBlank()) "\nDernière : ${BridgeState.lastJob}" else ""
        history.text = BridgeState.historyText()
    }

    // ------------------------------------------------------------------
    // Appairage Odoo
    // ------------------------------------------------------------------
    private fun tryPair(raw: String) {
        if (prefs.applyPairingPayload(raw)) {
            pairingInput.setText("")
            toast("PDA appairé : ${prefs.deviceName}")
            BridgeState.log("Appairé avec ${prefs.serverUrl}")
            BridgeService.start(this, BridgeService.ACTION_WAKE)
            render()
        } else if (raw.isNotBlank()) {
            toast("Code d'appairage non reconnu")
        }
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setMessage("Désappairer ce PDA ? Les étiquettes Odoo ne seront plus imprimées ici.")
            .setPositiveButton("Désappairer") { _, _ ->
                BridgeService.stop(this)
                prefs.unpair()
                BridgeState.deviceLabel = ""
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Imprimante
    // ------------------------------------------------------------------
    private fun normalizeMac(raw: String): String? {
        val hex = raw.uppercase().filter { it in "0123456789ABCDEF" }
        if (hex.length != 12) return null
        return hex.chunked(2).joinToString(":")
    }

    private fun saveMac(raw: String) {
        val mac = normalizeMac(raw)
        if (mac == null) { toast("Adresse MAC invalide (12 caractères hexadécimaux)"); return }
        linkBluetooth(mac, prefs.btName.takeIf { prefs.btAddress == mac } ?: "")
    }

    private fun linkBluetooth(mac: String, name: String) {
        prefs.printerTransport = "bluetooth"
        prefs.btAddress = mac
        prefs.btName = name
        macInput.setText(mac)
        transportGroup.check(transportGroup.getChildAt(0).id)
        ZebraPrinter.close()
        BridgeState.printerInfo = null
        BridgeState.log("Imprimante liée : ${name.ifBlank { mac }}")
        identifyPrinter()
    }

    @SuppressLint("MissingPermission")
    private fun chooseBondedPrinter() {
        if (!hasBtPermission()) { askPermissions(); return }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { toast("Activer le Bluetooth"); return }
        val devices = adapter.bondedDevices.orEmpty().sortedBy {
            if (it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING) 0 else 1
        }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("Aucun appareil appairé. Appairer la ZQ511 dans les réglages Bluetooth (code 0000 ou 1234 si demandé), ou poser le PDA sur son logo NFC.")
                .setPositiveButton("Réglages Bluetooth") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Fermer", null)
                .show()
            return
        }
        val labels = devices.map { d ->
            val printer = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING
            (if (printer) "🖨 " else "") + (d.name ?: "Sans nom") + "\n" + d.address
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Imprimante Bluetooth")
            .setItems(labels) { _, i -> linkBluetooth(devices[i].address, devices[i].name ?: "") }
            .setNeutralButton("Réglages Bluetooth") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .show()
    }

    private fun background(label: String, task: () -> String) {
        toast("$label…")
        Thread {
            val message = try { task() } catch (e: Exception) {
                BridgeState.printerOk = false
                BridgeState.printerStatus = e.message ?: "Erreur imprimante"
                BridgeState.changed()
                "Échec : ${e.message}"
            }
            runOnUiThread { toast(message); render() }
        }.start()
    }

    private fun identifyPrinter() {
        if (!prefs.hasPrinter) { toast("Aucune imprimante liée"); return }
        if (prefs.printerTransport == "bluetooth" && !hasBtPermission()) { askPermissions(); return }
        background("Identification") {
            val info = ZebraPrinter.identify(this)
            BridgeState.printerInfo = info
            BridgeState.printerOk = true
            BridgeState.printerStatus = "Prête"
            BridgeState.changed()
            if (!info.name.isNullOrBlank() && prefs.printerTransport == "bluetooth") prefs.btName = info.name
            wakeService()
            val lang = info.language ?: ""
            if (lang.isNotBlank() && !lang.contains("zpl", ignoreCase = true))
                "Imprimante en langage « $lang » : utiliser « Passer en ZPL »"
            else "Imprimante ${info.model ?: ""} ${info.serial ?: ""} reconnue".trim()
        }
    }

    private fun testPrint() {
        if (!prefs.hasPrinter) { toast("Aucune imprimante liée"); return }
        background("Impression") {
            ZebraPrinter.print(this, ZebraPrinter.testLabel(prefs.deviceName, BridgeState.printerInfo))
            BridgeState.log("Étiquette de test imprimée")
            "Étiquette de test envoyée"
        }
    }

    private fun setZpl() {
        if (!prefs.hasPrinter) { toast("Aucune imprimante liée"); return }
        background("Configuration") {
            ZebraPrinter.setZplMode(this)
            Thread.sleep(1500)
            val info = ZebraPrinter.identify(this)
            BridgeState.printerInfo = info
            "Langage de l'imprimante : ${info.language ?: "?"}"
        }
    }

    private fun wakeService() {
        if (prefs.serviceEnabled && prefs.isPaired) BridgeService.start(this, BridgeService.ACTION_WAKE)
    }

    // ------------------------------------------------------------------
    // NFC « Print Touch » : http://www.zebra.com/apps/r/nfc?mB=<MAC BT>&s=<n° série>&c=<modèle>...
    // ------------------------------------------------------------------
    private fun handleNfc(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        var uri: Uri? = intent.data
        if (uri == null) {
            @Suppress("DEPRECATION")
            val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            val msg = raw?.firstOrNull() as? NdefMessage
            uri = msg?.records?.firstNotNullOfOrNull { it.toUri() }
        }
        val u = uri ?: return
        val mac = normalizeMac(u.getQueryParameter("mB") ?: "")
        if (mac == null) { toast("Étiquette NFC Zebra sans adresse Bluetooth"); return }
        val serial = u.getQueryParameter("s") ?: ""
        val model = u.getQueryParameter("c") ?: ""
        linkBluetooth(mac, listOf(model.substringBefore('-'), serial).filter { it.isNotBlank() }.joinToString(" "))
        toast("Imprimante liée par NFC")
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------
    private fun hasBtPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPermission())
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Déjà exempté"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
