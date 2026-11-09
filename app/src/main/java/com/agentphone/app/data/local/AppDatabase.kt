package com.agentphone.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentphone.app.data.local.dao.AutoReplyDao
import com.agentphone.app.data.local.dao.AwayDao
import com.agentphone.app.data.local.dao.ChatDao
import com.agentphone.app.data.local.dao.MessageDao
import com.agentphone.app.data.local.dao.ProviderDao
import com.agentphone.app.data.local.dao.ToolLogDao
import com.agentphone.app.data.local.entity.AutoReplyEntity
import com.agentphone.app.data.local.entity.AwayChatRuleEntity
import com.agentphone.app.data.local.entity.AwayDecisionEntity
import com.agentphone.app.data.local.entity.AwayPendingEntity
import com.agentphone.app.data.local.entity.AwayWatchEntity
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
        AutoReplyEntity::class,
        AwayChatRuleEntity::class,
        AwayPendingEntity::class,
        AwayDecisionEntity::class,
        AwayWatchEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun toolLogDao(): ToolLogDao
    abstract fun autoReplyDao(): AutoReplyDao
    abstract fun awayDao(): AwayDao

    companion object {
        const val NAME = "agentphone.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `auto_replies` (" +
                        "`id` TEXT NOT NULL, " +
                        "`address` TEXT NOT NULL, " +
                        "`contactName` TEXT, " +
                        "`incomingBody` TEXT NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, " +
                        "`dueAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`replyText` TEXT, " +
                        "`sentAt` INTEGER, " +
                        "`reason` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_auto_replies_address` " +
                        "ON `auto_replies` (`address`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_auto_replies_receivedAt` " +
                        "ON `auto_replies` (`receivedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_auto_replies_status` " +
                        "ON `auto_replies` (`status`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `away_chat_rules` (" +
                        "`chatId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`chatType` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`disclosure` INTEGER, " +
                        "`sendToAi` INTEGER NOT NULL, " +
                        "`instructions` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chatId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_away_chat_rules_updatedAt` " +
                        "ON `away_chat_rules` (`updatedAt`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `away_pending` (" +
                        "`id` TEXT NOT NULL, " +
                        "`chatId` INTEGER NOT NULL, " +
                        "`chatTitle` TEXT NOT NULL, " +
                        "`chatType` TEXT NOT NULL, " +
                        "`senderId` INTEGER NOT NULL, " +
                        "`senderName` TEXT NOT NULL, " +
                        "`messageIds` TEXT NOT NULL, " +
                        "`lastMessageId` INTEGER NOT NULL, " +
                        "`firstMessageAt` INTEGER NOT NULL, " +
                        "`lastMessageAt` INTEGER NOT NULL, " +
                        "`dueAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_away_pending_chatId` " +
                        "ON `away_pending` (`chatId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_away_pending_dueAt` " +
                        "ON `away_pending` (`dueAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_away_pending_status` " +
                        "ON `away_pending` (`status`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `away_decisions` (" +
                        "`id` TEXT NOT NULL, " +
                        "`chatId` INTEGER NOT NULL, " +
                        "`chatTitle` TEXT NOT NULL, " +
                        "`chatType` TEXT NOT NULL, " +
                        "`senderId` INTEGER NOT NULL, " +
                        "`senderName` TEXT NOT NULL, " +
                        "`incomingText` TEXT NOT NULL, " +
                        "`decision` TEXT NOT NULL, " +
                        "`replyText` TEXT, " +
                        "`confidence` REAL NOT NULL, " +
                        "`reason` TEXT, " +
                        "`urgency` TEXT NOT NULL, " +
                        "`risksJson` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`disclosure` INTEGER NOT NULL, " +
                        "`triggerMessageId` INTEGER NOT NULL, " +
                        "`replyToMessageId` INTEGER, " +
                        "`sentMessageId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`sentAt` INTEGER, " +
                        "`error` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_away_decisions_chatId` " +
                        "ON `away_decisions` (`chatId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_away_decisions_createdAt` " +
                        "ON `away_decisions` (`createdAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_away_decisions_status` " +
                        "ON `away_decisions` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_away_decisions_triggerMessageId` " +
                        "ON `away_decisions` (`triggerMessageId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `away_watchlist` (" +
                        "`chatId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chatId`))",
                )
            }
        }
    }
}
