package com.meshlink.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class, PeerEntity::class, EventEntity::class, VoiceAssetEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun eventDao(): EventDao
    abstract fun voiceAssetDao(): VoiceAssetDao

    companion object {
        @Volatile private var INSTANCE: MeshDatabase? = null
        fun getDatabase(context: Context): MeshDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, MeshDatabase::class.java, "meshlink_database"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
