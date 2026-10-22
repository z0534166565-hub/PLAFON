package com.kosherdialer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Contact(val name: String, val numbers: List<String>)
data class CallEntry(val name: String, val number: String, val type: String, val time: Long)

/** לקוח PBAP (Phone Book Access Profile) מעל OBEX/RFCOMM - מושך אנשי קשר ויומן שיחות מהמכשיר המחובר. */
@SuppressLint("MissingPermission")
class PbapSession(private val device: BluetoothDevice) : Closeable {
    private var socket: BluetoothSocket? = null
    private lateinit var inp: InputStream
    private lateinit var out: OutputStream
    private var connId: ByteArray? = null

    private class Resp(val code: Int, val body: ByteArray?, val connId: ByteArray?)

    fun open() {
        val s = device.createRfcommSocketToServiceRecord(UUID.fromString("0000112f-0000-1000-8000-00805f9b34fb"))
        s.connect()
        socket = s; inp = s.inputStream; out = s.outputStream
        val b = ByteArrayOutputStream()
        b.write(0x10); b.write(0x00); b.write(0x20); b.write(0x00) // גרסה, דגלים, גודל חבילה 0x2000
        b.write(0x46); b.write16(3 + TARGET.size); b.write(TARGET)
        send(0x80, b)
        val r = readResponse(true)
        if (r.code != 0xA0) error("OBEX connect נכשל: 0x%02X".format(r.code))
        connId = r.connId
    }

    /** name: "telecom/pb.vcf" (אנשי קשר) או "telecom/cch.vcf" (יומן שיחות משולב) */
    fun pull(name: String, maxCount: Int): String {
        val h = ByteArrayOutputStream()
        connId?.let { h.write(0xCB); h.write(it) }
        val type = "x-bt/phonebook".toByteArray() + 0
        h.write(0x42); h.write16(3 + type.size); h.write(type)
        val n = name.toByteArray(Charsets.UTF_16BE) + byteArrayOf(0, 0)
        h.write(0x01); h.write16(3 + n.size); h.write(n)
        val ap = byteArrayOf(0x07, 1, 1, 0x04, 2, (maxCount shr 8).toByte(), (maxCount and 0xFF).toByte())
        h.write(0x4C); h.write16(3 + ap.size); h.write(ap)
        send(0x83, h)
        val res = ByteArrayOutputStream()
        while (true) {
            val r = readResponse(false)
            r.body?.let { res.write(it) }
            if (r.code == 0xA0) break
            if (r.code != 0x90) error("OBEX GET נכשל: 0x%02X".format(r.code))
            send(0x83, ByteArrayOutputStream())
        }
        return res.toString("UTF-8")
    }

    private fun send(op: Int, payload: ByteArrayOutputStream) {
        val p = payload.toByteArray()
        out.write(op); out.write16(p.size + 3); out.write(p); out.flush()
    }

    private fun readResponse(connect: Boolean): Resp {
        val head = ByteArray(3); readFully(head)
        val code = head[0].toInt() and 0xFF
        val len = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
        val data = ByteArray(len - 3); readFully(data)
        var i = if (connect && data.size >= 4) 4 else 0
        var body: ByteArray? = null; var cid: ByteArray? = null
        while (i < data.size) {
            val id = data[i].toInt() and 0xFF
            when (id and 0xC0) {
                0x00, 0x40 -> {
                    val l = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
                    if (id == 0x48 || id == 0x49) body = (body ?: ByteArray(0)) + data.copyOfRange(i + 3, i + l)
                    i += l
                }
                0x80 -> i += 2
                else -> { if (id == 0xCB) cid = data.copyOfRange(i + 1, i + 5); i += 5 }
            }
        }
        return Resp(code, body, cid)
    }

    private fun readFully(b: ByteArray) {
        var o = 0
        while (o < b.size) { val n = inp.read(b, o, b.size - o); if (n < 0) error("החיבור נותק"); o += n }
    }

    override fun close() {
        try { out.write(0x81); out.write16(3); out.flush() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun OutputStream.write16(v: Int) { write(v shr 8); write(v and 0xFF) }
    private fun ByteArrayOutputStream.write16(v: Int) { write(v shr 8); write(v and 0xFF) }

    companion object {
        private val TARGET = byteArrayOf(
            0x79, 0x61, 0x35, 0xF0.toByte(), 0xF0.toByte(), 0xC5.toByte(), 0x11, 0xD8.toByte(),
            0x09, 0x66, 0x08, 0x00, 0x20, 0x0C, 0x9A.toByte(), 0x66
        )
    }
}

private fun cards(raw: String): List<List<String>> {
    val lines = raw.replace("\r\n", "\n").replace("\n ", "").split("\n")
    val res = mutableListOf<List<String>>(); var cur: MutableList<String>? = null
    for (l in lines) when {
        l.startsWith("BEGIN:VCARD") -> cur = mutableListOf()
        l.startsWith("END:VCARD") -> { cur?.let { res.add(it) }; cur = null }
        else -> cur?.add(l)
    }
    return res
}

private fun nameOf(c: List<String>): String {
    c.firstOrNull { it.startsWith("FN") && it.substringBefore(':').substringBefore(';') == "FN" }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val n = c.firstOrNull { it.substringBefore(':').substringBefore(';') == "N" }?.substringAfter(':') ?: return ""
    return n.split(";").filter { it.isNotBlank() }.reversed().joinToString(" ").trim()
}

private fun telsOf(c: List<String>) = c.filter { it.substringBefore(':').substringBefore(';') == "TEL" }
    .map { it.substringAfter(':').trim() }.filter { it.isNotEmpty() }

fun parseContacts(raw: String): List<Contact> = cards(raw).mapNotNull {
    val tels = telsOf(it); val nm = nameOf(it)
    if (tels.isEmpty() && nm.isEmpty()) null else Contact(nm.ifEmpty { tels.first() }, tels)
}.sortedBy { it.name.lowercase() }

fun parseCalls(raw: String): List<CallEntry> {
    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
    return cards(raw).mapNotNull { c ->
        val tel = telsOf(c).firstOrNull() ?: return@mapNotNull null
        val dt = c.firstOrNull { it.startsWith("X-IRMC-CALL-DATETIME") } ?: return@mapNotNull null
        val type = Regex("(MISSED|RECEIVED|DIALED)").find(dt.substringBefore(':'))?.value ?: "RECEIVED"
        val t = try { fmt.parse(dt.substringAfter(':').trim().take(15))?.time ?: 0L } catch (_: Exception) { 0L }
        CallEntry(nameOf(c).takeIf { it != tel } ?: "", tel, type, t)
    }.sortedByDescending { it.time }
}

fun norm(s: String) = s.filter { it.isDigit() }.takeLast(9)
fun fmtTime(t: Long): String = if (t == 0L) "" else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(t))
