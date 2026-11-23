package dev.dexbuddy.client.music

import com.google.gson.JsonObject
import dev.lavalink.youtube.clients.AndroidMusic
import dev.lavalink.youtube.clients.AndroidVr
import dev.lavalink.youtube.clients.Ios
import dev.lavalink.youtube.clients.MWeb
import dev.lavalink.youtube.clients.Music
import dev.lavalink.youtube.clients.Tv
import dev.lavalink.youtube.clients.TvHtml5Simply
import dev.lavalink.youtube.clients.Web
import dev.lavalink.youtube.clients.WebEmbedded
import com.google.gson.JsonParser
import com.sedmelluq.discord.lavaplayer.format.AudioPlayerInputStream
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.client.antics.Commands
import dev.dexbuddy.client.tts.VoicePlayer
import dev.dexbuddy.net.MusicActionPayload
import dev.dexbuddy.net.MusicStatePayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundSource
import java.util.UUID
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dex's music player. Your Dex plays songs from YouTube (a link, a playlist, or a search) while it floats
 * beside you or rides in your inventory. Players nearby hear it like proximity chat: full volume up close,
 * fading out with distance, nothing beyond ~24 blocks. The server only says what's playing and where in
 * the song; every player's game streams the audio itself. Client thread (audio on its own threads).
 */
object MusicClient {
    /** Full volume within this many blocks... */
    private const val NEAR = 4.0
    /** ...fading to silence here; nothing is heard (or streamed) beyond. */
    private const val SILENT = 24.0
    private const val START = 26.0
    private const val STOP = 34.0

    /** A Dex playing music (yours or someone else's), as the server described it. */
    class Session(
        val owner: UUID, val name: String, val entityId: Int, val fromDex: Boolean,
        val id: String, val title: String, val author: String, val durationMs: Long,
        val posMs: Long, val playing: Boolean, val receivedAt: Long,
    ) {
        fun position(now: Long = System.currentTimeMillis()) = if (playing) posMs + (now - receivedAt) else posMs
    }

    /** [fallback]: already the SoundCloud stand-in for a song YouTube blocked (no second fallback). */
    class Song(val id: String, val title: String, val author: String, val durationMs: Long, val fallback: Boolean = false)

    /** Say "Now playing" once the audio really starts (not when the search finds it). */
    private var announce: String? = null
    private var playingSince = 0L
    /** Found, but the audio hasn't started yet (or is being looked for on SoundCloud). */
    val starting: Boolean get() = announce != null || loading
    /** A short line for the phone: what's happening while it starts, or why it couldn't. */
    var status: String = ""; private set
    var statusUntil = 0L; private set
    private fun status(s: String, ms: Long = 60_000) { status = s; statusUntil = System.currentTimeMillis() + ms }
    val statusLine: String? get() = status.takeIf { it.isNotEmpty() && System.currentTimeMillis() < statusUntil }

    val sessions = HashMap<UUID, Session>()
    private val streams = HashMap<UUID, Stream>()

    /** Your Dex: the song playing and what's queued after it. */
    var current: Song? = null; private set
    val queue = ArrayList<Song>()
    var loading = false; private set

    private var manager: AudioPlayerManager? = null

    /** The audio library, set up the first time music is used (YouTube + plain audio links). */
    private fun manager(): AudioPlayerManager = manager ?: DefaultAudioPlayerManager().also { m ->
        m.configuration.outputFormat = StandardAudioDataFormats.COMMON_PCM_S16_LE
        // Several YouTube clients, tried in order: when YouTube turns one away, the next usually works.
        // The TV client goes first: it's the one that plays with a YouTube sign-in (YouTubeLogin).
        val yt = dev.lavalink.youtube.YoutubeAudioSourceManager(true,
            Tv(), MWeb(), Ios(), TvHtml5Simply(), AndroidVr(), Web(), WebEmbedded(), Music(), AndroidMusic())
        m.registerSourceManager(yt)
        YouTubeLogin.attach(yt)
        // SoundCloud: where songs YouTube won't stream are found instead (no account needed).
        // (No plain web links: other players' songs must never make your game visit an arbitrary address.)
        m.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager.createDefault())
        manager = m
    }

    /** Sets up the audio library (and a saved YouTube sign-in) without playing anything. */
    fun warmUp() { manager() }

    private val me: UUID? get() = Minecraft.getInstance().player?.uuid
    private fun canSend() = try { ClientPlayNetworking.canSend(MusicActionPayload.TYPE) } catch (_: Exception) { false }

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(MusicStatePayload.TYPE) { p, _ -> onState(p.json) }
        ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { reset() } }
    }

    private fun reset() {
        streams.values.forEach { it.close() }; streams.clear(); sessions.clear()
        current = null; queue.clear(); loading = false
    }

    // ---- your Dex: play, queue, pause, skip, stop -------------------------------------------------------

    /** Plays a YouTube link, playlist, or search ("lofi beats"). With [queueIt], adds it after the current song. */
    fun play(query: String, queueIt: Boolean = false) {
        val q = query.trim().removePrefix("<").removeSuffix(">")
        if (q.isEmpty()) return
        loading = true; status("Finding it...")
        val notFound = {
            onClient { loading = false; status("No song found. Try other words?", 8_000); Commands.reply("Hmm, I couldn't find that song. Try other words?") }
        }
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(q)) {
            if (!Regex("^https://([\\w-]+\\.)?(youtube\\.com|youtu\\.be|soundcloud\\.com)/", RegexOption.IGNORE_CASE).containsMatchIn(q)) {
                loading = false; status("Only YouTube or SoundCloud links work.", 8_000)
                Commands.reply("I can only play YouTube or SoundCloud links!"); return
            }
            loadInto(q, queueIt) { onClient { loading = false; status("Couldn't open that link.", 8_000); Commands.reply("Bzzt... I couldn't open that link. Try another?") } }
            return
        }
        manager()
        // Typed songs: YouTube when signed in; otherwise SoundCloud first (most YouTube videos won't
        // stream signed out), then YouTube.
        if (YouTubeLogin.signedIn) { loadInto("ytsearch:$q", queueIt, notFound); return }
        searcher.execute {
            val f = SoundCloud.find(q)
            if (f != null) loadInto(f.url, queueIt) { loadInto("ytsearch:$q", queueIt, notFound) }
            else loadInto("ytsearch:$q", queueIt, notFound)
        }
    }

    private val searcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dexbuddy-music-search").apply { isDaemon = true } }

    /** Loads a link or search into your Dex (playing it, or queued); [miss] if nothing came of it. */
    private fun loadInto(id: String, queueIt: Boolean, miss: () -> Unit) {
        manager().loadItem(id, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) = onClient { got(listOf(track), queueIt) }
            override fun playlistLoaded(playlist: AudioPlaylist) {
                if (playlist.tracks.isEmpty()) { miss(); return }
                onClient { got(if (playlist.isSearchResult) playlist.tracks.take(1) else playlist.tracks.take(100), queueIt) }
            }
            override fun noMatches() = miss()
            override fun loadFailed(e: FriendlyException) { DexBuddyClient.LOG.warn("[dexbuddy] music load failed: {}", e.message); miss() }
        })
    }

    private fun got(tracks: List<AudioTrack>, queueIt: Boolean) {
        loading = false
        val songs = tracks.map { val uri = it.info.uri ?: it.identifier
            Song(uri, dev.dexbuddy.net.MusicIds.clean(it.info.title ?: "Unknown song", 200), dev.dexbuddy.net.MusicIds.clean(it.info.author ?: "", 100), it.duration, fallback = uri.contains("soundcloud.com")) }
            .filter { dev.dexbuddy.net.MusicIds.allowed(it.id) }
        if (songs.isEmpty()) { status("Couldn't play that link.", 8_000); Commands.reply("Hmm, I can't play that one."); return }
        if (queueIt && current != null) {
            queue.addAll(songs)
            Commands.reply(if (songs.size == 1) "Added ${songs[0].title} to the queue!" else "Added ${songs.size} songs to the queue!")
            return
        }
        queue.clear(); queue.addAll(songs.drop(1))
        start(songs[0])
        announce = "Now playing: ${cleanTitle(songs[0].title)}" + if (songs.size > 1) ", plus ${songs.size - 1} more!" else "!"
        dev.dexbuddy.client.antics.Tricks.used("music")
    }

    /** "Bruno Mars - The Lazy Song (Official Music Video)" -> "Bruno Mars - The Lazy Song". */
    private fun cleanTitle(t: String) = t
        .replace(Regex("\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|remaster|4k|hd|hq|mv|explicit|clean)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\|.*$"), "").trim().ifBlank { t }

    /** Your song wouldn't stream (YouTube wants a sign-in for most videos now): find it on SoundCloud instead. */
    private fun ownFailed() {
        val s = current ?: return
        announce = null
        DexBuddyClient.LOG.info("[dexbuddy] music: couldn't stream \"{}\"{}", s.title, if (s.fallback) "" else ", trying SoundCloud")
        if (s.fallback) { giveUp(); return }
        status("YouTube blocked it. Trying SoundCloud...")
        val q = cleanTitle(s.title).let { if (s.author.isNotBlank() && !it.contains(s.author.removeSuffix(" - Topic").removeSuffix("VEVO"), true)) "${s.author.removeSuffix(" - Topic").removeSuffix("VEVO")} $it" else it }
        loading = true
        searcher.execute {
            val f = SoundCloud.find(q)
            if (f == null) { onClient { loading = false; giveUp() }; return@execute }
            manager().loadItem(f.url, object : AudioLoadResultHandler {
                override fun trackLoaded(track: AudioTrack) = onClient { fallback(s, track) }
                override fun playlistLoaded(playlist: AudioPlaylist) = onClient { playlist.tracks.firstOrNull()?.let { fallback(s, it) } ?: run { loading = false; giveUp() } }
                override fun noMatches() = onClient { loading = false; giveUp() }
                override fun loadFailed(e: FriendlyException) = onClient { loading = false; giveUp() }
            })
        }
    }

    private fun fallback(was: Song, t: AudioTrack) {
        loading = false
        if (current !== was) return // something else was picked meanwhile
        val song = Song(t.info.uri ?: t.identifier, t.info.title ?: cleanTitle(was.title), t.info.author ?: was.author, t.duration, fallback = true)
        start(song)
        announce = "YouTube blocked that one, so I found it on SoundCloud! Now playing: ${cleanTitle(t.info.title ?: song.title)}!"
    }

    private fun giveUp() {
        val had = current?.title
        DexBuddyClient.LOG.info("[dexbuddy] music: gave up on \"{}\"", had)
        val hint = if (YouTubeLogin.signedIn) "Try another song or link." else "Sign in to YouTube below for more songs."
        status("Couldn't play ${had?.let { cleanTitle(it) } ?: "that"}. $hint", 12_000)
        Commands.reply(if (queue.isNotEmpty()) "Couldn't play that one. Skipping!"
            else if (YouTubeLogin.signedIn) "Bzzt... I couldn't play ${had?.let { cleanTitle(it) } ?: "that"} anywhere. Try another song?"
            else "YouTube won't stream that unless you sign in. Tap Sign in to YouTube on the Music page!")
        if (queue.isEmpty()) stop() else skip()
    }

    private fun start(s: Song) {
        current = s; playingSince = System.currentTimeMillis()
        if (!s.fallback) status("Starting...")
        send(JsonObject().apply {
            addProperty("op", "now"); addProperty("id", s.id); addProperty("title", s.title); addProperty("author", s.author)
            addProperty("dur", s.durationMs); addProperty("pos", 0); addProperty("playing", true)
            addProperty("share", BuddyConfig.settings.music.share)
        })
        // No Dex network here (a server without the mod): play it just for you.
        if (!canSend()) me?.let { id -> localSession(id, s, 0L, true) }
    }

    private fun localSession(owner: UUID, s: Song, pos: Long, playing: Boolean) {
        val p = Minecraft.getInstance().player ?: return
        sessions[owner] = Session(owner, p.gameProfile.name, p.id, false, s.id, s.title, s.author, s.durationMs, pos, playing, System.currentTimeMillis())
    }

    val isPlaying: Boolean get() = me?.let { sessions[it]?.playing } ?: false

    fun togglePause() {
        val owner = me ?: return
        val s = sessions[owner] ?: return
        val pos = streams[owner]?.position() ?: s.position()
        val playing = !s.playing
        send(JsonObject().apply { addProperty("op", if (playing) "resume" else "pause"); addProperty("pos", pos) })
        sessions[owner] = Session(s.owner, s.name, s.entityId, s.fromDex, s.id, s.title, s.author, s.durationMs, pos, playing, System.currentTimeMillis())
    }

    fun skip() {
        val next = if (queue.isNotEmpty()) queue.removeAt(0) else null
        if (next == null) { stop(); return }
        start(next)
    }

    fun stop() {
        current = null; queue.clear()
        send(JsonObject().apply { addProperty("op", "stop") })
        me?.let { owner -> sessions.remove(owner); streams.remove(owner)?.close() }
    }

    /** Turned sharing on/off in Settings: tell the server (nearby players stop or start hearing it). */
    fun shareChanged() = send(JsonObject().apply { addProperty("op", "share"); addProperty("share", BuddyConfig.settings.music.share) })

    private fun send(o: JsonObject) { if (canSend()) ClientPlayNetworking.send(MusicActionPayload(o.toString())) }

    // ---- what's playing around you ---------------------------------------------------------------------

    private fun onState(json: String) {
        val o = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return }
        val owner = try { UUID.fromString(o.get("owner").asString) } catch (_: Exception) { return }
        Minecraft.getInstance().execute {
            if (o.get("disabled")?.asBoolean == true && owner == me) {
                current = null; queue.clear(); announce = null; loading = false
                status("Music is turned off on this server.", 15_000)
                Commands.reply("Music is turned off on this server. The server owner can turn it on!")
                return@execute
            }
            if (o.get("stop")?.asBoolean == true) {
                sessions.remove(owner); streams.remove(owner)?.close()
                if (owner == me) { current = null; queue.clear() }
                return@execute
            }
            // Only YouTube / SoundCloud songs, whatever the server says (never an arbitrary address).
            val id = o.get("id")?.asString?.takeIf { dev.dexbuddy.net.MusicIds.allowed(it) } ?: return@execute
            sessions[owner] = Session(owner, dev.dexbuddy.net.MusicIds.clean(o.get("name")?.asString ?: "?", 16), o.get("entity")?.asInt ?: -1, o.get("fromDex")?.asBoolean ?: false,
                id, dev.dexbuddy.net.MusicIds.clean(o.get("title")?.asString ?: "", 200), dev.dexbuddy.net.MusicIds.clean(o.get("author")?.asString ?: "", 100),
                o.get("dur")?.asLong ?: 0L, o.get("pos")?.asLong ?: 0L, o.get("playing")?.asBoolean ?: true, System.currentTimeMillis())
        }
    }

    /** Your own song ended: next in the queue. */
    private fun ownEnded() { if (current != null) skip() }

    private var ticks = 0
    /** Dev runs: decode and stream but play silently. */
    @Volatile var testMute = false

    private fun tick(mc: Minecraft) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        ticks++
        val settings = BuddyConfig.settings.music
        val cam = mc.gameRenderer.mainCamera
        val listener = cam.position
        val yaw = Math.toRadians(cam.yRot.toDouble())
        val o = mc.options
        val base = (o.getSoundSourceVolume(SoundSource.MASTER) * o.getSoundSourceVolume(SoundSource.RECORDS) * settings.volume.toFloat()).coerceIn(0f, 1f)
        // The companion talking: the music dips under its voice.
        val duck = if (testMute) 0f else if (VoicePlayer.speaking) 0.3f else 1f
        // At most the three closest other Dexes stream at once (a crowd can't pile up downloads).
        val closest = sessions.values.filter { it.owner != player.uuid }
            .sortedBy { s -> level.getEntity(s.entityId)?.position()?.distanceTo(listener) ?: Double.MAX_VALUE }
            .take(3).map { it.owner }.toSet()
        for ((owner, s) in sessions.toList()) {
            val own = owner == player.uuid
            if (!own && owner !in closest) { streams.remove(owner)?.close(); continue }
            val e = level.getEntity(s.entityId)
            // Your own Dex in your inventory plays "from you"; otherwise from the Dex (or the player carrying it).
            val src = when {
                own && !s.fromDex -> null
                e != null -> e.position().add(0.0, e.bbHeight * 0.5, 0.0)
                else -> null
            }
            val d = if (src == null) (if (own) 0.0 else Double.MAX_VALUE) else src.distanceTo(listener)
            val stream = streams[owner]
            val wanted = (own || settings.hearOthers) && d <= (if (stream != null) STOP else START)
            if (!wanted) { if (stream != null) { stream.close(); streams.remove(owner) }; continue }
            val st = stream ?: Stream(owner, own).also { streams[owner] = it }
            st.sync(s)
            if (own && ticks % 100 == 0 && announce != null) DexBuddyClient.LOG.info("[dexbuddy] music: waiting for audio, failed={} pos={} for {} ms", st.failed, st.position(), System.currentTimeMillis() - playingSince)
            if (own && st.failed) { st.failed = false; ownFailed() }
            else if (own && announce != null && st.position() > 300) { Commands.reply(announce!!); announce = null; status = "" }
            // Search found it but nothing came through for a long while: treat it as blocked.
            else if (own && announce != null && s.playing && System.currentTimeMillis() - playingSince > 20_000) { DexBuddyClient.LOG.info("[dexbuddy] music: no audio after 20 s"); ownFailed() }
            // Proximity: full volume up close, fading to silence, nothing beyond.
            val f = when { d <= NEAR -> 1.0; d >= SILENT -> 0.0; else -> 1.0 - (d - NEAR) / (SILENT - NEAR) }
            val g = (base * duck * (f * f).toFloat()).coerceIn(0f, 1f)
            // Left / right from where it is around you.
            var pan = 0.0
            if (src != null && d > 1.0) {
                val dx = src.x - listener.x; val dz = src.z - listener.z
                val ang = atan2(dz, dx) - (yaw + Math.PI / 2)
                pan = -sin(ang).coerceIn(-1.0, 1.0)
            }
            st.gainL = g * (1f - (maxOf(0.0, pan) * 0.6).toFloat())
            st.gainR = g * (1f - (maxOf(0.0, -pan) * 0.6).toFloat())
            // Music notes around a Dex that's playing.
            if (s.playing && e != null && s.fromDex && ticks % 14 == 0 && d < SILENT)
                level.addParticle(ParticleTypes.NOTE, e.x + (Math.random() - 0.5) * 0.6, e.y + e.bbHeight + 0.2, e.z + (Math.random() - 0.5) * 0.6, Math.random(), 0.0, 0.0)
        }
        // Streams whose music ended.
        streams.keys.filter { it !in sessions }.forEach { streams.remove(it)?.close() }
    }

    private fun onClient(r: () -> Unit) = Minecraft.getInstance().execute(r)

    /** Dev aid: what's playing, and each stream's position and volume. */
    fun debug(): String = "current=${current?.title} queue=${queue.size} sessions=${sessions.values.map { "${it.name}:${it.title}:${it.playing}" }} " +
        "streams=${streams.values.map { "pos=${it.position()} gain=${"%.2f".format(it.gainL)}/${"%.2f".format(it.gainR)}" }}"

    // ---- one stream: decode and play one Dex's music at a given volume and pan -----------------------------

    private class Stream(val owner: UUID, val own: Boolean) {
        private val player: AudioPlayer = manager().createPlayer()
        @Volatile var gainL = 0f
        @Volatile var gainR = 0f
        @Volatile private var alive = true
        private var playingId: String? = null
        private var paused = false

        /** The song couldn't be streamed (checked each tick). */
        @Volatile var failed = false

        init {
            player.addListener(object : AudioEventAdapter() {
                override fun onTrackEnd(p: AudioPlayer, track: AudioTrack, reason: AudioTrackEndReason) {
                    DexBuddyClient.LOG.info("[dexbuddy] music: track ended ({})", reason)
                    // A blocked song "finishes" right away (before its error arrives): that's a failure, not the end.
                    val early = track.duration > 15_000 && track.position < 5_000
                    if (reason == AudioTrackEndReason.LOAD_FAILED || (reason == AudioTrackEndReason.FINISHED && early)) failed = true
                    else if (reason == AudioTrackEndReason.FINISHED && own) onClient { ownEnded() }
                }
                override fun onTrackException(p: AudioPlayer, track: AudioTrack, e: FriendlyException) {
                    DexBuddyClient.LOG.info("[dexbuddy] music: playback error (own={})", own); failed = true
                }
                override fun onTrackStuck(p: AudioPlayer, track: AudioTrack, thresholdMs: Long) { failed = true }
            })
            Thread({ pump() }, "dexbuddy-music").apply { isDaemon = true; start() }
        }

        fun position(): Long = player.playingTrack?.position ?: 0L

        /** Matches the server's description: the song, where in it, paused or not. */
        fun sync(s: Session) {
            if (s.id != playingId) {
                playingId = s.id
                failed = false
                val at = s.position()
                manager().loadItem(s.id, object : AudioLoadResultHandler {
                    override fun trackLoaded(track: AudioTrack) { begin(track, at, s.playing) }
                    override fun playlistLoaded(playlist: AudioPlaylist) { (playlist.selectedTrack ?: playlist.tracks.firstOrNull())?.let { begin(it, at, s.playing) } }
                    override fun noMatches() { failed = true }
                    override fun loadFailed(e: FriendlyException) { failed = true; DexBuddyClient.LOG.warn("[dexbuddy] music stream failed: {}", e.message) }
                })
                paused = !s.playing
                return
            }
            if (paused == s.playing) { paused = !s.playing; player.isPaused = paused }
            // Drifted far from where the song should be (a pause elsewhere, a slow start): catch up.
            val track = player.playingTrack ?: return
            val want = s.position()
            if (track.isSeekable && kotlin.math.abs(track.position - want) > 4_000 && want < track.duration) track.position = want
        }

        private fun begin(track: AudioTrack, at: Long, playing: Boolean) {
            if (!alive) return
            if (at > 1_000 && track.isSeekable && at < track.duration) track.position = at
            player.playTrack(track)
            player.isPaused = !playing
            DexBuddyClient.LOG.info("[dexbuddy] music stream started ({} s in)", at / 1000)
        }

        private fun pump() {
            try {
                val input = AudioPlayerInputStream.createStream(player, StandardAudioDataFormats.COMMON_PCM_S16_LE, 10_000L, true)
                val fmt = AudioFormat(48_000f, 16, 2, true, false)
                val line = AudioSystem.getSourceDataLine(fmt)
                line.open(fmt, 48_000 * 4 / 5) // 200 ms
                line.start()
                val buf = ByteArray(3840) // 20 ms
                while (alive) {
                    var n = 0
                    while (n < buf.size && alive) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    if (n <= 0) continue
                    val l = gainL; val r = gainR
                    var i = 0
                    while (i + 3 < n) {
                        val sl = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)) * l
                        val sr = ((buf[i + 3].toInt() shl 8) or (buf[i + 2].toInt() and 0xFF)) * r
                        val a = sl.toInt().coerceIn(-32768, 32767); val b = sr.toInt().coerceIn(-32768, 32767)
                        buf[i] = (a and 0xFF).toByte(); buf[i + 1] = (a shr 8).toByte()
                        buf[i + 2] = (b and 0xFF).toByte(); buf[i + 3] = (b shr 8).toByte()
                        i += 4
                    }
                    line.write(buf, 0, n)
                }
                line.stop(); line.close(); input.close()
            } catch (e: Exception) {
                DexBuddyClient.LOG.warn("[dexbuddy] music playback stopped: {}", e.toString())
            }
        }

        fun close() { alive = false; try { player.destroy() } catch (_: Exception) {} }
    }
}
