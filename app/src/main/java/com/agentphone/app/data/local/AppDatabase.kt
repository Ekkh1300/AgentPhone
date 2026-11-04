package com.agentphone.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.agentphone.app.data.local.dao.ChatDao
import com.agentphone.app.data.local.dao.MessageDao
import com.agentphone.app.data.local.dao.ProviderDao
import com.agentphone.app.data.local.dao.ToolLogDao
import com.agentphone.app.data.local.entity.ChatEntity
import com.agentphone.app.data.local.entity.MessageEntity
import com.agentphone.app.data.local.entity.ProviderEntity
import com.agentphone.app.data.local.entity.ToolCallLogEntity

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        ProviderEntity::class,
        ToolCallLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun toolLogDao(): ToolLogDao

    companion object {
        const val NAME = "agentphone.db"
    }
}
