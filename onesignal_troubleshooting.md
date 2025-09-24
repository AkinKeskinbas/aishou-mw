# OneSignal REST API Authorization Troubleshooting Guide

## Critical Issues Found in Your Code

### 1. **Inconsistent Authorization Headers** ✅ FIXED
Your code was using three different authorization formats:
- `sendInvite()`: Smart detection but incorrect "Key" format
- `sendInviteAccepted()`: Raw key only (incorrect)
- `sendCompatibilityReady()`: Raw key only (incorrect)

**Fixed with:** Consistent `getAuthorizationHeader()` helper function

### 2. **Incorrect Rich API Key Format** ✅ FIXED
You were using `restKey` directly for new keys instead of `Key $restKey`

## OneSignal API Key Types & Authorization Formats

### Rich API Keys (New - Recommended)
- **Format**: Starts with `os_v2_app_`
- **Authorization Header**: `Authorization: Key YOUR_RICH_API_KEY`
- **Introduced**: November 14, 2024
- **API Endpoint**: `https://api.onesignal.com`

### Legacy API Keys (Deprecated March 2025)
- **Format**: Various formats (not starting with `os_v2_app_`)
- **Authorization Header**: `Authorization: Basic YOUR_LEGACY_KEY`
- **API Endpoint**: `https://api.onesignal.com` (updated)

## Common Authorization Errors & Solutions

### Error: "Access denied. Please include an 'Authorization: ...' header with a valid API key"

**Root Causes:**
1. **Wrong Key Type**: Using User Auth Key instead of REST API Key
2. **Incorrect Format**: Missing "Basic" or "Key" prefix
3. **Invalid Key**: Key doesn't exist or is disabled
4. **Environment Variables**: Not properly loaded

**Solutions:**
1. Use REST API Key from app settings (not User Auth Key from account)
2. Apply correct authorization header format based on key type
3. Verify key exists and is enabled in OneSignal dashboard
4. Check environment variable loading

## Manual Testing Commands

### Test 1: Check App Information (Authentication Test)
```bash
# For Rich API Keys (os_v2_app_*)
curl -X GET "https://api.onesignal.com/apps/YOUR_APP_ID" \
  -H "Authorization: Key YOUR_RICH_API_KEY" \
  -H "Content-Type: application/json"

# For Legacy API Keys
curl -X GET "https://api.onesignal.com/apps/YOUR_APP_ID" \
  -H "Authorization: Basic YOUR_LEGACY_KEY" \
  -H "Content-Type: application/json"
```

### Test 2: Send Test Notification
```bash
# For Rich API Keys
curl -X POST "https://api.onesignal.com/notifications" \
  -H "Authorization: Key YOUR_RICH_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "app_id": "YOUR_APP_ID",
    "included_segments": ["All"],
    "contents": {"en": "Test notification"}
  }'

# For Legacy API Keys
curl -X POST "https://api.onesignal.com/notifications" \
  -H "Authorization: Basic YOUR_LEGACY_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "app_id": "YOUR_APP_ID",
    "included_segments": ["All"],
    "contents": {"en": "Test notification"}
  }'
```

## Environment Variable Debugging

### Check Environment Variables
```bash
# Check if variables are set
echo "App ID: $ONESIGNAL_APP_ID"
echo "REST Key (first 20 chars): ${ONESIGNAL_REST_KEY:0:20}..."

# Determine key type
if [[ $ONESIGNAL_REST_KEY == os_v2_app_* ]]; then
    echo "Key Type: Rich API Key"
    echo "Use: Authorization: Key \$ONESIGNAL_REST_KEY"
else
    echo "Key Type: Legacy API Key"
    echo "Use: Authorization: Basic \$ONESIGNAL_REST_KEY"
fi
```

### Load Environment Variables
```bash
# Option 1: Export in current session
export ONESIGNAL_APP_ID="your-app-id"
export ONESIGNAL_REST_KEY="your-rest-key"

# Option 2: Use .env file
echo "ONESIGNAL_APP_ID=your-app-id" > .env
echo "ONESIGNAL_REST_KEY=your-rest-key" >> .env
source .env

# Option 3: Set in IDE/deployment environment
```

## Implementation Testing

### 1. Test Authentication Endpoint
```bash
# After starting your server, test the debug endpoint
curl -X GET "http://localhost:3060/v1/push/debug-auth" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### 2. Run Debug Script
```bash
# Use the provided debug script
./debug_onesignal.sh
```

### 3. Check Server Logs
Look for debug output:
```
DEBUG: OneSignal App ID: your-app-id
DEBUG: OneSignal REST Key type: Rich API Key (or Legacy API Key)
DEBUG: Authorization header: 'Key os_v2_app_...' (or 'Basic legacy-key...')
```

## Step-by-Step Debugging Process

### Step 1: Verify OneSignal Dashboard Setup
1. Log into OneSignal dashboard
2. Navigate to Settings → Keys & IDs
3. Copy **App ID** and **REST API Key** (not User Auth Key)
4. Note if key starts with `os_v2_app_` (Rich) or not (Legacy)

### Step 2: Set Environment Variables
```bash
export ONESIGNAL_APP_ID="your-copied-app-id"
export ONESIGNAL_REST_KEY="your-copied-rest-key"
export PUBLIC_BASE_URL="https://your-domain.com"
```

### Step 3: Test with Debug Script
```bash
./debug_onesignal.sh
```

### Step 4: Test Your API
```bash
# Start your server
./gradlew run

# Test debug endpoint
curl -X GET "http://localhost:3060/v1/push/debug-auth" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Step 5: Test Notification Sending
Use your existing invite endpoints and check logs for:
- Authorization header format
- API response status
- Error messages

## Key Differences: User Auth Key vs REST API Key

| Aspect | User Auth Key | REST API Key |
|--------|---------------|--------------|
| **Location** | Account → User Auth Key | App Settings → Keys & IDs |
| **Purpose** | Account-level operations | App-specific notifications |
| **Usage** | User management | Send notifications |
| **Required For** | Creating apps, managing account | Sending push notifications |
| **Common Error** | Using for notifications (wrong!) | ✅ Correct for notifications |

## Alternative Approaches

### 1. OneSignal SDK (Server-side)
If REST API continues to have issues, consider OneSignal's official server SDKs:
- [OneSignal Node.js SDK](https://github.com/OneSignal/onesignal-node-api)
- Handles authentication automatically
- Better error handling and retry logic

### 2. Firebase Cloud Messaging (FCM)
As a backup option:
- Direct FCM integration
- More complex setup but reliable
- Better suited for enterprise applications

### 3. Alternative Push Services
- **Pusher Beams**
- **Amazon SNS**
- **Firebase Cloud Messaging**

## Recent OneSignal Changes (November 2024)

### Migration Requirements
1. **Create Rich API Key**: Generate new key in dashboard
2. **Update Authorization**: Use `Key` prefix instead of `Basic`
3. **Update Endpoint**: Use `https://api.onesignal.com` (already correct in your code)
4. **Deprecation Timeline**: Legacy keys deprecated March 2025

### Migration Benefits
- Better security and permission management
- Improved rate limiting
- Enhanced monitoring and analytics
- Future-proof API access

## Final Checklist

- ✅ Fixed authorization header inconsistencies
- ✅ Added debug authentication endpoint
- ✅ Created environment variable validation script
- ✅ Implemented proper Rich vs Legacy key detection
- ✅ Added comprehensive logging
- 📝 Test with debug script: `./debug_onesignal.sh`
- 📝 Test with debug endpoint: `GET /v1/push/debug-auth`
- 📝 Verify environment variables are properly loaded
- 📝 Check OneSignal dashboard for correct App ID and REST API Key