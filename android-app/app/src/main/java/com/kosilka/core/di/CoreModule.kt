package com.kosilka.core.di

import com.kosilka.core.CoroutineDispatchers
import com.kosilka.core.MessageIdGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideCoroutineDispatchers(): CoroutineDispatchers = CoroutineDispatchers()

    @Provides
    @Singleton
    fun provideMessageIdGenerator(): MessageIdGenerator = MessageIdGenerator()
}
