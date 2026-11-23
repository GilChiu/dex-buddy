# Dex Buddy

**Your Dex talks back.** Say *"Hey Dex"* and ask anything about your Cobblemon world: what that is, what it's weak to, where it spawns, what's around you right now. Your Dex answers out loud, in its own voice.

Then let it out. Your Dex becomes **Volty**, a little floating companion that follows you around, takes your picture, finds you gifts, quizzes you, and shares your photos with friends.

---

## Talk to your Dex

- **Hands-free:** say *"Hey Dex"* then your question, or hold a key if you prefer.
- **Knows your world:** types and weaknesses, abilities, moves, evolutions, and where any Cobblemon spawns. It also knows what's spawning near you, what you're looking at (even far away), and what's on your team.
- **Speaks back** with a cute voice (several to pick from), with subtitles.
- **Remembers** things you tell it.

## Your companion, Volty

- **Throw your Dex to let it out.** It floats beside you, follows you everywhere (other dimensions too), and comes back if it gets lost.
- **Name it** anything: *"Your name is Sparky"*, or use an anvil.
- **Pet it** (sneak + right-click) and **feed it** berries and redstone to grow your friendship.
- **Redstone charge:** a charged companion finds you gifts and gives your team **+10% battle EXP**.
- **Plays with you:** hide and seek, tricks, *"light the way"* in caves, and *"Who's that Cobblemon?"* quizzes where Volty turns into the mystery silhouette.
- **Reacts** to your battles, catches and shiny finds, and greets other Trainers.

## Five looks to collect

Phone Dex, Classic Dex, Flip Dex, Dex Ball and Plush Dex. Unlock them by growing your friendship, filling your Pokédex, winning quiz streaks, or finding them while your companion is charged. Once unlocked, swap any time: *"wear the plush!"*

## Take pictures together

- *"Take a picture of me"*: Volty flies out, frames you (**close-up, portrait or wide shot**), counts down and snaps the photo from its own eyes.
- Or use the camera yourself, with a viewfinder.
- Photos go to your **gallery**, and a copy is saved to your `screenshots/dexbuddy` folder.

## The Dex Phone

Right-click your Dex to open your phone. It follows the game's day and night, with Volty's face on the home screen.

- **Gallery** and **Wardrobe**
- **Feed:** your friends' posts, with likes
- **Friends:** requests, and players nearby to add
- **Profile:** your picture and your posts
- **Quiz**, plus a **flashlight** and **camera** shortcut

## Share with friends

Add friends (from the phone, or hold your Dex and sneak + right-click them), post your best shots with a caption, like each other's photos, and set a profile picture. Volty tells you when someone likes your photo or wants to be friends.

## One Dex, yours forever

Your Dex is linked to you. It never drops when you die, can't be taken by other players, and comes back if it gets lost. Craft a new one and it's the same Volty, with the same name, friendship and costumes.

---

## Requirements

- Minecraft **1.21.1** with **Fabric**
- **Cobblemon** 1.7.3 or newer
- **Fabric API** and **Fabric Language Kotlin**
- Install on **both the server and every player's game**.
- Optional: **Simple Voice Chat**. Your voice chat is muted while you talk to your Dex.

## Setup: the AI key (free)

Your Dex uses an AI service to understand you and answer. The default is **Groq**, which has a free tier.

**Option A: each player uses their own free key**
1. Make a free account at **console.groq.com** and create an API key.
2. Open `config/dexbuddy.json` and paste it into `"apiKey"` under `"llm"`.
3. In game, type `/dexbuddy reload`.

**Option B: the server shares one key (server key mode)**
The server owner puts a key in the server's `config/dexbuddy-server.json` and sets `"enabled": true`. Players without their own key then talk through the server. The key never leaves the server. See *For server owners* below.

**First launch:** Dex Buddy downloads its voice and its "Hey Dex" listener once, about 100 MB in total, from the projects' official release pages. The talk key works while that finishes.

## Commands

| Command | What it does |
|---|---|
| `/dexbuddy status` | Shows your setup (key, voice, microphone) |
| `/dexbuddy phone` | Opens the Dex Phone |
| `/dexbuddy name <name>` | Names your companion |
| `/dexbuddy look <look>` | Changes your companion's look (or lists your costumes) |
| `/dexbuddy voice ...` | Picks and tunes the voice |
| `/dexbuddy chatty <0-2>` | How often your companion talks on its own |
| `/dexbuddy mics` | Lists microphones |
| `/dexbuddy reload` | Reloads your config |
| `/dexbuddyadmin reset <player>` | (Operators) Gives a player a fresh companion |

## For server owners

- **Server config:** `config/dexbuddy-server.json`
  - `enabled`: share your key with players who don't have one (off by default)
  - `apiKey`: your key (never sent to players)
  - `chatPerMinute`, `voiceClipsPerMinute`: per-player limits
  - `maxTokens`: the longest reply a player can get
- **Saved data:** everything lives in your world folder under `dexbuddy/`.
  - Photos: `dexbuddy/photos/<player>/`
  - Friends, posts and likes: `dexbuddy/social.json`
  - Each player's companion is saved with the player.
- **Limits:** each player can keep up to 200 photos.

## Privacy and data

Please read this before installing.

- **Nothing is recorded until you call your Dex.** The "Hey Dex" listener runs entirely on your computer. Nothing is sent until it hears the wake phrase or you hold the talk key.
- **Your question goes to the AI service.** After "Hey Dex", that short voice clip goes to the AI service to be turned into text:
  - Groq by default, using your key or your server owner's key, through the Minecraft server.
  - What's sent with your question is the text plus the game details needed to answer: your team, nearby Cobblemon, your location and biome.
  - The voice that answers you is generated on your own computer.
- **Photos go to the server you're playing on.** Each photo is uploaded to that Minecraft server so you can see it in your gallery. Posts are visible only to your friends there. Profile pictures are visible to everyone on that server.
- **Your key stays with you.** It's stored in your config file and only sent to the AI service you set. A server owner's key never leaves the server.
- **Memory stays local.** Things your Dex remembers about you are kept in a file on your computer.

## Credits

- Offline wake-word listening: **Vosk** (Apache License 2.0), included in the mod.
- Offline voice: **Piper** (MIT License) and its public voice models, downloaded on first launch.
- AI answers and speech-to-text: **Groq** by default (any compatible service works).

Dex Buddy is a fan-made add-on for Cobblemon. It is not affiliated with or endorsed by the Cobblemon team, Mojang, or any other company.

**License:** MIT. Made by Zaredchiu.
