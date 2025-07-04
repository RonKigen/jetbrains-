package org.jetbrains.plugins.template.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class GeminiRequest(
    val contents: List<Content>
)

@Serializable
data class Content(
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String
)

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate>
)

@Serializable
data class Candidate(
    val content: Content,
    val finishReason: String? = null
)

class AICompletionService {
    
    private val logger = Logger.getInstance(AICompletionService::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val apiKey = System.getenv("GEMINI_API_KEY") ?: "GEMINI_API_KEY"
    private val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent"
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    suspend fun getCompletions(context: String): List<String> {
        return try {
            getGeminiCompletions(context)
        } catch (e: Exception) {
            logger.warn("Failed to get Gemini completions", e)
            getFallbackCompletions(context)
        }
    }
    
    private suspend fun getGeminiCompletions(context: String): List<String> = withContext(Dispatchers.IO) {
        val prompt = buildPrompt(context)
        
        val requestBody = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(Part(prompt))
                )
            )
        )
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiUrl?key=$apiKey"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(requestBody)))
            .timeout(Duration.ofSeconds(30))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() != 200) {
            logger.warn("Gemini API returned status ${response.statusCode()}: ${response.body()}")
            return@withContext getFallbackCompletions(context)
        }
        
        parseGeminiResponse(response.body())
    }
    
    private fun buildPrompt(context: String): String {
        return """
You are a code completion assistant. Given the following code context, provide 3 relevant code completions.
The completions should be syntactically correct and contextually appropriate.

Context:
$context

Rules:
1. Provide exactly 3 completions
2. Each completion should be on a separate line
3. Focus on the most likely next code that would be written
4. Consider the programming language and context
5. Keep completions concise and practical
6. Do not include explanations, only the code suggestions

Format your response as:
COMPLETION1
COMPLETION2
COMPLETION3
""".trimIndent()
    }
    
    private fun parseGeminiResponse(responseBody: String): List<String> {
        return try {
            val response = json.decodeFromString<GeminiResponse>(responseBody)
            val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            
            if (text.isNullOrBlank()) {
                return getFallbackCompletions("")
            }
            
            // Parse the response to extract individual completions
            val completions = text.lines()
                .filter { it.isNotBlank() }
                .filter { !it.startsWith("COMPLETION") } // Remove our formatting markers
                .take(3)
            
            if (completions.isEmpty()) {
                return getFallbackCompletions("")
            }
            
            completions
        } catch (e: Exception) {
            logger.warn("Failed to parse Gemini response", e)
            getFallbackCompletions("")
        }
    }
    
    private fun getFallbackCompletions(context: String): List<String> {
        val lowerContext = context.lowercase()
        
        return when {
            lowerContext.contains("function") || lowerContext.contains("fun ") || lowerContext.contains("def ") -> {
                listOf(
                    "    return result",
                    "    console.log(\"Debug:\", value)",
                    "    // TODO: Implement functionality"
                )
            }
            lowerContext.contains("class ") -> {
                listOf(
                    "    constructor() {",
                    "    private val property = \"\"",
                    "    public fun method() {"
                )
            }
            lowerContext.contains("if ") || lowerContext.contains("if(") -> {
                listOf(
                    "} else {",
                    "    return false",
                    "    throw new Error(\"Invalid condition\")"
                )
            }
            lowerContext.contains("for ") || lowerContext.contains("while ") -> {
                listOf(
                    "    console.log(item)",
                    "    break",
                    "    continue"
                )
            }
            lowerContext.contains("import ") || lowerContext.contains("from ") -> {
                listOf(
                    "import { Component } from 'react'",
                    "import * as utils from './utils'",
                    "from typing import List, Dict"
                )
            }
            lowerContext.contains("const ") || lowerContext.contains("let ") || lowerContext.contains("var ") -> {
                listOf(
                    " = useState()",
                    " = []",
                    " = null"
                )
            }
            lowerContext.contains("console.") -> {
                listOf(
                    "log(\"Value:\", variable)",
                    "error(\"Error occurred:\", error)",
                    "warn(\"Warning:\", message)"
                )
            }
            lowerContext.contains("print") -> {
                listOf(
                    "println(\"Debug: \$value\")",
                    "print(f\"Value: {value}\")",
                    "printf(\"Result: %s\", result)"
                )
            }
            lowerContext.contains("try ") || lowerContext.contains("catch") -> {
                listOf(
                    "} catch (error) {",
                    "    console.error(error)",
                    "} finally {"
                )
            }
            lowerContext.contains("array") || lowerContext.contains("list") -> {
                listOf(
                    ".map(item => item.id)",
                    ".filter(item => item.active)",
                    ".forEach(item => console.log(item))"
                )
            }
            else -> {
                listOf(
                    "// Auto-generated suggestion",
                    "const result = ",
                    "return "
                )
            }
        }.take(3)
    }
}