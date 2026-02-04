package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private val allSongs = mutableListOf<Song>()      // Raw data from storage
    private val displaySongs = mutableListOf<Song>()  // Filtered/Sorted songs (for Player)
    private val displayItems = mutableListOf<ListItem>() // Songs + Headers (for UI List)

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null
    private lateinit var adapter: SongAdapter

    // UI Elements
    private lateinit var listContainer: View
    private lateinit var playerContainer: View
    private lateinit var miniPlayerLayout: View

    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView

    private lateinit var ivMiniArt: ImageView
    private lateinit var tvMiniTitle: TextView
    private lateinit var btnMiniPlayPause: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Binding
        listContainer = findViewById(R.id.listContainer)
        playerContainer = findViewById(R.id.playerContainer)
        miniPlayerLayout = findViewById(R.id.miniPlayerLayout)
        ivAlbumArt = findViewById(R.id.ivAlbumArt)
        tvTitle = findViewById(R.id.tvPlayerTitle)
        tvArtist = findViewById(R.id.tvPlayerArtist)
        ivMiniArt = findViewById(R.id.ivMiniArt)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        btnMiniPlayPause = findViewById(R.id.btnMiniPlayPause)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val searchBar = findViewById<EditText>(R.id.etSearch)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        // Setup Recycler
        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager

        // Pass empty list initially
        adapter = SongAdapter(displayItems) { song -> playSong(song) }
        recyclerView.adapter = adapter

        // --- ALPHABET SCROLLER LOGIC ---
        val bubbleCard = findViewById<View>(R.id.cvAlphabetBubble)
        val bubbleText = findViewById<TextView>(R.id.tvAlphabetBubble)
        val alphabetScroller = findViewById<AlphabetScroller>(R.id.alphabetScroller)

        alphabetScroller.onSectionChanged = { letter ->
            bubbleCard.visibility = View.VISIBLE
            bubbleText.text = letter.toString()

            // Find the HEADER index in the mixed list
            val targetIndex = displayItems.indexOfFirst { item ->
                item is ListItem.Header && item.text.equals(letter.toString(), true)
            }

            if (targetIndex != -1) {
                layoutManager.scrollToPositionWithOffset(targetIndex, 0)
            }
        }

        alphabetScroller.onTouchActionUp = { bubbleCard.visibility = View.GONE }

        // Search Logic
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = processData(s.toString())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Standard Listeners
        btnBack.setOnClickListener { closePlayerView() }
        miniPlayerLayout.setOnClickListener {
            playerContainer.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
        }
        btnMiniPlayPause.setOnClickListener {
            if (mediaController?.isPlaying == true) mediaController?.pause() else mediaController?.play()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerContainer.visibility == View.VISIBLE) closePlayerView() else finish()
            }
        })

        if (hasPermission()) loadSongs() else requestPermission()
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            findViewById<PlayerControlView>(R.id.playerControlView).player = mediaController
            setupPlayerListeners()
            updatePlayerUI()
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListeners() {
        val btnPlay = findViewById<ImageButton>(R.id.btn_play)
        val btnPause = findViewById<ImageButton>(R.id.btn_pause)
        btnPlay.setOnClickListener { mediaController?.play() }
        btnPause.setOnClickListener { mediaController?.pause() }

        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
                btnPause.visibility = if (isPlaying) View.VISIBLE else View.GONE
                btnMiniPlayPause.setImageResource(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                updatePlayerUI()
            }
        })
        val isPlaying = mediaController?.isPlaying == true
        btnPlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
        btnPause.visibility = if (isPlaying) View.VISIBLE else View.GONE
    }

    // --- REFACTORED DATA LOADING ---
    private fun loadSongs() {
        allSongs.clear()

        // 1. UPDATED QUERY: Added IS_MUSIC to remove WhatsApp/Recordings
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION
        )

        // Filter: Duration >= 60s AND Must be Music
        val selection = "${MediaStore.Audio.Media.DURATION} >= ? AND ${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val selectionArgs = arrayOf("60000")
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val albumId = cursor.getLong(albumIdCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                allSongs.add(Song(id, title, artist, albumId, uri))
            }
        }

        // Initial Process (No search query)
        processData("")
    }

    // --- HELPER: Mixes Songs and Headers ---
    private fun processData(query: String) {
        displaySongs.clear()

        // 1. Filter Logic
        if (query.isEmpty()) {
            displaySongs.addAll(allSongs)
        } else {
            val lower = query.lowercase()
            displaySongs.addAll(allSongs.filter {
                it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower)
            })
        }

        // 2. Header Insertion Logic
        displayItems.clear()
        var lastChar = ""

        displaySongs.forEach { song ->
            // Get first letter (or # if special char)
            val firstChar = song.title.firstOrNull()?.uppercase() ?: "#"

            // Check if letter changed
            if (firstChar != lastChar && firstChar[0].isLetter()) {
                displayItems.add(ListItem.Header(firstChar))
                lastChar = firstChar
            }
            displayItems.add(ListItem.Audio(song))
        }

        adapter.updateData(displayItems)
    }

    private fun playSong(selectedSong: Song) {
        mediaController?.let { controller ->
            // We use displaySongs (pure list) for the player queue
            val mediaItems = displaySongs.map { song ->
                val artworkUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
                val metadata = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(artworkUri)
                    .build()
                MediaItem.Builder().setUri(song.uri).setMediaMetadata(metadata).build()
            }

            val startIndex = displaySongs.indexOf(selectedSong)
            controller.setMediaItems(mediaItems, startIndex, 0L)
            controller.prepare()
            controller.play()

            playerContainer.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
            miniPlayerLayout.visibility = View.GONE
        }
    }

    private fun updatePlayerUI() {
        val currentMediaItem = mediaController?.currentMediaItem ?: return
        val metadata = currentMediaItem.mediaMetadata

        val title = metadata.title ?: "Unknown"
        val artist = metadata.artist ?: "Unknown"

        tvTitle.text = title
        tvTitle.isSelected = true
        tvArtist.text = artist
        tvMiniTitle.text = title
        tvMiniTitle.isSelected = true

        val artworkUri = metadata.artworkUri
        if (artworkUri != null) {
            Glide.with(this).load(artworkUri).placeholder(android.R.drawable.ic_menu_gallery).into(ivAlbumArt)
            Glide.with(this).load(artworkUri).placeholder(android.R.drawable.ic_menu_gallery).into(ivMiniArt)
        }

        if (playerContainer.visibility != View.VISIBLE) {
            miniPlayerLayout.visibility = View.VISIBLE
        }
    }

    private fun closePlayerView() {
        playerContainer.visibility = View.GONE
        listContainer.visibility = View.VISIBLE
        if (mediaController?.currentMediaItem != null) {
            miniPlayerLayout.visibility = View.VISIBLE
        }
    }

    override fun onStop() {
        super.onStop()
        findViewById<PlayerControlView>(R.id.playerControlView).player = null
        MediaController.releaseFuture(controllerFuture)
    }

    private fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
    }
}