// Test alternative KMongo query

// AuthRoutes.kt içinde test için bu kodu kullanın:

// Normal query yerine:
// val currentUser = userCol.findOne(User::_id eq uid)

// Bu query'i deneyin:
val rawDocument = userCol.withDocumentClass<org.bson.Document>()
    .findOne(org.bson.Document("_id", uid))

println("DEBUG RAW DOCUMENT: $rawDocument")
println("DEBUG RAW hasChangedName: ${rawDocument?.get("hasChangedName")}")
println("DEBUG RAW hasChangedName type: ${rawDocument?.get("hasChangedName")?.javaClass}")

// Sonra normal User object'e dönüştürün
val currentUser = userCol.findOne(User::_id eq uid)