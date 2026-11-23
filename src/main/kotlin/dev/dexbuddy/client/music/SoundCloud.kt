package dev.dexbuddy.client.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.client.DexBuddyClient
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Finds songs on SoundCloud that can actually be played: full tracks (not 30-second previews) with an
 * MP3 or Opus stream the audio library can decode. The library's own search gives up when any result
 * only has SoundCloud's newer format, so we search here and hand it the track's link. Background threads only.
 */
object SoundCloud {
    class Found(val url: String, val title: String, val artist: String, val durationMs: Long)

    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    @Volatile private var clientId: String? = null

    private fun get(url: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", UA).GET().build(), HttpResponse.BodyHandlers.ofString())

    /** SoundCloud's public web-player id, read from its site's scripts (what the web player itself uses). */
    private fun clientId(refresh: Boolean = false): String? {
        if (!refresh) clientId?.let { return it }
        return try {
            val page = get("https://soundcloud.com").body()
            val scripts = Regex("<script crossorigin src=\"(https://[^\"]+\\.js)\"").findAll(page).map { it.groupValues[1] }.toList().reversed()
            scripts.firstNotNullOfOrNull { s -> Regex("client_id\\s*:\\s*\"([a-zA-Z0-9]{20,40})\"").find(get(s).body())?.groupValues?.get(1) }
                ?.also { clientId = it }
        } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] music: SoundCloud unreachable ({})", e.javaClass.simpleName); null }
    }

    private val ALT = Regex("\\b(cover|remix|live|sped ?up|slowed|nightcore|reverb|karaoke|instrumental|8d|bass ?boost(ed)?|mashup|edit)\\b", RegexOption.IGNORE_CASE)

    private fun playable(t: JsonObject): Boolean {
        val policy = t.get("policy")?.takeIf { !it.isJsonNull }?.asString ?: ""
        if (policy == "SNIP" || policy == "BLOCK") return false
        val codes = t.getAsJsonObject("media")?.getAsJsonArray("transcodings") ?: return false
        return codes.any { c ->
            val f = c.asJsonObject.getAsJsonObject("format") ?: return@any false
            val proto = f.get("protocol")?.asString; val mime = f.get("mime_type")?.asString ?: ""
            // "audio/mpeg" is MP3; "audio/mpegurl" is SoundCloud's newer stream the library can't read.
            val mp3 = Regex("^audio/mpeg\\s*(;.*)?$").matches(mime)
            (proto == "progressive" && mp3) || (proto == "hls" && (mp3 || mime.contains("opus")))
        }
    }

    /** The best playable full track for [query], or null. */
    fun find(query: String): Found? {
        repeat(2) { attempt ->
            val id = clientId(refresh = attempt > 0) ?: return null
            val res = try {
                get("https://api-v2.soundcloud.com/search/tracks?q=${URLEncoder.encode(query, Charsets.UTF_8)}&limit=20&client_id=$id")
            } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] music: SoundCloud search failed ({})", e.javaClass.simpleName); return null }
            if (res.statusCode() == 401 || res.statusCode() == 403) return@repeat // stale id: read a fresh one
            if (res.statusCode() != 200) return null
            val list = try { JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("collection") } catch (_: Exception) { return null } ?: return null
            val wantAlt = ALT.containsMatchIn(query)
            val tracks = list.map { it.asJsonObject }.filter { playable(it) }
            // The original over covers/remixes (unless asked for), keeping SoundCloud's order otherwise.
            val pick = tracks.firstOrNull { wantAlt || !ALT.containsMatchIn(it.get("title")?.asString ?: "") } ?: tracks.firstOrNull() ?: return null
            val user = pick.getAsJsonObject("user")?.get("username")?.asString ?: ""
            DexBuddyClient.LOG.info("[dexbuddy] music: SoundCloud picked {} of {} results", tracks.indexOf(pick) + 1, list.size())
            return Found(pick.get("permalink_url").asString, pick.get("title")?.asString ?: query, user, pick.get("full_duration")?.asLong ?: pick.get("duration")?.asLong ?: 0L)
        }
        return null
    }
}
