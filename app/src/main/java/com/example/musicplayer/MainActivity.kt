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
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import androidx.palette.graphics.Palette
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException

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

    private lateinit var btnMiniPlayPause: com.google.android.material.button.MaterialButton

    private var sleepTimer: android.os.CountDownTimer? = null

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

        // Sleep Timer Logic
        val btnSleepTimer = findViewById<ImageButton>(R.id.btnSleepTimer)
        btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val searchBar = findViewById<EditText>(R.id.etSearch)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        // --- NEW: SCAN BUTTON LOGIC ADDED HERE ---
        val btnScan = findViewById<ImageButton>(R.id.btnScan)
        btnScan.setOnClickListener {
            // 1. Visual feedback: Spin the icon
            btnScan.animate().rotationBy(360f).setDuration(500).start()

            // 2. Reload data
            loadSongs()

            // 3. User feedback
            android.widget.Toast.makeText(this, "Scanning for new songs...", android.widget.Toast.LENGTH_SHORT).show()
        }
        // ----------------------------------------

        // Setup Recycler
        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager

        // Pass empty list initially
        adapter = SongAdapter(displayItems) { song -> playSong(song) }
        recyclerView.adapter = adapter

        // ALPHABET SCROLLER LOGIC
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

        val btnMiniClose = findViewById<ImageButton>(R.id.btnMiniClose)
        btnMiniClose.setOnClickListener {
            // 1. Stop playback
            mediaController?.pause()

            // 2. Clear the current song from memory so it doesn't auto-resume
            mediaController?.stop()
            mediaController?.clearMediaItems()

            // 3. Hide the Mini Player
            miniPlayerLayout.visibility = View.GONE
        }

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
        val btnToggle = findViewById<ImageButton>(R.id.btn_mode_toggle)

        // 1. Find our Custom Buttons
        val btnPrev = findViewById<View>(R.id.btn_prev_custom)
        val btnNext = findViewById<View>(R.id.btn_next_custom)

        // 2. Play/Pause Logic
        btnPlay.setOnClickListener { mediaController?.play() }
        btnPause.setOnClickListener { mediaController?.pause() }

        // 3. Manual Previous/Next Logic
        btnPrev.setOnClickListener {
            mediaController?.seekToPrevious()
            mediaController?.play()
        }
        btnNext.setOnClickListener {
            if (mediaController?.hasNextMediaItem() == true) {
                mediaController?.seekToNext()
                mediaController?.play()
            }
        }


        // 4. Mode Toggle Logic (Shuffle -> Loop 1 -> Loop All -> Off)
        btnToggle.setOnClickListener {
            val controller = mediaController ?: return@setOnClickListener

            // Get current state
            val isShuffle = controller.shuffleModeEnabled
            val repeatMode = controller.repeatMode

            if (!isShuffle && repeatMode == Player.REPEAT_MODE_OFF) {
                // STATE 1: Switch to SHUFFLE
                controller.shuffleModeEnabled = true
                controller.repeatMode = Player.REPEAT_MODE_OFF

                btnToggle.setImageResource(R.drawable.ic_shuffle)
                btnToggle.setColorFilter(getColor(android.R.color.white)) // White

            } else if (isShuffle) {
                // STATE 2: Switch to LOOP ONE
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ONE

                // Use your custom "1" icon or standard repeat with a Green tint if you don't have it
                btnToggle.setImageResource(R.drawable.ic_repeat_one)
                btnToggle.setColorFilter(getColor(android.R.color.white))

            } else if (repeatMode == Player.REPEAT_MODE_ONE) {
                // STATE 3: Switch to LOOP ALL
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ALL

                btnToggle.setImageResource(R.drawable.ic_repeat)
                btnToggle.setColorFilter(getColor(android.R.color.white))

            } else {
                // STATE 4: Switch OFF (Reset)
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_OFF

                btnToggle.setImageResource(R.drawable.ic_repeat)
                btnToggle.setColorFilter(getColor(android.R.color.darker_gray)) // Greyed out
            }
        }

        // 5. Listener
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Update Main Player Buttons
                btnPlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
                btnPause.visibility = if (isPlaying) View.VISIBLE else View.GONE

                // Update Mini Player Button (Using System Icons)
                val miniIconRes = if (isPlaying)
                    android.R.drawable.ic_media_pause
                else
                    android.R.drawable.ic_media_play

                btnMiniPlayPause.setIconResource(miniIconRes)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                updatePlayerUI()
            }
        })

        // Initial State
        val isPlaying = mediaController?.isPlaying == true
        btnPlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
        btnPause.visibility = if (isPlaying) View.VISIBLE else View.GONE
    }

    // --- REFACTORED DATA LOADING ---
    private fun loadSongs() {
        allSongs.clear()

        // 1. Projection: We explicitly ask for the DATA (File Path) to check folder names
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA // <--- Needed to check file path
        )

        // 2. Strict Selection: Must be Music AND at least 30 seconds long
        // (lowered to 30s to catch short songs, but filter handles the junk)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf("30000")
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        try {
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA) // Path column

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol).lowercase()

                    // --- 3. THE SPAM FILTER ---
                    // If the folder name contains any of these, SKIP IT.
                    if (path.contains("whatsapp") ||
                        path.contains("telegram") ||
                        path.contains("recorder") ||
                        path.contains("recordings") ||
                        path.contains("call_rec") ||
                        path.contains("voice")) {
                        continue
                    }

                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val albumId = cursor.getLong(albumIdCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    // Final Check: Filter out strange files that have no artist AND no title
                    if (title.contains("AUD-") && artist.contains("<unknown>")) {
                        continue
                    }

                    allSongs.add(Song(id, title, artist, albumId, uri))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Refresh List
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

        if (isDestroyed || isFinishing) return

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
            // 1. Mini Player
            Glide.with(this).load(artworkUri)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(ivMiniArt)

            // 2. Full Player with Gradient
            Glide.with(this)
                .asBitmap()
                .load(artworkUri)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>,
                        isFirstResource: Boolean
                    ): Boolean {
                        // Reset background to dark if image fails
                        playerContainer.setBackgroundColor(0xFF121212.toInt())
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: Target<Bitmap>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        // Image loaded! Set the gradient
                        applyGradient(resource)
                        return false                    }
                })
                .into(ivAlbumArt)

        } else {
            ivAlbumArt.setImageResource(android.R.drawable.ic_menu_gallery)
            ivMiniArt.setImageResource(android.R.drawable.ic_menu_gallery)
            playerContainer.setBackgroundColor(0xFF121212.toInt())
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

    private fun applyGradient(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            // Try to get the "Dominant" or "Vibrant" color. Default to Dark Grey if fails.
            val defaultColor = 0xFF121212.toInt()
            val dominantColor = palette?.getDominantColor(defaultColor) ?: defaultColor

            // Create a Gradient: From Dominant Color (Top) -> Dark Grey (Bottom)
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(dominantColor, 0xFF121212.toInt())
            )

            // Apply it to the Player Container
            playerContainer.background = gradient

            // Optional: Also color the window status bar to match!
            window.statusBarColor = dominantColor
        }
    }

    private fun showSleepTimerDialog() {
        // 1. Inflate the custom layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_sleep_timer, null)
        val tvTimerValue = dialogView.findViewById<TextView>(R.id.tvTimerValue)
        val slider = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderTimer)
        val btnTimerIcon = findViewById<ImageButton>(R.id.btnSleepTimer)

        // 2. Setup Slider Logic (Update text as you drag)
        slider.addOnChangeListener { _, value, _ ->
            val minutes = value.toInt()
            if (minutes == 0) {
                tvTimerValue.text = "Off"
            } else {
                tvTimerValue.text = "$minutes min"
            }
        }

        // 3. Build the Dialog
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Set Sleep Timer")
            .setView(dialogView)
            .setPositiveButton("Set") { _, _ ->
                val minutes = slider.value.toInt()

                // Reset any previous timer first
                sleepTimer?.cancel()
                sleepTimer = null

                if (minutes > 0) {
                    startSleepTimer(minutes)
                } else {
                    // If user set slider to 0, consider it "Turn Off"
                    android.widget.Toast.makeText(this, "Timer Disabled", android.widget.Toast.LENGTH_SHORT).show()
                    btnTimerIcon.setColorFilter(getColor(android.R.color.white))
                }
            }
            .setNegativeButton("Cancel", null) // Do nothing, just close
            .setNeutralButton("Turn Off") { _, _ ->
                // Dedicated "Turn Off" button for quick access
                sleepTimer?.cancel()
                sleepTimer = null
                android.widget.Toast.makeText(this, "Timer Disabled", android.widget.Toast.LENGTH_SHORT).show()
                btnTimerIcon.setColorFilter(getColor(android.R.color.white))
            }
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        val millis = minutes * 60 * 1000L
        val btnTimer = findViewById<ImageButton>(R.id.btnSleepTimer)

        sleepTimer = object : android.os.CountDownTimer(millis, 60000) {
            override fun onTick(millisUntilFinished: Long) {
                // (Optional) You could log this if you want to debug
            }

            override fun onFinish() {
                mediaController?.pause()
                android.widget.Toast.makeText(applicationContext, "Sleep Timer: Music Stopped", android.widget.Toast.LENGTH_LONG).show()
                btnTimer.setColorFilter(getColor(android.R.color.white))
                sleepTimer = null
            }
        }.start()

        // Turn icon Green to indicate active timer
        btnTimer.setColorFilter(getColor(android.R.color.holo_green_light))
        android.widget.Toast.makeText(this, "Timer set for $minutes minutes", android.widget.Toast.LENGTH_SHORT).show()
    }
}