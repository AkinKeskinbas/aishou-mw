package aishou.infra.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import io.ktor.server.config.*
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo

class Mongo(conf: ApplicationConfig) {
    private val uri = conf.property("aishou.mongo.uri").getString()
    private val dbName = conf.property("aishou.mongo.db").getString()
    private val client = KMongo.createClient(
        MongoClientSettings.builder().applyConnectionString(ConnectionString(uri)).build()
    ).coroutine
    val db = client.getDatabase(dbName)
}