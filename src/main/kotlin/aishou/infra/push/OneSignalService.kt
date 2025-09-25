package aishou.infra.push

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

class OneSignalService(
    private val client: HttpClient,
    private val appId: String,
    private val restKey: String,
    private val publicBaseUrl: String
) {

    /**
     * Validates player IDs by checking with OneSignal API
     * Returns list of valid, subscribed player IDs
     */
    suspend fun validatePlayerIds(playerIds: List<String>): List<String> {
        if (playerIds.isEmpty()) return emptyList()

        val validPlayerIds = mutableListOf<String>()
        val authHeader = getAuthorizationHeader()

        for (playerId in playerIds) {
            try {
                // Correct OneSignal player endpoint format
                val response = client.get("https://api.onesignal.com/players/$playerId") {
                    header("Authorization", authHeader)
                    header("Content-Type", "application/json; charset=utf-8")
                    parameter("app_id", appId)
                }

                if (response.status.isSuccess()) {
                    val responseBody = response.bodyAsText()
                    println("DEBUG: Player $playerId validation response: $responseBody")

                    // Check if player is subscribed - OneSignal uses different fields
                    // Look for "invalid_identifier" : false and check subscription status
                    val isValidPlayer = !responseBody.contains("\"invalid_identifier\":true") &&
                                       responseBody.contains("\"id\":\"$playerId\"")

                    if (isValidPlayer) {
                        validPlayerIds.add(playerId)
                        println("DEBUG: Player $playerId is valid and registered")
                    } else {
                        println("WARNING: Player $playerId is invalid or not found")
                    }
                } else {
                    val responseBody = response.bodyAsText()
                    println("WARNING: Could not validate player $playerId: ${response.status}")
                    println("WARNING: Response body: $responseBody")

                    // If it's a 400 but not an invalid player, still consider it potentially valid
                    // Sometimes OneSignal returns 400 for various reasons but the player might still receive notifications
                    if (response.status.value == 400) {
                        println("DEBUG: Treating 400 response as potentially valid player for $playerId")
                        validPlayerIds.add(playerId)
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Failed to validate player $playerId: ${e.message}")
                // If validation fails, assume the player might be valid to avoid blocking notifications
                println("DEBUG: Treating validation error as potentially valid player for $playerId")
                validPlayerIds.add(playerId)
            }
        }

        println("DEBUG: Validated ${validPlayerIds.size}/${playerIds.size} player IDs")
        return validPlayerIds
    }

    /**
     * Helper function to determine the correct authorization header format
     * Based on OneSignal's API changes in November 2024
     */
    private fun getAuthorizationHeader(): String {
        return when {
            // New Rich API keys (introduced Nov 2024)
            restKey.startsWith("os_v2_app_") -> "Key $restKey"
            // Legacy REST API keys
            else -> "Basic $restKey"
        }
    }

    /**
     * Debug function to test OneSignal authentication
     * Call this to verify your API key and app configuration
     */
    suspend fun debugAuthentication(): String {
        val authHeader = getAuthorizationHeader()
        println("DEBUG: Testing OneSignal authentication...")
        println("DEBUG: App ID: $appId")
        println("DEBUG: REST Key type: ${if (restKey.startsWith("os_v2_app_")) "Rich API Key" else "Legacy API Key"}")
        println("DEBUG: Authorization header: '${authHeader.take(30)}...'")

        return try {
            val response = client.get("https://api.onesignal.com/apps/$appId") {
                header("Authorization", authHeader)
                header("Content-Type", "application/json; charset=utf-8")
            }

            val responseBody = response.bodyAsText()
            println("DEBUG: Auth test response status: ${response.status}")
            println("DEBUG: Auth test response: $responseBody")

            if (response.status.isSuccess()) {
                "✅ Authentication successful"
            } else {
                "❌ Authentication failed: ${response.status} - $responseBody"
            }
        } catch (e: Exception) {
            val errorMsg = "❌ Authentication error: ${e.message}"
            println("ERROR: $errorMsg")
            errorMsg
        }
    }
    suspend fun sendInvite(playerIds: List<String>, inviteId: String, testId: String, version: Int, senderId: String, testTitle: String, senderName: String? = null, senderMbti: String? = null) {
        if (playerIds.isEmpty()) {
            println("DEBUG: No player IDs provided for invite, skipping notification")
            return
        }

        @kotlinx.serialization.Serializable
        data class TestInviteNotification(
            val app_id: String,
            val include_subscription_ids: List<String>,
            val headings: Map<String, String>,
            val contents: Map<String, String>,
            val data: Map<String, String>,
            val url: String? = null
        )

        // Create deep link for test invite - mobile app format
        var deepLink = "aishou://invite/$inviteId?senderId=$senderId&testId=$testId&testTitle=${java.net.URLEncoder.encode(testTitle, "UTF-8")}"

        // Add senderName and senderMbti to deeplink if available
        senderName?.let {
            deepLink += "&senderName=${java.net.URLEncoder.encode(it, "UTF-8")}"
        }
        senderMbti?.let {
            deepLink += "&senderMbti=${java.net.URLEncoder.encode(it, "UTF-8")}"
        }

        println("DEBUG: Created invite deep link: $deepLink")

        val basicValidPlayerIds = playerIds.filter { it.isNotEmpty() && it.length > 10 }
        println("DEBUG: Original player IDs for invite: $playerIds")
        println("DEBUG: Basic valid player IDs after filtering: $basicValidPlayerIds")

        if (basicValidPlayerIds.isEmpty()) {
            println("DEBUG: No basic valid player IDs found after filtering, skipping invite notification")
            return
        }

        // Skip validation for now and use basic filtering
        val validPlayerIds = basicValidPlayerIds
        println("DEBUG: Using basic validated player IDs for invite: $validPlayerIds (skipping OneSignal validation)")

        val payload = TestInviteNotification(
            app_id = appId,
            include_subscription_ids = validPlayerIds,
            headings = mapOf(
                "ja" to "テスト招待",
                "en" to "Test Invite",
                "zh" to "测试邀请",
                "ko" to "테스트 초대"
            ),
            contents = mapOf(
                "ja" to "新しいテスト「$testTitle」に招待されました！",
                "en" to "You've been invited to take the test: $testTitle!",
                "zh" to "您已被邀请参加测试：$testTitle！",
                "ko" to "테스트 \"$testTitle\"에 초대되었습니다!"
            ),
            data = mapOf(
                "type" to "test_invite",
                "inviteId" to inviteId,
                "testId" to testId,
                "testTitle" to testTitle,
                "senderId" to senderId,
                "deepLink" to deepLink
            ),
            url = deepLink
        )

        val authHeader = getAuthorizationHeader()
        println("DEBUG: sendInvite - Authorization header: '${authHeader.take(30)}...'")

        // Log the exact JSON being sent
        val jsonPayload = kotlinx.serialization.json.Json.encodeToString(payload)
        println("DEBUG: sendInvite - Exact JSON payload: $jsonPayload")

        val response = client.post("https://api.onesignal.com/notifications") {
            header("Authorization", authHeader)
            header("Content-Type", "application/json; charset=utf-8")
            setBody(payload)
        }

        val responseBody = response.bodyAsText()
        println("DEBUG: sendInvite Response: $responseBody")
        println("DEBUG: sendInvite Status Code: ${response.status}")

        if (!response.status.isSuccess()) {
            println("ERROR: sendInvite failed with status: ${response.status}")
            println("ERROR: Response body: $responseBody")
        } else {
            // Check for OneSignal specific errors even with 200 OK
            if (responseBody.contains("All included players are not subscribed")) {
                println("WARNING: Player IDs are not subscribed to push notifications (test invite)")
                println("WARNING: This means the device is not properly registered with OneSignal")
                println("WARNING: User needs to reinstall app or re-enable notifications")
            } else if (responseBody.contains("\"id\":\"\"")) {
                println("WARNING: OneSignal returned empty notification ID - invite may not have been sent")
            } else {
                println("SUCCESS: Test invite notification sent successfully")
            }
        }
    }

    suspend fun sendInviteAccepted(playerIds: List<String>, friendName: String, testId: String) {
        if (playerIds.isEmpty()) return

        // Skip validation and use player IDs directly
        val validPlayerIds = playerIds.filter { it.isNotEmpty() && it.length > 10 }
        if (validPlayerIds.isEmpty()) {
            println("WARNING: No valid player IDs for invite accepted notification")
            return
        }

        @kotlinx.serialization.Serializable
        data class InviteAcceptedNotification(
            val app_id: String,
            val include_subscription_ids: List<String>,
            val headings: Map<String, String>,
            val contents: Map<String, String>,
            val data: Map<String, String>
        )

        val payload = InviteAcceptedNotification(
            app_id = appId,
            include_subscription_ids = validPlayerIds,
            headings = mapOf(
                "ja" to "テスト完了！",
                "en" to "Test Completed!",
                "zh" to "测试完成！",
                "ko" to "테스트 완료!"
            ),
            contents = mapOf(
                "ja" to "${friendName}がテストを完了しました！相性結果を確認しよう 🎉",
                "en" to "$friendName completed the test! Check your compatibility results 🎉",
                "zh" to "$friendName 完成了测试！查看你们的匹配结果 🎉",
                "ko" to "${friendName}님이 테스트를 완료했습니다! 궁합 결과를 확인해보세요 🎉"
            ),
            data = mapOf(
                "type" to "invite_accepted",
                "testId" to testId
            )
        )

        // Use consistent authorization format
        val authHeader = getAuthorizationHeader()
        println("DEBUG: sendInviteAccepted - Authorization header: '${authHeader.take(30)}...'")

        val response = client.post("https://api.onesignal.com/notifications") {
            header("Authorization", authHeader)
            header("Content-Type", "application/json; charset=utf-8")
            setBody(payload)
        }

        val responseBody = response.bodyAsText()
        println("DEBUG: sendInviteAccepted Response: $responseBody")
        println("DEBUG: sendInviteAccepted Status Code: ${response.status}")

        if (!response.status.isSuccess()) {
            println("ERROR: sendInviteAccepted failed with status: ${response.status}")
            println("ERROR: Response body: $responseBody")
        }
    }

    suspend fun sendCompatibilityReady(playerIds: List<String>, compatibilityScore: Int, testId: String) {
        if (playerIds.isEmpty()) return

        // Skip validation and use player IDs directly
        val validPlayerIds = playerIds.filter { it.isNotEmpty() && it.length > 10 }
        if (validPlayerIds.isEmpty()) {
            println("WARNING: No valid player IDs for compatibility ready notification")
            return
        }

        @kotlinx.serialization.Serializable
        data class CompatibilityNotification(
            val app_id: String,
            val include_subscription_ids: List<String>,
            val headings: Map<String, String>,
            val contents: Map<String, String>,
            val data: Map<String, String>,
            val url: String? = null
        )

        // Create deep link for compatibility results - mobile app format
        val deepLink = "aishou://match?testId=$testId"
        println("DEBUG: Created compatibility ready deep link: $deepLink")

        val payload = CompatibilityNotification(
            app_id = appId,
            include_subscription_ids = validPlayerIds,
            headings = mapOf(
                "ja" to "相性結果が出ました！",
                "en" to "Compatibility Results Ready!",
                "zh" to "匹配结果出来了！",
                "ko" to "궁합 결과가 나왔습니다!"
            ),
            contents = mapOf(
                "ja" to "あなたたちの相性は${compatibilityScore}%です！結果を見てみよう ❤️",
                "en" to "You're ${compatibilityScore}% compatible! Check out your results ❤️",
                "zh" to "你们的匹配度是${compatibilityScore}%！来查看结果吧 ❤️",
                "ko" to "두 분의 궁합도는 ${compatibilityScore}%입니다! 결과를 확인해보세요 ❤️"
            ),
            data = mapOf(
                "type" to "compatibility_ready",
                "testId" to testId,
                "score" to compatibilityScore.toString(),
                "deepLink" to deepLink
            ),
            url = deepLink
        )

        // Use consistent authorization format
        val authHeader = getAuthorizationHeader()
        println("DEBUG: sendCompatibilityReady - Authorization header: '${authHeader.take(30)}...'")

        val response = client.post("https://api.onesignal.com/notifications") {
            header("Authorization", authHeader)
            header("Content-Type", "application/json; charset=utf-8")
            setBody(payload)
        }

        val responseBody = response.bodyAsText()
        println("DEBUG: sendCompatibilityReady Response: $responseBody")
        println("DEBUG: sendCompatibilityReady Status Code: ${response.status}")

        if (!response.status.isSuccess()) {
            println("ERROR: sendCompatibilityReady failed with status: ${response.status}")
            println("ERROR: Response body: $responseBody")
        }
    }

    suspend fun sendFriendRequest(playerIds: List<String>, senderName: String, message: String?, senderId: String) {
        if (playerIds.isEmpty()) {
            println("DEBUG: No player IDs provided, skipping notification")
            return
        }

        @kotlinx.serialization.Serializable
        data class FriendRequestNotification(
            val app_id: String,
            val include_subscription_ids: List<String>, // Changed from include_player_ids to include_subscription_ids
            val headings: Map<String, String>,
            val contents: Map<String, String>,
            val data: Map<String, String>,
            val url: String? = null
        )

        // First check if subscription IDs are valid by filtering empty/invalid ones
        val basicValidPlayerIds = playerIds.filter { it.isNotEmpty() && it.length > 10 }
        println("DEBUG: Original subscription IDs: $playerIds")
        println("DEBUG: Basic valid subscription IDs after filtering: $basicValidPlayerIds")

        if (basicValidPlayerIds.isEmpty()) {
            println("DEBUG: No basic valid player IDs found after filtering, skipping notification")
            return
        }

        // Skip validation for now and use basic filtering
        // Player validation can be unreliable and block legitimate notifications
        val validPlayerIds = basicValidPlayerIds
        println("DEBUG: Using basic validated subscription IDs: $validPlayerIds (User-based API)")

        // Create deep link for friend request - mobile app format
        val deepLink = "aishou://friends/request?senderId=$senderId&senderName=${java.net.URLEncoder.encode(senderName, "UTF-8")}"
        println("DEBUG: Created friend request deep link: $deepLink")

        val payload = FriendRequestNotification(
            app_id = appId,
            include_subscription_ids = validPlayerIds,
            headings = mapOf(
                "ja" to "フレンドリクエスト",
                "en" to "Friend Request"
            ),
            contents = mapOf(
                "ja" to "${senderName}からフレンドリクエストが届きました${if (message != null) ": $message" else ""}",
                "en" to "$senderName sent you a friend request${if (message != null) ": $message" else ""}"
            ),
            data = mapOf(
                "type" to "friend_request",
                "senderName" to senderName,
                "senderId" to senderId,
                "deepLink" to deepLink
            ),
            url = deepLink
        )

        val authHeader = getAuthorizationHeader()
        println("DEBUG: sendFriendRequest - Authorization header: '${authHeader.take(30)}...'")

        // Log the exact JSON being sent
        val jsonPayload = kotlinx.serialization.json.Json.encodeToString(payload)
        println("DEBUG: sendFriendRequest - Exact JSON payload: $jsonPayload")

        val response = client.post("https://api.onesignal.com/notifications") {
            header("Authorization", authHeader)
            header("Content-Type", "application/json; charset=utf-8")
            setBody(payload)
        }

        val responseBody = response.bodyAsText()
        println("DEBUG: sendFriendRequest Response: $responseBody")
        println("DEBUG: sendFriendRequest Status Code: ${response.status}")

        if (!response.status.isSuccess()) {
            println("ERROR: sendFriendRequest failed with status: ${response.status}")
            println("ERROR: Response body: $responseBody")
        } else {
            // Check for OneSignal specific errors even with 200 OK
            if (responseBody.contains("All included players are not subscribed")) {
                println("WARNING: Player IDs are not subscribed to push notifications")
                println("WARNING: This means the device is not properly registered with OneSignal")
                println("WARNING: User needs to reinstall app or re-enable notifications")
            } else if (responseBody.contains("\"id\":\"\"")) {
                println("WARNING: OneSignal returned empty notification ID - notification may not have been sent")
            } else {
                println("SUCCESS: Friend request notification sent successfully")
            }
        }
    }

    suspend fun sendFriendRequestAccepted(playerIds: List<String>, accepterName: String, accepterId: String) {
        if (playerIds.isEmpty()) return

        // Skip validation and use player IDs directly
        val validPlayerIds = playerIds.filter { it.isNotEmpty() && it.length > 10 }
        if (validPlayerIds.isEmpty()) {
            println("WARNING: No valid player IDs for friend request accepted notification")
            return
        }

        @kotlinx.serialization.Serializable
        data class FriendAcceptedNotification(
            val app_id: String,
            val include_subscription_ids: List<String>,
            val headings: Map<String, String>,
            val contents: Map<String, String>,
            val data: Map<String, String>,
            val url: String? = null
        )

        // Create deep link for friend response screen - mobile app format
        val deepLink = "aishou://friends/respond/$accepterId"
        println("DEBUG: Created friend request accepted deep link: $deepLink")

        val payload = FriendAcceptedNotification(
            app_id = appId,
            include_subscription_ids = validPlayerIds,
            headings = mapOf(
                "ja" to "フレンドリクエスト承認",
                "en" to "Friend Request Accepted"
            ),
            contents = mapOf(
                "ja" to "${accepterName}があなたのフレンドリクエストを承認しました！ 🎉",
                "en" to "$accepterName accepted your friend request! 🎉"
            ),
            data = mapOf(
                "type" to "friend_request_accepted",
                "accepterName" to accepterName,
                "accepterId" to accepterId,
                "deepLink" to deepLink
            ),
            url = deepLink
        )

        val authHeader = getAuthorizationHeader()
        println("DEBUG: sendFriendRequestAccepted - Authorization header: '${authHeader.take(30)}...'")

        val response = client.post("https://api.onesignal.com/notifications") {
            header("Authorization", authHeader)
            header("Content-Type", "application/json; charset=utf-8")
            setBody(payload)
        }

        val responseBody = response.bodyAsText()
        println("DEBUG: sendFriendRequestAccepted Response: $responseBody")
        println("DEBUG: sendFriendRequestAccepted Status Code: ${response.status}")

        if (!response.status.isSuccess()) {
            println("ERROR: sendFriendRequestAccepted failed with status: ${response.status}")
            println("ERROR: Response body: $responseBody")
        } else {
            // Check for OneSignal specific errors even with 200 OK
            if (responseBody.contains("All included players are not subscribed")) {
                println("WARNING: Player IDs are not subscribed to push notifications (friend accepted)")
                println("WARNING: This means the device is not properly registered with OneSignal")
            } else if (responseBody.contains("\"id\":\"\"")) {
                println("WARNING: OneSignal returned empty notification ID - friend accepted notification may not have been sent")
            } else {
                println("SUCCESS: Friend request accepted notification sent successfully")
            }
        }
    }

    suspend fun sendFriendRequestRejected(playerIds: List<String>, rejecterName: String, rejecterId: String) {
        if (playerIds.isEmpty()) return

        // Skip validation and use player IDs directly
        val validPlayerIds = playerIds.filter { it.isNotEmpty() && it.length > 10 }
        if (validPlayerIds.isEmpty()) {
            println("WARNING: No valid player IDs for friend request rejected notification")
            return
        }

        @kotlinx.serialization.Serializable
        data class FriendRejectedNotification(
            val app_id: String,
            val include_subscription_ids: List<String>,
            val headings: Map<String, String>,
            val contents: Map<String, String>,
            val data: Map<String, String>,
            val url: String? = null
        )

        // Create deep link for friend response screen (even for rejected, user might want to try again later) - mobile app format
        val deepLink = "aishou://friends/respond/$rejecterId"
        println("DEBUG: Created friend request rejected deep link: $deepLink")

        val payload = FriendRejectedNotification(
            app_id = appId,
            include_subscription_ids = validPlayerIds,
            headings = mapOf(
                "ja" to "フレンドリクエスト",
                "en" to "Friend Request"
            ),
            contents = mapOf(
                "ja" to "${rejecterName}があなたのフレンドリクエストを拒否しました",
                "en" to "$rejecterName declined your friend request"
            ),
            data = mapOf(
                "type" to "friend_request_rejected",
                "rejecterName" to rejecterName,
                "rejecterId" to rejecterId,
                "deepLink" to deepLink
            ),
            url = deepLink
        )

        val authHeader = getAuthorizationHeader()
        println("DEBUG: sendFriendRequestRejected - Authorization header: '${authHeader.take(30)}...'")

        val response = client.post("https://api.onesignal.com/notifications") {
            header("Authorization", authHeader)
            header("Content-Type", "application/json; charset=utf-8")
            setBody(payload)
        }

        val responseBody = response.bodyAsText()
        println("DEBUG: sendFriendRequestRejected Response: $responseBody")
        println("DEBUG: sendFriendRequestRejected Status Code: ${response.status}")

        if (!response.status.isSuccess()) {
            println("ERROR: sendFriendRequestRejected failed with status: ${response.status}")
            println("ERROR: Response body: $responseBody")
        } else {
            // Check for OneSignal specific errors even with 200 OK
            if (responseBody.contains("All included players are not subscribed")) {
                println("WARNING: Player IDs are not subscribed to push notifications (friend rejected)")
                println("WARNING: This means the device is not properly registered with OneSignal")
            } else if (responseBody.contains("\"id\":\"\"")) {
                println("WARNING: OneSignal returned empty notification ID - friend rejected notification may not have been sent")
            } else {
                println("SUCCESS: Friend request rejected notification sent successfully")
            }
        }
    }

    suspend fun sendInviteRejected(playerIds: List<String>, friendName: String, testId: String) {
        if (playerIds.isEmpty()) return

        // Skip validation and use player IDs directly
        val validPlayerIds = playerIds.filter { it.isNotEmpty() && it.length > 10 }
        if (validPlayerIds.isEmpty()) {
            println("WARNING: No valid player IDs for invite rejected notification")
            return
        }

        @kotlinx.serialization.Serializable
        data class InviteRejectedNotification(
            val app_id: String,
            val include_subscription_ids: List<String>,
            val headings: Map<String, String>,
            val contents: Map<String, String>,
            val data: Map<String, String>
        )

        val payload = InviteRejectedNotification(
            app_id = appId,
            include_subscription_ids = validPlayerIds,
            headings = mapOf(
                "ja" to "テスト招待",
                "en" to "Test Invite",
                "zh" to "测试邀请",
                "ko" to "테스트 초대"
            ),
            contents = mapOf(
                "ja" to "${friendName}があなたのテスト招待を辞退しました",
                "en" to "$friendName declined your test invite",
                "zh" to "$friendName 拒绝了您的测试邀请",
                "ko" to "${friendName}님이 테스트 초대를 거절했습니다"
            ),
            data = mapOf(
                "type" to "invite_rejected",
                "testId" to testId,
                "friendName" to friendName
            )
        )

        // Use consistent authorization format
        val authHeader = getAuthorizationHeader()
        println("DEBUG: sendInviteRejected - Authorization header: '${authHeader.take(30)}...'")

        val response = client.post("https://api.onesignal.com/notifications") {
            header("Authorization", authHeader)
            header("Content-Type", "application/json; charset=utf-8")
            setBody(payload)
        }

        val responseBody = response.bodyAsText()
        println("DEBUG: sendInviteRejected Response: $responseBody")
        println("DEBUG: sendInviteRejected Status Code: ${response.status}")

        if (!response.status.isSuccess()) {
            println("ERROR: sendInviteRejected failed with status: ${response.status}")
            println("ERROR: Response body: $responseBody")
        } else {
            // Check for OneSignal specific errors even with 200 OK
            if (responseBody.contains("All included players are not subscribed")) {
                println("WARNING: Player IDs are not subscribed to push notifications (invite rejected)")
                println("WARNING: This means the device is not properly registered with OneSignal")
            } else if (responseBody.contains("\"id\":\"\"")) {
                println("WARNING: OneSignal returned empty notification ID - invite rejected notification may not have been sent")
            } else {
                println("SUCCESS: Invite rejected notification sent successfully")
            }
        }
    }
}