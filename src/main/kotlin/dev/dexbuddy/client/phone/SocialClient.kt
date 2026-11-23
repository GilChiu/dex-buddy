package dev.dexbuddy.client.phone

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.net.SocialActionPayload
import dev.dexbuddy.net.SocialDataPayload
import dev.dexbuddy.net.SocialNoticePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player

/** The phone's view of the Dex social network (feed, friends, profile), kept fresh from the server. Client thread. */
object SocialClient {
    class Person(val uuid: String, val name: String, val avatar: String?, val online: Boolean)
    class Post(val id: String, val owner: String, val name: String, val avatar: String?, val caption: String, val time: Long, var likes: Int, var liked: Boolean,
               val comments: Int = 0)
    class Comment(val id: String, val name: String, val avatar: String?, val text: String, val time: Long, val canDelete: Boolean)
    class Thread(val with: Person, val last: String?, val time: Long, val unread: Int)
    class Message(val mine: Boolean, val text: String, val photo: String?, val time: Long)

    /** The post open on the phone, with its comments. */
    var openPost: Post? = null; private set
    var openPostId: String? = null; private set
    var comments: List<Comment> = emptyList(); private set
    var postGone = false; private set

    var threads: List<Thread> = emptyList(); private set
    var threadsLoaded = false; private set
    val unreadTotal: Int get() = threads.sumOf { it.unread }

    /** The chat open on the phone. */
    var chatWith: Person? = null; private set
    var chatId: String? = null; private set
    var messages: List<Message> = emptyList(); private set
    var chatLoaded = false; private set
    /** Bumped whenever the open chat gets new messages (the phone scrolls to the bottom). */
    var chatVersion = 0; private set

    fun viewPost(id: String) {
        if (openPostId != id) { openPost = feed.firstOrNull { it.id == id } ?: myPosts.firstOrNull { it.id == id }; comments = emptyList() }
        openPostId = id; postGone = false
        send("postview:$id")
    }

    fun openChat(p: Person) {
        if (chatId != p.uuid) { messages = emptyList(); chatLoaded = false }
        chatWith = p; chatId = p.uuid
        send("chat:${p.uuid}")
    }

    fun closeChat() { chatId = null; send("chats") }

    var feed: List<Post> = emptyList(); private set
    var feedLoaded = false; private set
    var friends: List<Person> = emptyList(); private set
    var incoming: List<Person> = emptyList(); private set
    var outgoing: List<Person> = emptyList(); private set
    var nearby: List<Person> = emptyList(); private set
    var friendsLoaded = false; private set
    var me: Person? = null; private set
    var myPosts: List<Post> = emptyList(); private set
    var friendCount = 0; private set
    var likeTotal = 0; private set
    var profileLoaded = false; private set

    /** Feed posts newer than this count as new (for the home screen's badge). */
    private var feedSeenAt = System.currentTimeMillis()
    /** Shown by an open phone too. */
    var onNotice: ((String) -> Unit)? = null

    val available: Boolean get() = try { ClientPlayNetworking.canSend(SocialActionPayload.TYPE) } catch (_: Exception) { false }

    fun send(action: String) { if (available) ClientPlayNetworking.send(SocialActionPayload(action.take(250))) }

    val newPosts: Int get() {
        val myId = Minecraft.getInstance().player?.uuid?.toString()
        return feed.count { it.time > feedSeenAt && it.owner != myId }
    }

    fun markFeedSeen() { feedSeenAt = System.currentTimeMillis() }

    fun isPosted(photoId: String) = myPosts.any { it.id == photoId }

    fun like(p: Post) {
        p.liked = !p.liked; p.likes += if (p.liked) 1 else -1 // right away; the server's reply confirms
        send("like:${p.id}")
    }

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(SocialDataPayload.TYPE) { p, _ -> onData(p.json) }
        ClientPlayNetworking.registerGlobalReceiver(SocialNoticePayload.TYPE) { p, _ ->
            dev.dexbuddy.client.antics.Speaker.say("social", p.text, 0, urgent = true)
            onNotice?.invoke(p.text)
            // Keep what's on the phone current.
            send("feed"); send("friends")
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            Minecraft.getInstance().execute {
                PhoneScreen.flashlightOn = false // the server turns it off when you leave
                feed = emptyList(); friends = emptyList(); incoming = emptyList(); outgoing = emptyList(); nearby = emptyList()
                myPosts = emptyList(); me = null; feedLoaded = false; friendsLoaded = false; profileLoaded = false
                openPost = null; openPostId = null; comments = emptyList()
                threads = emptyList(); threadsLoaded = false; chatWith = null; chatId = null; messages = emptyList(); chatLoaded = false
            }
        }
        // Sneak + right-click another player while holding your Dex: send them a friend request (or accept theirs).
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            val holdingDex = player.mainHandItem.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY) || player.offhandItem.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY)
            if (!level.isClientSide || hand != InteractionHand.MAIN_HAND || entity !is Player || entity == player || !player.isShiftKeyDown || !holdingDex || !available)
                return@register InteractionResult.PASS
            send("request:${entity.uuid}")
            dev.dexbuddy.client.antics.Tricks.used("friends")
            InteractionResult.SUCCESS
        }
    }

    private fun person(o: JsonObject) = Person(o.get("uuid").asString, o.get("name")?.asString ?: "?", o.get("avatar")?.asString, o.get("online")?.asBoolean ?: false)
    private fun post(o: JsonObject) = Post(o.get("id").asString, o.get("owner").asString, o.get("name")?.asString ?: "?", o.get("avatar")?.asString,
        o.get("caption")?.asString ?: "", o.get("time")?.asLong ?: 0L, o.get("likes")?.asInt ?: 0, o.get("liked")?.asBoolean ?: false,
        o.get("comments")?.asInt ?: 0)

    private fun onData(json: String) {
        try {
            val o = JsonParser.parseString(json).asJsonObject
            when (o.get("type")?.asString) {
                "feed" -> { feed = o.getAsJsonArray("posts").map { post(it.asJsonObject) }; feedLoaded = true }
                "friends" -> {
                    friends = o.getAsJsonArray("friends").map { person(it.asJsonObject) }
                    incoming = o.getAsJsonArray("incoming").map { person(it.asJsonObject) }
                    outgoing = o.getAsJsonArray("outgoing").map { person(it.asJsonObject) }
                    nearby = o.getAsJsonArray("nearby").map { person(it.asJsonObject) }
                    friendsLoaded = true
                }
                "profile" -> {
                    me = person(o.getAsJsonObject("me"))
                    friendCount = o.get("friends")?.asInt ?: 0
                    likeTotal = o.get("likes")?.asInt ?: 0
                    myPosts = o.getAsJsonArray("posts").map { post(it.asJsonObject) }
                    profileLoaded = true
                }
                "post" -> {
                    if (o.get("gone")?.asBoolean == true) { if (o.get("id")?.asString == openPostId) postGone = true; return }
                    val p = post(o.getAsJsonObject("post"))
                    if (p.id != openPostId) return
                    openPost = p
                    comments = o.getAsJsonArray("comments").map { e -> val c = e.asJsonObject
                        Comment(c.get("id").asString, c.get("name")?.asString ?: "?", c.get("avatar")?.asString, c.get("text")?.asString ?: "",
                            c.get("time")?.asLong ?: 0L, c.get("canDelete")?.asBoolean ?: false) }
                }
                "chats" -> {
                    threads = o.getAsJsonArray("threads").map { e -> val t = e.asJsonObject
                        Thread(person(t.getAsJsonObject("with")), t.get("last")?.asString, t.get("time")?.asLong ?: 0L, t.get("unread")?.asInt ?: 0) }
                    threadsLoaded = true
                }
                "chat" -> {
                    val with = person(o.getAsJsonObject("with"))
                    if (with.uuid != chatId) return
                    val before = messages.size
                    chatWith = with
                    messages = o.getAsJsonArray("messages").map { e -> val m = e.asJsonObject
                        Message(m.get("mine")?.asBoolean ?: false, m.get("text")?.asString ?: "", m.get("photo")?.asString, m.get("time")?.asLong ?: 0L) }
                    if (!chatLoaded || messages.size != before) chatVersion++
                    chatLoaded = true
                }
                "message" -> {
                    // A friend texted you: refresh, and let your companion tell you (unless you're reading that chat).
                    val from = o.get("from")?.asString
                    val name = o.get("name")?.asString ?: "A friend"
                    send("chats")
                    if (from != null && from == chatId && Minecraft.getInstance().screen is PhoneScreen) send("chat:$from")
                    else {
                        val line = if (o.get("photo")?.asBoolean == true) "$name sent you a photo!" else "$name sent you a message!"
                        dev.dexbuddy.client.antics.Speaker.say("message", line, 0, urgent = true)
                        onNotice?.invoke(line)
                        if (dev.dexbuddy.client.antics.SpeakCategory.of("message")?.on == false || !dev.dexbuddy.client.BuddyConfig.settings.personality.reactions)
                            Minecraft.getInstance().player?.displayClientMessage(net.minecraft.network.chat.Component.literal(line).withStyle(net.minecraft.ChatFormatting.AQUA), true)
                    }
                }
            }
        } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] bad social data: {}", e.toString()) }
    }
}
