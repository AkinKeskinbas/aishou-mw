// Check raw user document in MongoDB
// Run with: mongosh your-database-name check-raw-user.js

// Replace with your actual user ID
const userId = "YOUR_USER_ID_HERE";

print(`=== Checking user: ${userId} ===`);

// 1. Raw document
const rawUser = db.users.findOne({_id: userId});
print("\n1. Raw document:");
printjson(rawUser);

// 2. Just hasChangedName field
print("\n2. hasChangedName field specifically:");
const hasChangedField = db.users.findOne({_id: userId}, {hasChangedName: 1});
printjson(hasChangedField);

// 3. Field type analysis
print("\n3. Field analysis:");
if (rawUser && rawUser.hasChangedName !== undefined) {
    print(`hasChangedName exists: true`);
    print(`hasChangedName value: ${rawUser.hasChangedName}`);
    print(`hasChangedName type: ${typeof rawUser.hasChangedName}`);
} else {
    print(`hasChangedName field does not exist or is undefined`);
}

// 4. Set to true for testing
print("\n4. Setting hasChangedName to true for testing...");
const updateResult = db.users.updateOne(
    {_id: userId},
    {$set: {hasChangedName: true}}
);
print(`Modified count: ${updateResult.modifiedCount}`);

// 5. Verify update
print("\n5. After update:");
const updatedUser = db.users.findOne({_id: userId}, {hasChangedName: 1});
printjson(updatedUser);

print(`\n=== Make sure to replace YOUR_USER_ID_HERE with actual user ID ===`);