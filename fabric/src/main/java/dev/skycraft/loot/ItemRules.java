package dev.skycraft.loot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Which Skyrim items become Minecraft items when the player gets them, and what they become.
 *
 * <p>Plain text, one rule per line, first match wins, anything unmatched stays in Skyrim:
 * <pre>
 * tag food name potato = minecraft:potato 1
 * tag weapon material iron from looted = minecraft:iron_ingot 1
 * tag gold = keep
 * tag potion name healing = minecraft:potion[healing] 1
 * tag potion name vigor = minecraft:potion{speed:0-1:120-300 + saturation:0-4:0 #3a9a3a} 1
 * tag jewelry name diamond = minecraft:gold_nugget 3 + minecraft:diamond 1
 * </pre>
 * Every condition on the left must hold. {@code name} is a whole word of the Skyrim name (ignoring
 * case; {@code half-eaten} needs both words), {@code tag} one of {@link #TAGS}, {@code material}
 * one of {@link #MATERIALS}, {@code from} one of {@link #SOURCES}. The number on the right is
 * Minecraft items per Skyrim item; {@code +} joins several items. A bracket after one of the
 * {@link #POTION_ITEMS} names the vanilla potion it holds, one of {@link #POTIONS}; braces list
 * effects of its own instead ({@link Effect}), with an optional {@code #rrggbb} color and
 * {@code "name"}. Effect ranges scale with the Skyrim name's tier ({@link #TIERS}).
 * Skyrim sends names cut to 27 bytes; {@link #fullName} gives back the known long ones.
 * Pure Java on purpose, so it's unit tested without Minecraft.
 */
public final class ItemRules {
	/** Tag names, by bit (see ItemTags in the protocol header). */
	public static final List<String> TAGS = List.of("food", "foodraw", "hide", "oreingot", "potion", "poison", "ingredient", "weapon", "armor", "clothing",
		"jewelry", "gold", "key", "arrow", "gem", "clutter", "book", "soulgem", "filled");
	/** Material names, by value (see ItemMaterial in the protocol header). */
	public static final List<String> MATERIALS = List.of("none", "wood", "iron", "steel", "imperial", "orcish", "dwarven", "elven", "glass", "ebony", "daedric",
		"dragon", "silver", "hide", "draugr", "falmer");
	/** Source names, by value (see ItemSource); "looted" means corpse or container, "bought" is Skyrim's shop. */
	public static final List<String> SOURCES = List.of("world", "corpse", "container", "actor", "bought");
	/** Vanilla potion ids, as Minecraft 26.3 registers them (net.minecraft.world.item.alchemy.PotionIds). */
	public static final List<String> POTIONS = List.of("water", "mundane", "thick", "awkward", "night_vision", "long_night_vision", "invisibility",
		"long_invisibility", "leaping", "long_leaping", "strong_leaping", "fire_resistance", "long_fire_resistance", "swiftness", "long_swiftness",
		"strong_swiftness", "slowness", "long_slowness", "strong_slowness", "turtle_master", "long_turtle_master", "strong_turtle_master",
		"water_breathing", "long_water_breathing", "healing", "strong_healing", "harming", "strong_harming", "poison", "long_poison", "strong_poison",
		"regeneration", "long_regeneration", "strong_regeneration", "strength", "long_strength", "strong_strength", "weakness", "long_weakness", "luck",
		"slow_falling", "long_slow_falling", "wind_charged", "weaving", "oozing", "infested");
	/** Effect ids, as Minecraft 26.3 registers them (net.minecraft.world.effect.MobEffects), for potions with effects of their own. */
	public static final List<String> EFFECTS = List.of("speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage",
		"jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing", "invisibility", "blindness", "night_vision", "hunger",
		"weakness", "poison", "wither", "health_boost", "absorption", "saturation", "glowing", "levitation", "luck", "unluck", "slow_falling",
		"conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village", "darkness", "trial_omen", "raid_omen", "wind_charged", "weaving", "oozing",
		"infested", "breath_of_the_nautilus");
	/** Items that hold a potion (the potion_contents component), so a rule can write {@code minecraft:potion[healing]}. */
	public static final List<String> POTION_ITEMS = List.of("minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion",
		"minecraft:tipped_arrow");
	/**
	 * How strong a Skyrim potion or poison is, from the words of its name: Minor and Weak are tier 0, a name
	 * without a tier word is tier 1, and Ultimate, Grand and Prime are tier 5. The strongest word counts.
	 */
	public static final Map<String, Integer> TIERS = Map.ofEntries(Map.entry("minor", 0), Map.entry("weak", 0), Map.entry("brief", 0),
		Map.entry("plentiful", 2), Map.entry("draught", 2), Map.entry("potent", 2), Map.entry("extended", 2), Map.entry("enduring", 2),
		Map.entry("lasting", 2), Map.entry("vigorous", 3), Map.entry("solution", 3), Map.entry("philter", 3), Map.entry("virulent", 3),
		Map.entry("malign", 3), Map.entry("persisting", 3), Map.entry("prolonged", 3), Map.entry("extreme", 4), Map.entry("elixir", 4),
		Map.entry("deadly", 4), Map.entry("unceasing", 4), Map.entry("ultimate", 5), Map.entry("grand", 5), Map.entry("prime", 5));
	/** The tier of a name without a tier word, and the highest tier. */
	public static final int DEFAULT_TIER = 1, MAX_TIER = 5;
	/** Bytes of a name an ItemEvent carries (ItemEvent.name is char[28]). */
	public static final int NAME_BYTES = 27;
	/**
	 * Real Skyrim names longer than {@link #NAME_BYTES}: the vanilla potions, the potions and poisons Skyrim's
	 * alchemy names "Potion of " or "Poison of " plus the effect, and a Dwemer part. Three crafted lingering
	 * poisons share their first 27 bytes, so they're left cut.
	 */
	public static final List<String> LONG_NAMES = List.of("Potion of Brief Invisibility", "Potion of Extended Invisibility",
		"Potion of Enduring Invisibility", "Potion of Prolonged Invisibility", "Potion of Plentiful Well-being", "Potion of Vigorous Well-being",
		"Potion of Extreme Well-being", "Potion of Ultimate Well-being", "Solution of Enhanced Stamina", "Potion of Fortify One-handed",
		"Potion of Fortify Two-handed", "Potion of Fortify Heavy Armor", "Potion of Fortify Light Armor", "Potion of Fortify Lockpicking",
		"Potion of Fortify Pickpocket", "Potion of Fortify Persuasion", "Potion of Fortify Enchanting", "Potion of Fortify Alteration",
		"Potion of Fortify Conjuration", "Potion of Fortify Destruction", "Potion of Fortify Restoration", "Potion of Fortify Carry Weight",
		"Potion of Regenerate Magicka", "Potion of Regenerate Stamina", "Poison of Damage Magicka Regen", "Poison of Damage Stamina Regen",
		"Poison of Weakness to Poison", "Poison of Lingering Damage Health", "Poison of Lingering Damage Magicka",
		"Poison of Lingering Damage Stamina", "Large Decorative Dwemer Strut");
	/** Most an effect's amplifier and seconds can be. */
	static final int MAX_AMPLIFIER = 255, MAX_SECONDS = 3600;

	public record Item(int tags, int material, int source, String name) {
	}

	/**
	 * One effect of a potion with effects of its own: a 26.3 effect id, its amplifier (0 is level I) and how many
	 * seconds it lasts (0 for instant effects like instant_health). {@code amplifier} and {@code seconds} are the
	 * values at tier 0 and the {@code Top} ones at tier {@link #MAX_TIER}; tiers between are in proportion, rounded.
	 * A rule writes {@code speed:0-1:60-180}, or {@code speed:1:120} for the same at every tier.
	 */
	public record Effect(String id, int amplifier, int seconds, int amplifierTop, int secondsTop) {
		public Effect(String id, int amplifier, int seconds) {
			this(id, amplifier, seconds, amplifier, seconds);
		}

		/** This effect with a name of tier {@code tier}. */
		public Effect at(int tier) {
			int a = scale(this.amplifier, this.amplifierTop, tier), s = scale(this.seconds, this.secondsTop, tier);
			return new Effect(this.id, a, s);
		}

		/** As a rule writes it. */
		public String text() {
			return this.id + ":" + range(this.amplifier, this.amplifierTop) + ":" + range(this.seconds, this.secondsTop);
		}

		private static String range(int low, int high) {
			return low == high ? String.valueOf(low) : low + "-" + high;
		}
	}

	/**
	 * {@code item} null means keep it in Skyrim; {@code potion} is the vanilla potion the item holds, or null;
	 * {@code effects} its own effects; {@code color} 0xRRGGBB or -1; {@code name} the Minecraft name, or null for
	 * Minecraft's own; {@code also} more items given with it.
	 */
	public record Result(String item, int perItem, String potion, List<Effect> effects, int color, String name, List<Result> also) {
		public Result(String item, int perItem) {
			this(item, perItem, null);
		}

		public Result(String item, int perItem, String potion) {
			this(item, perItem, potion, List.of(), -1, null, List.of());
		}

		public boolean keep() {
			return this.item == null;
		}

		/** A potion with effects of its own (rather than a vanilla potion). */
		public boolean custom() {
			return !this.effects.isEmpty();
		}

		/** The item as a rule writes it, {@code minecraft:potion[healing]} or {@code minecraft:potion{speed:1:120}}, for logs. */
		public String label() {
			if (this.item == null) {
				return "keep";
			}
			StringBuilder sb = new StringBuilder(this.item);
			if (this.potion != null) {
				sb.append('[').append(this.potion).append(']');
			}
			if (this.custom()) {
				sb.append('{').append(this.effects.stream().map(Effect::text).collect(Collectors.joining("+"))).append('}');
			}
			return sb.toString();
		}

		/** Every item with its count per Skyrim item: {@code minecraft:gold_nugget 3 + minecraft:diamond 1}. */
		public String describe() {
			if (this.item == null) {
				return "keep";
			}
			StringBuilder sb = new StringBuilder(this.label()).append(' ').append(this.perItem);
			for (Result r : this.also) {
				sb.append(" + ").append(r.describe());
			}
			return sb.toString();
		}

		/** This result for a Skyrim item named {@code skyrimName} of tier {@code tier}: effect values picked, custom potions named. */
		Result at(int tier, String skyrimName) {
			List<Effect> fx = this.effects.stream().map(e -> e.at(tier)).toList();
			String n = this.name != null ? this.name : this.custom() && skyrimName != null ? skyrimName.strip() : null;
			List<Result> more = this.also.stream().map(r -> r.at(tier, skyrimName)).toList();
			return new Result(this.item, this.perItem, this.potion, fx, this.color, n, more);
		}
	}

	private static final Result KEEP = new Result(null, 0);
	/** What separates the words of a name: anything but letters, digits and apostrophes. */
	private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9']+");
	private static final Pattern RANGE = Pattern.compile("(\\d{1,5})(?:-(\\d{1,5}))?");
	private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
	/** Cut name to whole name, for the {@link #LONG_NAMES} no other long name shares a cut with. */
	private static final Map<String, String> RESTORE = restoreTable();

	record Rule(int tagMask, int material, int sourceMask, List<String> nameWords, Result result) {
		boolean matches(Item it, List<String> words) {
			if ((it.tags() & this.tagMask) != this.tagMask) {
				return false;
			}
			if (this.material >= 0 && it.material() != this.material) {
				return false;
			}
			if (this.sourceMask != 0 && (this.sourceMask & (1 << it.source())) == 0) {
				return false;
			}
			return words.containsAll(this.nameWords);
		}
	}

	public static final String DEFAULT_TEXT = """
		# SkyCraft item conversion: which Skyrim items become Minecraft items when you get them.
		# One rule per line, first match wins. Anything that matches no rule stays in Skyrim.
		#   conditions = minecraft:item N     (N Minecraft items per Skyrim item)
		#   conditions = keep                 (stays in Skyrim)
		#   conditions = minecraft:item N + minecraft:other M   (several items for one)
		#   conditions = minecraft:potion[healing] N   (a vanilla potion; see "Potions and poisons" below)
		#   conditions = minecraft:potion{speed:0-1:60-180 + jump_boost:0:60 #3a9a3a "Name"} N
		#                                     (a potion with effects of its own; see "Potions and poisons")
		# Conditions (all must hold): tag <t>, name <word>, material <m>, from <where>
		#   tags: food foodraw hide oreingot potion poison ingredient weapon armor clothing jewelry
		#         gold key arrow gem clutter book soulgem filled (a soul gem that comes with a soul)
		#   materials: wood iron steel imperial orcish dwarven elven glass ebony daedric dragon
		#              silver hide draugr falmer
		#   from: world corpse container actor bought (Skyrim's shop) looted (corpse or container)
		# Skyrim sends names cut to 27 letters. SkyCraft gives back the whole name of the long Skyrim
		# potion names it knows (Potion of Fortify Two-handed, Potion of Prolonged Invisibility, ...),
		# so a name word can be the whole word; the cut word ("hande") matches too.
		# Restart Skyrim after editing.

		# Stays in Skyrim: coins for merchants, keys and books for quests, drinks, two rare treasures
		# (Sheogorath-Shaped Amber, the Exquisite Sapphire). Lockpicks, spell tomes, scrolls and soul gems
		# you fill yourself stay too, since no rule takes them.
		tag gold = keep
		tag key = keep
		tag book = keep
		tag food name wine = keep
		tag food name ale = keep
		tag food name mead = keep
		tag food name skooma = keep
		tag food name milk = keep
		name sheogorath = keep
		name exquisite = keep

		# Food
		tag food name pie = minecraft:pumpkin_pie 1
		tag food name salmon name steak = minecraft:cooked_salmon 1
		tag food name salmon = minecraft:salmon 1
		tag food name potato = minecraft:potato 1
		tag food name potatoes = minecraft:baked_potato 1
		tag food name apple = minecraft:apple 1
		tag food name carrot = minecraft:carrot 1
		tag food name cabbage = minecraft:beetroot 1
		tag food name leek = minecraft:beetroot 1
		tag food name tomato = minecraft:sweet_berries 2
		tag food name bread = minecraft:bread 1
		tag food name roll = minecraft:cookie 2
		tag food name cheese = minecraft:bread 1
		tag food name honey = minecraft:honey_bottle 1
		tag food name stew = minecraft:rabbit_stew 1
		tag food name soup = minecraft:mushroom_stew 1
		tag food name steak = minecraft:cooked_beef 1
		tag food name roast = minecraft:cooked_beef 1
		tag food name grilled = minecraft:cooked_chicken 1
		tag food name egg = minecraft:egg 1
		tag food name snowberries = minecraft:sweet_berries 2
		tag food name cooked name beef = minecraft:cooked_beef 1
		tag food name cooked name boar = minecraft:cooked_porkchop 1
		tag food name cooked = minecraft:cooked_cod 1
		tag foodraw name roe = keep
		tag foodraw name salmon = minecraft:salmon 1
		tag foodraw name arctic name char = minecraft:salmon 1
		tag foodraw name arctic name grayling = minecraft:salmon 1
		tag foodraw name scorpion name fish = minecraft:pufferfish 1
		tag foodraw name direfish = minecraft:pufferfish 1
		tag foodraw name tripod name spiderfish = minecraft:tropical_fish 1
		tag foodraw name pogfish = minecraft:tropical_fish 1
		tag foodraw name cod = minecraft:cod 1
		tag foodraw name carp = minecraft:cod 1
		tag foodraw name catfish = minecraft:cod 1
		tag foodraw name brook name bass = minecraft:cod 1
		tag foodraw name angler = minecraft:cod 1
		tag foodraw name vampire name fish = minecraft:cod 1
		tag foodraw name wheat = minecraft:wheat 1
		tag foodraw name juniper name berries = minecraft:sweet_berries 2
		tag foodraw name breast = minecraft:chicken 1
		tag foodraw name leg = minecraft:mutton 1
		tag foodraw name snout = minecraft:beef 2
		tag foodraw name venison = minecraft:beef 1
		tag foodraw name beef = minecraft:beef 1
		tag foodraw name meat = minecraft:beef 1
		tag food = minecraft:bread 1

		# Flowers, berries, mushrooms and feathers picked in Skyrim (Nirnroot and other rare ingredients stay)
		tag ingredient name blue name mountain name flower = minecraft:cornflower 1
		tag ingredient name red name mountain name flower = minecraft:poppy 1
		tag ingredient name purple name mountain name flower = minecraft:allium 1
		tag ingredient name yellow name mountain name flower = minecraft:dandelion 1
		tag ingredient name lavender = minecraft:lilac 1
		tag ingredient name tongue = minecraft:orange_tulip 1
		tag ingredient name snowberries = minecraft:sweet_berries 2
		tag ingredient name juniper name berries = minecraft:sweet_berries 2
		tag ingredient name jazbay = minecraft:sweet_berries 2
		tag ingredient name fly name amanita = minecraft:red_mushroom 1
		tag ingredient name cap = minecraft:brown_mushroom 1
		tag ingredient name stool = minecraft:brown_mushroom 1
		tag ingredient name wheat = minecraft:wheat 1
		tag ingredient name bone name meal = minecraft:bone_meal 2
		tag ingredient name cotton = minecraft:string 2
		name feathers = minecraft:feather 2
		tag foodraw name legs = minecraft:cod 1

		# More alchemy plants and fungi
		tag ingredient name bleeding name crown = minecraft:red_mushroom 1
		tag ingredient name blisterwort = minecraft:red_mushroom 1
		tag ingredient name creep name cluster = minecraft:red_mushroom 1
		tag ingredient name mora name tapinella = minecraft:brown_mushroom 1
		tag ingredient name namira's name rot = minecraft:brown_mushroom 1
		tag ingredient name scaly name pholiota = minecraft:brown_mushroom 1
		tag ingredient name swamp name fungal name pod = minecraft:brown_mushroom 1
		tag ingredient name glowing name mushroom = minecraft:glow_lichen 1
		tag ingredient name luminous name russula = minecraft:glow_lichen 1
		tag ingredient name hanging name moss = minecraft:vine 1
		tag ingredient name giant name lichen = minecraft:vine 1
		tag ingredient name deathbell = minecraft:wither_rose 1
		tag ingredient name nightshade = minecraft:wither_rose 1
		tag ingredient name grass name pod = minecraft:wheat_seeds 2
		tag ingredient name comberry = minecraft:sweet_berries 2
		tag ingredient name moon name sugar = minecraft:sugar 2
		tag ingredient name salt name pile = minecraft:sugar 1
		tag ingredient name honeycomb = minecraft:honeycomb 1
		tag ingredient name beehive name husk = minecraft:honeycomb 1
		tag ingredient name spriggan name sap = minecraft:resin_clump 1
		tag ingredient name burnt name spriggan name wood = minecraft:charcoal 1

		# Animal parts and alchemy reagents (horns, claws, teeth and tusks are bone)
		tag ingredient name skeever name tail = minecraft:rotten_flesh 1
		tag ingredient name giant's name toe = minecraft:rotten_flesh 2
		tag ingredient name falmer name ear = minecraft:rotten_flesh 1
		tag ingredient name troll name fat = minecraft:slime_ball 1
		tag ingredient name jelly = minecraft:slime_ball 1
		tag ingredient name large name antlers = minecraft:bone 2
		tag ingredient name small name antlers = minecraft:bone 1
		tag ingredient name minotaur name horn = minecraft:bone 2
		tag ingredient name bear name claws = minecraft:bone 2
		tag ingredient name hagraven name claw = minecraft:bone 1
		tag ingredient name sabre name cat name tooth = minecraft:bone 1
		tag ingredient name teeth = minecraft:bone 1
		tag ingredient name boar name tusk = minecraft:bone 1
		tag ingredient name powdered name mammoth name tusk = minecraft:bone_meal 3
		tag ingredient name slaughterfish name scales = minecraft:bone_meal 2
		tag ingredient name eye name sabre name cat = minecraft:spider_eye 1
		tag ingredient name mudcrab name chitin = minecraft:turtle_scute 1
		tag ingredient name kagouti name hide = minecraft:leather 1
		tag ingredient name daedra name silk = minecraft:string 3
		tag ingredient name wisp name wrappings = minecraft:string 2
		tag ingredient name kresh name fiber = minecraft:string 2
		tag ingredient name ectoplasm = minecraft:phantom_membrane 1
		tag ingredient name glow name dust = minecraft:glowstone_dust 2
		tag ingredient name torchbug name thorax = minecraft:glowstone_dust 1
		tag ingredient name vampire name dust = minecraft:gunpowder 1
		tag ingredient name fire name salts = minecraft:blaze_powder 1
		tag ingredient name frost name salts = minecraft:packed_ice 1
		tag ingredient name void name salts = minecraft:ender_pearl 1

		# Fish that are alchemy ingredients
		tag ingredient name abecean name longfin = minecraft:cod 1
		tag ingredient name histcarp = minecraft:cod 1
		tag ingredient name silverside name perch = minecraft:cod 1
		tag ingredient name pearlfish = minecraft:cod 1
		tag ingredient name cyrodilic name spadetail = minecraft:tropical_fish 1
		tag ingredient name river name betty = minecraft:tropical_fish 1
		tag ingredient name goldfish = minecraft:tropical_fish 1
		tag ingredient name angelfish = minecraft:tropical_fish 1
		tag ingredient name lyretail name anthias = minecraft:tropical_fish 1
		tag ingredient name pygmy name sunfish = minecraft:tropical_fish 1
		tag ingredient name spadefish = minecraft:tropical_fish 1
		tag ingredient name glassfish = minecraft:tropical_fish 1

		# Leather armor bought in a Skyrim shop becomes Minecraft leather armor
		tag armor material hide name helmet from bought = minecraft:leather_helmet 1
		tag armor material hide name hood from bought = minecraft:leather_helmet 1
		tag armor material hide name armor from bought = minecraft:leather_chestplate 1
		tag armor material hide name bracers from bought = minecraft:leather_leggings 1
		tag armor material hide name gauntlets from bought = minecraft:leather_leggings 1
		tag armor material hide name boots from bought = minecraft:leather_boots 1

		# Hides and leather (strips before hides, in case Skyrim tags them as hide)
		name leather name strips = skycraft:leather_strips 1
		tag hide = minecraft:leather 1
		name leather = minecraft:leather 1
		name firewood = minecraft:oak_log 1

		# Metals and gems (flawless gems give more; rubies and garnets are redstone, sapphires lapis)
		tag oreingot name iron name ingot = minecraft:iron_ingot 1
		tag oreingot name iron name ore = minecraft:raw_iron 1
		tag oreingot name steel = skycraft:steel_ingot 1
		tag oreingot name gold name ingot = minecraft:gold_ingot 1
		tag oreingot name gold name ore = minecraft:raw_gold 1
		tag oreingot name corundum name ingot = minecraft:copper_ingot 1
		tag oreingot name corundum name ore = minecraft:raw_copper 1
		tag gem name flawless name diamond = minecraft:diamond 2
		tag gem name flawless name emerald = minecraft:emerald 2
		tag gem name flawless name amethyst = minecraft:amethyst_shard 4
		tag gem name diamond = minecraft:diamond 1
		tag gem name emerald = minecraft:emerald 1
		tag gem name amethyst = minecraft:amethyst_shard 2
		tag gem name flawless name ruby = minecraft:redstone 8
		tag gem name ruby = minecraft:redstone 4
		tag gem name flawless name garnet = minecraft:redstone 4
		tag gem name garnet = minecraft:redstone 2
		tag gem name flawless name sapphire = minecraft:lapis_lazuli 8
		tag gem name sapphire = minecraft:lapis_lazuli 4
		tag gem name soul name gem name fragment = minecraft:amethyst_shard 1
		tag oreingot name refined name amber = minecraft:resin_brick 2
		tag oreingot name amber = minecraft:resin_clump 2
		tag oreingot name stalhrim = minecraft:blue_ice 1
		tag oreingot name quarried name stone = minecraft:cobblestone 4
		tag oreingot name chitin = minecraft:turtle_scute 1
		tag oreingot name hawk name claw = minecraft:bone 1
		tag oreingot name hawk name skull = minecraft:bone 2

		# Skyrim smithing materials for the SkyCraft gear sets (ore and ingot alike)
		tag oreingot name orichalcum = skycraft:orichalcum_ingot 1
		tag oreingot name dwarven = skycraft:dwarven_metal_ingot 1
		tag oreingot name moonstone = skycraft:refined_moonstone 1
		tag oreingot name malachite = skycraft:refined_malachite 1
		tag oreingot name ebony = skycraft:ebony_ingot 1
		name dwemer name scrap = skycraft:dwarven_metal_ingot 1
		name solid name dwemer name metal = skycraft:dwarven_metal_ingot 2
		name dwemer name plate = skycraft:dwarven_metal_ingot 2
		name dwemer name strut = skycraft:dwarven_metal_ingot 3
		name daedra name heart = skycraft:daedra_heart 1
		tag oreingot name dwemer name gyro = skycraft:dwarven_metal_ingot 2
		tag oreingot name dwemer name actuator = skycraft:dwarven_metal_ingot 2
		tag oreingot name dwemer name lever = skycraft:dwarven_metal_ingot 1
		tag oreingot name dwemer name cog = skycraft:dwarven_metal_ingot 1
		tag oreingot name dwemer name gear = skycraft:dwarven_metal_ingot 1

		# Arrows
		tag arrow = minecraft:arrow 1

		# Potions and poisons
		# A Skyrim potion becomes a Minecraft potion with the nearest effects, named like the Skyrim one,
		# and a poison becomes a splash potion to throw. Braces list the effects:
		#   minecraft:potion{effect:amplifier:seconds + effect:amplifier:seconds #rrggbb "Name"} N
		# The amplifier is 0 for level I; seconds is 0 for instant effects. A range like 0-2 grows
		# with the Skyrim name's tier: Minor, Weak and Brief are tier 0, a name with none of these
		# words tier 1, Plentiful, Draught, Potent, Extended, Enduring and Lasting tier 2, Vigorous,
		# Solution, Philter, Virulent, Malign, Persisting and Prolonged tier 3, Extreme, Elixir, Deadly
		# and Unceasing tier 4, Ultimate, Grand and Prime tier 5 (words the rule names don't count, so
		# "Potion of Lasting Potency" is tier 1). Without "Name" the potion takes the Skyrim name; the
		# color is optional (Minecraft mixes the effects' colors).
		#   effects: speed slowness haste mining_fatigue strength instant_health instant_damage
		#            jump_boost nausea regeneration resistance fire_resistance water_breathing
		#            invisibility blindness night_vision hunger weakness poison wither health_boost
		#            absorption saturation glowing levitation luck unluck slow_falling conduit_power
		#            dolphins_grace bad_omen hero_of_the_village darkness trial_omen raid_omen
		#            wind_charged weaving oozing infested breath_of_the_nautilus
		# A bracket names a vanilla potion instead; potion, splash_potion, lingering_potion and
		# tipped_arrow all take either (minecraft:tipped_arrow[poison] 4 gives four poison arrows).
		#   potions: healing strong_healing regeneration long_regeneration strong_regeneration strength
		#            long_strength strong_strength swiftness long_swiftness strong_swiftness invisibility
		#            long_invisibility night_vision long_night_vision fire_resistance long_fire_resistance
		#            water_breathing long_water_breathing leaping long_leaping strong_leaping slow_falling
		#            long_slow_falling turtle_master long_turtle_master strong_turtle_master luck poison
		#            long_poison strong_poison harming strong_harming weakness long_weakness slowness
		#            long_slowness strong_slowness wind_charged weaving oozing infested water awkward
		#            mundane thick
		# Health potions are red, magicka blue, stamina green. Magicka has no Minecraft meter, so it
		# becomes Absorption (a ward of golden hearts); stamina is the hunger bar and running.
		# Stay in Skyrim: the White Phial (it refills), the Lotus and Nightshade Extracts (quest poisons),
		# Unknown Potion, and drinks without a vendor keyword (Skooma, Potion of Blood).
		tag potion name phial = keep
		name philter name phantom = minecraft:potion{invisibility:0:60} 1
		tag potion name night name eye = minecraft:potion{night_vision:0:120-480} 1
		tag potion name detect name life = minecraft:potion{night_vision:0:60-240} 1
		tag potion name muffle = minecraft:potion{invisibility:0:20-60} 1
		# Restore, Fortify and Regenerate Health, Magicka and Stamina
		tag potion name well = minecraft:potion{instant_health:0-2:0 + absorption:0-2:30-90 + saturation:1-9:0 #9c4fb0} 1
		tag potion name healing = minecraft:potion{instant_health:0-2:0 #c62828} 1
		tag potion name restore name health = minecraft:potion{instant_health:0-2:0 #c62828} 1
		tag potion name escape = minecraft:potion{invisibility:0:30-180 + instant_health:0-2:0} 1
		tag potion name regenerate name health = minecraft:potion{regeneration:0-1:45-180 #c62828} 1
		tag potion name regeneration = minecraft:potion{regeneration:0-1:45-180 #c62828} 1
		tag potion name health = minecraft:potion{health_boost:0-3:60-180 #c62828} 1
		tag potion name extra name magicka = minecraft:potion{absorption:0-2:60-180 #2f5fd0} 1
		tag potion name fortify name magicka = minecraft:potion{absorption:0-2:60-180 #2f5fd0} 1
		tag potion name lasting name potency = minecraft:potion{absorption:0-1:150-300 #2f5fd0} 1
		tag potion name regenerate name magicka = minecraft:potion{absorption:0-1:150-300 #2f5fd0} 1
		tag potion name magicka = minecraft:potion{absorption:0-3:20-60 #2f5fd0} 1
		tag potion name enhanced name stamina = minecraft:potion{speed:0-1:60-180 + jump_boost:0-1:60-180 #3a9a3a} 1
		tag potion name fortify name stamina = minecraft:potion{speed:0-1:60-180 + jump_boost:0-1:60-180 #3a9a3a} 1
		tag potion name stallion's = minecraft:potion{speed:0:300 + jump_boost:0:300 #3a9a3a} 1
		tag potion name plunder = minecraft:potion{strength:0-1:120-300 + speed:0-1:120-300} 1
		tag potion name keenshot = minecraft:potion{night_vision:0:90-300 + speed:0-1:60-180} 1
		tag potion name regenerate name stamina = minecraft:potion{speed:0-1:120-300 + saturation:0-4:0 #3a9a3a} 1
		tag potion name vigor = minecraft:potion{speed:0-1:120-300 + saturation:0-4:0 #3a9a3a} 1
		tag potion name stamina = minecraft:potion{saturation:3-19:0 #3a9a3a} 1
		# Combat: weapon skills are Strength, armor skills Resistance, Block is Absorption
		tag potion name conflict = minecraft:potion{strength:0-2:60-180 + resistance:0-1:60-180 + speed:0:60-180} 1
		tag potion name warrior = minecraft:potion{strength:0-2:60-180} 1
		tag potion name one-handed = minecraft:potion{strength:0-2:60-180} 1
		tag potion name berserker = minecraft:potion{strength:0-2:60-180} 1
		tag potion name two-handed = minecraft:potion{strength:0-2:60-180} 1
		tag potion name true name shot = minecraft:potion{night_vision:0:90-300} 1
		tag potion name archery = minecraft:potion{night_vision:0:90-300} 1
		tag potion name marksman = minecraft:potion{night_vision:0:90-300} 1
		tag potion name defender = minecraft:potion{absorption:0-2:60-180} 1
		tag potion name block = minecraft:potion{absorption:0-2:60-180} 1
		tag potion name knight = minecraft:potion{resistance:0-2:60-180 + slowness:0:60-180} 1
		tag potion name heavy name armor = minecraft:potion{resistance:0-2:60-180 + slowness:0:60-180} 1
		tag potion name skirmisher's = minecraft:potion{resistance:0-1:60-180 + speed:0:60-180} 1
		tag potion name light name armor = minecraft:potion{resistance:0-1:60-180 + speed:0:60-180} 1
		# Stealth and thievery: Sneak is a short Invisibility, Lockpicking and Pickpocket are Luck
		tag potion name light name feet = minecraft:potion{invisibility:0:20-90} 1
		tag potion name sneak = minecraft:potion{invisibility:0:20-90} 1
		tag potion name invisibility = minecraft:potion{invisibility:0:30-180} 1
		tag potion name larceny = minecraft:potion{luck:0-2:60-180} 1
		tag potion name lockpicking = minecraft:potion{luck:0-2:60-180} 1
		tag potion name pickpocketing = minecraft:potion{luck:0-2:60-180} 1
		tag potion name pickpocket = minecraft:potion{luck:0-2:60-180} 1
		# Speech: Barter and Persuasion make villagers trade cheaper (Hero of the Village)
		tag potion name haggling = minecraft:potion{hero_of_the_village:0-2:60-300} 1
		tag potion name barter = minecraft:potion{hero_of_the_village:0-2:60-300} 1
		tag potion name glibness = minecraft:potion{hero_of_the_village:0-1:60-300} 1
		tag potion name persuasion = minecraft:potion{hero_of_the_village:0-1:60-300} 1
		# Crafting skills are Haste
		tag potion name blacksmith's = minecraft:potion{haste:0-2:60-180} 1
		tag potion name smithing = minecraft:potion{haste:0-2:60-180} 1
		tag potion name alchemy = minecraft:potion{haste:0-1:60-180} 1
		tag potion name enchanter's = minecraft:potion{haste:0-1:60-180} 1
		tag potion name enchanting = minecraft:potion{haste:0-1:60-180} 1
		# Magic schools: Alteration (stoneflesh) is Resistance, Conjuration (bound weapons) Strength,
		# Destruction Strength and Fire Resistance, Illusion Invisibility and Night Vision,
		# Restoration Regeneration
		tag potion name alteration = minecraft:potion{resistance:0-1:60-180} 1
		tag potion name conjurer's = minecraft:potion{strength:0-1:60-180} 1
		tag potion name conjuration = minecraft:potion{strength:0-1:60-180} 1
		tag potion name destruction = minecraft:potion{strength:0-1:60-180 + fire_resistance:0:60-180} 1
		tag potion name illusion = minecraft:potion{invisibility:0:20-60 + night_vision:0:60-180} 1
		tag potion name healer = minecraft:potion{regeneration:0-1:60-180} 1
		tag potion name restoration = minecraft:potion{regeneration:0-1:60-180} 1
		# Carry Weight ("Potion of Strength" in Skyrim) is Strength
		tag potion name strength = minecraft:potion{strength:0-1:120-300} 1
		tag potion name carry name weight = minecraft:potion{strength:0-1:120-300} 1
		# Resistances
		tag potion name resist name fire = minecraft:potion{fire_resistance:0:60-300} 1
		tag potion name resist name cold = minecraft:potion{resistance:0:60-180} 1
		tag potion name resist name frost = minecraft:potion{resistance:0:60-180} 1
		tag potion name wraith name essence = minecraft:potion{resistance:0:60-180} 1
		tag potion name resist name shock = minecraft:potion{resistance:0:60-180 + fire_resistance:0:60-180} 1
		tag potion name husk = minecraft:potion{resistance:0-1:60-180} 1
		tag potion name resist name magic = minecraft:potion{resistance:0-1:60-180} 1
		tag potion name resist name poison = minecraft:potion{regeneration:0-1:20-60} 1
		# Water, and cures (milk is Minecraft's cure)
		tag potion name waterbreathing = minecraft:potion{water_breathing:0:30-240 + conduit_power:0:30-240} 1
		tag potion name waterwalking = minecraft:potion{dolphins_grace:0:60-180} 1
		tag potion name cure = minecraft:milk_bucket 1
		# Poisons: Damage Health harms, lingering poisons poison, Ravage Health withers, magicka poisons
		# weaken, stamina poisons starve and slow, Paralysis all but stops the target
		tag poison name extract = keep
		tag poison name frostbite name venom = minecraft:splash_potion{poison:0:6 + slowness:1:8} 1
		tag poison name netch name poison = minecraft:splash_potion{poison:0:6 + slowness:1:8} 1
		tag poison name wraith name bane = minecraft:splash_potion{instant_damage:1:0} 1
		tag poison name lingering name magicka = minecraft:splash_potion{weakness:0:20 + unluck:0:20} 1
		tag poison name enduring name magicka = minecraft:splash_potion{weakness:0:30 + unluck:0:30} 1
		tag poison name lasting name magicka = minecraft:splash_potion{weakness:1:30 + unluck:0:30} 1
		tag poison name persisting name magicka = minecraft:splash_potion{weakness:1:40 + unluck:0:40} 1
		tag poison name unceasing name magicka = minecraft:splash_potion{weakness:2:40 + unluck:0:40} 1
		tag poison name lingering name stamina = minecraft:splash_potion{hunger:0:20 + slowness:0:20} 1
		tag poison name enduring name stamina = minecraft:splash_potion{hunger:0:30 + slowness:0:30} 1
		tag poison name lasting name stamina = minecraft:splash_potion{hunger:1:30 + slowness:0:30} 1
		tag poison name persisting name stamina = minecraft:splash_potion{hunger:1:40 + slowness:0:40} 1
		tag poison name unceasing name stamina = minecraft:splash_potion{hunger:2:40 + slowness:0:40} 1
		tag poison name lingering = minecraft:splash_potion{poison:0-2:10-25} 1
		tag poison name ravage name health = minecraft:splash_potion{wither:0-1:8-20} 1
		tag poison name ravage name magicka = minecraft:splash_potion{weakness:0-1:20-60} 1
		tag poison name ravage name stamina = minecraft:splash_potion{mining_fatigue:0-1:10-40 + hunger:0-1:10-40} 1
		tag poison name recovery = minecraft:splash_potion{weakness:0:20-90 + unluck:0-1:20-90} 1
		tag poison name magicka name regen = minecraft:splash_potion{weakness:0:20-90 + unluck:0-1:20-90} 1
		tag poison name vigor = minecraft:splash_potion{slowness:0:20-90 + hunger:0:20-90} 1
		tag poison name stamina name regen = minecraft:splash_potion{slowness:0:20-90 + hunger:0:20-90} 1
		tag poison name magicka = minecraft:splash_potion{weakness:0-1:10-40} 1
		tag poison name stamina = minecraft:splash_potion{hunger:0-2:10-30} 1
		tag poison name paralysis = minecraft:splash_potion{slowness:4:3-15 + mining_fatigue:2:3-15} 1
		tag poison name fear = minecraft:splash_potion{darkness:0:10-30 + weakness:0:10-30} 1
		tag poison name frenzy = minecraft:splash_potion{nausea:0:5-20} 1
		tag poison name slow = minecraft:splash_potion{slowness:0-2:15-45} 1
		tag poison name aversion name fire = minecraft:splash_potion{weakness:0-1:15-45} 1
		tag poison name weakness name fire = minecraft:splash_potion{weakness:0-1:15-45} 1
		tag poison name aversion name frost = minecraft:splash_potion{slowness:0-1:15-45} 1
		tag poison name weakness name frost = minecraft:splash_potion{slowness:0-1:15-45} 1
		tag poison name aversion name shock = minecraft:splash_potion{glowing:0:15-45 + weakness:0:15-45} 1
		tag poison name weakness name shock = minecraft:splash_potion{glowing:0:15-45 + weakness:0:15-45} 1
		tag poison name aversion name magic = minecraft:splash_potion{unluck:0-1:15-45 + weakness:0:15-45} 1
		tag poison name weakness name magic = minecraft:splash_potion{unluck:0-1:15-45 + weakness:0:15-45} 1
		tag poison name weakness name poison = minecraft:splash_potion{poison:0:5-15 + weakness:0:15-45} 1
		tag poison = minecraft:splash_potion{instant_damage:0-2:0} 1

		# Soul gems (portal keys)
		# A Greater, Grand or Black soul gem that already holds a soul (as Skyrim's shops and loot have them)
		# becomes a SkyCraft filled soul gem: use it on a lit Nether portal to attune it, then on a second
		# portal to bind the two for good. Petty, Lesser and Common gems, empty gems, gems you filled with
		# Soul Trap yourself, Azura's Star and the Black Star stay in Skyrim.
		tag soulgem name star = keep
		tag soulgem tag filled name greater = skycraft:filled_soul_gem 1
		tag soulgem tag filled name grand = skycraft:filled_soul_gem 1
		tag soulgem tag filled name black = skycraft:filled_soul_gem 1
		tag soulgem = keep

		# Bows from anywhere become Minecraft bows
		tag weapon name crossbow = minecraft:crossbow 1
		tag weapon name bow = minecraft:bow 1

		# Tools from anywhere become the Minecraft tool
		tag weapon name pickaxe = minecraft:iron_pickaxe 1
		tag weapon name woodcutter's name axe = minecraft:iron_axe 1
		tag weapon name fishing name rod = minecraft:fishing_rod 1

		# Gear bought in a Skyrim shop becomes the matching SkyCraft gear (looted gear salvages instead).
		# Gauntlets fill the greaves slot, since Minecraft has no glove slot.
		tag armor name shield from bought = minecraft:shield 1
		tag weapon material iron name sword from bought = skycraft:iron_sword 1
		tag weapon material iron name greatsword from bought = skycraft:iron_sword 1
		tag weapon material iron name dagger from bought = skycraft:iron_sword 1
		tag weapon material iron name axe from bought = skycraft:iron_war_axe 1
		tag weapon material iron name battleaxe from bought = skycraft:iron_war_axe 1
		tag weapon material iron name mace from bought = skycraft:iron_war_axe 1
		tag weapon material iron name warhammer from bought = skycraft:iron_war_axe 1
		tag armor material iron name helmet from bought = skycraft:iron_helmet 1
		tag armor material iron name armor from bought = skycraft:iron_cuirass 1
		tag armor material iron name cuirass from bought = skycraft:iron_cuirass 1
		tag armor material iron name gauntlets from bought = skycraft:iron_greaves 1
		tag armor material iron name boots from bought = skycraft:iron_boots 1
		tag weapon material steel name sword from bought = skycraft:steel_sword 1
		tag weapon material steel name greatsword from bought = skycraft:steel_sword 1
		tag weapon material steel name dagger from bought = skycraft:steel_sword 1
		tag weapon material steel name axe from bought = skycraft:steel_war_axe 1
		tag weapon material steel name battleaxe from bought = skycraft:steel_war_axe 1
		tag weapon material steel name mace from bought = skycraft:steel_war_axe 1
		tag weapon material steel name warhammer from bought = skycraft:steel_war_axe 1
		tag armor material steel name helmet from bought = skycraft:steel_helmet 1
		tag armor material steel name armor from bought = skycraft:steel_cuirass 1
		tag armor material steel name cuirass from bought = skycraft:steel_cuirass 1
		tag armor material steel name gauntlets from bought = skycraft:steel_greaves 1
		tag armor material steel name boots from bought = skycraft:steel_boots 1
		tag weapon material orcish name sword from bought = skycraft:orcish_sword 1
		tag weapon material orcish name greatsword from bought = skycraft:orcish_sword 1
		tag weapon material orcish name dagger from bought = skycraft:orcish_sword 1
		tag weapon material orcish name axe from bought = skycraft:orcish_war_axe 1
		tag weapon material orcish name battleaxe from bought = skycraft:orcish_war_axe 1
		tag weapon material orcish name mace from bought = skycraft:orcish_war_axe 1
		tag weapon material orcish name warhammer from bought = skycraft:orcish_war_axe 1
		tag armor material orcish name helmet from bought = skycraft:orcish_helmet 1
		tag armor material orcish name armor from bought = skycraft:orcish_cuirass 1
		tag armor material orcish name cuirass from bought = skycraft:orcish_cuirass 1
		tag armor material orcish name gauntlets from bought = skycraft:orcish_greaves 1
		tag armor material orcish name boots from bought = skycraft:orcish_boots 1
		tag weapon material dwarven name sword from bought = skycraft:dwarven_sword 1
		tag weapon material dwarven name greatsword from bought = skycraft:dwarven_sword 1
		tag weapon material dwarven name dagger from bought = skycraft:dwarven_sword 1
		tag weapon material dwarven name axe from bought = skycraft:dwarven_war_axe 1
		tag weapon material dwarven name battleaxe from bought = skycraft:dwarven_war_axe 1
		tag weapon material dwarven name mace from bought = skycraft:dwarven_war_axe 1
		tag weapon material dwarven name warhammer from bought = skycraft:dwarven_war_axe 1
		tag armor material dwarven name helmet from bought = skycraft:dwarven_helmet 1
		tag armor material dwarven name armor from bought = skycraft:dwarven_cuirass 1
		tag armor material dwarven name cuirass from bought = skycraft:dwarven_cuirass 1
		tag armor material dwarven name gauntlets from bought = skycraft:dwarven_greaves 1
		tag armor material dwarven name boots from bought = skycraft:dwarven_boots 1
		tag weapon material elven name sword from bought = skycraft:elven_sword 1
		tag weapon material elven name greatsword from bought = skycraft:elven_sword 1
		tag weapon material elven name dagger from bought = skycraft:elven_sword 1
		tag weapon material elven name axe from bought = skycraft:elven_war_axe 1
		tag weapon material elven name battleaxe from bought = skycraft:elven_war_axe 1
		tag weapon material elven name mace from bought = skycraft:elven_war_axe 1
		tag weapon material elven name warhammer from bought = skycraft:elven_war_axe 1
		tag armor material elven name helmet from bought = skycraft:elven_helmet 1
		tag armor material elven name armor from bought = skycraft:elven_cuirass 1
		tag armor material elven name cuirass from bought = skycraft:elven_cuirass 1
		tag armor material elven name gauntlets from bought = skycraft:elven_greaves 1
		tag armor material elven name boots from bought = skycraft:elven_boots 1
		tag weapon material glass name sword from bought = skycraft:glass_sword 1
		tag weapon material glass name greatsword from bought = skycraft:glass_sword 1
		tag weapon material glass name dagger from bought = skycraft:glass_sword 1
		tag weapon material glass name axe from bought = skycraft:glass_war_axe 1
		tag weapon material glass name battleaxe from bought = skycraft:glass_war_axe 1
		tag weapon material glass name mace from bought = skycraft:glass_war_axe 1
		tag weapon material glass name warhammer from bought = skycraft:glass_war_axe 1
		tag armor material glass name helmet from bought = skycraft:glass_helmet 1
		tag armor material glass name armor from bought = skycraft:glass_cuirass 1
		tag armor material glass name cuirass from bought = skycraft:glass_cuirass 1
		tag armor material glass name gauntlets from bought = skycraft:glass_greaves 1
		tag armor material glass name boots from bought = skycraft:glass_boots 1
		tag weapon material ebony name sword from bought = skycraft:ebony_sword 1
		tag weapon material ebony name greatsword from bought = skycraft:ebony_sword 1
		tag weapon material ebony name dagger from bought = skycraft:ebony_sword 1
		tag weapon material ebony name axe from bought = skycraft:ebony_war_axe 1
		tag weapon material ebony name battleaxe from bought = skycraft:ebony_war_axe 1
		tag weapon material ebony name mace from bought = skycraft:ebony_war_axe 1
		tag weapon material ebony name warhammer from bought = skycraft:ebony_war_axe 1
		tag armor material ebony name helmet from bought = skycraft:ebony_helmet 1
		tag armor material ebony name armor from bought = skycraft:ebony_cuirass 1
		tag armor material ebony name cuirass from bought = skycraft:ebony_cuirass 1
		tag armor material ebony name gauntlets from bought = skycraft:ebony_greaves 1
		tag armor material ebony name boots from bought = skycraft:ebony_boots 1
		tag weapon material daedric name sword from bought = skycraft:daedric_sword 1
		tag weapon material daedric name greatsword from bought = skycraft:daedric_sword 1
		tag weapon material daedric name dagger from bought = skycraft:daedric_sword 1
		tag weapon material daedric name axe from bought = skycraft:daedric_war_axe 1
		tag weapon material daedric name battleaxe from bought = skycraft:daedric_war_axe 1
		tag weapon material daedric name mace from bought = skycraft:daedric_war_axe 1
		tag weapon material daedric name warhammer from bought = skycraft:daedric_war_axe 1
		tag armor material daedric name helmet from bought = skycraft:daedric_helmet 1
		tag armor material daedric name armor from bought = skycraft:daedric_cuirass 1
		tag armor material daedric name cuirass from bought = skycraft:daedric_cuirass 1
		tag armor material daedric name gauntlets from bought = skycraft:daedric_greaves 1
		tag armor material daedric name boots from bought = skycraft:daedric_boots 1

		# Looted iron and steel gear salvages into ingots
		tag weapon material iron from looted = minecraft:iron_ingot 1
		tag weapon material steel from looted = skycraft:steel_ingot 1
		tag armor material iron from looted = minecraft:iron_ingot 2
		tag armor material steel from looted = skycraft:steel_ingot 2
		tag armor material hide from looted = minecraft:leather 2

		# Clothing (pending your call)
		# Remove the '#' in front of these lines to turn Skyrim clothes into Minecraft leather armor by
		# slot: hoods and hats are helmets, boots and shoes boots, gloves leggings (Minecraft has no glove
		# slot), and robes, clothes, dresses and tunics chestplates. A child's dress or tunic from
		# Hearthfire carries no clothing tag, so it has a rule of its own.
		#tag clothing name hood = minecraft:leather_helmet 1
		#tag clothing name hat = minecraft:leather_helmet 1
		#tag clothing name cap = minecraft:leather_helmet 1
		#tag clothing name cowl = minecraft:leather_helmet 1
		#tag clothing name boots = minecraft:leather_boots 1
		#tag clothing name shoes = minecraft:leather_boots 1
		#tag clothing name sandals = minecraft:leather_boots 1
		#tag clothing name footwraps = minecraft:leather_boots 1
		#tag clothing name gloves = minecraft:leather_leggings 1
		#tag clothing name gauntlets = minecraft:leather_leggings 1
		#tag clothing name armguards = minecraft:leather_leggings 1
		#tag clothing name cuffs = minecraft:leather_leggings 1
		#tag clothing name wraps = minecraft:leather_leggings 1
		#tag clothing = minecraft:leather_chestplate 1
		#name girl's name dress = minecraft:leather_chestplate 1
		#name boy's name tunic = minecraft:leather_chestplate 1

		# Jewelry (pending your call)
		# Remove the '#' in front of these lines to turn rings, necklaces, amulets and circlets into
		# nuggets of their metal, plus the gem when the name has one (gold nuggets for any jeweled piece).
		#tag jewelry name diamond = minecraft:gold_nugget 3 + minecraft:diamond 1
		#tag jewelry name emerald = minecraft:gold_nugget 3 + minecraft:emerald 1
		#tag jewelry name sapphire = minecraft:gold_nugget 3 + minecraft:lapis_lazuli 2
		#tag jewelry name ruby = minecraft:gold_nugget 3 + minecraft:redstone 2
		#tag jewelry name garnet = minecraft:gold_nugget 3 + minecraft:redstone 1
		#tag jewelry name amethyst = minecraft:gold_nugget 3 + minecraft:amethyst_shard 1
		#tag jewelry name gold = minecraft:gold_nugget 4
		#tag jewelry name silver = minecraft:iron_nugget 4
		#tag jewelry name copper = minecraft:copper_nugget 4
		#tag jewelry = minecraft:iron_nugget 3

		# Everything above had its chance. Gear, clothing, jewelry, potions, food and ingredients no rule
		# above took stay in Skyrim, so the household rules below only ever see misc items and lights.
		tag weapon = keep
		tag armor = keep
		tag clothing = keep
		tag jewelry = keep
		tag potion = keep
		tag poison = keep
		tag ingredient = keep
		tag foodraw = keep
		# Keepsakes and quest pieces stay too: the Thieves Guild's jeweled displays, Ysgramor's Soup Spoon,
		# Balbus' Fork, Michaela's Flagon, Wylandriah's Spoon and the White Phial's Mammoth Tusk Powder.
		name jeweled = keep
		name ysgramor's = keep
		name balbus' = keep
		name michaela's = keep
		name wylandriah's = keep
		name tusk name powder = keep

		# Dwemer clutter melts down into Dwemer metal
		tag clutter name dwemer name bowl = skycraft:dwarven_metal_ingot 1
		tag clutter name dwemer name cup = skycraft:dwarven_metal_ingot 1
		tag clutter name dwemer name dish = skycraft:dwarven_metal_ingot 1
		tag clutter name dwemer name pan = skycraft:dwarven_metal_ingot 1
		tag clutter name dwemer name pot = skycraft:dwarven_metal_ingot 2
		tag clutter name dwemer name urn = skycraft:dwarven_metal_ingot 2
		name centurion name dynamo name core = minecraft:redstone_block 1

		# Light, tools and household goods
		name torch = minecraft:torch 4
		name lantern = minecraft:lantern 1
		name glazed name candles = minecraft:candle 2
		name silver name candlestick = minecraft:iron_nugget 4
		name candlestick = minecraft:candle 1
		name charcoal = minecraft:charcoal 1
		name bucket = minecraft:bucket 1
		name shovel = minecraft:iron_shovel 1
		name pitchfork = minecraft:iron_hoe 1
		name tongs = minecraft:shears 1
		name broom = minecraft:brush 1
		name hammer = minecraft:iron_nugget 3
		name saw = minecraft:iron_nugget 3
		name oar = minecraft:stick 3
		name linen name wrap = minecraft:string 2
		tag clutter name bloody name rags = minecraft:string 1
		name honey name jar = minecraft:honey_bottle 1
		name jar = minecraft:glass_bottle 1
		name empty name bottle = minecraft:glass_bottle 1
		name silver name goblet = minecraft:iron_nugget 3
		name silver name jug = minecraft:iron_nugget 4
		name silver name platter = minecraft:iron_nugget 4
		name tankard = minecraft:glass_bottle 1
		name flagon = minecraft:glass_bottle 1
		name goblet = minecraft:glass_bottle 1
		name cup = minecraft:glass_bottle 1
		name jug = minecraft:glass_bottle 1
		name bowl = minecraft:bowl 1
		name cast name iron name pot = minecraft:iron_nugget 6
		name kettle = minecraft:iron_nugget 3
		name clothes name iron = minecraft:iron_nugget 3
		tag clutter name pot = minecraft:iron_nugget 2
		name fork = minecraft:iron_nugget 1
		name knife = minecraft:iron_nugget 1
		name spoon = minecraft:iron_nugget 1
		name roll name paper = minecraft:paper 3
		tag clutter name quill = minecraft:feather 1
		tag clutter name inkwell = minecraft:ink_sac 1
		tag clutter name ruined name book = minecraft:paper 2
		tag clutter name burned name book = minecraft:charcoal 1
		tag clutter name urn = minecraft:decorated_pot 1
		tag clutter name lute = minecraft:note_block 1
		tag clutter name drum = minecraft:note_block 1
		tag clutter name flute = minecraft:note_block 1
		tag clutter name unmelting name snow = minecraft:snow_block 1
		tag clutter name pyroil name tar = minecraft:fire_charge 2
		name goat name horns = minecraft:bone 2
		name horker name tusk = minecraft:bone 2
		name mammoth name tusk = minecraft:bone 4
		name chaurus name chitin = minecraft:turtle_scute 1

		# Hearthfire building materials
		tag clutter name clay = minecraft:clay_ball 4
		tag clutter name glass = minecraft:glass 2
		tag clutter name straw = minecraft:wheat 1
		name sawn name log = minecraft:oak_planks 4
		name nails = minecraft:iron_nugget 1
		name iron name fittings = minecraft:iron_nugget 4
		name hinge = minecraft:iron_nugget 2
		name lock = minecraft:iron_nugget 3
		""";

	private final List<Rule> rules;

	private ItemRules(List<Rule> rules) {
		this.rules = rules;
	}

	public int size() {
		return this.rules.size();
	}

	/** The words of a name, lower case: what {@code name} conditions (and LootRules) match against. */
	public static List<String> words(String name) {
		List<String> out = new ArrayList<>();
		if (name != null) {
			for (String w : NON_WORD.split(name.toLowerCase(Locale.ROOT))) {
				if (!w.isEmpty()) {
					out.add(w);
				}
			}
		}
		return out;
	}

	/** {@code name} as an ItemEvent carries it: its first {@link #NAME_BYTES} bytes of UTF-8. */
	public static String cut(String name) {
		byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
		return bytes.length <= NAME_BYTES ? name : new String(bytes, 0, NAME_BYTES, StandardCharsets.UTF_8);
	}

	/** The whole name of a cut one of the {@link #LONG_NAMES}; any other name as it is. */
	public static String fullName(String name) {
		return name == null ? null : RESTORE.getOrDefault(name, name);
	}

	private static Map<String, String> restoreTable() {
		Map<String, String> out = new HashMap<>();
		Set<String> shared = new HashSet<>();
		for (String name : LONG_NAMES) {
			String cut = cut(name);
			if (!cut.equals(name) && out.putIfAbsent(cut, name) != null && !out.get(cut).equals(name)) {
				shared.add(cut);
			}
		}
		shared.forEach(out::remove);
		return Map.copyOf(out);
	}

	/** The strongest {@link #TIERS} word in {@code words}, not counting the rule's own name words; {@link #DEFAULT_TIER} without one. */
	static int tier(List<String> words, List<String> ruleWords) {
		int best = -1;
		for (String w : words) {
			Integer t = TIERS.get(w);
			if (t != null && !ruleWords.contains(w)) {
				best = Math.max(best, t);
			}
		}
		return best < 0 ? DEFAULT_TIER : best;
	}

	/** {@code low} at tier 0 to {@code high} at tier {@link #MAX_TIER}, rounded. */
	static int scale(int low, int high, int tier) {
		return low + Math.round((high - low) * Math.max(0, Math.min(MAX_TIER, tier)) / (float) MAX_TIER);
	}

	/**
	 * The first matching rule's result, with effect values picked for the name's tier and custom potions named
	 * after the Skyrim item; keep (stays in Skyrim) when nothing matches.
	 */
	public Result match(Item item) {
		String full = fullName(item.name());
		List<String> words = words(full);
		if (full != null && !full.equals(item.name())) {
			// The cut words match too, for rules written against the cut name.
			for (String w : words(item.name())) {
				if (!words.contains(w)) {
					words.add(w);
				}
			}
		}
		for (Rule rule : this.rules) {
			if (rule.matches(item, words)) {
				Result r = rule.result();
				return r.keep() ? r : r.at(tier(words, rule.nameWords()), full);
			}
		}
		return KEEP;
	}

	public static ItemRules parse(String text, List<String> problems) {
		List<Rule> out = new ArrayList<>();
		// Unknown potion and effect ids, with the lines that use them: reported once each, after the rules.
		Map<String, List<Integer>> unknownPotions = new LinkedHashMap<>();
		Map<String, List<Integer>> unknownEffects = new LinkedHashMap<>();
		int n = 0;
		for (String raw : text.split("\\R")) {
			n++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq <= 0) {
				problems.add("line " + n + ": no '='");
				continue;
			}
			String[] left = line.substring(0, eq).strip().toLowerCase(Locale.ROOT).split("\\s+");
			String rhs = line.substring(eq + 1).strip();
			if (left.length % 2 != 0) {
				problems.add("line " + n + ": conditions come in pairs, like 'tag food'");
				continue;
			}
			int tagMask = 0, material = -1, sourceMask = 0;
			List<String> names = new ArrayList<>();
			String bad = null;
			for (int i = 0; i < left.length && bad == null; i += 2) {
				String key = left[i], value = left[i + 1];
				switch (key) {
					case "tag" -> {
						int bit = TAGS.indexOf(value);
						if (bit < 0) {
							bad = "unknown tag '" + value + "'";
						} else {
							tagMask |= 1 << bit;
						}
					}
					case "material" -> {
						material = MATERIALS.indexOf(value);
						if (material < 0) {
							bad = "unknown material '" + value + "'";
						}
					}
					case "from" -> {
						if (value.equals("looted")) {
							sourceMask |= (1 << 1) | (1 << 2);
						} else if (SOURCES.contains(value)) {
							sourceMask |= 1 << SOURCES.indexOf(value);
						} else {
							bad = "unknown source '" + value + "'";
						}
					}
					case "name" -> {
						// Split like item names are, so "name half-eaten" needs both "half" and "eaten".
						List<String> parts = words(value);
						if (parts.isEmpty()) {
							bad = "name '" + value + "' has no letters or digits";
						} else {
							names.addAll(parts);
						}
					}
					default -> bad = "unknown condition '" + key + "'";
				}
			}
			if (bad != null) {
				problems.add("line " + n + ": " + bad);
				continue;
			}
			Result result;
			if (rhs.equalsIgnoreCase("keep")) {
				result = KEEP;
			} else {
				List<String> parts = splitOutputs(rhs);
				List<Result> outputs = new ArrayList<>();
				for (String part : parts) {
					Result r = parseOutput(part, n, problems, unknownPotions, unknownEffects);
					if (r == null) {
						break;
					}
					outputs.add(r);
				}
				if (outputs.size() < parts.size()) {
					continue;
				}
				Result first = outputs.get(0);
				result = new Result(first.item(), first.perItem(), first.potion(), first.effects(), first.color(), first.name(),
					List.copyOf(outputs.subList(1, outputs.size())));
			}
			out.add(new Rule(tagMask, material, sourceMask, List.copyOf(names), result));
		}
		unknownPotions.forEach((id, lines) -> problems.add("line " + lines.get(0) + ": unknown potion '" + id + "', rule skipped" + alsoLines(lines)));
		unknownEffects.forEach((id, lines) -> problems.add("line " + lines.get(0) + ": unknown effect '" + id + "', rule skipped" + alsoLines(lines)));
		return new ItemRules(List.copyOf(out));
	}

	private static String alsoLines(List<Integer> lines) {
		return lines.size() > 1 ? " (also line " + lines.stream().skip(1).map(String::valueOf).collect(Collectors.joining(", ")) + ")" : "";
	}

	/** The right side split at each {@code +} outside brackets, braces and quotes. */
	static List<String> splitOutputs(String rhs) {
		List<String> out = new ArrayList<>();
		int depth = 0, start = 0;
		boolean quoted = false;
		for (int i = 0; i < rhs.length(); i++) {
			char c = rhs.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (!quoted && (c == '[' || c == '{')) {
				depth++;
			} else if (!quoted && (c == ']' || c == '}')) {
				depth = Math.max(0, depth - 1);
			} else if (!quoted && depth == 0 && c == '+') {
				out.add(rhs.substring(start, i).strip());
				start = i + 1;
			}
		}
		out.add(rhs.substring(start).strip());
		return out;
	}

	/**
	 * One item of a right side: {@code item[potion]{effects #color "name"} N}. Null when it's bad (the problem is
	 * added) or names an unknown potion or effect (recorded, reported once after the rules).
	 */
	private static Result parseOutput(String text, int n, List<String> problems, Map<String, List<Integer>> unknownPotions,
		Map<String, List<Integer>> unknownEffects) {
		String rhs = text;
		// {effects}: the potion's own effects; read first, since a quoted name may hold any character.
		String body = null;
		int brace = rhs.indexOf('{');
		if (brace >= 0) {
			int close = closingBrace(rhs, brace);
			if (close < 0) {
				problems.add("line " + n + ": no '}' after '{'");
				return null;
			}
			body = rhs.substring(brace + 1, close);
			rhs = (rhs.substring(0, brace) + " " + rhs.substring(close + 1)).strip();
		}
		// minecraft:potion[healing] 2: the bracket names the potion, the rest reads like any other result.
		String potion = null;
		int open = rhs.indexOf('[');
		if (open >= 0) {
			int close = rhs.indexOf(']', open);
			if (close < 0) {
				problems.add("line " + n + ": no ']' after '['");
				return null;
			}
			potion = rhs.substring(open + 1, close).strip().toLowerCase(Locale.ROOT);
			if (potion.startsWith("minecraft:")) {
				potion = potion.substring("minecraft:".length());
			}
			rhs = (rhs.substring(0, open) + " " + rhs.substring(close + 1)).strip();
		}
		String[] right = rhs.split("\\s+");
		int per = 1;
		if (right.length >= 2) {
			try {
				per = Integer.parseInt(right[1]);
			} catch (NumberFormatException e) {
				problems.add("line " + n + ": bad count '" + right[1] + "'");
				return null;
			}
		}
		if (right[0].isEmpty() || right[0].equalsIgnoreCase("keep") || per < 1 || per > 64) {
			problems.add("line " + n + ": needs an item and a count from 1 to 64");
			return null;
		}
		if (potion != null || body != null) {
			String id = right[0].toLowerCase(Locale.ROOT);
			if (!POTION_ITEMS.contains(id.indexOf(':') < 0 ? "minecraft:" + id : id)) {
				problems.add("line " + n + ": only potion, splash_potion, lingering_potion and tipped_arrow hold a potion, not '" + right[0] + "'");
				return null;
			}
		}
		if (potion != null) {
			if (potion.isEmpty()) {
				problems.add("line " + n + ": '[]' names no potion");
				return null;
			}
			if (!POTIONS.contains(potion)) {
				unknownPotions.computeIfAbsent(potion, k -> new ArrayList<>()).add(n);
				return null;
			}
		}
		List<Effect> effects = List.of();
		int color = -1;
		String name = null;
		if (body != null) {
			List<Effect> fx = new ArrayList<>();
			boolean unknown = false;
			for (String token : effectTokens(body)) {
				if (token.startsWith("\"")) {
					if (token.length() < 2 || !token.endsWith("\"")) {
						problems.add("line " + n + ": no closing '\"' after the name");
						return null;
					}
					name = token.substring(1, token.length() - 1).strip();
					if (name.isEmpty()) {
						problems.add("line " + n + ": '\"\"' names nothing");
						return null;
					}
				} else if (token.startsWith("#")) {
					if (!COLOR.matcher(token).matches()) {
						problems.add("line " + n + ": color '" + token + "' isn't #rrggbb");
						return null;
					}
					color = Integer.parseInt(token.substring(1), 16);
				} else {
					String spec = token.toLowerCase(Locale.ROOT);
					if (spec.startsWith("minecraft:")) {
						spec = spec.substring("minecraft:".length());
					}
					String[] p = spec.split(":", -1);
					if (p.length != 3 || p[0].isEmpty()) {
						problems.add("line " + n + ": effect '" + token + "' needs effect:amplifier:seconds, like speed:0:60");
						return null;
					}
					int[] amp = range(p[1], MAX_AMPLIFIER), secs = range(p[2], MAX_SECONDS);
					if (amp == null) {
						problems.add("line " + n + ": amplifier '" + p[1] + "' of '" + token + "' isn't 0 to " + MAX_AMPLIFIER + " or a range like 0-2");
						return null;
					}
					if (secs == null) {
						problems.add("line " + n + ": seconds '" + p[2] + "' of '" + token + "' isn't 0 to " + MAX_SECONDS + " or a range like 60-180");
						return null;
					}
					if (!EFFECTS.contains(p[0])) {
						unknownEffects.computeIfAbsent(p[0], k -> new ArrayList<>()).add(n);
						unknown = true;
						continue;
					}
					fx.add(new Effect(p[0], amp[0], secs[0], amp[1], secs[1]));
				}
			}
			if (unknown) {
				return null;
			}
			if (fx.isEmpty()) {
				problems.add("line " + n + ": '{}' names no effect");
				return null;
			}
			effects = List.copyOf(fx);
		}
		return new Result(right[0], per, potion, effects, color, name, List.of());
	}

	/** Where the brace opened at {@code open} closes, skipping quoted text; -1 when it doesn't. */
	private static int closingBrace(String s, int open) {
		boolean quoted = false;
		for (int i = open + 1; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (c == '}' && !quoted) {
				return i;
			}
		}
		return -1;
	}

	/** The parts of an effect list: effects and a color split at spaces and {@code +}, a quoted name whole. */
	private static List<String> effectTokens(String body) {
		List<String> out = new ArrayList<>();
		int i = 0;
		while (i < body.length()) {
			char c = body.charAt(i);
			if (Character.isWhitespace(c) || c == '+') {
				i++;
			} else if (c == '"') {
				int end = body.indexOf('"', i + 1);
				out.add(end < 0 ? body.substring(i) : body.substring(i, end + 1));
				i = end < 0 ? body.length() : end + 1;
			} else {
				int j = i;
				while (j < body.length() && !Character.isWhitespace(body.charAt(j)) && body.charAt(j) != '+' && body.charAt(j) != '"') {
					j++;
				}
				out.add(body.substring(i, j));
				i = j;
			}
		}
		return out;
	}

	/** {@code 2} as {2, 2} and {@code 0-2} as {0, 2}, each at most {@code max}; null when it's neither. */
	private static int[] range(String s, int max) {
		java.util.regex.Matcher m = RANGE.matcher(s);
		if (!m.matches()) {
			return null;
		}
		int low = Integer.parseInt(m.group(1)), high = m.group(2) != null ? Integer.parseInt(m.group(2)) : low;
		return low > max || high > max ? null : new int[] { low, high };
	}

	/** Tag names set in {@code tags}, for logs. */
	public static String tagNames(int tags) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < TAGS.size(); i++) {
			if ((tags & (1 << i)) != 0) {
				if (!sb.isEmpty()) {
					sb.append(',');
				}
				sb.append(TAGS.get(i));
			}
		}
		return sb.toString();
	}

	static int bit(String tag) {
		return 1 << TAGS.indexOf(tag);
	}
}
