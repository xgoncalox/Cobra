package com.facerecog.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val thumbnailPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "embeddings")
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val vector: FloatArray,
    val imagePath: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEmbeddingEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
