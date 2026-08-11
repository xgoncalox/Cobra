package com.facerecog.app

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Talks directly to Supabase's REST API (PostgREST, for the "persons" and
 * "embeddings" tables) and Storage HTTP API (for face photos), over plain
 * HTTPS with OkHttp. No Supabase SDK needed.
 *
 * Requires two Postgres tables and one public storage bucket to exist in your
 * Supabase project first - see README.md for the exact SQL/setup steps.
 */
class SupabaseRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = SupabaseConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = SupabaseConfig.SUPABASE_ANON_KEY

    private fun restRequest(path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl/rest/v1/$path")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $body")
            }
            body
        }
    }

    // ---------- Persons ----------

    suspend fun getAllPersons(): List<Person> {
        val req = restRequest("persons?select=*").get().build()
        val body = execute(req)
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i -> parsePerson(arr.getJSONObject(i)) }
    }

    suspend fun getPersonById(id: String): Person? {
        val req = restRequest("persons?id=eq.$id&select=*").get().build()
        val body = execute(req)
        val arr = JSONArray(body)
        return if (arr.length() > 0) parsePerson(arr.getJSONObject(0)) else null
    }

    suspend fun createPerson(name: String, thumbnailUrl: String?): String {
        val json = JSONObject().apply {
            put("name", name)
            put("thumbnail_url", thumbnailUrl)
            put("created_at", System.currentTimeMillis())
        }
        val req = restRequest("persons")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=representation")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = execute(req)
        val arr = JSONArray(body)
        return arr.getJSONObject(0).getString("id")
    }

    suspend fun updatePersonThumbnail(personId: String, thumbnailUrl: String) {
        val json = JSONObject().apply { put("thumbnail_url", thumbnailUrl) }
        val req = restRequest("persons?id=eq.$personId")
            .header("Content-Type", "application/json")
            .patch(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        execute(req)
    }

    suspend fun updatePersonName(personId: String, newName: String) {
        val json = JSONObject().apply { put("name", newName) }
        val req = restRequest("persons?id=eq.$personId")
            .header("Content-Type", "application/json")
            .patch(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        execute(req)
    }

    suspend fun deletePerson(personId: String) {
        // Delete embeddings first, then the person row.
        execute(restRequest("embeddings?person_id=eq.$personId").delete().build())
        execute(restRequest("persons?id=eq.$personId").delete().build())
    }

    // ---------- Embeddings ----------

    suspend fun getAllEmbeddings(): List<FaceEmbeddingEntity> {
        val req = restRequest("embeddings?select=*").get().build()
        val body = execute(req)
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i -> parseEmbedding(arr.getJSONObject(i)) }
    }

    suspend fun getEmbeddingsForPerson(personId: String): List<FaceEmbeddingEntity> {
        val req = restRequest("embeddings?person_id=eq.$personId&select=*").get().build()
        val body = execute(req)
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i -> parseEmbedding(arr.getJSONObject(i)) }
    }

    suspend fun deleteEmbedding(embeddingId: String) {
        execute(restRequest("embeddings?id=eq.$embeddingId").delete().build())
    }

    suspend fun addEmbedding(personId: String, vector: FloatArray, imageUrl: String?) {
        val vectorArr = JSONArray()
        vector.forEach { vectorArr.put(it.toDouble()) }
        val json = JSONObject().apply {
            put("person_id", personId)
            put("vector", vectorArr)
            put("image_url", imageUrl)
        }
        val req = restRequest("embeddings")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        execute(req)
    }

    // ---------- Storage (face photos) ----------

    /** Uploads a face photo to the public "faces" bucket and returns its public URL. */
    suspend fun uploadFaceImage(bitmap: Bitmap, personId: String): String = withContext(Dispatchers.IO) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val bytes = baos.toByteArray()
        val filename = "${personId}/${UUID.randomUUID()}.jpg"

        val request = Request.Builder()
            .url("$baseUrl/storage/v1/object/faces/$filename")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "image/jpeg")
            .post(bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Upload failed: HTTP ${response.code}: ${response.body?.string()}")
            }
        }

        "$baseUrl/storage/v1/object/public/faces/$filename"
    }

    // ---------- Combined convenience operations ----------

    suspend fun createPersonWithFace(name: String, bitmap: Bitmap, vector: FloatArray): String {
        val personId = createPerson(name, thumbnailUrl = null)
        val imageUrl = uploadFaceImage(bitmap, personId)
        updatePersonThumbnail(personId, imageUrl)
        addEmbedding(personId, vector, imageUrl)
        return personId
    }

    suspend fun addFaceToPerson(personId: String, bitmap: Bitmap, vector: FloatArray) {
        val imageUrl = uploadFaceImage(bitmap, personId)
        addEmbedding(personId, vector, imageUrl)
    }

    // ---------- JSON parsing helpers ----------

    private fun parsePerson(obj: JSONObject): Person = Person(
        id = obj.getString("id"),
        name = obj.optString("name", ""),
        thumbnailUrl = obj.optString("thumbnail_url", null).takeIf { it != "null" },
        createdAt = obj.optLong("created_at", 0L)
    )

    private fun parseEmbedding(obj: JSONObject): FaceEmbeddingEntity {
        val vectorArr = obj.optJSONArray("vector") ?: JSONArray()
        val vector = (0 until vectorArr.length()).map { vectorArr.getDouble(it) }
        return FaceEmbeddingEntity(
            id = obj.getString("id"),
            personId = obj.getString("person_id"),
            vector = vector,
            imageUrl = obj.optString("image_url", null).takeIf { it != "null" }
        )
    }

    companion object {
        @Volatile private var INSTANCE: SupabaseRepository? = null
        fun getInstance(): SupabaseRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SupabaseRepository().also { INSTANCE = it }
            }
    }
}
