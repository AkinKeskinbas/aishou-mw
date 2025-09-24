package aishou.config

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun Application.installHttp() {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
    install(CallLogging) {
        level = org.slf4j.event.Level.INFO
    }
    install(CORS) { anyHost(); allowNonSimpleContentTypes = true }
    install(RateLimit) {
        register(RateLimitName("ip")) {
            rateLimiter(limit = 100, refillPeriod = 60.seconds)
            requestKey { it.request.local.remoteHost }
        }
    }
}