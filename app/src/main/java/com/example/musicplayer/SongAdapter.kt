package com.example.musicplayer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// 1. Define the types of items our list can hold
sealed class ListItem {
    data class Header(val text: String) : ListItem()
    data class Audio(val song: Song) : ListItem()
}

class SongAdapter(
    private var items: List<ListItem>, // Takes the mixed list
    private val onSongClick: (Song) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SONG = 1
    }

    // Function to update data without creating a new Adapter
    fun updateData(newItems: List<ListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.Header -> TYPE_HEADER
            is ListItem.Audio -> TYPE_SONG
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
            SongViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item.text)
            is ListItem.Audio -> (holder as SongViewHolder).bind(item.song, onSongClick)
        }
    }

    override fun getItemCount() = items.size

    // --- ViewHolders ---
    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.tvHeader)
        fun bind(text: String) {
            textView.text = text
        }
    }

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSongTitle)
        val artist: TextView = view.findViewById(R.id.tvArtist)

        fun bind(song: Song, onClick: (Song) -> Unit) {
            title.text = song.title
            title.setTextColor(Color.WHITE)

            artist.text = if(song.artist == "<unknown>") "Unknown Artist" else song.artist
            artist.setTextColor(Color.LTGRAY)

            itemView.setOnClickListener { onClick(song) }
        }
    }
}