# SkyCraft fork features

Features this fork (InstructorGrindset/SkyCraft) adds on top of upstream SkyCraft 0.1.2. The base
game features (movement, blocks, digging, combat, skills) are described in the README and
[DESIGN.md](DESIGN.md). Every config file below lives in the Minecraft instance's `config` folder
and is written with defaults the first time it's needed; delete a file to get the new defaults
back.

Status: **Tested** means played and confirmed in game, **Built** means it compiles and passes unit
tests but hasn't been played yet.

## Feature map

Every feature can be switched off in `skycraft-features.txt` (restart Minecraft after editing).
Minecraft side paths are under `fabric/src/main/java/dev/skycraft/`, Skyrim side under `skse/src/`.

| Feature | Switch | What it does | Minecraft side | Skyrim side | Config | Log tag | Status |
|---|---|---|---|---|---|---|---|
| Kill drops | `kill_drops` | Skyrim animals you kill drop Minecraft meat, leather and bones | `loot/SkyLoot`, `loot/LootRules` | actor table | `skycraft-loot.txt` | `SkyCraft-loot` | Tested |
| Kill XP | `kill_xp` | Credited kills drop XP orbs, more for higher levels | `loot/KillXp`, `loot/SkyLoot` | actor table level | none | `SkyCraft-loot` | Built |
| Item conversion | `item_conversion` | Food, potions, ores, hides and salvaged gear you loot in Skyrim become Minecraft items, and so do torches, tools, kitchenware, Dwemer clutter, Hearthfire building materials, animal parts, alchemy reagents, fish and gems (`+` gives several items for one); clothing and jewelry rules wait, commented out, for a decision; quest items never convert. Full table in `docs/CONVERSIONS.md` | `loot/SkyBridge`, `loot/ItemRules` | `Loot.cpp` (container sink, Take) | `skycraft-items.txt` | `SkyCraft-loot` | Tested |
| Potions | `item_conversion` | Every Skyrim potion becomes a Minecraft potion with effects of its own, named like the Skyrim one (health red, magicka blue, stamina green), and every poison a splash potion: 57 alchemy effects map to Minecraft effects (Restore Health is Instant Health, magicka is Absorption, Barter is Hero of the Village, Smithing is Haste, Paralysis is Slowness V, ...), with level and seconds growing with the name's tier (Minor and Weak up to Ultimate, Grand and Prime). Cure Disease and Cure Poison give a milk bucket; the White Phial and the quest poisons stay. Rules write `minecraft:potion{haste:0-2:60-180 #rrggbb "Name"}`, or `minecraft:potion[healing]` for a vanilla potion, also for `splash_potion`, `lingering_potion` and `tipped_arrow`. Long names cut to 27 letters come back whole. Full table in `docs/CONVERSIONS.md` | `loot/ItemRules` ("Potions and poisons" section, `Effect`, `TIERS`, `LONG_NAMES`), `loot/SkyBridge` (potion contents, custom name) | `Loot.cpp` (VendorItemPotion, VendorItemPoison tags) | `skycraft-items.txt` | `SkyCraft-loot` | Built |
| Dungeon chests | `dungeon_chests` | First loot of each dungeon chest also rolls a Minecraft chest loot table | `loot/DungeonLoot` | `WorldState::Place` (dungeon flag), item event chest flag and container id | `skycraft-dungeons.txt`, `skycraft-opened-chests.txt` | `SkyCraft-loot` | Built |
| Merchants | `merchants` | Right click a Skyrim vendor with an empty hand for a villager trade screen; town prices, supply and demand, Speech XP | `trade/` (SkyTrade, TradeRules, Currency, Demand), `combat/SkyrimActorEntity.interact` | `WorldState::VendorMask`, `WorldState::Place` (town) | `skycraft-trades.txt`, `skycraft-towns.txt` | `SkyCraft-trade` | Built |
| Time sync | `time_sync` | Minecraft's clock follows Skyrim's game hour | `time/SkyTime` | SkyState game hour | none | `SkyCraft-time` | Built |
| Bed sleep | `bed_sleep` | A full night in a Minecraft bed passes the night in Skyrim (needs time sync for beds to work at night) | `time/SkyTime` | `WorldState::PassTime` | none | `SkyCraft-time` | Built |
| Deep mining | none | Bedrock 64 blocks under the land (however deep, under buried rocks, ore veins and cave mouths too; in interiors 64 under the floor above), ores by depth, deepslate layer. Ground under the land never opens into nothing: undug cells beside a hole are solid, old holes close as you pass, and a player who still ends up in the void, or put inside rock by Skyrim, goes back to safe open ground | `world/SkyDig`, `world/SkyGround`, `world/SafeSpot`, client `FallGuard` | `Dig.cpp`, `Collision.cpp` (the land over a deep tunnel), `DigPhysics.cpp` (no Skyrim land rescue for the player) | none | `SkyCraft` | Built |
| Deep creatures | `deep_creatures` | Skeevers, skeletons and draugr spawn in dark tunnels you dug, by depth | `deep/` (SkyDeep, DeepRules) | `Spawn.cpp` | `skycraft-deep.txt` | `SkyCraft-deep` | Built |
| Breach doors | `breach_doors` | A hidden antechamber under each dungeon entrance (12 blocks down for low levels, deeper for higher ones), walled in the dungeon's theme (Nordic, Dwemer, Falmer, mine, fort, lair, cave). Its walls need a good enough pickaxe (iron for Dwemer and lairs, stone for Nordic, Falmer and forts); its sealed iron door takes you into the real dungeon. Particles and sounds leak through the rock near it, its name shows when you break in, and the first breach of an uncleared dungeon has a loot chest | `breach/` (SkyBreach, BreachPlanner, BreachRoom, BreachStore, BreachBlocks), hooks in `world/SkyDig` and `world/SkyDigBlast` | breach table, `kEvEnterDungeon` | `skycraft-dungeons.txt` (chest tables), `skycraft-breaches.txt` in the world's save folder | `SkyCraft-breach` | Built |
| Click to activate | `click_to_activate` | Right click with nothing usable in hand activates the Skyrim door, container, NPC, item, furniture or lever under the crosshair, as G does. Minecraft goes first: the held item is used (blocks placed, food eaten, bows drawn, shields raised), Minecraft blocks and entities take the click, and an empty hand on a merchant still opens the trade screen. Only a fresh press counts, so holding right click never activates anything; at most one activation per 0.4 s | client source set: `client/ClickActivate`, `client/mixin/MinecraftUseMixin`; `combat/SkyrimActorEntity` (merchant vendor mask synced so the client sees the trade take the click) | `Input.cpp` (`UpdateClickActivate`, same activation as G), `kEvActivate` | none | `SkyCraft` | Built |
| Minecraft view | `minecraft_view` | In any dimension other than the Skyrim mirror (the Nether, Tamriel Wilds, Home, ...) the screen shows Minecraft's own world: Minecraft draws its world, hand and HUD and sends the whole picture (at most 1080 high, `-Dskycraft.viewMaxHeight`, Skyrim scales it up) to cover Skyrim's. Skyrim keeps running underneath and parks its player where they stood: held in place, god mode, nobody fighting them, HUD hidden, its world not drawn. Every Skyrim-coupled system stands down meanwhile (collision, dug ground, water, NPC stand-ins, loot and items, time, breaches, deep creatures, click to activate, Skyrim's teleports and look); keys and mouse go to Minecraft, which turns the player itself. Back in the mirror, Skyrim's player follows to wherever Minecraft's comes back in (Skyrim leads instead if its player died there or is in another Skyrim world). A portal back to a Skyrim place in another worldspace, an interior or far off moves Skyrim's parked player there first, then Minecraft's follows (Portal network). Dying over there kills the Skyrim player as anywhere. Debug: `/skyview` (host only) goes to the Nether, or any other dimension the world has, and back; `/skyview <dimension id>`, `/skyview mirror`. Off: other dimensions act like the mirror, as before | `SkyDimension` (the guard every system asks), client `MinecraftView`, `FrameExporter` (opaque frames), `SkyClient`, `InputBridge` (look), guards in the `mixin/` and `client/mixin/` classes, `combat/SkyCombat`, `net/SkyNet`, `breach/SkyBreach` | `View.cpp` (park, hand back), `Overlay.cpp` (full screen frame), `Input.cpp` (routing, `kInLook`), `Game.cpp` | `SkyCraft.ini` `[MinecraftView] bHideSkyrimWorld` | `SkyCraft`, `Minecraft view` in SkyCraft.log | Built |
| Gear sets | none | Skyrim Iron through Daedric weapons and armor, new materials, recipes, original textures | `gear/` (SkyGear, GearTiers), `mixin/SmithingMixin` | none | data in the jar | none | Built |
| Portal network | `portals` | Portals are cracks between places that resemble each other. An obsidian frame lit in Skyrim makes a real Nether portal, but vanilla Nether travel never happens, in the mirror or in any other world: a portal outside the network leads nowhere. A filled Greater, Grand or Black soul gem from Skyrim (bought or looted filled) becomes a Filled Soul Gem; used on a lit portal it becomes an Attuned Soul Gem, and used on a second lit portal it binds the two for good and is spent. After Minecraft's usual portal wait a bound portal takes the host to its partner behind a loading screen, like a load door, between worldspaces and interiors alike. Keys open a lit portal in Skyrim onto another world, building the partner portal at that world's arrival point (obsidian frame, solid floor, lava and water sealed off) and binding the pair; the key is spent and the partner leads back: a Daedra Heart opens onto the Nether, a Tonal Key (crafted from a dwarven metal ingot, redstone and an amethyst shard) onto Tamriel Wilds. A portal lit at the Time-Wound on the Throat of the World's summit opens one way onto Home when Skyrim's player carries an Elder Scroll. In Home the Far Lands' strata (past 12,550,821 on either axis, the crack too) drop Seamstone; the first one picked up is the ending (a title, a few lines, and a lit portal opens in the crack). Seamstone held to any lit portal opens a chest of every place you've been (the worlds' arrival points, every portal you stood at, bound or built); pick one and step in. It's never spent. Obsidian frames light in Tamriel Wilds and Home too. Unbound portals do nothing but say how to open them; breaking a frame unbinds its partner. Merchants sell obsidian dear (smiths, cheapest in Markarth, and general stores). See [Portals into the worlds](#portals-into-the-worlds) | `portal/` (SkyPortals, PortalStore, PortalItems, SoulGemItem, PortalKeyItem, PortalKeys, PortalPlan, PortalBuilder, TimeWound, Destinations, SeamMenu, Ending), `mixin/NetherPortalBlockMixin`, `mixin/BaseFireBlockMixin`, soul gem rules in `loot/ItemRules`, obsidian in `trade/TradeRules` | `Travel.cpp` (MoveTo_Impl with no target reference), `Loot.cpp` (soul gem tags), `WorldState.cpp` (`Carried`: the Elder Scrolls), `kEvPortalTravel`, `SkyState.carried` | `skycraft-portals.txt` in the world's save folder, `skycraft-items.txt` ("Soul gems" section), `skycraft-trades.txt` | `SkyCraft-portal` | Built |
| World spaces | none | Every Skyrim world (each worldspace and each interior cell) keeps its Minecraft blocks in a space of its own in the mirror, so what you build, dig or reveal in one interior never shows in another or in the sky over Tamriel. Tamriel's space is the origin (outdoor builds stay put); the others are 32,768 blocks apart on a spiral round it, in the order you first visit them. A world made before this sorts itself out once: other worlds' dug cells move to their spaces with the blocks in and against them (see [DESIGN](DESIGN.md) section 4) | `link/SkySpace`, `link/WorldSpaces`, `link/SkyLink` (every position crossing the link), `world/SkyCollision`, `world/SpaceMigration`, client `render/WorldExporter`, guests' copy via `net/SkyNet` | none (the protocol stays in each world's own terms); `kColClear` names the world, `Game.cpp` logs McState `spaceWorld` | `skycraft-spaces.txt` in the world's save folder | `SkyCraft-space` | Built |
| Worlds | none | Three dimensions beside the mirror world: the Nether, Tamriel Wilds and Home (Steve's seed, ending in the Far Lands), reached through the portal network. See [Worlds](#worlds) | `worlds/` (Worlds, SkyWorlds, HomeChunkGenerator, HomeTerrain, ClassicNoise, FarLands, SteveCamp, HomeCamp, WorldArrivals), `worlds/mixin/` | none | data in the jar | `SkyCraft-worlds` | Built |
| Companions | `companions` | Minecraft's taming rules on Skyrim's animals. A bone fed to a Skyrim dog or wolf tames it one time in three (hearts, or smoke), unless you hit it in the last 30 seconds; apples, wheat, sugar, carrots, golden carrots and golden apples tame a horse by temper, each meal standing for one mount attempt. A tamed dog or wolf becomes Skyrim's animal follower the way a stray dog joins (ally, teammate, follows the player through load doors, fights for them) and sits, as a freshly tamed wolf does; a tamed horse becomes the player's own, like a bought one. Right click a companion with an empty hand to make it sit or follow (sneak for a horse: a plain click rides it, Skyrim's way); its food heals it (meat and fish for dogs and wolves, horse food for horses). Skyrim's own animal followers (Meeko, Vigilance, stray and bought dogs, huskies), dog, wolf or horse teammates and owned horses count as tamed. Skyrim keeps one animal follower: the one you last told to follow has it, the others stay behind, still yours. Stand-ins are named "Meeko (tamed)"; a companion's death is told in chat. Kept in the SKSE co-save. See [Companions](#companions) | `companion/` (SkyCompanions, CompanionRules), `combat/SkyrimActorEntity` (actor flags synced, interact), `combat/SkyCombat` | `Companion.cpp`, `AnimalKinds.h` (which races are dogs, wolves, horses), `CoSave.cpp` (co-save record `CMPN`), `Combat.cpp` (actor table bits, `kEvCompanion`) | none | `SkyCraft-companion` (both halves) | Built |

Shared pieces: `config/Config` reads and writes the config files, `config/Features` holds the
switches, `link/` is the shared memory link, and `WorldState.cpp` on the Skyrim side reports the
player's town and place and merchants' buy lists. Place changes are logged as `SkyCraft-place`.

## Worlds

Three dimensions sit beside the mirror world, for the Sygmension story ([LORE.md](LORE.md)). The portal
network leads into them ([Portals into the worlds](#portals-into-the-worlds)); `dev.skycraft.worlds.Worlds`
holds their keys, seeds and arrival points.

| World | Dimension | Seed | What's in it |
|---|---|---|---|
| The Nether | `minecraft:the_nether` | the world's | Vanilla Nether generator and biomes, fortresses and bastions |
| Tamriel Wilds | `skycraft:tamriel_wilds` | 4201 (4E 201), pinned | Skyrim one step away. Amplified height in the mountains, normal height on open land: tall jagged snowy ranges, broad flat valleys, sea cliffs. Granite cliffs in wavy bands of tuff, andesite and diorite. Ten biomes, two structures, mineshafts, ruined portals in the mountains |
| Home | `skycraft:home` | Steve's (3257840388504953787), pinned | Steve's old seed: classic terrain (octave noise hills, lakes, beaches, oaks) that breaks into the Far Lands at 12,550,821 on both axes. Steve's last camp and the crack |

**Tamriel Wilds biomes** (`data/skycraft/worldgen/biome`): Whiterun Tundra (golden grass, dry grass,
andesite boulders, scattered pines, allium and bluet), Falkreath Pines (dense old growth spruce, ferns,
mossy boulders, moss and podzol), The Rift (autumn poplars in orange, red and yellow, birches, leaf litter,
rivers), Eastmarch Springs (basalt and sulfur ground, hot springs with sulfur rims and magma floors that
bubble, snowy pines, sulfur haze), The Reach (tuff and andesite crags, poppies and rose bushes, goats),
The Pale (snowfields, snowy spruce), Winterhold Peaks (every high peak, and the cold north: snow, packed
ice, ice), Solstheim Ash (grey ash plains, dark basalt and blackstone cliffs, huge mushroom groves on
mycelium, falling ash), Tamriel River and the Sea of Ghosts (cold ocean with ice floes and icebergs).
Each has its own grass, foliage, water, fog and sky colors. Above about y 195 every biome turns to snow.

**Structures**: Dwemer towers (waxed cut copper with weathered bands, copper grates, redstone lamps lit by
hidden redstone blocks, copper bulbs, a domed top with a lightning rod, a loot chest) in the Reach,
Eastmarch, Whiterun, the Rift and the Pale; Nordic barrows (grassy burial mounds with a stone doorway,
a crypt with a sarcophagus, urn, candles and a loot chest) in the holds. Single piece jigsaw structures;
templates in `data/skycraft/structure`.

**Steve's camp** (Home, centred on 12,550,700, 71, 0): a 5 by 5 dirt hut with a red bed, a furnace, a
crafting table, a chest of Beta era things (porkchops, bread, apples, torches, cobblestone, a stone pickaxe,
flint and steel, a compass, a clock) and a lectern holding the written book "Journal" by Steve, whose
pages are placeholders marked `[PLACEHOLDER]` (text in `SteveCamp.JOURNAL_PAGES`). Seven waxed signs
along the gravel trail count Day 2417 to Day 2423. The trail runs east on levelled ground to the wall,
where the crack (3 wide, 48 deep, open to the sky) cuts into the Far Lands. Arrival
(`Worlds.HOME_ARRIVAL`): 12,550,705.5, 71, 0.5, facing east at the wall.

**How they load into the existing SkyCraft world.** The dimensions are data in the mod's own data pack
(`data/minecraft/dimension/the_nether.json`, `data/skycraft/dimension/*.json`). When a world opens,
Minecraft 26.3 merges the data pack's dimensions with the ones saved in the world
(`WorldDimensions.bake`, called from `LevelStorageSource.getLevelDataAndDimensions`), so a world made
with the mirror preset (overworld only) gains the Nether, Tamriel Wilds and Home the next time it opens.
Nothing in the save changes; take the mod out and the dimensions go away (their region files stay under
`dimensions/`). The data pack must not define `minecraft:overworld`: a data pack dimension replaces the
saved one with the same key. The SkyCraft world is created with structures off and seed 0, so two mixins
in `worlds/mixin/` adjust these worlds only: `ServerLevelSeedMixin` gives Tamriel Wilds and Home their
lore seeds (terrain, structures and decoration all read `ServerLevel.getSeed`), and
`ChunkStatusTasksMixin`, `StructureManagerMixin` and `ChunkGeneratorLocateMixin` let structures generate
and be located in the Nether, Tamriel Wilds and Home. The mirror overworld keeps its settings. The world
border (default 29,999,984) reaches past the Far Lands.

Tested on a headless 26.3 server (Fabric Loader 0.19.5, Fabric API 0.161.0): a world created with the
mirror preset, seed 0 and structures off, reopened with the worlds code, reports seeds 0, 4201 and
3257840388504953787 for the overworld, Tamriel Wilds and Home, locates the Dwemer tower at 96, 288 and a
fortress in the Nether, and generates the camp with its chest, journal and signs.

**Visit** (cheats on):

```
/execute in skycraft:tamriel_wilds run tp @s 91.5 120 306.5 180 0
/execute in minecraft:the_nether run tp @s 0 70 0
/execute in skycraft:home run tp @s 12550705.5 71 0.5 -90 0
/execute in skycraft:tamriel_wilds run locate biome skycraft:the_rift
/execute in skycraft:tamriel_wilds run locate structure skycraft:dwemer_tower
```

**Preview without Skyrim.** `python3 tools/build_worlds_preview.py` writes `dist/skycraft-worlds-preview.zip`,
a data pack for a plain vanilla 26.3 world (no mod): the Nether and Tamriel Wilds with its biomes,
features and structures. Add it under Data Packs when creating a world (or put it in
`<world>/datapacks` before the first start) and use the commands above. Without the mod, Tamriel Wilds
uses the world seed; create the world with seed 4201 to see the same land as the mod. Home is Java code,
so it needs the mod: in any world running SkyCraft (the SkyCraft world, or the dev client from
`./gradlew runClient` in `fabric`), use the Home command.

**Editing.** Tamriel Wilds' JSON and both structure templates are written by
`python3 tools/gen_worlds_data.py --jar <minecraft-26.3-client.jar>` (the jar is in Prism's
`libraries/com/mojang/minecraft/26.3`); it covers biome colors, features, climate ranges, surface rules,
the terrain blend and the structure layouts. Edit the tables there and run it again. Home's
terrain is `worlds/HomeTerrain` (noise in `ClassicNoise`, the edge in `FarLands`), the camp layout
`worlds/SteveCamp`.

Things to know: the Skyrim coupled features stand down in these worlds (the Minecraft view, `SkyDimension`).
Vanilla Nether travel never happens in any of them: every Nether portal belongs to the portal network.

## Portals into the worlds

The portal network ([Portal network](#feature-map)) is the way into the Seed family's worlds and out again.
Everything is kept in the world's `skycraft-portals.txt` with the rest of the network and is under the
`portals` switch; travel into another world also needs `minecraft_view` on (with the view off those worlds
would act like the mirror). Only the host travels.

| Way in | Where it opens | Partner | Back |
|---|---|---|---|
| Daedra Heart on a lit portal in Skyrim (spent) | The Nether: a spot found once near 0, 0 between y 64 and 100 with solid ground and no lava, fire or water within two blocks (a pocket is carved at y 80 if none), recorded as `arrival minecraft:the_nether` | Built at the arrival, facing south | Step into the partner |
| Tonal Key on a lit portal in Skyrim (spent) | Tamriel Wilds: the surface a dozen blocks south of the Dwemer tower (`Worlds.TAMRIEL_WILDS_ARRIVAL`), facing north at it | Built at the arrival | Step into the partner |
| A lit portal at the Time-Wound with an Elder Scroll in Skyrim's inventory | Home: `Worlds.HOME_ARRIVAL` in front of Steve's camp | None: one way, the story ends there | Seamstone |
| Seamstone on any lit portal | Any place you've been: the arrival points of the worlds you've visited and every portal in the network | The portal itself, for one trip | Seamstone again |

**Partner portals.** A key used on an unbound portal in the mirror builds a 2 by 3 portal in an obsidian
frame just behind the world's arrival point (`PortalPlan.behind`), so you step out onto it facing the
arrival's way: it seals lava, water and fire within a block of the work, clears two rows of air in front,
gives them a floor (obsidian where the ground isn't solid) and lights the frame the way flint and steel
would (`PortalShape`). Each key makes its own pair: a second key builds the next partner five blocks along
from the first (`PortalPlan.sites`). The partner is recorded with its dimension (`<id> minecraft:the_nether
...` in the store) and bound to the portal in Skyrim like a soul gem pair; breaking either frame breaks the
bond, and the next key builds a new partner.

**The way back.** Stepping into a partner (or a Seamstone portal opened onto a Skyrim place) takes you back
to the portal in Skyrim. If Skyrim's parked player is in that portal's Skyrim world and within 64 blocks,
Minecraft moves its player into the mirror in front of it and Skyrim's follows (the Minecraft view's
handover: Minecraft leads). Otherwise Skyrim goes first: `kEvPortalTravel` moves the parked player to the
portal behind a loading screen (Whiterun's portal while the body waits in Riften, an interior, Solstheim),
and once SkyState shows them there Minecraft moves its player into the mirror at the same spot. Skyrim then
sees its player came back in another world than the one it parked them in and leads the handover, putting
Minecraft's player where its own already stands. Skyrim not arriving within 45 seconds calls the trip off.

**The Time-Wound.** Skyrim.esm's `MQ206TimeWoundRef` (0x000430A3) stands in Tamriel (0x3C) at 56044, -47760,
36055 Skyrim units, about 800.6, 515.1, 682.3 in Minecraft. A portal whose middle is within 16 blocks of it
(flat, and 16 up or down) opens one way onto Home while `SkyState.carried` has `kCarryElderScroll`: the
Skyrim plugin sets it once a second when the player's inventory holds an Elder Scroll (Skyrim.esm
`DA04ElderScroll` 0x02D513 and `DA04ElderScrollFurled` 0x048782; Dawnguard.esm `DLC1ElderScrollDragon`
0x0126DC, `DLC1ElderScrollSun` 0x011A13, `DLC1ElderScrollBlood` 0x0118F9). The scrolls are quest items, so
they never convert and stay in Skyrim; the scroll isn't used up. Without one, standing in the portal says
what it wants.

**Seamstone and the ending.** In Home, mining any block a pickaxe or shovel takes past 12,550,821 on either
axis (the Far Lands' strata, the crack in front of the camp too) drops a Seamstone for a player who has none.
The first Seamstone a player picks up is the ending: the title "Past the Far Lands", a line under it, a few
lines in chat (the story lines are `[PLACEHOLDER]` text in `portal/Ending`), and a lit portal opens in the
mouth of the crack facing the camp, so there's always a way out of Home. From then on Seamstone held to any
lit portal, anywhere, opens a chest (a vanilla chest screen, so the client needs nothing new) listing every
place you've been: the arrival points of Home, Tamriel Wilds and the Nether once you've visited them, and
every portal of the network (Skyrim places where you stood in a portal or bound one, partners, the crack),
at most 54. Click one, then step in within five minutes: the portal opens onto it for one trip. The stone
isn't spent.

**Lighting.** Vanilla lights Nether portals only in the overworld and the Nether. `BaseFireBlockMixin` lets
obsidian frames light in Tamriel Wilds and Home too, so Seamstone works on portals you build there. A
portal outside the network leads nowhere in every world (`NetherPortalBlockMixin`): vanilla would send it
into the mirror at scaled coordinates (from the Nether) or into the Nether (from the others).

Tested on a headless 26.3 server (Fabric Loader 0.19.5, Fabric API 0.161.0, the mirror world from the Worlds
test): the Nether arrival came out at 0.5, 76, 0.5; partner portals built and lit at it (and a second one
five blocks along), at the Dwemer tower (on Eastmarch sulfur at y 67) and in the crack, each with all 6
portal blocks, all 14 frame blocks, a solid floor under the exit and no lava, fire or water round it; fire
in an obsidian frame lit a portal in Tamriel Wilds and in Home; and a pig standing in a portal in the
Nether, Tamriel Wilds and Home stayed where it was (the network was asked and gave no destination).

**Try it** (cheats on, the host): in Skyrim's console `player.additem 0003AD5B 1` gives a Daedra heart
(Skyrim.esm `DaedraHeart`; it converts to a Minecraft Daedra Heart), or in Minecraft `/give @s
skycraft:daedra_heart`; `/give @s skycraft:tonal_key`; `/give @s skycraft:seamstone`. For the ending,
`player.additem 0002D513 1` in Skyrim gives the Elder Scroll (it stays in Skyrim) and `player.moveto 0009E096`
takes you to the summit (`MQ305ReturnToTamrielMarker`, nine blocks from the Time-Wound; the map marker
`000162C4` is twelve blocks off). Build and light the frame within 16 blocks of the wound.

## Companions

Minecraft's taming rules on Skyrim's animals, so a Minecraft player's habits work on Skyrim's creatures
(switch `companions`). Skyrim decides what each actor is and whose it is and says so in the actor table
(`AnimalKind` and the companion bits); Minecraft rolls the taming and sends `kEvCompanion`; Skyrim makes it so
with its own means and checks every request (the actor is alive, loaded, within 64 blocks, of the kind
Minecraft named, not essential, not summoned, nobody riding it).

**Which animals.** By race editor ID (`skse/src/AnimalKinds.h`, unit tested in `skse/tests/AnimalKindsTest.cpp`):
werewolves and werebears never; "horse" is a horse; "dog", "husky" and "hound" are dogs; "wolf" is a wolf.
So DogRace, DogCompanionRace, WolfRace, HorseRace, Dawnguard's huskies and death hounds are covered, and so are
most mods' races. Foxes, sabre cats, trolls and the like are not.

| You do | On | What happens (vanilla 26.3's numbers) |
|---|---|---|
| Right click with a bone | a dog or wolf that isn't yours | The bone is eaten; one time in three (`Wolf.tryToTame`) it's tamed: hearts, and "Wolf is tamed and sits". Otherwise smoke. One you hit in the last 30 seconds is angry and won't take it |
| Right click with wheat, sugar, an apple, a carrot, a golden carrot or a golden apple | a horse that isn't yours | Eaten; its temper rises as `AbstractHorse.handleEating` says (3, 3, 3, 3, 5, 10), then the meal counts as a mount attempt (`RunAroundLikeCrazyGoal`): tamed if a roll under 100 is below the temper (hearts, "is your horse now"), else it shies (smoke, angry neigh) and gains 5 temper. About five apples on average. Hay only heals a hurt horse. Temper is remembered per horse until Minecraft restarts |
| Right click with an empty hand | a dog, wolf or other animal companion | Sits (waits) or follows, by turns; the action bar says which |
| Sneak and right click with an empty hand | your horse | Stays or follows |
| Right click with an empty hand | your horse | Rides it: the click goes to Skyrim's activation (click to activate) and Skyrim takes the player while riding |
| Right click with its food | a hurt companion | Heals it: meat, fish or rabbit stew heal a dog or wolf twice their nutrition of a wolf's 40 health (cooked beef 40%); horse food heals as for a Minecraft horse of 22.5 health (hay 89%, an apple 13%). Nothing is eaten at full health |

**Skyrim's side** (`Companion.cpp`). A tamed dog or wolf becomes Skyrim's animal follower exactly as a stray dog
does when you ask it along: SkyCraft calls the vanilla `DialogueFollowerScript.SetAnimal` on the DialogueFollower
quest (found by editor ID), which ranks it an Ally of the player, makes it a teammate and puts it in the quest's
animal alias, whose packages follow the player, through load doors too, and fight beside them. Wild wolves also
stop their fight and are made no more than Aggressive (they never pick fights with neutral folk). Sitting is the
`WaitingForPlayer` actor value those packages read, set directly (the vanilla wait without its three-day timer: a
sitting Minecraft wolf waits as long as it takes). A tamed horse becomes the player's own (ownership, as a bought
horse) and keeps to its own ways (a stable's horse stays by its stable) until told to follow; following puts it in the
same animal slot. Skyrim keeps one animal follower: whichever companion you last told to follow has the slot, and the
one it took it from stays yours but is left behind (it goes about its own business near where it was, and shows as
sitting in Minecraft). What
Minecraft tamed is kept in the SKSE co-save (record `CMPN`, `CoSave.cpp`), so it's still tamed when that game is
loaded again; a game saved before this build has no record and nothing is tamed. Counted as tamed already: the
actor in the animal slot (Meeko, Vigilance, a stray, bought or Hearthfire dog, a husky), any dog, wolf or horse that
is the player's teammate, and horses the player owns. Barbas counts only while Skyrim makes him a teammate or he
holds the slot; on his own quest he is essential and his quest leads him, so a bone does nothing to him.

**In Minecraft** the stand-in of a companion is named "Meeko (tamed)" (stand-ins aren't drawn, so the name shows in
Minecraft's own messages and logs; a collar tint can't be shown, Skyrim draws the animal), the action bar answers
each order, hearts and smoke are Minecraft particles (drawn in Skyrim like any), and a companion's death is told in
chat, as Minecraft tells a pet's owner. Only the host tames and orders: a guest's clicks on the host's animals are
left alone.

**Riding.** Mounting already works while Minecraft drives the player: a right click on a horse with an empty hand
(or G) is Skyrim's activation, and once Skyrim's player is on a mount Skyrim takes the player ("Skyrim takes the
player (riding)" in SkyCraft.log): Skyrim's controls and camera ride the horse, and Minecraft picks the player up
again where they dismount. Riding your tamed horse is riding your own; riding an untamed one is stealing it, as in
Skyrim. Not played yet with this feature.

**Try it** (cheats on). Skyrim console: `player.placeatme 00023ABE` (a Wolf; `00023ABF` an Ice Wolf),
`player.placeatme 00023A92` (a Dog), `player.placeatme 00109487` (a Stray Dog), `player.placeatme 00109E3D` (a
stables horse, unowned when placed); `player.moveto 000D95EA` (Meeko, at his shack), `player.moveto 0009A7AB`
(Vigilance, Markarth stables). Minecraft chat: `/give @s minecraft:bone 16`, `/give @s minecraft:cooked_beef 8`,
`/give @s minecraft:apple 16`, `/give @s minecraft:golden_carrot 8`, `/give @s minecraft:hay_block 2`.

1. Place a wolf and let it come at you. Right click it with bones until hearts show. It stops fighting, sits, and
   both logs have a `SkyCraft-companion` line saying it's tamed. In the Skyrim console (click it): `getplayerteammate`
   is 1, `getav WaitingForPlayer` is 1, `getav Aggression` at most 1; `sqv DialogueFollower` shows it in the animal
   alias.
2. Right click it with an empty hand: "Wolf follows you"; walk off, through a load door: it follows. Again: it sits.
3. Hit a fresh wolf, then feed it a bone at once: nothing (angry for 30 seconds). Feed one to a Dog: the same odds.
4. Hurt your wolf (console, click it: `damageav health 50`), feed it cooked beef: health goes up 40% of its maximum
   (`getav health`, and the log line `fed ... health a -> b`). At full health beef stays in your hand.
5. `player.moveto 000D95EA`, talk to Meeko and ask him along: in Minecraft an empty-hand click sits him and makes him
   follow, by turns; his stand-in is "Meeko (tamed)". Tame a wolf now: the wolf takes the slot (sitting), Meeko
   stays yours but behind; tell Meeko to follow and the wolf is the one left behind.
6. Place a horse; feed it apples until hearts (about five). Skyrim's prompt over it no longer marks riding it a theft
   (no red text), and an empty-hand right click rides it. Sneak and right click: "follows you", and it follows on foot;
   again: "waits here".
7. Save, load the save: the tamed wolf and horse are still "(tamed)"; SkyCraft.log says how many the co-save had.
   Load a save from before the taming (or before this build): nothing is tamed, no errors.
8. Kill a tamed companion (console `kill`): Minecraft's chat says "Wolf died".
9. Set `companions = off` in `skycraft-features.txt` and restart Minecraft: bones and empty hands do what they did
   before (Skyrim's activation), and no stand-in is named "(tamed)".

Headless: `SKYCRAFT_COMPANION=1 python tools/fake_skyrim.py` adds a wild wolf five blocks west of the start and
answers `kEvCompanion` as Skyrim would (tamed and sitting, follow and wait, healing).

## Planned

| Feature | Notes |
|---|---|
| Farming on Skyrim soil | May already work with dug dirt; needs a test first |
| Artifacts | About 20 named Skyrim artifacts as unique Minecraft items with real enchantments |

## Protocol 13

The shared memory protocol went from 12 to 13 for these features. Both halves must match.

| Addition | Direction | Used by |
|---|---|---|
| `SkyState.town`, `SkyState.place` (PlaceFlags) | Skyrim to Minecraft | Merchants, dungeon chests |
| `ActorRecord.vendor` (VendorBuys) | Skyrim to Minecraft | Merchants |
| `ActorFlags::kActorSpawned` | Skyrim to Minecraft | Deep creatures cap |
| `ItemEvent.chest`, `ItemEvent.container` | Skyrim to Minecraft | Dungeon chests |
| `ItemEvent.quest` | Skyrim to Minecraft | Item conversion skips quest items |
| `kEvSleep` | Minecraft to Skyrim | Bed sleep |
| `kEvSpawnCreature` | Minecraft to Skyrim | Deep creatures |
| `BreachTable` | Skyrim to Minecraft | Breach doors |
| `kEvEnterDungeon` | Minecraft to Skyrim | Breach doors |
| `kEvActivate` (10) | Minecraft to Skyrim | Click to activate. Added within 13 without a version bump: an older Skyrim plugin ignores the event type |
| `kEvPortalTravel` (11), `PortalTravelFlags` | Minecraft to Skyrim | Portal network: formId = destination worldspace, or interior cell with `kPortalInterior`; a/b/c = feet (MC coords), d = yaw, weapon = the world it was sent from (stale requests dropped). Added within 13 |
| `ItemTags` `kTagSoulGem`, `kTagSoulFilled` (bits 17, 18) | Skyrim to Minecraft | Portal network: soul gems that come filled convert to Filled Soul Gems. Added within 13; an older plugin never sets them |
| `SkyState.carried` (offset 0x64, was `reserved`), `CarriedFlags` `kCarryElderScroll` (bit 0) | Skyrim to Minecraft | Portal network: the player's inventory holds an Elder Scroll (a quest item that never converts), so the portal at the Time-Wound opens onto Home. Rechecked once a second. Added within 13: the field was reserved and always 0, so an older plugin reads as "carries nothing" and an older Minecraft ignores it |

The Minecraft view added these within 13, without a version bump (an older half ignores them):

| Addition | Direction | Used by |
|---|---|---|
| `McFlags::kMcOtherDimension` (bit 8) | Minecraft to Skyrim | The player is in a dimension other than the mirror. Skyrim parks its player while it's set and takes Minecraft's position on the falling edge |
| `OverlayFlags::kOverlayOpaque` (`OverlaySlotHdr.flags` bit 1) | Minecraft to Skyrim | The frame is Minecraft's whole picture, drawn full screen with its alpha ignored |
| `kInLook` (9) | Skyrim to Minecraft | Raw mouse motion while away; Minecraft turns its player itself |
| `McViewEventType` `kEvEnterOtherDimension` (12), `kEvLeaveOtherDimension` (13) | Minecraft to Skyrim | Reserved and not sent: Skyrim follows `kMcOtherDimension`, a state a dropped event or a reconnect can't leave stale |

World spaces added these within 13, without a version bump (an older half ignores them):

| Addition | Direction | Used by |
|---|---|---|
| `kColClear` payload `u32 worldId` after the epoch | Skyrim to Minecraft | Minecraft puts the epoch's collision in that world's space. Without it Minecraft waits for SkyState to show the epoch |
| `McState.spaceWorld`, `spaceX`, `spaceZ` (0xC8..0xD3; McState is 0xD8 bytes now) | Minecraft to Skyrim | Which Skyrim world's space Minecraft's player is in, and its origin; Skyrim logs it. Every coordinate on the link stays in the current world's own terms |

Companions added these within 13, without a version bump (an older half ignores them: an older plugin never sets the bits
and drops the event type, an older Minecraft ignores the bits):

| Addition | Direction | Used by |
|---|---|---|
| `ActorFlags` `kActorCompanion` (bit 5), `kActorCompanionWaiting` (bit 6), in `ActorRecord.flags` (offset 4 of each 64-byte record; records from 0x12040) | Skyrim to Minecraft | The actor is the player's animal; it isn't following now (told to wait, or left behind because another holds the animal follower slot) |
| `ActorRecord.flags` bits 8 to 11: `AnimalKind` (`kActorKindShift` 8, `kActorKindMask` 0xF00; 0 none, 1 dog, 2 wolf, 3 horse) | Skyrim to Minecraft | Which taming rules apply, from the race (`AnimalKinds.h`) |
| `kEvCompanion` (14), `CompanionAction` 1 tame, 2 follow, 3 wait, 4 heal | Minecraft to Skyrim | `McEvent` (32 bytes from 0x17080): formId @4 = the actor, flags @24 = the action, weapon @28 = the AnimalKind Minecraft saw (Skyrim refuses a taming of another kind), a @8 = heal: the share of maximum health to restore. Event 14 because 12 and 13 are the Minecraft view's |

## Testing a build

1. Back up the Skyrim saves folder.
2. Install the jar into the instance's `mods` folder and the DLL into `Data/SKSE/Plugins`, keeping
   the previous ones in `dev-backups`.
3. Play the feature tests for the build.
4. Read `Documents/My Games/Skyrim Special Edition/SKSE/SkyCraft.log` and the instance's
   `logs/latest.log`, searching for the log tags above.
