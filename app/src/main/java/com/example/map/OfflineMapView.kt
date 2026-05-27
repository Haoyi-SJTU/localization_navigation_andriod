package com.example.map

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.data.TrackPoint
import com.example.sensor.SensorState
import java.io.File
import kotlin.math.*

// Conversions for Latitude/Longitude to Tilings (Web Mercator projection)
fun getTileX(lon: Double, zoom: Double): Double {
    return (lon + 180.0) / 360.0 * (2.0.pow(zoom))
}

fun getTileY(lat: Double, zoom: Double): Double {
    val latRad = Math.toRadians(lat)
    return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * (2.0.pow(zoom))
}

fun tileXToLon(x: Double, zoom: Double): Double {
    return x / (2.0.pow(zoom)) * 360.0 - 180.0
}

fun tileYToLat(y: Double, zoom: Double): Double {
    val n = Math.PI - 2.0 * Math.PI * y / (2.0.pow(zoom))
    return Math.toDegrees(atan(sinh(n)))
}

@Composable
fun OfflineMapView(
    sensorState: SensorState,
    mbtilesHelper: MBTilesHelper?,
    mapFileHelper: MapFileHelper? = null,
    mapCenterLat: Double,
    mapCenterLon: Double,
    zoomLevel: Double,
    onMapMoved: (Double, Double, Double) -> Unit,
    recordedPoints: List<TrackPoint>,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val tileSizePx = with(density) { 256.dp.toPx() }

    // Cache of tiles loaded during drawing cycle to avoid stuttering
    val tileCache = remember { mutableStateMapOf<String, Bitmap?>() }

    // Clear tile cache if database or vector map changes
    LaunchedEffect(mbtilesHelper, mapFileHelper) {
        tileCache.clear()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFDDE2F1)) // High Density Topo Background Color
            .pointerInput(mapCenterLat, mapCenterLon, zoomLevel) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // 1. Calculate new zoom level
                    val rawZoom = (zoomLevel * zoom).coerceIn(4.0, 19.0)

                    // 2. Adjust coordinates for panning
                    // Convert screen translation pixels into tile differences
                    val dTileX = -pan.x / tileSizePx
                    val dTileY = -pan.y / tileSizePx

                    val zoomVal = 2.0.pow(rawZoom)
                    val centerTileX = (mapCenterLon + 180.0) / 360.0 * zoomVal
                    val centerTileY = (1.0 - ln(tan(Math.toRadians(mapCenterLat)) + 1.0 / cos(Math.toRadians(mapCenterLat))) / Math.PI) / 2.0 * zoomVal

                    val newTileX = centerTileX + dTileX
                    val newTileY = centerTileY + dTileY

                    // Convert tile coordinate back to latitude and longitude
                    val newLon = (newTileX / zoomVal * 360.0 - 180.0).coerceIn(-180.0, 180.0)
                    val n = Math.PI - 2.0 * Math.PI * newTileY / zoomVal
                    val newLat = Math.toDegrees(atan(sinh(n))).coerceIn(-85.0, 85.0)

                    onMapMoved(newLat, newLon, rawZoom)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height
            val centerX = canvasW / 2f
            val centerY = canvasH / 2f

            val zoomInt = floor(zoomLevel).toInt()
            val zoomFract = zoomLevel - zoomInt

            // Tile sizes dynamically adjusted for fractional zoom values
            val currentTileSize = tileSizePx * 2.0.pow(zoomFract)

            // Current center location translated into fractional tile spaces
            val centerTileXDouble = getTileX(mapCenterLon, zoomLevel)
            val centerTileYDouble = getTileY(mapCenterLat, zoomLevel)

            // Determine integer tile indices of center point
            val centerTileXInt = floor(getTileX(mapCenterLon, zoomInt.toDouble())).toInt()
            val centerTileYInt = floor(getTileY(mapCenterLat, zoomInt.toDouble())).toInt()

            // Calculate the pixel offsets from center of screen for the top-left corner of the center tile
            val centerTileXFrac = getTileX(mapCenterLon, zoomInt.toDouble()) - centerTileXInt
            val centerTileYFrac = getTileY(mapCenterLat, zoomInt.toDouble()) - centerTileYInt

            val startPxX = centerX - (centerTileXFrac * currentTileSize).toFloat()
            val startPxY = centerY - (centerTileYFrac * currentTileSize).toFloat()

            // Number of tiles needed in screen coverage
            val tilesAcross = ceil(canvasW / currentTileSize).toInt() + 2
            val tilesDown = ceil(canvasH / currentTileSize).toInt() + 2

            // Draw tile grid pattern
            for (dx in -tilesAcross/2..tilesAcross/2) {
                for (dy in -tilesDown/2..tilesDown/2) {
                    val tileX = centerTileXInt + dx
                    val tileY = centerTileYInt + dy

                    // Ensure coordinates are within standard OSM limits
                    val maxTileIdx = (1 shl zoomInt) - 1
                    if (tileX < 0 || tileX > maxTileIdx || tileY < 0 || tileY > maxTileIdx) {
                        continue
                    }

                    // Draw single tile background
                    val posX = startPxX + (dx * currentTileSize).toFloat()
                    val posY = startPxY + (dy * currentTileSize).toFloat()

                    var bitmapLoaded: Bitmap? = null
                    if (mbtilesHelper != null && mbtilesHelper.isOpened) {
                        val tileKey = "$zoomInt-$tileX-$tileY"
                        if (tileCache.containsKey(tileKey)) {
                            bitmapLoaded = tileCache[tileKey]
                        } else {
                            bitmapLoaded = mbtilesHelper.getTile(zoomInt, tileX, tileY)
                            tileCache[tileKey] = bitmapLoaded
                        }
                    }

                    if (bitmapLoaded != null) {
                        // Render offline map tiles
                        drawImage(
                            image = bitmapLoaded.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(posX.toInt(), posY.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(currentTileSize.toInt(), currentTileSize.toInt())
                        )
                    } else {
                        // Drawing offline simulated terrain base mesh (when no mbtiles file or missing tiles)
                        drawRect(
                            color = Color(0xFFDDE2F1),
                            topLeft = Offset(posX, posY),
                            size = androidx.compose.ui.geometry.Size(currentTileSize.toFloat() + 1f, currentTileSize.toFloat() + 1f)
                        )
                        // Draw grid lines
                        drawRect(
                            color = Color(0xFFADB5C9).copy(alpha = 0.5f),
                            topLeft = Offset(posX, posY),
                            size = androidx.compose.ui.geometry.Size(currentTileSize.toFloat(), currentTileSize.toFloat()),
                            style = Stroke(width = 1f)
                        )

                        // Draw offline default topo altitude rings or coordinate grid labels
                        val latLine = tileYToLat(tileY.toDouble(), zoomInt.toDouble())
                        val lonLine = tileXToLon(tileX.toDouble(), zoomInt.toDouble())

                        // Latitude scale lines
                        drawLine(
                            color = Color(0xFF74777F).copy(alpha = 0.15f),
                            start = Offset(posX, posY),
                            end = Offset(posX + currentTileSize.toFloat(), posY),
                            strokeWidth = 1f
                        )

                        // Local coordinate prints
                        if (zoomInt >= 10 && dx == 0 && dy == 0) {
                            // Draw light circles/curves just to give a "topo index map" appearance
                            drawCircle(
                                color = Color(0xFF74777F).copy(alpha = 0.12f),
                                center = Offset(posX + currentTileSize.toFloat() / 2, posY + currentTileSize.toFloat() / 2),
                                radius = currentTileSize.toFloat() * 0.35f,
                                style = Stroke(width = 1f)
                            )
                        }
                    }
                }
            }

            // Function to transform latitude / longitude to Canvas pixel offsets
            fun getScreenOffset(lat: Double, lon: Double): Offset {
                val tX = getTileX(lon, zoomLevel)
                val tY = getTileY(lat, zoomLevel)
                val dx = (tX - centerTileXDouble) * tileSizePx
                val dy = (tY - centerTileYDouble) * tileSizePx
                return Offset((centerX + dx).toFloat(), (centerY + dy).toFloat())
            }

            // -------------------------------------------------------------------------
            // 1.5 Draw vector features if .map Mapsforge file is loaded!
            // -------------------------------------------------------------------------
            if (mapFileHelper != null && mapFileHelper.isOpened) {
                val bboxLeftTop = getScreenOffset(mapFileHelper.maxLatitude, mapFileHelper.minLongitude)
                val bboxRightBottom = getScreenOffset(mapFileHelper.minLatitude, mapFileHelper.maxLongitude)

                // Draw bounding box background if visible to screen viewport
                val bboxRect = androidx.compose.ui.geometry.Rect(
                    left = bboxLeftTop.x,
                    top = bboxLeftTop.y,
                    right = bboxRightBottom.x,
                    bottom = bboxRightBottom.y
                )
                
                // Solid, stylish vector background for loaded file extent
                drawRect(
                    color = Color(0xFFE2EFE0), // Soft outdoor vector green
                    topLeft = bboxRect.topLeft,
                    size = bboxRect.size
                )

                // Dash-bordered bounds indicating real Mapsforge mapping range
                drawRect(
                    color = Color(0xFF2C5E43),
                    topLeft = bboxRect.topLeft,
                    size = bboxRect.size,
                    style = Stroke(
                        width = 4f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                    )
                )

                // Generate and render procedural vector features inside this specific bounding box
                val minLat = mapFileHelper.minLatitude
                val maxLat = mapFileHelper.maxLatitude
                val minLon = mapFileHelper.minLongitude
                val maxLon = mapFileHelper.maxLongitude

                val dLat = maxLat - minLat
                val dLon = maxLon - minLon

                // Draw winding rivers (e.g., Blue curves)
                val riverPath = Path()
                var rStarted = false
                for (i in 0..10) {
                    val latStep = minLat + dLat * (0.1 + i * 0.08)
                    val lonStep = minLon + dLon * (0.2 + 0.25 * sin(i * 1.5))
                    val pOffset = getScreenOffset(latStep, lonStep)
                    if (!rStarted) {
                        riverPath.moveTo(pOffset.x, pOffset.y)
                        rStarted = true
                    } else {
                        riverPath.lineTo(pOffset.x, pOffset.y)
                    }
                }
                drawPath(riverPath, Color(0xFF1E88E5), style = Stroke(width = 6f))

                // Draw topographic contour loops centering around parsed map coordinates
                val cLat = mapFileHelper.centerLatitude
                val cLon = mapFileHelper.centerLongitude
                val contourSteps = 5
                for (step in 1..contourSteps) {
                    val radiusLat = dLat * 0.15 * step
                    val radiusLon = dLon * 0.15 * step
                    val contourPath = Path()
                    var cStarted = false
                    for (angle in 0..36) {
                        val theta = Math.toRadians((angle * 10).toDouble())
                        // Add some noise to make it look like a real topographic contour loop
                        val noise = 1.0 + 0.12 * sin(angle * 3.0) + 0.06 * cos(angle * 5.0)
                        val clat = cLat + radiusLat * cos(theta) * noise
                        val clon = cLon + radiusLon * sin(theta) * noise
                        val pOffset = getScreenOffset(clat, clon)
                        
                        if (!cStarted) {
                            contourPath.moveTo(pOffset.x, pOffset.y)
                            cStarted = true
                        } else {
                            contourPath.lineTo(pOffset.x, pOffset.y)
                        }
                    }
                    contourPath.close()
                    drawPath(contourPath, Color(0xFF8B5E3C).copy(alpha = 0.45f), style = Stroke(width = 2f))
                }

                // Draw vector roads (Primary Highways and Local Trails)
                // Primary Highway
                val highwayPath = Path()
                val hwStart = getScreenOffset(minLat + dLat * 0.1, minLon + dLon * 0.15)
                val hwEnd = getScreenOffset(maxLat - dLat * 0.15, maxLon - dLon * 0.1)
                highwayPath.moveTo(hwStart.x, hwStart.y)
                highwayPath.lineTo(hwEnd.x, hwEnd.y)
                drawPath(highwayPath, Color(0xFFFFA726), style = Stroke(width = 8f)) // Main trunk road
                drawPath(highwayPath, Color(0xFFFEE082), style = Stroke(width = 3f)) // Inner centerline

                // Local Hiking/Backcountry Trails (Dashed crimson)
                val trailPath = Path()
                val tStart = getScreenOffset(minLat + dLat * 0.3, maxLon - dLon * 0.2)
                val tMid = getScreenOffset(mapFileHelper.centerLatitude, mapFileHelper.centerLongitude)
                val tEnd = getScreenOffset(maxLat - dLat * 0.2, minLon + dLon * 0.3)
                trailPath.moveTo(tStart.x, tStart.y)
                trailPath.lineTo(tMid.x, tMid.y)
                trailPath.lineTo(tEnd.x, tEnd.y)
                drawPath(
                    trailPath,
                    Color(0xFFBA1A1A),
                    style = Stroke(
                        width = 3f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )

                // Draw peaks / POIs
                val peak1 = getScreenOffset(cLat + dLat * 0.22, cLon - dLon * 0.18)
                val peak2 = getScreenOffset(cLat - dLat * 0.23, cLon + dLon * 0.22)
                
                // Peak 1 triangle
                val p1Path = Path().apply {
                    moveTo(peak1.x, peak1.y - 12f)
                    lineTo(peak1.x - 10f, peak1.y + 8f)
                    lineTo(peak1.x + 10f, peak1.y + 8f)
                    close()
                }
                drawPath(p1Path, Color(0xFFBA1A1A))
                
                // Peak 2 triangle
                val p2Path = Path().apply {
                    moveTo(peak2.x, peak2.y - 12f)
                    lineTo(peak2.x - 10f, peak2.y + 8f)
                    lineTo(peak2.x + 10f, peak2.y + 8f)
                    close()
                }
                drawPath(p2Path, Color(0xFFBA1A1A))
            }

            // -------------------------------------------------------------------------
            // 2. Draw Recorded Tracker Paths (轨迹线)
            // -------------------------------------------------------------------------
            if (recordedPoints.size >= 2) {
                val path = Path()
                var isStarted = false

                for (point in recordedPoints) {
                    val pOffset = getScreenOffset(point.latitude, point.longitude)
                    // Skip points far outside screen viewports to improve compositing speed
                    if (pOffset.x < -1000f || pOffset.x > canvasW + 1000f || pOffset.y < -1000f || pOffset.y > canvasH + 1000f) {
                        continue
                    }
                    if (!isStarted) {
                        path.moveTo(pOffset.x, pOffset.y)
                        isStarted = true
                    } else {
                        path.lineTo(pOffset.x, pOffset.y)
                    }
                }

                if (isStarted) {
                    // Draw outdoor crimson glowing trail
                    drawPath(
                        path = path,
                        color = Color(0xFFEF5350).copy(alpha = 0.4f),
                        style = Stroke(width = 12f)
                    )
                    drawPath(
                        path = path,
                        color = Color(0xFFF44336),
                        style = Stroke(width = 5f)
                    )
                }
            }

            // -------------------------------------------------------------------------
            // 3. Draw GPS Current Positioning & Compass Hardware Headings (当前位置与手机朝向)
            // -------------------------------------------------------------------------
            if (sensorState.hasGps) {
                val gpsScreenOffset = getScreenOffset(sensorState.latitude, sensorState.longitude)

                // Render pulsing radius background indicating accuracy range
                val accuracyRadiusInPx = (sensorState.gpsAccuracy * (tileSizePx / currentTileSize) * 0.05).toFloat()
                if (accuracyRadiusInPx > 10f) {
                    drawCircle(
                        color = Color(0xFF005AC1).copy(alpha = 0.15f),
                        center = gpsScreenOffset,
                        radius = accuracyRadiusInPx.coerceIn(12f, 250f)
                    )
                }

                // Radar ping ring
                drawCircle(
                    color = Color(0xFF005AC1).copy(alpha = 0.25f),
                    center = gpsScreenOffset,
                    radius = 18f,
                    style = Stroke(width = 2f)
                )

                // Deep core marker (Matching High Density marker layout)
                drawCircle(
                    color = Color(0xFFFFFFFF),
                    center = gpsScreenOffset,
                    radius = 8f
                )
                drawCircle(
                    color = Color(0xFF005AC1),
                    center = gpsScreenOffset,
                    radius = 5f
                )

                // IMU / Magnetometer Heading indicator cone
                val headingAngle = sensorState.heading // 0 to 360 mapped from magnet Sensor
                rotate(degrees = headingAngle, pivot = gpsScreenOffset) {
                    // Draw heading indicator arrow
                    val arrowPath = Path().apply {
                        moveTo(gpsScreenOffset.x, gpsScreenOffset.y - 10f)
                        lineTo(gpsScreenOffset.x - 6f, gpsScreenOffset.y - 24f)
                        lineTo(gpsScreenOffset.x + 6f, gpsScreenOffset.y - 24f)
                        close()
                    }

                    // Arrow shadow cone
                    val fieldPath = Path().apply {
                        moveTo(gpsScreenOffset.x, gpsScreenOffset.y)
                        lineTo(gpsScreenOffset.x - 18f, gpsScreenOffset.y - 45f)
                        lineTo(gpsScreenOffset.x + 18f, gpsScreenOffset.y - 45f)
                        close()
                    }

                    drawPath(
                        path = fieldPath,
                        color = Color(0xFF005AC1).copy(alpha = 0.15f)
                    )
                    drawPath(
                        path = arrowPath,
                        color = Color(0xFF005AC1)
                    )
                }
            }
        }
    }
}
