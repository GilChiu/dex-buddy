# Publishing Dex Buddy (CurseForge + Modrinth)

Everything you need to fill in the project pages. Paste, don't retype.

| File | Use it for |
|---|---|
| `summary.txt` | The one-line summary (both sites) |
| `description.md` | The project page body (both sites accept Markdown) |
| `icon-512.png` | Project icon / avatar |
| `../build/libs/dexbuddy-<version>.jar` | The file you upload (build it fresh first, see step 1) |

## Before you upload

1. **Build a fresh jar.** Run `gradlew build` in `C:\Users\Zared\Projects\dex-buddy`. Upload `build\libs\dexbuddy-0.1.0.jar`, not the `-sources` jar.
2. **Test with the official Cobblemon.** The mod was built and tested against the Cobblemon Academy pack's patched Cobblemon 1.7.3. Install the official Cobblemon 1.7.x from its own page in a clean instance and check it loads. Then try "Hey Dex", throwing the Dex, a photo and the phone.
3. **Remove your keys from anything you share.** Your own `config/dexbuddy.json` has your Groq key. Never upload or share it. The mod jar has no keys in it.
4. **Pick the version name.** For a first public build, use `0.1.0-beta`, or just `0.1.0` with the release type set to **Beta**.
5. **Take screenshots** for the gallery, in this order (best first):
   1. Volty floating beside you, with a subtitle showing.
   2. The Dex Phone home screen at night, then the same screen by day.
   3. A Volty photo: a wide shot of you in a nice spot.
   4. The Wardrobe page showing the five looks.
   5. The Feed with a friend's post and likes.
   6. A "Who's that Cobblemon?" silhouette.
   7. All five looks side by side.

## Modrinth

- **Name:** Dex Buddy
- **Summary:** paste `summary.txt`
- **Description:** paste `description.md`
- **Icon:** `icon-512.png`
- **License:** MIT
- **Categories:** Adventure, Utility, Social, Game Mechanics (pick the closest Modrinth offers)
- **Environment:** required on **both client and server**
- **Loader / version:** Fabric, Minecraft 1.21.1
- **Dependencies** (add each as *Required*): Fabric API, Fabric Language Kotlin, Cobblemon
- **Dependencies** (add as *Optional*): Simple Voice Chat
- **Links:** leave Source / Issues empty for now (add a Discord invite if you want reports there)
- **Content disclosures:** fill these honestly. Dex Buddy sends voice clips and questions to an AI service (Groq) after "Hey Dex", and uploads photos to the Minecraft server you play on. Modrinth's rules require clear disclosure of anything sent to a remote server, and `description.md` already has a *Privacy and data* section for this.

## CurseForge

- **Project name:** Dex Buddy
- **Summary:** paste `summary.txt`
- **Description:** paste `description.md` (switch the editor to Markdown first)
- **Avatar:** `icon-512.png` (it's original pixel art; avatars can't use copyrighted images)
- **Main category:** Mobs or Adventure and RPG; **extra categories:** Utility & QoL, Addons (Cobblemon, if offered)
- **License:** MIT
- **Game version:** 1.21.1, **Modloader:** Fabric
- **Relations** (*Required dependency*): Fabric API, Fabric Language Kotlin, Cobblemon
- **Relations** (*Optional dependency*): Simple Voice Chat
- **Release type:** Beta

## Wording rules for the pages

- Say **Cobblemon**, not the other franchise's name, and don't call the looks by franchise names.
- Keep the "not affiliated" line at the bottom of the description.

## Changelog for the first upload

> First public release: voice companion ("Hey Dex"), Volty companion with five looks, photos, the Dex Phone, friends and feed, server key mode.
