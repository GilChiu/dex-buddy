package dev.dexbuddy.photo

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.net.SocialDataPayload
import dev.dexbuddy.net.SocialNoticePayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.util.UUID

/**
 * The Dex social network, kept on the server: friends (by request and accept), posts (a photo from
 * your gallery plus a caption), likes, and profile pictures. Friends see each other's posts; profile
 * pictures are visible to everyone. Stored in world/dexbuddy/social.json. Server thread.
 */
object SocialStore {
    private class Comment(val id: String, val author: UUID, val text: String, val time: Long)
    private class Post(val id: String, val owner: UUID, val caption: String, val time: Long, val likes: MutableSet<UUID> = HashSet(),
                       val comments: MutableList<Comment> = ArrayList())
    /** A text (or a photo, when [photo] is set) sent between two friends. */
    private class Msg(val from: UUID, val text: String, val photo: String?, val time: Long)

    /** Chats between two friends, keyed by both ids in order ("a|b"). */
    private val chats = HashMap<String, MutableList<Msg>>()
    /** When each player last opened each chat (for unread counts). */
    private val lastRead = HashMap<UUID, MutableMap<UUID, Long>>()
    /** Photos sent in chats: who sent them and who may now see them. Rebuilt from [chats]. */
    private val shared = HashMap<String, Pair<UUID, MutableSet<UUID>>>()
    /** Spam guard: when each player last commented or messaged. */
    private val lastWrite = HashMap<UUID, Long>()

    private val names = HashMap<UUID, String>()
    private val avatars = HashMap<UUID, String>()
    private val friends = HashMap<UUID, MutableSet<UUID>>()
    private val requests = HashMap<UUID, MutableSet<UUID>>() // to -> from
    private val posts = LinkedHashMap<String, Post>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var server: MinecraftServer? = null

    private const val MAX_CAPTION = 80
    private const val MAX_COMMENT = 120
    private const val MAX_MSG = 200
    private const val MAX_COMMENTS = 60
    private const val MAX_THREAD = 200
    private const val FIND_RANGE = 24.0
    private val ID = Regex("^[a-f0-9-]{36}$")

    private fun chatKey(a: UUID, b: UUID) = if (a.toString() < b.toString()) "$a|$b" else "$b|$a"
    private fun clean(s: String, max: Int) = s.replace('§', ' ').replace(Regex("[\\p{Cntrl}]"), " ").trim().take(max)

    /** At most one comment or message every 0.6 s per player. */
    private fun tooFast(me: UUID): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastWrite[me] ?: 0L) < 600) return true
        lastWrite[me] = now
        return false
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    fun load(s: MinecraftServer) {
        server = s
        names.clear(); avatars.clear(); friends.clear(); requests.clear(); posts.clear()
        chats.clear(); lastRead.clear(); shared.clear(); lastWrite.clear()
        val f = file(s)
        if (!Files.exists(f)) return
        try {
            val o = JsonParser.parseString(Files.readString(f)).asJsonObject
            o.getAsJsonObject("names")?.entrySet()?.forEach { (k, v) -> names[UUID.fromString(k)] = v.asString }
            o.getAsJsonObject("avatars")?.entrySet()?.forEach { (k, v) -> avatars[UUID.fromString(k)] = v.asString }
            o.getAsJsonObject("friends")?.entrySet()?.forEach { (k, v) -> friends[UUID.fromString(k)] = v.asJsonArray.map { UUID.fromString(it.asString) }.toMutableSet() }
            o.getAsJsonObject("requests")?.entrySet()?.forEach { (k, v) -> requests[UUID.fromString(k)] = v.asJsonArray.map { UUID.fromString(it.asString) }.toMutableSet() }
            o.getAsJsonArray("posts")?.forEach { e ->
                val p = e.asJsonObject
                val post = Post(p.get("id").asString, UUID.fromString(p.get("owner").asString), p.get("caption")?.asString ?: "", p.get("time").asLong)
                p.getAsJsonArray("likes")?.forEach { post.likes += UUID.fromString(it.asString) }
                p.getAsJsonArray("comments")?.forEach { c ->
                    val co = c.asJsonObject
                    post.comments += Comment(co.get("id").asString, UUID.fromString(co.get("author").asString), co.get("text")?.asString ?: "", co.get("time")?.asLong ?: 0L)
                }
                posts[post.id] = post
            }
            o.getAsJsonObject("chats")?.entrySet()?.forEach { (k, v) ->
                chats[k] = v.asJsonArray.map { e ->
                    val m = e.asJsonObject
                    Msg(UUID.fromString(m.get("from").asString), m.get("text")?.asString ?: "", m.get("photo")?.asString, m.get("time")?.asLong ?: 0L)
                }.toMutableList()
            }
            o.getAsJsonObject("read")?.entrySet()?.forEach { (k, v) ->
                lastRead[UUID.fromString(k)] = v.asJsonObject.entrySet().associate { (o2, t) -> UUID.fromString(o2) to t.asLong }.toMutableMap()
            }
            chats.forEach { (k, list) ->
                val (a, b) = k.split('|').map { UUID.fromString(it) }
                list.forEach { m -> m.photo?.let { sharePhoto(it, m.from, if (m.from == a) b else a) } }
            }
        } catch (e: Exception) { DexBuddyMod.LOG.warn("[dexbuddy] couldn't read social.json: {}", e.toString()) }
    }

    private fun file(s: MinecraftServer) = s.getWorldPath(LevelResource.ROOT).resolve("dexbuddy").resolve("social.json")

    private fun save() {
        val s = server ?: return
        val o = JsonObject()
        o.add("names", JsonObject().also { j -> names.forEach { (k, v) -> j.addProperty(k.toString(), v) } })
        o.add("avatars", JsonObject().also { j -> avatars.forEach { (k, v) -> j.addProperty(k.toString(), v) } })
        o.add("friends", JsonObject().also { j -> friends.forEach { (k, v) -> j.add(k.toString(), JsonArray().also { a -> v.forEach { a.add(it.toString()) } }) } })
        o.add("requests", JsonObject().also { j -> requests.forEach { (k, v) -> if (v.isNotEmpty()) j.add(k.toString(), JsonArray().also { a -> v.forEach { a.add(it.toString()) } }) } })
        o.add("posts", JsonArray().also { a -> posts.values.forEach { p ->
            a.add(JsonObject().apply {
                addProperty("id", p.id); addProperty("owner", p.owner.toString()); addProperty("caption", p.caption); addProperty("time", p.time)
                add("likes", JsonArray().also { l -> p.likes.forEach { l.add(it.toString()) } })
                add("comments", JsonArray().also { l -> p.comments.forEach { c -> l.add(JsonObject().apply {
                    addProperty("id", c.id); addProperty("author", c.author.toString()); addProperty("text", c.text); addProperty("time", c.time)
                }) } })
            })
        } })
        o.add("chats", JsonObject().also { j -> chats.forEach { (k, list) -> j.add(k, JsonArray().also { a -> list.forEach { m -> a.add(JsonObject().apply {
            addProperty("from", m.from.toString()); addProperty("text", m.text); m.photo?.let { addProperty("photo", it) }; addProperty("time", m.time)
        }) } }) } })
        o.add("read", JsonObject().also { j -> lastRead.forEach { (k, v) -> j.add(k.toString(), JsonObject().also { r -> v.forEach { (o2, t) -> r.addProperty(o2.toString(), t) } }) } })
        try {
            val f = file(s)
            Files.createDirectories(f.parent)
            Files.writeString(f, gson.toJson(o))
        } catch (e: Exception) { DexBuddyMod.LOG.warn("[dexbuddy] couldn't save social.json: {}", e.toString()) }
    }

    fun onJoin(player: ServerPlayer) {
        if (names[player.uuid] != player.gameProfile.name) { names[player.uuid] = player.gameProfile.name; save() }
        requests[player.uuid]?.takeIf { it.isNotEmpty() }?.let {
            notice(player, if (it.size == 1) "${name(it.first())} wants to be friends! Check your Friends app." else "You have ${it.size} friend requests waiting!")
        }
        val unread = friendsOf(player.uuid).sumOf { unreadFrom(player.uuid, it) }
        if (unread > 0) notice(player, if (unread == 1) "You have a new message! Check Messages." else "You have $unread new messages! Check Messages.")
    }

    private fun sharePhoto(photo: String, owner: UUID, to: UUID) {
        shared.getOrPut(photo) { owner to HashSet() }.second += to
    }

    private fun unreadFrom(me: UUID, other: UUID): Int {
        val seen = lastRead[me]?.get(other) ?: 0L
        return chats[chatKey(me, other)]?.count { it.from == other && it.time > seen } ?: 0
    }

    // ---- rules ----------------------------------------------------------------------------------------

    private fun name(id: UUID) = names[id] ?: "Someone"
    fun nameOf(id: UUID): String? = names[id]
    /** Who has already been told about a like (so toggling a like can't spam the owner). */
    private val likeNoticed = HashSet<String>()
    private fun friendsOf(id: UUID): MutableSet<UUID> = friends.getOrPut(id) { HashSet() }
    fun areFriends(a: UUID, b: UUID) = a == b || friendsOf(a).contains(b)
    fun friendCount(id: UUID) = friends[id]?.size ?: 0
    fun likesReceived(id: UUID) = posts.values.filter { it.owner == id }.sumOf { it.likes.size }

    /** Can [viewer] see the photo [photoId] owned by [owner]? Their own, a friend's post, or anyone's profile picture. */
    fun ownerIfVisible(viewer: UUID, photoId: String): UUID? {
        posts[photoId]?.let { if (areFriends(viewer, it.owner)) return it.owner }
        avatars.entries.firstOrNull { it.value == photoId }?.let { return it.key }
        shared[photoId]?.let { (owner, to) -> if (viewer == owner || viewer in to) return owner }
        return null
    }

    /** A photo was deleted from a gallery: its post and profile-picture use go too. */
    fun forgetPhoto(owner: UUID, photoId: String) {
        var changed = posts.remove(photoId) != null
        if (avatars[owner] == photoId) { avatars.remove(owner); changed = true }
        if (changed) save()
    }

    // ---- requests from the phone -------------------------------------------------------------------------

    fun handle(player: ServerPlayer, action: String) {
        val me = player.uuid
        val verb = action.substringBefore(':')
        val arg = action.substringAfter(':', "")
        // Logged without the text of comments and messages: those are private.
        if (verb !in setOf("feed", "friends", "profile", "chats", "chat", "postview")) DexBuddyMod.LOG.info("[dexbuddy] social {} {} from {}", verb, arg.substringBefore(':').take(36), player.gameProfile.name)
        when (verb) {
            "postview" -> sendPost(player, arg)
            "comment" -> {
                val p = posts[arg.substringBefore(':')] ?: return
                val text = clean(arg.substringAfter(':', ""), MAX_COMMENT)
                if (text.isEmpty() || !areFriends(me, p.owner) || tooFast(me)) { sendPost(player, p.id); return }
                p.comments += Comment(UUID.randomUUID().toString().take(8), me, text, System.currentTimeMillis())
                while (p.comments.size > MAX_COMMENTS) p.comments.removeAt(0)
                save()
                if (p.owner != me) online(p.owner)?.let { notice(it, "${player.gameProfile.name} commented on your photo!") }
                sendPost(player, p.id); sendFeed(player)
            }
            "uncomment" -> {
                val p = posts[arg.substringBefore(':')] ?: return
                val cid = arg.substringAfter(':', "")
                if (p.comments.removeIf { it.id == cid && (it.author == me || p.owner == me) }) save()
                sendPost(player, p.id)
            }
            "chats" -> sendChats(player)
            "chat" -> { val other = parse(arg) ?: return; sendChat(player, other) }
            "msg", "msgphoto" -> {
                val to = parse(arg.substringBefore(':')) ?: return
                val body = arg.substringAfter(':', "")
                if (to == me || !friendsOf(me).contains(to) || tooFast(me)) { sendChat(player, to); return }
                val msg = if (verb == "msg") {
                    val text = clean(body, MAX_MSG).ifEmpty { return }
                    Msg(me, text, null, System.currentTimeMillis())
                } else {
                    if (!ID.matches(body) || !ownsPhoto(player, body)) return
                    sharePhoto(body, me, to)
                    Msg(me, "", body, System.currentTimeMillis())
                }
                val list = chats.getOrPut(chatKey(me, to)) { ArrayList() }
                list += msg
                while (list.size > MAX_THREAD) list.removeAt(0)
                save()
                sendChat(player, to)
                online(to)?.let { send(it, JsonObject().apply {
                    addProperty("type", "message"); addProperty("from", me.toString()); addProperty("name", player.gameProfile.name)
                    addProperty("photo", msg.photo != null)
                }) }
            }
            "feed" -> sendFeed(player)
            "friends" -> sendFriends(player)
            "profile" -> sendProfile(player)
            "post" -> {
                val id = arg.substringBefore(':')
                val caption = clean(arg.substringAfter(':', ""), MAX_CAPTION)
                if (!ID.matches(id) || !ownsPhoto(player, id)) return
                if (posts[id] == null) {
                    posts[id] = Post(id, me, caption, System.currentTimeMillis())
                    save()
                    friendsOf(me).forEach { f -> online(f)?.let { notice(it, "${player.gameProfile.name} posted a new photo!") } }
                }
                sendProfile(player); sendFeed(player)
            }
            "unpost" -> { if (posts[arg]?.owner == me) { posts.remove(arg); save() }; sendProfile(player); sendFeed(player) }
            "like" -> {
                val p = posts[arg] ?: return
                if (!areFriends(me, p.owner)) return
                if (!p.likes.remove(me)) {
                    p.likes += me
                    if (p.owner != me && likeNoticed.add("${p.id}|$me")) online(p.owner)?.let { notice(it, "${player.gameProfile.name} liked your photo!") }
                }
                save(); sendFeed(player)
            }
            "avatar" -> { if (ID.matches(arg) && ownsPhoto(player, arg)) { avatars[me] = arg; save(); notice(player, "New profile picture! Looking good!") }; sendProfile(player) }
            "request" -> { val to = parse(arg) ?: return; if (dev.dexbuddy.RateLimit.allow(me, "friendreq", 5, 60_000)) request(player, to) else sendFriends(player) }
            "accept" -> { val from = parse(arg) ?: return; if (requests[me]?.remove(from) == true) befriend(me, from) ; sendFriends(player) }
            "decline" -> { parse(arg)?.let { requests[me]?.remove(it); save() }; sendFriends(player) }
            "unfriend" -> { parse(arg)?.let { friendsOf(me).remove(it); friendsOf(it).remove(me); save() }; sendFriends(player) }
        }
    }

    private fun parse(s: String) = try { UUID.fromString(s) } catch (_: Exception) { null }
    private fun online(id: UUID) = server?.playerList?.getPlayer(id)
    private fun ownsPhoto(player: ServerPlayer, id: String) = Files.exists(PhotoStore.dir(player.server, player.uuid).resolve("$id.jpg"))

    /** Sends a friend request (or accepts theirs, if they already asked). Also used by sneak + right-click. */
    fun request(player: ServerPlayer, to: UUID) {
        val me = player.uuid
        if (to == me || friendsOf(me).contains(to)) { sendFriends(player); return }
        // Only real Trainers: someone online now, or who has played here before.
        if (online(to) == null && !names.containsKey(to)) return
        if (requests[me]?.remove(to) == true) { befriend(me, to); sendFriends(player); return }
        if (requests.getOrPut(to) { HashSet() }.add(me)) {
            save()
            notice(player, "Friend request sent to ${name(to)}!")
            online(to)?.let { notice(it, "${player.gameProfile.name} wants to be friends! Check your Friends app.") }
        }
        sendFriends(player)
    }

    private fun befriend(a: UUID, b: UUID) {
        DexBuddyMod.LOG.info("[dexbuddy] {} and {} are now friends", name(a), name(b))
        friendsOf(a) += b; friendsOf(b) += a
        requests[b]?.remove(a)
        save()
        online(a)?.let { notice(it, "You and ${name(b)} are friends now!") }
        online(b)?.let { notice(it, "You and ${name(a)} are friends now!") }
    }

    // ---- replies --------------------------------------------------------------------------------------

    private fun person(id: UUID) = JsonObject().apply {
        addProperty("uuid", id.toString()); addProperty("name", name(id))
        avatars[id]?.let { addProperty("avatar", it) }
        addProperty("online", online(id) != null)
    }

    private fun postJson(p: Post, viewer: UUID) = JsonObject().apply {
        addProperty("id", p.id); addProperty("owner", p.owner.toString()); addProperty("name", name(p.owner))
        avatars[p.owner]?.let { addProperty("avatar", it) }
        addProperty("caption", p.caption); addProperty("time", p.time)
        addProperty("likes", p.likes.size); addProperty("liked", p.likes.contains(viewer))
        addProperty("comments", p.comments.size)
    }

    /** One post with all its comments (friends only). */
    fun sendPost(player: ServerPlayer, id: String) {
        val me = player.uuid
        val p = posts[id]
        if (p == null || !areFriends(me, p.owner)) {
            send(player, JsonObject().apply { addProperty("type", "post"); addProperty("id", id); addProperty("gone", true) }); return
        }
        send(player, JsonObject().apply {
            addProperty("type", "post")
            add("post", postJson(p, me))
            add("comments", JsonArray().also { a -> p.comments.forEach { c -> a.add(JsonObject().apply {
                addProperty("id", c.id); addProperty("author", c.author.toString()); addProperty("name", name(c.author))
                avatars[c.author]?.let { addProperty("avatar", it) }
                addProperty("text", c.text); addProperty("time", c.time)
                addProperty("mine", c.author == me); addProperty("canDelete", c.author == me || p.owner == me)
            }) } })
        })
    }

    /** Your chats: every friend, the ones you've talked with most recently first. */
    fun sendChats(player: ServerPlayer) {
        val me = player.uuid
        val rows = friendsOf(me).map { f -> f to chats[chatKey(me, f)]?.lastOrNull() }
            .sortedWith(compareByDescending<Pair<UUID, Msg?>> { it.second?.time ?: 0L }.thenBy { name(it.first).lowercase() })
        send(player, JsonObject().apply {
            addProperty("type", "chats")
            add("threads", JsonArray().also { a -> rows.forEach { (f, last) -> a.add(JsonObject().apply {
                add("with", person(f))
                last?.let {
                    addProperty("last", if (it.photo != null) (if (it.from == me) "You sent a photo" else "Sent you a photo") else (if (it.from == me) "You: " else "") + it.text)
                    addProperty("time", it.time)
                }
                addProperty("unread", unreadFrom(me, f))
            }) } })
        })
    }

    /** One chat (the latest 100 messages); opening it marks it read. */
    fun sendChat(player: ServerPlayer, other: UUID) {
        val me = player.uuid
        if (!friendsOf(me).contains(other)) return
        val list = chats[chatKey(me, other)].orEmpty().takeLast(100)
        if (unreadFrom(me, other) > 0) { lastRead.getOrPut(me) { HashMap() }[other] = System.currentTimeMillis(); save() }
        send(player, JsonObject().apply {
            addProperty("type", "chat")
            add("with", person(other))
            add("messages", JsonArray().also { a -> list.forEach { m -> a.add(JsonObject().apply {
                addProperty("mine", m.from == me); addProperty("text", m.text); m.photo?.let { addProperty("photo", it) }; addProperty("time", m.time)
            }) } })
        })
    }

    private fun send(player: ServerPlayer, o: JsonObject) = ServerPlayNetworking.send(player, SocialDataPayload(o.toString()))

    fun sendFeed(player: ServerPlayer) {
        val me = player.uuid
        val list = posts.values.filter { areFriends(me, it.owner) }.sortedByDescending { it.time }.take(60)
        send(player, JsonObject().apply { addProperty("type", "feed"); add("posts", JsonArray().also { a -> list.forEach { a.add(postJson(it, me)) } }) })
    }

    fun sendFriends(player: ServerPlayer) {
        val me = player.uuid
        val nearby = player.serverLevel().players().filter { it.uuid != me && it.distanceTo(player) <= FIND_RANGE && !friendsOf(me).contains(it.uuid) }
        send(player, JsonObject().apply {
            addProperty("type", "friends")
            add("friends", JsonArray().also { a -> friendsOf(me).sortedBy { name(it).lowercase() }.forEach { a.add(person(it)) } })
            add("incoming", JsonArray().also { a -> requests[me].orEmpty().forEach { a.add(person(it)) } })
            add("outgoing", JsonArray().also { a -> requests.filter { me in it.value }.keys.forEach { a.add(person(it)) } })
            add("nearby", JsonArray().also { a -> nearby.forEach { names[it.uuid] = it.gameProfile.name; a.add(person(it.uuid)) } })
        })
    }

    fun sendProfile(player: ServerPlayer) {
        val me = player.uuid
        val mine = posts.values.filter { it.owner == me }.sortedByDescending { it.time }
        send(player, JsonObject().apply {
            addProperty("type", "profile")
            add("me", person(me))
            addProperty("friends", friendsOf(me).size)
            addProperty("likes", mine.sumOf { it.likes.size })
            add("posts", JsonArray().also { a -> mine.forEach { a.add(postJson(it, me)) } })
        })
    }

    private fun notice(player: ServerPlayer, text: String) = ServerPlayNetworking.send(player, SocialNoticePayload(text))
}
