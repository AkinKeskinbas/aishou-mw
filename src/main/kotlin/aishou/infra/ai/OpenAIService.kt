package aishou.infra.ai

import aishou.domain.model.*
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import kotlinx.serialization.json.Json
import com.aallam.openai.api.BetaOpenAI

@OptIn(BetaOpenAI::class)
class OpenAIService(
    private val client: OpenAI,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {

    suspend fun analyzeMBTIOnly(
        answers: Map<String, String>,
        questions: List<TestQuestion>,
        language: String = "en"
    ): Pair<String, String> {
        val prompt = buildMBTIOnlyPrompt(answers, questions, language)

        val systemMessage = when (language) {
            "jp", "ja" -> """あなたはMBTI専門の性格分析者です。
                与えられたクイズの回答からMBTIタイプと星座を特定してください。詳細な分析は不要です。
                重要: 回答は必ず日本語で行ってください。英語は一切使用しないでください。"""
            else -> """You are an MBTI expert. Identify only the MBTI type and zodiac sign from the quiz responses.
                No detailed analysis needed.
                IMPORTANT: You must respond only in English. Do not use any other language."""
        }

        val request = ChatCompletionRequest(
            model = ModelId("gpt-4"),
            messages = listOf(
                ChatMessage(role = ChatRole.System, content = systemMessage),
                ChatMessage(role = ChatRole.User, content = prompt)
            ),
            temperature = 0.3
        )

        val completion = client.chatCompletion(request)
        val responseContent = completion.choices.first().message?.content ?: ""

        return parseMBTIOnly(responseContent)
    }

    suspend fun analyzePersonality(
        answers: Map<String, String>,
        questions: List<TestQuestion>,
        language: String = "en"
    ): PersonalityAnalysis {
        val prompt = buildPersonalityPrompt(answers, questions, language)

        val systemMessage = when (language) {
            "jp", "ja" -> """あなたはMBTIと占星術の分析を専門とする専門的な性格分析者です。
                与えられたクイズの回答を分析し、JSON形式で詳細な性格の洞察を提供してください。
                重要: 回答は必ず日本語で行ってください。英語は一切使用しないでください。"""
            else -> """You are an expert personality analyst specializing in MBTI and Zodiac analysis.
                Analyze the given quiz responses and provide detailed personality insights in JSON format.
                IMPORTANT: You must respond only in English. Do not use any other language."""
        }

        val request = ChatCompletionRequest(
            model = ModelId("gpt-4"),
            messages = listOf(
                ChatMessage(role = ChatRole.System, content = systemMessage),
                ChatMessage(role = ChatRole.User, content = prompt)
            ),
            temperature = 0.7
        )

        val completion = client.chatCompletion(request)
        val responseContent = completion.choices.first().message?.content ?: ""

        return parsePersonalityAnalysis(responseContent)
    }

    suspend fun generatePersonalizedInsights(
        answers: Map<String, String>,
        questions: List<TestQuestion>,
        mbtiType: String,
        zodiacSign: String,
        language: String = "en",
        personalityScores: Map<String, Int> = emptyMap()
    ): String {
        val questionAnswerPairs = questions.sortedBy { it.index }.mapNotNull { question ->
            val answer = answers[question.index.toString()]
            if (answer != null) {
                val choiceText = question.choices.find { it.key == answer }?.text ?: answer
                "${question.text} -> $choiceText"
            } else null
        }

        val scoresText = if (personalityScores.isNotEmpty()) {
            when (language) {
                "jp", "ja" -> "\n性格スコア: ${personalityScores.map { "${it.key}: ${it.value}" }.joinToString(", ")}"
                else -> "\nPersonality Scores: ${personalityScores.map { "${it.key}: ${it.value}" }.joinToString(", ")}"
            }
        } else ""

        val prompt = when (language) {
            "jp", "ja" -> """
            この${mbtiType}で${zodiacSign}の人のクイズ回答に基づいて、個人化された洞察を提供してください：

            ${questionAnswerPairs.joinToString("\n")}$scoresText

            この人の${mbtiType}の特性と${zodiacSign}の特徴を考慮して、以下について詳細な分析を提供してください：
            1. 彼らの回答がMBTIタイプとどのように一致するか
            2. 星座の特性がどのように現れているか
            3. 性格スコアが示す特定の傾向
            4. 個人的な成長のためのアドバイス
            5. 関係性における強みと課題

            2-3段落の詳細な洞察を日本語で提供してください。
            """.trimIndent()

            else -> """
            Based on this ${mbtiType} ${zodiacSign} person's quiz responses, provide personalized insights:

            ${questionAnswerPairs.joinToString("\n")}$scoresText

            Considering their ${mbtiType} traits and ${zodiacSign} characteristics, provide detailed analysis on:
            1. How their answers align with their MBTI type
            2. How their zodiac traits manifest
            3. Specific tendencies shown by their personality scores
            4. Personal growth advice
            5. Strengths and challenges in relationships

            Provide 2-3 paragraphs of detailed insights in English.
            """.trimIndent()
        }

        val systemMessage = when (language) {
            "jp", "ja" -> """あなたは性格分析の専門家です。ユーザーの既知のMBTIタイプと星座に基づいて個人化された洞察を提供してください。
                重要: 回答は必ず日本語で行ってください。英語は一切使用しないでください。"""
            else -> """You are a personality analysis expert. Provide personalized insights based on the user's known MBTI type and zodiac sign.
                IMPORTANT: You must respond only in English. Do not use any other language."""
        }

        val request = ChatCompletionRequest(
            model = ModelId("gpt-4"),
            messages = listOf(
                ChatMessage(role = ChatRole.System, content = systemMessage),
                ChatMessage(role = ChatRole.User, content = prompt)
            ),
            temperature = 0.7
        )

        val completion = client.chatCompletion(request)
        return completion.choices.first().message?.content ?: ""
    }

    suspend fun analyzeCompatibility(
        answersA: Map<String, String>,
        answersB: Map<String, String>,
        analysisA: PersonalityAnalysis,
        analysisB: PersonalityAnalysis,
        questions: List<TestQuestion>,
        language: String = "en"
    ): Triple<Int, MatchingAnalysis, String> {
        val prompt = buildCompatibilityPrompt(answersA, answersB, analysisA, analysisB, questions, language)

        val systemMessage = when (language) {
            "jp", "ja" -> """あなたは恋愛相性分析の専門家です。
                MBTIタイプ、星座、クイズの回答に基づいて2人の相性を分析してください。
                相性スコア（0-100）、魅力的なchemistry表現、詳細な分析をJSON形式で提供してください。
                Chemistry表現は短くてキャッチーで、この2人の関係を象徴する表現を選んでください。
                重要: 回答は必ず日本語で行ってください。英語は一切使用しないでください。"""
            else -> """You are an expert relationship compatibility analyst.
                Analyze the compatibility between two people based on their MBTI types, Zodiac signs, and quiz responses.
                Provide a compatibility score (0-100), catchy chemistry expression, and detailed analysis in JSON format.
                The chemistry expression should be short, catchy, and symbolic of their relationship dynamic.
                IMPORTANT: You must respond only in English. Do not use any other language."""
        }

        val request = ChatCompletionRequest(
            model = ModelId("gpt-4"),
            messages = listOf(
                ChatMessage(role = ChatRole.System, content = systemMessage),
                ChatMessage(role = ChatRole.User, content = prompt)
            ),
            temperature = 0.7
        )

        val completion = client.chatCompletion(request)
        val responseContent = completion.choices.first().message?.content ?: ""

        return parseCompatibilityAnalysisWithChemistry(responseContent)
    }

    private fun buildPersonalityPrompt(
        answers: Map<String, String>,
        questions: List<TestQuestion>,
        language: String = "en"
    ): String {
        val questionAnswerPairs = questions.sortedBy { it.index }.mapNotNull { question ->
            val answer = answers[question.index.toString()]
            if (answer != null) {
                val choiceText = question.choices.find { it.key == answer }?.text ?: answer
                "${question.text} -> $choiceText"
            } else null
        }

        return when (language) {
            "jp", "ja" -> """
        以下のクイズ回答に基づいて、包括的な性格分析を提供してください：

        ${questionAnswerPairs.joinToString("\n")}

        以下の正確な構造でJSON応答を分析して返してください：
        {
          "mbti": {
            "type": "ENFP",
            "description": "運動家 - 情熱的で創造的で社交的な自由な精神",
            "strengths": ["情熱的", "創造的", "社交的", "洞察力がある"],
            "weaknesses": ["集中力を失いがち", "考えすぎ", "ストレスを感じやすい"],
            "summary": "このMBTIタイプの詳細な要約とその人への適用方法"
          },
          "zodiac": {
            "sign": "獅子座",
            "element": "火",
            "description": "自信に満ち、野心的で、寛大で、忠実で、励ましになる",
            "traits": ["リーダーシップ", "創造性", "寛大さ", "忠誠心"],
            "summary": "この星座と性格特性の詳細な要約"
          }
        }

        必ず以下を実行してください：
        1. 回答に基づいて最も可能性の高いMBTIタイプを決定する
        2. 表示された性格に合致する星座の特徴を推測する
        3. 具体的で個人化された洞察を提供する
        4. 有効なJSONのみを返す
        """.trimIndent()

            else -> """
        Based on the following quiz responses, provide a comprehensive personality analysis:

        ${questionAnswerPairs.joinToString("\n")}

        Please analyze and return a JSON response with this exact structure:
        {
          "mbti": {
            "type": "ENFP",
            "description": "The Campaigner - Enthusiastic, creative and sociable free spirits",
            "strengths": ["Enthusiastic", "Creative", "Sociable", "Perceptive"],
            "weaknesses": ["Can lose focus", "Overthinking", "Gets stressed easily"],
            "summary": "A detailed summary of this MBTI type and how it applies to the person"
          },
          "zodiac": {
            "sign": "Leo",
            "element": "Fire",
            "description": "Confident, ambitious, generous, loyal, encouraging",
            "traits": ["Leadership", "Creativity", "Generosity", "Loyalty"],
            "summary": "A detailed summary of this zodiac sign and personality traits"
          }
        }

        Make sure to:
        1. Determine the most likely MBTI type based on responses
        2. Infer zodiac characteristics that align with the personality shown
        3. Provide specific, personalized insights
        4. Return only valid JSON
        """.trimIndent()
        }
    }

    private fun buildCompatibilityPrompt(
        answersA: Map<String, String>,
        answersB: Map<String, String>,
        analysisA: PersonalityAnalysis,
        analysisB: PersonalityAnalysis,
        questions: List<TestQuestion>,
        language: String = "en"
    ): String {
        // Build comparison of their actual quiz answers
        val answerComparisons = questions.sortedBy { it.index }.mapNotNull { question ->
            val answerA = answersA[question.index.toString()]
            val answerB = answersB[question.index.toString()]
            if (answerA != null && answerB != null) {
                val choiceA = question.choices.find { it.key == answerA }?.text ?: answerA
                val choiceB = question.choices.find { it.key == answerB }?.text ?: answerB
                "${question.text}\n  Person A: $choiceA\n  Person B: $choiceB"
            } else null
        }

        return when (language) {
            "jp", "ja" -> """
        以下の2人のプロフィールと実際のクイズ回答に基づいて相性を分析してください：

        個人A:
        - MBTI: ${analysisA.mbti.type}
        - 星座: ${analysisA.zodiac.sign} (${analysisA.zodiac.element})

        個人B:
        - MBTI: ${analysisB.mbti.type}
        - 星座: ${analysisB.zodiac.sign} (${analysisB.zodiac.element})

        実際のクイズ回答の比較:
        ${answerComparisons.joinToString("\n\n")}

        以下のJSON形式で相性分析を提供してください：
        {
          "compatibilityScore": 85,
          "chemistry": "ソウルメイト",
          "matchingAnalysis": {
            "mbtiMatch": {
              "typeA": "${analysisA.mbti.type}",
              "typeB": "${analysisB.mbti.type}",
              "compatibilityScore": 80,
              "strengths": ["自然なコミュニケーション", "共通の価値観"],
              "challenges": ["異なる意思決定スタイル"],
              "explanation": "MBTIの相性についての詳細な説明"
            },
            "zodiacMatch": {
              "signA": "${analysisA.zodiac.sign}",
              "signB": "${analysisB.zodiac.sign}",
              "compatibilityScore": 90,
              "elementInteraction": "火と風がエネルギーと興奮を生み出す",
              "strengths": ["相互理解", "補完的な特性"],
              "challenges": ["特定の分野での潜在的な対立"],
              "explanation": "星座の相性についての詳細な説明"
            },
            "overallCompatibility": "調和の取れた関係に向けた強いポテンシャルを持つ高い相性"
          }
        }

        考慮すべき点：
        1. MBTIタイプの相性（認知機能、コミュニケーションスタイル）
        2. 星座の相性（元素の相互作用、性格特性）
        3. クイズ回答の類似性と相違点
        4. 全体的な関係の力学
        5. 0-100のスコアを提供
        6. Chemistry: 相性スコアと分析に基づいて、この2人の関係を最もよく表現する短くて魅力的な表現を選択してください。
           - 高い相性 (80-100): "ソウルメイト", "運命の相手", "完璧な組み合わせ", "理想のパートナー"
           - 中程度の相性 (60-79): "相性抜群", "最高の相性", "素晴らしいマッチ", "理想的な関係"
           - 低い相性 (40-59): "良い相性", "成長の可能性", "バランスの良い関係"
           - とても低い相性 (<40): "チャレンジング", "学びの関係", "成長の機会"
        7. 有効なJSONのみを返す
        """.trimIndent()

            else -> """
        Analyze the compatibility between two people based on their profiles and actual quiz responses:

        Person A:
        - MBTI: ${analysisA.mbti.type}
        - Zodiac: ${analysisA.zodiac.sign} (${analysisA.zodiac.element})

        Person B:
        - MBTI: ${analysisB.mbti.type}
        - Zodiac: ${analysisB.zodiac.sign} (${analysisB.zodiac.element})

        Comparison of their actual quiz answers:
        ${answerComparisons.joinToString("\n\n")}

        Please provide a compatibility analysis in this JSON format:
        {
          "compatibilityScore": 85,
          "chemistry": "Soulmate",
          "matchingAnalysis": {
            "mbtiMatch": {
              "typeA": "${analysisA.mbti.type}",
              "typeB": "${analysisB.mbti.type}",
              "compatibilityScore": 80,
              "strengths": ["Communication flows naturally", "Shared values"],
              "challenges": ["Different decision-making styles"],
              "explanation": "Detailed explanation of MBTI compatibility"
            },
            "zodiacMatch": {
              "signA": "${analysisA.zodiac.sign}",
              "signB": "${analysisB.zodiac.sign}",
              "compatibilityScore": 90,
              "elementInteraction": "Fire and Air create energy and excitement",
              "strengths": ["Mutual understanding", "Complementary traits"],
              "challenges": ["Potential for conflicts in certain areas"],
              "explanation": "Detailed explanation of zodiac compatibility"
            },
            "overallCompatibility": "High compatibility with strong potential for a harmonious relationship"
          }
        }

        Consider:
        1. MBTI type compatibility (cognitive functions, communication styles)
        2. Zodiac sign compatibility (element interactions, personality traits)
        3. Similarities and differences in their quiz answers
        4. Overall relationship dynamics
        5. Provide a score from 0-100
        6. Chemistry: Based on the compatibility score and analysis, choose a short, catchy expression that best represents this relationship:
           - High compatibility (80-100): "Soulmate", "Destined", "Perfect Match", "Ideal Pair"
           - Medium compatibility (60-79): "Great Chemistry", "Twin Flame", "Amazing Match", "Ideal Relationship"
           - Low compatibility (40-59): "Good Chemistry", "Growth Potential", "Balanced Pair"
           - Very low compatibility (<40): "Challenging", "Learning Relationship", "Growth Opportunity"
        7. Return only valid JSON
        """.trimIndent()
        }
    }

    private fun parsePersonalityAnalysis(response: String): PersonalityAnalysis {
        return try {
            val cleanResponse = extractJsonFromResponse(response)
            json.decodeFromString<PersonalityAnalysis>(cleanResponse)
        } catch (e: Exception) {
            // Fallback analysis if parsing fails
            PersonalityAnalysis(
                mbti = MBTIAnalysis(
                    type = "ENFP",
                    description = "Analysis pending",
                    strengths = listOf("Creative", "Enthusiastic"),
                    weaknesses = listOf("Can lose focus"),
                    summary = "Personality analysis will be updated shortly"
                ),
                zodiac = ZodiacAnalysis(
                    sign = "Leo",
                    element = "Fire",
                    description = "Analysis pending",
                    traits = listOf("Confident", "Creative"),
                    summary = "Zodiac analysis will be updated shortly"
                )
            )
        }
    }

    private fun parseCompatibilityAnalysisWithChemistry(response: String): Triple<Int, MatchingAnalysis, String> {
        return try {
            val cleanResponse = extractJsonFromResponse(response)
            val result = json.decodeFromString<CompatibilityResponseWithChemistry>(cleanResponse)
            Triple(result.compatibilityScore, result.matchingAnalysis, result.chemistry)
        } catch (e: Exception) {
            // Fallback analysis if parsing fails
            Triple(
                75,
                MatchingAnalysis(
                    mbtiMatch = MBTIMatchAnalysis(
                        typeA = "Unknown",
                        typeB = "Unknown",
                        compatibilityScore = 75,
                        strengths = listOf("Good potential"),
                        challenges = listOf("Need more data"),
                        explanation = "Compatibility analysis will be updated shortly"
                    ),
                    zodiacMatch = ZodiacMatchAnalysis(
                        signA = "Unknown",
                        signB = "Unknown",
                        compatibilityScore = 75,
                        elementInteraction = "Balanced interaction",
                        strengths = listOf("Mutual respect"),
                        challenges = listOf("Communication needed"),
                        explanation = "Zodiac compatibility analysis will be updated shortly"
                    ),
                    overallCompatibility = "Good compatibility with potential for growth"
                ),
                "Great Chemistry"
            )
        }
    }

    private fun parseCompatibilityAnalysis(response: String): Pair<Int, MatchingAnalysis> {
        return try {
            val cleanResponse = extractJsonFromResponse(response)
            val result = json.decodeFromString<CompatibilityResponse>(cleanResponse)
            result.compatibilityScore to result.matchingAnalysis
        } catch (e: Exception) {
            // Fallback analysis if parsing fails
            75 to MatchingAnalysis(
                mbtiMatch = MBTIMatchAnalysis(
                    typeA = "Unknown",
                    typeB = "Unknown",
                    compatibilityScore = 75,
                    strengths = listOf("Good potential"),
                    challenges = listOf("Need more data"),
                    explanation = "Compatibility analysis will be updated shortly"
                ),
                zodiacMatch = ZodiacMatchAnalysis(
                    signA = "Unknown",
                    signB = "Unknown",
                    compatibilityScore = 75,
                    elementInteraction = "Balanced interaction",
                    strengths = listOf("Mutual respect"),
                    challenges = listOf("Communication needed"),
                    explanation = "Zodiac compatibility analysis will be updated shortly"
                ),
                overallCompatibility = "Good compatibility with potential for growth"
            )
        }
    }

    private fun buildMBTIOnlyPrompt(
        answers: Map<String, String>,
        questions: List<TestQuestion>,
        language: String = "en"
    ): String {
        val questionAnswerPairs = questions.sortedBy { it.index }.mapNotNull { question ->
            val answer = answers[question.index.toString()]
            if (answer != null) {
                val choiceText = question.choices.find { it.key == answer }?.text ?: answer
                "${question.text} -> $choiceText"
            } else null
        }

        return when (language) {
            "jp", "ja" -> """
        以下のクイズ回答に基づいて、MBTIタイプと星座のみを特定してください：

        ${questionAnswerPairs.joinToString("\n")}

        以下の形式で回答してください：
        MBTI: [16タイプのうち1つ]
        星座: [12星座のうち1つ]

        例：
        MBTI: ENFP
        星座: 獅子座
        """.trimIndent()

            else -> """
        Based on the following quiz responses, identify only the MBTI type and zodiac sign:

        ${questionAnswerPairs.joinToString("\n")}

        Please respond in this format:
        MBTI: [one of 16 types]
        Zodiac: [one of 12 signs]

        Example:
        MBTI: ENFP
        Zodiac: Leo
        """.trimIndent()
        }
    }

    private fun parseMBTIOnly(response: String): Pair<String, String> {
        return try {
            val lines = response.lines()
            val mbtiLine = lines.find { it.startsWith("MBTI:") }
            val zodiacLine = lines.find { it.startsWith("Zodiac:") || it.startsWith("星座:") }

            val mbti = mbtiLine?.substringAfter(":")?.trim() ?: "ENFP"
            val zodiac = zodiacLine?.substringAfter(":")?.trim() ?: "Leo"

            mbti to zodiac
        } catch (e: Exception) {
            "ENFP" to "Leo"
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        // Remove markdown code blocks and extra text
        return response
            .substringAfter("```json")
            .substringAfter("{")
            .substringBeforeLast("}")
            .let { "{$it}" }
            .replace("```", "")
            .trim()
    }

    @kotlinx.serialization.Serializable
    private data class CompatibilityResponse(
        val compatibilityScore: Int,
        val matchingAnalysis: MatchingAnalysis
    )

    @kotlinx.serialization.Serializable
    private data class CompatibilityResponseWithChemistry(
        val compatibilityScore: Int,
        val chemistry: String,
        val matchingAnalysis: MatchingAnalysis
    )
}