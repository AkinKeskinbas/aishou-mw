package aishou.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual

@Serializable
data class BaseResponse<T>(
    val status: String? = null,
    val data: T? = null
)

@Serializable
data class TestQuestion(
    val _id: String? = null,
    val testId: String,
    val version: Int,
    val index: Int,
    val text: String,
    val choices: List<Choice>,
    val weights: Map<String, Map<String, Int>>? = null, // choice_key -> dimension -> score
    val locale: String = "en"
) {
    @Serializable
    data class Choice(val key:String, val text:String)

    fun generateId(): String = "${testId}_${version}_${index}_${locale}"
}

@Serializable
data class TestMeta(
    val _id: String? = null,
    val testId: String,
    val title: String,
    val category: String,
    val activeVersion: Int,
    val isActive: Boolean = true,
    val isPremium: Boolean = false,
    val display: TestDisplay? = TestDisplay(),
    val locale: String = "en",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun generateId(): String = "${testId}_${locale}"
}


@Serializable
data class TestDisplay(
    val resultCard: String = "AUTO",
    val color: String? = null,
    val icon: String? = null
)

@Serializable
data class SolvedQuiz(
    val testId: String,
    val version: Int,
    val solvedAt: Long = System.currentTimeMillis(),
    val submissionId: String? = null
)

@Serializable
data class User(
    val _id: String,  // RevenueCat ID from client
    val displayName: String? = null,
    val photoUrl: String? = null,
    val lang: String = "en",
    val platform: String? = null,  // "ios" or "android"
    val isAnonymous: Boolean = true,
    val mbtiType: String? = null,
    val zodiacSign: String? = null,
    val personalityAssessed: Boolean = false,
    val solvedQuizzes: List<SolvedQuiz> = emptyList(),
    val isPremium: Boolean = false,
    val premiumExpiresAt: Long? = null,
    val hasChangedName: Boolean? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class DeviceReg(
    val userId: String,
    val provider: String = "onesignal",
    val playerId: String,
    val platform: String,
    val locale: String = "ja-JP",
    val timezone: String? = "Asia/Tokyo",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PushReq(
    val playerId: String,
    val platform: String,
    val locale: String? = null,
    val timezone: String? = null
)

@Serializable
data class Invite(
    val _id: String,
    val fromUserId: String,
    val toUserId: String? = null,
    val testId: String,
    val version: Int,
    val status: String = "pending",
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Submission(
    @Contextual val _id: org.bson.types.ObjectId? = null,
    val testId: String,
    val version: Int,
    val uid: String,
    val answers: Map<String, String>,
    val scoreVector: List<Int>? = null,
    val totalScore: Int? = null,
    val personalizedInsights: String? = null,
    val isInviteResponse: Boolean = false, // True if this submission was made in response to an invite
    val completedAt: Long = System.currentTimeMillis()
)

@Serializable
data class Pairing(
    val _id: String,
    val testId: String,
    val version: Int,
    val uidA: String,
    val uidB: String,
    val submissionAId: String?,
    val submissionBId: String?,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Compatibility(
    val _id: String,
    val testId: String,
    val version: Int,
    val uidA: String,
    val uidB: String,
    val pairId: String,
    val score: Int,
    val summary: String,
    val explanations: List<Explanation>,
    val matchingAnalysis: MatchingAnalysis? = null,
    val chemistry: String? = null, // Short chemistry description like "Soulmate", "Perfect Match", etc.
    val inputHash: String,
    val createdAt: Long = System.currentTimeMillis(),
    val cacheTTL: Long = 1000L*60*60*24*30
) { @Serializable data class Explanation(val topic:String, val detail:String) }

@Serializable
data class PersonalityAnalysis(
    val mbti: MBTIAnalysis,
    val zodiac: ZodiacAnalysis
)

@Serializable
data class MBTIAnalysis(
    val type: String,
    val description: String,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val summary: String
)

@Serializable
data class ZodiacAnalysis(
    val sign: String,
    val element: String,
    val description: String,
    val traits: List<String>,
    val summary: String
)

@Serializable
data class MatchingAnalysis(
    val mbtiMatch: MBTIMatchAnalysis,
    val zodiacMatch: ZodiacMatchAnalysis,
    val overallCompatibility: String
)

@Serializable
data class MBTIMatchAnalysis(
    val typeA: String,
    val typeB: String,
    val compatibilityScore: Int,
    val strengths: List<String>,
    val challenges: List<String>,
    val explanation: String
)

@Serializable
data class ZodiacMatchAnalysis(
    val signA: String,
    val signB: String,
    val compatibilityScore: Int,
    val elementInteraction: String,
    val strengths: List<String>,
    val challenges: List<String>,
    val explanation: String
)

@Serializable
data class FriendRequest(
    @Contextual val _id: org.bson.types.ObjectId? = null,
    val fromUserId: String,
    val toUserId: String,
    val status: FriendRequestStatus = FriendRequestStatus.PENDING,
    val tag: String? = null,
    val message: String? = null,
    val isRead: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val respondedAt: Long? = null
)

@Serializable
data class Friendship(
    @Contextual val _id: org.bson.types.ObjectId? = null,
    val userAId: String,
    val userBId: String,
    val tagA: String? = null,
    val tagB: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class FriendRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}

@Serializable
enum class FriendTag {
    FRIEND,
    FAMILY,
    COLLEAGUE,
    PARTNER
}

@Serializable
data class SendFriendRequestReq(
    val toUserId: String? = null,        // Legacy support
    val toUserDisplayName: String? = null, // New way - search by display name
    val tag: String? = null,
    val message: String? = null
)

@Serializable
data class RespondFriendRequestReq(
    val accept: Boolean,
    val tag: String? = null
)

@Serializable
data class FriendInfo(
    val userId: String,
    val displayName: String?,
    val photoUrl: String?,
    val tag: String?,
    val mbtiType: String?,
    val zodiacSign: String?,
    val friendsSince: Long
)

@Serializable
data class RequestWithSenderInfo(
    val request: FriendRequestDto,
    val sender: SenderInfo
)

@Serializable
data class SenderInfo(
    val userId: String,
    val displayName: String?,
    val photoUrl: String?,
    val mbtiType: String?,
    val zodiacSign: String?
)

@Serializable
data class RequestWithReceiverInfo(
    val request: FriendRequestDto,
    val receiver: ReceiverInfo
)

@Serializable
data class ReceiverInfo(
    val userId: String,
    val displayName: String?,
    val photoUrl: String?,
    val mbtiType: String?,
    val zodiacSign: String?
)

@Serializable
data class UnreadCount(
    val count: Long
)

@Serializable
data class FriendRequestDto(
    val id: String,
    val fromUserId: String,
    val toUserId: String,
    val status: FriendRequestStatus,
    val tag: String? = null,
    val message: String? = null,
    val isRead: Boolean,
    val createdAt: Long,
    val respondedAt: Long? = null
)