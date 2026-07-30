package com.trafficwatch.app.core.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private const val DEFAULT_ZOOM = 17.0

/**
 * A small, non-interactive (no pan/zoom) OpenStreetMap view centered on [latitude]/[longitude]
 * with a single pin marker - just enough to make a report's location immediately visible,
 * not a navigable map. Uses osmdroid (see build.gradle.kts) rather than Google Maps - no API
 * key/billing needed.
 */
@Composable
fun LocationMapView(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth().height(150.dp),
        factory = { context ->
            MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setMultiTouchControls(false)
                setOnTouchListener { _, _ -> true }
                val point = GeoPoint(latitude, longitude)
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(point)
                overlays.add(Marker(this).apply { position = point })
            }
        },
        update = { mapView ->
            val point = GeoPoint(latitude, longitude)
            mapView.controller.setCenter(point)
            mapView.overlays.clear()
            mapView.overlays.add(Marker(mapView).apply { position = point })
        },
        onRelease = { mapView ->
            mapView.onDetach()
        },
    )
}
