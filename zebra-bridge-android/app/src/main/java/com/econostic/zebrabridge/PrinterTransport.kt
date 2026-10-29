package com.econostic.zebrabridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Liaison brute vers l'imprimante : Bluetooth SPP (RFCOMM) ou TCP 9100. */
interface PrinterTransport : Closeable {
    val kind: String        // "bluetooth" | "tcp"
    val address: String
    val isOpen: Boolean
    fun open()
    fun write(data: ByteArray)
    /** Lit la réponse de l'imprimante : s'arrête après [quietMs] sans nouvel octet ou à [timeoutMs]. */
    fun read(timeoutMs: Long, quietMs: Long = 150): ByteArray
    fun drain()
}

abstract class StreamTransport : PrinterTransport {
    protected var input: InputStream? = null
    protected var output: OutputStream? = null

    override fun write(data: ByteArray) {
        val out = output ?: throw IOException("Liaison imprimante fermée")
        // envoi par blocs : certains firmwares mobiles saturent sur de gros paquets Bluetooth
        var offset = 0
        while (offset < data.size) {
            val len = minOf(1024, data.size - offset)
            out.write(data, offset, len)
            offset += len
        }
        out.flush()
    }

    override fun read(timeoutMs: Long, quietMs: Long): ByteArray {
        val inp = input ?: throw IOException("Liaison imprimante fermée")
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(512)
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastData = 0L
        while (System.currentTimeMillis() < deadline) {
            val available = inp.available()
            if (available > 0) {
                val n = inp.read(chunk, 0, minOf(available, chunk.size))
                if (n < 0) throw IOException("Liaison imprimante interrompue")
                buffer.write(chunk, 0, n)
                lastData = System.currentTimeMillis()
            } else if (lastData > 0 && System.currentTimeMillis() - lastData > quietMs) {
                break
            } else {
                Thread.sleep(20)
            }
        }
        return buffer.toByteArray()
    }

    override fun drain() {
        try {
            val inp = input ?: return
            while (inp.available() > 0) inp.read(ByteArray(inp.available()))
        } catch (_: IOException) {
        }
    }
}

class BluetoothTransport(private val context: Context, mac: String) : StreamTransport() {
    override val kind = "bluetooth"
    override val address = mac.uppercase()
    private var socket: BluetoothSocket? = null

    override val isOpen: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    override fun open() {
        close()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: throw IOException("Bluetooth indisponible sur ce terminal")
        if (!adapter.isEnabled) throw IOException("Bluetooth désactivé")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IOException("Adresse Bluetooth invalide : $address")
        }
        // Les imprimantes mobiles Zebra acceptent le SPP non sécurisé ; repli en mode sécurisé.
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
        )
        var last: Exception? = null
        for (create in attempts) {
            val s = try { create() } catch (e: Exception) { last = e; continue }
            try {
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return
            } catch (e: Exception) {
                last = e
                try { s.close() } catch (_: IOException) {}
            }
        }
        throw IOException("Connexion Bluetooth impossible (${last?.message ?: "imprimante hors de portée ?"})")
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        input = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

class TcpTransport(private val host: String, private val port: Int = 9100) : StreamTransport() {
    override val kind = "tcp"
    override val address = "$host:$port"
    private var socket: Socket? = null

    override val isOpen: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    override fun open() {
        close()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 5000)
            s.tcpNoDelay = true
            s.keepAlive = true
        } catch (e: Exception) {
            try { s.close() } catch (_: IOException) {}
            throw IOException("Connexion Wi-Fi impossible vers $host:$port (${e.message})")
        }
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        input = null
        output = null
    }
}
