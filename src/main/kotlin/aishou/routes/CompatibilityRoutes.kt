package aishou.routes

import aishou.config.graph
import aishou.domain.model.*
import aishou.infra.security.sha256
import aishou.infra.security.sortedPairId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.litote.kmongo.*
import com.mongodb.client.model.UpdateOptions
import kotlinx.serialization.Serializable

// Helper function to get language from header or user profile
private fun getLanguageFromRequest(call: ApplicationCall, user: User?): String {
    // First check Accept-Language header
    val headerLang = call.request.headers["Accept-Language"]?.lowercase()?.split(",")?.firstOrNull()?.split("-")?.firstOrNull()

    return when {
        headerLang == "ja" || headerLang == "jp" -> "ja"
        headerLang == "en" -> "en"
        user?.lang == "ja" || user?.lang == "jp" -> "ja"
        user?.lang == "en" -> "en"
        else -> "en" // Default to English
    }
}

// Helper function to create analysis objects from stored types
private fun createAnalysisFromTypes(mbtiType: String, zodiacSign: String): PersonalityAnalysis {
  return PersonalityAnalysis(
    mbti = MBTIAnalysis(
      type = mbtiType,
      description = "Stored MBTI type",
      strengths = emptyList(),
      weaknesses = emptyList(),
      summary = "Using stored MBTI type for compatibility analysis"
    ),
    zodiac = ZodiacAnalysis(
      sign = zodiacSign,
      element = getZodiacElement(zodiacSign),
      description = "Stored zodiac sign",
      traits = emptyList(),
      summary = "Using stored zodiac sign for compatibility analysis"
    )
  )
}

// Helper function to get zodiac element
private fun getZodiacElement(sign: String): String {
  return when (sign.lowercase()) {
    "aries", "leo", "sagittarius" -> "Fire"
    "taurus", "virgo", "capricorn" -> "Earth"
    "gemini", "libra", "aquarius" -> "Air"
    "cancer", "scorpio", "pisces" -> "Water"
    else -> "Unknown"
  }
}

fun Route.compatibilityRoutes() {
  authenticate("auth") {
    post("/v1/compatibility/compute") {
      @Serializable
      data class Req(val testId:String, val version:Int, val friendId:String)
      val uidA = call.principal<JWTPrincipal>()!!.payload.subject
      val r = call.receive<Req>()

      val subs = graph.mongo.db.getCollection<Submission>("submissions")
      val a = subs.findOne(and(Submission::testId eq r.testId, Submission::version eq r.version, Submission::uid eq uidA))
      val b = subs.findOne(and(Submission::testId eq r.testId, Submission::version eq r.version, Submission::uid eq r.friendId))
      if (a==null || b==null) return@post call.respond(HttpStatusCode.PreconditionFailed, "Both must submit")

      val pairId = sortedPairId(uidA, r.friendId)
      val docId = "${r.testId}_${r.version}_${pairId}"
      val inputHash = sha256("${a!!.answers}|${b!!.answers}|${r.testId}|${r.version}")

      val compCol = graph.mongo.db.getCollection<Compatibility>("compatibility")
      val cached = compCol.findOne(Compatibility::_id eq docId)
      if (cached != null && cached.inputHash == inputHash) return@post call.respond(BaseResponse(status = "success", data = cached))

      // Get questions for OpenAI analysis
      val questionsCol = graph.mongo.db.getCollection<TestQuestion>("testQuestions")
      val questions = questionsCol.find(and(TestQuestion::testId eq r.testId, TestQuestion::version eq r.version))
        .sort(ascending(TestQuestion::index)).toList()

      // Get user's language preference (use requesting user's language)
      val userCol = graph.mongo.db.getCollection<User>("users")
      val user = userCol.findOne(User::_id eq uidA)
      val userLang = getLanguageFromRequest(call, user)
      println("DEBUG: Language determined for compatibility analysis: $userLang (header: ${call.request.headers["Accept-Language"]}, user: ${user?.lang})")

      // Get both users' personality types
      val userA = userCol.findOne(User::_id eq uidA)
      val userB = userCol.findOne(User::_id eq r.friendId)

      if (userA?.personalityAssessed != true || userB?.personalityAssessed != true) {
        return@post call.respond(HttpStatusCode.PreconditionFailed, "Both users must complete personality assessment first")
      }

      // Generate compatibility analysis using stored personality types
      val (aiScore, matchingAnalysis, chemistry) = try {
        // Create analysis objects from stored types
        val analysisA = createAnalysisFromTypes(userA.mbtiType!!, userA.zodiacSign!!)
        val analysisB = createAnalysisFromTypes(userB.mbtiType!!, userB.zodiacSign!!)

        graph.openAI.analyzeCompatibility(a.answers, b.answers, analysisA, analysisB, questions, userLang)
      } catch (e: Exception) {
        // Fallback to simple compatibility if OpenAI fails
        Triple(75, null, "Great Chemistry")
      }

      val score = aiScore
      val summary = matchingAnalysis?.overallCompatibility ?: "Good compatibility potential"
      val explanations = listOf(
        Compatibility.Explanation("MBTI Compatibility", matchingAnalysis?.mbtiMatch?.explanation ?: "Analysis pending"),
        Compatibility.Explanation("Zodiac Compatibility", matchingAnalysis?.zodiacMatch?.explanation ?: "Analysis pending")
      )

      val payload = Compatibility(docId, r.testId, r.version, uidA, r.friendId, pairId, score, summary, explanations, matchingAnalysis, chemistry, inputHash)
      compCol.updateOne(Compatibility::_id eq docId, payload, UpdateOptions().upsert(true))

      val pairing = Pairing(docId, r.testId, r.version, uidA, r.friendId, a._id?.toString(), b._id?.toString())
      val pairCol = graph.mongo.db.getCollection<Pairing>("pairings")
      pairCol.updateOne(Pairing::_id eq docId, pairing, UpdateOptions().upsert(true))

      // Send compatibility ready notification to both users
      try {
        println("DEBUG: Compatibility computed - Score: $score, Test: ${r.testId}, Users: $uidA & ${r.friendId}")

        val devices = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")

        // Get most recent device for both users
        val userAPlayerIds = devices.find(DeviceReg::userId eq uidA)
          .sort(org.bson.Document("createdAt", -1))
          .limit(1)
          .toList().map { it.playerId }
        val userBPlayerIds = devices.find(DeviceReg::userId eq r.friendId)
          .sort(org.bson.Document("createdAt", -1))
          .limit(1)
          .toList().map { it.playerId }
        val allPlayerIds = userAPlayerIds + userBPlayerIds

        println("DEBUG: Found devices - UserA: ${userAPlayerIds.size}, UserB: ${userBPlayerIds.size}")
        println("DEBUG: Player IDs for compatibility notification: $allPlayerIds")

        if (allPlayerIds.isNotEmpty()) {
          // Send notification to both users about compatibility results
          println("DEBUG: Sending compatibility ready notification - Score: $score%, Test: ${r.testId}")
          graph.oneSignal.sendCompatibilityReady(allPlayerIds, score, r.testId)
        } else {
          println("DEBUG: No devices found for users, compatibility notification not sent")
        }
      } catch (e: Exception) {
        println("ERROR: Failed to send compatibility ready notification: ${e.message}")
        e.printStackTrace()
      }

      call.respond(BaseResponse(status = "success", data = payload))
    }
  }
}