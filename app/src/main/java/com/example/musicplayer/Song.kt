package com.example.musicplayer

data class Song(
    val id: Long,
    var title: String,
    val artist: String,
    val albumId: Long,
    val uri: android.net.Uri
)