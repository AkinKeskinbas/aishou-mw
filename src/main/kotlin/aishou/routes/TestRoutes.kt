package aishou.routes

import aishou.config.graph
import aishou.domain.model.TestMeta
import aishou.domain.model.TestQuestion
import aishou.domain.model.TestDisplay
import aishou.domain.model.User
import aishou.domain.model.BaseResponse
import aishou.domain.model.Submission
import aishou.domain.model.Compatibility
import aishou.domain.model.MatchingAnalysis
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.litote.kmongo.*
import kotlinx.serialization.Serializable

fun Route.testRoutes() {
    route("/v1/tests") {
        get {
            // Get language preference from header or query parameter
            val langParam = call.request.queryParameters["lang"]
            val acceptLangHeader = call.request.headers["Accept-Language"]
            val acceptLangParsed = acceptLangHeader?.lowercase()?.split(",")?.firstOrNull()?.split("-")?.firstOrNull()

            val requestedLang = langParam ?: acceptLangParsed ?: "en"
            val normalizedLang = when (requestedLang.lowercase()) {
                "ja", "jp" -> "ja"
                "zh", "zh-cn", "zh-hans", "cn" -> "zh"
                "ko", "kr" -> "ko"
                else -> "en"
            }

            val col = graph.mongo.db.getCollection<TestMeta>("tests")

            // First try to get tests in requested language
            val testsInLang = col.find(TestMeta::locale eq normalizedLang).toList()

            val finalTests = testsInLang.ifEmpty {
                // Fallback to English tests
                col.find(TestMeta::locale eq "en").toList()
            }

            call.respond(BaseResponse(status = "success", data = finalTests))
        }

        // Get tests with completion status for authenticated user
        authenticate("auth") {
            get("/status") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject

                // Get language preference from header or query parameter
                val langParam = call.request.queryParameters["lang"]
                val acceptLangHeader = call.request.headers["Accept-Language"]
                val acceptLangParsed = acceptLangHeader?.lowercase()?.split(",")?.firstOrNull()?.split("-")?.firstOrNull()

                val requestedLang = langParam ?: acceptLangParsed ?: "en"
                val normalizedLang = when (requestedLang.lowercase()) {
                    "ja", "jp" -> "ja"
                    "zh", "zh-cn", "zh-hans", "cn" -> "zh"
                    "ko", "kr" -> "ko"
                    else -> "en"
                }

                val testsCol = graph.mongo.db.getCollection<TestMeta>("tests")
                val userCol = graph.mongo.db.getCollection<User>("users")

                // Get tests in requested language with fallback to English
                val testsInLang = testsCol.find(TestMeta::locale eq normalizedLang).toList()
                val tests = if (testsInLang.isNotEmpty()) {
                    testsInLang
                } else {
                    testsCol.find(TestMeta::locale eq "en").toList()
                }
                val user = userCol.findOne(User::_id eq uid)
                val solvedQuizzes = user?.solvedQuizzes ?: emptyList()

                @Serializable
                data class TestWithStatus(
                    val _id: String,
                    val title: String,
                    val category: String,
                    val activeVersion: Int,
                    val isActive: Boolean,
                    val isPremium: Boolean,
                    val display: TestDisplay?,
                    val createdAt: Long,
                    val isSolved: Boolean,
                    val solvedAt: Long? = null
                )

                val testsWithStatus = tests.map { test ->
                    val solved = solvedQuizzes.find { it.testId == test.testId && it.version == test.activeVersion }
                    TestWithStatus(
                        test.testId, // Use testId instead of _id for client compatibility
                        test.title,
                        test.category,
                        test.activeVersion,
                        test.isActive,
                        test.isPremium,
                        test.display,
                        test.createdAt,
                        solved != null,
                        solved?.solvedAt
                    )
                }

                call.respond(BaseResponse(status = "success", data = testsWithStatus))
            }
// Get unified results for a specific test (both solo and compatibility)
            get("/{testId}/results") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val testId = call.parameters["testId"]!!
                val friendId = call.request.queryParameters["friendId"] // Optional: filter by specific friend
                val showCompatibility = call.request.queryParameters["compatibility"] == "true" // Optional: show all compatibility results

                // Get solo submission for this test (exclude invite responses)
                val submissionCol = graph.mongo.db.getCollection<Submission>("submissions")
                val soloSubmission = submissionCol.findOne(
                    and(
                        Submission::uid eq uid,
                        Submission::testId eq testId,
                        Submission::isInviteResponse eq false // Only true solo submissions
                    )
                )

                // Get compatibility results for this test
                val compatibilityCol = graph.mongo.db.getCollection<Compatibility>("compatibility")
                val compatibilityResults = if (friendId != null) {
                    // Filter by specific friend
                    println("DEBUG: Filtering compatibility results for testId=$testId, currentUser=$uid, friendId=$friendId")
                    val filtered = compatibilityCol.find(
                        and(
                            or(
                                and(Compatibility::uidA eq uid, Compatibility::uidB eq friendId),
                                and(Compatibility::uidA eq friendId, Compatibility::uidB eq uid)
                            ),
                            Compatibility::testId eq testId
                        )
                    ).sort(descending(Compatibility::createdAt)).limit(1).toList()
                    println("DEBUG: Found ${filtered.size} compatibility result(s) for specific friend")
                    filtered
                } else {
                    // Get all compatibility results for this test
                    println("DEBUG: Getting all compatibility results for testId=$testId, currentUser=$uid")
                    val all = compatibilityCol.find(
                        and(
                            or(Compatibility::uidA eq uid, Compatibility::uidB eq uid),
                            Compatibility::testId eq testId
                        )
                    ).sort(descending(Compatibility::createdAt)).toList()
                    println("DEBUG: Found ${all.size} total compatibility result(s)")
                    all
                }

                @Serializable
                data class SoloResultDto(
                    val submissionId: String,
                    val totalScore: Int?,
                    val personalizedInsights: String?,
                    val scoreVector: List<Int>?,
                    val completedAt: Long
                )

                @Serializable
                data class FriendInfo(
                    val userId: String,
                    val displayName: String?,
                    val mbtiType: String?,
                    val zodiacSign: String?
                )

                @Serializable
                data class CompatibilityResultDto(
                    val compatibilityId: String,
                    val friendId: String,
                    val friendInfo: FriendInfo?, // Friend's profile information
                    val myInfo: FriendInfo?, // Current user's profile information
                    val score: Int,
                    val summary: String,
                    val chemistry: String?, // New field for chemistry description
                    val explanations: List<Compatibility.Explanation>?, // Detailed explanations
                    val matchingAnalysis: MatchingAnalysis?, // MBTI and Zodiac matching details
                    val createdAt: Long
                )

                @Serializable
                data class TestResultsResponse(
                    val testId: String,
                    val soloResult: SoloResultDto?,
                    val compatibilityResults: List<CompatibilityResultDto>,
                    val resultType: String, // "solo", "compatibility", "both", "none",
                    val myDisplayName: String,
                )

                val soloResult = soloSubmission?.let {
                    SoloResultDto(
                        submissionId = it._id?.toString() ?: "unknown",
                        totalScore = it.totalScore,
                        personalizedInsights = it.personalizedInsights,
                        scoreVector = it.scoreVector,
                        completedAt = it.completedAt
                    )
                }

                // Get all friend IDs to fetch their profile information
                val friendIds = compatibilityResults.map { if (it.uidA == uid) it.uidB else it.uidA }.distinct()
                val userCol = graph.mongo.db.getCollection<User>("users")
                val me = userCol.findOne(User::_id eq uid)
                if (me == null) {
                    println("DEBUG: User not found with ID: '$uid'")
                    return@get call.respond(HttpStatusCode.NotFound)
                }
                println("DEBUG: Found user: ${me._id}")
                val friendsMap = userCol.find(User::_id `in` friendIds).toList().associateBy { it._id }

                val compatibilityResultsDto = compatibilityResults.map {
                    val friendId = if (it.uidA == uid) it.uidB else it.uidA
                    val friend = friendsMap[friendId]

                    println("DEBUG: Processing compatibility ${it._id} - Current user: $uid (${me.displayName}, ${me.mbtiType}), Friend: $friendId (${friend?.displayName}, ${friend?.mbtiType})")
                    println("DEBUG: Compatibility uidA=${it.uidA}, uidB=${it.uidB} - Current user is ${if (it.uidA == uid) "uidA" else "uidB"}")

                    CompatibilityResultDto(
                        compatibilityId = it._id,
                        friendId = friendId,
                        friendInfo = friend?.let { f ->
                            FriendInfo(
                                userId = f._id,
                                displayName = f.displayName,
                                mbtiType = f.mbtiType,
                                zodiacSign = f.zodiacSign
                            )
                        },
                        myInfo = FriendInfo( // Current user's information
                            userId = me._id,
                            displayName = me.displayName,
                            mbtiType = me.mbtiType,
                            zodiacSign = me.zodiacSign
                        ),
                        score = it.score,
                        summary = it.summary,
                        chemistry = it.chemistry, // Include chemistry description
                        explanations = it.explanations, // Include detailed explanations
                        matchingAnalysis = it.matchingAnalysis, // Include MBTI/Zodiac analysis
                        createdAt = it.createdAt
                    )
                }

                // Determine result type and filter response accordingly
                val (finalSoloResult, finalCompatibilityResults, resultType) = when {
                    friendId != null -> {
                        // Specific friend requested = only compatibility result
                        Triple(null, compatibilityResultsDto, "compatibility")
                    }
                    showCompatibility -> {
                        // All compatibility results requested
                        Triple(null, compatibilityResultsDto, "compatibility")
                    }
                    soloResult != null -> {
                        // Default: prioritize solo result (as requested)
                        Triple(soloResult, emptyList(), "solo")
                    }
                    compatibilityResultsDto.isNotEmpty() -> {
                        // Only compatibility results available (no solo)
                        Triple(null, compatibilityResultsDto, "compatibility")
                    }
                    else -> {
                        // No results
                        Triple(null, emptyList(), "none")
                    }
                }

                println("DEBUG: Final result - Type: $resultType, Solo: ${finalSoloResult != null}, Compatibility: ${finalCompatibilityResults.size}")

                call.respond(
                    BaseResponse(
                        status = "success",
                        data = TestResultsResponse(
                            testId = testId,
                            soloResult = finalSoloResult,
                            compatibilityResults = finalCompatibilityResults,
                            resultType = resultType,
                            myDisplayName = me.displayName.orEmpty(),
                        )
                    )
                )
            }
        }
        get("/{testId}/versions/{version}/questions") {
            val testId = call.parameters["testId"]!!
            val version = call.parameters["version"]!!.toInt()

            // Get language preference from header or query parameter - WITH DETAILED LOGGING
            val langParam = call.request.queryParameters["lang"]
            val acceptLangHeader = call.request.headers["Accept-Language"]
            val acceptLangParsed = acceptLangHeader?.lowercase()?.split(",")?.firstOrNull()?.split("-")?.firstOrNull()

            println("DEBUG: Language detection for test questions:")
            println("DEBUG: - Query param 'lang': $langParam")
            println("DEBUG: - Accept-Language header: '$acceptLangHeader'")
            println("DEBUG: - Accept-Language parsed: '$acceptLangParsed'")
            println("DEBUG: - All headers: ${call.request.headers.entries().map { "${it.key}: ${it.value}" }}")

            val requestedLang = langParam ?: acceptLangParsed ?: "en"

            val normalizedLang = when (requestedLang.lowercase()) {
                "ja", "jp" -> "ja"
                "zh", "zh-cn", "zh-hans", "cn" -> "zh"
                "ko", "kr" -> "ko"
                else -> "en"
            }

            println("DEBUG: Final language selection: requestedLang='$requestedLang' -> normalizedLang='$normalizedLang'")
            println("DEBUG: Fetching test questions for testId=$testId, version=$version, language=$normalizedLang")

            val col = graph.mongo.db.getCollection<TestQuestion>("testQuestions")

            // First try to get questions in requested language
            val questionsInLang = col.find(
                and(
                    TestQuestion::testId eq testId,
                    TestQuestion::version eq version,
                    TestQuestion::locale eq normalizedLang
                )
            ).sort(ascending(TestQuestion::index)).toList()

            val finalQuestions = if (questionsInLang.isNotEmpty()) {
                println("DEBUG: Found ${questionsInLang.size} questions in $normalizedLang")
                questionsInLang
            } else {
                // Fallback to English
                println("DEBUG: No questions found in $normalizedLang, falling back to English")
                val englishQuestions = col.find(
                    and(
                        TestQuestion::testId eq testId,
                        TestQuestion::version eq version,
                        TestQuestion::locale eq "en"
                    )
                ).sort(ascending(TestQuestion::index)).toList()

                if (englishQuestions.isEmpty()) {
                    // Last resort: get any questions for this test/version
                    println("DEBUG: No English questions found, getting any available questions")
                    col.find(and(TestQuestion::testId eq testId, TestQuestion::version eq version))
                        .sort(ascending(TestQuestion::index)).toList()
                } else {
                    englishQuestions
                }
            }

            println("DEBUG: Returning ${finalQuestions.size} questions")
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respond(BaseResponse(status = "success", data = finalQuestions))
        }
    }
}