package com.example.data

import kotlinx.coroutines.flow.Flow

class TrackRepository(private val trackDao: TrackDao) {
    val allTracks: Flow<List<Track>> = trackDao.getAllTracks()

    suspend fun getTrackById(trackId: Long): Track? {
        return trackDao.getTrackById(trackId)
    }

    suspend fun startTrack(name: String, description: String = ""): Long {
        val track = Track(
            name = name,
            description = description,
            startTime = System.currentTimeMillis()
        )
        return trackDao.insertTrack(track)
    }

    suspend fun updateTrackInfo(track: Track) {
        trackDao.updateTrack(track)
    }

    suspend fun deleteTrack(track: Track) {
        trackDao.deleteTrack(track)
    }

    suspend fun savePoint(point: TrackPoint): Long {
        return trackDao.insertTrackPoint(point)
    }

    fun getPointsForTrack(trackId: Long): Flow<List<TrackPoint>> {
        return trackDao.getPointsForTrack(trackId)
    }

    suspend fun getPointsForTrackSync(trackId: Long): List<TrackPoint> {
        return trackDao.getPointsForTrackSync(trackId)
    }
}
