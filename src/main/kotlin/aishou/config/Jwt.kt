package aishou.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.time.Instant
import java.util.*

data class JwtCfg(val issuer:String, val audience:String, val secret:String, val accessMinutes:Long, val refreshDays:Long)

object JwtProvider {
    lateinit var cfg: JwtCfg
    private val alg get() = Algorithm.HMAC256(cfg.secret)

    fun issueAccess(userId:String): String =
        JWT.create().withIssuer(cfg.issuer).withAudience(cfg.audience)
            .withSubject(userId).withClaim("typ","access")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(cfg.accessMinutes*60)))
            .sign(alg)

    fun issueRefresh(userId:String, jti:String): String =
        JWT.create().withIssuer(cfg.issuer).withAudience(cfg.audience)
            .withSubject(userId).withJWTId(jti).withClaim("typ","refresh")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(cfg.refreshDays*24*3600)))
            .sign(alg)

    fun verifier() = JWT.require(alg).withIssuer(cfg.issuer).withAudience(cfg.audience).build()
}

fun Application.installJwt() {
    val conf = environment.config.config("aishou.jwt")
    JwtProvider.cfg = JwtCfg(
        conf.property("issuer").getString(),
        conf.property("audience").getString(),
        conf.property("secret").getString(),
        conf.property("accessMinutes").getString().toLong(),
        conf.property("refreshDays").getString().toLong()
    )

    install(Authentication) {
        jwt("auth") {
            verifier(JwtProvider.verifier())
            validate { cred -> if (cred.payload.getClaim("typ").asString()=="access") JWTPrincipal(cred.payload) else null }
        }
    }
}