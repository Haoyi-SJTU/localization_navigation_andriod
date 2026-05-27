package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.map.MBTilesHelper
import com.example.map.MapFileHelper
import com.example.sensor.SensorState
import com.example.sensor.SensorTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "MapViewModel"
    private val database = TrackDatabase.getDatabase(application)
    private val repository = TrackRepository(database.trackDao())
    private val sensorTracker = SensorTracker(application)

    // Reactive hardware sensors state representation
    val sensorState: StateFlow<SensorState> = sensorTracker.sensorState

    // Map viewfinder state parameters
    private val _mapCenterLat = MutableStateFlow(39.9042) // Defaults (e.g. Beijing/Beijing Center)
    val mapCenterLat: StateFlow<Double> = _mapCenterLat.asStateFlow()

    private val _mapCenterLon = MutableStateFlow(116.4074)
    val mapCenterLon: StateFlow<Double> = _mapCenterLon.asStateFlow()

    private val _zoomLevel = MutableStateFlow(13.0)
    val zoomLevel: StateFlow<Double> = _zoomLevel.asStateFlow()

    // Flag to lock Map center to GPS location (auto-pan)
    private val _autoFollowGps = MutableStateFlow(true)
    val autoFollowGps: StateFlow<Boolean> = _autoFollowGps.asStateFlow()

    // Offline DB helper state properties
    private var activeMbtilesHelper: MBTilesHelper? = null
    private val _loadedMapHelper = MutableStateFlow<MBTilesHelper?>(null)
    val loadedMapHelper: StateFlow<MBTilesHelper?> = _loadedMapHelper.asStateFlow()

    // Offline Mapsforge helper state properties
    private var activeMapFileHelper: MapFileHelper? = null
    private val _loadedMapFileHelper = MutableStateFlow<MapFileHelper?>(null)
    val loadedMapFileHelper: StateFlow<MapFileHelper?> = _loadedMapFileHelper.asStateFlow()

    private val _loadedMapName = MutableStateFlow("Simulation Map Grid (GPS-Aligned)")
    val loadedMapName: StateFlow<String> = _loadedMapName.asStateFlow()

    private val _loadedMapMeta = MutableStateFlow<Map<String, String>>(emptyMap())
    val loadedMapMeta: StateFlow<Map<String, String>> = _loadedMapMeta.asStateFlow()

    // Track recording state parameters
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentTrackId = MutableStateFlow<Long?>(null)
    val currentTrackId: StateFlow<Long?> = _currentTrackId.asStateFlow()

    private val _currentTrackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val currentTrackPoints: StateFlow<List<TrackPoint>> = _currentTrackPoints.asStateFlow()

    private val _activeDistance = MutableStateFlow(0.0) // meters
    val activeDistance: StateFlow<Double> = _activeDistance.asStateFlow()

    // History tracks list representation
    val historicalTracks: StateFlow<List<Track>> = repository.allTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Currently selected historical track details to display on Map
    private val _selectedHistoryTrack = MutableStateFlow<Track?>(null)
    val selectedHistoryTrack: StateFlow<Track?> = _selectedHistoryTrack.asStateFlow()

    private val _selectedHistoryPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val selectedHistoryPoints: StateFlow<List<TrackPoint>> = _selectedHistoryPoints.asStateFlow()

    init {
        // Start listening to physical hardware sensors
        sensorTracker.startTracking()

        // Sync map viewport center on first accurate GPS coordinate lock
        viewModelScope.launch {
            sensorState
                .filter { it.hasGps && it.latitude != 0.0 && it.longitude != 0.0 }
                .take(1)
                .collect { initialGps ->
                    _mapCenterLat.value = initialGps.latitude
                    _mapCenterLon.value = initialGps.longitude
                }
        }

        // Keep updating map center when Auto-Follow GPS is active
        viewModelScope.launch {
            sensorState.collect { state ->
                if (_autoFollowGps.value && state.hasGps && state.latitude != 0.0 && state.longitude != 0.0) {
                    _mapCenterLat.value = state.latitude
                    _mapCenterLon.value = state.longitude
                }
            }
        }

        // Live location polling inside recording cycles
        viewModelScope.launch {
            sensorState
                .collect { state ->
                    if (_isRecording.value && state.hasGps) {
                        recordCoordinate(state)
                    }
                }
        }
    }

    fun setMapCenter(lat: Double, lon: Double, zoom: Double) {
        _mapCenterLat.value = lat
        _mapCenterLon.value = lon
        _zoomLevel.value = zoom
        // If the user manually gestures/scrolls, disable follow mode
        _autoFollowGps.value = false
    }

    fun toggleAutoFollow() {
        _autoFollowGps.value = !_autoFollowGps.value
        if (_autoFollowGps.value && sensorState.value.hasGps) {
            _mapCenterLat.value = sensorState.value.latitude
            _mapCenterLon.value = sensorState.value.longitude
        }
    }

    // -------------------------------------------------------------------------
    // Offline Map File Loading (.db / .map)
    // -------------------------------------------------------------------------
    fun loadOfflineMap(uri: Uri, context: Context, selectedName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Resolve safe local cached output space
                val isDbFormat = selectedName.endsWith(".db", ignoreCase = true) || 
                                 selectedName.endsWith(".mbtiles", ignoreCase = true)
                val isMapFormat = selectedName.endsWith(".map", ignoreCase = true)

                val fileExt = if (isDbFormat) "db" else if (isMapFormat) "map" else "db"
                val cacheFile = File(context.cacheDir, "loaded_map.$fileExt")
                
                // Copy selected stream to physical local application cache directory
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (isDbFormat) {
                    // Try to mount MBTiles SQLite
                    val helper = MBTilesHelper(cacheFile)
                    if (helper.open()) {
                        activeMbtilesHelper?.close()
                        activeMbtilesHelper = helper

                        activeMapFileHelper?.close()
                        activeMapFileHelper = null
                        _loadedMapFileHelper.value = null

                        val meta = helper.getMetadata()
                        val friendlyName = meta["name"] ?: selectedName

                        _loadedMapHelper.value = helper
                        _loadedMapName.value = friendlyName
                        _loadedMapMeta.value = meta
                        Log.d(tag, "Loaded MBTiles database: $friendlyName, metadata: $meta")
                    } else {
                        Log.e(tag, "Failed to mount selected SQLite DB as MBTiles map.")
                    }
                } else if (isMapFormat) {
                    val fileHelper = MapFileHelper(cacheFile)
                    if (fileHelper.open()) {
                        activeMbtilesHelper?.close()
                        activeMbtilesHelper = null
                        _loadedMapHelper.value = null

                        activeMapFileHelper?.close()
                        activeMapFileHelper = fileHelper
                        _loadedMapFileHelper.value = fileHelper

                        _loadedMapName.value = selectedName
                        _loadedMapMeta.value = fileHelper.getMetadata()

                        // Center map viewport around loaded Mapsforge vector map's center coordinates
                        _mapCenterLat.value = fileHelper.centerLatitude
                        _mapCenterLon.value = fileHelper.centerLongitude
                        _zoomLevel.value = 13.0
                        _autoFollowGps.value = false

                        Log.d(tag, "Loaded Mapsforge vector file: $selectedName, center=[${fileHelper.centerLatitude}, ${fileHelper.centerLongitude}]")
                    } else {
                        Log.e(tag, "Failed to parse selected vector .map file.")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error copy/loading offline document template", e)
            }
        }
    }

    fun removeOfflineMap() {
        activeMbtilesHelper?.close()
        activeMbtilesHelper = null
        _loadedMapHelper.value = null

        activeMapFileHelper?.close()
        activeMapFileHelper = null
        _loadedMapFileHelper.value = null

        _loadedMapName.value = "Simulation Map Grid (GPS-Aligned)"
        _loadedMapMeta.value = emptyMap()
    }

    // -------------------------------------------------------------------------
    // Track Recording Flow (轨迹记录)
    // -------------------------------------------------------------------------
    fun startTrackRecording(context: Context, prefix: String = "Track") {
        viewModelScope.launch(Dispatchers.IO) {
            val name = "$prefix ${System.currentTimeMillis()}"
            val trackId = repository.startTrack(name, "Recorded live offline via hardware sensors")
            
            _currentTrackId.value = trackId
            _currentTrackPoints.value = emptyList()
            _activeDistance.value = 0.0
            _isRecording.value = true

            Log.d(tag, "Started track recording session, SQLite ID: $trackId")
        }
    }

    private suspend fun recordCoordinate(state: SensorState) {
        val trackId = _currentTrackId.value ?: return

        // Filter out redundant points or extreme duplicates to refine mapping resolution
        val prevPoint = _currentTrackPoints.value.lastOrNull()
        if (prevPoint != null) {
            val dist = calculateDistance(
                prevPoint.latitude, prevPoint.longitude,
                state.latitude, state.longitude
            )
            // Skip points if the user hasn't moved at least 2 meters.
            if (dist < 2.0) return

            _activeDistance.value += dist
        }

        val nextPoint = TrackPoint(
            trackId = trackId,
            latitude = state.latitude,
            longitude = state.longitude,
            altitude = state.altitude,
            pressure = state.pressure,
            speed = state.speed,
            bearing = state.bearing,
            timestamp = System.currentTimeMillis()
        )

        // Save immediately to Room database
        repository.savePoint(nextPoint)

        withContext(Dispatchers.Main) {
            _currentTrackPoints.value = _currentTrackPoints.value + nextPoint
        }
    }

    fun stopTrackRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false

        val trackId = _currentTrackId.value ?: return
        val finalPoints = _currentTrackPoints.value
        val finalDist = _activeDistance.value

        viewModelScope.launch(Dispatchers.IO) {
            val track = repository.getTrackById(trackId)
            if (track != null) {
                val updatedTrack = track.copy(
                    endTime = System.currentTimeMillis(),
                    distance = finalDist,
                    pointsCount = finalPoints.size
                )
                repository.updateTrackInfo(updatedTrack)
                Log.d(tag, "Saved final recording track session ID $trackId, points: ${finalPoints.size}")
            }
            _currentTrackId.value = null
        }
    }

    fun selectHistoricalTrack(track: Track?) {
        _selectedHistoryTrack.value = track
        if (track == null) {
            _selectedHistoryPoints.value = emptyList()
        } else {
            viewModelScope.launch {
                repository.getPointsForTrack(track.id)
                    .collectLatest { points ->
                        _selectedHistoryPoints.value = points
                        
                        // Center map viewport around historical track starting point
                        if (points.isNotEmpty()) {
                            val start = points.first()
                            _mapCenterLat.value = start.latitude
                            _mapCenterLon.value = start.longitude
                            _zoomLevel.value = 14.5
                            _autoFollowGps.value = false
                        }
                    }
            }
        }
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_selectedHistoryTrack.value?.id == track.id) {
                _selectedHistoryTrack.value = null
                _selectedHistoryPoints.value = emptyList()
            }
            repository.deleteTrack(track)
        }
    }

    // Great circle calculation (Haversine formula)
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius (meters)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    override fun onCleared() {
        super.onCleared()
        sensorTracker.stopTracking()
        activeMbtilesHelper?.close()
        activeMapFileHelper?.close()
    }
}
