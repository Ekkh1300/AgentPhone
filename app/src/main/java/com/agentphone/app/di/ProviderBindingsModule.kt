package com.agentphone.app.di

import com.agentphone.app.data.remote.AndroidImageEncoder
import com.agentphone.app.data.remote.DefaultProviderAdapterRegistry
import com.agentphone.app.data.remote.ImageEncoder
import com.agentphone.app.data.remote.OpenAiCompatibleProvider
import com.agentphone.app.domain.provider.ProviderAdapter
import com.agentphone.app.domain.provider.ProviderAdapterRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderBindingsModule {

    @Binds
    @IntoSet
    abstract fun bindOpenAiCompatibleProvider(
        provider: OpenAiCompatibleProvider,
    ): ProviderAdapter

    @Binds
    abstract fun bindImageEncoder(encoder: AndroidImageEncoder): ImageEncoder

    @Binds
    abstract fun bindProviderAdapterRegistry(
        registry: DefaultProviderAdapterRegistry,
    ): ProviderAdapterRegistry
}
