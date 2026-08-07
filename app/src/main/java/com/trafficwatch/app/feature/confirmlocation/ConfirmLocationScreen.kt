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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
                        MapView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setMultiTouchControls(true)
                            controller.setZoom(DEFAULT_ZOOM)
                            controller.setCenter(originPoint)

                            val boundary = Polygon(this).apply {
                                points = Polygon.pointsAsCircle(originPoint, maxRadiusMeters)
                                fillPaint.color = Color.argb(30, 255, 0, 0)
                                outlinePaint.color = Color.RED
                                outlinePaint.strokeWidth = 3f
                            }
                            overlays.add(boundary)

                            val marker = Marker(this).apply {
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
                                            invalidate()
                                        }
                                        confirmedPosition.value = marker.position
                                    }
                                })
                            }
                            overlays.add(marker)
                        }
                    },
                    onRelease = { mapView -> mapView.onDetach() },
                )
            }

            Button(
                onClick = { onConfirm(confirmedPosition.value.latitude, confirmedPosition.value.longitude) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("Confirm Position") }
        }
    }
}
