package com.facerecog.app

import android.content.Context

data class MatchResult(val personId: Long?, val personName: String, val score: Float)

class FaceMatcher(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private var cache: List<Triple<Long, String, FloatArray>> = emptyList()

    suspend fun refreshCache() {
        val embeddings = db.personDao().getAllEmbeddings()
        val persons = db.personDao().getAllPersonsList()
        val personNameCache = HashMap<Long, String>()
        persons.forEach { personNameCache[it.id] = it.name }
        cache = embeddings.mapNotNull { emb ->
            val name = personNameCache[emb.personId]
            if (name != null) Triple(emb.personId, name, emb.vector) else null
        }
    }

    fun match(queryVector: FloatArray): MatchResult {
        var bestScore = -1f
        var bestId: Long? = null
        var bestName = "Unknown"
        for ((personId, name, vector) in cache) {
            val score = FaceEmbedder.cosineSimilarity(queryVector, vector)
            if (score > bestScore) {
                bestScore = score
                bestId = personId
                bestName = name
            }
        }
        return if (bestScore >= FaceEmbedder.MATCH_THRESHOLD) {
            MatchResult(bestId, bestName, bestScore)
        } else {
            MatchResult(null, "Unknown", bestScore)
        }
    }
}
