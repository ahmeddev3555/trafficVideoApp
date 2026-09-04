package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.Gson
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.RotationSample

/**
 * The one place a captured sample series becomes its wire JSON string. Empty list -> null,
 * so the multipart field is omitted entirely (the "presence, not sentinel" convention shared
 * with compassHeadingDegrees). Persisted on the report row AND sent by [UploadWorker] so a
 * first upload and any later retry transmit byte-identical data.
 */
object SampleJson {
    private val gson = Gson()

    fun location(samples: List<LocationData>): String? =
        samples.takeIf { it.isNotEmpty() }?.let { gson.toJson(it.map(LocationData::toSampleDto)) }

    fun rotation(samples: List<RotationSample>): String? =
        samples.takeIf { it.isNotEmpty() }?.let { gson.toJson(it.map(RotationSample::toSampleDto)) }
}
