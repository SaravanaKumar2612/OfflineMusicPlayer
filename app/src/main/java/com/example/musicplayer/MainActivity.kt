package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import androidx.core.view.isVisible
import androidx.core.net.toUri

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    // Data Structures
    private val allSongs = mutableListOf<Song>()
    private val displaySongs = mutableListOf<Song>()
    private val displayItems = mutableListOf<ListItem>()

    // Playlist State
    private var currentPlaylistMode: String? = null // Null = All Songs View

    // Player
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null
    private var sleepTimer: android.os.CountDownTimer? = null

    // Adapters
    private lateinit var songsAdapter: SongAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    // UI References
    private lateinit var playerContainer: View
    private lateinit var miniPlayerLayout: View
    private lateinit var rvSongs: RecyclerView
    private lateinit var rvPlaylists: RecyclerView
    private lateinit var etSearchField: EditText
    private lateinit var ivAlbumArt: ImageView
    private lateinit var btnLike: ImageButton
    private lateinit var btnMiniPlayPause: MaterialButton
    private lateinit var ivMiniArt: ImageView

    // ... other variables ...
    private lateinit var gestureDetector: androidx.core.view.GestureDetectorCompat
    private var pendingRenameSong: Song? = null
    private var pendingRenameTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Load Playlists Database
        PlaylistManager.load(this)

        // 2. Bind Views
        playerContainer = findViewById(R.id.playerContainer)
        miniPlayerLayout = findViewById(R.id.miniPlayerLayout)
        rvSongs = findViewById(R.id.rvSongs)
        rvPlaylists = findViewById(R.id.rvPlaylists)
        etSearchField = findViewById(R.id.etSearchField)
        ivAlbumArt = findViewById(R.id.ivAlbumArt)
        btnLike = findViewById(R.id.btnLike)
        btnMiniPlayPause = findViewById(R.id.btnMiniPlayPause)
        ivMiniArt = findViewById(R.id.ivMiniArt)

        // 3. Setup Components
        setupTabs()
        setupRecyclers()
        setupSearch()
        setupButtons()
        setupAlphabetScroller()

        // 4. Handle Back Press (Hierarchy: Player -> Playlist -> Search -> Exit)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerContainer.isVisible) {
                    closePlayerView()
                } else if (currentPlaylistMode != null) {
                    exitPlaylistMode()
                } else if (etSearchField.isVisible) {
                    etSearchField.visibility = View.GONE
                    etSearchField.setText("")
                } else {
                    finish()
                }
            }
        })

        // 5. Load Data
        if (hasPermission()) loadSongs() else requestPermission()
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.addTab(tabLayout.newTab().setText("Songs"))
        tabLayout.addTab(tabLayout.newTab().setText("Playlists"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    rvSongs.visibility = View.VISIBLE
                    rvPlaylists.visibility = View.GONE
                    findViewById<View>(R.id.alphabetScroller).visibility = View.VISIBLE
                    exitPlaylistMode()
                } else {
                    rvSongs.visibility = View.GONE
                    rvPlaylists.visibility = View.VISIBLE
                    findViewById<View>(R.id.alphabetScroller).visibility = View.GONE
                    loadPlaylists()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // --- 1. GESTURE DETECTOR ---
        gestureDetector = androidx.core.view.GestureDetectorCompat(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || e2 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                // Low thresholds for easy swiping
                val SWIPE_THRESHOLD = 30
                val VELOCITY_THRESHOLD = 50

                // Only trigger if Horizontal Swipe > Vertical Scroll
                if (Math.abs(diffX) > Math.abs(diffY) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > VELOCITY_THRESHOLD) {

                    val tabs = findViewById<TabLayout>(R.id.tabLayout)
                    if (diffX > 0) {
                        // Swipe Right -> Go to Songs (Tab 0)
                        if (tabs.selectedTabPosition == 1) tabs.getTabAt(0)?.select()
                    } else {
                        // Swipe Left -> Go to Playlists (Tab 1)
                        if (tabs.selectedTabPosition == 0) tabs.getTabAt(1)?.select()
                    }
                    return true // Event Handled
                }
                return false
            }
        })

        // --- 2. RECYCLER VIEW LISTENER (The Magic Fix) ---
        // This intercepts touches BEFORE the list tries to scroll
        val recyclerListener = object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                // If gestureDetector returns true, it means we swiped.
                // We return true here to tell RecyclerView: "Stop scrolling, I handled this."
                return gestureDetector.onTouchEvent(e)
            }
        }

        // --- 3. STANDARD LISTENER (For empty space) ---
        val frameListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Attach the robust listener to the lists
        findViewById<RecyclerView>(R.id.rvSongs).addOnItemTouchListener(recyclerListener)
        findViewById<RecyclerView>(R.id.rvPlaylists).addOnItemTouchListener(recyclerListener)

        // Attach standard listener to the background container
        findViewById<FrameLayout>(R.id.mainFrame).setOnTouchListener(frameListener)
    }


    private fun setupRecyclers() {
        // SONGS ADAPTER
        rvSongs.layoutManager = LinearLayoutManager(this)
        songsAdapter = SongAdapter(
            items = displayItems,
            onSongClick = { song -> playSong(song) },
            onMenuClick = { view, song -> showSongOptionsMenu(view, song) } // <--- Handle the clicks
        )
        rvSongs.adapter = songsAdapter

        // PLAYLIST ADAPTER
        rvPlaylists.layoutManager = LinearLayoutManager(this)
        playlistAdapter = PlaylistAdapter(
            onClick = { playlistName -> enterPlaylistMode(playlistName) },
            onLongClick = { playlistName -> showPlaylistOptions(playlistName) }
        )
        rvPlaylists.adapter = playlistAdapter
    }

    private fun setupSearch() {
        val btnHeaderSearch = findViewById<ImageView>(R.id.btnHeaderSearch)
        btnHeaderSearch.setOnClickListener {
            if (etSearchField.isVisible) {
                etSearchField.visibility = View.GONE
                etSearchField.setText("") // Clear search
                processData("") // Reset list
            } else {
                etSearchField.visibility = View.VISIBLE
                etSearchField.requestFocus()
            }
        }

        etSearchField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = processData(s.toString())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupAlphabetScroller() {
        val bubbleCard = findViewById<CardView>(R.id.cvAlphabetBubble)
        val bubbleText = findViewById<TextView>(R.id.tvAlphabetBubble)
        val scroller = findViewById<AlphabetScroller>(R.id.alphabetScroller)
        val layoutManager = rvSongs.layoutManager as LinearLayoutManager

        scroller.onSectionChanged = { letter ->
            bubbleCard.visibility = View.VISIBLE
            bubbleText.text = letter.toString()
            val targetIndex = displayItems.indexOfFirst { item ->
                item is ListItem.Header && item.text.equals(letter.toString(), true)
            }
            if (targetIndex != -1) layoutManager.scrollToPositionWithOffset(targetIndex, 0)
        }
        scroller.onTouchActionUp = { bubbleCard.visibility = View.GONE }
    }

    private fun setupButtons() {
        // Top Right Scan Button
        findViewById<ImageView>(R.id.btnHeaderScan).setOnClickListener {
            it.animate().rotationBy(360f).setDuration(500).start()
            loadSongs()
            Toast.makeText(this, "Library Updated", Toast.LENGTH_SHORT).show()
        }

        // Like Button (Heart) Logic
        btnLike.setOnClickListener {

            playerContainer.visibility = View.VISIBLE
            miniPlayerLayout.visibility = View.GONE

            val currentSong = getCurrentSong() ?: return@setOnClickListener
            // Use Song ID as key for simplicity
            val key = currentSong.id.toString()

            if (PlaylistManager.isSongInPlaylist("Liked Songs", key)) {
                PlaylistManager.removeSongFromPlaylist(this, "Liked Songs", key)
                btnLike.setImageResource(R.drawable.ic_favorite)
                Toast.makeText(this, "Removed from Liked Songs", Toast.LENGTH_SHORT).show()
            } else {
                PlaylistManager.addSongToPlaylist(this, "Liked Songs", key)
                btnLike.setImageResource(R.drawable.ic_favorite_filled)
                Toast.makeText(this, "Added to Liked Songs", Toast.LENGTH_SHORT).show()
            }
        }

        // Add To Playlist (Plus Icon) Logic
        findViewById<ImageButton>(R.id.btnAddToPlaylist).setOnClickListener {
            showAddToPlaylistDialog()
        }

        // Mini Player
        miniPlayerLayout.setOnClickListener {
            playerContainer.visibility = View.VISIBLE
            miniPlayerLayout.visibility = View.GONE
            playerContainer.visibility = View.VISIBLE
        }

        findViewById<ImageButton>(R.id.btnMiniClose).setOnClickListener {
            mediaController?.stop()
            mediaController?.clearMediaItems()
            miniPlayerLayout.visibility = View.GONE
        }

        btnMiniPlayPause.setOnClickListener {
            if (mediaController?.isPlaying == true) mediaController?.pause() else mediaController?.play()
        }

        // Full Player Back & Timer
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { closePlayerView() }
        findViewById<ImageButton>(R.id.btnSleepTimer).setOnClickListener { showSleepTimerDialog() }
    }

    // PLAYLIST MANAGEMENT
    private fun loadPlaylists() {
        val names = PlaylistManager.getPlaylists()
        playlistAdapter.submitList(names)
    }

    private fun enterPlaylistMode(playlistName: String) {
        currentPlaylistMode = playlistName

        // Switch UI to Song View
        rvPlaylists.visibility = View.GONE
        rvSongs.visibility = View.VISIBLE

        // Filter songs based on Playlist IDs
        val songIdsInPlaylist = PlaylistManager.getSongsInPlaylist(playlistName)

        displaySongs.clear()
        // Filter allSongs to match IDs in the playlist
        displaySongs.addAll(allSongs.filter { song ->
            songIdsInPlaylist.contains(song.id.toString())
        })

        updateAdapterList()
        Toast.makeText(this, "Playlist: $playlistName", Toast.LENGTH_SHORT).show()
    }

    private fun exitPlaylistMode() {
        currentPlaylistMode = null

        // Check which tab is currently selected
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        if (tabLayout.selectedTabPosition == 1) {
            // We are in Playlists Tab: Hide songs, Show Playlist Names
            rvSongs.visibility = View.GONE
            rvPlaylists.visibility = View.VISIBLE
            // Optional: clear song list so it doesn't flash old data later
            songsAdapter.updateData(emptyList())
        } else {
            // We are in Songs Tab: Just reset to show all songs
            rvSongs.visibility = View.VISIBLE
            rvPlaylists.visibility = View.GONE
            processData("")
        }
    }

    private fun showAddToPlaylistDialog() {
        val playlists = PlaylistManager.getPlaylists().toMutableList()
        playlists.add(0, "+ Create New Playlist")

        MaterialAlertDialogBuilder(this)
            .setTitle("Add to Playlist")
            .setItems(playlists.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreatePlaylistDialog()
                } else {
                    val playlistName = playlists[which]
                    addToPlaylist(playlistName)
                }
            }
            .show()
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this)
        input.hint = "Enter Playlist Name"

        MaterialAlertDialogBuilder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    if (PlaylistManager.createPlaylist(this, name)) {
                        addToPlaylist(name) // Add current song immediately
                    } else {
                        Toast.makeText(this, "Playlist already exists", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addToPlaylist(playlistName: String) {
        val currentSong = getCurrentSong() ?: return
        PlaylistManager.addSongToPlaylist(this, playlistName, currentSong.id.toString())
        Toast.makeText(this, "Added to $playlistName", Toast.LENGTH_SHORT).show()

        // Refresh view if inside that playlist
        if (currentPlaylistMode == playlistName) {
            enterPlaylistMode(playlistName)
        }
    }

    private fun getCurrentSong(): Song? {
        val item = mediaController?.currentMediaItem ?: return null
        val title = item.mediaMetadata.title.toString()
        // Match currently playing title to our list of Song objects
        return allSongs.find { it.title == title }
    }

    //  DATA LOADING & LOGIC
    private fun loadSongs() {
        val songsList = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )

        try {
            val cursor = contentResolver.query(uri, projection, selection, null, null)
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (it.moveToNext()) {
                    val path = it.getString(dataCol) ?: ""
                    val duration = it.getLong(durCol)

                    // --- FILTER JUNK (60s + WhatsApp) ---
                    if (duration < 60000) continue
                    if (path.contains("WhatsApp", true) ||
                        path.contains("Telegram", true) ||
                        path.contains("Recorder", true)) continue

                    val id = it.getLong(idCol)
                    var title = it.getString(titleCol)
                    val displayName = it.getString(nameCol)
                    val artist = it.getString(artistCol) ?: "<Unknown>"
                    val albumId = it.getLong(albumCol)
                    val songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    // 1. Fallback to filename if title is bad
                    if (title.isNullOrEmpty() || title.equals("unknown", true)) {
                        title = displayName.substringBeforeLast(".")
                    }

                    // 2. [THE FIX] OVERRIDE WITH OUR CUSTOM NAME
                    val customName = SongPreferences.getTitle(this, id)
                    if (customName != null) {
                        title = customName
                    }

                    songsList.add(Song(id, title, artist, albumId, songUri))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        runOnUiThread {
            allSongs.clear()
            allSongs.addAll(songsList)
            allSongs.sortBy { it.title.lowercase() }
            processData(etSearchField.text.toString())
        }
    }

    private fun processData(query: String) {
        if (currentPlaylistMode != null) {
            // If in playlist mode, we only filter the CURRENT set of songs
            // (Note: For full playlist search, more logic is needed, skipping for simplicity)
            return
        }

        displaySongs.clear()
        if (query.isEmpty()) {
            displaySongs.addAll(allSongs)
        } else {
            val lower = query.lowercase()
            displaySongs.addAll(allSongs.filter {
                it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower)
            })
        }
        updateAdapterList()
    }

    private fun updateAdapterList() {
        displayItems.clear()
        var lastChar = ""
        displaySongs.forEach { song ->
            val firstChar = song.title.firstOrNull()?.uppercase() ?: "#"
            if (firstChar != lastChar && firstChar[0].isLetter()) {
                displayItems.add(ListItem.Header(firstChar))
                lastChar = firstChar
            }
            displayItems.add(ListItem.Audio(song))
        }
        songsAdapter.updateData(displayItems)
    }

    private fun playSong(selectedSong: Song) {
        mediaController?.let { controller ->
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

            // SHOW Big Player, HIDE Mini Player
            playerContainer.visibility = View.VISIBLE
            miniPlayerLayout.visibility = View.GONE
        }
    }

    //  PLAYER UI UPDATES
    private fun updatePlayerUI() {
        if (isDestroyed || isFinishing) return
        val currentMediaItem = mediaController?.currentMediaItem ?: return
        val metadata = currentMediaItem.mediaMetadata

        // 1. Update Text
        val title = metadata.title ?: "Unknown"
        findViewById<TextView>(R.id.tvPlayerTitle).text = title
        findViewById<TextView>(R.id.tvPlayerArtist).text = metadata.artist ?: "Unknown"
        findViewById<TextView>(R.id.tvMiniTitle).text = title

        // 2. Update Like Button
        val currentSong = allSongs.find { it.title == title.toString() }
        if (currentSong != null) {
            val isLiked = PlaylistManager.isSongInPlaylist("Liked Songs", currentSong.id.toString())
            btnLike.setImageResource(if (isLiked) R.drawable.ic_favorite_filled else R.drawable.ic_favorite)
        }

        // 3. Update Artwork
        val artworkUri = metadata.artworkUri
        if (artworkUri != null) {
            Glide.with(this).load(artworkUri).placeholder(android.R.drawable.ic_menu_gallery).into(findViewById(R.id.ivMiniArt))
            Glide.with(this).asBitmap().load(artworkUri).listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirst: Boolean): Boolean = false
                override fun onResourceReady(resource: Bitmap, model: Any, target: Target<Bitmap>?, dataSource: DataSource, isFirst: Boolean): Boolean {
                    applyGradient(resource)
                    return false
                }
            }).into(ivAlbumArt)
        } else {
            ivAlbumArt.setImageResource(android.R.drawable.ic_menu_gallery)
            ivMiniArt.setImageResource(android.R.drawable.ic_menu_gallery)
            playerContainer.setBackgroundColor(0xFF121212.toInt())
        }

        // 4. CRITICAL FIX: Mutually Exclusive Visibility
        if (playerContainer.visibility == View.VISIBLE) {
            miniPlayerLayout.visibility = View.GONE
        } else {
            miniPlayerLayout.visibility = View.VISIBLE
        }
    }

    private fun applyGradient(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val defaultColor = 0xFF121212.toInt()
            val dominantColor = palette?.getDominantColor(defaultColor) ?: defaultColor
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(dominantColor, 0xFF121212.toInt())
            )
            playerContainer.background = gradient
            window.statusBarColor = dominantColor
        }
    }

    private fun closePlayerView() {
        playerContainer.visibility = View.GONE
        if (mediaController?.currentMediaItem != null) {
            miniPlayerLayout.visibility = View.VISIBLE
        }
    }

    //   LIFECYCLE & PERMISSIONS
    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            findViewById<PlayerControlView>(R.id.playerControlView).player = mediaController

            setupPlayerControlListeners() // Setup Toggle/Next/Prev

            mediaController?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val icon = if(isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    btnMiniPlayPause.setIconResource(icon)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updatePlayerUI()
                }
            })
            updatePlayerUI()
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerControlListeners() {
        val btnPlay = findViewById<ImageButton>(R.id.btn_play)
        val btnPause = findViewById<ImageButton>(R.id.btn_pause)
        val btnToggle = findViewById<ImageButton>(R.id.btn_mode_toggle)
        val btnPrev = findViewById<View>(R.id.btn_prev_custom)
        val btnNext = findViewById<View>(R.id.btn_next_custom)

        btnPlay.setOnClickListener { mediaController?.play() }
        btnPause.setOnClickListener { mediaController?.pause() }

        btnPrev.setOnClickListener { mediaController?.seekToPrevious(); mediaController?.play() }
        btnNext.setOnClickListener {
            if(mediaController?.hasNextMediaItem() == true) { mediaController?.seekToNext(); mediaController?.play() }
        }

        btnToggle.setOnClickListener {
            val controller = mediaController ?: return@setOnClickListener
            val isShuffle = controller.shuffleModeEnabled
            val repeatMode = controller.repeatMode

            if (!isShuffle && repeatMode == Player.REPEAT_MODE_OFF) {
                controller.shuffleModeEnabled = true
                controller.repeatMode = Player.REPEAT_MODE_OFF
                btnToggle.setImageResource(R.drawable.ic_shuffle)
                btnToggle.setColorFilter(getColor(android.R.color.white))
            } else if (isShuffle) {
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ONE
                btnToggle.setImageResource(R.drawable.ic_repeat_one)
                btnToggle.setColorFilter(getColor(android.R.color.white))
            } else if (repeatMode == Player.REPEAT_MODE_ONE) {
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ALL
                btnToggle.setImageResource(R.drawable.ic_repeat)
                btnToggle.setColorFilter(getColor(android.R.color.white))
            } else {
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_OFF
                btnToggle.setImageResource(R.drawable.ic_repeat)
                btnToggle.setColorFilter(getColor(android.R.color.darker_gray))
            }
        }

        // Play/Pause Visibility
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
                btnPause.visibility = if (isPlaying) View.VISIBLE else View.GONE
            }
        })
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

    // --- SLEEP TIMER ---
    private fun showSleepTimerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_sleep_timer, null)
        val tvTimerValue = dialogView.findViewById<TextView>(R.id.tvTimerValue)
        val slider = dialogView.findViewById<Slider>(R.id.sliderTimer)
        val btnTimerIcon = findViewById<ImageButton>(R.id.btnSleepTimer)

        slider.addOnChangeListener { _, value, _ ->
            val minutes = value.toInt()
            tvTimerValue.text = if (minutes == 0) "Off" else "$minutes min"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Set Sleep Timer")
            .setView(dialogView)
            .setPositiveButton("Set") { _, _ ->
                val minutes = slider.value.toInt()
                sleepTimer?.cancel()
                sleepTimer = null
                if (minutes > 0) startSleepTimer(minutes)
                else {
                    Toast.makeText(this, "Timer Disabled", Toast.LENGTH_SHORT).show()
                    btnTimerIcon.setColorFilter(getColor(android.R.color.white))
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Turn Off") { _, _ ->
                sleepTimer?.cancel()
                sleepTimer = null
                Toast.makeText(this, "Timer Disabled", Toast.LENGTH_SHORT).show()
                btnTimerIcon.setColorFilter(getColor(android.R.color.white))
            }
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        val millis = minutes * 60 * 1000L
        val btnTimer = findViewById<ImageButton>(R.id.btnSleepTimer)
        sleepTimer = object : android.os.CountDownTimer(millis, 60000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                mediaController?.pause()
                Toast.makeText(applicationContext, "Music Stopped", Toast.LENGTH_LONG).show()
                btnTimer.setColorFilter(getColor(android.R.color.white))
                sleepTimer = null
            }
        }.start()
        btnTimer.setColorFilter(getColor(android.R.color.holo_green_light))
        Toast.makeText(this, "Timer set for $minutes minutes", Toast.LENGTH_SHORT).show()
    }

    // --- UPDATED ADAPTER ---
    class PlaylistAdapter(
        private val onClick: (String) -> Unit,
        private val onLongClick: (String) -> Unit // <--- New Callback
    ) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

        private val items = mutableListOf<String>()

        fun submitList(newItems: List<String>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val name = items[position]
            val text = holder.itemView as TextView
            text.text = name
            text.setTextColor(0xFFFFFFFF.toInt())

            // Normal Click -> Open
            text.setOnClickListener { onClick(name) }

            // Long Click -> Show Options
            text.setOnLongClickListener {
                onLongClick(name)
                true // Consume event
            }
        }

        override fun getItemCount(): Int = items.size

        class Holder(v: View) : RecyclerView.ViewHolder(v)
    }

    private fun showPlaylistOptions(playlistName: String) {
        if (playlistName == "Liked Songs") {
            Toast.makeText(this, "Cannot modify Default Playlist", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("Rename", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle("Options for '$playlistName'")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(playlistName)
                    1 -> showDeleteConfirmation(playlistName)
                }
            }
            .show()
    }

    private fun showRenamePlaylistDialog(oldName: String) {
        val input = EditText(this)
        input.setText(oldName)

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Playlist")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotEmpty() && newName != oldName) {
                    if (PlaylistManager.renamePlaylist(this, oldName, newName)) {
                        loadPlaylists() // Refresh List
                        Toast.makeText(this, "Renamed to $newName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Name already exists", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(playlistName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Playlist?")
            .setMessage("Are you sure you want to delete '$playlistName'? Songs will not be deleted from device.")
            .setPositiveButton("Delete") { _, _ ->
                PlaylistManager.deletePlaylist(this, playlistName)
                loadPlaylists() // Refresh List
                Toast.makeText(this, "Playlist Deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- SONG MENU LOGIC (Rename / Delete) ---

    private fun showSongOptionsMenu(view: View, song: Song) {
        val popup = PopupMenu(this, view)

        // Option 1: Rename (Always available)
        popup.menu.add("Rename")

        // Option 2: Remove from Playlist (Only if inside a playlist)
        if (currentPlaylistMode != null && currentPlaylistMode != "Liked Songs") {
            popup.menu.add("Remove from Playlist")
        }

        // Option 3: Delete from Device (Always available)
        popup.menu.add("Delete from Device")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Rename" -> showRenameSongDialog(song)
                "Remove from Playlist" -> {
                    currentPlaylistMode?.let { playlistName ->
                        PlaylistManager.removeSongFromPlaylist(this, playlistName, song.id.toString())
                        // Refresh the view
                        enterPlaylistMode(playlistName)
                        Toast.makeText(this, "Removed from $playlistName", Toast.LENGTH_SHORT).show()
                    }
                }
                "Delete from Device" -> showDeleteSongConfirmation(song)
            }
            true
        }
        popup.show()
    }

    private fun showRenameSongDialog(song: Song) {
        val input = EditText(this)
        input.setText(song.title)

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Song")
            .setMessage("Note: This changes the Display Name only.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotEmpty()) {
                    updateSongTitle(song, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSongTitle(song: Song, newTitle: String) {
        // 1. Save to Local App Storage (Guaranteed Success)
        SongPreferences.saveTitle(this, song.id, newTitle)

        // 2. Update Memory Immediately
        song.title = newTitle

        // 3. Show Success
        Toast.makeText(this, "Renamed to $newTitle", Toast.LENGTH_SHORT).show()

        // 4. Refresh List
        loadSongs()

        // OPTIONAL: Try to update system file too (Best Effort), but don't worry if it fails.
        // This keeps it compatible with other apps if possible.
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
            val values = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, newTitle)
            }
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Ignore errors here. We already saved it locally!
        }
    }

    private fun showDeleteSongConfirmation(song: Song) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete File?")
            .setMessage("This will permanently delete '${song.title}' from your phone's storage.")
            .setPositiveButton("Delete") { _, _ ->
                deleteSongFromDevice(song)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSongFromDevice(song: Song) {
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)

            // 1. Try standard delete
            val rows = contentResolver.delete(uri, null, null)

            if (rows > 0) {
                Toast.makeText(this, "File Deleted", Toast.LENGTH_SHORT).show()
                loadSongs() // Refresh UI
            } else {
                Toast.makeText(this, "Could not delete file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            // 2. Android 10+ Security Handling
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Request permission to delete
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                try {
                    startIntentSenderForResult(pendingIntent.intentSender, 102, null, 0, 0, 0, null)
                } catch (ex: Exception) {
                    Toast.makeText(this, "Error requesting permission", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Handle the Permission Result for Android 11+ Deletion
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // 102 = DELETE Permission Result
        if (requestCode == 102 && resultCode == RESULT_OK) {
            Toast.makeText(this, "File Deleted", Toast.LENGTH_SHORT).show()
            loadSongs()
        }

        // 103 = RENAME Permission Result
        if (requestCode == 103 && resultCode == RESULT_OK) {
            // User clicked "Allow". Retry the update!
            val song = pendingRenameSong
            val title = pendingRenameTitle

            if (song != null && title != null) {
                // This call will now succeed because the system granted temporary write access
                updateSongTitle(song, title)
            }
        } else if (requestCode == 103) {
            // User clicked "Deny"
            pendingRenameSong = null
            pendingRenameTitle = null
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        var path: String? = null
        val cursor = contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) path = it.getString(0)
        }
        return path
    }
}

// --- PASTE AT THE BOTTOM OF MainActivity.kt ---
object SongPreferences {
    private const val PREFS_NAME = "SongTitles"

    fun saveTitle(context: Context, songId: Long, newTitle: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(songId.toString(), newTitle).apply()
    }

    fun getTitle(context: Context, songId: Long): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(songId.toString(), null)
    }
}