package aishou.routes

import aishou.config.JwtProvider
import aishou.domain.model.User
import aishou.domain.model.BaseResponse
import aishou.config.graph
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.http.*
import org.litote.kmongo.*
import com.mongodb.client.model.UpdateOptions
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.random.Random

@Serializable
data class RegisterReq(
    val revenueCatId: String,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val lang: String = "en",
    val platform: String? = null,  // "ios" or "android"
    val isAnonymous: Boolean = true
)
@Serializable
data class TokenResponse(val token: String, val refreshToken: String)

private suspend fun generateRandomDisplayName(): String {
    return try {
        println("DEBUG: Attempting to get random name from MongoDB...")

        // Get random name from randomNames collection
        val randomNamesCol = graph.mongo.db.getCollection<org.bson.Document>("randomNames")

        // First check if collection exists and has documents
        val totalCount = randomNamesCol.countDocuments()
        println("DEBUG: Found $totalCount documents in randomNames collection")

        if (totalCount > 0) {
            // Use safer skip value based on actual count
            val skipValue = if (totalCount > 1) Random.nextInt(totalCount.toInt()) else 0
            val randomDoc = randomNamesCol.find().skip(skipValue).limit(1).first()

            val usernames = randomDoc?.getList("usernames", String::class.java)
            println("DEBUG: Retrieved ${usernames?.size ?: 0} usernames from document")

            if (usernames != null && usernames.isNotEmpty()) {
                val randomName = usernames[Random.nextInt(usernames.size)]
                val randomNumber = Random.nextInt(1000, 9999)
                val finalName = "$randomName-$randomNumber"
                println("DEBUG: Generated name from MongoDB: $finalName")
                return finalName
            }
        }

        println("DEBUG: MongoDB collection empty or no usernames found, using fallback...")
        // Fallback if collection is empty or not found
        val defaultNames = listOf("Player", "User", "Friend", "Guest", "Member", "Buddy", "Pal", "Mate",
            "Hero", "Star", "Cool", "Super", "Ninja", "Wizard", "Knight", "Ace",
            "Lucky", "Happy", "Bright", "Swift", "Smart", "Bold", "Brave", "Wise",
            "Fire", "Ice", "Storm", "Wind", "Stone", "Ocean", "Sky", "Moon",
            "Tiger", "Wolf", "Eagle", "Fox", "Bear", "Lion", "Shark", "Phoenix")
        val randomName = defaultNames[Random.nextInt(defaultNames.size)]
        val randomNumber = Random.nextInt(1000, 9999)
        val fallbackName = "$randomName-$randomNumber"
        println("DEBUG: Generated fallback name: $fallbackName")
        fallbackName

    } catch (e: Exception) {
        println("ERROR: Failed to generate random display name from MongoDB: ${e.message}")
        e.printStackTrace()

        // Fallback with hardcoded names
        val defaultNames = listOf("Player", "User", "Friend", "Guest", "Member", "Cool", "Super", "Ninja", "Wise")
        val randomName = defaultNames[Random.nextInt(defaultNames.size)]
        val randomNumber = Random.nextInt(1000, 9999)
        val errorFallbackName = "$randomName-$randomNumber"
        println("DEBUG: Generated error fallback name: $errorFallbackName")
        errorFallbackName
    }
}

private suspend fun generateUniqueDisplayName(userCol: org.litote.kmongo.coroutine.CoroutineCollection<User>): String {
    var attempts = 0
    val maxAttempts = 10

    while (attempts < maxAttempts) {
        val candidateName = generateRandomDisplayName().take(13) // Ensure 13 char limit
        val existingUser = userCol.findOne(User::displayName eq candidateName)

        if (existingUser == null) {
            return candidateName
        }
        attempts++
    }

    // Fallback: add timestamp if all attempts failed
    return "User${System.currentTimeMillis() % 1000000}".take(13)
}

fun Route.authRoutes() {
    route("/v1/auth") {
        post("/register") {
            try {
                val r = call.receive<RegisterReq>()

                val userCol = graph.mongo.db.getCollection<User>("users")
                val isJapanese = r.lang.lowercase() in listOf("ja", "jp")

                // Generate or validate display name
                val finalDisplayName = if (r.displayName.isNullOrBlank()) {
                    // Generate unique random display name
                    generateUniqueDisplayName(userCol)
                } else {
                    val trimmedName = r.displayName.trim()

                    // Validate length
                    if (trimmedName.length > 13) {
                        val errorMessage = if (isJapanese) {
                            "表示名は13文字以内で入力してください"
                        } else {
                            "Display name must be 13 characters or less"
                        }
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            BaseResponse<String>(status = "error", data = errorMessage)
                        )
                    }

                    // Check if already taken
                    val existingUser = userCol.findOne(User::displayName eq trimmedName)
                    if (existingUser != null) {
                        val errorMessage = if (isJapanese) {
                            "この表示名は既に使用されています"
                        } else {
                            "This display name is already taken"
                        }
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            BaseResponse<String>(status = "error", data = errorMessage)
                        )
                    }

                    trimmedName
                }

                println("DEBUG: Final display name: $finalDisplayName for user: ${r.revenueCatId}")

                val user = User(
                    _id = r.revenueCatId,
                    displayName = finalDisplayName,
                    photoUrl = r.photoUrl,
                    lang = r.lang,
                    platform = r.platform,
                    isAnonymous = r.isAnonymous,
                    isPremium = false, // Default to non-premium on registration
                    premiumExpiresAt = null
                )


                // Upsert user (insert if new, update if exists)
                userCol.updateOne(
                    User::_id eq r.revenueCatId,
                    user,
                    UpdateOptions().upsert(true)
                )

                // Generate JWT tokens using RevenueCat ID
                val access = JwtProvider.issueAccess(r.revenueCatId)
                val refresh = JwtProvider.issueRefresh(r.revenueCatId, UUID.randomUUID().toString())

//      call.respond(mapOf(
//        "status" to "success",
//        "data" to mapOf(
//          "userId" to r.revenueCatId,
//          "accessToken" to access,
//          "refreshToken" to refresh,
//          "user" to user
//        )
//      ))
                call.respond(
                    BaseResponse(
                        status = "success",
                        data = TokenResponse(
                            token = access,
                            refreshToken = refresh
                        )
                    )
                )
            } catch (e: Exception) {
                println("Error in registration: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Registration failed: ${e.message}"))
            }
        }

        post("/refresh") {
            val body = call.receive<Map<String, String>>()
            val token = body["refreshToken"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val dec = runCatching { JwtProvider.verifier().verify(token) }.getOrNull() ?: return@post call.respond(
                HttpStatusCode.Unauthorized
            )
            if (dec.getClaim("typ").asString() != "refresh") return@post call.respond(HttpStatusCode.Unauthorized)
            val access = JwtProvider.issueAccess(dec.subject)
            val newRefresh = JwtProvider.issueRefresh(dec.subject, UUID.randomUUID().toString())
            call.respond(BaseResponse(
                status = "success",
                data = TokenResponse(
                    token = access,
                    refreshToken = newRefresh
                )
            ))
        }

        // Update user profile endpoint
        authenticate("auth") {
            put("/profile") {
                @Serializable
                data class ProfileUpdateRequest(val displayName: String)

                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receive<ProfileUpdateRequest>()

                // Get user language for error messages
                val userCol = graph.mongo.db.getCollection<User>("users")
                val currentUser = userCol.findOne(User::_id eq uid)

                // DEBUG: Log the raw user data
                println("DEBUG: User ID: $uid")
                println("DEBUG: Found user: ${currentUser != null}")
                if (currentUser != null) {
                    println("DEBUG: hasChangedName value: ${currentUser.hasChangedName}")
                    println("DEBUG: hasChangedName type: ${currentUser.hasChangedName?.javaClass}")
                    println("DEBUG: Full user object: $currentUser")
                }

                if (currentUser == null) {
                    return@put call.respond(
                        HttpStatusCode.NotFound,
                        BaseResponse<String>(status = "error", data = "User not found")
                    )
                }

                val isJapanese = currentUser.lang.lowercase() in listOf("ja", "jp")

                // Check if user has already changed their name
                if (currentUser.hasChangedName == true) {
                    val errorMessage = if (isJapanese) {
                        "表示名は一度だけ変更できます"
                    } else {
                        "Display name can only be changed once"
                    }
                    return@put call.respond(
                        HttpStatusCode.Forbidden,
                        BaseResponse<String>(status = "error", data = errorMessage)
                    )
                }

                // Validate displayName length
                val trimmedName = req.displayName.trim()
                if (trimmedName.isBlank() || trimmedName.length > 13) {
                    val errorMessage = if (isJapanese) {
                        "表示名は1〜13文字で入力してください"
                    } else {
                        "Display name must be 1-13 characters"
                    }
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        BaseResponse<String>(status = "error", data = errorMessage)
                    )
                }

                // Check if displayName is already taken by another user
                val existingUser = userCol.findOne(
                    and(
                        User::displayName eq trimmedName,
                        User::_id ne uid  // Exclude current user
                    )
                )

                if (existingUser != null) {
                    val errorMessage = if (isJapanese) {
                        "この表示名は既に使用されています"
                    } else {
                        "This display name is already taken"
                    }
                    return@put call.respond(
                        HttpStatusCode.Conflict,
                        BaseResponse<String>(status = "error", data = errorMessage)
                    )
                }

                // Update displayName and set hasChangedName flag
                val result = userCol.updateOne(
                    User::_id eq uid,
                    combine(
                        setValue(User::displayName, trimmedName),
                        setValue(User::hasChangedName, true)
                    )
                )

                call.respond(BaseResponse<Unit>(status = "success", data = null))
            }

            get("/profile") {
                @Serializable
                data class ProfileResponse(
                    val _id: String,
                    val displayName: String?,
                    val photoUrl: String?,
                    val lang: String,
                    val platform: String?,
                    val isAnonymous: Boolean,
                    val mbtiType: String?,
                    val zodiacSign: String?,
                    val personalityAssessed: Boolean,
                    val isPremium: Boolean,
                    val hasChangedName: Boolean,
                    val createdAt: Long
                )

                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val userCol = graph.mongo.db.getCollection<User>("users")
                val user = userCol.findOne(User::_id eq uid)

                // DEBUG: Log profile data
                println("DEBUG GET PROFILE: User ID: $uid")
                if (user != null) {
                    println("DEBUG GET PROFILE: hasChangedName value: ${user.hasChangedName}")
                    println("DEBUG GET PROFILE: hasChangedName after elvis: ${user.hasChangedName ?: false}")
                }

                if (user == null) {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        BaseResponse<String>(status = "error", data = "User not found")
                    )
                }

                val profileResponse = ProfileResponse(
                    _id = user._id,
                    displayName = user.displayName,
                    photoUrl = user.photoUrl,
                    lang = user.lang,
                    platform = user.platform,
                    isAnonymous = user.isAnonymous,
                    mbtiType = user.mbtiType,
                    zodiacSign = user.zodiacSign,
                    personalityAssessed = user.personalityAssessed,
                    isPremium = user.isPremium,
                    hasChangedName = user.hasChangedName ?: false,
                    createdAt = user.createdAt
                )

                call.respond(BaseResponse(status = "success", data = profileResponse))
            }
        }

        // Update premium status endpoint
        authenticate("auth") {
            put("/premium") {
                @Serializable
                data class PremiumUpdateRequest(val isPremium: Boolean, val expiresAt: Long?)

                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receive<PremiumUpdateRequest>()

                val userCol = graph.mongo.db.getCollection<User>("users")
                userCol.updateOne(
                    User::_id eq uid,
                    combine(
                        setValue(User::isPremium, req.isPremium),
                        setValue(User::premiumExpiresAt, req.expiresAt)
                    )
                )

                call.respond(BaseResponse<Unit>(status = "success", data = null))
            }
        }
    }
}