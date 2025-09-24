package aishou.routes

import aishou.config.graph
import aishou.domain.model.DeviceReg
import aishou.domain.model.Invite
import aishou.domain.model.BaseResponse
import aishou.domain.model.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.litote.kmongo.*
import java.util.*
import kotlinx.serialization.Serializable

fun Route.inviteRoutes() {
  authenticate("auth") {
    route("/v1/invites") {
      post {
        @Serializable
        data class InviteCreateRequest(val friendId: String?, val testId: String, val version: Int)

        val me = call.principal<JWTPrincipal>()!!.payload.subject
        val req = call.receive<InviteCreateRequest>()
        val id = "inv_" + UUID.randomUUID().toString().take(8)
        val exp = System.currentTimeMillis() + 1000L*60*60*48
        val inv = Invite(id, me, req.friendId, req.testId, req.version, "pending", exp)
        val invites = graph.mongo.db.getCollection<Invite>("invites")
        invites.insertOne(inv)

        println("DEBUG: Invite created with ID: $id")
        println("DEBUG: FriendId: ${req.friendId}")

        if (req.friendId != null) {
          val devices = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")
          val players = devices.find(DeviceReg::userId eq req.friendId)
            .sort(org.bson.Document("createdAt", -1))
            .limit(1)
            .toList().map { it.playerId }

          println("DEBUG: Found ${players.size} device registrations for user ${req.friendId}")
          players.forEach { println("DEBUG: Player ID: $it") }

          if (players.isNotEmpty()) {
            try {
              // Get test title from TestMeta collection - try user's language first
              val userCol = graph.mongo.db.getCollection<User>("users")
              val user = userCol.findOne(User::_id eq me)
              val userLang = user?.lang?.let {
                  when (it.lowercase()) {
                      "ja", "jp" -> "ja"
                      else -> "en"
                  }
              } ?: "en"

              val testMetaCol = graph.mongo.db.getCollection<aishou.domain.model.TestMeta>("tests")

              // Try to get test meta in user's language, fallback to English
              val testMeta = testMetaCol.findOne(
                  org.litote.kmongo.and(
                      aishou.domain.model.TestMeta::testId eq req.testId,
                      aishou.domain.model.TestMeta::locale eq userLang
                  )
              ) ?: testMetaCol.findOne(
                  org.litote.kmongo.and(
                      aishou.domain.model.TestMeta::testId eq req.testId,
                      aishou.domain.model.TestMeta::locale eq "en"
                  )
              )
              val testTitle = testMeta?.title ?: "Test"

              // Get sender info for deeplink
              val senderName = user?.displayName
              val senderMbti = user?.mbtiType

              println("DEBUG: Sending invite notification - InviteId: $id, TestId: ${req.testId}, TestTitle: $testTitle, SenderName: $senderName, SenderMbti: $senderMbti")
              graph.oneSignal.sendInvite(players, id, req.testId, req.version, me, testTitle, senderName, senderMbti)
              println("DEBUG: OneSignal invite sent successfully")
            } catch (e: Exception) {
              println("DEBUG: OneSignal send failed: ${e.message}")
              e.printStackTrace()
            }
          } else {
            println("DEBUG: No player IDs found - notification not sent")
          }
        } else {
          println("DEBUG: FriendId is null - no notification sent")
        }

        call.respond(BaseResponse(status = "success", data = mapOf("inviteId" to id)))
      }
      get("/{inviteId}") {
        val id = call.parameters["inviteId"]!!
        val inv = graph.mongo.db.getCollection<Invite>("invites").findOne(Invite::_id eq id)
          ?: return@get call.respond(HttpStatusCode.NotFound)
        if (inv.status != "pending" || System.currentTimeMillis() > inv.expiresAt) return@get call.respond(HttpStatusCode.Gone)
        call.respond(BaseResponse(status = "success", data = inv))
      }
      post("/{inviteId}/accept") {
        val me = call.principal<JWTPrincipal>()!!.payload.subject
        val id = call.parameters["inviteId"]!!
        val col = graph.mongo.db.getCollection<Invite>("invites")
        val inv = col.findOne(Invite::_id eq id) ?: return@post call.respond(HttpStatusCode.NotFound)

        if (inv.toUserId == null || inv.toUserId == me) {
          col.updateOne(Invite::_id eq id, setValue(Invite::toUserId, me))
          col.updateOne(Invite::_id eq id, setValue(Invite::status, "accepted"))

          // Send notification to invite creator
          try {
            val userCol = graph.mongo.db.getCollection<User>("users")
            val acceptingUser = userCol.findOne(User::_id eq me)
            val friendName = acceptingUser?.displayName ?: "Your friend"

            val devices = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")
            val creatorPlayerIds = devices.find(DeviceReg::userId eq inv.fromUserId)
              .sort(org.bson.Document("createdAt", -1))
              .limit(1)
              .toList().map { it.playerId }

            graph.oneSignal.sendInviteAccepted(creatorPlayerIds, friendName, inv.testId)
          } catch (e: Exception) {
            println("Failed to send invite accepted notification: ${e.message}")
          }

          call.respond(BaseResponse<Unit>(status = "success", data = null))
        } else call.respond(HttpStatusCode.Forbidden)
      }

      post("/{inviteId}/reject") {
        val me = call.principal<JWTPrincipal>()!!.payload.subject
        val id = call.parameters["inviteId"]!!
        val col = graph.mongo.db.getCollection<Invite>("invites")
        val inv = col.findOne(Invite::_id eq id) ?: return@post call.respond(HttpStatusCode.NotFound)

        if (inv.toUserId == null || inv.toUserId == me) {
          // Set toUserId if it's null (anonymous invite) and update status
          if (inv.toUserId == null) {
            col.updateOne(Invite::_id eq id, setValue(Invite::toUserId, me))
          }
          col.updateOne(Invite::_id eq id, setValue(Invite::status, "rejected"))

          println("DEBUG: Invite $id rejected by user $me")

          // Send notification to invite creator (optional - you might want to skip this)
          try {
            val userCol = graph.mongo.db.getCollection<User>("users")
            val rejectingUser = userCol.findOne(User::_id eq me)
            val friendName = rejectingUser?.displayName ?: "Your friend"

            val devices = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")
            val creatorPlayerIds = devices.find(DeviceReg::userId eq inv.fromUserId)
              .sort(org.bson.Document("createdAt", -1))
              .limit(1)
              .toList().map { it.playerId }

            if (creatorPlayerIds.isNotEmpty()) {
              println("DEBUG: Sending invite rejected notification to creator")
              graph.oneSignal.sendInviteRejected(creatorPlayerIds, friendName, inv.testId)
            }
          } catch (e: Exception) {
            println("Failed to send invite rejected notification: ${e.message}")
          }

          call.respond(BaseResponse<Unit>(status = "success", data = null))
        } else {
          call.respond(HttpStatusCode.Forbidden)
        }
      }

      // Get received invites (pending ones sent to me)
      get("/received") {
        val me = call.principal<JWTPrincipal>()!!.payload.subject
        println("DEBUG: Getting received invites for user: $me")

        try {
          val inviteCol = graph.mongo.db.getCollection<Invite>("invites")
          val currentTime = System.currentTimeMillis()

          val receivedInvites = inviteCol.find(
            and(
              Invite::toUserId eq me,
              Invite::status eq "pending",
              Invite::expiresAt gte currentTime
            )
          ).sort(descending(Invite::createdAt)).toList()

          println("DEBUG: Found ${receivedInvites.size} pending received invites for user $me")

          // Get sender information for each invite
          val senderIds = receivedInvites.map { it.fromUserId }.distinct()
          val userCol = graph.mongo.db.getCollection<User>("users")
          val sendersMap = userCol.find(User::_id `in` senderIds).toList().associateBy { it._id }

          // Get test information for each invite - prefer current user's language
          val currentUser = userCol.findOne(User::_id eq me)
          val userLang = currentUser?.lang?.let {
              when (it.lowercase()) {
                  "ja", "jp" -> "ja"
                  else -> "en"
              }
          } ?: "en"

          val testIds = receivedInvites.map { it.testId }.distinct()
          val testCol = graph.mongo.db.getCollection<aishou.domain.model.TestMeta>("tests")

          // Try to get tests in user's language first
          val testsInLang = testCol.find(
              and(
                  aishou.domain.model.TestMeta::testId `in` testIds,
                  aishou.domain.model.TestMeta::locale eq userLang
              )
          ).toList()

          // Get English fallbacks for missing tests
          val foundTestIds = testsInLang.map { it.testId }.toSet()
          val missingTestIds = testIds.filter { it !in foundTestIds }
          val englishFallbacks = if (missingTestIds.isNotEmpty()) {
              testCol.find(
                  and(
                      aishou.domain.model.TestMeta::testId `in` missingTestIds,
                      aishou.domain.model.TestMeta::locale eq "en"
                  )
              ).toList()
          } else emptyList()

          val allTests = testsInLang + englishFallbacks
          val testsMap = allTests.associateBy { it.testId }

          @kotlinx.serialization.Serializable
          data class ReceivedInviteDto(
            val inviteId: String,
            val testId: String,
            val testTitle: String?,
            val testCategory: String?,
            val version: Int,
            val fromUserId: String,
            val senderName: String?,
            val senderMbti: String?,
            val message: String?,
            val createdAt: Long,
            val expiresAt: Long
          )

          val receivedInvitesDto = receivedInvites.map { invite ->
            val sender = sendersMap[invite.fromUserId]
            val test = testsMap[invite.testId]

            println("DEBUG: Processing invite ${invite._id} from ${invite.fromUserId} (${sender?.displayName}) for test ${invite.testId} (${test?.title})")

            ReceivedInviteDto(
              inviteId = invite._id,
              testId = invite.testId,
              testTitle = test?.title,
              testCategory = test?.category,
              version = invite.version,
              fromUserId = invite.fromUserId,
              senderName = sender?.displayName,
              senderMbti = sender?.mbtiType,
              message = null, // Message field not available in current Invite model
              createdAt = invite.createdAt,
              expiresAt = invite.expiresAt
            )
          }

          println("DEBUG: Returning ${receivedInvitesDto.size} received invites with full details")
          call.respond(BaseResponse(status = "success", data = receivedInvitesDto))

        } catch (e: Exception) {
          println("ERROR: Failed to get received invites for user $me: ${e.message}")
          e.printStackTrace()
          call.respond(HttpStatusCode.InternalServerError, BaseResponse<Unit>(status = "error", data = null))
        }
      }

      // Get sent invites (invites I have sent)
      get("/sent") {
        val me = call.principal<JWTPrincipal>()!!.payload.subject
        println("DEBUG: Getting sent invites for user: $me")

        try {
          val inviteCol = graph.mongo.db.getCollection<Invite>("invites")

          val sentInvites = inviteCol.find(Invite::fromUserId eq me)
            .sort(descending(Invite::createdAt)).toList()

          println("DEBUG: Found ${sentInvites.size} total sent invites for user $me")

          // Get recipient information for each invite
          val recipientIds = sentInvites.mapNotNull { it.toUserId }.distinct()
          val userCol = graph.mongo.db.getCollection<User>("users")
          val recipientsMap = userCol.find(User::_id `in` recipientIds).toList().associateBy { it._id }

          // Get test information for each invite - prefer current user's language
          val currentUser = userCol.findOne(User::_id eq me)
          val userLang = currentUser?.lang?.let {
              when (it.lowercase()) {
                  "ja", "jp" -> "ja"
                  else -> "en"
              }
          } ?: "en"

          val testIds = sentInvites.map { it.testId }.distinct()
          val testCol = graph.mongo.db.getCollection<aishou.domain.model.TestMeta>("tests")

          // Try to get tests in user's language first
          val testsInLang = testCol.find(
              and(
                  aishou.domain.model.TestMeta::testId `in` testIds,
                  aishou.domain.model.TestMeta::locale eq userLang
              )
          ).toList()

          // Get English fallbacks for missing tests
          val foundTestIds = testsInLang.map { it.testId }.toSet()
          val missingTestIds = testIds.filter { it !in foundTestIds }
          val englishFallbacks = if (missingTestIds.isNotEmpty()) {
              testCol.find(
                  and(
                      aishou.domain.model.TestMeta::testId `in` missingTestIds,
                      aishou.domain.model.TestMeta::locale eq "en"
                  )
              ).toList()
          } else emptyList()

          val allTests = testsInLang + englishFallbacks
          val testsMap = allTests.associateBy { it.testId }

          @kotlinx.serialization.Serializable
          data class SentInviteDto(
            val inviteId: String,
            val testId: String,
            val testTitle: String?,
            val testCategory: String?,
            val version: Int,
            val toUserId: String?,
            val recipientName: String?,
            val recipientMbti: String?,
            val message: String?,
            val status: String, // pending, accepted, expired
            val createdAt: Long,
            val expiresAt: Long,
            val isExpired: Boolean
          )

          val currentTime = System.currentTimeMillis()
          val sentInvitesDto = sentInvites.map { invite ->
            val recipient = if (invite.toUserId != null) recipientsMap[invite.toUserId] else null
            val test = testsMap[invite.testId]
            val isExpired = currentTime > invite.expiresAt
            val actualStatus = if (isExpired && invite.status == "pending") "expired" else invite.status

            println("DEBUG: Processing sent invite ${invite._id} to ${invite.toUserId} (${recipient?.displayName}) for test ${invite.testId} (${test?.title}) - Status: $actualStatus")

            SentInviteDto(
              inviteId = invite._id,
              testId = invite.testId,
              testTitle = test?.title,
              testCategory = test?.category,
              version = invite.version,
              toUserId = invite.toUserId,
              recipientName = recipient?.displayName,
              recipientMbti = recipient?.mbtiType,
              message = null, // Message field not available in current Invite model
              status = actualStatus,
              createdAt = invite.createdAt,
              expiresAt = invite.expiresAt,
              isExpired = isExpired
            )
          }

          println("DEBUG: Returning ${sentInvitesDto.size} sent invites with full details")
          call.respond(BaseResponse(status = "success", data = sentInvitesDto))

        } catch (e: Exception) {
          println("ERROR: Failed to get sent invites for user $me: ${e.message}")
          e.printStackTrace()
          call.respond(HttpStatusCode.InternalServerError, BaseResponse<Unit>(status = "error", data = null))
        }
      }
    }
  }
}
