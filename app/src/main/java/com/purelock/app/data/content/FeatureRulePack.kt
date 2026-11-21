package com.purelock.app.data.content

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * In-app feature blocking, driven by a JSON rule pack in assets ([ASSET]). Each rule names one
 * distracting part of one app (for example Instagram Reels) and how to recognise it on screen:
 * view IDs, visible text or content descriptions. When the app updates and a rule stops matching,
 * only the JSON has to change — no code release.
 *
 * Matching is best-effort by design: apps can rename their views or hide labels, so a rule can
 * miss. When the pack itself cannot be loaded, [loadError] is set and the Home screen warns
 * (see [com.purelock.app.security.IntegrityChecker]).
 *
 * The JSON parser here is deliberately hand-written (no org.json): it keeps [parse] pure Kotlin
 * so it is covered by plain JVM unit tests.
 */
data class FeatureRule(
    val id: String,
    val packageName: String,
    val feature: String,
    val viewIds: List<String>,
    val texts: List<String>,
    val descriptions: List<String>,
    /** "BACK_THEN_HOME" (default) or "HOME". */
    val action: String = ACTION_BACK_THEN_HOME
) {
    companion object {
        const val ACTION_BACK_THEN_HOME = "BACK_THEN_HOME"
        const val ACTION_HOME = "HOME"
    }
}

/** One visible node's matchable identity, collected from the accessibility tree. */
data class NodeSig(
    val viewId: String?,
    val text: String?,
    val description: String?
)

object FeatureRulePack {

    const val ASSET = "feature_rules.json"

    private const val PREFS = "purelock_feature_rules"
    private const val KEY_LOAD_ERROR = "load_error"

    @Volatile private var cached: List<FeatureRule>? = null

    /** Loads (and caches) the rules from assets. Returns empty when the pack cannot be read. */
    fun load(context: Context): List<FeatureRule> {
        cached?.let { return it }
        val rules = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }.let(::parse)
        }.getOrElse {
            setLoadError(context, true)
            emptyList()
        }
        if (rules.isNotEmpty()) setLoadError(context, false)
        cached = rules
        return rules
    }

    /** True when the last load attempt failed (pack missing or unparsable). */
    fun loadError(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOAD_ERROR, false)

    private fun setLoadError(context: Context, error: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOAD_ERROR, error).apply()
    }

    /** Parses the rule-pack JSON. Pure Kotlin; throws [IllegalArgumentException] when malformed. */
    fun parse(json: String): List<FeatureRule> {
        val root = JsonParser(json).parseValue()
        if (root !is Map<*, *>) throw IllegalArgumentException("Rule pack root must be an object")
        val rules = root["rules"] as? List<*> ?: throw IllegalArgumentException("Rule pack has no rules array")
        return rules.map { item ->
            val obj = item as? Map<*, *> ?: throw IllegalArgumentException("Rule must be an object")
            FeatureRule(
                id = obj.string("id"),
                packageName = obj.string("package"),
                feature = obj.string("feature"),
                viewIds = obj.stringList("viewIds"),
                texts = obj.stringList("texts"),
                descriptions = obj.stringList("descriptions"),
                action = (obj["action"] as? String)?.uppercase()
                    ?: FeatureRule.ACTION_BACK_THEN_HOME
            )
        }
    }

    private fun Map<*, *>.string(key: String): String =
        (this[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Rule is missing '$key'")

    private fun Map<*, *>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    /**
     * Minimal JSON reader: objects, arrays, strings (with escapes), numbers, true/false/null.
     * Only what the rule pack needs; numbers are returned as [Long]/[Double] and ignored by [parse].
     */
    class JsonParser(private val input: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWs()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = input[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expect("true", true)
                'f' -> expect("false", false)
                'n' -> expect("null", null)
                else -> parseNumber(c)
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < input.length && input[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= input.length || input[pos] != '"') {
                    throw IllegalArgumentException("Expected string key at $pos")
                }
                val key = parseString()
                skipWs()
                if (pos >= input.length || input[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' at $pos")
                }
                pos++
                map[key] = parseValue()
                skipWs()
                when {
                    pos < input.length && input[pos] == ',' -> pos++
                    pos < input.length && input[pos] == '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < input.length && input[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list += parseValue()
                skipWs()
                when {
                    pos < input.length && input[pos] == ',' -> pos++
                    pos < input.length && input[pos] == ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (pos < input.length) {
                when (val c = input[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= input.length) break
                        when (val e = input[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) throw IllegalArgumentException("Bad unicode escape")
                                out.append(input.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun expect(word: String, value: Any?): Any? {
            if (!input.startsWith(word, pos)) throw IllegalArgumentException("Expected $word at $pos")
            pos += word.length
            return value
        }

        private fun parseNumber(first: Char): Any {
            val start = pos
            if (first == '-') pos++
            while (pos < input.length && (input[pos].isDigit() || input[pos] in ".eE+-")) pos++
            val text = input.substring(start, pos)
            if (text.isEmpty() || text == "-") throw IllegalArgumentException("Bad number at $start")
            return text.toLongOrNull() ?: text.toDoubleOrNull()
                ?: throw IllegalArgumentException("Bad number '$text'")
        }

        private fun skipWs() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }
    }
}

/** Pure matching of [FeatureRule]s against collected node signatures. Covered by unit tests. */
object FeatureRuleMatcher {

    /**
     * First rule for [packageName] that matches any of [nodes], or null. A rule matches when any
     * of its view IDs, texts or descriptions matches any node: view IDs match the resource name
     * after "id/" (exact or contains), texts match whole words (case-insensitive), descriptions
     * match substrings (case-insensitive).
     */
    fun match(rules: List<FeatureRule>, packageName: String, nodes: List<NodeSig>): FeatureRule? {
        if (nodes.isEmpty()) return null
        for (rule in rules) {
            if (rule.packageName != packageName) continue
            if (matches(rule, nodes)) return rule
        }
        return null
    }

    fun matches(rule: FeatureRule, nodes: List<NodeSig>): Boolean {
        for (node in nodes) {
            val viewId = node.viewId?.substringAfterLast('/')?.lowercase()
            if (viewId != null && rule.viewIds.any { it.lowercase() in viewId }) {
                return true
            }
            val text = node.text
            if (!text.isNullOrBlank() && rule.texts.any { containsWord(text, it) }) return true
            val desc = node.description
            if (!desc.isNullOrBlank() && rule.descriptions.any { desc.contains(it, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }

    private fun containsWord(text: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * Collects matchable signatures from a node tree (depth-first, capped so a huge screen cannot
     * stall the accessibility service). Android-dependent; the pure [match] works on the result.
     */
    fun collectNodes(
        root: AccessibilityNodeInfo?,
        maxNodes: Int = 400
    ): List<NodeSig> {
        if (root == null) return emptyList()
        val out = ArrayList<NodeSig>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var nodes = 0
        while (stack.isNotEmpty() && nodes < maxNodes) {
            val node = stack.removeLast()
            nodes++
            val viewId = node.viewIdResourceName
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if (viewId != null || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
                out += NodeSig(viewId, text, desc)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }
}
