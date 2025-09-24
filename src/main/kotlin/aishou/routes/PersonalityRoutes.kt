package aishou.routes

import aishou.config.graph
import aishou.domain.model.TestQuestion
import aishou.domain.model.TestMeta
import aishou.domain.model.User
import aishou.domain.model.BaseResponse
import aishou.domain.model.PersonalityAnalysis
import aishou.domain.model.MBTIAnalysis
import aishou.domain.model.ZodiacAnalysis
import aishou.domain.model.Submission
import aishou.domain.model.Compatibility
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.bson.conversions.Bson
import org.litote.kmongo.*
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
data class PersonalityAssessRequest(val testId: String, val version: Int, val answers: Map<String, String>)

@Serializable
data class PersonalityUpdateRequest(val mbtiType: String?, val zodiacSign: String?)

@Serializable
data class PersonalityAssessResponse(
    val mbtiType: String,
    val zodiacSign: String,
    val analysis: PersonalityAnalysis,
    val message: String
)

@Serializable
data class QuickQuizResponse(
    val testId: String,
    val version: Int,
    val title: String,
    val category: String,
    val questions: List<TestQuestion>
)

fun Route.personalityRoutes() {
    authenticate("auth") {
        route("/v1/personality") {

            // Initial personality assessment to determine MBTI and Zodiac Ok
            post("/assess") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val r = call.receive<PersonalityAssessRequest>()

                // Get user and determine language preference
                val userCol = graph.mongo.db.getCollection<User>("users")
                val user = userCol.findOne(User::_id eq uid) ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    "User not found"
                )
                val userLang = getLanguageFromRequest(call, user)
                println("DEBUG: Language determined for personality assessment: $userLang (header: ${call.request.headers["Accept-Language"]}, user: ${user.lang})")

                // Get questions for OpenAI analysis
                val questionsCol = graph.mongo.db.getCollection<TestQuestion>("testQuestions")
                val questions =
                    questionsCol.find(and(TestQuestion::testId eq r.testId, TestQuestion::version eq r.version))
                        .sort(ascending(TestQuestion::index)).toList()

                // Check if user is premium for detailed analysis
                val isPremiumValid =
                    user.isPremium && (user.premiumExpiresAt == null || user.premiumExpiresAt > System.currentTimeMillis())

                val analysis = if (isPremiumValid) {
                    try {
                        graph.openAI.analyzePersonality(r.answers, questions, userLang)
                    } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.InternalServerError, "Failed to analyze personality")
                    }
                } else {
                    // Non-premium users get basic MBTI/Zodiac determination
                    try {
                        val (mbti, zodiac) = graph.openAI.analyzeMBTIOnly(r.answers, questions, userLang)
                        PersonalityAnalysis(
                            mbti = MBTIAnalysis(
                                type = mbti,
                                description = if (userLang == "jp" || userLang == "ja") "基本的な性格タイプ" else "Basic personality type",
                                strengths = emptyList(),
                                weaknesses = emptyList(),
                                summary = if (userLang == "jp" || userLang == "ja") "詳細な分析にはプレミアム版が必要です" else "Premium required for detailed analysis"
                            ),
                            zodiac = ZodiacAnalysis(
                                sign = zodiac,
                                element = "",
                                description = if (userLang == "jp" || userLang == "ja") "基本的な星座情報" else "Basic zodiac info",
                                traits = emptyList(),
                                summary = if (userLang == "jp" || userLang == "ja") "詳細な分析にはプレミアム版が必要です" else "Premium required for detailed analysis"
                            )
                        )
                    } catch (e: Exception) {
                        return@post call.respond(HttpStatusCode.InternalServerError, "Failed to analyze personality")
                    }
                }

                // Extract MBTI and Zodiac types
                val mbtiType = analysis.mbti.type
                val zodiacSign = analysis.zodiac.sign

                // Update user with personality types
                userCol.updateOne(
                    User::_id eq uid,
                    combine(
                        setValue(User::mbtiType, mbtiType),
                        setValue(User::zodiacSign, zodiacSign),
                        setValue(User::personalityAssessed, true)
                    )
                )

                // Return the analysis
                call.respond(
                    BaseResponse(
                        status = "success",
                        data = PersonalityAssessResponse(
                            mbtiType = mbtiType,
                            zodiacSign = zodiacSign,
                            analysis = analysis,
                            message = if (userLang == "jp" || userLang == "ja") "性格タイプが保存されました" else "Personality type saved"
                        )
                    )
                )
            }

            // Get user's complete personality profile with quiz history
            get("/profile") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                println("DEBUG: JWT User ID: '$uid'")
                println("DEBUG: JWT User ID length: ${uid.length}")
                println("DEBUG: JWT User ID type: ${uid::class.java.simpleName}")

                val userCol = graph.mongo.db.getCollection<User>("users")
                val user = userCol.findOne(User::_id eq uid)
                if (user == null) {
                    println("DEBUG: User not found with ID: '$uid'")
                    return@get call.respond(HttpStatusCode.NotFound)
                }
                println("DEBUG: Found user: ${user._id}")
                println("DEBUG PERSONALITY PROFILE: hasChangedName value: ${user.hasChangedName}")
                println("DEBUG PERSONALITY PROFILE: hasChangedName after elvis: ${user.hasChangedName ?: false}")

                // Get solo submissions
                val submissionCol = graph.mongo.db.getCollection<Submission>("submissions")

                // Debug: Check all submissions first
                try {
                    val allSubmissions = submissionCol.find().limit(5).toList()
                    println("DEBUG: Total submissions in collection: ${submissionCol.countDocuments()}")
                    println("DEBUG: Sample submissions:")
                    allSubmissions.forEach {
                        println("DEBUG: - Submission uid='${it.uid}' testId=${it.testId} _id=${it._id}")
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error checking all submissions: ${e.message}")
                }

                val soloSubmissions = try {
                    // Only get true solo submissions (exclude invite responses)
                    val query = and(
                        Submission::uid eq uid,
                        Submission::isInviteResponse eq false
                    )
                    println("DEBUG: Solo submissions query filter: $query")

                    val submissions = submissionCol.find(query)
                        .sort(descending(Submission::completedAt)).toList()
                    println("DEBUG: Found ${submissions.size} SOLO submissions for user '$uid' (excluding invite responses)")

                    // Debug: also check invite responses separately
                    val inviteSubmissions = submissionCol.find(
                        and(Submission::uid eq uid, Submission::isInviteResponse eq true)
                    ).toList()
                    println("DEBUG: Found ${inviteSubmissions.size} INVITE RESPONSE submissions for user '$uid'")

                    submissions.forEach { println("DEBUG: Solo submission ${it._id} testId=${it.testId} completedAt=${it.completedAt}") }
                    submissions
                } catch (e: Exception) {
                    println("DEBUG: Error fetching submissions: ${e.message}")
                    e.printStackTrace()
                    emptyList()
                }

                // Get compatibility matches
                val compatibilityCol = graph.mongo.db.getCollection<Compatibility>("compatibility")
                val matches = try {
                    compatibilityCol.find(or(Compatibility::uidA eq uid, Compatibility::uidB eq uid))
                        .sort(descending(Compatibility::createdAt)).toList()
                } catch (e: Exception) {
                    emptyList()
                }

                // Get all friend IDs to fetch their profile information
                val friendIds = matches.map { if (it.uidA == uid) it.uidB else it.uidA }.distinct()
                val friendsMap = if (friendIds.isNotEmpty()) {
                    userCol.find(User::_id `in` friendIds).toList().associateBy { it._id }
                } else {
                    emptyMap()
                }

                @Serializable
                data class SoloQuizDto(
                    val submissionId: String,
                    val testId: String,
                    val version: Int,
                    val totalScore: Int?,
                    val completedAt: Long,
                    val type: String = "solo"
                )

                @Serializable
                data class FriendInfo(
                    val userId: String,
                    val displayName: String?,
                    val mbtiType: String?,
                    val zodiacSign: String?
                )

                @Serializable
                data class MatchQuizDto(
                    val compatibilityId: String,
                    val testId: String,
                    val version: Int,
                    val friendId: String,
                    val friendInfo: FriendInfo?, // Friend's profile information
                    val score: Int, // Compatibility score (not totalScore)
                    val summary: String,
                    val chemistry: String?, // Chemistry description
                    val createdAt: Long,
                    val type: String = "match"
                )

                val soloQuizzes = soloSubmissions.map {
                    SoloQuizDto(
                        submissionId = it._id?.toString() ?: it.hashCode().toString(),
                        testId = it.testId,
                        version = it.version,
                        totalScore = it.totalScore,
                        completedAt = it.completedAt
                    )
                }

                val matchQuizzes = matches.map {
                    val friendId = if (it.uidA == uid) it.uidB else it.uidA
                    val friend = friendsMap[friendId]

                    MatchQuizDto(
                        compatibilityId = it._id,
                        testId = it.testId,
                        version = it.version,
                        friendId = friendId,
                        friendInfo = friend?.let { f ->
                            FriendInfo(
                                userId = f._id,
                                displayName = f.displayName,
                                mbtiType = f.mbtiType,
                                zodiacSign = f.zodiacSign
                            )
                        },
                        score = it.score, // Using correct field name from Compatibility model
                        summary = it.summary,
                        chemistry = it.chemistry, // Include chemistry information
                        createdAt = it.createdAt
                    )
                }

                @Serializable
                data class ProfileResponse(
                    val mbtiType: String?,
                    val zodiacSign: String?,
                    val personalityAssessed: Boolean,
                    val lang: String,
                    val soloQuizzes: List<SoloQuizDto>,
                    val matchQuizzes: List<MatchQuizDto>,
                    val totalQuizzes: Int,
                    val displayName: String?,
                    val hasChangedName: Boolean,
                )

                call.respond(
                    BaseResponse(
                        status = "success",
                        data = ProfileResponse(
                            mbtiType = user.mbtiType,
                            zodiacSign = user.zodiacSign,
                            personalityAssessed = user.personalityAssessed,
                            lang = user.lang,
                            soloQuizzes = soloQuizzes,
                            matchQuizzes = matchQuizzes,
                            totalQuizzes = soloQuizzes.size + matchQuizzes.size,
                            displayName = user.displayName,
                            hasChangedName = user.hasChangedName ?: false
                        )
                    )
                )
            }

            // Update personality types manually (if user wants to change)
            put("/update") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val r = call.receive<PersonalityUpdateRequest>()

                val userCol = graph.mongo.db.getCollection<User>("users")
                val updates = mutableListOf<Bson>()

                r.mbtiType?.let { updates.add(setValue(User::mbtiType, it)) }
                r.zodiacSign?.let { updates.add(setValue(User::zodiacSign, it)) }

                if (updates.isNotEmpty()) {
                    updates.add(setValue(User::personalityAssessed, true))
                    userCol.updateOne(User::_id eq uid, combine(updates))
                }

                call.respond(BaseResponse<Unit>(status = "success", data = null))
            }

            // Quick MBTI assessment - lightweight version for first-time users
            post("/quick-assess") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val r = call.receive<PersonalityAssessRequest>()

                // Get user and determine language preference
                val userCol = graph.mongo.db.getCollection<User>("users")
                val user = userCol.findOne(User::_id eq uid) ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    "User not found"
                )
                val userLang = getLanguageFromRequest(call, user)
                println("DEBUG: Language determined for quick assessment: $userLang (header: ${call.request.headers["Accept-Language"]}, user: ${user.lang})")

                // Get questions for analysis
                val questionsCol = graph.mongo.db.getCollection<TestQuestion>("testQuestions")
                val questions =
                    questionsCol.find(and(TestQuestion::testId eq r.testId, TestQuestion::version eq r.version))
                        .sort(ascending(TestQuestion::index)).toList()

                // Use lightweight MBTI-only analysis
                val (mbtiType, zodiacSign) = try {
                    graph.openAI.analyzeMBTIOnly(r.answers, questions, userLang)
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.InternalServerError, "Failed to analyze personality")
                }

                // Update user with personality types
                userCol.updateOne(
                    User::_id eq uid,
                    combine(
                        setValue(User::mbtiType, mbtiType),
                        setValue(User::zodiacSign, zodiacSign),
                        setValue(User::personalityAssessed, true)
                    )
                )

                // Return simple response
                call.respond(
                    BaseResponse(
                        status = "success",
                        data = mapOf(
                            "mbtiType" to mbtiType,
                            "zodiacSign" to zodiacSign,
                            "message" to if (userLang == "jp" || userLang == "ja") "性格タイプが保存されました" else "Personality type saved"
                        )
                    )
                )
            }

            // Quick quiz - random test selection
            get("/quick-quiz") {
                // Get language preference from header or query parameter
                val langParam = call.request.queryParameters["lang"]
                val acceptLangHeader = call.request.headers["Accept-Language"]
                val acceptLangParsed = acceptLangHeader?.lowercase()?.split(",")?.firstOrNull()?.split("-")?.firstOrNull()

                val requestedLang = langParam ?: acceptLangParsed ?: "en"
                val normalizedLang = when (requestedLang.lowercase()) {
                    "ja", "jp" -> "ja"
                    else -> "en"
                }

                // Get all active tests in requested language with fallback to English
                val testMetaCol = graph.mongo.db.getCollection<TestMeta>("tests")
                val testsInLang = testMetaCol.find(
                    and(TestMeta::isActive eq true, TestMeta::locale eq normalizedLang)
                ).toList()

                val activeTests = if (testsInLang.isNotEmpty()) {
                    testsInLang
                } else {
                    testMetaCol.find(
                        and(TestMeta::isActive eq true, TestMeta::locale eq "en")
                    ).toList()
                }

                if (activeTests.isEmpty()) {
                    return@get call.respond(
                        HttpStatusCode.NotFound, BaseResponse<String>(
                            status = "error",
                            data = "No active tests available"
                        )
                    )
                }

                // Select random test
                val randomTest = activeTests.random()

                // Get language preference for questions
                val questionsRequestedLang = call.request.queryParameters["lang"]
                    ?: call.request.headers["Accept-Language"]?.lowercase()?.split(",")?.firstOrNull()?.split("-")?.firstOrNull()
                    ?: "en"

                val questionsNormalizedLang = when (questionsRequestedLang.lowercase()) {
                    "ja", "jp" -> "ja"
                    else -> "en"
                }

                println("DEBUG: Quick-quiz fetching questions for testId=${randomTest.testId}, version=${randomTest.activeVersion}, language=$questionsNormalizedLang")

                // Get questions for selected test with language filtering
                val questionsCol = graph.mongo.db.getCollection<TestQuestion>("testQuestions")

                // First try to get questions in requested language
                val questionsInLang = questionsCol.find(
                    and(
                        TestQuestion::testId eq randomTest.testId,
                        TestQuestion::version eq randomTest.activeVersion,
                        TestQuestion::locale eq questionsNormalizedLang
                    )
                ).sort(ascending(TestQuestion::index)).toList()

                val questions = if (questionsInLang.isNotEmpty()) {
                    println("DEBUG: Quick-quiz found ${questionsInLang.size} questions in $questionsNormalizedLang")
                    questionsInLang
                } else {
                    // Fallback to English
                    println("DEBUG: Quick-quiz no questions found in $questionsNormalizedLang, falling back to English")
                    val englishQuestions = questionsCol.find(
                        and(
                            TestQuestion::testId eq randomTest.testId,
                            TestQuestion::version eq randomTest.activeVersion,
                            TestQuestion::locale eq "en"
                        )
                    ).sort(ascending(TestQuestion::index)).toList()

                    if (englishQuestions.isEmpty()) {
                        // Last resort: get any questions for this test/version
                        questionsCol.find(
                            and(
                                TestQuestion::testId eq randomTest.testId,
                                TestQuestion::version eq randomTest.activeVersion
                            )
                        ).sort(ascending(TestQuestion::index)).toList()
                    } else {
                        englishQuestions
                    }
                }

                if (questions.isEmpty()) {
                    return@get call.respond(
                        HttpStatusCode.NotFound, BaseResponse<String>(
                            status = "error",
                            data = "No questions found for selected test"
                        )
                    )
                }

                call.respond(
                    BaseResponse(
                        status = "success",
                        data = QuickQuizResponse(
                            testId = randomTest.testId,
                            version = randomTest.activeVersion,
                            title = randomTest.title,
                            category = randomTest.category,
                            questions = questions
                        )
                    )
                )
            }
        }
    }
}