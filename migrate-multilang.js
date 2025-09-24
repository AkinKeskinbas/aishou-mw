// MongoDB Migration Script for Multi-language Support
// Run with: mongosh your-database-name migrate-multilang.js

print("Starting migration for multi-language support...");

// 1. Migrate TestMeta collection
print("Migrating tests collection...");

// Get all existing tests
const existingTests = db.tests.find({}).toArray();
print(`Found ${existingTests.length} existing tests`);

// Backup original tests
db.tests_backup.drop();
db.tests_backup.insertMany(existingTests);
print("Backup created in tests_backup collection");

// Clear tests collection
db.tests.drop();

// Insert migrated tests with new structure
for (const test of existingTests) {
    const newTest = {
        _id: `${test._id}_en`,
        testId: test._id,
        title: test.title,
        category: test.category,
        activeVersion: test.activeVersion,
        isActive: test.isActive || true,
        isPremium: test.isPremium || false,
        display: test.display || {},
        locale: "en",
        createdAt: test.createdAt || Date.now()
    };

    db.tests.insertOne(newTest);
    print(`Migrated test: ${test._id} -> ${newTest._id}`);
}

// 2. Migrate TestQuestion collection
print("\nMigrating testQuestions collection...");

// Get all existing questions
const existingQuestions = db.testQuestions.find({}).toArray();
print(`Found ${existingQuestions.length} existing questions`);

// Backup original questions
db.testQuestions_backup.drop();
db.testQuestions_backup.insertMany(existingQuestions);
print("Backup created in testQuestions_backup collection");

// Clear testQuestions collection
db.testQuestions.drop();

// Insert migrated questions with new structure
for (const question of existingQuestions) {
    const locale = question.locale || "en";
    const newQuestion = {
        _id: `${question.testId}_${question.version}_${question.index}_${locale}`,
        testId: question.testId,
        version: question.version,
        index: question.index,
        text: question.text,
        choices: question.choices,
        weights: question.weights,
        locale: locale
    };

    db.testQuestions.insertOne(newQuestion);
}

print(`Migrated ${existingQuestions.length} questions`);

// 3. Create indexes for better performance
print("\nCreating indexes...");

// Index for tests collection
db.tests.createIndex({ "testId": 1, "locale": 1 });
db.tests.createIndex({ "locale": 1, "isActive": 1 });

// Index for testQuestions collection
db.testQuestions.createIndex({ "testId": 1, "version": 1, "locale": 1 });
db.testQuestions.createIndex({ "testId": 1, "version": 1, "index": 1, "locale": 1 });

print("Indexes created");

// 4. Verification
print("\nVerification:");
print(`Total tests after migration: ${db.tests.countDocuments()}`);
print(`Total questions after migration: ${db.testQuestions.countDocuments()}`);

// Show sample migrated data
print("\nSample migrated test:");
printjson(db.tests.findOne());

print("\nSample migrated question:");
printjson(db.testQuestions.findOne());

print("\nMigration completed successfully!");
print("\nTo add Japanese translations:");
print("1. Copy English tests and translate titles:");
print("   db.tests.find({locale: 'en'}).forEach(function(doc) {");
print("     var newDoc = Object.assign({}, doc);");
print("     newDoc._id = doc.testId + '_ja';");
print("     newDoc.locale = 'ja';");
print("     newDoc.title = 'TRANSLATE_ME: ' + doc.title;");
print("     db.tests.insertOne(newDoc);");
print("   });");
print("\n2. Copy English questions and translate text/choices similarly");