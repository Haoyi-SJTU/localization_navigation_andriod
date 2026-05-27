package com.example.map

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

class MBTilesHelper(private val file: File) {
    private var database: SQLiteDatabase? = null
    var isOpened = false
        private set

    fun open(): Boolean {
        return try {
            if (!file.exists()) {
                Log.e("MBTilesHelper", "Database file does not exist: ${file.absolutePath}")
                return false
            }
            database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            isOpened = true
            Log.d("MBTilesHelper", "Successfully opened MBTiles DB: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e("MBTilesHelper", "Failed to open MBTiles database: ${file.absolutePath}", e)
            isOpened = false
            false
        }
    }

    fun close() {
        try {
            database?.close()
        } catch (e: Exception) {
            Log.e("MBTilesHelper", "Error closing database", e)
        }
        database = null
        isOpened = false
    }

    fun getTile(zoom: Int, column: Int, row: Int): Bitmap? {
        val db = database ?: return null
        
        // MBTiles standard uses TMS coords (origin bottom-left) rather than OSM Slippy standard (origin top-left).
        // TMS Row = (2^Zoom) - 1 - OSM Row
        val tmsRow = (1 shl zoom) - 1 - row

        var bitmap: Bitmap? = null
        var cursor: android.database.Cursor? = null
        try {
            cursor = db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(zoom.toString(), column.toString(), tmsRow.toString())
            )
            if (cursor != null && cursor.moveToFirst()) {
                val bytes = cursor.getBlob(0)
                if (bytes != null && bytes.isNotEmpty()) {
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (e: Exception) {
            Log.e("MBTilesHelper", "Failed to query tile at Z=$zoom, X=$column, Y=$row", e)
        } finally {
            cursor?.close()
        }
        return bitmap
    }

    fun getMetadata(): Map<String, String> {
        val db = database ?: return emptyMap()
        val metadataMap = mutableMapOf<String, String>()
        var cursor: android.database.Cursor? = null
        try {
            cursor = db.rawQuery("SELECT name, value FROM metadata", null)
            if (cursor != null) {
                val nameIndex = cursor.getColumnIndex("name")
                val valueIndex = cursor.getColumnIndex("value")
                if (nameIndex >= 0 && valueIndex >= 0) {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex)
                        val value = cursor.getString(valueIndex)
                        if (name != null && value != null) {
                            metadataMap[name] = value
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("MBTilesHelper", "Metadata query omitted or failed: ${e.message}")
        } finally {
            cursor?.close()
        }
        return metadataMap
    }
}
