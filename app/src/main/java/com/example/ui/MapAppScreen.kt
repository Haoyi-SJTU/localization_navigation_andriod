package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Track
import com.example.map.MBTilesHelper
import com.example.map.MapFileHelper
import com.example.map.OfflineMapView
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

// Helper utility to resolve safe DISPLAY_NAME from raw selected Document URIs
fun queryUriFileName(context: Context, uri: Uri): String {
    var name = ""
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (name.isEmpty()) {
        name = uri.path ?: "file.db"
        val slash = name.lastIndexOf('/')
        if (slash != -1) {
            name = name.substring(slash + 1)
        }
    }
    return name
}

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapAppScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State Collectors
    val sensorState by viewModel.sensorState.collectAsStateWithLifecycle()
    val mapCenterLat by viewModel.mapCenterLat.collectAsStateWithLifecycle()
    val mapCenterLon by viewModel.mapCenterLon.collectAsStateWithLifecycle()
    val zoomLevel by viewModel.zoomLevel.collectAsStateWithLifecycle()
    val autoFollowGps by viewModel.autoFollowGps.collectAsStateWithLifecycle()

    val loadedMapHelper by viewModel.loadedMapHelper.collectAsStateWithLifecycle()
    val loadedMapFileHelper by viewModel.loadedMapFileHelper.collectAsStateWithLifecycle()
    val loadedMapName by viewModel.loadedMapName.collectAsStateWithLifecycle()
    val loadedMapMeta by viewModel.loadedMapMeta.collectAsStateWithLifecycle()

    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val currentPoints by viewModel.currentTrackPoints.collectAsStateWithLifecycle()
    val activeDistance by viewModel.activeDistance.collectAsStateWithLifecycle()
    val historicalTracks by viewModel.historicalTracks.collectAsStateWithLifecycle()

    val selectedHistoryTrack by viewModel.selectedHistoryTrack.collectAsStateWithLifecycle()
    val selectedHistoryPoints by viewModel.selectedHistoryPoints.collectAsStateWithLifecycle()

    // Active bottom navigation selected tab: 0 = Map, 1 = Sensors HUD, 2 = Trails Ledger, 3 = Details Settings
    var activeTab by remember { mutableStateOf(0) }

    // Permission tracking flags
    var hasLocationPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        if (granted) {
            coroutineScope.launch {
                viewModel.toggleAutoFollow() // Toggle follower and sync location coordinates
            }
        }
    }

    // Auto trigger system fine location permission request on first load
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Load file launcher contracts
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = queryUriFileName(context, uri)
            viewModel.loadOfflineMap(uri, context, fileName)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFDFBFF))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // -------------------------------------------------------------------------
        // Header: App Status Bar (Match High Density design layout exactly)
        // -------------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("floating_header_card"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Circular design logo/badge with "MAP" abbreviation text
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFFE3E2E6), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MAP",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF44474E)
                    )
                }

                // Text labels stack containing v2.4 title and dynamic active GPS lock
                Column {
                    Text(
                        text = "TopoExplorer v2.4",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1B1B1F)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val pulseIndicatorState by rememberInfiniteTransition(label = "").animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1100, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ), label = ""
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = if (sensorState.hasGps) Color(0xFF00662B).copy(alpha = pulseIndicatorState) else Color(0xFFBA1A1A).copy(alpha = pulseIndicatorState),
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = if (sensorState.hasGps) "GPS Fixed: ±${String.format("%.0fm", sensorState.gpsAccuracy)}" else "Searching GPS...",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (sensorState.hasGps) Color(0xFF00662B) else Color(0xFFBA1A1A)
                        )
                    }
                }
            }

            // Folder file trigger selection button
            IconButton(
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf("*/*")
                    )
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFFDFBFF), CircleShape)
                    .background(Color.White)
                    .clip(CircleShape)
                    .testTag("action_load_map")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Load Offline Map File",
                    tint = Color(0xFF1B1B1F),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Warning banner when hardware location permissions are missing
        if (!hasLocationPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFBA1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "LOCATION PERMISSIONS MISSING",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "To track coordinates, azimuth directions, or map files, authorize device location sensors access.",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
                    ) {
                        Text("Authorise Permission", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // -------------------------------------------------------------------------
        // Main Viewport Container Area (Controlling specific active tabs displays)
        // -------------------------------------------------------------------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (activeTab) {
                0 -> {
                    // --- MAP INTERFACE (Tab 0) ---
                    // Enclosed within high density rounded card viewport
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFFE0E2ED))
                    ) {
                        // Background Grid Map System
                        OfflineMapView(
                            sensorState = sensorState,
                            mbtilesHelper = loadedMapHelper,
                            mapFileHelper = loadedMapFileHelper,
                            mapCenterLat = mapCenterLat,
                            mapCenterLon = mapCenterLon,
                            zoomLevel = zoomLevel,
                            onMapMoved = { lat, lon, zoom ->
                                viewModel.setMapCenter(lat, lon, zoom)
                            },
                            recordedPoints = if (isRecording) currentPoints else selectedHistoryPoints,
                            modifier = Modifier.fillMaxSize()
                        )

                        // --- ON-MAP FLOATING WIDGETS ---

                        // 1. Compass Overlay HUD (Top Left)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(44.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawCircle(
                                            color = Color(0xFFDDE2F1),
                                            style = Stroke(width = 2f)
                                        )
                                    }
                                    // Pointer rotates by negation of actual device azimuth headings
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .rotate(-sensorState.heading),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(modifier = Modifier.size(6.dp, 34.dp)) {
                                            val northPath = Path().apply {
                                                moveTo(size.width / 2f, 0f)
                                                lineTo(0f, size.height / 2f)
                                                lineTo(size.width, size.height / 2f)
                                                close()
                                            }
                                            val southPath = Path().apply {
                                                moveTo(size.width / 2f, size.height)
                                                lineTo(0f, size.height / 2f)
                                                lineTo(size.width, size.height / 2f)
                                                close()
                                            }
                                            drawPath(northPath, color = Color(0xFFBA1A1A))
                                            drawPath(southPath, color = Color(0xFF74777F))
                                        }
                                        Text(
                                            text = "N",
                                            color = Color(0xFFBA1A1A),
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.align(Alignment.TopCenter)
                                        )
                                    }
                                }
                                val compassDir = when (sensorState.heading.toInt()) {
                                    in 338..360, in 0..22 -> "N"
                                    in 23..67 -> "NE"
                                    in 68..112 -> "E"
                                    in 113..157 -> "SE"
                                    in 158..202 -> "S"
                                    in 203..247 -> "SW"
                                    in 248..292 -> "W"
                                    else -> "NW"
                                }
                                Text(
                                    text = "${String.format("%.1f°", sensorState.heading)} $compassDir",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B1B1F)
                                )
                            }
                        }

                        // 2. Altitude & Barometer HUD (Top Right Stacked)
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "ALTITUDE",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF74777F),
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                Text(
                                    text = if (sensorState.hasBarometer) 
                                        "${String.format("%.1f", sensorState.pressureAltitude)}m"
                                    else if (sensorState.hasGps) 
                                        "${String.format("%.1f", sensorState.altitude)}m"
                                    else "--",
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B1B1F)
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "PRESSURE",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF74777F),
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                Text(
                                    text = if (sensorState.hasBarometer) 
                                        "${String.format("%.1f", sensorState.pressure)}hPa"
                                    else "N/A",
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B1B1F)
                                )
                            }
                        }

                        // 3. Current Live Lat/Lon Coordinates Bar (Bottom Overlay)
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 12.dp, end = 12.dp, bottom = 80.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "COORDINATES",
                                        fontSize = 8.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (sensorState.hasGps) {
                                            "LAT: ${String.format("%.4f°", sensorState.latitude)} N\nLON: ${String.format("%.4f°", sensorState.longitude)} E"
                                        } else {
                                            "LAT: 39.9042° N\nLON: 116.4074° E"
                                        },
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White,
                                        lineHeight = 13.sp
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .height(24.dp)
                                        .width(1.dp)
                                        .background(Color.White.copy(alpha = 0.2f))
                                )
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "IMU STATUS",
                                        fontSize = 8.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "CALIBRATED",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF81C784)
                                    )
                                }
                            }
                        }

                        // 4. Floating Record Action Buttons Row (Bottom Map overlay)
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Recording trigger button
                            Button(
                                onClick = {
                                    if (isRecording) {
                                        viewModel.stopTrackRecording()
                                    } else {
                                        viewModel.startTrackRecording(context)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF005AC1),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .testTag("record_button"),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Blinking red recording point
                                    val pulseAlpha by rememberInfiniteTransition(label = "").animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 1.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(850, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ), label = ""
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(Color(0xFFBA1A1A).copy(alpha = pulseAlpha), CircleShape)
                                            .padding(2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0xFFBA1A1A), CircleShape)
                                        )
                                    }

                                    Text(
                                        text = if (isRecording) "STOP RECORD" else "RECORD TRACK",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }

                            // Current GPS center locator map toggle snap button
                            IconButton(
                                onClick = { viewModel.toggleAutoFollow() },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(Color(0xFFD8E2FF), RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                                    .testTag("action_toggle_align")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "Fit view to real-time location coordinates",
                                    tint = Color(0xFF001A41),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // --- SENSORS HUD TAB ---
                    SensorDashboardTab(sensorState = sensorState)
                }
                2 -> {
                    // --- HISTORICAL TRACKS LEDGER TAB ---
                    TrackRecorderTab(
                        isRecording = isRecording,
                        activeDistance = activeDistance,
                        currentPoints = currentPoints,
                        historicalTracks = historicalTracks,
                        selectedHistoryTrack = selectedHistoryTrack,
                        onStartRecording = { viewModel.startTrackRecording(context) },
                        onStopRecording = { viewModel.stopTrackRecording() },
                        onSelectTrack = { viewModel.selectHistoricalTrack(it) },
                        onDeleteTrack = { viewModel.deleteTrack(it) }
                    )
                }
                3 -> {
                    // --- DATABASE EXPLORER TAB ---
                    MapSettingsTab(
                        loadedMapName = loadedMapName,
                        loadedMapMeta = loadedMapMeta,
                        loadedMapHelper = loadedMapHelper,
                        loadedMapFileHelper = loadedMapFileHelper,
                        onLoadMapClick = {
                            filePickerLauncher.launch(
                                arrayOf("*/*")
                            )
                        },
                        onClearMapClick = { viewModel.removeOfflineMap() }
                    )
                }
            }
        }

        // -------------------------------------------------------------------------
        // Bottom Navigation Bar (Matched to High Density style)
        // -------------------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF3F3FA))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(72.dp)
                .testTag("telemetry_dashboard_card")
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Nav buttons configs: Tab Label, Icon, Index
                val navItemsConfigs = listOf(
                    Triple("Map", Icons.Default.LocationOn, 0),
                    Triple("Sensors", Icons.Default.Info, 1),
                    Triple("Trails", Icons.Default.List, 2),
                    Triple("Details", Icons.Default.Settings, 3)
                )

                navItemsConfigs.forEach { (label, icon, idx) ->
                    val isSelected = activeTab == idx
                    val isTrailsTab = label == "Trails"
                    
                    Column(
                        modifier = Modifier
                            .clickable { activeTab = idx }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .then(if (isTrailsTab) Modifier.testTag("tab_tracking") else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // High density active selected oval background capsule
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .width(60.dp)
                                .background(
                                    color = if (isSelected) Color(0xFFD8E2FF) else Color.Transparent,
                                    shape = RoundedCornerShape(15.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) Color(0xFF001D4A) else Color(0xFF44474E),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF001A41) else Color(0xFF44474E)
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Helper Component: High Density Sensors HUD
// -----------------------------------------------------------------------------
@SuppressLint("DefaultLocale")
@Composable
fun SensorDashboardTab(sensorState: com.example.sensor.SensorState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("sensors_hud_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // GPS positioning metric card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFE3E2E6))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "1. POSITION (GPS STATUS)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = Color(0xFF005AC1),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DashboardMetricBox(
                            label = "Latitude",
                            value = if (sensorState.hasGps) String.format("%.6f°", sensorState.latitude) else "--",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        DashboardMetricBox(
                            label = "Longitude",
                            value = if (sensorState.hasGps) String.format("%.6f°", sensorState.longitude) else "--",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DashboardMetricBox(
                            label = "GPS Altitude",
                            value = if (sensorState.hasGps) String.format("%.1f m", sensorState.altitude) else "--",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        DashboardMetricBox(
                            label = "Odometer Speed",
                            value = if (sensorState.hasGps) String.format("%.1f km/h", sensorState.speed * 3.6f) else "--",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Barometric parameter card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFE3E2E6))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "2. BAROMETRIC ALTIMETER",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color(0xFF005AC1),
                            letterSpacing = 0.5.sp
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (sensorState.hasBarometer) Color(0xFF00662B).copy(alpha = 0.12f) else Color(0xFFBA1A1A).copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (sensorState.hasBarometer) "SUPPORTED" else "NO SENSOR",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (sensorState.hasBarometer) Color(0xFF00662B) else Color(0xFFBA1A1A)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DashboardMetricBox(
                            label = "Pressure State",
                            value = if (sensorState.hasBarometer) String.format("%.2f hPa", sensorState.pressure) else "Not Fitted",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        DashboardMetricBox(
                            label = "Estimated altitude",
                            value = if (sensorState.hasBarometer) String.format("%.1f m", sensorState.pressureAltitude) else "Calculated --",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (sensorState.hasBarometer) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "* Altitude computed dynamically from standard pressure ISA reference parameters.",
                            fontSize = 8.sp,
                            color = Color(0xFF74777F)
                        )
                    }
                }
            }
        }

        // Magnetometer Compass & IMU speeds card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFE3E2E6))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "3. IMU ALIGNMENT & GYROSCOPE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = Color(0xFF005AC1),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = "AZIMUTH", fontSize = 9.sp, color = Color(0xFF74777F), fontWeight = FontWeight.Medium)
                            Text(
                                text = String.format("%d°", sensorState.heading.toInt()),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B1B1F)
                            )
                            val compassHeadingName = when (sensorState.heading.toInt()) {
                                in 338..360, in 0..22 -> "North (N)"
                                in 23..67 -> "N-East (NE)"
                                in 68..112 -> "East (E)"
                                in 113..157 -> "S-East (SE)"
                                in 158..202 -> "South (S)"
                                in 203..247 -> "S-West (SW)"
                                in 248..292 -> "West (W)"
                                else -> "N-West (NW)"
                            }
                            Text(text = compassHeadingName, fontSize = 9.sp, color = Color(0xFF005AC1), fontWeight = FontWeight.Bold)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = "PITCH (FORE/AFT)", fontSize = 9.sp, color = Color(0xFF74777F), fontWeight = FontWeight.Medium)
                            Text(
                                text = String.format("%.1f°", sensorState.pitch),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B1B1F)
                            )
                            Text(text = if (sensorState.pitch >= 0) "UPWARD" else "DOWNWARD", fontSize = 9.sp, color = Color(0xFF74777F))
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(text = "ROLL (SIDEWAYS)", fontSize = 9.sp, color = Color(0xFF74777F), fontWeight = FontWeight.Medium)
                            Text(
                                text = String.format("%.1f°", sensorState.roll),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B1B1F)
                            )
                            Text(text = if (sensorState.roll >= 0) "STARBOARD" else "PORT", fontSize = 9.sp, color = Color(0xFF74777F))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFFE3E2E6))
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(text = "Gyro scope Vector speeds (rad/s):", fontSize = 9.sp, color = Color(0xFF74777F), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        DashboardMetricBox(
                            label = "X Pitch speed",
                            value = String.format("%.3f", sensorState.gyroX),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        DashboardMetricBox(
                            label = "Y Roll speed",
                            value = String.format("%.3f", sensorState.gyroY),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        DashboardMetricBox(
                            label = "Z Yaw speed",
                            value = String.format("%.3f", sensorState.gyroZ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Helper Component: High Density Track Recording ledger list
// -----------------------------------------------------------------------------
@SuppressLint("DefaultLocale")
@Composable
fun TrackRecorderTab(
    isRecording: Boolean,
    activeDistance: Double,
    currentPoints: List<com.example.data.TrackPoint>,
    historicalTracks: List<Track>,
    selectedHistoryTrack: Track?,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSelectTrack: (Track?) -> Unit,
    onDeleteTrack: (Track) -> Unit
) {
    val distanceUnit = if (activeDistance >= 1000.0) {
        String.format("%.2f km", activeDistance / 1000.0)
    } else {
        String.format("%d m", activeDistance.toInt())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFFE3E2E6)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (isRecording) "TRACK ACTIVE (RECORDING)" else "READY TO RECORD TRAILS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecording) Color(0xFFBA1A1A) else Color(0xFF00662B),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Odometer: $distanceUnit",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B1B1F)
                    )
                    Text(
                        text = "Captured: ${currentPoints.size} waypoints",
                        fontSize = 10.sp,
                        color = Color(0xFF74777F)
                    )
                }

                // Record Trigger Toggle Button
                if (!isRecording) {
                    Button(
                        onClick = onStartRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFBA1A1A),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("record_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start Track Recording",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("RECORD", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Button(
                        onClick = onStopRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF74777F),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("record_button") // Keep test tag consistent so tests align
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop Track Recording",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("STOP & SAVE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "SAVED HISTORIC TRAILS (${historicalTracks.size})",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF74777F),
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (historicalTracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = "Empty trails list",
                        tint = Color(0xFFE3E2E6),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No saved trails yet.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF74777F)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Press RECORD to start tracking your walk.",
                        fontSize = 9.sp,
                        color = Color(0xFF74777F)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(historicalTracks) { track ->
                    val isSelected = selectedHistoryTrack?.id == track.id
                    val format = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    val dateFormatted = format.format(Date(track.startTime))

                    val finalLen = if (track.distance >= 1000.0) {
                        String.format("%.2f km", track.distance / 1000.0)
                    } else {
                        String.format("%d m", track.distance.toInt())
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) {
                                    onSelectTrack(null)
                                } else {
                                    onSelectTrack(track)
                                }
                            }
                            .testTag("track_history_item_${track.id}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFFD8E2FF) else Color.White
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF005AC1) else Color(0xFFE3E2E6)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B1B1F)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$dateFormatted • $finalLen • ${track.pointsCount} pts",
                                    fontSize = 10.sp,
                                    color = Color(0xFF74777F)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelected) {
                                    Text(
                                        text = "DISPLAYING",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF005AC1),
                                        modifier = Modifier
                                            .background(Color(0xFF005AC1).copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                IconButton(
                                    onClick = { onDeleteTrack(track) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete track recording permanently",
                                        tint = Color(0xFFBA1A1A).copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Helper Component: High Density Settings/Database Manager
// -----------------------------------------------------------------------------
@Composable
fun MapSettingsTab(
    loadedMapName: String,
    loadedMapMeta: Map<String, String>,
    loadedMapHelper: MBTilesHelper?,
    loadedMapFileHelper: MapFileHelper?,
    onLoadMapClick: () -> Unit,
    onClearMapClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("map_settings_hud"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFE3E2E6))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "OFFLINE MAP DATA FILE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = Color(0xFF005AC1),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = loadedMapName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B1B1F)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            loadedMapHelper != null -> "Status: MBTiles Database Active"
                            loadedMapFileHelper != null -> "Status: .MAP Vector Map Active"
                            else -> "Status: Simulated Default Mesh"
                        },
                        fontSize = 11.sp,
                        color = if (loadedMapHelper != null || loadedMapFileHelper != null) Color(0xFF00662B) else Color(0xFFBA1A1A),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onLoadMapClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("LOAD FILE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (loadedMapHelper != null || loadedMapFileHelper != null) {
                            Button(
                                onClick = onClearMapClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA1A1A)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("UNLOAD", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (loadedMapMeta.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE3E2E6))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "METADATA PARAMETERS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color(0xFF005AC1),
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        loadedMapMeta.entries.forEach { entry ->
                            if (entry.key != "tile_data") {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = entry.key.uppercase(),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF74777F)
                                    )
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        text = entry.value,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF1B1B1F)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    HorizontalDivider(color = Color(0xFFE3E2E6))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Metric data cell styled elegantly for High Density palette
@Composable
fun DashboardMetricBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFFE3E2E6).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Column {
            Text(
                text = label.uppercase(),
                fontSize = 8.sp,
                color = Color(0xFF74777F),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 14.sp,
                color = Color(0xFF1B1B1F),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
