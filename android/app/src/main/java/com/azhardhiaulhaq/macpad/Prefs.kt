package com.azhardhiaulhaq.macpad

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** A Mac seen over any transport, kept so it can be reached without Bonjour. */
internal class KnownMac(val serverIdHex: String, val name: String, val addresses: List<String>)

/** Client id and per-Mac pairing keys. SharedPreferences is thread-safe for these calls. */
internal class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("macpad", Context.MODE_PRIVATE)

    val clientId: ByteArray = loadClientId()

    private fun loadClientId(): ByteArray {
        prefs.getString(KEY_CLIENT_ID, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_CLIENT_ID, Base64.encodeToString(id, Base64.NO_WRAP)).apply()
        return id
    }

    fun pairingKey(serverIdHex: String): ByteArray? =
        prefs.getString(PAIR_PREFIX + serverIdHex, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun hasPairingKey(serverIdHex: String): Boolean = prefs.contains(PAIR_PREFIX + serverIdHex)

    fun storePairingKey(serverIdHex: String, key: ByteArray) {
        prefs.edit().putString(PAIR_PREFIX + serverIdHex, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
    }

    fun deletePairingKey(serverIdHex: String) {
        prefs.edit().remove(PAIR_PREFIX + serverIdHex).apply()
    }

    fun knownMacs(): List<KnownMac> {
        val out = ArrayList<KnownMac>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(MAC_PREFIX) || value !is String) continue
            val json = JSONObject(value)
            val list = json.getJSONArray("addresses")
            out.add(KnownMac(key.substring(MAC_PREFIX.length), json.getString("name"), List(list.length()) { list.getString(it) }))
        }
        out.sortBy { it.name.lowercase() }
        return out
    }

    /** Stores the Mac's name and replaces its address list (from SERVER_INFO). */
    @Synchronized
    fun rememberMac(serverIdHex: String, name: String, addresses: List<String>) {
        val json = JSONObject().put("name", name).put("addresses", JSONArray(addresses))
        prefs.edit().putString(MAC_PREFIX + serverIdHex, json.toString()).apply()
    }

    /** Puts [address] first in the Mac's list, keeping a known name over [fallbackName]. */
    @Synchronized
    fun addMacAddress(serverIdHex: String, fallbackName: String, address: String) {
        val known = knownMacs().firstOrNull { it.serverIdHex == serverIdHex }
        val addresses = listOf(address) + (known?.addresses?.filter { it != address } ?: emptyList())
        rememberMac(serverIdHex, known?.name ?: fallbackName, addresses)
    }

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
        const val PAIR_PREFIX = "pair_"
        const val MAC_PREFIX = "mac_"
    }
}
