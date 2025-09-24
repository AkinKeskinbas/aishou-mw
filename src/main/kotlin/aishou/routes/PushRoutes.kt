package aishou.routes

import aishou.config.graph
import aishou.domain.model.DeviceReg
import aishou.domain.model.BaseResponse
import aishou.domain.model.PushReq
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


fun Route.pushRoutes() {
    authenticate("auth") {
        route("/v1/push") {
            post("/register") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject
                    val r = call.receive<PushReq>()
                    val col = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")
                    col.updateOne(
                        DeviceReg::playerId eq r.playerId,
                        set(
                            DeviceReg::userId setTo uid,
                            DeviceReg::provider setTo "onesignal",
                            DeviceReg::platform setTo r.platform,
                            DeviceReg::locale setTo (r.locale ?: "ja-JP"),
                            DeviceReg::timezone setTo (r.timezone ?: "Asia/Tokyo"),
                            DeviceReg::createdAt setTo System.currentTimeMillis()
                        ),
                        UpdateOptions().upsert(true)
                    )
                    call.respond(
                        message = BaseResponse<Unit>(
                            status = "success",
                            data = null
                        )
                    )
                } catch (e: Exception) {
                    println("Error in registration: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Registration failed: ${e.message}"))
                }
            }

            // Debug endpoint to test OneSignal authentication
            get("/debug-auth") {
                try {
                    val authResult = graph.oneSignal.debugAuthentication()
                    call.respond(
                        BaseResponse(
                            status = "success",
                            data = mapOf("authResult" to authResult)
                        )
                    )
                } catch (e: Exception) {
                    println("Error in OneSignal debug: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Debug failed: ${e.message}"))
                }
            }

            // Debug endpoint to test player ID validation
            get("/debug-validate-players") {
                try {
                    val uid = call.principal<JWTPrincipal>()!!.payload.subject

                    // Get current user's device registrations
                    val col = graph.mongo.db.getCollection<DeviceReg>("deviceRegistrations")
                    val devices = col.find(DeviceReg::userId eq uid).toList()
                    val playerIds = devices.map { it.playerId }

                    println("DEBUG: Found ${devices.size} devices for user $uid")
                    println("DEBUG: Player IDs to validate: $playerIds")

                    if (playerIds.isEmpty()) {
                        call.respond(
                            BaseResponse(
                                status = "no_devices",
                                data = mapOf(
                                    "message" to "No devices registered for this user",
                                    "userId" to uid
                                )
                            )
                        )
                        return@get
                    }

                    // Validate the player IDs
                    val validPlayerIds = graph.oneSignal.validatePlayerIds(playerIds)

                    call.respond(
                        BaseResponse(
                            status = "success",
                            data = mapOf(
                                "userId" to uid,
                                "totalDevices" to devices.size,
                                "allPlayerIds" to playerIds,
                                "validPlayerIds" to validPlayerIds,
                                "validCount" to validPlayerIds.size,
                                "invalidCount" to (playerIds.size - validPlayerIds.size)
                            )
                        )
                    )

                } catch (e: Exception) {
                    println("Error in player validation debug: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Validation debug failed: ${e.message}"))
                }
            }
        }
    }
}