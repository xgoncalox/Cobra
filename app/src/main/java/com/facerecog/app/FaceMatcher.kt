package com.facerecog.app

data class MatchResult(val personId: String?, val personName: String, val score: Float)

/**
 * Keeps all known embeddings (fetched from the shared Firebase database) in memory
 * for fast linear-scan matching. Call refreshCache() after adding/removing people
 * or periodically to pick up faces added by other devices.
 */
class FaceMatcher(private val repo: FirebaseRepository) {

    private var cache: List<Triple<String, String, FloatArray>> = emptyList()

    suspend fun refreshCache() {
        val embeddings = repo.getAllEmbeddings()
        val persons = repo.getAllPersons()
        val nameById = HashMap<String, String>()
        persons.forEach { nameById[it.id] = it.name }
        cache = embeddings.mapNotNull { emb ->
            val name = nameById[emb.personId]
            if (name != null) Triple(emb.personId, name, emb.vectorAsFloatArray()) else null
        }
    }

    fun match(queryVector: FloatArray): MatchResult {
        var bestScore = -1f
        var bestId: String? = null
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
}        } else {
            MatchResult(null, "Unknown", bestScore)
        }
    }
}
