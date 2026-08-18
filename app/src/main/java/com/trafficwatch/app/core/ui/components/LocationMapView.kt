package com.trafficwatch.app.core.ui.components

import android.util.Log
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.trafficwatch.app.R
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private const val DEFAULT_ZOOM = 17.0
private const val DEBUG_TAG = "MapSizeDebug"
private var debugFactoryCallCount = 0
private var debugUpdateCallCount = 0

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
            debugFactoryCallCount++
            Log.d(DEBUG_TAG, "FACTORY call #$debugFactoryCallCount - creating new MapView instance")
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
                        icon = ContextCompat.getDrawable(context, R.drawable.ic_location_arrow)
                        // A direction arrow (unlike osmdroid's default teardrop pin) has no
                        // "tip touches the ground" convention - it must stay centered on the
                        // coordinate whether or not a heading is available yet.
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        // osmdroid rotates markers counter-clockwise for a positive bearing (verified against
                        // the library's own Marker.draw()/canvas.rotate() behavior), but CompassProvider emits
                        // standard clockwise-from-north compass bearings - negate to render the correct direction.
                        headingDegrees?.let { rotation = -it }
                    }
                )
                addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
                    if (r - l != or_ - ol || b - t != ob - ot) {
                        Log.d(DEBUG_TAG, "LAYOUT CHANGED - old size ${or_-ol}x${ob-ot} -> new size ${r-l}x${b-t}")
                    }
                }
            }
        },
        update = { mapView ->
            debugUpdateCallCount++
            Log.d(
                DEBUG_TAG,
                "UPDATE call #$debugUpdateCallCount - lat=$latitude lon=$longitude heading=$headingDegrees " +
                    "currentMeasuredSize=${mapView.width}x${mapView.height}"
            )
            val point = GeoPoint(latitude, longitude)
            mapView.controller.setCenter(point)
            mapView.overlays.clear()
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = point
                    icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_location_arrow)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    headingDegrees?.let { rotation = -it }
                }
            )
        },
        onRelease = { mapView ->
            Log.d(DEBUG_TAG, "RELEASE - MapView being detached/destroyed")
            mapView.onDetach()
        },
    )
}
