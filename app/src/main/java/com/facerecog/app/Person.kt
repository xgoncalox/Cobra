package com.facerecog.app

/** A person "folder" stored in Firestore collection "persons". */
data class Person(
    val id: String = "",
    val name: String = "",
    val thumbnailUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One saved face sample for a person, stored in Firestore collection "embeddings".
 * The vector is stored as a list of doubles (Firestore doesn't support float arrays directly).
 */
data class FaceEmbeddingEntity(
    val id: String = "",
    val personId: String = "",
    val vector: List<Double> = emptyList(),
    val imageUrl: String? = null
) {
    fun vectorAsFloatArray(): FloatArray = FloatArray(vector.size) { vector[it].toFloat() }
}
