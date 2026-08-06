package com.trafficwatch.app.core.ui.components

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
fun LocationMapView(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    headingDegrees: Float? = null
) {
    AndroidView(
        modifier = modifier,
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
                overlays.add(
                    Marker(this).apply {
                        position = point
                        // osmdroid rotates markers counter-clockwise for a positive bearing (verified against
                        // the library's own Marker.draw()/canvas.rotate() behavior), but CompassProvider emits
                        // standard clockwise-from-north compass bearings - negate to render the correct direction.
                        headingDegrees?.let {
                            rotation = -it
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                    }
                )
            }
        },
        update = { mapView ->
            val point = GeoPoint(latitude, longitude)
            mapView.controller.setCenter(point)
            mapView.overlays.clear()
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = point
                    headingDegrees?.let {
                        rotation = -it
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                }
            )
        },
        onRelease = { mapView ->
            mapView.onDetach()
        },
    )
}
