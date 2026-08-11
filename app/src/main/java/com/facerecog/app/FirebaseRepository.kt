package com.facerecog.app

import android.graphics.Bitmap
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * All data (people + face embeddings + photos) lives in a shared Firebase project,
 * so every device using the app sees the same people. Read/query operations are
 * open to anyone using the app; only the Admin screen (gated by a 10-digit code,
 * see AdminGate) exposes destructive actions like rename/delete.
 */
class FirebaseRepository {

    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val personsCol = db.collection("persons")
    private val embeddingsCol = db.collection("embeddings")

    /** Anonymous sign-in so Firestore/Storage security rules can require "request.auth != null". */
    suspend fun ensureSignedIn() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    suspend fun getAllPersons(): List<Person> {
        val snapshot = personsCol.get().await()
        return snapshot.documents.mapNotNull { doc ->
            doc.toObject(Person::class.java)?.copy(id = doc.id)
        }
    }

    suspend fun getPersonById(id: String): Person? {
        val doc = personsCol.document(id).get().await()
        return doc.toObject(Person::class.java)?.copy(id = doc.id)
    }

    suspend fun createPerson(name: String, thumbnailUrl: String?): String {
        val data = hashMapOf(
            "name" to name,
            "thumbnailUrl" to thumbnailUrl,
            "createdAt" to System.currentTimeMillis()
        )
        val ref = personsCol.add(data).await()
        return ref.id
    }

    suspend fun updatePersonName(personId: String, newName: String) {
        personsCol.document(personId).update("name", newName).await()
    }

    suspend fun deletePerson(personId: String) {
        // Delete embeddings first, then the person doc.
        val embeddings = embeddingsCol.whereEqualTo("personId", personId).get().await()
        for (doc in embeddings.documents) {
            doc.reference.delete().await()
        }
        personsCol.document(personId).delete().await()
    }

    suspend fun getAllEmbeddings(): List<FaceEmbeddingEntity> {
        val snapshot = embeddingsCol.get().await()
        return snapshot.documents.mapNotNull { doc ->
            @Suppress("UNCHECKED_CAST")
            val vectorRaw = doc.get("vector") as? List<Double> ?: return@mapNotNull null
            FaceEmbeddingEntity(
                id = doc.id,
                personId = doc.getString("personId") ?: return@mapNotNull null,
                vector = vectorRaw,
                imageUrl = doc.getString("imageUrl")
            )
        }
    }

    suspend fun getEmbeddingsForPerson(personId: String): List<FaceEmbeddingEntity> {
        val snapshot = embeddingsCol.whereEqualTo("personId", personId).get().await()
        return snapshot.documents.mapNotNull { doc ->
            @Suppress("UNCHECKED_CAST")
            val vectorRaw = doc.get("vector") as? List<Double> ?: return@mapNotNull null
            FaceEmbeddingEntity(
                id = doc.id,
                personId = personId,
                vector = vectorRaw,
                imageUrl = doc.getString("imageUrl")
            )
        }
    }

    suspend fun deleteEmbedding(embeddingId: String) {
        embeddingsCol.document(embeddingId).delete().await()
    }

    /** Uploads a face bitmap to Storage and returns its public download URL. */
    suspend fun uploadFaceImage(bitmap: Bitmap, personId: String): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val bytes = baos.toByteArray()
        val filename = "${UUID.randomUUID()}.jpg"
        val ref = storage.reference.child("faces/$personId/$filename")
        ref.putBytes(bytes).await()
        val uri: Uri = ref.downloadUrl.await()
        return uri.toString()
    }

    suspend fun addEmbedding(personId: String, vector: FloatArray, imageUrl: String?) {
        val data = hashMapOf(
            "personId" to personId,
            "vector" to vector.map { it.toDouble() },
            "imageUrl" to imageUrl
        )
        embeddingsCol.add(data).await()
    }

    /** Creates a new person, uploads their first face photo, and saves the embedding. */
    suspend fun createPersonWithFace(name: String, bitmap: Bitmap, vector: FloatArray): String {
        val personId = createPerson(name, thumbnailUrl = null)
        val imageUrl = uploadFaceImage(bitmap, personId)
        personsCol.document(personId).update("thumbnailUrl", imageUrl).await()
        addEmbedding(personId, vector, imageUrl)
        return personId
    }

    /** Adds another face sample to an existing person. */
    suspend fun addFaceToPerson(personId: String, bitmap: Bitmap, vector: FloatArray) {
        val imageUrl = uploadFaceImage(bitmap, personId)
        addEmbedding(personId, vector, imageUrl)
    }

    companion object {
        @Volatile private var INSTANCE: FirebaseRepository? = null
        fun getInstance(): FirebaseRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseRepository().also { INSTANCE = it }
            }
    }
}
