package com.krudoy.stlviewer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last few models opened, so they can be reopened without the system file
 * picker. Only files the app can still read later are kept: ones picked with
 * Open (persisted read permission) or plain file paths.
 */
class RecentFiles(context: Context) {

    data class Entry(val uri: Uri, val name: String, val openedAt: Long)

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)
    private val resolver: ContentResolver = context.contentResolver

    fun list(): List<Entry> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(Uri.parse(o.getString("uri")), o.getString("name"), o.getLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    /** Records [uri] as just opened, if the app will be able to open it again later. */
    fun add(uri: Uri, name: String) {
        if (!canReopen(uri)) return
        val rest = list().filter { it.uri != uri }
        val updated = listOf(Entry(uri, name, System.currentTimeMillis())) + rest
        save(updated.take(MAX))
        // Give back read access for files that fell off the list.
        for (dropped in updated.drop(MAX)) release(dropped.uri)
    }

    fun remove(uri: Uri) {
        save(list().filter { it.uri != uri })
        release(uri)
    }

    private fun canReopen(uri: Uri): Boolean = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> true
        ContentResolver.SCHEME_CONTENT -> resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        else -> false
    }

    private fun release(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().put("uri", e.uri.toString()).put("name", e.name).put("at", e.openedAt))
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val MAX = 8
    }
}
