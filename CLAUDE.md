# Aishou - Personality & Compatibility Assessment API

## Project Overview
Aishou is a Kotlin-based web API built with Ktor framework that provides personality assessment and compatibility matching services. The application combines MBTI personality testing with zodiac analysis to create comprehensive personality profiles and calculate compatibility between users.

## Architecture

### Technology Stack
- **Framework**: Ktor (Kotlin web framework)
- **Database**: MongoDB with KMongo driver
- **Authentication**: JWT (JSON Web Tokens)
- **AI Integration**: OpenAI GPT-4 for personality analysis
- **Push Notifications**: OneSignal
- **Serialization**: kotlinx.serialization
- **HTTP Client**: Apache HTTP Client

### Project Structure
```
src/main/kotlin/
├── aishou/
│   ├── App.kt                     # Main application module configuration
│   ├── config/                    # Configuration modules
│   │   ├── DI.kt                 # Dependency injection setup
│   │   ├── HttpPlugins.kt        # HTTP plugins configuration
│   │   └── Jwt.kt                # JWT authentication setup
│   ├── domain/model/             # Data models
│   │   └── Models.kt             # All domain models and DTOs
│   ├── infra/                    # Infrastructure layer
│   │   ├── ai/OpenAIService.kt   # OpenAI integration
│   │   ├── mongo/Mongo.kt        # MongoDB connection
│   │   ├── push/OneSignalService.kt # Push notifications
│   │   └── security/Crypto.kt    # Security utilities
│   └── routes/                   # API route handlers
│       ├── AuthRoutes.kt         # Authentication endpoints
│       ├── CompatibilityRoutes.kt # Compatibility analysis
│       ├── InviteRoutes.kt       # User invitations
│       ├── PersonalityRoutes.kt  # Personality assessment
│       ├── PushRoutes.kt         # Push notification registration
│       ├── QuizListRoutes.kt     # Quiz history/results
│       ├── SubmissionRoutes.kt   # Quiz submissions
│       └── TestRoutes.kt         # Test/quiz management
├── Application.kt                # Legacy main application entry
├── Routing.kt                    # Legacy routing (basic hello world)
├── Serialization.kt              # JSON serialization config
├── Monitoring.kt                 # Logging and monitoring
└── Security.kt                   # Security configuration
```

## Core Features

### 1. User Authentication & Management
- **Registration**: Users register with RevenueCat ID for subscription management
- **JWT Authentication**: Access and refresh token system
- **Multi-platform Support**: iOS and Android platform tracking
- **Multi-language Support**: English and Japanese localization

### 2. Personality Assessment System
- **MBTI Analysis**: 16 personality type determination through quiz responses
- **Zodiac Integration**: Astrological sign analysis complementing MBTI
- **AI-Powered Insights**: OpenAI GPT-4 generates personalized analysis
- **Score Calculation**: Weighted scoring system for personality dimensions

### 3. Quiz & Test Management
- **Dynamic Test System**: Version-controlled test questions
- **Question Types**: Single and multiple choice questions
- **Weighted Scoring**: Each choice maps to personality dimension scores
- **Completion Tracking**: User progress and solved quiz history

### 4. Compatibility Analysis
- **Pair Matching**: Calculate compatibility between two users
- **Multi-factor Analysis**: Combines MBTI, zodiac, and quiz response similarities
- **Caching System**: Cached results with input hash validation
- **Detailed Explanations**: AI-generated compatibility breakdowns

### 5. Social Features
- **Invitation System**: Users can invite friends to take tests
- **Push Notifications**: OneSignal integration for invite notifications
- **Shared Results**: Compatibility analysis sharing between users

## API Routes Summary

### Authentication (`/v1/auth`)
- `POST /register` - User registration with RevenueCat ID
- `POST /refresh` - JWT token refresh

### Personality Assessment (`/v1/personality`) 🔒
- `POST /assess` - Initial personality assessment (determines MBTI/Zodiac)
- `GET /profile` - Get user's personality profile
- `PUT /update` - Manually update personality types

### Test Management (`/v1/tests`)
- `GET /` - List all available tests
- `GET /status` 🔒 - Get tests with completion status
- `GET /{testId}/versions/{version}/questions` - Get test questions

### Quiz Submissions (`/v1/submissions`) 🔒
- `PUT /{testId}/{version}` - Submit quiz answers and get insights

### Quiz History (`/v1/quiz`) 🔒
- `GET /solo` - Get user's solo quiz submissions
- `GET /matches` - Get user's compatibility matches

### Compatibility (`/v1/compatibility`) 🔒
- `POST /compute` - Calculate compatibility between users

### Invitations (`/v1/invites`) 🔒
- `POST /` - Create test invitation
- `GET /{inviteId}` - Get invite details
- `POST /{inviteId}/accept` - Accept invitation

### Push Notifications (`/v1/push`) 🔒
- `POST /register` - Register device for notifications

🔒 = Requires JWT authentication

## Data Models

### API Response Format

All API endpoints use a consistent response format with the `BaseResponse` wrapper:

```kotlin
@Serializable
data class BaseResponse(
    val status: String? = null,
    @Contextual val data: Any? = null
)
```

**Example Response:**
```json
{
  "status": "success",
  "data": {
    "token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9..."
  }
}
```

### Core Entities

#### User
```kotlin
data class User(
    val _id: String,              // RevenueCat ID
    val displayName: String?,
    val photoUrl: String?,
    val lang: String = "en",      // "en" or "jp"/"ja"
    val platform: String?,       // "ios" or "android"
    val isAnonymous: Boolean = true,
    val mbtiType: String?,        // e.g., "ENFP"
    val zodiacSign: String?,      // e.g., "Leo"
    val personalityAssessed: Boolean = false,
    val solvedQuizzes: List<SolvedQuiz> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
```

#### TestQuestion
```kotlin
data class TestQuestion(
    val testId: String,
    val version: Int,
    val index: Int,
    val text: String,
    val choices: List<Choice>,
    val weights: Map<String, Map<String, Int>>?, // choice -> dimension -> score
    val locale: String?
)
```

#### Submission
```kotlin
data class Submission(
    val _id: String?,
    val testId: String,
    val version: Int,
    val uid: String,
    val answers: Map<String, String>,      // questionIndex -> choiceKey
    val scoreVector: List<Int>?,           // [extrovert, introvert, thinking, feeling, ...]
    val totalScore: Int?,
    val personalizedInsights: String?,     // AI-generated insights
    val completedAt: Long = System.currentTimeMillis()
)
```

#### Compatibility
```kotlin
data class Compatibility(
    val _id: String,                       // testId_version_pairId
    val testId: String,
    val version: Int,
    val uidA: String,
    val uidB: String,
    val pairId: String,                    // sorted(uidA, uidB) hash
    val score: Int,                        // 0-100 compatibility score
    val summary: String,
    val explanations: List<Explanation>,
    val matchingAnalysis: MatchingAnalysis?,
    val inputHash: String,                 // for cache validation
    val createdAt: Long = System.currentTimeMillis(),
    val cacheTTL: Long = 1000L*60*60*24*30 // 30 days
)
```

### AI Analysis Models

#### PersonalityAnalysis
```kotlin
data class PersonalityAnalysis(
    val mbti: MBTIAnalysis,
    val zodiac: ZodiacAnalysis
)

data class MBTIAnalysis(
    val type: String,                      // e.g., "ENFP"
    val description: String,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val summary: String
)

data class ZodiacAnalysis(
    val sign: String,                      // e.g., "Leo"
    val element: String,                   // Fire, Earth, Air, Water
    val description: String,
    val traits: List<String>,
    val summary: String
)
```

#### MatchingAnalysis
```kotlin
data class MatchingAnalysis(
    val mbtiMatch: MBTIMatchAnalysis,
    val zodiacMatch: ZodiacMatchAnalysis,
    val overallCompatibility: String
)
```

## Configuration

### Environment Variables
```bash
JWT_SECRET=                    # HMAC256 secret for JWT signing
MONGO_URI=                     # MongoDB connection string
ONESIGNAL_APP_ID=             # OneSignal application ID
ONESIGNAL_REST_KEY=           # OneSignal REST API key
PUBLIC_BASE_URL=              # Base URL for the application
OPENAI_API_KEY=               # OpenAI API key for GPT-4
```

### Application Configuration
The app uses Ktor's HOCON configuration system with properties like:
- `aishou.mongo.uri` - MongoDB connection
- `aishou.mongo.db` - Database name
- `aishou.jwt.*` - JWT configuration (issuer, audience, secret, expiry)
- `aishou.onesignal.*` - OneSignal configuration
- `aishou.openai.apiKey` - OpenAI API key

## Business Logic Flow

### Personality Assessment Flow
1. User registers with RevenueCat ID
2. User takes initial personality assessment quiz
3. OpenAI analyzes responses to determine MBTI type and zodiac sign
4. Results stored in user profile
5. User can take additional tests for more insights
6. Personalized insights generated based on stored personality types

### Compatibility Analysis Flow
1. Both users must complete personality assessment
2. Both users submit answers to the same test
3. System calculates compatibility using:
   - MBTI type compatibility
   - Zodiac sign compatibility
   - Quiz response similarities
   - AI-generated analysis
4. Results cached with input hash for performance
5. Compatibility score (0-100) with detailed explanations returned

### Invitation & Social Flow
1. User creates invitation for specific test
2. Optional: Push notification sent to invited friend
3. Friend accepts invitation through invite link
4. Both complete the test
5. Compatibility analysis automatically available

## AI Integration Details

### OpenAI Service Functions
- **analyzePersonality()**: Determines MBTI type and zodiac sign from quiz responses
- **generatePersonalizedInsights()**: Creates tailored insights based on known personality types
- **analyzeCompatibility()**: Compares two users' personalities and generates compatibility analysis

### Prompt Engineering
- Structured JSON responses for consistent parsing
- Multi-language support (English/Japanese)
- Fallback analysis if AI parsing fails
- Temperature settings optimized for personality analysis

## Database Collections

### MongoDB Collections
- **users** - User profiles and personality types
- **tests** - Test metadata and configurations
- **testQuestions** - Question data with weights
- **submissions** - User quiz submissions and scores
- **compatibility** - Cached compatibility analyses
- **pairings** - User pair relationships
- **invites** - Test invitations
- **deviceRegistrations** - Push notification device tokens

## Security Features

### Authentication & Authorization
- JWT-based authentication with access/refresh token pattern
- User isolation - users can only access their own data
- Invite-based friend access for compatibility features

### Data Protection
- RevenueCat ID as primary identifier (no direct personal data storage)
- Optional anonymous usage support
- Secure JWT secret management
- Input validation and sanitization

## Development Commands

### Building & Running
```bash
./gradlew test              # Run tests
./gradlew build             # Build everything
./gradlew run               # Run the server locally
./gradlew buildFatJar       # Build executable JAR
./gradlew buildImage        # Build Docker image
```

### Server Information
- **Default Port**: 8080
- **Health Check**: GET / returns "Hello World!"
- **Content Type**: JSON (application/json)

## Key Implementation Notes

### Test Management System
- Tests have `isPremium` flag to control access to premium content
- Only premium subscribers can access tests marked with `isPremium: true`
- Test status endpoint includes premium flag for client-side access control

### Personality Scoring System
- Questions have weighted choices mapping to personality dimensions
- Score vector: [extrovert, introvert, thinking, feeling, judging, perceiving, sensing, intuition]
- Total score calculated as sum of all dimension scores
- AI analysis complements algorithmic scoring

### Caching Strategy
- Compatibility results cached with input hash validation
- Cache TTL of 30 days for compatibility analyses
- Prevents expensive AI re-computation for identical inputs

### Multi-language Support
- User language preference stored in profile
- AI prompts and responses in user's preferred language
- Currently supports English ("en") and Japanese ("jp"/"ja")

### Error Handling
- Graceful AI service failures with fallback responses
- Comprehensive input validation
- Proper HTTP status codes for different error scenarios

This documentation provides a complete understanding of the Aishou project structure, functionality, and implementation details for future development and maintenance.