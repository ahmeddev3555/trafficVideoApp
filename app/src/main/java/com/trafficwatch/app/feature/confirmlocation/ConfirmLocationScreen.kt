package com.trafficwatch.app.feature.confirmlocation

import android.graphics.Color
import android.location.Location
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import com.trafficwatch.app.core.util.pointAtBearingAndDistance

private const val DEFAULT_ZOOM = 17.0

/**
 * Full-screen, interactive (pan/zoom/drag) map for confirming or correcting a report's
 * location when its captured GPS accuracy was weak. Distinct from [com.trafficwatch.app.core.ui.components.LocationMapView],
 * which is deliberately non-interactive - this screen needs real dragging and a boundary
 * overlay, different enough interaction modes that sharing one component would tangle both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmLocationScreen(
    initialLatitude: Double,
    initialLongitude: Double,
    maxRadiusMeters: Double,
    onConfirm: (latitude: Double, longitude: Double) -> Unit,
    onNavigateBack: () -> Unit
) {
    val originPoint = remember { GeoPoint(initialLatitude, initialLongitude) }
    val confirmedPosition = remember { mutableStateOf(GeoPoint(initialLatitude, initialLongitude)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm Location") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val mapView = MapView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setMultiTouchControls(true)
                        }

                        val boundary = Polygon(mapView).apply {
                            points = Polygon.pointsAsCircle(originPoint, maxRadiusMeters)
                            fillPaint.color = Color.argb(30, 255, 0, 0)
                            outlinePaint.color = Color.RED
                            outlinePaint.strokeWidth = 3f
                        }
                        mapView.overlays.add(boundary)

                        // Fixed DEFAULT_ZOOM alone can't guarantee the boundary circle is fully
                        // visible for large accuracy radii, so fit the view to the boundary's
                        // actual bounding box; setZoom/setCenter still run first as a reasonable
                        // fallback and to ensure a sane starting center (zoomToBoundingBox is a
                        // MapView method, not IMapController - osmdroid has no Polygon.bounds,
                        // so the box is derived from the boundary's own points).
                        mapView.controller.setZoom(DEFAULT_ZOOM)
                        mapView.controller.setCenter(originPoint)
                        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(boundary.points), false)

                        val marker = Marker(mapView).apply {
                            position = confirmedPosition.value
                            isDraggable = true
                            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                                override fun onMarkerDrag(marker: Marker) = Unit
                                override fun onMarkerDragStart(marker: Marker) = Unit
                                override fun onMarkerDragEnd(marker: Marker) {
                                    val results = FloatArray(2)
                                    Location.distanceBetween(
                                        originPoint.latitude, originPoint.longitude,
                                        marker.position.latitude, marker.position.longitude,
                                        results
                                    )
                                    val distanceMeters = results[0]
                                    val bearingDegrees = results[1]
                                    if (distanceMeters > maxRadiusMeters) {
                                        val clamped = pointAtBearingAndDistance(
                                            originPoint, bearingDegrees.toDouble(), maxRadiusMeters
                                        )
                                        marker.position = clamped
                                        mapView.invalidate()
                                    }
                                    // Defensive copy - marker.position is the Marker's own mutable
                                    // GeoPoint, so storing it directly would alias it instead of
                                    // capturing a snapshot.
                                    confirmedPosition.value = GeoPoint(marker.position)
                                }
                            })
                        }
                        mapView.overlays.add(marker)

                        mapView
                    },
                    onRelease = { mapView -> mapView.onDetach() },
                )
            }

            Text(
                "Long-press and drag the pin to adjust your location",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = { onConfirm(confirmedPosition.value.latitude, confirmedPosition.value.longitude) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("Confirm Position") }
        }
    }
}
