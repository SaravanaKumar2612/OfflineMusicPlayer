package com.example.musicplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PlaylistManager {

    private const val FILE_NAME = "playlists.json"

    // Structure: Map<PlaylistName, List<SongPath>>
    private val playlists = mutableMapOf<String, MutableList<String>>()

    fun load(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            // Create default "Liked Songs"
            playlists["Liked Songs"] = mutableListOf()
            save(context)
            return
        }

        try {
            val jsonString = file.readText()
            val jsonObject = JSONObject(jsonString)
            val keys = jsonObject.keys()

            playlists.clear()
            while (keys.hasNext()) {
                val playlistName = keys.next()
                val jsonArray = jsonObject.getJSONArray(playlistName)
                val songPaths = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    songPaths.add(jsonArray.getString(i))
                }
                playlists[playlistName] = songPaths
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Ensure Liked Songs always exists
        if (!playlists.containsKey("Liked Songs")) {
            playlists["Liked Songs"] = mutableListOf()
        }
    }

    fun save(context: Context) {
        val jsonObject = JSONObject()
        for ((name, paths) in playlists) {
            val jsonArray = JSONArray()
            paths.forEach { jsonArray.put(it) }
            jsonObject.put(name, jsonArray)
        }
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(jsonObject.toString())
    }

    fun getPlaylists(): List<String> = playlists.keys.sorted()

    fun getSongsInPlaylist(playlistName: String): List<String> {
        return playlists[playlistName] ?: emptyList()
    }

    fun createPlaylist(context: Context, name: String): Boolean {
        if (playlists.containsKey(name)) return false
        playlists[name] = mutableListOf()
        save(context)
        return true
    }

    fun addSongToPlaylist(context: Context, playlistName: String, songPath: String) {
        val list = playlists[playlistName] ?: return
        if (!list.contains(songPath)) {
            list.add(songPath)
            save(context)
        }
    }

    fun removeSongFromPlaylist(context: Context, playlistName: String, songPath: String) {
        val list = playlists[playlistName] ?: return
        list.remove(songPath)
        save(context)
    }

    fun isSongInPlaylist(playlistName: String, songPath: String): Boolean {
        return playlists[playlistName]?.contains(songPath) == true
    }

    fun deletePlaylist(context: Context, name: String) {
        if (name == "Liked Songs") return // Protection
        playlists.remove(name)
        save(context)
    }

    fun renamePlaylist(context: Context, oldName: String, newName: String): Boolean {
        if (oldName == "Liked Songs") return false
        if (playlists.containsKey(newName)) return false

        val songs = playlists[oldName] ?: return false
        playlists.remove(oldName)
        playlists[newName] = songs
        save(context)
        return true
    }
}