package com.trafficwatch.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration as OsmConfiguration
import javax.inject.Inject

@HiltAndroidApp
class TrafficWatchApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        // osmdroid requires a user agent to be set before any MapView is created, or
        // OpenStreetMap's tile servers may reject requests.
        OsmConfiguration.getInstance().userAgentValue = packageName
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Every `AsyncImage`/Coil request in the app goes through this loader, built from the
     * same [OkHttpClient] Retrofit uses (see `NetworkModule`) - so requests for the
     * wrong-way-frame image (behind JWT auth, like every other endpoint) automatically
     * carry the same Authorization header `AuthInterceptor` already attaches, with no
     * per-call wiring needed.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .build()
}
