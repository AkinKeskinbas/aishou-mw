// MongoDB Debug Script for hasChangedName field
// Run with: mongosh your-database-name debug-haschangedname.js

print("=== hasChangedName Field Debug ===");

// 1. Check users with hasChangedName field
print("\n1. Users with hasChangedName field:");
const usersWithField = db.users.find({ hasChangedName: { $exists: true } });
usersWithField.forEach(function(user) {
    print(`User ${user._id}: hasChangedName = ${user.hasChangedName}`);
});

// 2. Check users without hasChangedName field
print("\n2. Users without hasChangedName field:");
const usersWithoutField = db.users.find({ hasChangedName: { $exists: false } });
let countWithoutField = 0;
usersWithoutField.forEach(function(user) {
    print(`User ${user._id}: hasChangedName field missing`);
    countWithoutField++;
});
print(`Total users without hasChangedName field: ${countWithoutField}`);

// 3. Sample user document structure
print("\n3. Sample user document:");
const sampleUser = db.users.findOne();
if (sampleUser) {
    print("Fields in user document:");
    Object.keys(sampleUser).forEach(function(key) {
        print(`  ${key}: ${typeof sampleUser[key]} = ${sampleUser[key]}`);
    });
}

// 4. Add hasChangedName field to users without it
print("\n4. Adding hasChangedName field to users without it...");
const updateResult = db.users.updateMany(
    { hasChangedName: { $exists: false } },
    { $set: { hasChangedName: false } }
);
print(`Updated ${updateResult.modifiedCount} users with hasChangedName: false`);

// 5. Verify after update
print("\n5. After update - All users hasChangedName status:");
db.users.find({}, { _id: 1, displayName: 1, hasChangedName: 1 }).forEach(function(user) {
    print(`${user._id}: ${user.displayName} -> hasChangedName: ${user.hasChangedName}`);
});

print("\n=== Debug Complete ===");