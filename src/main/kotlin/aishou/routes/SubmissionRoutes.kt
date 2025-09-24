package aishou.routes

import aishou.config.graph
import aishou.domain.model.Submission
import aishou.domain.model.TestQuestion
import aishou.domain.model.User
import aishou.domain.model.SolvedQuiz
import aishou.domain.model.BaseResponse
import aishou.domain.model.Invite
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

@Serializable
data class SubmissionRequest(val answers: Map<String, String>)

private fun calculatePersonalityScores(answers: Map<String, String>, questions: List<TestQuestion>): Map<String, Int> {
  val scores = mutableMapOf<String, Int>()

  questions.forEach { question ->
    val userAnswer = answers[question.index.toString()]
    if (userAnswer != null && question.weights != null) {
      // Get weights for the selected choice
      val choiceWeights = question.weights[userAnswer]
      choiceWeights?.forEach { (dimension, weight) ->
        scores[dimension] = (scores[dimension] ?: 0) + weight
      }
    }
  }

  return scores
}

private fun calculateScoreRange(questions: List<TestQuestion>): Pair<Int, Int> {
  var minPossible = 0
  var maxPossible = 0

  questions.forEach { question ->
    if (question.weights != null) {
      // For each question, find the choice that gives minimum and maximum total score
      val choiceScoreSums = question.weights.map { (choiceKey, dimensionWeights) ->
        dimensionWeights.values.sum()
      }

      if (choiceScoreSums.isNotEmpty()) {
        minPossible += choiceScoreSums.minOrNull() ?: 0
        maxPossible += choiceScoreSums.maxOrNull() ?: 0
      }
    }
  }

  return Pair(minPossible, maxPossible)
}

fun Route.submissionRoutes() {
  authenticate("auth") {
    put("/v1/submissions/{testId}/{version}") {
      val uid = call.principal<JWTPrincipal>()!!.payload.subject
      val testId = call.parameters["testId"]!!
      val version = call.parameters["version"]!!.toInt()
      val inviteId = call.request.queryParameters["inviteId"] // If provided, this is an invite response
      val r = call.receive<SubmissionRequest>()

      // Check if this is an invite response and validate invite
      val isInviteResponse = inviteId != null
      if (isInviteResponse) {
        println("DEBUG: This submission is for invite: $inviteId")
        val inviteCol = graph.mongo.db.getCollection<Invite>("invites")
        val invite = inviteCol.findOne(Invite::_id eq inviteId!!)

        if (invite == null) {
          println("DEBUG: Invite $inviteId not found")
          return@put call.respond(HttpStatusCode.NotFound, "Invite not found")
        }

        if (invite.status != "accepted") {
          println("DEBUG: Invite $inviteId not accepted, status: ${invite.status}")
          return@put call.respond(HttpStatusCode.BadRequest, "Invite not accepted")
        }

        if (invite.testId != testId || invite.version != version) {
          println("DEBUG: Invite test mismatch - Expected: ${invite.testId}v${invite.version}, Got: ${testId}v${version}")
          return@put call.respond(HttpStatusCode.BadRequest, "Test ID/version mismatch")
        }

        if (invite.toUserId != uid) {
          println("DEBUG: Invite recipient mismatch - Expected: ${invite.toUserId}, Got: $uid")
          return@put call.respond(HttpStatusCode.Forbidden, "Invite not for this user")
        }

        println("DEBUG: Invite validation passed for $inviteId")
      } else {
        println("DEBUG: This is a solo submission (no invite)")
      }

      // Get questions for OpenAI analysis
      val questionsCol = graph.mongo.db.getCollection<TestQuestion>("testQuestions")
      val questions = questionsCol.find(and(TestQuestion::testId eq testId, TestQuestion::version eq version))
        .sort(ascending(TestQuestion::index)).toList()

      // Calculate personality scores using weights
      val personalityScores = calculatePersonalityScores(r.answers, questions)
      println("DEBUG: Personality scores for test $testId: $personalityScores")

      // Get user's profile with personality types and determine language
      val userCol = graph.mongo.db.getCollection<User>("users")
      val user = userCol.findOne(User::_id eq uid)
      val userLang = getLanguageFromRequest(call, user)
      println("DEBUG: Language determined for submission insights: $userLang (header: ${call.request.headers["Accept-Language"]}, user: ${user?.lang})")

      // Generate personalized insights only for premium users
      val personalizedInsights = if (user?.isPremium == true &&
          user.personalityAssessed == true &&
          user.mbtiType != null &&
          user.zodiacSign != null &&
          (user.premiumExpiresAt == null || user.premiumExpiresAt > System.currentTimeMillis())) {
        try {
          graph.openAI.generatePersonalizedInsights(r.answers, questions, user.mbtiType!!, user.zodiacSign!!, userLang, personalityScores)
        } catch (e: Exception) {
          null
        }
      } else {
        // Non-premium users get null - client will show upgrade UI
        null
      }

      // Calculate total score dynamically based on actual weight ranges
      val totalScore = if (personalityScores.isNotEmpty()) {
        // Calculate the theoretical maximum and minimum possible scores based on question weights
        val (minPossible, maxPossible) = calculateScoreRange(questions)
        println("DEBUG: Score range for test $testId: min=$minPossible, max=$maxPossible")

        // Sum all positive scores (ignore negative/opposite dimensions to avoid double counting)
        val currentScore = personalityScores.values.sum()
        println("DEBUG: Current total score sum: $currentScore")

        // Normalize to 0-100 based on actual possible range
        if (maxPossible > minPossible) {
          val normalizedScore = ((currentScore - minPossible).toDouble() / (maxPossible - minPossible) * 100).toInt()
          println("DEBUG: Normalized score: $normalizedScore")
          normalizedScore.coerceIn(0, 100)
        } else {
          // Fallback: use percentage of maximum possible positive score
          val maxPositive = personalityScores.values.filter { it > 0 }.sum()
          val maxTheoreticalPositive = questions.sumOf { question ->
            question.weights?.values?.sumOf { weights ->
              weights.values.filter { it > 0 }.sum()
            } ?: 0
          }
          println("DEBUG: Fallback - maxPositive: $maxPositive, maxTheoreticalPositive: $maxTheoreticalPositive")

          if (maxTheoreticalPositive > 0) {
            val fallbackScore = ((maxPositive.toDouble() / maxTheoreticalPositive) * 100).toInt().coerceIn(0, 100)
            println("DEBUG: Fallback score: $fallbackScore")
            fallbackScore
          } else {
            println("DEBUG: Using default score 50")
            50 // Default middle score
          }
        }
      } else {
        println("DEBUG: No personality scores, using default 50")
        50 // Default middle score if no scores available
      }

      // Convert personality scores to score vector for compatibility matching
      val scoreVector = listOf(
        personalityScores["extrovert"] ?: 0,
        personalityScores["introvert"] ?: 0,
        personalityScores["thinking"] ?: 0,
        personalityScores["feeling"] ?: 0,
        personalityScores["judging"] ?: 0,
        personalityScores["perceiving"] ?: 0,
        personalityScores["sensing"] ?: 0,
        personalityScores["intuition"] ?: 0
      )

      val sub = Submission(null, testId, version, uid, r.answers, scoreVector, totalScore, personalizedInsights, isInviteResponse)
      val submissionCol = graph.mongo.db.getCollection<Submission>("submissions")
      submissionCol.updateOne(and(Submission::testId eq testId, Submission::version eq version, Submission::uid eq uid), sub, UpdateOptions().upsert(true))

      // Mark quiz as solved for this user
      val solvedQuiz = SolvedQuiz(testId, version, System.currentTimeMillis(), sub._id?.toString())

      // Update user's solved quizzes list
      userCol.updateOne(
        User::_id eq uid,
        addToSet(User::solvedQuizzes, solvedQuiz)
      )

      // Return the submission with analysis
      call.respond(BaseResponse(status = "success", data = sub))
    }
  }
}