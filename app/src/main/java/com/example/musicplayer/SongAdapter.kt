package com.example.musicplayer

import android.content.ContentUris
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.material3.ListItem
import androidx.recyclerview.widget.RecyclerView
import com.android.volley.Header
import com.bumptech.glide.Glide

// 1. We defined the callback to handle clicks AND menu clicks
class SongAdapter(
    private var items: List<ListItem>,
    private val onSongClick: (Song) -> Unit,
    private val onMenuClick: (View, Song) -> Unit // <--- NEW Callback for 3 dots
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun updateData(newItems: List<ListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is ListItem.Header) 0 else 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            // Header View
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            HeaderHolder(view)
        } else {
            // Song View (Using our new layout)
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
            SongHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]

        if (holder is HeaderHolder && item is ListItem.Header) {
            val textView = holder.itemView.findViewById<TextView>(android.R.id.text1)
            textView.text = item.text
            textView.setTextColor(0xFF03DAC5.toInt()) // Teal Color for headers
            textView.textSize = 18f
            textView.setPadding(32, 16, 16, 16)
            holder.itemView.isEnabled = false
        }
        else if (holder is SongHolder && item is ListItem.Audio) {
            val song = item.song
            holder.tvTitle.text = song.title
            holder.tvArtist.text = song.artist

            // Load Album Art
            val artworkUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
            Glide.with(holder.itemView.context)
                .load(artworkUri)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(holder.ivArt)

            // Click on the whole row -> Play
            holder.itemView.setOnClickListener { onSongClick(song) }

            // Click on the 3 dots -> Show Menu
            holder.btnMenu.setOnClickListener { view ->
                onMenuClick(view, song)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val textView: TextView = v.findViewById(android.R.id.text1)
        var textColor: Int
            get() = textView.currentTextColor
            set(value) { textView.setTextColor(value) }
    }

    class SongHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvItemTitle)
        val tvArtist: TextView = v.findViewById(R.id.tvItemArtist)
        val ivArt: ImageView = v.findViewById(R.id.ivItemArt)
        val btnMenu: ImageButton = v.findViewById(R.id.btnItemMenu)
    }
}

sealed class ListItem {
    data class Audio(val song: Song) : ListItem()
    data class Header(val text: String) : ListItem()
}

