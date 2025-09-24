package aishou.config

import aishou.infra.mongo.Mongo
import aishou.infra.push.OneSignalService
import aishou.infra.ai.OpenAIService
import io.ktor.server.application.*
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.aallam.openai.client.OpenAI

class AppGraph(val mongo: Mongo, val oneSignal: OneSignalService, val openAI: OpenAIService, val http: HttpClient)
lateinit var graph: AppGraph

fun Application.installDI() {
    val mongo = Mongo(environment.config)

    // HTTP client with JSON serialization for OneSignal
    val http = HttpClient(Apache) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    val oneSignalAppId = environment.config.property("aishou.onesignal.appId").getString()
    val oneSignalRestKey = environment.config.property("aishou.onesignal.restKey").getString()
    val oneSignalBaseUrl = environment.config.property("aishou.onesignal.publicBaseUrl").getString()

    println("DEBUG: OneSignal App ID: $oneSignalAppId")
    println("DEBUG: OneSignal REST Key: ${oneSignalRestKey.take(10)}...")
    println("DEBUG: OneSignal Base URL: $oneSignalBaseUrl")

    val os = OneSignalService(http, oneSignalAppId, oneSignalRestKey, oneSignalBaseUrl)
    val openAIClient = OpenAI(environment.config.property("aishou.openai.apiKey").getString())
    val openAIService = OpenAIService(openAIClient)

    graph = AppGraph(mongo, os, openAIService, http)
}