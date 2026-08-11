package com.facerecog.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Insert
    suspend fun insertPerson(person: Person): Long

    @Update
    suspend fun updatePerson(person: Person)

    @Delete
    suspend fun deletePerson(person: Person)

    @Query("SELECT * FROM persons ORDER BY name ASC")
    fun getAllPersons(): Flow<List<Person>>

    @Query("SELECT * FROM persons ORDER BY name ASC")
    suspend fun getAllPersonsList(): List<Person>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun getPersonById(id: Long): Person?

    @Insert
    suspend fun insertEmbedding(embedding: FaceEmbeddingEntity): Long

    @Delete
    suspend fun deleteEmbedding(embedding: FaceEmbeddingEntity)

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE personId = :personId")
    suspend fun getEmbeddingsForPerson(personId: Long): List<FaceEmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE personId = :personId")
    suspend fun deleteEmbeddingsForPerson(personId: Long)

    @Query("SELECT COUNT(*) FROM embeddings WHERE personId = :personId")
    suspend fun countEmbeddingsForPerson(personId: Long): Int
}
