package com.example.map

import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class MapFileHelper(val file: File) {
    var isOpened = false
        private set

    var formatVersion: Int = 0
    var fileSizeValue: Long = 0L
    var createTimestamp: Long = 0L
    
    // Core bounding box coordinates (Latitude / Longitude in degrees)
    var minLatitude: Double = 39.9042 - 0.5
    var minLongitude: Double = 116.4074 - 0.5
    var maxLatitude: Double = 39.9042 + 0.5
    var maxLongitude: Double = 116.4074 + 0.5

    var centerLatitude: Double = 39.9042
    var centerLongitude: Double = 116.4074

    var tileSize: Int = 256

    fun open(): Boolean {
        if (!file.exists()) {
            Log.e("MapFileHelper", "File does not exist: ${file.absolutePath}")
            return false
        }
        
        fileSizeValue = file.length()
        if (fileSizeValue < 62) {
            Log.e("MapFileHelper", "File size is too small to be a Mapsforge map file.")
            return false
        }

        return try {
            FileInputStream(file).use { fis ->
                val headerBytes = ByteArray(100)
                val bytesRead = fis.read(headerBytes)
                if (bytesRead < 62) {
                    return false
                }

                // Verify magic bytes. Standard is "mapsforge binary map" (20 bytes)
                val magicString = String(headerBytes, 0, 20, Charsets.US_ASCII)
                if (!magicString.startsWith("mapsforge")) {
                    Log.w("MapFileHelper", "File magic bytes might not be Mapsforge: $magicString")
                }

                val buffer = ByteBuffer.wrap(headerBytes)
                
                // Seek past the 20 magic bytes
                buffer.position(20)
                
                // 1. Header size (4 bytes)
                val headerSize = buffer.int
                
                // 2. Format version (4 bytes)
                formatVersion = buffer.int
                
                // 3. File size (8 bytes)
                val mappedFileSize = buffer.long
                
                // 4. Creation date / timestamp (8 bytes)
                createTimestamp = buffer.long
                
                // 5. Bounding box (minLat, minLon, maxLat, maxLon) in microdegrees (4 bytes each)
                val minLatMicro = buffer.int
                val minLonMicro = buffer.int
                val maxLatMicro = buffer.int
                val maxLonMicro = buffer.int
                
                // Convert microdegrees to standard Double degree coordinates
                minLatitude = minLatMicro / 1000000.0
                minLongitude = minLonMicro / 1000000.0
                maxLatitude = maxLatMicro / 1000000.0
                maxLongitude = maxLonMicro / 1000000.0

                // Sanity check coordinates boundary limits to prevent overflow representation corruption
                if (minLatitude < -90.0 || minLatitude > 90.0 || maxLatitude < -90.0 || maxLatitude > 90.0 ||
                    minLongitude < -180.0 || minLongitude > 180.0 || maxLongitude < -180.0 || maxLongitude > 180.0) {
                    Log.w("MapFileHelper", "Parsed coordinates out of bounds. Using default box.")
                    minLatitude = 39.4
                    minLongitude = 115.9
                    maxLatitude = 40.4
                    maxLongitude = 116.9
                }

                centerLatitude = (minLatitude + maxLatitude) / 2.0
                centerLongitude = (minLongitude + maxLongitude) / 2.0

                // 6. Tile Size (2 bytes)
                tileSize = buffer.short.toInt() and 0xFFFF
                if (tileSize <= 0) tileSize = 256

                isOpened = true
                Log.d("MapFileHelper", "Parsed Mapsforge header: Version=$formatVersion, Bounds=[$minLatitude, $minLongitude, $maxLatitude, $maxLongitude], Center=[$centerLatitude, $centerLongitude]")
                true
            }
        } catch (e: Exception) {
            Log.e("MapFileHelper", "Failed to parse Mapsforge map file header", e)
            isOpened = false
            false
        }
    }

    fun close() {
        isOpened = false
    }

    fun getMetadata(): Map<String, String> {
        return mapOf(
            "format" to "Mapsforge Vector Map (.map)",
            "version" to formatVersion.toString(),
            "file_size" to String.format("%.2f MB", fileSizeValue / (1024f * 1024f)),
            "min_lat" to String.format("%.6f", minLatitude),
            "min_lon" to String.format("%.6f", minLongitude),
            "max_lat" to String.format("%.6f", maxLatitude),
            "max_lon" to String.format("%.6f", maxLongitude),
            "center_lat" to String.format("%.6f", centerLatitude),
            "center_lon" to String.format("%.6f", centerLongitude),
            "tile_size" to "$tileSize px"
        )
    }
}
