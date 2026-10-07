package org.underfoot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Query("SELECT * FROM sessions ORDER BY startMs DESC") fun observeAll(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE synced = 0 ORDER BY startMs") suspend fun unsynced(): List<SessionEntity>
    @Query("UPDATE sessions SET synced = 1 WHERE id = :id") suspend fun markSynced(id: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1") fun observe(): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile WHERE id = 1") suspend fun get(): ProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: ProfileEntity)
}
