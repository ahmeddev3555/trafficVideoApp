package com.trafficwatch.app.feature.trim

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class TrimProgress {
    object Idle : TrimProgress()
    data class Working(val percent: Int) : TrimProgress()
    data class Done(val outputFile: File) : TrimProgress()
    data class Failed(val error: String) : TrimProgress()
}

@Singleton
class VideoTrimmer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun trim(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long
    ): Flow<TrimProgress> = callbackFlow {
        trySend(TrimProgress.Working(0))

        val clipping = ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(inputFile.absolutePath)
            .setClippingConfiguration(clipping)
            .build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    trySend(TrimProgress.Done(outputFile))
                    close()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    trySend(TrimProgress.Failed(exportException.message ?: "Trim failed"))
                    close()
                }
            })
            .build()

        if (outputFile.exists()) outputFile.delete()

        transformer.start(
            Composition.Builder(EditedMediaItemSequence(listOf(editedMediaItem))).build(),
            outputFile.absolutePath
        )

        // Poll progress
        val progressHolder = androidx.media3.transformer.ProgressHolder()
        var lastPercent = 0
        while (true) {
            val state = transformer.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                val pct = progressHolder.progress
                if (pct != lastPercent) {
                    trySend(TrimProgress.Working(pct))
                    lastPercent = pct
                }
            }
            if (state == Transformer.PROGRESS_STATE_NOT_STARTED) break
            kotlinx.coroutines.delay(200)
        }

        awaitClose { transformer.cancel() }
    }
}
