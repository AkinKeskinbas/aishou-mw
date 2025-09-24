#!/bin/bash

# OneSignal API Debug Script
# This script helps you debug OneSignal REST API authorization issues

echo "🔍 OneSignal API Debugging Script"
echo "=================================="
echo

# Check if environment variables are set
echo "📋 Checking Environment Variables:"
echo "-----------------------------------"

if [ -z "$ONESIGNAL_APP_ID" ]; then
    echo "❌ ONESIGNAL_APP_ID is not set"
    MISSING_VARS=true
else
    echo "✅ ONESIGNAL_APP_ID: ${ONESIGNAL_APP_ID:0:10}..."
fi

if [ -z "$ONESIGNAL_REST_KEY" ]; then
    echo "❌ ONESIGNAL_REST_KEY is not set"
    MISSING_VARS=true
else
    echo "✅ ONESIGNAL_REST_KEY: ${ONESIGNAL_REST_KEY:0:20}..."

    # Determine key type
    if [[ $ONESIGNAL_REST_KEY == os_v2_app_* ]]; then
        KEY_TYPE="Rich API Key (New)"
        AUTH_HEADER="Key $ONESIGNAL_REST_KEY"
        echo "   🔑 Key Type: $KEY_TYPE"
    else
        KEY_TYPE="Legacy API Key"
        AUTH_HEADER="Basic $ONESIGNAL_REST_KEY"
        echo "   🔑 Key Type: $KEY_TYPE"
    fi
fi

if [ -z "$PUBLIC_BASE_URL" ]; then
    echo "⚠️  PUBLIC_BASE_URL is not set (optional)"
else
    echo "✅ PUBLIC_BASE_URL: $PUBLIC_BASE_URL"
fi

echo

if [ "$MISSING_VARS" = true ]; then
    echo "❌ Missing required environment variables. Please set them and try again."
    echo
    echo "Example .env file:"
    echo "ONESIGNAL_APP_ID=your-app-id-here"
    echo "ONESIGNAL_REST_KEY=your-rest-api-key-here"
    echo "PUBLIC_BASE_URL=https://your-domain.com"
    exit 1
fi

# Test OneSignal App API endpoint
echo "🔐 Testing OneSignal Authentication:"
echo "------------------------------------"
echo "Testing endpoint: https://api.onesignal.com/apps/$ONESIGNAL_APP_ID"
echo "Authorization header: ${AUTH_HEADER:0:30}..."
echo

# Make the API call
response=$(curl -s -w "\nHTTP_STATUS:%{http_code}" \
    -H "Authorization: $AUTH_HEADER" \
    -H "Content-Type: application/json; charset=utf-8" \
    "https://api.onesignal.com/apps/$ONESIGNAL_APP_ID")

# Parse response
http_status=$(echo "$response" | grep "HTTP_STATUS:" | cut -d: -f2)
response_body=$(echo "$response" | sed '/HTTP_STATUS:/d')

echo "📡 API Response:"
echo "Status Code: $http_status"
echo "Response Body: $response_body"
echo

# Analyze result
if [ "$http_status" = "200" ]; then
    echo "✅ SUCCESS: OneSignal authentication is working correctly!"
    echo "   Your API key and App ID are valid."
elif [ "$http_status" = "401" ]; then
    echo "❌ AUTHENTICATION FAILED: Invalid API key or App ID"
    echo "   Common solutions:"
    echo "   1. Verify you're using the REST API key (not User Auth key)"
    echo "   2. Check that your App ID is correct"
    echo "   3. For new Rich API keys, make sure the format is: Key \$ONESIGNAL_REST_KEY"
    echo "   4. For legacy keys, make sure the format is: Basic \$ONESIGNAL_REST_KEY"
elif [ "$http_status" = "404" ]; then
    echo "❌ NOT FOUND: App ID '$ONESIGNAL_APP_ID' does not exist"
    echo "   Check your App ID in the OneSignal dashboard"
else
    echo "❌ ERROR: Unexpected response (HTTP $http_status)"
    echo "   This might be a network issue or OneSignal service problem"
fi

echo
echo "🛠️  Troubleshooting Steps:"
echo "-------------------------"
echo "1. Log into your OneSignal dashboard"
echo "2. Go to Settings → Keys & IDs"
echo "3. Copy the correct App ID and REST API Key"
echo "4. If using legacy keys, they should NOT start with 'os_v2_app_'"
echo "5. If using Rich API keys (recommended), they SHOULD start with 'os_v2_app_'"
echo "6. Test the /v1/push/debug-auth endpoint in your API after setting correct keys"
echo
echo "📚 OneSignal API Documentation:"
echo "https://documentation.onesignal.com/docs/keys-and-ids"
echo "https://documentation.onesignal.com/reference/create-notification"