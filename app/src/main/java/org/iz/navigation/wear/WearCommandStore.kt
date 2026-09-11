package org.iz.navigation.wear

import android.content.Context
import android.util.Base64
import org.iz.navigation.wearprotocol.*
import java.security.MessageDigest
import org.json.JSONObject

/** All access is serialized by WearPhoneBridge. Writes commit before any recording side effect. */
internal class WearCommandStore(context: Context) {
    private val prefs = context.getSharedPreferences("wear_commands_v1", Context.MODE_PRIVATE)
    data class Entry(val key: String, val source: String, val command: WearCommand, val result: WearResult,
        val protocolVersion: Int = 1)
    private fun key(source: String, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$source\u0000$id".toByteArray()).joinToString("") { "%02x".format(it) }
    fun get(source: String, command: WearCommand): Entry? = get(key(source, command.id))
    fun get(key: String): Entry? = runCatching {
        val obj = JSONObject(prefs.getString(key, null) ?: return null)
        val bytes = Base64.decode(obj.getString("command"), Base64.NO_WRAP)
        val version = obj.optInt("protocolVersion", 1)
        if (WearProtocol.frameVersion(bytes) != version) return null
        val resultBytes = Base64.decode(obj.getString("result"), Base64.NO_WRAP)
        if (WearProtocol.frameVersion(resultBytes) != version) return null
        Entry(key, obj.getString("source"), WearProtocol.decodeCommand(bytes) ?: return null,
            WearProtocol.decodeResult(resultBytes) ?: return null, version)
    }.getOrNull()
    fun save(source: String, command: WearCommand, result: WearResult, now: Long,
        protocolVersion: Int = WearProtocol.CURRENT_VERSION): Entry {
        val entryKey = key(source, command.id)
        val entries = prefs.all.keys.mapNotNull(::get)
        val expired = entries.filter { now - it.command.requestedAt > 600_000L }
        check(entries.size - expired.size < 128 || prefs.contains(entryKey)) { "Saat istek kuyruğu dolu; biraz sonra tekrar dene." }
        val obj = JSONObject().put("source", source).put("protocolVersion", protocolVersion)
            .put("command", Base64.encodeToString(WearProtocol.encodeCommand(command, protocolVersion), Base64.NO_WRAP))
            .put("result", Base64.encodeToString(WearProtocol.encodeResult(result, protocolVersion), Base64.NO_WRAP))
        val edit = prefs.edit()
        expired.forEach { edit.remove(it.key) }
        check(edit.putString(entryKey, obj.toString()).commit()) { "Saat isteği saklanamadı." }
        return Entry(entryKey, source, command, result, protocolVersion)
    }
    fun pending(now: Long): Entry? = prefs.all.keys.mapNotNull(::get).filter {
        it.result.code == WearResultCode.NEEDS_PHONE && WearProtocol.isFreshCommand(it.command, now)
    }.maxByOrNull { it.command.requestedAt }
}
