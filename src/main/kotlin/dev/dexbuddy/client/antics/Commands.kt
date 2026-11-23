package dev.dexbuddy.client.antics

import dev.dexbuddy.client.entity.CompanionClient
import dev.dexbuddy.client.tts.Tts
import dev.dexbuddy.client.ui.Subtitles
import dev.dexbuddy.entity.DexBuddyEntity
import dev.dexbuddy.entity.Look
import dev.dexbuddy.net.CompanionCommandPayload
import dev.dexbuddy.net.QuizActionPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

/**
 * Things said to the companion that it simply does, instead of chatting about them: "stay", "follow
 * me", "light the way", "spin!", "get in the Dex", "hide and seek", quizzes. Checked before the AI is
 * asked, so they're instant and cost nothing. Client thread.
 */
object Commands {
    private data class Rule(val pattern: Regex, val maxWords: Int, val run: () -> Unit)

    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    @Volatile var quizOpen = false
    /** When the Trainer last asked for a spin (so the spin isn't mistaken for a punch). */
    @Volatile var lastSpinAt = 0L
    private var lastQuizKind = "who"
    private var lastQuizEnd = 0L

    private val NAME_RX = Regex(
        "\\b(?:your (?:new )?name (?:is|will be|shall be)|i(?:'ll| will| am going to|'m going to| wanna| want to) (?:call|name) you|(?:i )?(?:name|call) you|call yourself|you(?:'re| are) (?:now )?(?:called|named))\\s+(?!(?:back|later|soon|tomorrow|tonight|again|now|out|up|when|if|a|an|the|maybe|sometime|in|on|at|for|please|ok|okay|that|this|what|anything|something|nothing|over|then)\\b)([\\p{L}\\p{N}'\\-]+(?: [\\p{L}\\p{N}'\\-]+)?)",
        RegexOption.IGNORE_CASE,
    )

    private val QUIET_RX = rx("\\b(stop talking|be quiet|shut up|don'?t (talk|speak)|stay quiet|keep quiet|quiet down|go quiet|mute( yourself)?|silence|no talking)\\b")
    private val NUM_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "sixty" to 60, "half an" to 0)

    /** "stop talking for 10 minutes" / "be quiet for an hour": how many minutes (10 if no time is given). */
    private fun quietMinutes(t: String): Int {
        val s = t.lowercase()
        if (Regex("\\bhalf an hour\\b").containsMatchIn(s)) return 30
        val m = Regex("\\b(\\d+|forty five|${NUM_WORDS.keys.joinToString("|")})\\s*(minutes?|mins?|hours?|hrs?)\\b").find(s) ?: return 10
        val n = m.groupValues[1].toIntOrNull() ?: NUM_WORDS[m.groupValues[1]] ?: 10
        return if (m.groupValues[2].startsWith("h")) n * 60 else n
    }

    private val rules = listOf(
        // Quiet time: "stop talking for 10 minutes" (said with "Hey Dex", typed, or anything that reaches here).
        Rule(rx("\\b(you can (talk|speak)( now| again)?|talk again|speak again|unmute( yourself)?)\\b"), 8) { dev.dexbuddy.client.tts.Hush.talkAgain() },
        Rule(Regex(QUIET_RX.pattern + ".*\\bfor\\b", RegexOption.IGNORE_CASE), 16) { dev.dexbuddy.client.tts.Hush.quietFor(quietMinutes(lastText ?: "")) },
        // Quiz flow first, so "hint" or "I give up" during a quiz always go to the quiz.
        Rule(rx("\\b(i give up|give up|i don'?t know|no idea|tell me the answer)\\b"), 8) { quiz("giveup") },
        Rule(rx("^\\W*(a )?(hint|clue|give me a (hint|clue))\\b"), 6) { quiz("hint") },
        Rule(rx("\\b(stop|quit|end) (the )?(quiz|game)\\b"), 8) { quiz("stop") },
        Rule(rx("\\b(type quiz|type question|quiz me on types|type trivia)\\b"), 12) { startQuiz("type") },
        Rule(rx("\\b(quiz|trivia|who'?s that cobblemon|guess (the|a|which) cobblemon|test me)\\b"), 12) { startQuiz("who") },
        // Companion commands.
        Rule(rx("\\b(hide and seek|hide-and-seek|play hide|go hide)\\b"), 12) { companion("hide", "Hehe, okay! Count to ten and come find me! No peeking!", needsOut = true) },
        // Music: "play lofi beats", "pause the music", "next song", "stop the music", "what song is this".
        Rule(rx("\\b(stop|turn off|end|kill) (the |that |this )?(music|song|songs|tunes)\\b"), 8) { music("stop") },
        Rule(rx("\\b(pause|hold) (the |that |this )?(music|song|tunes)\\b"), 8) { music("pause") },
        Rule(rx("\\b(resume|unpause|continue|keep playing) (the )?(music|song|tunes)\\b|^\\W*(resume|unpause)\\W*$|\\bplay (the music|it) again\\b"), 8) { music("resume") },
        Rule(rx("\\b(next|skip( this| the)?) (song|track|one)\\b|^\\W*skip( it)?\\W*$"), 6) { music("skip") },
        Rule(rx("\\b(turn (it|the music) (up|down)|(louder|quieter|softer) (music|please)?|music (louder|quieter|softer|up|down))\\b"), 8) { music(if (Regex("up|louder", RegexOption.IGNORE_CASE).containsMatchIn(lastText.orEmpty())) "up" else "down") },
        Rule(rx("\\b(what|which) (song|music|track) (is (this|that|playing)|are you playing|is on)\\b|\\bwhat'?s (this|that) song\\b"), 12) { music("what") },
        Rule(rx("^\\W*(?:can you |could you |would you |please |ok(?:ay)? |hey )*(?:play|put on|queue( up)?|add)\\s+(?!hide\\b|a game\\b|games?\\b|with\\b|tag\\b|quiz\\b|trivia\\b|dead\\b)(.+)"), 22) { music("play") },
        Rule(rx("\\b(stop glowing|lights? off|turn off (the |your )?light|stop (the )?light)\\b"), 8) { companion("glow_off", "Lights off! Saving my sparks.", needsOut = true) },
        Rule(rx("\\b(light (the way|it up|up)|glow|be a (lamp|lantern|flashlight)|i can'?t see)\\b"), 8) { companion("glow_on", "Bzzt! Lighting the way!", needsOut = true) },
        Rule(rx("\\b(stay|wait) (here|there|put)\\b|^\\W*(stay|wait)\\W*$|\\bdon'?t move\\b"), 6) { companion("stay", "Okay! I'll wait right here.", needsOut = true) },
        Rule(rx("\\b(follow me|come (here|back|with me|on)|let'?s go|keep up)\\b"), 6) { companion("follow", "Coming! Wait for me!", needsOut = true) },
        Rule(rx("\\b(do a (spin|trick|flip|twirl)|spin( around)?|twirl|show me a trick)\\b"), 8) { companion("spin", "Wheee! Ta-daa!", needsOut = true) },
        Rule(rx("\\b(get (back )?in (the |your )?dex|go back (in|to) (the |your )?dex|return to (the |your )?dex|back in the dex)\\b"), 10) { companion("return", "Back into the Dex! Cozy!", needsOut = true) },
        // "What can you do?": a quick taste, and the Quick guide for the rest.
        Rule(rx("\\b(what (else )?can you do|what are your (features|tricks)|(any|show me|teach me) (some |your )?tricks|how do i use you|what do you do)\\b"), 12) { Tricks.used("hey_dex"); reply(Tricks.overview()) },
        // Tripod mode: "tripod mode", "stay there for a photo", "set up the camera"; "done" / "you can move" ends it.
        Rule(rx("\\btrip[aeo]d\\b|\\b(stay|wait|stand) (right )?(there|here) (for|and take|so you can take) (a |the |my )?(photo|picture|pic|shot|video)\\b|\\bset up (the |a )?(camera|shot)\\b"), 14) { startTripod() },
        Rule(rx("^\\W*(done|we'?re done|i'?m done|that'?s all|you can move( now)?|end tripod)\\W*$"), 6) { if (tripod) endTripod() else reply("Okay!") },
        // Videos: "take a video of me", "record a wide clip of us"; "stop recording" ends one.
        Rule(rx("\\b(stop|end|finish|cut) (the )?(recording|video|filming|clip)\\b|^\\W*cut\\W*$"), 8) { dev.dexbuddy.client.photo.PhotoClient.stopVideo() },
        Rule(rx("\\b(take|record|film|shoot|make|get|start)\\b.*\\b(video|clip|vid|vlog|movie)\\b|\\b(film|record) (me|us)\\b"), 16) { video(lastText ?: "") },
        // "Take a picture (of me)", "selfie!", "take a wide photo of us".
        Rule(rx("\\b(take|snap|grab|get) (a |me a |us a |my |our )?(quick |nice |cute )?(close.?up |portrait |wide |group )?(picture|photo|pic|selfie|shot|snapshot)\\b|\\bselfie\\b|\\bsay cheese\\b"), 16) {
            photo(lastText.orEmpty())
        },
        // "Your name is Sparky", "I'll call you Sparky", "your new name is Sir Beeps".
        Rule(NAME_RX, 12) {
            NAME_RX.find(lastText.orEmpty())?.groupValues?.get(1)?.let { rename(it) }
        },
        // "Open my phone", "show me my photos", "open my wardrobe".
        Rule(rx("\\b(open|show|check|see|let me see)( me| up)? (my |the |your )?(phone|gallery|photos|pictures|pics|camera roll|album|wardrobe|closet|costumes)\\b"), 10) {
            val t = lastText.orEmpty().lowercase()
            val tab = when {
                Regex("wardrobe|closet|costume").containsMatchIn(t) -> dev.dexbuddy.client.phone.PhoneScreen.Tab.WARDROBE
                Regex("gallery|photos|pictures|pics|camera roll|album").containsMatchIn(t) -> dev.dexbuddy.client.phone.PhoneScreen.Tab.GALLERY
                else -> dev.dexbuddy.client.phone.PhoneScreen.Tab.HOME
            }
            dev.dexbuddy.client.phone.PhoneScreen.open(tab)
        },
        // "What costumes do I have?"
        Rule(rx("\\b(wardrobe|(what|which|my) (costumes|outfits|looks)|costumes? do (i|you|we) have)\\b"), 12) { reply(wardrobeLine()) },
        // "Change your look to the dex ball", "turn into the plush", "wear the flip dex".
        Rule(rx("\\b(change|switch) (your )?(look|body|form|style|skin|model|costume|outfit)|\\b(turn|transform|switch|change) (in)?to (the |a |an |your )?(phone|classic|flip|ball|dex ball|plush|retro|plushie)\\b|\\b(wear|put on) (the |a |an |your )?(phone|classic|flip|ball|dex ball|plush|retro|plushie)\\b"), 14) {
            lastText?.let { Look.fromSpeech(it) }?.let { changeLook(it) } ?: reply(wardrobeLine())
        },
    )

    // ---- naming ------------------------------------------------------------------------------------

    /** Gives your Dex a name (out in the world, or the item you're carrying). */
    fun rename(raw: String): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        Tricks.used("name")
        // "Sparky from now on" -> "Sparky"; keep it short and title-cased.
        val trimmed = raw.trim().trimEnd('.', '!', '?', ',').split(' ')
            .takeWhile { it.lowercase() !in setOf("from", "now", "okay", "ok", "please", "then", "and", "because") }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        val name = DexBuddyEntity.cleanName(trimmed)
        if (name == null) { reply("Hmm, I need a name up to 16 letters. Try something like Sparky!"); return false }
        val holding = dev.dexbuddy.client.context.GameContext.heldDexBuddy() != null
        if (CompanionClient.mine(player) == null && !holding) { reply("Hold me or drop me out first, then name me!"); return false }
        if (!canSend(CompanionCommandPayload.TYPE)) { reply("I can only take a new name in a world with the Dex network."); return false }
        ClientPlayNetworking.send(CompanionCommandPayload("name:$name"))
        reply(listOf("$name? I love it! From now on, I'm $name!", "Bzzt! $name it is! That's me!", "$name... $name! Yes! That's my name now!").random())
        return true
    }

    // ---- camera ------------------------------------------------------------------------------------

    /** Set after it asked "what kind of picture?": the next thing said is the answer. */
    private var photoAskUntil = 0L

    /** Waiting for the companion to hop out before a photo (null kind: ask which kind once it's out). */
    private var pendingPhoto: dev.dexbuddy.client.photo.PhotoClient.Kind? = null
    private var pendingPhotoUntil = 0L
    private var pendingIsVideo = false
    private var outSince = 0L

    /** Just asked "what kind of photo?": "close-up", "portrait" or "wide" said on its own is the answer. */
    fun awaitingPhotoKind() = System.currentTimeMillis() < photoAskUntil

    // ---- tripod mode: it stays put and watches you; you get in position, then say "cheese" ----------------

    /** True while the companion is parked as a tripod, waiting for "cheese" / "record" / "done". */
    @Volatile var tripod = false
        private set
    private var tripodPending = false

    fun startTripod() {
        val player = Minecraft.getInstance().player ?: return
        val buddy = CompanionClient.mine(player)
        val holding = player.mainHandItem.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY) || player.offhandItem.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY)
        if (!canSend(CompanionCommandPayload.TYPE)) { reply("Tripod mode needs this world's Dex network."); return }
        closePhone()
        if (buddy == null) {
            if (!holding) { reply("Hold me in your hand, or drop me out, and I'll be your tripod!"); return }
            ClientPlayNetworking.send(CompanionCommandPayload("out"))
            tripodPending = true; pendingPhotoUntil = System.currentTimeMillis() + 6_000; pendingPhoto = null; pendingIsVideo = false
            return
        }
        ClientPlayNetworking.send(CompanionCommandPayload("stay"))
        tripod = true
        Tricks.used("tripod")
        reply("Tripod mode! I'll stay right here. Get in position, then say cheese for a photo, or record for a video. Say done when you're finished.")
    }

    fun endTripod(say: Boolean = true) {
        if (!tripod) return
        tripod = false
        if (canSend(CompanionCommandPayload.TYPE)) ClientPlayNetworking.send(CompanionCommandPayload("follow"))
        if (say) reply("Tripod mode off! Coming back to you.")
    }

    /** Photos are of the world, not the phone: close it first. */
    private fun closePhone() {
        val mc = Minecraft.getInstance()
        if (mc.screen is dev.dexbuddy.client.phone.PhoneScreen) mc.setScreen(null)
    }

    /** Client tick: once the companion is out (and has settled a moment), take the photo that was asked for. */
    fun tickPendingPhoto() {
        // Tripod mode ends if the companion goes back in the Dex (or away).
        if (tripod) Minecraft.getInstance().player?.let { if (CompanionClient.mine(it) == null) tripod = false }
        if (pendingPhotoUntil == 0L) return
        val now = System.currentTimeMillis()
        if (now > pendingPhotoUntil) { pendingPhotoUntil = 0L; outSince = 0L; return }
        val player = Minecraft.getInstance().player ?: return
        if (CompanionClient.mine(player) == null) return
        if (outSince == 0L) { outSince = now; return }
        if (now - outSince < 800) return
        pendingPhotoUntil = 0L; outSince = 0L
        if (tripodPending) { tripodPending = false; startTripod(); return }
        val k = pendingPhoto
        if (pendingIsVideo) { pendingIsVideo = false; dev.dexbuddy.client.photo.PhotoClient.takeCompanionVideo(k ?: dev.dexbuddy.client.photo.PhotoClient.Kind.PORTRAIT); return }
        if (k != null) dev.dexbuddy.client.photo.PhotoClient.takeCompanionPhoto(k)
        else {
            photoAskUntil = now + 25_000
            reply("Ready! What kind of photo? A close-up, a portrait, or a wide shot?")
            dev.dexbuddy.client.voice.WakeWord.expectAnswer()
        }
    }

    private fun photoKind(text: String): dev.dexbuddy.client.photo.PhotoClient.Kind? {
        val t = text.lowercase()
        return when {
            Regex("\\b(close.?up|close|face|zoom|near)\\b").containsMatchIn(t) -> dev.dexbuddy.client.photo.PhotoClient.Kind.CLOSE
            Regex("\\b(wide|far|landscape|full body|whole|scenery|big|everything|group)\\b").containsMatchIn(t) -> dev.dexbuddy.client.photo.PhotoClient.Kind.WIDE
            Regex("\\b(portrait|medium|normal|regular|half|upper body)\\b").containsMatchIn(t) -> dev.dexbuddy.client.photo.PhotoClient.Kind.PORTRAIT
            else -> null
        }
    }

    fun canUseCompanionCommands() = canSend(CompanionCommandPayload.TYPE)

    /** The phone's Quiz app. */
    fun startQuiz() = startQuiz("who")

    /** The phone's "Stop quiz" button. */
    fun stopQuiz() = quiz("stop")

    /** The phone's flashlight: your companion's glow, on or off. */
    fun toggleGlow() {
        val buddy = Minecraft.getInstance().player?.let { CompanionClient.mine(it) } ?: return
        if (buddy.glowing) companion("glow_off", "Lights off! Saving my sparks.", needsOut = true)
        else companion("glow_on", "Bzzt! Lighting the way!", needsOut = true)
    }

    /** Starts a photo the way saying [text] would (used by the phone's Camera button too). */
    fun startPhoto(text: String) = photo(text)

    private fun photo(text: String) {
        val player = Minecraft.getInstance().player ?: return
        val t = text.lowercase()
        if (tripod && CompanionClient.mine(player) != null) { closePhone(); dev.dexbuddy.client.photo.PhotoClient.takeTripodPhoto(); return }
        val holding = player.mainHandItem.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY) || player.offhandItem.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY)
        val ofMe = Regex("\\b(of me|of us|me and|selfie|my picture|our picture|with me)\\b").containsMatchIn(t)
        val buddy = CompanionClient.mine(player)
        val kind = photoKind(t)
        when {
            buddy == null && !holding -> reply("Hold me in your hand, or drop me out so I can be your camera!")
            // In your hand, but you want a photo of you (or a framed shot): it hops out and takes it.
            buddy == null && (ofMe || kind != null) && canSend(CompanionCommandPayload.TYPE) -> {
                closePhone()
                ClientPlayNetworking.send(CompanionCommandPayload("out"))
                pendingPhoto = kind; pendingIsVideo = false
                pendingPhotoUntil = System.currentTimeMillis() + 6_000
                reply(if (kind != null) "Coming out! Get in position..." else "Coming out! Let me get the camera ready...")
            }
            buddy == null || (holding && !ofMe) -> { closePhone(); dev.dexbuddy.client.photo.PhotoClient.takeHandPhoto() }
            else -> kind?.let { closePhone(); dev.dexbuddy.client.photo.PhotoClient.takeCompanionPhoto(it) } ?: run {
                photoAskUntil = System.currentTimeMillis() + 25_000
                reply("Ooh, photo time! What kind? A close-up, a portrait, or a wide shot?")
                dev.dexbuddy.client.voice.WakeWord.expectAnswer()
            }
        }
    }

    /** "Take a video of me": filmed by the companion (it hops out of your hand first if needed), or of what you see. */
    private fun video(text: String) {
        val player = Minecraft.getInstance().player ?: return
        val t = text.lowercase()
        if (tripod && CompanionClient.mine(player) != null) { closePhone(); dev.dexbuddy.client.photo.PhotoClient.takeTripodVideo(); return }
        val holding = player.mainHandItem.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY) || player.offhandItem.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY)
        val ofMe = Regex("\\b(of me|of us|me and|with me|film me|record me|film us|record us|selfie)\\b").containsMatchIn(t)
        val kind = photoKind(t)
        val buddy = CompanionClient.mine(player)
        closePhone()
        when {
            buddy == null && !holding -> reply("Hold me in your hand, or drop me out so I can film!")
            buddy == null && (ofMe || kind != null) && canSend(CompanionCommandPayload.TYPE) -> {
                ClientPlayNetworking.send(CompanionCommandPayload("out"))
                pendingPhoto = kind; pendingIsVideo = true
                pendingPhotoUntil = System.currentTimeMillis() + 6_000
                reply("Coming out to film you!")
            }
            buddy == null -> dev.dexbuddy.client.photo.PhotoClient.takeHandVideo()
            else -> dev.dexbuddy.client.photo.PhotoClient.takeCompanionVideo(kind ?: dev.dexbuddy.client.photo.PhotoClient.Kind.PORTRAIT)
        }
    }

    /** Which costumes your companion has, and what's still out there. */
    fun wardrobeLine(): String {
        val player = Minecraft.getInstance().player ?: return ""
        val buddy = CompanionClient.mine(player)
        val bits = buddy?.costumeBits ?: dev.dexbuddy.client.context.GameContext.heldDexBuddy()?.let { DexBuddyEntity.costumesOf(it) } ?: 0
        val owned = Look.entries.filter { DexBuddyEntity.hasCostume(bits, it) }
        val locked = Look.entries.size - owned.size
        val list = owned.joinToString(", ") { it.displayName }
        return if (locked == 0) "I have every costume! $list. Just say which one to wear!"
            else "My costumes: $list. $locked more to find! I might dig one up while I'm charged, or earn one as we grow closer."
    }

    private var lastText: String? = null

    /** Asks the server to swap your companion's body. False if it isn't out or the costume is locked. */
    fun changeLook(look: Look): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        Tricks.used("wear")
        val buddy = CompanionClient.mine(player)
        // Out, or tucked away in the Dex you're carrying: either way it can change.
        val bits = buddy?.costumeBits ?: dev.dexbuddy.client.context.GameContext.heldDexBuddy()?.let { DexBuddyEntity.costumesOf(it) }
        if (bits == null) { reply("Carry me or drop me out first, then I can change!"); return false }
        if (!DexBuddyEntity.hasCostume(bits, look)) { reply("I don't have the ${look.displayName} costume yet! Help me find it?"); return false }
        if (!canSend(CompanionCommandPayload.TYPE)) return false
        ClientPlayNetworking.send(CompanionCommandPayload("look:${look.id}"))
        reply(listOf("Ta-daa! How do I look?", "New look, same me! Bzzt!", "Ooh, I like this one!").random())
        return true
    }

    /** Returns true if [text] was a command (and it's been handled). */
    fun tryHandle(text: String): Boolean {
        lastText = text
        val words = text.trim().split(Regex("\\s+")).size
        // Answering "what kind of picture?"
        if (System.currentTimeMillis() < photoAskUntil) {
            photoAskUntil = 0
            val kind = photoKind(text)
            if (kind == null && Regex("\\b(no|nope|nah|never ?mind|cancel|forget it|stop|not now|later)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                reply("Okay, no photo! Just ask when you're ready.")
                return true
            }
            if (kind != null || words <= 4) {
                closePhone()
                dev.dexbuddy.client.photo.PhotoClient.takeCompanionPhoto(kind ?: dev.dexbuddy.client.photo.PhotoClient.Kind.PORTRAIT)
                return true
            }
        }
        // While a quiz question is open, whatever isn't a command is an answer.
        if (quizOpen) {
            rules.take(3).firstOrNull { it.pattern.containsMatchIn(text) && words <= it.maxWords }?.let { it.run(); return true }
            quiz("answer:$text"); return true
        }
        if (System.currentTimeMillis() - lastQuizEnd < 30_000 && Regex("^\\W*(yes|yeah|yep|sure|another( one)?|again|one more|ok(ay)?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) && words <= 4) {
            startQuiz(lastQuizKind); return true
        }
        val rule = rules.firstOrNull { it.pattern.containsMatchIn(text) && words <= it.maxWords } ?: return false
        rule.run()
        return true
    }

    private fun companion(cmd: String, line: String, needsOut: Boolean) {
        val player = Minecraft.getInstance().player ?: return
        when (cmd) { "follow", "stay" -> Tricks.used("follow"); "glow_on" -> Tricks.used("light"); "hide" -> Tricks.used("hide") }
        val buddy = CompanionClient.mine(player)
        if (needsOut && buddy == null) { reply("I'm inside the Dex right now! Drop me out first, then ask again."); return }
        if (!canSend(CompanionCommandPayload.TYPE)) { reply("Hmm, I can't do that in this world."); return }
        if (cmd == "giveup" || (cmd == "hide" && buddy?.mode == DexBuddyEntity.Mode.HIDING)) { /* handled server-side */ }
        if (cmd == "spin") lastSpinAt = System.currentTimeMillis()
        ClientPlayNetworking.send(CompanionCommandPayload(cmd))
        reply(line)
    }

    private fun startQuiz(kind: String) {
        if (!canSend(QuizActionPayload.TYPE)) { reply("My quiz chip needs this world's Dex network. Can't play here, sorry!"); return }
        Tricks.used("quiz")
        lastQuizKind = kind
        ClientPlayNetworking.send(QuizActionPayload("start:$kind"))
    }

    private fun quiz(action: String) {
        if (!canSend(QuizActionPayload.TYPE)) return
        // Giving up on hide and seek, when no quiz is running.
        if (action == "giveup" && !quizOpen) {
            val player = Minecraft.getInstance().player
            val buddy = player?.let { CompanionClient.mine(it, 96.0) }
            if (buddy?.mode == DexBuddyEntity.Mode.HIDING) {
                ClientPlayNetworking.send(CompanionCommandPayload("giveup"))
                reply("Hehe, I win! Here I come!")
                return
            }
        }
        ClientPlayNetworking.send(QuizActionPayload(action))
    }

    /** A line from the server's quiz. */
    fun onQuizLine(line: String, open: Boolean) {
        if (quizOpen && !open) lastQuizEnd = System.currentTimeMillis()
        quizOpen = open
        // The server doesn't have display names: it sends {ability:id} for this client to translate.
        val shown = Regex("\\{ability:([a-z0-9_]+)}").replace(line) { m ->
            com.cobblemon.mod.common.api.abilities.Abilities.get(m.groupValues[1])
                ?.let { net.minecraft.client.resources.language.I18n.get(it.displayName) } ?: m.groupValues[1]
        }
        reply(shown)
    }

    // ---- music ---------------------------------------------------------------------------------------

    private fun music(op: String) {
        val m = dev.dexbuddy.client.music.MusicClient
        val s = dev.dexbuddy.client.BuddyConfig.settings.music
        Tricks.used("music")
        when (op) {
            "stop" -> if (m.current == null) reply("Nothing's playing!") else { m.stop(); reply("Music off!") }
            "pause" -> if (!m.isPlaying) reply("It's already paused.") else { m.togglePause(); reply("Paused!") }
            "resume" -> if (m.current == null) reply("Nothing to resume. Ask me to play something!") else { if (!m.isPlaying) m.togglePause(); reply("Here we go!") }
            "skip" -> if (m.current == null) reply("Nothing's playing!") else if (m.queue.isEmpty()) { m.stop(); reply("That was the last song!") } else { m.skip(); reply("Next one: ${m.current?.title}!") }
            "up", "down" -> {
                s.volume = (s.volume + if (op == "up") 0.15 else -0.15).coerceIn(0.05, 1.0); dev.dexbuddy.client.BuddyConfig.save()
                reply(if (op == "up") "Louder!" else "A bit softer.")
            }
            "what" -> {
                val me = Minecraft.getInstance().player?.uuid
                val cur = m.current
                val other = m.sessions.values.firstOrNull { it.owner != me }
                reply(when {
                    cur != null -> "This is ${cur.title}" + if (cur.author.isNotBlank()) ", by ${cur.author}." else "."
                    other != null -> "${other.name}'s Dex is playing ${other.title}."
                    else -> "Nothing's playing right now."
                })
            }
            "play" -> {
                val t = lastText.orEmpty()
                val queueIt = Regex("\\b(queue|add|next|after this)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) && m.current != null
                var q = t.replace(Regex("^\\W*(?:can you |could you |would you |please |ok(?:ay)? |hey )*(?:play|put on|queue( up)?|add)\\s+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*\\b(please|for me|for us|on youtube|on your dex|to (the )?queue|next|after this( one| song)?)\\b\\s*", RegexOption.IGNORE_CASE), " ")
                    .trim().trimEnd('.', '!', '?')
                if (Regex("^(some |a )?(music|songs?|tunes|something)$", RegexOption.IGNORE_CASE).matches(q)) q = "chill lofi music"
                if (q.isBlank()) { reply("What should I play?"); return }
                reply(if (queueIt) "Adding it to the queue!" else "Ooh, finding it!")
                m.play(q, queueIt)
            }
        }
    }

    private fun canSend(type: net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<*>) =
        try { ClientPlayNetworking.canSend(type) } catch (_: Exception) { false }

    /** Speaks right away (an answer to the Trainer, not an unprompted remark). */
    fun reply(line: String) {
        Mood.noteActivity()
        val shown = line.replace(Regex("^\\s*Bzzt[!.]*\\s*", RegexOption.IGNORE_CASE), "").ifBlank { line }
        // Only a reply to a question typed to it (phone chat or "@dex") goes into the phone's chat.
        dev.dexbuddy.client.phone.VoltyChat.answer(line, dev.dexbuddy.client.phone.VoltyChat.syncSource)
        Tts.beginReply(System.nanoTime(), null)
        if (!Tts.say(line) { Subtitles.push(shown) }) Subtitles.push(shown)
    }
}
