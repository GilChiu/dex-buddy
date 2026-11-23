package dev.dexbuddy.client.antics

import dev.dexbuddy.client.BuddyConfig

/**
 * The kinds of things the companion says on its own, grouped so each group can be switched off from
 * the phone's Settings page. Answers to your own questions are never muted.
 */
enum class SpeakCategory(val id: String, val label: String, val kinds: Set<String>) {
    SOCIAL("social", "Likes, comments, friend requests", setOf("social")),
    MESSAGES("messages", "New messages from friends", setOf("message")),
    NIGHT("night", "When night falls", setOf("night")),
    WEATHER("weather", "Rain and thunder", setOf("weather")),
    BATTLE("battle", "In battle", setOf("battle_start", "b_super", "b_crit", "b_resist", "b_immune", "b_miss", "b_faint", "b_end")),
    TEAM("team", "Catches, level-ups, evolutions", setOf("caught", "evolved", "level", "fainted")),
    RARE("rare", "Shinies and legendaries", setOf("shiny", "legendary", "radar")),
    DANGER("danger", "Danger and getting hurt", setOf("died", "lowhp", "ouch", "fall", "creeper")),
    MOOD("mood", "Moods (bored, sleepy, scared)", setOf("mood")),
    GREET("greet", "Greeting other players", setOf("banter")),
    PLAY("play", "Petting, feeding and games", setOf("petted", "fed", "found", "hideseek", "punched", "friendship")),
    GIFTS("gifts", "Gifts and new costumes", setOf("gift", "costume")),
    TIPS("tips", "Tips about tricks I can do", setOf("tip"));

    val on: Boolean get() = id !in BuddyConfig.settings.personality.muted

    fun toggle() {
        val muted = BuddyConfig.settings.personality.muted
        if (id in muted) muted.remove(id) else muted.add(id)
        BuddyConfig.save()
    }

    companion object {
        fun of(kind: String): SpeakCategory? = entries.firstOrNull { kind in it.kinds }
        fun allowed(kind: String): Boolean = of(kind)?.on ?: true
    }
}
