package dev.dexbuddy.client.voice

import com.google.gson.JsonParser
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.llm.LlmException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** OpenAI-compatible /audio/transcriptions client (Groq Whisper by default). Worker threads only. */
object SpeechToText {
    private val http: HttpClient get() = dev.dexbuddy.client.llm.LlmClient.http

    @Volatile private var resolved: Pair<String, String>? = null // baseUrl to model
    @Volatile private var lastUsed = 0L

    /**
     * Called when the talk key goes down: while the Trainer speaks, make sure the model is known and the
     * connection is open, so the request on release skips DNS/TLS setup.
     */
    fun warmUp() {
        try {
            model()
            if (System.currentTimeMillis() - lastUsed > 20_000 && !viaServer()) {
                http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/models")).timeout(Duration.ofSeconds(5)).auth().GET().build(),
                    HttpResponse.BodyHandlers.discarding())
                lastUsed = System.currentTimeMillis()
            }
        } catch (_: Exception) {
            // Best effort: the real request reports any problem.
        }
    }

    private fun baseUrl(): String =
        BuddyConfig.settings.stt.baseUrl.ifBlank { BuddyConfig.settings.llm.baseUrl }.trimEnd('/')

    private fun apiKey(): String =
        BuddyConfig.settings.stt.apiKey.ifBlank { BuddyConfig.settings.llm.apiKey }.trim()

    fun forgetResolvedModel() { resolved = null }

    private fun viaServer() = dev.dexbuddy.client.llm.Provider.viaServer(apiKey(), baseUrl())

    private fun requireKey() {
        val local = baseUrl().contains("localhost") || baseUrl().contains("127.0.0.1")
        if (apiKey().isBlank() && !local && !viaServer()) throw LlmException(LlmException.Kind.NO_KEY, "no API key set for speech-to-text")
    }

    private fun call(method: String, path: String, contentType: String?, body: ByteArray?, timeout: Long): Pair<Int, String> = try {
        val r = dev.dexbuddy.client.llm.Provider.request(baseUrl(), apiKey(), method, path, contentType, body, timeout)
        r.status to r.body.use { String(it.readNBytes(200_000), Charsets.UTF_8) }
    } catch (e: Exception) {
        throw LlmException(LlmException.Kind.NETWORK, "could not reach the speech-to-text provider (${e.message ?: e.javaClass.simpleName})")
    }

    private fun HttpRequest.Builder.auth(): HttpRequest.Builder = apply { if (apiKey().isNotBlank()) header("Authorization", "Bearer ${apiKey()}") }

    /** The configured model, or the provider's Whisper model (turbo preferred: fastest). */
    fun model(): String {
        BuddyConfig.settings.stt.model.trim().takeIf { it.isNotEmpty() }?.let { return it }
        resolved?.let { (url, m) -> if (url == baseUrl()) return m }
        requireKey()
        val (code, text) = call("GET", "/models", null, null, 10)
        status(code, text)
        val ids = JsonParser.parseString(text).asJsonObject.getAsJsonArray("data").map { it.asJsonObject.get("id").asString }
        val whisper = ids.filter { it.contains("whisper", true) }
        val pick = whisper.firstOrNull { it.contains("turbo", true) } ?: whisper.firstOrNull()
            ?: throw LlmException(LlmException.Kind.BAD_MODEL, "the provider has no Whisper model; set stt.model")
        resolved = baseUrl() to pick
        return pick
    }

    /**
     * Turns speech into text. [hint] is a short list of words to spell right (names, Pokémon on your team);
     * Whisper uses it as context.
     */
    fun transcribe(rec: Recording, hint: String): String {
        requireKey()
        val s = BuddyConfig.settings.stt
        val boundary = "dexbuddy-" + UUID.randomUUID()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", model())
        field("response_format", "json")
        field("temperature", "0")
        if (s.language.isNotBlank()) field("language", s.language.trim())
        if (hint.isNotBlank()) field("prompt", hint)
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(rec.wav())
        body.write("\r\n--$boundary--\r\n".toByteArray())

        val (code, text) = call("POST", "/audio/transcriptions", "multipart/form-data; boundary=$boundary", body.toByteArray(), s.timeoutSeconds.toLong())
        status(code, text)
        lastUsed = System.currentTimeMillis()
        return JsonParser.parseString(text).asJsonObject.get("text")?.asString?.trim() ?: ""
    }

    private fun status(code: Int, body: String) {
        when {
            code == 200 -> return
            code == 401 || code == 403 -> throw LlmException(LlmException.Kind.BAD_KEY, "speech-to-text rejected the API key (HTTP $code)")
            code == 429 -> throw LlmException(LlmException.Kind.RATE_LIMIT, "speech-to-text rate limited (HTTP 429)")
            code == 404 -> throw LlmException(LlmException.Kind.BAD_MODEL, "speech-to-text model or endpoint not found (HTTP 404)")
            else -> throw LlmException(LlmException.Kind.SERVER, "speech-to-text error (HTTP $code): " +
                body.replace(Regex("(sk|gsk|key)[-_][A-Za-z0-9_\\-]{8,}"), "***").replace(Regex("\\s+"), " ").take(200))
        }
    }
}
