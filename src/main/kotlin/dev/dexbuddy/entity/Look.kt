package dev.dexbuddy.entity

/**
 * The companion's body styles. Stored by [id] (in the item and the world save), synced by ordinal, and
 * unlocked as a bit per ordinal, so new looks only ever go at the end. [hint] is how to earn it.
 */
enum class Look(val id: String, val displayName: String, val spoken: List<String>, val hint: String = "") {
    PHONE("phone", "Phone Dex", listOf("phone", "phone dex", "smartphone")),
    CLASSIC("classic", "Classic Dex", listOf("classic", "classic dex", "original", "normal dex"), "Friendship 10"),
    FLIP("flip", "Flip Dex", listOf("flip", "flip dex", "retro", "kanto", "old school", "old dex"), "151 caught"),
    BALL("ball", "Dex Ball", listOf("ball", "dex ball", "pokeball", "poke ball", "poké ball"), "10 quiz streak"),
    PLUSH("plush", "Plush Dex", listOf("plush", "plush dex", "plushie", "plushy", "teddy", "cuddly"), "Friendship 50"),

    BUBBLEGUM("bubblegum_pop", "Bubblegum Pop", listOf("bubblegum", "bubble gum", "bubblegum pop"), "Friendship 20"),
    GLAM_STAR("glam_star", "Glam Star", listOf("glam", "glam star", "pop star", "popstar", "idol"), "Take 25 photos"),
    STRAWBERRY_MILK("strawberry_milk", "Strawberry Milk", listOf("strawberry", "strawberry milk"), "Feed 30 berries"),
    GOTH_BAT("goth_bat", "Goth Bat", listOf("goth bat", "bat", "goth"), "Catch 20 Ghost-types"),
    GOTHIC_LOLITA("gothic_lolita", "Gothic Lolita", listOf("lolita", "gothic lolita", "gothic"), "Rare find at night"),
    EMO_SCENE("emo_scene", "Emo Scene", listOf("emo", "scene", "emo scene"), "Visit the End"),
    PUNK_ROCKER("punk_rocker", "Punk Rocker", listOf("punk", "punk rocker", "mohawk"), "Win 50 battles"),
    ROCKSTAR("rockstar", "Rockstar", listOf("rockstar", "rock star", "rock"), "Get 50 likes"),
    METALHEAD("metalhead", "Metalhead", listOf("metalhead", "metal head", "metal"), "Visit the Nether"),
    KAWAII_CAT("kawaii_cat", "Kawaii Cat", listOf("cat", "kitty", "kitten", "kawaii cat"), "Pet 100 times"),
    BUNNY_HOP("bunny_hop", "Bunny Hop", listOf("bunny", "rabbit", "bunny hop"), "Find it 10 times"),
    CLOUD_DREAM("cloud_dream", "Cloud Dream", listOf("cloud", "cloud dream", "rainbow"), "Sleep 10 nights"),
    CYBER_NEON("cyber_neon", "Cyber Neon", listOf("cyber", "neon", "cyber neon", "robot"), "Charged 5 hours"),
    Y2K_FLIP("y2k_flip", "Y2K Flip", listOf("y2k", "y2k flip", "butterfly"), "Add 5 friends"),
    WITCHY("witchy", "Witchy", listOf("witch", "witchy"), "Catch 15 Psychic"),
    PUMPKIN_SPICE("pumpkin_spice", "Pumpkin Spice", listOf("pumpkin", "pumpkin spice"), "Out in October"),
    FROST_FAIRY("frost_fairy", "Frost Fairy", listOf("frost", "fairy", "frost fairy", "ice fairy"), "Catch 20 Ice-types"),
    SAKURA("sakura", "Sakura", listOf("sakura", "cherry blossom", "blossom"), "Out in spring"),
    PIXEL_GHOST("pixel_ghost", "Pixel Ghost", listOf("ghost", "pixel ghost"), "Found 5 times at night"),
    LOFI_CHILL("lofi_chill", "Lo-fi Chill", listOf("lofi", "lo-fi", "lo fi", "chill", "headphones"), "Music near it"),
    ;

    companion object {
        val DEFAULT = PHONE
        fun byId(id: String?): Look? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
        fun byOrdinal(i: Int): Look = entries.getOrElse(i) { DEFAULT }

        /** Finds a look named in what the Trainer said ("turn into the dex ball"). */
        fun fromSpeech(text: String): Look? {
            val t = text.lowercase()
            return entries.flatMap { l -> l.spoken.map { it to l } }
                .sortedByDescending { it.first.length }
                .firstOrNull { (word, _) -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(t) }?.second
        }
    }
}
