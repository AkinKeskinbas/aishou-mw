package aishou

import aishou.config.installDI
import aishou.config.installHttp
import aishou.config.installJwt
import aishou.routes.*
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.module() {
    installDI()
    installHttp()
    installJwt()

    routing {
        authRoutes()
        personalityRoutes()
        testRoutes()
        pushRoutes()
        inviteRoutes()
        submissionRoutes()
        quizListRoutes()
        compatibilityRoutes()
        friendsRoutes()
    }
}