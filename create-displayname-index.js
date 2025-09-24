// MongoDB script to create unique index for displayName
// Run with: mongosh your-database-name create-displayname-index.js

print("Creating unique index for displayName...");

// Create unique index on displayName field
db.users.createIndex(
    { "displayName": 1 },
    {
        unique: true,
        partialFilterExpression: { "displayName": { $exists: true, $ne: null } }
    }
);

print("Unique index created for displayName field");
print("This will prevent duplicate display names in the database");