# Skyrim to Minecraft conversions

What the default rules in `skycraft-items.txt` (`ItemRules.DEFAULT_TEXT`) turn Skyrim items into. Every name below is a real name read from Skyrim.esm, Update.esm, the three DLCs, the Creation Club plugins and USSEP, with the tags the SKSE plugin gives it (its VendorItem keywords). Counts are Minecraft items per Skyrim item.

An existing `config/skycraft-items.txt` keeps its old rules: delete it (or merge by hand) to get these.

## Items

163 new rules. Each one takes an item that used to stay in Skyrim (191 real item names in all). They sit where no earlier rule can take their items and they can't take an earlier rule's items: tagged rules join their sections, and the household rules come last, after a block of `keep` rules for gear, clothing, jewelry, potions, raw food and ingredients.

### Light, tools and household goods

| Item | Becomes | Why |
|---|---|---|
| Torch, Torch Bright | 4 torch | One Skyrim torch burns as long as a handful of Minecraft ones; four is what coal and sticks craft |
| Lantern | lantern | The same object |
| Candlestick | candle | The candle is the useful part |
| Glazed Candles | 2 candle | Two candles in the model |
| Silver Candlestick | 4 iron_nugget | Minecraft has no silver, so silver melts to its nearest metal, iron |
| Charcoal | charcoal | The same thing |
| Bucket | bucket | The same object; a Minecraft bucket can't be smelted back to iron, so there is no iron farm in it |
| Pickaxe, Notched Pickaxe, Ancient Nordic Pickaxe | iron_pickaxe | Skyrim's mining pick is iron (from anywhere: picked up, looted or bought) |
| Woodcutter's Axe | iron_axe | Skyrim's chopping axe is iron; bought ones used to become a war axe |
| Fishing Rod, Alik'ri Fishing Rod, Argonian Fishing Rod, Dwarven Fishing Rod | fishing_rod | The same tool (Fishing Creation) |
| Shovel | iron_shovel | The same tool |
| Pitchfork | iron_hoe | Minecraft's farming tool |
| Tongs | shears | Minecraft's two armed iron hand tool |
| Broom | brush | Minecraft's sweeping tool |
| Hammer, Saw | 3 iron_nugget | Minecraft has neither tool, so the iron is salvaged |
| Oar | 3 stick | It's wood |
| Linen Wrap | 2 string | Cloth |
| Bloody Rags | string | Cloth |
| Roll of Paper | 3 paper | The same thing |
| Ruined Book | 2 paper | What's left of it |
| Burned Book | charcoal | What's left of it |
| Quill | feather | A quill is a feather |
| Inkwell | ink_sac | Minecraft's ink |
| Bowl, Wooden Bowl | bowl | The same object |
| Tankard, Bloody Tankard, Flagon, Goblet, Cup, Jug | glass_bottle | Minecraft's only drinking vessel |
| Empty Wine Bottle, Empty Skooma Bottle | glass_bottle | The same object |
| Empty Jar, and Bee, Butterfly, Green Butterfly, Purple Butterfly, Dragonfly, Moth, Torchbug and Bliss Bug in a Jar | glass_bottle | The jar carries over; Minecraft has no bottled insects |
| Honey Jar | honey_bottle | The same thing |
| Silver Goblet | 3 iron_nugget | Silver melts to iron |
| Silver Jug, Silver Platter | 4 iron_nugget | Silver melts to iron |
| Cast Iron Pot | 6 iron_nugget | Iron cookware melts to nuggets, more for the heavy pot |
| Kettle, Clothes Iron | 3 iron_nugget | Iron melts to nuggets |
| Pot | 2 iron_nugget | Iron melts to nuggets |
| Fork, Knife (the table knife), Draw Knife, Spoon | iron_nugget | Iron melts to nuggets |
| Burial Urn, Glazed Urn | decorated_pot | Minecraft's urn |
| Lute, Drum, Flute | note_block | Minecraft's instrument |
| Unmelting Snow | snow_block | The same thing, and it doesn't melt either |
| Pyroil Tar | 2 fire_charge | It's for setting things alight |
| Centurion Dynamo Core | redstone_block | A Dwemer power source is a block of power |
| Goat Horns, Horker Tusk | 2 bone | Horn and tusk are bone |
| Mammoth Tusk | 4 bone | A big tusk |
| Chaurus Chitin | turtle_scute | Shell plate |

### Dwemer clutter

| Item | Becomes | Why |
|---|---|---|
| Dwemer Bowl, Dwemer Cup, Dwemer Dish, Dwemer Pan | dwarven_metal_ingot | Dwemer metal melts into the SkyCraft Dwemer ingot |
| Dwemer Pot, Dwemer Urn | 2 dwarven_metal_ingot | Bigger pieces |
| Dwemer Gyro, Dwemer Actuator | 2 dwarven_metal_ingot | Bigger parts |
| Dwemer Lever, Small Dwemer Lever, Dwemer Cog, Dwemer Gear | dwarven_metal_ingot | Small parts |
| Large Decorative Dwemer Strut | 3 dwarven_metal_ingot | The existing strut rule; it now matches because the cut name "Large Decorative Dwemer Str" comes back whole |

### Hearthfire building materials

| Item | Becomes | Why |
|---|---|---|
| Clay | 4 clay_ball | A block of clay's worth |
| Quarried Stone | 4 cobblestone | Building stone |
| Sawn Log | 4 oak_planks | One log's planks |
| Nails | iron_nugget | One iron ingot makes 10 nails in Skyrim; 9 nuggets make an ingot |
| Iron Fittings | 4 iron_nugget | One iron ingot makes 2 |
| Hinge | 2 iron_nugget | Iron and a strip of leather |
| Lock | 3 iron_nugget | Iron and corundum |
| Glass | 2 glass | The same thing |
| Straw | wheat | Straw is wheat stalks (a hay block would be 9) |

### Animal parts and alchemy reagents

| Item | Becomes | Why |
|---|---|---|
| Skeever Tail, Falmer Ear | rotten_flesh | Flesh |
| Giant's Toe | 2 rotten_flesh | A big one |
| Troll Fat | slime_ball | Greasy and sticky |
| Ash Hopper Jelly, Netch Jelly, Scrib Jelly | slime_ball | Jelly |
| Large Antlers, Minotaur Horn, Bear Claws | 2 bone | Antler, horn and claw are bone |
| Small Antlers, Hagraven Claw, Bone Hawk Claw, Sabre Cat Tooth, Boar Tusk | bone | Bone |
| Ice Wraith Teeth, Daedroth Teeth, Ogre's Teeth | bone | Teeth are bone |
| Bone Hawk Skull | 2 bone | Bone |
| Powdered Mammoth Tusk | 3 bone_meal | Ground bone |
| Slaughterfish Scales | 2 bone_meal | Fish meal is a fertilizer |
| Eye of Sabre Cat | spider_eye | Minecraft's brewing eye |
| Mudcrab Chitin, Shellbug Chitin | turtle_scute | Shell plate |
| Kagouti Hide | leather | A hide |
| Daedra Silk | 3 string | Fine thread |
| Wisp Wrappings, Kresh Fiber | 2 string | Thread |
| Ectoplasm | phantom_membrane | Ghost stuff from an undead flyer |
| Glow Dust | 2 glowstone_dust | Glowing dust |
| Torchbug Thorax | glowstone_dust | The glowing part |
| Vampire Dust | gunpowder | Grey ash |
| Fire Salts | blaze_powder | Fire in powder form |
| Frost Salts | packed_ice | Cold made solid |
| Void Salts | ender_pearl | The Void; rare in both games |
| Honeycomb, Beehive Husk | honeycomb | The same thing |

### Plants and fungi

| Item | Becomes | Why |
|---|---|---|
| Bleeding Crown, Blisterwort, Creep Cluster, Ash Creep Cluster | red_mushroom | Red fungi |
| Mora Tapinella, Namira's Rot, Scaly Pholiota, Swamp Fungal Pod | brown_mushroom | Brown fungi |
| Glowing Mushroom, Luminous Russula | glow_lichen | Glowing growth |
| Hanging Moss, Giant Lichen | vine | Hanging greenery |
| Deathbell, Nightshade | wither_rose | Deadly flowers |
| Grass Pod, Ashen Grass Pod | 2 wheat_seeds | Seed pods |
| Comberry | 2 sweet_berries | Berries |
| Juniper Berries | 2 sweet_berries | Berries (they carry a raw food tag, so the old ingredient rule missed them) |
| Wheat | wheat | The same thing (raw food tag, like juniper) |
| Moon Sugar | 2 sugar | Sugar |
| Salt Pile | sugar | Minecraft's white cooking powder; it has no salt |
| Spriggan Sap | resin_clump | Tree resin |
| Burnt Spriggan Wood | charcoal | Burnt wood |

### Fish

| Item | Becomes | Why |
|---|---|---|
| Abecean Longfin, Histcarp, Silverside Perch, Pearlfish, Cod, Carp, Catfish, Glass Catfish, Brook Bass, Angler, Vampire Fish | cod | Plain eating fish |
| Salmon, Arctic Char, Arctic Grayling | salmon | Salmon and its kin |
| Cyrodilic Spadetail, River Betty, Goldfish, Angelfish, Lyretail Anthias, Pygmy Sunfish, Spadefish, Glassfish, Pogfish, Tripod Spiderfish | tropical_fish | Small bright fish |
| Scorpion Fish, Direfish | pufferfish | Fish you shouldn't eat raw |

### Gems and ores

| Item | Becomes | Why |
|---|---|---|
| Ruby | 4 redstone | Red gem, red dust |
| Flawless Ruby | 8 redstone | Flawless gems give twice as much |
| Garnet | 2 redstone | A lesser red gem |
| Flawless Garnet | 4 redstone | Twice as much |
| Sapphire | 4 lapis_lazuli | Blue gem, blue gem |
| Flawless Sapphire | 8 lapis_lazuli | Twice as much |
| Soul Gem Fragment | amethyst_shard | A purple crystal shard (never a portal key) |
| Amber | 2 resin_clump | Amber is resin |
| Refined Amber | 2 resin_brick | Worked resin |
| Stalhrim | blue_ice | Enchanted ice that never melts |

### Rules that changed what an item already became

| Item | Was | Now | Why |
|---|---|---|---|
| Chicken's Egg, Hawk's Egg, Pine Thrush Egg, Rock Warbler Egg | bread | egg | They carry a food tag, so the bread rule took them |
| Snowberries | bread | 2 sweet_berries | Same: a food tag |
| Cooked Carp, Cooked Arctic Char, Cooked Juvenile Mudcrab and the other cooked catches of the Fishing Creation | bread | cooked_cod | Cooked fish |
| Cooked Beef | bread | cooked_beef | The same thing |
| Cooked Boar Meat | bread | cooked_porkchop | Boar is pig |
| Salmon Meat | beef | salmon | It has only the raw food tag, so the meat rule took it |
| Flawless Diamond, Flawless Emerald | 1 | 2 | Flawless gems give twice as much |
| Flawless Amethyst | 2 amethyst_shard | 4 amethyst_shard | Same |
| Woodcutter's Axe, bought | iron war axe | iron_axe | It's a tool |
| Pickaxes and Woodcutter's Axe, looted | iron_ingot | the tool | The tool is worth more than its ingot |

### Stays in Skyrim on purpose

Gold, keys, books, spell tomes, scrolls, lockpicks, wine, ale, mead, Skooma, milk (Jug of Milk), Salmon Roe, Nirnroot and Crimson Nirnroot, soul gems other than the filled Greater, Grand and Black ones, Sheogorath-Shaped Amber, the Exquisite Sapphire, Ysgramor's Soup Spoon, Balbus' Fork, Michaela's Flagon, Wylandriah's Spoon, Mammoth Tusk Powder, and the Thieves Guild's Jeweled Goblet, Flagon, Pitcher and Candlestick. Quest items never convert in any case.

### Clothing (pending your call)

In the defaults but commented out (each line starts with `#`), so nothing changes until you remove the `#`.

| Clothing | Becomes |
|---|---|
| Hoods, hats, caps, cowls (Adept Hood, Chef's Hat, Cowl) | leather_helmet |
| Boots, shoes, sandals, footwraps (Fine Boots, Barkeeper's Shoes) | leather_boots |
| Gloves, gauntlets, armguards, cuffs, wraps (Cicero's Gloves, Fine Armguards) | leather_leggings (no glove slot) |
| Everything else: robes, clothes, tunics, dresses, garb (Hooded Monk Robes, Fine Clothes, Belted Tunic) | leather_chestplate |
| Girl's Yellow Dress and the other Hearthfire children's clothes (no clothing tag) | leather_chestplate |

### Jewelry (pending your call)

Also commented out. A gem in the name gives the gem plus gold nuggets; otherwise the metal.

| Jewelry | Becomes |
|---|---|
| Diamond (Gold Diamond Ring) | 3 gold_nugget + diamond |
| Emerald (Silver Emerald Necklace) | 3 gold_nugget + emerald |
| Sapphire (Silver Sapphire Necklace) | 3 gold_nugget + 2 lapis_lazuli |
| Ruby (Gold Ruby Necklace) | 3 gold_nugget + 2 redstone |
| Garnet (Silver Garnet Ring) | 3 gold_nugget + redstone |
| Amethyst (Silver Amethyst Ring) | 3 gold_nugget + amethyst_shard |
| Gold (Gold Necklace) | 4 gold_nugget |
| Silver (Silver Ring) | 4 iron_nugget |
| Copper (Copper and Onyx Circlet) | 4 copper_nugget |
| Anything else (Saarthal Amulet, Amulet of Mara) | 3 iron_nugget |

## Potions and poisons

Every Skyrim potion becomes a Minecraft potion with effects of its own, named like the Skyrim one, and every poison a splash potion to throw. Health potions are red, magicka blue, stamina green; the rest take Minecraft's mix of their effects' colors. Rules write `minecraft:potion{effect:amplifier:seconds + ...}`; a range like `0-2` grows with the name's tier.

| Tier | Words in the name |
|---|---|
| 0 | Minor, Weak, Brief |
| 1 | none of these (every crafted "Potion of ..." and "Poison of ...") |
| 2 | Plentiful, Draught, Potent, Extended, Enduring, Lasting |
| 3 | Vigorous, Solution, Philter, Virulent, Malign, Persisting, Prolonged |
| 4 | Extreme, Elixir, Deadly, Unceasing |
| 5 | Ultimate, Grand, Prime |

A range runs from tier 0 to tier 5 in proportion, rounded: levels I to III are I at tiers 0 and 1, II at 2 and 3, III at 4 and 5; 60 to 180 seconds is 84 s at tier 1, 156 s at tier 4. Seconds follow Skyrim's durations, stretched for Minecraft. Magicka has no Minecraft meter, so magicka potions give Absorption (a ward of golden hearts); stamina is the hunger bar and running.

57 of Skyrim's alchemy effects map to Minecraft effects (plus Ghostveil, Frostbite Venom and three effects from mods); Cure Disease and Cure Poison become a milk bucket.

| Skyrim effect | Minecraft effect(s), tiers | Example real names |
|---|---|---|
| Restore Health | Instant Health I to III | Potion of Minor Healing (I), Potion of Plentiful Healing (II), Potion of Ultimate Healing (III), Potion of Restore Health (I) |
| Restore Magicka | Absorption I to IV, 20 to 60 s | Potion of Minor Magicka (I, 20 s), Potion of Magicka (II, 28 s), Potion of Ultimate Magicka (IV, 60 s), Potion of Restore Magicka |
| Restore Stamina | Saturation, 4 to 20 food points | Potion of Minor Stamina (4), Potion of Stamina (7), Potion of Ultimate Stamina (20), Potion of Restore Stamina |
| Restore all three (Well-being) | Instant Health I to III, Absorption I to III for 30 to 90 s, Saturation 2 to 10 | Potion of Minor Well-being (I, I 30 s, 2), Potion of Vigorous Well-being (II, II 66 s, 7), Potion of Ultimate Well-being |
| Fortify Health | Health Boost I to IV, 60 to 180 s | Potion of Health (II, 84 s), Elixir of Health (III, 156 s), Potion of Fortify Health |
| Fortify Magicka | Absorption I to III, 60 to 180 s | Potion of Extra Magicka (I, 84 s), Elixir of Extra Magicka (III, 156 s), Potion of Fortify Magicka |
| Fortify Stamina | Speed I to II and Jump Boost I to II, 60 to 180 s | Potion of Enhanced Stamina (I, 84 s), Solution of Enhanced Stamina (II, 132 s), Stallion's Potion (I, 300 s) |
| Regenerate Health | Regeneration I to II, 45 to 180 s | Potion of Regeneration (I, 72 s), Elixir of Regeneration (II, 153 s), Potion of Regenerate Health |
| Regenerate Magicka | Absorption I to II, 150 to 300 s | Potion of Lasting Potency (I, 180 s), Solution of Lasting Potency (II, 240 s), Potion of Regenerate Magicka |
| Regenerate Stamina | Speed I to II for 120 to 300 s, Saturation 1 to 5 | Potion of Vigor (I, 156 s, 2), Elixir of Vigor (II, 264 s, 4), Potion of Regenerate Stamina |
| Fortify One-handed | Strength I to III, 60 to 180 s | Potion of the Warrior (I, 84 s), Elixir of the Warrior (III, 156 s), Potion of Fortify One-handed |
| Fortify Two-handed | Strength I to III, 60 to 180 s | Draught of the Berserker (II, 108 s), Elixir of the Berserker (III), Potion of Fortify Two-handed |
| Fortify One-handed with Light Armor (Conflict) | Strength I to III, Resistance I to II, Speed I, 60 to 180 s | Potion of Conflict (I, I, I), Grand Elixir of Conflict (III, II, I, 180 s) |
| Fortify Archery | Night Vision, 90 to 300 s (no Minecraft effect strengthens bows) | Potion of True Shot (132 s), Elixir of True Shot (258 s), Potion of Fortify Archery |
| Fortify Archery with Regenerate Stamina (Keenshot) | Night Vision 90 to 300 s and Speed I to II | Potion of Keenshot, Prime Elixir of Keenshot |
| Fortify Block | Absorption I to III, 60 to 180 s | Potion of the Defender (I), Philter of the Defender (II, 132 s), Potion of Fortify Block |
| Fortify Heavy Armor | Resistance I to III with Slowness I, 60 to 180 s | Potion of the Knight (I), Elixir of the Knight (III, 156 s), Potion of Fortify Heavy Armor |
| Fortify Light Armor | Resistance I to II with Speed I, 60 to 180 s | Skirmisher's Potion (I), Skirmisher's Elixir (II), Potion of Fortify Light Armor |
| Fortify Sneak | Invisibility, 20 to 90 s | Potion of Light Feet (34 s), Draught of Light Feet (48 s), Potion of Fortify Sneak |
| Invisibility | Invisibility, 30 to 180 s | Potion of Brief Invisibility (30 s), Potion of Extended Invisibility (90 s), Potion of Prolonged Invisibility (120 s), Potion of Invisibility |
| Invisibility with Restore Health (Escape) | Invisibility 30 to 180 s and Instant Health I to III | Potion of Escape (60 s, I), Prime Elixir of Escape (180 s, III) |
| Fortify Lockpicking | Luck I to III, 60 to 180 s | Potion of Lockpicking (I), Elixir of Lockpicking (III), Potion of Fortify Lockpicking |
| Fortify Pickpocket | Luck I to III, 60 to 180 s | Potion of Pickpocketing (I), Philter of Pickpocketing (II), Potion of Fortify Pickpocket |
| Fortify Lockpicking with Pickpocket (Larceny) | Luck I to III, 60 to 180 s | Potion of Larceny (I), Prime Elixir of Larceny (III, 180 s) |
| Fortify Barter | Hero of the Village I to III, 60 to 300 s (villagers trade cheaper) | Potion of Haggling (I, 108 s), Elixir of Haggling (III, 252 s), Potion of Fortify Barter |
| Fortify Persuasion | Hero of the Village I to II, 60 to 300 s | Potion of Glibness (I), Elixir of Glibness (II), Potion of Fortify Persuasion |
| Fortify Smithing | Haste I to III, 60 to 180 s | Blacksmith's Potion (I, 84 s), Blacksmith's Elixir (III, 156 s), Potion of Fortify Smithing |
| Fortify Alchemy | Haste I to II, 60 to 180 s | Potion of Fortify Alchemy (I, 84 s) |
| Fortify Enchanting | Haste I to II, 60 to 180 s | Enchanter's Potion (I), Enchanter's Philter (II), Potion of Fortify Enchanting |
| Fortify Alteration | Resistance I to II, 60 to 180 s (Oakflesh and Stoneflesh) | Draught of Alteration (I), Elixir of Alteration (II), Potion of Fortify Alteration |
| Fortify Conjuration | Strength I to II, 60 to 180 s (bound weapons) | Conjurer's Potion (I), Conjurer's Elixir (II), Potion of Fortify Conjuration |
| Fortify Destruction | Strength I to II and Fire Resistance, 60 to 180 s | Potion of Destruction (I), Philter of Destruction (II, 132 s), Potion of Fortify Destruction |
| Fortify Illusion | Invisibility 20 to 60 s and Night Vision 60 to 180 s | Potion of Illusion (28 s, 84 s), Elixir of Illusion, Potion of Fortify Illusion |
| Fortify Restoration | Regeneration I to II, 60 to 180 s | Potion of the Healer (I), Elixir of the Healer (II), Potion of Fortify Restoration |
| Fortify Carry Weight | Strength I to II, 120 to 300 s (Skyrim calls it Potion of Strength) | Potion of Strength (I, 156 s), Elixir of Strength (II, 264 s), Potion of Fortify Carry Weight |
| Fortify Carry Weight with Stamina (Plunder) | Strength I to II and Speed I to II, 120 to 300 s | Potion of Plunder (I, 156 s), Prime Elixir of Plunder (II, 300 s) |
| Resist Fire | Fire Resistance, 60 to 300 s | Potion of Resist Fire (108 s), Philter of Resist Fire (204 s) |
| Resist Frost | Resistance I, 60 to 180 s | Potion of Resist Cold, Elixir of Resist Cold (156 s), Ice Wraith Essence, Potion of Resist Frost |
| Resist Shock | Resistance I and Fire Resistance (lightning sets you alight), 60 to 180 s | Potion of Resist Shock, Draught of Resist Shock (108 s) |
| Resist Magic | Resistance I to II, 60 to 180 s | Potion of Resist Magic (I), Elixir of Resist Magic (II), Soul Husk Extract |
| Resist Poison | Regeneration I to II, 20 to 60 s | Potion of Resist Poison (I, 28 s) |
| Waterbreathing | Water Breathing and Conduit Power, 30 to 240 s | Potion of Waterbreathing (72 s), Elixir of Waterbreathing (198 s) |
| Waterwalking | Dolphin's Grace, 60 to 180 s | Potion of Waterwalking (84 s) |
| Cure Disease, Cure Poison | a milk bucket, Minecraft's cure | Potion of Cure Disease, Potion of Cure Poison |
| Ghostveil | Invisibility, 60 s | Philter of the Phantom |
| Night Eye (mods) | Night Vision, 120 to 480 s | Potion of Night Eye (192 s) |
| Detect Life (mods) | Night Vision, 60 to 240 s | Potion of Detect Life |
| Muffle (mods) | Invisibility, 20 to 60 s | Potion of Muffle |
| Damage Health | splash Instant Damage I to III | Weak Poison (I), Poison (I), Potent Poison (II), Deadly Poison (III), Ice Wraith Bane (II), Poison of Damage Health |
| Lingering Damage Health | splash Poison I to III, 10 to 25 s | Weak Lingering Poison (I, 10 s), Potent Lingering Poison (II, 16 s), Deadly Lingering Poison (III, 22 s), Poison of Lingering Damage Health |
| Ravage Health | splash Wither I to II, 8 to 20 s | Poison of Ravage Health (I, 10 s) |
| Damage Magicka | splash Weakness I to II, 10 to 40 s | Weak Magicka Poison (I, 10 s), Deadly Magicka Poison (II, 34 s), Poison of Damage Magicka |
| Lingering Damage Magicka | splash Weakness I to III with Bad Luck, 20 to 40 s | Lingering Magicka Poison (I, 20 s), Lasting Magicka Poison (II, 30 s), Unceasing Magicka Poison (III, 40 s) |
| Damage Magicka Regen | splash Weakness I and Bad Luck I to II, 20 to 90 s | Weak Recovery Poison (20 s), Deadly Recovery Poison (II, 76 s), Poison of Damage Magicka Regen |
| Ravage Magicka | splash Weakness I to II, 20 to 60 s | Poison of Ravage Magicka (I, 28 s) |
| Damage Stamina | splash Hunger I to III, 10 to 30 s | Stamina Poison (I, 14 s), Virulent Stamina Poison (II, 22 s), Deadly Stamina Poison (III, 26 s) |
| Lingering Damage Stamina | splash Hunger I to III with Slowness I, 20 to 40 s | Lingering Stamina Poison (I, 20 s), Lasting Stamina Poison (II, 30 s), Unceasing Stamina Poison (III, 40 s) |
| Damage Stamina Regen | splash Slowness I and Hunger I, 20 to 90 s | Vigor Poison (34 s), Malign Vigor Poison (62 s), Poison of Damage Stamina Regen |
| Ravage Stamina | splash Mining Fatigue I to II and Hunger I to II, 10 to 40 s | Poison of Ravage Stamina (I, 16 s) |
| Paralysis | splash Slowness V and Mining Fatigue III, 3 to 15 s | Weak Paralysis Poison (3 s), Paralysis Poison (5 s), Virulent Paralysis Poison (10 s), Deadly Paralysis Poison (13 s) |
| Fear | splash Darkness and Weakness I, 10 to 30 s | Weak Fear Poison (10 s), Potent Fear Poison (18 s), Deadly Fear Poison (26 s) |
| Frenzy | splash Nausea, 5 to 20 s | Weak Frenzy Poison (5 s), Frenzy Poison (8 s), Deadly Frenzy Poison (17 s) |
| Slow | splash Slowness I to III, 15 to 45 s | Poison of Slow (I, 21 s) |
| Weakness to Fire | splash Weakness I to II, 15 to 45 s | Weak Aversion to Fire (I, 15 s), Deadly Aversion to Fire (II, 39 s), Poison of Weakness to Fire |
| Weakness to Frost | splash Slowness I to II, 15 to 45 s | Aversion to Frost (I, 21 s), Malign Aversion to Frost (II, 33 s) |
| Weakness to Shock | splash Glowing and Weakness I, 15 to 45 s | Aversion to Shock (21 s), Poison of Weakness to Shock |
| Weakness to Magic | splash Bad Luck I to II and Weakness I, 15 to 45 s | Potent Aversion to Magic (27 s), Poison of Weakness to Magic |
| Weakness to Poison | splash Poison I 5 to 15 s and Weakness I 15 to 45 s | Poison of Weakness to Poison (7 s, 21 s) |
| Frostbite Venom | splash Poison I 6 s and Slowness II 8 s | Frostbite Venom, Netch Poison |

Staying in Skyrim: The White Phial (it refills), Lotus Extract and Nightshade Extract (quest poisons), Unknown Potion, and drinks without a vendor keyword (Skooma, Potion of Blood, Falmer Blood Elixir).

Crafted potion names longer than 27 letters arrive cut ("Potion of Fortify Two-hande"); SkyCraft gives back the whole name from `ItemRules.LONG_NAMES` for both the rules and the Minecraft potion's name. The three crafted Lingering Damage poisons share their first 27 letters, so they stay cut and count as Lingering Damage Health.
