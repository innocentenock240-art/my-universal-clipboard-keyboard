package com.example.core.content

import com.example.data.model.ClipboardItem
import java.util.regex.Pattern

/**
 * Universal Smart Content Classifier & Sensitive Data Detector.
 * Analyzes clipboard content non-intrusively to provide category metadata and privacy masking.
 */
object SmartContentClassifier {

    enum class ContentCategory(val displayName: String, val iconName: String) {
        TEXT("Text", "text_fields"),
        URL("URL", "link"),
        EMAIL("Email", "email"),
        PHONE("Phone", "phone"),
        CODE("Code", "code"),
        JSON("JSON", "data_object"),
        XML("XML", "code_blocks"),
        HTML("HTML", "html"),
        MARKDOWN("Markdown", "article"),
        IMAGE("Image", "image"),
        FILE("File", "folder"),
        SENSITIVE("Sensitive", "lock")
    }

    private val URL_PATTERN = Pattern.compile(
        "^((https?|ftp)://|(www|ftp)\\.)[a-z0-9-]+(\\.[a-z0-9-]+)+([/?].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    private val EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$"
    )

    private val PHONE_PATTERN = Pattern.compile(
        "^(\\+?[0-9]{1,3}[-.\\s]?)?(\\(?[0-9]{3}\\)?[-.\\s]?)?[0-9]{3}[-.\\s]?[0-9]{4}$"
    )

    private val API_KEY_PATTERNS = listOf(
        Pattern.compile("(?i)(api[_-]?key|secret|token|password|bearer|auth[_-]?token)[\\s:=]+['\"]?([a-zA-Z0-9_\\-]{16,})['\"]?"),
        Pattern.compile("^gh[pousr]_[a-zA-Z0-9]{20,}$"), // GitHub Tokens (ghp_, gho_, ghu_, ghs_, ghr_)
        Pattern.compile("^github_pat_[a-zA-Z0-9_]{20,}$"), // GitHub Fine-grained Personal Access Token
        Pattern.compile("^AIza[0-9A-Za-z\\-_]{35}$"), // Google API Key
        Pattern.compile("^sk-[a-zA-Z0-9]{20,}$"), // OpenAI Key
        Pattern.compile("^ey[a-zA-Z0-9_-]{10,}\\.ey[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}$") // JWT Token
    )

    /**
     * Determines if content contains sensitive data (e.g. passwords, tokens, API keys).
     */
    fun isSensitive(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.length < 8) return false

        // Check against known API key and token regex patterns
        for (pattern in API_KEY_PATTERNS) {
            if (pattern.matcher(trimmed).find()) {
                return true
            }
        }

        // Generic detection: high entropy, no spaces, starts with key identifier
        val lower = trimmed.lowercase()
        if ((lower.contains("password=") || lower.contains("passwd=") || lower.contains("bearer ")) && trimmed.length > 12) {
            return true
        }

        return false
    }

    /**
     * Classifies content into a domain [ContentCategory].
     */
    fun classify(item: ClipboardItem): ContentCategory {
        if (item.type == ClipboardItem.TYPE_IMAGE) return ContentCategory.IMAGE
        if (item.type == ClipboardItem.TYPE_FILE) return ContentCategory.FILE
        if (isSensitive(item.content)) return ContentCategory.SENSITIVE

        val text = item.content.trim()
        if (URL_PATTERN.matcher(text).matches() || text.startsWith("http://") || text.startsWith("https://")) {
            return ContentCategory.URL
        }
        if (EMAIL_PATTERN.matcher(text).matches()) {
            return ContentCategory.EMAIL
        }
        if (PHONE_PATTERN.matcher(text).matches()) {
            return ContentCategory.PHONE
        }
        if (isJson(text)) {
            return ContentCategory.JSON
        }
        if (isXmlOrHtml(text)) {
            return if (text.contains("<html", ignoreCase = true) || text.contains("<!doctype html", ignoreCase = true)) {
                ContentCategory.HTML
            } else {
                ContentCategory.XML
            }
        }
        if (isCode(text)) {
            return ContentCategory.CODE
        }
        if (isMarkdown(text)) {
            return ContentCategory.MARKDOWN
        }

        return ContentCategory.TEXT
    }

    private fun isJson(text: String): Boolean {
        val trimmed = text.trim()
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return try {
                if (trimmed.startsWith("{")) {
                    org.json.JSONObject(trimmed)
                } else {
                    org.json.JSONArray(trimmed)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    private fun isXmlOrHtml(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.contains("</")) ||
                trimmed.startsWith("<?xml", ignoreCase = true)
    }

    private fun isMarkdown(text: String): Boolean {
        val lines = text.lines()
        val hasHeaders = lines.any { it.startsWith("# ") || it.startsWith("## ") || it.startsWith("### ") }
        val hasLists = lines.any { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("1. ") }
        val hasLinks = text.contains("[") && text.contains("](") && text.contains(")")
        return (hasHeaders || hasLists) && hasLinks
    }

    private fun isCode(text: String): Boolean {
        val keywords = listOf(
            "fun ", "val ", "var ", "class ", "interface ", "function ", "const ", "let ",
            "def ", "import ", "public static void", "System.out.print", "console.log",
            "<?php", "#!/bin/", "SELECT ", "CREATE TABLE", "WHERE ", "FROM ", "struct ",
            "impl ", "fn ", "fn main", "export default", "async ", "await "
        )
        return keywords.any { text.contains(it) } || (text.contains("{") && text.contains("}") && text.lines().size >= 3)
    }

    /**
     * Masks sensitive content for UI preview (e.g. "••••••••••••").
     */
    fun maskSensitiveText(content: String): String {
        return "•".repeat(content.length.coerceIn(8, 24))
    }
}
