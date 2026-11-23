package dev.dexbuddy.client.llm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.client.LlmSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Failure kinds the companion turns into in-character lines plus a config hint. Messages never contain the key. */
class LlmException(val kind: Kind, message: String, val retryAfterMs: Long? = null) : Exception(message) {
    enum class Kind { NO_KEY, BAD_KEY, RATE_LIMIT, BAD_MODEL, NETWORK, SERVER, TOOL_FAILED }
}

data class ToolCall(val id: String, val name: String, val arguments: String)

/** One streamed completion: text already delivered through onText, plus any tool calls the model asked for. */
data class Completion(val text: String, val toolCalls: List<ToolCall>)

/**
 * Minimal OpenAI-compatible chat client: streaming SSE, tool calls, one shared keep-alive HTTP client.
 * Runs on worker threads only.
 */
object LlmClient {
    /** Shared with speech-to-text so both reuse one warm HTTP/2 connection to the provider. */
    val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_2)
        .build()

    @Volatile
    private var resolvedModel: Pair<String, String>? = null // baseUrl to model

    private fun base(s: LlmSettings) = s.baseUrl.trimEnd('/')

    private fun auth(b: HttpRequest.Builder, s: LlmSettings): HttpRequest.Builder {
        if (s.apiKey.isNotBlank()) b.header("Authorization", "Bearer ${s.apiKey.trim()}")
        return b
    }

    private fun requireKey(s: LlmSettings) {
        val local = base(s).contains("localhost") || base(s).contains("127.0.0.1")
        if (s.apiKey.isBlank() && !local && !Provider.viaServer(s.apiKey, base(s))) throw LlmException(LlmException.Kind.NO_KEY, "no API key set")
    }

    private fun get(s: LlmSettings, method: String, path: String, body: ByteArray?, timeout: Long): Provider.Response = try {
        Provider.request(base(s), s.apiKey, method, path, if (body != null) "application/json" else null, body, timeout)
    } catch (e: Exception) {
        throw LlmException(LlmException.Kind.NETWORK, "could not reach the provider (${e.message ?: e.javaClass.simpleName})")
    }

    private fun readAll(r: Provider.Response, limit: Int = 200_000): String =
        r.body.use { String(it.readNBytes(limit), Charsets.UTF_8) }

    /** The configured model, or the first preferred model the provider actually serves. */
    fun model(s: LlmSettings): String {
        if (s.model.isNotBlank()) return s.model.trim()
        resolvedModel?.let { (url, m) -> if (url == base(s)) return m }
        requireKey(s)
        val res = get(s, "GET", "/models", null, 10)
        val text = readAll(res)
        checkStatus(res.status, text)
        val ids = JsonParser.parseString(text).asJsonObject.getAsJsonArray("data")
            .map { it.asJsonObject.get("id").asString }
        availableIds = ids
        val notChat = Regex("(?i)whisper|tts|guard|embed|playai|orpheus|distil|moderation|speech|audio|image|vision")
        val pick = s.preferredModels.firstOrNull { it in ids } ?: ids.firstOrNull { !notChat.containsMatchIn(it) }
            ?: throw LlmException(LlmException.Kind.BAD_MODEL, "the provider lists no chat models")
        resolvedModel = base(s) to pick
        return pick
    }

    fun forgetResolvedModel() { resolvedModel = null; availableIds = null; coolingUntil.clear() }

    /** Models the provider serves (from /models), once known. */
    @Volatile private var availableIds: List<String>? = null

    /** Free tiers limit each model separately: a model that just said "429" rests here until the time shown. */
    private val coolingUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The main model first, then the other preferred models this provider serves: tried in order on 429. */
    private fun candidates(s: LlmSettings): List<String> {
        val main = model(s)
        val ids = availableIds
        val others = s.preferredModels.filter { it != main && (ids == null || it in ids) }
        val now = System.currentTimeMillis()
        val all = listOf(main) + others
        return all.filter { (coolingUntil[it] ?: 0L) <= now }.ifEmpty { listOf(all.minBy { coolingUntil[it] ?: 0L }) }
    }

    /** The model the last reply came from (it can differ from the main one after a rate limit). */
    @Volatile var lastModelUsed: String? = null
        private set

    /**
     * Streams one chat completion. [onText] gets content deltas as they arrive (worker thread).
     */
    fun stream(s: LlmSettings, messages: JsonArray, tools: JsonArray?, onText: (String) -> Unit): Completion {
        requireKey(s)
        var last: LlmException? = null
        for (model in candidates(s)) {
            try {
                return streamWith(model, s, messages, tools, onText).also { lastModelUsed = model }
            } catch (e: LlmException) {
                if (e.kind != LlmException.Kind.RATE_LIMIT) throw e
                // Rest this model (for the time the provider asked, else a minute) and try the next one.
                coolingUntil[model] = System.currentTimeMillis() + (e.retryAfterMs ?: 60_000L)
                last = e
            }
        }
        throw last ?: LlmException(LlmException.Kind.RATE_LIMIT, "rate limited")
    }

    private fun streamWith(model: String, s: LlmSettings, messages: JsonArray, tools: JsonArray?, onText: (String) -> Unit): Completion {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("stream", true)
            addProperty("max_tokens", s.maxTokens)
            addProperty("temperature", s.temperature)
            if (tools != null && tools.size() > 0) {
                add("tools", tools)
                addProperty("tool_choice", "auto")
            }
            // Reasoning models spend tokens thinking before they speak; keep that short for latency.
            if (model.contains("gpt-oss")) addProperty("reasoning_effort", "low")
            for ((k, v) in s.extraBody.entrySet()) add(k, v)
        }
        val res = get(s, "POST", "/chat/completions", body.toString().toByteArray(Charsets.UTF_8), s.timeoutSeconds.toLong())
        if (res.status != 200) {
            val err = readAll(res, 8_000)
            if (res.status == 429) {
                val wait = res.retryAfter?.trim()?.toDoubleOrNull()
                throw LlmException(LlmException.Kind.RATE_LIMIT, "rate limited (HTTP 429)", wait?.let { (it * 1000).toLong().coerceIn(1_000, 600_000) })
            }
            // The model wrote a tool call that isn't valid JSON: worth a retry (and then going without tools).
            if (err.contains("tool_use_failed")) throw LlmException(LlmException.Kind.TOOL_FAILED, "the model fumbled a tool call")
            checkStatus(res.status, err)
        }

        val text = StringBuilder()
        val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, args
        try {
            res.body.bufferedReader(Charsets.UTF_8).lines().use { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue
                    val obj = JsonParser.parseString(data).asJsonObject
                    obj.getAsJsonObject("error")?.let {
                        val kind = if (it.toString().contains("tool_use_failed")) LlmException.Kind.TOOL_FAILED else LlmException.Kind.SERVER
                        throw LlmException(kind, safeError(it.toString()))
                    }
                    val choices = obj.getAsJsonArray("choices") ?: continue
                    if (choices.size() == 0) continue
                    val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: continue
                    delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let { if (it.isNotEmpty()) { text.append(it); onText(it) } }
                    delta.getAsJsonArray("tool_calls")?.forEach { tc ->
                        val o = tc.asJsonObject
                        val i = o.get("index")?.asInt ?: 0
                        val acc = calls.getOrPut(i) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                        o.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { acc.first.append(it) }
                        o.getAsJsonObject("function")?.let { f ->
                            f.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { acc.second.append(it) }
                            f.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { acc.third.append(it) }
                        }
                    }
                }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException(LlmException.Kind.NETWORK, "the reply stream broke (${e.javaClass.simpleName})")
        }
        return Completion(text.toString(), calls.values.map { (id, name, args) ->
            ToolCall(id.toString().ifEmpty { "call_${name}" }, name.toString(), args.toString().ifBlank { "{}" })
        })
    }

    private fun checkStatus(code: Int, body: String) {
        when {
            code == 200 -> return
            code == 401 || code == 403 -> throw LlmException(LlmException.Kind.BAD_KEY, "the provider rejected the API key (HTTP $code)")
            code == 429 -> throw LlmException(LlmException.Kind.RATE_LIMIT, "rate limited (HTTP 429)")
            code == 404 || (code == 400 && body.contains("model", true)) ->
                throw LlmException(LlmException.Kind.BAD_MODEL, "model or endpoint not found (HTTP $code): ${safeError(body)}")
            else -> throw LlmException(LlmException.Kind.SERVER, "provider error (HTTP $code): ${safeError(body)}")
        }
    }

    /** Provider error text, trimmed, with anything key-shaped masked. */
    private fun safeError(s: String): String =
        s.replace(Regex("(sk|gsk|or|key)[-_][A-Za-z0-9_\\-]{8,}"), "***").replace(Regex("\\s+"), " ").take(200)
}
