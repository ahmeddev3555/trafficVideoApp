package com.trafficwatch.app.core.domain.usecase

import com.trafficwatch.app.core.util.FileUtil
import com.trafficwatch.app.feature.trim.TrimProgress
import com.trafficwatch.app.feature.trim.VideoTrimmer
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject

class TrimVideoUseCase @Inject constructor(
    private val videoTrimmer: VideoTrimmer,
    private val fileUtil: FileUtil
) {
    operator fun invoke(inputFile: File, startMs: Long, endMs: Long): Flow<TrimProgress> {
        val outputFile = fileUtil.newTrimmedVideoFile()
        return videoTrimmer.trim(inputFile, outputFile, startMs, endMs)
    }
}
