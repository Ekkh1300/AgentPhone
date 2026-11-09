package com.agentphone.app.di

import com.agentphone.app.telegram.MessageChannel
import com.agentphone.app.telegram.TelegramChannel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TelegramModule {

    @Binds
    @Singleton
    abstract fun bindMessageChannel(channel: TelegramChannel): MessageChannel
}
