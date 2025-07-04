package com.github.ronkigen.jetbrains.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class AICompletionContributor : CompletionContributor() {
    
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            AICompletionProvider()
        )
    }
}

class AICompletionProvider : CompletionProvider<CompletionParameters>() {
    
    private val cache = ConcurrentHashMap<String, List<String>>()
    private val aiService = AICompletionService()
    private val logger = Logger.getInstance(AICompletionProvider::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val editor = parameters.editor
        val file = parameters.originalFile
        val element = parameters.position
        
        val codeContext = extractCodeContext(editor, file, element)
        val contextHash = codeContext.hashCode().toString()
        
        // Check cache first
        cache[contextHash]?.let { cachedSuggestions ->
            addSuggestionsToResult(cachedSuggestions, result)
            return
        }
        
        // Show loading indicator
        result.addElement(
            LookupElementBuilder.create("Loading AI suggestions...")
                .withIcon(AllIcons.Process.Step_1)
                .withTailText(" (AI)", true)
                .withTypeText("Loading...")
        )
        
        // Fetch suggestions asynchronously
        scope.launch {
            try {
                val suggestions = aiService.getCompletions(codeContext)
                cache[contextHash] = suggestions
                
                // Update UI on EDT
                ApplicationManager.getApplication().invokeLater {
                    // Create a new result set to replace the loading indicator
                    val newResult = result.withPrefixMatcher("")
                    addSuggestionsToResult(suggestions, newResult)
                }
            } catch (e: Exception) {
                logger.warn("Failed to get AI completions", e)
                val fallback = listOf("// AI completion unavailable")
                
                ApplicationManager.getApplication().invokeLater {
                    val newResult = result.withPrefixMatcher("")
                    addSuggestionsToResult(fallback, newResult)
                }
            }
        }
    }
    
    private fun extractCodeContext(editor: Editor, file: PsiFile, element: PsiElement): String {
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        
        // Get more context for better AI suggestions
        val startOffset = maxOf(0, caretOffset - 500)
        val endOffset = minOf(document.textLength, caretOffset + 100)
        
        val contextBefore = document.getText().substring(startOffset, caretOffset)
        val contextAfter = document.getText().substring(caretOffset, endOffset)
        
        val fileName = file.name
        val fileExtension = file.virtualFile?.extension ?: ""
        
        // Determine language context
        val language = when (fileExtension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js", "ts" -> "javascript/typescript"
            "py" -> "python"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "go" -> "go"
            "rs" -> "rust"
            "php" -> "php"
            "rb" -> "ruby"
            "swift" -> "swift"
            "cs" -> "csharp"
            else -> "unknown"
        }
        
        return buildString {
            appendLine("// File: $fileName")
            appendLine("// Language: $language")
            appendLine("// Context before cursor:")
            appendLine(contextBefore)
            appendLine("// <CURSOR_POSITION>")
            appendLine("// Context after cursor:")
            append(contextAfter)
        }
    }
    
    private fun addSuggestionsToResult(suggestions: List<String>, result: CompletionResultSet) {
        suggestions.forEachIndexed { index, suggestion ->
            val displayText = suggestion.lines().first().trim()
            val fullText = suggestion.trim()
            
            val lookupElement = LookupElementBuilder.create(fullText)
                .withIcon(AllIcons.Actions.IntentionBulb)
                .withPresentableText(displayText)
                .withTailText(" (Gemini AI)", true)
                .withTypeText("AI Completion")
                .withInsertHandler { context, _ ->
                    // Handle multi-line completions
                    val document = context.document
                    val startOffset = context.startOffset
                    val endOffset = context.tailOffset
                    
                    document.replaceString(startOffset, endOffset, fullText)
                    context.editor.caretModel.moveToOffset(startOffset + fullText.length)
                }
                .bold()
            
            result.addElement(lookupElement)
        }
    }
}