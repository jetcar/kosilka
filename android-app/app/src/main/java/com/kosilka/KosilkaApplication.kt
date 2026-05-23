package com.kosilka

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Annotated with @HiltAndroidApp to trigger Hilt's
 * code generation and set up the application-level dependency injection graph.
 *
 * Also implements [Configuration.Provider] so WorkManager uses Hilt-injected
 * workers (e.g. the 90-day session history cleanup worker).
 */
@HiltAndroidApp
class KosilkaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
