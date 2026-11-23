package dev.dexbuddy.client.music

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.client.DexBuddyClient
import dev.lavalink.youtube.YoutubeAudioSourceManager
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Signing in to YouTube, so it streams songs to Dex (it blocks most of them for signed-out players now).
 * Google's TV-style sign-in: the phone shows a short code, you enter it at google.com/device. The token is
 * kept only on this PC (config/dexbuddy-youtube.json), never logged and never sent to the server.
 */
object YouTubeLogin {
    private val file = FabricLoader.getInstance().configDir.resolve("dexbuddy-youtube.json")

    enum class State { SIGNED_OUT, WAITING, SIGNED_IN, FAILED }
    @Volatile var state = State.SIGNED_OUT; private set
    /** While WAITING: the code to type, and where. */
    @Volatile var userCode = ""; private set
    @Volatile var verifyUrl = "https://www.google.com/device"; private set
    @Volatile var error = ""; private set

    private var yt: YoutubeAudioSourceManager? = null
    @Volatile private var attempt = 0

    private fun saved(): String? = try {
        if (Files.exists(file)) JsonParser.parseString(Files.readString(file)).asJsonObject.get("refreshToken")?.asString?.takeIf { it.isNotBlank() } else null
    } catch (_: Exception) { null }

    private fun save(token: String?) {
        try {
            if (token == null) { Files.deleteIfExists(file); return }
            Files.createDirectories(file.parent)
            Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(JsonObject().apply {
                addProperty("note", "Your YouTube sign-in for Dex Buddy's music. Keep it private; delete this file to sign out.")
                addProperty("refreshToken", token)
            }))
        } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] music: couldn't save the YouTube sign-in ({})", e.javaClass.simpleName) }
    }

    /** Called when the audio library starts: signs back in with the saved token, if any. */
    fun attach(source: YoutubeAudioSourceManager) {
        yt = source
        val token = saved() ?: return
        try { source.useOauth2(token, true); state = State.SIGNED_IN } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] music: saved YouTube sign-in didn't work ({})", e.javaClass.simpleName)
        }
    }

    val signedIn: Boolean get() = state == State.SIGNED_IN

    /** Starts the sign-in: fetches a code to show, then waits (in the background) for you to enter it. */
    fun begin() {
        if (state == State.WAITING) return
        val source = yt ?: run { MusicClient.warmUp(); yt } ?: return
        val my = ++attempt
        state = State.WAITING; userCode = ""; error = ""
        Thread({
            try {
                val handler = source.oauth2Handler
                val code = handler.fetchDeviceCode()
                userCode = code.get("user_code").text() ?: ""
                verifyUrl = code.get("verification_url").text() ?: "https://www.google.com/device"
                val device = code.get("device_code").text() ?: throw IllegalStateException("no device code")
                val interval = (code.get("interval").asLong(5L).coerceAtLeast(2L)) * 1000L
                val until = System.currentTimeMillis() + code.get("expires_in").asLong(1800L) * 1000L
                DexBuddyClient.LOG.info("[dexbuddy] music: YouTube sign-in started (waiting for the code to be entered)")
                while (my == attempt && System.currentTimeMillis() < until) {
                    Thread.sleep(interval)
                    val r = handler.fetchRefreshToken(device)
                    val err = r.get("error").text()
                    when {
                        err == null -> {
                            val token = r.get("refresh_token").text() ?: throw IllegalStateException("no token")
                            source.useOauth2(token, true)
                            save(token)
                            state = State.SIGNED_IN; userCode = ""
                            DexBuddyClient.LOG.info("[dexbuddy] music: signed in to YouTube")
                            net.minecraft.client.Minecraft.getInstance().execute { dev.dexbuddy.client.antics.Commands.reply("Signed in to YouTube! Now I can play way more songs.") }
                            return@Thread
                        }
                        err == "authorization_pending" -> {}
                        err == "slow_down" -> Thread.sleep(interval)
                        else -> throw IllegalStateException(err)
                    }
                }
                if (my == attempt) { state = State.FAILED; error = "The code expired. Try again." }
            } catch (e: Exception) {
                if (my == attempt) { state = State.FAILED; error = "Sign-in didn't work (${e.message?.take(40) ?: e.javaClass.simpleName})." }
                DexBuddyClient.LOG.warn("[dexbuddy] music: YouTube sign-in failed ({})", e.javaClass.simpleName)
            }
        }, "dexbuddy-yt-login").apply { isDaemon = true }.start()
    }

    fun cancel() { attempt++; if (state == State.WAITING || state == State.FAILED) state = State.SIGNED_OUT; userCode = "" }

    fun signOut() {
        attempt++
        save(null)
        // The library has no "sign out": switch its sign-in off directly.
        try {
            yt?.oauth2Handler?.let { h ->
                h.javaClass.getDeclaredField("enabled").apply { isAccessible = true }.setBoolean(h, false)
                h.javaClass.getDeclaredField("refreshToken").apply { isAccessible = true }.set(h, null)
                h.javaClass.getDeclaredField("accessToken").apply { isAccessible = true }.set(h, null)
            }
        } catch (_: Exception) {}
        state = State.SIGNED_OUT; userCode = ""
    }
}
