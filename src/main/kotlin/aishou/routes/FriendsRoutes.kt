package aishou.routes

import aishou.domain.model.*
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

private fun FriendRequest.toDto(): FriendRequestDto {
    return FriendRequestDto(
        id = this._id?.toString() ?: "",
        fromUserId = this.fromUserId,
        toUserId = this.toUserId,
        status = this.status,
        tag = this.tag,
        message = this.message,
        isRead = this.isRead,
        createdAt = this.createdAt,
        respondedAt = this.respondedAt
    )
}

fun Route.friendsRoutes() {
    route("/v1/friends") {
        authenticate("auth") {

            // Debug endpoint to list all users with displayNames
            get("/debug/users") {
                try {
                    val userCol = graph.mongo.db.getCollection<User>("users")
                    val users = userCol.find().limit(20).toList()

                    @kotlinx.serialization.Serializable
                    data class UserDebugInfo(
                        val userId: String,
                        val displayName: String?,
                        val isAnonymous: Boolean,
                        val createdAt: Long
                    )

                    val userInfo = users.map {
                        UserDebugInfo(
                            userId = it._id,
                            displayName = it.displayName,
                            isAnonymous = it.isAnonymous,
                            createdAt = it.createdAt
                        )
                    }
                    call.respond(BaseResponse(status = "success", data = userInfo))
                } catch (e: Exception) {
                    println("DEBUG ENDPOINT ERROR: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // Send friend request
            post("/request") {
                try {
                    println("DEBUG: Friend request endpoint called")
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject
                    val req = call.receive<SendFriendRequestReq>()
                    println("DEBUG: Request received - toUserId: '${req.toUserId}', toUserDisplayName: '${req.toUserDisplayName}', from uid: $uid")

                    // Determine target user ID based on input
                    val userCol = graph.mongo.db.getCollection<User>("users")
                    val targetUserId = when {
                        // Legacy support: direct user ID provided
                        !req.toUserId.isNullOrBlank() -> req.toUserId
                        // New way: search by display name
                        !req.toUserDisplayName.isNullOrBlank() -> {
                            println("DEBUG: Searching for user with displayName: '${req.toUserDisplayName}'")
                            println("DEBUG: DisplayName length: ${req.toUserDisplayName.length}")

                            // Try exact match first
                            val userByDisplayName = userCol.findOne(User::displayName eq req.toUserDisplayName)
                            println("DEBUG: Exact match found: ${userByDisplayName?._id} with displayName: '${userByDisplayName?.displayName}'")

                            // If not found, try case insensitive search
                            if (userByDisplayName == null) {
                                println("DEBUG: Trying case insensitive search...")
                                val allUsers = userCol.find().toList()
                                println("DEBUG: Total users in DB: ${allUsers.size}")
                                val matchingUsers = allUsers.filter { user ->
                                    user.displayName?.equals(req.toUserDisplayName, ignoreCase = true) == true
                                }
                                println("DEBUG: Case insensitive matches: ${matchingUsers.size}")
                                matchingUsers.forEach { user ->
                                    println("DEBUG: Match - ID: ${user._id}, DisplayName: '${user.displayName}'")
                                }

                                // Also show some sample users for debugging
                                println("DEBUG: Sample users from DB:")
                                allUsers.take(5).forEach { user ->
                                    println("DEBUG: User - ID: ${user._id}, DisplayName: '${user.displayName}', isAnonymous: ${user.isAnonymous}")
                                }
                            }

                            userByDisplayName?._id
                        }
                        else -> null
                    }

                    if (targetUserId.isNullOrBlank()) {
                        return@post call.respond(HttpStatusCode.NotFound,
                            BaseResponse<Unit>(status = "user_not_found", data = null))
                    }

                    // Check if user exists
                    val targetUser = userCol.findOne(User::_id eq targetUserId)
                        ?: return@post call.respond(HttpStatusCode.NotFound,
                            BaseResponse<Unit>(status = "user_not_found", data = null))

                    // Check if users are already friends
                    val friendshipCol = graph.mongo.db.getCollection<Friendship>("friendships")
                    val existingFriendship = friendshipCol.findOne(
                        or(
                            and(Friendship::userAId eq uid, Friendship::userBId eq targetUserId),
                            and(Friendship::userAId eq targetUserId, Friendship::userBId eq uid)
                        )
                    )
                    if (existingFriendship != null) {
                        return@post call.respond(HttpStatusCode.Conflict,
                            BaseResponse<Unit>(status = "already_friends", data = null))
                    }

                    // Check if there's already a pending request
                    val requestCol = graph.mongo.db.getCollection<FriendRequest>("friend_requests")
                    val existingRequest = requestCol.findOne(
                        and(
                            FriendRequest::fromUserId eq uid,
                            FriendRequest::toUserId eq targetUserId,
                            FriendRequest::status eq FriendRequestStatus.PENDING
                        )
                    )
                    if (existingRequest != null) {
                        return@post call.respond(HttpStatusCode.Conflict,
                            BaseResponse<Unit>(status = "request_already_sent", data = null))
                    }

                    // Create friend request
                    val friendRequest = FriendRequest(
                        fromUserId = uid,
                        toUserId = targetUserId,
                        tag = req.tag,
                        message = req.message
                    )

                    requestCol.insertOne(friendRequest)

                    // Send push notification
                    val currentUser = userCol.findOne(User::_id eq uid)
                    val senderName = currentUser?.displayName ?: "Someone"
                    println("DEBUG: Friend request from $uid ($senderName) to $targetUserId")

                    // Get target user's most recent device token
                    val deviceCol = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")
                    val devices = deviceCol.find(DeviceReg::userId eq targetUserId)
                        .sort(org.bson.Document("createdAt", -1)) // Sort by most recent first
                        .limit(1)
                        .toList()

                    println("DEBUG: Found ${devices.size} devices for user $targetUserId")

                    if (devices.isNotEmpty()) {
                        val mostRecentDevice = devices.first()
                        val playerId = mostRecentDevice.playerId
                        println("DEBUG: Using most recent device - Player ID: $playerId, Created: ${mostRecentDevice.createdAt}")

                        try {
                            println("DEBUG: Sending friend request notification to most recent device: $playerId")
                            graph.oneSignal.sendFriendRequest(listOf(playerId), senderName, req.message, uid)
                            // Success/failure logging is now handled inside OneSignal service
                        } catch (e: Exception) {
                            println("ERROR: Failed to send friend request notification: ${e.message}")
                            e.printStackTrace()
                        }
                    } else {
                        println("DEBUG: No devices found for user $targetUserId, notification not sent")
                    }

                    call.respond(BaseResponse(status = "success", data = friendRequest.toDto()))

                } catch (e: Exception) {
                    println("Error sending friend request: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // Respond to friend request (accept/reject)
            post("/request/{requestId}/respond") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject
                    val requestId = call.parameters["requestId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest,
                            BaseResponse<Unit>(status = "missing_request_id", data = null))

                    val req = call.receive<RespondFriendRequestReq>()

                    val requestCol = graph.mongo.db.getCollection<FriendRequest>("friend_requests")
                    val friendRequest = requestCol.findOne(
                        and(
                            FriendRequest::_id eq org.bson.types.ObjectId(requestId),
                            FriendRequest::toUserId eq uid,
                            FriendRequest::status eq FriendRequestStatus.PENDING
                        )
                    ) ?: return@post call.respond(HttpStatusCode.NotFound,
                        BaseResponse<Unit>(status = "request_not_found", data = null))

                    // Update request status
                    val newStatus = if (req.accept) FriendRequestStatus.ACCEPTED else FriendRequestStatus.REJECTED
                    requestCol.updateOne(
                        FriendRequest::_id eq org.bson.types.ObjectId(requestId),
                        combine(
                            setValue(FriendRequest::status, newStatus),
                            setValue(FriendRequest::respondedAt, System.currentTimeMillis())
                        )
                    )

                    if (req.accept) {
                        // Create friendship
                        val friendshipCol = graph.mongo.db.getCollection<Friendship>("friendships")
                        val friendship = Friendship(
                            userAId = friendRequest.fromUserId,
                            userBId = uid,
                            tagA = friendRequest.tag,
                            tagB = req.tag
                        )
                        friendshipCol.insertOne(friendship)

                        // Send acceptance notification
                        val userCol = graph.mongo.db.getCollection<User>("users")
                        val accepterUser = userCol.findOne(User::_id eq uid)
                        val accepterName = accepterUser?.displayName ?: "Someone"

                        val deviceCol = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")
                        val devices = deviceCol.find(DeviceReg::userId eq friendRequest.fromUserId)
                            .sort(org.bson.Document("createdAt", -1))
                            .limit(1)
                            .toList()

                        if (devices.isNotEmpty()) {
                            val playerId = devices.first().playerId
                            println("DEBUG: Sending friend request accepted notification to ${friendRequest.fromUserId}")
                            println("DEBUG: Accepter: $accepterName (ID: $uid), Player ID: $playerId")
                            try {
                                graph.oneSignal.sendFriendRequestAccepted(listOf(playerId), accepterName, uid)
                            } catch (e: Exception) {
                                println("ERROR: Failed to send friend request accepted notification: ${e.message}")
                                e.printStackTrace()
                            }
                        } else {
                            println("DEBUG: No devices found for user ${friendRequest.fromUserId}, accepted notification not sent")
                        }
                    } else {
                        // Send rejection notification
                        val userCol = graph.mongo.db.getCollection<User>("users")
                        val rejecterUser = userCol.findOne(User::_id eq uid)
                        val rejecterName = rejecterUser?.displayName ?: "Someone"

                        val deviceCol = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")
                        val devices = deviceCol.find(DeviceReg::userId eq friendRequest.fromUserId)
                            .sort(org.bson.Document("createdAt", -1))
                            .limit(1)
                            .toList()

                        if (devices.isNotEmpty()) {
                            val playerId = devices.first().playerId
                            println("DEBUG: Sending friend request rejected notification to ${friendRequest.fromUserId}")
                            println("DEBUG: Rejecter: $rejecterName (ID: $uid), Player ID: $playerId")
                            try {
                                graph.oneSignal.sendFriendRequestRejected(listOf(playerId), rejecterName, uid)
                            } catch (e: Exception) {
                                println("ERROR: Failed to send friend request rejected notification: ${e.message}")
                                e.printStackTrace()
                            }
                        } else {
                            println("DEBUG: No devices found for user ${friendRequest.fromUserId}, rejected notification not sent")
                        }
                    }

                    call.respond(BaseResponse(status = "success", data = mapOf("accepted" to req.accept)))

                } catch (e: Exception) {
                    println("Error responding to friend request: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // Get friend requests (received)
            get("/requests/received") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject
                    val unreadOnly = call.request.queryParameters["unread_only"]?.toBoolean() ?: false

                    val requestCol = graph.mongo.db.getCollection<FriendRequest>("friend_requests")

                    val filters = mutableListOf(
                        FriendRequest::toUserId eq uid,
                        FriendRequest::status eq FriendRequestStatus.PENDING
                    )

                    if (unreadOnly) {
                        filters.add(FriendRequest::isRead eq false)
                    }

                    val requests = requestCol.find(and(*filters.toTypedArray())).toList()

                    // Get sender info for each request
                    val userCol = graph.mongo.db.getCollection<User>("users")
                    val requestsWithSenderInfo = requests.map { request ->
                        val sender = userCol.findOne(User::_id eq request.fromUserId)
                        RequestWithSenderInfo(
                            request = request.toDto(),
                            sender = SenderInfo(
                                userId = request.fromUserId,
                                displayName = sender?.displayName,
                                photoUrl = sender?.photoUrl,
                                mbtiType = sender?.mbtiType,
                                zodiacSign = sender?.zodiacSign
                            )
                        )
                    }

                    call.respond(BaseResponse(status = "success", data = requestsWithSenderInfo))

                } catch (e: Exception) {
                    println("Error getting friend requests: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // Mark friend request as read
            put("/request/{requestId}/mark-read") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject
                    val requestId = call.parameters["requestId"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest,
                            BaseResponse<Unit>(status = "missing_request_id", data = null))

                    val requestCol = graph.mongo.db.getCollection<FriendRequest>("friend_requests")
                    val updateResult = requestCol.updateOne(
                        and(
                            FriendRequest::_id eq org.bson.types.ObjectId(requestId),
                            FriendRequest::toUserId eq uid
                        ),
                        setValue(FriendRequest::isRead, true)
                    )

                    if (updateResult.matchedCount == 0L) {
                        return@put call.respond(HttpStatusCode.NotFound,
                            BaseResponse<Unit>(status = "request_not_found", data = null))
                    }

                    call.respond(BaseResponse<Unit>(status = "success", data = null))

                } catch (e: Exception) {
                    println("Error marking friend request as read: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // Mark all friend requests as read
            put("/requests/mark-all-read") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject

                    val requestCol = graph.mongo.db.getCollection<FriendRequest>("friend_requests")
                    requestCol.updateMany(
                        and(
                            FriendRequest::toUserId eq uid,
                            FriendRequest::status eq FriendRequestStatus.PENDING,
                            FriendRequest::isRead eq false
                        ),
                        setValue(FriendRequest::isRead, true)
                    )

                    call.respond(BaseResponse<Unit>(status = "success", data = null))

                } catch (e: Exception) {
                    println("Error marking all friend requests as read: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // Get unread friend request count
            get("/requests/unread-count") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject

                    val requestCol = graph.mongo.db.getCollection<FriendRequest>("friend_requests")
                    val unreadCount = requestCol.countDocuments(
                        and(
                            FriendRequest::toUserId eq uid,
                            FriendRequest::status eq FriendRequestStatus.PENDING,
                            FriendRequest::isRead eq false
                        )
                    )

                    call.respond(BaseResponse(status = "success", data = UnreadCount(count = unreadCount)))

                } catch (e: Exception) {
                    println("Error getting unread friend request count: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // Get friend requests (sent)
            get("/requests/sent") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject

                    val requestCol = graph.mongo.db.getCollection<FriendRequest>("friend_requests")
                    val requests = requestCol.find(
                        FriendRequest::fromUserId eq uid
                    ).toList()

                    // Get receiver info for each request
                    val userCol = graph.mongo.db.getCollection<User>("users")
                    val requestsWithReceiverInfo = requests.map { request ->
                        val receiver = userCol.findOne(User::_id eq request.toUserId)
                        RequestWithReceiverInfo(
                            request = request.toDto(),
                            receiver = ReceiverInfo(
                                userId = request.toUserId,
                                displayName = receiver?.displayName,
                                photoUrl = receiver?.photoUrl,
                                mbtiType = receiver?.mbtiType,
                                zodiacSign = receiver?.zodiacSign
                            )
                        )
                    }

                    call.respond(BaseResponse(status = "success", data = requestsWithReceiverInfo))

                } catch (e: Exception) {
                    println("Error getting sent friend requests: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // List friends
            get("/my-friends") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject

                    val friendshipCol = graph.mongo.db.getCollection<Friendship>("friendships")
                    val friendships = friendshipCol.find(
                        or(
                            Friendship::userAId eq uid,
                            Friendship::userBId eq uid
                        )
                    ).toList()

                    val userCol = graph.mongo.db.getCollection<User>("users")
                    val friends = friendships.map { friendship ->
                        val friendId = if (friendship.userAId == uid) friendship.userBId else friendship.userAId
                        val tag = if (friendship.userAId == uid) friendship.tagB else friendship.tagA

                        val friend = userCol.findOne(User::_id eq friendId)
                        FriendInfo(
                            userId = friendId,
                            displayName = friend?.displayName,
                            photoUrl = friend?.photoUrl,
                            tag = tag,
                            mbtiType = friend?.mbtiType,
                            zodiacSign = friend?.zodiacSign,
                            friendsSince = friendship.createdAt
                        )
                    }

                    call.respond(BaseResponse(status = "success", data = friends))

                } catch (e: Exception) {
                    println("Error getting friends list: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }

            // Remove friend
            delete("/{friendId}") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject
                    val friendId = call.parameters["friendId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest,
                            BaseResponse<Unit>(status = "missing_friend_id", data = null))

                    val friendshipCol = graph.mongo.db.getCollection<Friendship>("friendships")
                    val deleteResult = friendshipCol.deleteOne(
                        or(
                            and(Friendship::userAId eq uid, Friendship::userBId eq friendId),
                            and(Friendship::userAId eq friendId, Friendship::userBId eq uid)
                        )
                    )

                    if (deleteResult.deletedCount == 0L) {
                        return@delete call.respond(HttpStatusCode.NotFound,
                            BaseResponse<Unit>(status = "friendship_not_found", data = null))
                    }

                    call.respond(BaseResponse<Unit>(status = "success", data = null))

                } catch (e: Exception) {
                    println("Error removing friend: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError,
                        BaseResponse<Unit>(status = "error", data = null))
                }
            }
        }
    }
}