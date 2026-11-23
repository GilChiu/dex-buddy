package dev.dexbuddy.client.knowledge

import com.cobblemon.mod.common.api.abilities.AbilityTemplate
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.spawning.TimeRange
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.client.battle.MoveEffectivenessCalculator
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility
import com.cobblemon.mod.common.pokemon.evolution.variants.ItemInteractionEvolution
import net.minecraft.advancements.critereon.ItemPredicate
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import java.lang.reflect.Modifier

/**
 * Compact, token-cheap text cards built from Cobblemon's registries. Client thread only.
 *
 * Cobblemon syncs stats, types, abilities, learnsets, Dex text and forms to every client, but not
 * evolutions, catch rate or egg groups. Those are only real when this client hosts the world
 * (singleplayer / LAN); on a remote server they come from the server packet in a later phase.
 */
object DataCards {
    private val STAT_ORDER = listOf(
        Stats.HP to "HP", Stats.ATTACK to "Atk", Stats.DEFENCE to "Def",
        Stats.SPECIAL_ATTACK to "SpA", Stats.SPECIAL_DEFENCE to "SpD", Stats.SPEED to "Spe",
    )

    /** True when the server-only species data (evolutions, catch rate, egg groups) is loaded here. */
    fun hasFullSpeciesData(): Boolean = Minecraft.getInstance().hasSingleplayerServer()

    fun forHits(hits: List<NameMatcher.Hit>): String = hits.joinToString("\n") {
        when (it) {
            is NameMatcher.Hit.Pokemon -> (it.heardAs?.let { h ->
                "[SOUNDS LIKE] The Trainer said \"$h\", which sounds like ${speciesName(it.species)}. If their words don't make sense otherwise, this is what they meant.\n"
            } ?: "") + pokemon(it.species)
            is NameMatcher.Hit.Move -> move(it.move)
            is NameMatcher.Hit.Ability -> ability(it.ability)
            is NameMatcher.Hit.Item -> item(it.item)
        }
    }

    // ---- Pokémon ---------------------------------------------------------------------------------

    fun speciesName(s: Species): String = s.translatedName.string

    fun typesOf(s: Species): List<ElementalType> = s.types.toList()

    fun pokemon(s: Species, withLearnset: Boolean = false): String {
        val sb = StringBuilder()
        val types = typesOf(s)
        val stats = STAT_ORDER.joinToString(" ") { (st, label) -> "$label${s.baseStats[st] ?: 0}" }
        val bst = STAT_ORDER.sumOf { s.baseStats[it.first] ?: 0 }
        sb.append("[COBBLEMON] ${speciesName(s)} #${s.nationalPokedexNumber} | ")
            .append(types.joinToString("/") { typeName(it) })
            .append(" | $stats (BST$bst)")
        if (!s.implemented) sb.append(" | not yet found in this world (no spawns, cannot be obtained)")
        sb.append("\nAbilities: ").append(abilities(s))
        sb.append("\n").append(matchups(types))
        sb.append("\nHeight ${fmt(s.height / 10f)}m, weight ${fmt(s.weight / 10f)}kg")
        if (hasFullSpeciesData()) {
            sb.append(" | Catch rate ${s.catchRate} | Egg groups: ")
                .append(s.eggGroups.joinToString(", ") { pretty(it.name) }.ifEmpty { "none" })
            sb.append("\nEvo: ").append(evolutionLine(s))
        } else {
            sb.append("\nEvo, catch rate, egg groups: not in the Dex here yet")
        }
        val forms = s.forms.map { it.name }.filter { it.isNotBlank() && !it.equals("normal", true) }
        if (forms.isNotEmpty()) sb.append("\nForms: ").append(forms.joinToString(", "))
        dexEntry(s)?.let { sb.append("\nDex: \"").append(it).append('"') }
        sb.append("\nSource: ").append(s.resourceIdentifier.namespace)
        if (withLearnset) sb.append("\n").append(learnset(s))
        return sb.toString()
    }

    private fun abilities(s: Species): String {
        // Hidden abilities are their own PotentialAbility class; list regular ones first.
        val parts = s.abilities.sortedBy { it is HiddenAbility }.map {
            I18n.get(it.template.displayName) + if (it is HiddenAbility) "(H)" else ""
        }
        return parts.distinct().joinToString(", ").ifEmpty { "unknown" }
    }

    fun dexEntry(s: Species): String? =
        s.pokedex.firstOrNull()?.let { key -> if (I18n.exists(key)) I18n.get(key) else null }

    private fun learnset(s: Species): String {
        val ls = s.moves
        val level = ls.levelUpMoves.toSortedMap().entries.joinToString(", ") { (lvl, ms) ->
            ms.joinToString(", ") { "Lv$lvl ${it.displayName.string}" }
        }
        fun names(list: Collection<MoveTemplate>) = list.joinToString(", ") { it.displayName.string }.ifEmpty { "none" }
        return "Level-up: ${level.ifEmpty { "none" }}\nTM: ${names(ls.tmMoves)}\nEgg: ${names(ls.eggMoves)}\nTutor: ${names(ls.tutorMoves)}"
    }

    // ---- evolutions (server-only data; see hasFullSpeciesData) ------------------------------------

    fun evolutionLine(s: Species): String {
        val parts = mutableListOf<String>()
        s.preEvolution?.species?.let { parts += "evolves from ${speciesName(it)}" }
        for (evo in s.evolutions) parts += "-> ${resultName(evo)} (${evolutionMethod(evo)})"
        return parts.joinToString("; ").ifEmpty { "does not evolve" }
    }

    private fun resultName(evo: Evolution): String {
        val id = evo.result.species ?: return "?"
        val sp = PokemonSpecies.getByName(id.substringAfter(':')) ?: KnowledgeIndex.findSpecies(id)
        val form = evo.result.form?.takeIf { it.isNotBlank() }?.let { " ($it form)" } ?: ""
        return (sp?.let { speciesName(it) } ?: id) + form
    }

    private fun evolutionMethod(evo: Evolution): String {
        val parts = mutableListOf<String>()
        when (evo) {
            is ItemInteractionEvolution -> parts += "use ${itemPredicateName(evo.requiredContext)}"
            else -> parts += when (evo.javaClass.simpleName) {
                "LevelUpEvolution" -> "level up"
                "TradeEvolution" -> "trade"
                "BlockClickEvolution" -> "use on a block"
                else -> pretty(evo.javaClass.simpleName.removeSuffix("Evolution"))
            }
        }
        for (req in evo.requirements) describeRequirement(req)?.let { parts += it }
        return parts.joinToString(", ")
    }

    private fun itemPredicateName(p: ItemPredicate): String {
        val names = p.items().map { set -> set.stream().map { I18n.get(it.value().descriptionId) }.toList() }.orElse(emptyList())
        return names.joinToString(" or ").ifEmpty { "an item" }
    }

    /** Requirements are many small classes (addons add more), so describe any of them from its fields. */
    private fun describeRequirement(req: Any): String? {
        val name = pretty(req.javaClass.simpleName.removeSuffix("Requirement"))
        val fields = generateSequence<Class<*>>(req.javaClass) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }
            .mapNotNull { f ->
                val v = try { f.isAccessible = true; f.get(req) } catch (_: Throwable) { return@mapNotNull null }
                val shown = when (v) {
                    null -> null
                    is Int -> if (v == Int.MAX_VALUE || v == Int.MIN_VALUE) null else v.toString()
                    is Number, is Boolean, is String, is Enum<*> -> v.toString()
                    is net.minecraft.resources.ResourceLocation -> v.path
                    is ItemPredicate -> itemPredicateName(v)
                    is ElementalType -> typeName(v)
                    is TimeRange -> timeRangeName(v)
                    is Collection<*> -> if (v.isEmpty() || v.size > 6) null else v.joinToString("/")
                    else -> v.toString().takeIf { it.length <= 40 && !it.contains('@') }
                } ?: return@mapNotNull null
                "${pretty(f.name)} $shown"
            }.toList()
        return if (fields.isEmpty()) name else "$name: ${fields.joinToString(", ")}"
    }

    /** "day", "night", "dusk"... from Cobblemon's named ranges, else the tick ranges. */
    private fun timeRangeName(t: TimeRange): String {
        val ranges = t.ranges.map { it.first to it.last }
        TimeRange.timeRanges.entries.firstOrNull { (_, v) -> v === t || v.ranges.map { it.first to it.last } == ranges }
            ?.let { return it.key }
        return ranges.joinToString(" or ") { "ticks ${it.first}-${it.second}" }
    }

    // ---- types -------------------------------------------------------------------------------------

    fun typeName(t: ElementalType): String = t.displayName.string

    fun multiplier(attacking: ElementalType, defending: List<ElementalType>): Float =
        defending.fold(1f) { acc, d -> acc * MoveEffectivenessCalculator.getEffectiveness(attacking, d) }

    fun matchups(defending: List<ElementalType>): String {
        val weak = mutableListOf<String>()
        val resist = mutableListOf<String>()
        val immune = mutableListOf<String>()
        for (atk in ElementalTypes.all()) {
            val m = multiplier(atk, defending)
            when {
                m == 0f -> immune += typeName(atk)
                m > 1f -> weak += "${typeName(atk)} x${fmt(m)}"
                m < 1f -> resist += "${typeName(atk)} x${fmt(m)}"
            }
        }
        weak.sortByDescending { it.substringAfterLast('x').toFloatOrNull() ?: 0f }
        return "Weak: ${weak.joinToString(", ").ifEmpty { "none" }} | Resists: ${resist.joinToString(", ").ifEmpty { "none" }} | Immune: ${immune.joinToString(", ").ifEmpty { "none" }}"
    }

    fun findType(name: String): ElementalType? =
        ElementalTypes.all().firstOrNull { Names.normalize(it.name) == Names.normalize(name) || Names.normalize(typeName(it)) == Names.normalize(name) }

    // ---- moves / abilities / items -----------------------------------------------------------------

    fun move(m: MoveTemplate): String {
        val power = if (m.power > 0) "Power ${fmt(m.power.toFloat())}" else "no direct damage"
        val acc = if (m.accuracy <= 0 || m.accuracy > 100) "never misses" else "Acc ${fmt(m.accuracy.toFloat())}"
        val prio = if (m.priority != 0) " | Priority ${m.priority}" else ""
        return "[MOVE] ${m.displayName.string} | ${typeName(m.elementalType)} | ${m.damageCategory.displayName.string} | $power, $acc, PP ${m.pp}$prio\n\"${m.description.string}\""
    }

    fun ability(a: AbilityTemplate): String {
        val desc = if (I18n.exists(a.description)) I18n.get(a.description) else "no description"
        return "[ABILITY] ${I18n.get(a.displayName)}: \"$desc\""
    }

    fun item(e: KnowledgeIndex.ItemEntry): String {
        val tip = tooltip(e.item)
        return "[ITEM] ${e.name} (${e.id.namespace})" + if (tip.isNotEmpty()) ": \"$tip\"" else ""
    }

    private fun tooltip(item: Item): String {
        val lines = ArrayList<Component>()
        try {
            item.appendHoverText(ItemStack(item), Item.TooltipContext.of(Minecraft.getInstance().level), lines, TooltipFlag.NORMAL)
        } catch (_: Throwable) {
        }
        val key = item.descriptionId + ".tooltip"
        if (lines.isEmpty() && I18n.exists(key)) return I18n.get(key)
        return lines.joinToString(" ") { it.string }.trim()
    }

    // ---- helpers -----------------------------------------------------------------------------------

    fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(f).trimEnd('0').trimEnd('.')

    fun pretty(s: String): String =
        s.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}
