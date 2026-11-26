package com.qf.musicplayer.bean

import android.os.Parcel
import android.os.Parcelable

/**
 * The QF firmware music player's track, as it sends it in its
 * "com.qf.musicplayer.action.UPDATE_ACTION" broadcast. Read back by its class
 * name, so this copy lives under the player's own name with the fields in the
 * order its writeToParcel writes them (issue #178). Kept by name in proguard-rules.pro.
 */
class MusicInfoData private constructor(p: Parcel) : Parcelable {
    val name: String? = p.readString()
    val artist: String? = p.readString()
    val path: String? = p.readString()
    val currTime: Int = p.readInt()
    val totalTime: Int = p.readInt()
    /** [PLAYING], or 17 when paused. */
    val curPlayStatus: Int = p.readInt()
    val index: Int = p.readInt()
    val listSize: Int = p.readInt()
    val parentFilePath: String? = p.readString()
    val parentFileName: String? = p.readString()
    val favourite: Int = p.readInt()
    val album: String? = p.readString()

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
        dest.writeString(artist)
        dest.writeString(path)
        dest.writeInt(currTime)
        dest.writeInt(totalTime)
        dest.writeInt(curPlayStatus)
        dest.writeInt(index)
        dest.writeInt(listSize)
        dest.writeString(parentFilePath)
        dest.writeString(parentFileName)
        dest.writeInt(favourite)
        dest.writeString(album)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val PLAYING = 18

        @JvmField
        val CREATOR: Parcelable.Creator<MusicInfoData> = object : Parcelable.Creator<MusicInfoData> {
            override fun createFromParcel(source: Parcel): MusicInfoData = MusicInfoData(source)
            override fun newArray(size: Int): Array<MusicInfoData?> = arrayOfNulls(size)
        }
    }
}
