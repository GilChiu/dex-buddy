package dev.dexbuddy.client.antics

/** Everything the companion says on its own. {x} placeholders are filled in by Speaker.pick. */
object Lines {
    val shiny = listOf(
        "Bzzt! {trainer}, a SHINY {mon}! Look look look!", "Whoa whoa whoa, that {mon} is SHINY! Don't let it get away!",
        "My sensors are going crazy! Shiny {mon} nearby!", "Is that... a shiny {mon}?! This is the best day ever!",
    )
    val legendary = listOf(
        "Bzzt... {trainer}, my readings just spiked. That's {mon}! A legendary!", "No way. No WAY. {mon} is right here!",
        "Shh shh, stay calm... that's {mon}. Legendary Cobblemon, right in front of us!",
    )
    val caught = listOf(
        "You caught {mon}! Welcome to the team, {mon}!", "Yes! {mon} is ours! I'm adding it to the Dex right now!",
        "Gotcha! {mon} caught! You're getting really good at this!", "Another one for the collection! Hi {mon}, I'm {name}!",
    )
    val evolved = listOf(
        "{old} evolved into {mon}! I'm literally sparking right now!", "Whoa! {old} became {mon}! Look how strong it looks!",
        "Evolution! {mon}! I knew {old} could do it!",
    )
    val levelUp = listOf("{mon} grew to level {lv}! Keep it up!", "Level {lv}, {mon}! You're on fire!", "Ding! {mon} hit level {lv}!")
    val fainted = listOf("Oh no, {mon} fainted! Hang in there, buddy...", "{mon} is down! Let's get it to a healer soon.", "Nooo, {mon}! You did your best!")
    val ouch = listOf("Ouch! Are you okay?", "Hey! Watch out!", "Bzzt! That looked like it hurt!", "Yikes! Careful, {trainer}!")
    val lowHealth = listOf("{trainer}, your health is really low! Eat something, please!", "Bzzt bzzt! Low health alert! Let's be careful!", "I'm worried... maybe heal up before the next fight?")
    val died = listOf(
        "Nooo! {trainer}! ... Okay, okay, deep breaths. We respawn. We go again!", "Bzzt... that was... dramatic. Let's never do that again.",
        "And so our hero fell... but heroes always come back! Right? RIGHT?",
    )
    val bigFall = listOf("Whoa, that was a long drop! My circuits rattled!", "Wheee! ...Wait, was that on purpose?", "Next time, maybe the stairs?")
    val creeper = listOf("Creeper! Creeper! Run run run!", "Bzzt! Hissing sound! Move, {trainer}!", "That green thing is NOT friendly! Back away!")
    val rain = listOf("Ooh, rain! Water types love this weather!", "It's raining! Perfect weather for a nap in the Dex...")
    val thunder = listOf("Thunderstorm! I feel so charged up right now! Bzzt!", "Lightning! Hehe, it tickles my circuits!")
    val night = listOf("It's getting dark... Ghost types come out at night, you know.", "Nighttime! Different Cobblemon show up after dark!")

    // Battle commentary.
    val battleStartWild = listOf("A wild {mon}! Let's do this!", "Battle time! Show that {mon} what we've got!", "{mon} wants to battle! I'll cheer the loudest!")
    val battleStartTrainer = listOf("Trainer battle! Let's show them how it's done!", "It's on! I've got your back, {trainer}!")
    val superEffective = listOf("SUPER EFFECTIVE! Did you see that?!", "Bzzt! Right in the weak spot!", "That's what I'm talking about! Super effective!")
    val crit = listOf("CRITICAL HIT! Ooh, that's gotta sting!", "Critical! What a shot!", "Bullseye! Critical hit!")
    val resisted = listOf("Hmm, not very effective... maybe switch it up?", "That barely tickled. Try another type!")
    val immune = listOf("Immune?! It didn't do anything!", "Nope, no effect! Check the types!")
    val missed = listOf("Missed! So close!", "Whiff! Shake it off!")
    val enemyFainted = listOf("{mon} is down! Nice one!", "Down goes {mon}! You're unstoppable!")
    val allyFainted = listOf("Nooo, {mon}! Send in the next one!", "{mon} fainted... we've still got this!")
    val won = listOf("We WON! Victory dance! Bzzt bzzt!", "Winner winner! That was awesome, {trainer}!", "Too easy! ...Okay, it was a little hard. But we won!")
    val lost = listOf("We lost... it's okay. We'll train and come back stronger!", "Ugh, so close! Rematch later?")
    val fled = listOf("Phew, we got away!", "Strategic retreat! Totally on purpose.")

    // Radar.
    val radarHint = listOf("My radar's beeping! Something special is {where}!", "Signal's strong... {where}! Keep going!", "Getting close! It's {where}!")

    // Moods.
    val bored = listOf(
        "Soooo... are we doing anything? Bzzt.", "I counted every block around us. Twice. Can we go somewhere?",
        "Psst, {trainer}. Adventure? Exploring? Anything?", "I'm sooo bored I started naming the clouds.",
    )
    val sleepy = listOf("Yaaawn... it's late, {trainer}... zzz...", "Zzz... mmm... five more minutes... zzz...", "Zzz... no, Garchomp, that's MY berry... zzz...")
    val scared = listOf("Um, {trainer}? It's dark and there are monsters. Can we go home?", "Did you hear that? I definitely heard that.")
    val excited = listOf("I can feel it, something amazing is nearby!", "Bzzt bzzt! My sensors are tingling!")
}
