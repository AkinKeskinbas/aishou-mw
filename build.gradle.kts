plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

repositories { mavenCentral() }

dependencies {
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.json)
  implementation(libs.ktor.server.auth)
  implementation(libs.ktor.server.auth.jwt)
  implementation(libs.ktor.server.call.logging)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.rate.limit)

  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.apache)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.logging)

  implementation(libs.kmongo.serialization)
  implementation(libs.kmongo.coroutine)

  implementation(libs.java.jwt)
  implementation(libs.logback)
  implementation(libs.okio)
  implementation(libs.openai.client)
}

application {
  mainClass.set("io.ktor.server.netty.EngineMain")
}

kotlin { jvmToolchain(17) }
