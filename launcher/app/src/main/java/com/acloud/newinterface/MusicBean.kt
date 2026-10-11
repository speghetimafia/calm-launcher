package com.acloud.newinterface

import android.os.Parcel
import android.os.Parcelable

/**
 * Wire-compatible copy of the stock USB music player's track object. Its play-state broadcasts carry one,
 * and Android must be able to unparcel it before any extra can be read (without it the launcher crashed).
 * Field order matches com.acloud.newinterface.MusicBean in com.nwd.android.music.ui.
 */
class MusicBean private constructor(p: Parcel) : Parcelable {
    private val id = p.readString(); private val data = p.readString(); private val size = p.readString()
    val artist: String? = p.readString()
    private val albumId = p.readString(); private val displayName = p.readString()
    val title: String? = p.readString()
    private val duration = p.readString(); private val type = p.readInt(); private val album = p.readString()
    private val parentPath = p.readString(); private val tempArtist = p.readString(); private val tempAlbum = p.readString()

    /** Track title without a file extension (the player often puts the file name in TITLE). */
    val name: String get() = (title?.takeIf { it.isNotBlank() } ?: displayName.orEmpty()).replace(EXT, "")

    override fun writeToParcel(d: Parcel, flags: Int) {
        for (s in listOf(id, data, size, artist, albumId, displayName, title, duration)) d.writeString(s)
        d.writeInt(type)
        for (s in listOf(album, parentPath, tempArtist, tempAlbum)) d.writeString(s)
    }
    override fun describeContents() = 0

    companion object {
        private val EXT = Regex("""\.(mp3|flac|wav|m4a|aac|ogg|wma|ape)$""", RegexOption.IGNORE_CASE)
        @JvmField val CREATOR = object : Parcelable.Creator<MusicBean> {
            override fun createFromParcel(p: Parcel) = MusicBean(p)
            override fun newArray(n: Int) = arrayOfNulls<MusicBean>(n)
        }
    }
}
