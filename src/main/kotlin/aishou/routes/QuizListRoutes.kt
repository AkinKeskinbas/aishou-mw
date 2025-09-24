package aishou.routes

import aishou.config.graph
import aishou.domain.model.Compatibility
import aishou.domain.model.Submission
import aishou.domain.model.BaseResponse
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.litote.kmongo.*
import kotlinx.serialization.Serializable

fun Route.quizListRoutes() {
  authenticate("auth") {
    route("/v1/quiz") {
      get("/solo") {
        val uid = call.principal<JWTPrincipal>()!!.payload.subject
        val testId = call.request.queryParameters["testId"]
        val col = graph.mongo.db.getCollection<Submission>("submissions")
        val baseFilter = and(Submission::uid eq uid, Submission::isInviteResponse eq false) // Only true solo submissions
        val filter = if (testId==null) baseFilter else and(baseFilter, Submission::testId eq testId)
        val list = col.find(filter).sort(descending(Submission::completedAt)).toList()
        @Serializable
        data class SoloItemDto(val submissionId:String, val testId:String, val version:Int, val totalScore:Int?, val completedAt:Long)
        val items = list.map { SoloItemDto(it._id?.toString() ?: it.hashCode().toString(), it.testId, it.version, it.totalScore, it.completedAt) }
        call.respond(BaseResponse(status = "success", data = mapOf("items" to items)))
      }
      get("/matches") {
        val uid = call.principal<JWTPrincipal>()!!.payload.subject
        val testId = call.request.queryParameters["testId"]
        val col = graph.mongo.db.getCollection<Compatibility>("compatibility")
        val filterBase = or(Compatibility::uidA eq uid, Compatibility::uidB eq uid)
        val filter = if (testId==null) filterBase else and(filterBase, Compatibility::testId eq testId)
        val list = col.find(filter).sort(descending(Compatibility::createdAt)).toList()
        @Serializable
        data class MatchItemDto(val compatibilityId:String, val testId:String, val version:Int, val friendId:String, val score:Int, val summary:String, val createdAt:Long)
        val items = list.map {
          val friend = if (it.uidA==uid) it.uidB else it.uidA
          MatchItemDto(it._id, it.testId, it.version, friend, it.score, it.summary, it.createdAt)
        }
        call.respond(BaseResponse(status = "success", data = mapOf("items" to items)))
      }
    }
  }
}