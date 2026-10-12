package dev.skycraft.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemRulesTest {
	private static final int WORLD = 0, CORPSE = 1, CONTAINER = 2, ACTOR = 3, BOUGHT = 4;

	private static ItemRules defaults() {
		List<String> problems = new ArrayList<>();
		ItemRules rules = ItemRules.parse(ItemRules.DEFAULT_TEXT, problems);
		assertTrue(problems.isEmpty(), problems.toString());
		return rules;
	}

	private static int tags(String... names) {
		int t = 0;
		for (String n : names) {
			t |= ItemRules.bit(n);
		}
		return t;
	}

	private static int material(String name) {
		return ItemRules.MATERIALS.indexOf(name);
	}

	private static String convert(ItemRules rules, int tags, int material, int source, String name) {
		ItemRules.Result r = rules.match(new ItemRules.Item(tags, material, source, name));
		return r.keep() ? "keep" : r.label() + " " + r.perItem();
	}

	@Test
	void itemsFromTheDetectTestConvertAsPlanned() {
		ItemRules rules = defaults();
		// Seen in the build 3 log.
		assertEquals("minecraft:potato 1", convert(rules, tags("food"), 0, CONTAINER, "Potato"));
		assertEquals("minecraft:pumpkin_pie 1", convert(rules, tags("food"), 0, CORPSE, "Apple Pie"));
		assertEquals("keep", convert(rules, tags("food"), 0, CORPSE, "Wine"));
		assertEquals("minecraft:leather 1", convert(rules, tags("hide"), 0, CONTAINER, "Bear Pelt"));
		assertEquals("keep", convert(rules, tags("gold", "clutter"), 0, CONTAINER, "Gold"));
		assertEquals("keep", convert(rules, tags("key"), 0, CORPSE, "Key to Birna's Oddments"));
		assertEquals("minecraft:sugar 1", convert(rules, tags("ingredient", "foodraw"), 0, CONTAINER, "Salt Pile"));
		assertEquals("keep", convert(rules, tags("clothing"), 0, CORPSE, "Belted Tunic"));
		assertEquals("keep", convert(rules, tags("jewelry"), 0, CORPSE, "Silver Ring"));
		assertEquals("minecraft:iron_ingot 1", convert(rules, tags("weapon"), 2, CORPSE, "Iron Dagger"));
		assertEquals("keep", convert(rules, tags("weapon", "clutter"), 1, CONTAINER, "Wooden Sword"));
	}

	@Test
	void appleIsNotAnApplePieAndCookedSalmonWins() {
		ItemRules rules = defaults();
		assertEquals("minecraft:apple 1", convert(rules, tags("food"), 0, WORLD, "Red Apple"));
		assertEquals("minecraft:cooked_salmon 1", convert(rules, tags("food"), 0, WORLD, "Salmon Steak"));
		assertEquals("minecraft:salmon 1", convert(rules, tags("food", "foodraw"), 0, WORLD, "Salmon Meat"));
		assertEquals("minecraft:beef 1", convert(rules, tags("food", "foodraw"), 0, WORLD, "Horker Meat"));
	}

	@Test
	void gearOnlySalvagesWhenLooted() {
		ItemRules rules = defaults();
		assertEquals("keep", convert(rules, tags("weapon"), 3, WORLD, "Steel Sword"));
		assertEquals("skycraft:steel_ingot 1", convert(rules, tags("weapon"), 3, CONTAINER, "Steel Sword"));
		assertEquals("skycraft:steel_ingot 2", convert(rules, tags("armor"), 3, CORPSE, "Steel Armor"));
		assertEquals("keep", convert(rules, tags("weapon"), 9, CORPSE, "Ebony Sword"));
	}

	@Test
	void boughtGearBecomesTheMatchingGear() {
		ItemRules rules = defaults();
		int steel = ItemRules.MATERIALS.indexOf("steel"), elven = ItemRules.MATERIALS.indexOf("elven"), iron = ItemRules.MATERIALS.indexOf("iron");
		int daedric = ItemRules.MATERIALS.indexOf("daedric"), hide = ItemRules.MATERIALS.indexOf("hide");
		// The user's report: a Hunting Bow bought from Elrindir became a steel ingot.
		assertEquals("minecraft:bow 1", convert(rules, tags("weapon"), steel, BOUGHT, "Hunting Bow"));
		assertEquals("minecraft:bow 1", convert(rules, tags("weapon"), steel, CORPSE, "Hunting Bow"));
		assertEquals("skycraft:steel_sword 1", convert(rules, tags("weapon"), steel, BOUGHT, "Steel Sword"));
		assertEquals("skycraft:steel_war_axe 1", convert(rules, tags("weapon"), steel, BOUGHT, "Steel War Axe"));
		assertEquals("skycraft:daedric_sword 1", convert(rules, tags("weapon"), daedric, BOUGHT, "Daedric Greatsword"));
		assertEquals("skycraft:elven_helmet 1", convert(rules, tags("armor"), elven, BOUGHT, "Elven Helmet"));
		assertEquals("skycraft:iron_greaves 1", convert(rules, tags("armor"), iron, BOUGHT, "Iron Gauntlets"));
		assertEquals("skycraft:steel_boots 1", convert(rules, tags("armor"), steel, BOUGHT, "Steel Shin Boots"));
		assertEquals("minecraft:leather_chestplate 1", convert(rules, tags("armor"), hide, BOUGHT, "Leather Armor"));
		assertEquals("minecraft:shield 1", convert(rules, tags("armor"), iron, BOUGHT, "Iron Shield"));
		// Bought gear never salvages; looted gear still does.
		assertEquals("keep", convert(rules, tags("weapon"), ItemRules.MATERIALS.indexOf("silver"), BOUGHT, "Silver Sword"));
		assertEquals("skycraft:steel_ingot 1", convert(rules, tags("weapon"), steel, CONTAINER, "Steel Sword"));
		// Gold paid by a merchant stays in Skyrim; bought food still converts.
		assertEquals("keep", convert(rules, tags("gold", "clutter"), 0, BOUGHT, "Gold"));
		assertEquals("minecraft:pumpkin_pie 1", convert(rules, tags("food"), 0, ACTOR, "Apple Pie"));
	}

	@Test
	void floraFromTheTestBecomesMinecraftPlants() {
		ItemRules rules = defaults();
		// Seen in the dev8 test log as "stays in Skyrim".
		assertEquals("minecraft:cornflower 1", convert(rules, tags("ingredient"), 0, WORLD, "Blue Mountain Flower"));
		assertEquals("minecraft:allium 1", convert(rules, tags("ingredient"), 0, WORLD, "Purple Mountain Flower"));
		assertEquals("minecraft:cod 1", convert(rules, tags("foodraw"), 0, CORPSE, "Mudcrab Legs"));
		assertEquals("minecraft:orange_tulip 1", convert(rules, tags("ingredient"), 0, WORLD, "Dragon's Tongue"));
		assertEquals("minecraft:feather 2", convert(rules, tags("ingredient"), 0, CORPSE, "Hawk Feathers"));
		assertEquals("minecraft:rotten_flesh 1", convert(rules, tags("ingredient"), 0, CORPSE, "Skeever Tail"));
		assertEquals("keep", convert(rules, tags("ingredient"), 0, WORLD, "Nirnroot"));
		assertEquals("keep", convert(rules, tags("ingredient"), 0, WORLD, "Crimson Nirnroot"));
	}

	@Test
	void metals() {
		ItemRules rules = defaults();
		assertEquals("minecraft:iron_ingot 1", convert(rules, tags("oreingot"), 0, WORLD, "Iron Ingot"));
		assertEquals("minecraft:raw_iron 1", convert(rules, tags("oreingot"), 0, WORLD, "Iron Ore"));
		assertEquals("minecraft:raw_copper 1", convert(rules, tags("oreingot"), 0, WORLD, "Corundum Ore"));
		assertEquals("skycraft:steel_ingot 1", convert(rules, tags("oreingot"), 0, WORLD, "Steel Ingot"));
	}

	@Test
	void skyrimSmithingMaterialsBecomeGearMaterials() {
		ItemRules rules = defaults();
		assertEquals("skycraft:ebony_ingot 1", convert(rules, tags("oreingot"), 0, WORLD, "Ebony Ingot"));
		assertEquals("skycraft:ebony_ingot 1", convert(rules, tags("oreingot"), 0, CONTAINER, "Ebony Ore"));
		assertEquals("skycraft:orichalcum_ingot 1", convert(rules, tags("oreingot"), 0, WORLD, "Orichalcum Ingot"));
		assertEquals("skycraft:orichalcum_ingot 1", convert(rules, tags("oreingot"), 0, WORLD, "Orichalcum Ore"));
		assertEquals("skycraft:dwarven_metal_ingot 1", convert(rules, tags("oreingot"), 0, WORLD, "Dwarven Metal Ingot"));
		assertEquals("skycraft:refined_moonstone 1", convert(rules, tags("oreingot"), 0, WORLD, "Refined Moonstone"));
		assertEquals("skycraft:refined_moonstone 1", convert(rules, tags("oreingot"), 0, WORLD, "Moonstone Ore"));
		assertEquals("skycraft:refined_malachite 1", convert(rules, tags("oreingot"), 0, WORLD, "Refined Malachite"));
		assertEquals("skycraft:refined_malachite 1", convert(rules, tags("oreingot"), 0, WORLD, "Malachite Ore"));
		assertEquals("skycraft:daedra_heart 1", convert(rules, tags("ingredient"), 0, CORPSE, "Daedra Heart"));
		assertEquals("skycraft:leather_strips 1", convert(rules, 0, 0, CONTAINER, "Leather Strips"));
		assertEquals("skycraft:leather_strips 1", convert(rules, tags("hide"), 0, CONTAINER, "Leather Strips"));
		assertEquals("minecraft:leather 1", convert(rules, 0, 0, CONTAINER, "Leather"));
		assertEquals("skycraft:dwarven_metal_ingot 1", convert(rules, tags("clutter"), 0, WORLD, "Bent Dwemer Scrap Metal"));
		assertEquals("skycraft:dwarven_metal_ingot 2", convert(rules, tags("clutter"), 0, WORLD, "Solid Dwemer Metal"));
		assertEquals("skycraft:dwarven_metal_ingot 3", convert(rules, tags("clutter"), 0, WORLD, "Large Dwemer Strut"));
		// Corundum still feeds copper, which the orichalcum and dwarven ingot recipes use.
		assertEquals("minecraft:copper_ingot 1", convert(rules, tags("oreingot"), 0, WORLD, "Corundum Ingot"));
		// Looted gear of the higher tiers stays in Skyrim (only iron and steel salvage).
		assertEquals("keep", convert(rules, tags("weapon"), 10, CORPSE, "Daedric Sword"));
	}

	@Test
	void everySkycraftItemInTheDefaultsExists() {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("skycraft:([a-z0-9_]+)").matcher(ItemRules.DEFAULT_TEXT);
		int found = 0;
		while (m.find()) {
			found++;
			assertTrue(dev.skycraft.gear.GearTiers.allItemIds().contains(m.group(1)) || dev.skycraft.portal.PortalStore.ITEM_IDS.contains(m.group(1)),
				"unknown item skycraft:" + m.group(1));
		}
		assertTrue(found >= 8, "expected the gear material rules in the defaults");
	}

	@Test
	void reportsBadLines() {
		List<String> problems = new ArrayList<>();
		ItemRules rules = ItemRules.parse("""
			tag food = minecraft:bread 1
			tag spicy = minecraft:bread 1
			tag food name = minecraft:bread 1
			material mithril = minecraft:iron_ingot 1
			from moon = minecraft:iron_ingot 1
			tag gem = minecraft:diamond 99
			tag gold = keep
			""", problems);
		assertEquals(2, rules.size());
		assertEquals(5, problems.size(), problems.toString());
	}

	@Test
	void nameValuesWithPunctuationMatchLikeItemNames() {
		List<String> problems = new ArrayList<>();
		ItemRules rules = ItemRules.parse("""
			tag food name half-eaten = minecraft:apple 1
			name dwemer's = minecraft:iron_ingot 1
			name - = minecraft:dirt 1
			""", problems);
		assertEquals(1, problems.size(), problems.toString());
		assertEquals(2, rules.size());
		assertEquals("minecraft:apple 1", convert(rules, tags("food"), 0, WORLD, "Half-Eaten Apple"));
		assertEquals("keep", convert(rules, tags("food"), 0, WORLD, "Eaten Apple"));
		assertEquals("minecraft:iron_ingot 1", convert(rules, 0, 0, WORLD, "Dwemer's Gear"));
	}

	@Test
	void potionResultsNameTheirPotionInBrackets() {
		List<String> problems = new ArrayList<>();
		ItemRules rules = ItemRules.parse("""
			tag potion name healing = minecraft:potion[healing] 1
			tag poison name weak = minecraft:splash_potion[poison]
			tag poison name deadly = minecraft:lingering_potion[ Minecraft:Strong_Harming ] 2
			tag arrow name poisoned = minecraft:tipped_arrow[long_poison] 8
			tag potion name luck = potion[luck] 1
			tag food = minecraft:bread 1
			""", problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(6, rules.size());
		ItemRules.Result healing = rules.match(new ItemRules.Item(tags("potion"), 0, BOUGHT, "Potion of Minor Healing"));
		assertEquals("minecraft:potion", healing.item());
		assertEquals("healing", healing.potion());
		assertEquals(1, healing.perItem());
		// A vanilla potion keeps Minecraft's own name.
		assertNull(healing.name());
		assertEquals("minecraft:splash_potion[poison] 1", convert(rules, tags("poison"), 0, CORPSE, "Weak Poison"));
		assertEquals("minecraft:lingering_potion[strong_harming] 2", convert(rules, tags("poison"), 0, CORPSE, "Deadly Poison"));
		assertEquals("minecraft:tipped_arrow[long_poison] 8", convert(rules, tags("arrow"), 0, WORLD, "Poisoned Arrow"));
		assertEquals("potion[luck] 1", convert(rules, tags("potion"), 0, WORLD, "Draught of Luck"));
		// The old syntax still gives plain items.
		ItemRules.Result bread = rules.match(new ItemRules.Item(tags("food"), 0, WORLD, "Bread"));
		assertEquals("minecraft:bread", bread.item());
		assertNull(bread.potion());
		assertEquals("minecraft:bread", bread.label());
		assertTrue(bread.effects().isEmpty());
		assertTrue(bread.also().isEmpty());
	}

	@Test
	void reportsBadPotionsAndEachUnknownPotionOnce() {
		List<String> problems = new ArrayList<>();
		ItemRules rules = ItemRules.parse("""
			tag potion name speed = minecraft:potion[speed] 1
			tag potion name haste = minecraft:potion[speed] 1
			tag potion name quick = minecraft:splash_potion[speed] 1
			tag potion name night = minecraft:potion[nightvision] 1
			tag potion name bread = minecraft:bread[healing] 1
			tag potion name open = minecraft:potion[healing 1
			tag potion name empty = minecraft:potion[] 1
			tag potion name count = minecraft:potion[healing] lots
			tag potion name many = minecraft:potion[healing] 65
			tag potion name keep = keep
			tag potion name fine = minecraft:potion[swiftness] 1
			""", problems);
		assertEquals(2, rules.size());
		// One problem per bad line, but 'speed' (three lines) is reported once, naming every line.
		assertEquals(7, problems.size(), problems.toString());
		assertEquals(1L, problems.stream().filter(p -> p.contains("'speed'")).count(), problems.toString());
		assertTrue(problems.contains("line 1: unknown potion 'speed', rule skipped (also line 2, 3)"), problems.toString());
		assertTrue(problems.contains("line 4: unknown potion 'nightvision', rule skipped"), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("line 5: only potion")), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("line 6: no ']'")), problems.toString());
		// Skipped rules don't match: the item falls through to later rules, or stays in Skyrim.
		assertEquals("keep", convert(rules, tags("potion"), 0, WORLD, "Potion of Speed"));
		assertEquals("minecraft:potion[swiftness] 1", convert(rules, tags("potion"), 0, WORLD, "Fine Potion"));
	}

	@Test
	void potionIdsAreThe26Point3OnesAndAllDocumented() {
		assertEquals(46, ItemRules.POTIONS.size());
		assertEquals(46, new java.util.HashSet<>(ItemRules.POTIONS).size());
		for (String id : ItemRules.POTIONS) {
			assertTrue(java.util.regex.Pattern.compile("[ :]" + id + "(\\s|$)", java.util.regex.Pattern.MULTILINE).matcher(ItemRules.DEFAULT_TEXT).find(),
				"potion " + id + " missing from the list in the defaults");
		}
	}

	@Test
	void effectIdsAreThe26Point3OnesAndAllDocumented() {
		// net.minecraft.world.effect.MobEffects in the 26.3 client jar registers these 40.
		assertEquals(40, ItemRules.EFFECTS.size());
		assertEquals(40, new java.util.HashSet<>(ItemRules.EFFECTS).size());
		String list = ItemRules.DEFAULT_TEXT.substring(ItemRules.DEFAULT_TEXT.indexOf("#   effects:"), ItemRules.DEFAULT_TEXT.indexOf("# A bracket names"));
		for (String id : ItemRules.EFFECTS) {
			assertTrue(java.util.regex.Pattern.compile("[ ]" + id + "(\\s|$)", java.util.regex.Pattern.MULTILINE).matcher(list).find(),
				"effect " + id + " missing from the list in the defaults");
		}
	}

	@Test
	void potionsWithEffectsOfTheirOwnParse() {
		List<String> problems = new ArrayList<>();
		ItemRules rules = ItemRules.parse("""
			tag potion name haste = minecraft:potion{haste:1:90+night_vision:0:180} 1
			tag potion name speedy = minecraft:potion{ minecraft:speed:0-2:60-180 + jump_boost:1:30 #3A9A3A "Potion of Speed" } 2
			tag poison name sticky = minecraft:splash_potion[slowness]{weakness:0:20} 1
			tag arrow name glowing = minecraft:tipped_arrow{glowing:0:10} 8
			tag jewelry name diamond = minecraft:gold_nugget 3 + minecraft:diamond 1
			tag potion name both = minecraft:potion{luck:0:60 "A + B {c}"} 1 + minecraft:glass_bottle 1
			""", problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(6, rules.size());
		ItemRules.Result haste = rules.match(new ItemRules.Item(tags("potion"), 0, WORLD, "Potion of Haste"));
		assertEquals("minecraft:potion{haste:1:90+night_vision:0:180}", haste.label());
		assertEquals(2, haste.effects().size());
		assertEquals("haste", haste.effects().get(0).id());
		assertEquals(1, haste.effects().get(0).amplifier());
		assertEquals(90, haste.effects().get(0).seconds());
		// No name in the rule: the potion takes the Skyrim name; no color: Minecraft mixes one.
		assertEquals("Potion of Haste", haste.name());
		assertEquals(-1, haste.color());
		// Ranges follow the tier of the Skyrim name: 0 to 2 levels, 60 to 180 seconds from tier 0 to tier 5.
		ItemRules.Result speedy = rules.match(new ItemRules.Item(tags("potion"), 0, WORLD, "Speedy Potion"));
		assertEquals("minecraft:potion{speed:0:84+jump_boost:1:30}", speedy.label());
		assertEquals("Potion of Speed", speedy.name());
		assertEquals(0x3A9A3A, speedy.color());
		assertEquals(2, speedy.perItem());
		assertEquals("minecraft:potion{speed:0:60+jump_boost:1:30} 2", convert(rules, tags("potion"), 0, WORLD, "Weak Speedy Potion"));
		assertEquals("minecraft:potion{speed:1:108+jump_boost:1:30} 2", convert(rules, tags("potion"), 0, WORLD, "Draught of Speedy"));
		assertEquals("minecraft:potion{speed:2:156+jump_boost:1:30} 2", convert(rules, tags("potion"), 0, WORLD, "Elixir of Speedy"));
		assertEquals("minecraft:potion{speed:2:180+jump_boost:1:30} 2", convert(rules, tags("potion"), 0, WORLD, "Prime Elixir of Speedy"));
		// A vanilla potion and effects of its own together.
		ItemRules.Result sticky = rules.match(new ItemRules.Item(tags("poison"), 0, WORLD, "Sticky Poison"));
		assertEquals("minecraft:splash_potion[slowness]{weakness:0:20}", sticky.label());
		assertEquals("slowness", sticky.potion());
		assertEquals("minecraft:tipped_arrow{glowing:0:10} 8", convert(rules, tags("arrow"), 0, WORLD, "Glowing Arrow"));
		// '+' between items gives several; a plain item takes no name.
		ItemRules.Result ring = rules.match(new ItemRules.Item(tags("jewelry"), 0, WORLD, "Gold Diamond Ring"));
		assertEquals("minecraft:gold_nugget 3 + minecraft:diamond 1", ring.describe());
		assertEquals(1, ring.also().size());
		assertNull(ring.name());
		// A quoted name may hold '+' and braces.
		ItemRules.Result both = rules.match(new ItemRules.Item(tags("potion"), 0, WORLD, "Both Ways"));
		assertEquals("A + B {c}", both.name());
		assertEquals("minecraft:potion{luck:0:60} 1 + minecraft:glass_bottle 1", both.describe());
	}

	@Test
	void reportsBadEffectsAndEachUnknownEffectOnce() {
		List<String> problems = new ArrayList<>();
		ItemRules rules = ItemRules.parse("""
			tag potion name a = minecraft:potion{speedy:0:60} 1
			tag potion name b = minecraft:potion{speedy:1:30 + haste:0:30} 1
			tag potion name c = minecraft:potion{speed:0} 1
			tag potion name d = minecraft:potion{speed:x:60} 1
			tag potion name e = minecraft:potion{speed:0:99999} 1
			tag potion name f = minecraft:potion{speed:256:60} 1
			tag potion name g = minecraft:potion{} 1
			tag potion name h = minecraft:potion{speed:0:60 #12345} 1
			tag potion name i = minecraft:potion{speed:0:60 "Name} 1
			tag potion name j = minecraft:bread{speed:0:60} 1
			tag potion name k = minecraft:potion{speed:0:60 1
			tag potion name l = minecraft:gold_nugget 2 + keep
			tag potion name m = minecraft:potion{speed:0-1:60-120 + nausea:0:5} 1
			""", problems);
		assertEquals(1, rules.size());
		// One problem per bad line, with 'speedy' (two lines) reported once.
		assertEquals(11, problems.size(), problems.toString());
		assertTrue(problems.contains("line 1: unknown effect 'speedy', rule skipped (also line 2)"), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("line 3: effect 'speed:0' needs")), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("line 4: amplifier 'x'")), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("line 5: seconds '99999'")), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("line 6: amplifier '256'")), problems.toString());
		assertTrue(problems.contains("line 7: '{}' names no effect"), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("line 8: color '#12345'")), problems.toString());
		assertTrue(problems.contains("line 9: no '}' after '{'"), problems.toString());
		assertTrue(problems.stream().anyMatch(p -> p.startsWith("line 10: only potion")), problems.toString());
		assertTrue(problems.contains("line 11: no '}' after '{'"), problems.toString());
		assertTrue(problems.contains("line 12: needs an item and a count from 1 to 64"), problems.toString());
	}

	@Test
	void tiersComeFromTheStrongestWordTheRuleDoesNotName() {
		assertEquals(0, ItemRules.tier(ItemRules.words("Potion of Minor Healing"), List.of("healing")));
		assertEquals(1, ItemRules.tier(ItemRules.words("Potion of Healing"), List.of("healing")));
		assertEquals(4, ItemRules.tier(ItemRules.words("Potion of Extreme Healing"), List.of("healing")));
		assertEquals(5, ItemRules.tier(ItemRules.words("Grand Elixir of Escape"), List.of("escape")));
		assertEquals(3, ItemRules.tier(ItemRules.words("Malign Lingering Poison"), List.of("lingering")));
		// "Lasting" is a tier word, but not in "Potion of Lasting Potency", whose rule names it.
		assertEquals(1, ItemRules.tier(ItemRules.words("Potion of Lasting Potency"), List.of("lasting", "potency")));
		assertEquals(3, ItemRules.tier(ItemRules.words("Solution of Lasting Potency"), List.of("lasting", "potency")));
		assertEquals(2, ItemRules.tier(ItemRules.words("Lasting Magicka Poison"), List.of("magicka")));
		assertEquals(0, ItemRules.scale(0, 2, 1));
		assertEquals(1, ItemRules.scale(0, 2, 2));
		assertEquals(2, ItemRules.scale(0, 2, 5));
		assertEquals(84, ItemRules.scale(60, 180, 1));
		assertEquals(13, ItemRules.scale(3, 15, 4));
	}

	@Test
	void longSkyrimNamesComeBackWhole() {
		for (String name : ItemRules.LONG_NAMES) {
			assertTrue(name.length() > ItemRules.NAME_BYTES, name + " fits in an ItemEvent");
		}
		assertEquals("Potion of Fortify Two-hande", ItemRules.cut("Potion of Fortify Two-handed"));
		assertEquals("Potion of Fortify Two-handed", ItemRules.fullName("Potion of Fortify Two-hande"));
		assertEquals("Potion of Prolonged Invisibility", ItemRules.fullName("Potion of Prolonged Invisib"));
		assertEquals("Large Decorative Dwemer Strut", ItemRules.fullName("Large Decorative Dwemer Str"));
		// The three crafted lingering poisons share their first 27 bytes, so they stay cut.
		assertEquals("Poison of Lingering Damage ", ItemRules.fullName(ItemRules.cut("Poison of Lingering Damage Magicka")));
		assertEquals("Potion of Healing", ItemRules.fullName("Potion of Healing"));
		assertNull(ItemRules.fullName(null));
	}

	/** What the Skyrim side sends: the display name cut to 27 bytes (ItemEvent.name is char[28]). */
	private static String sent(String name) {
		return ItemRules.cut(name);
	}

	private static String potion(ItemRules rules, String name) {
		return convert(rules, tags("potion"), 0, CONTAINER, sent(name));
	}

	private static String poison(ItemRules rules, String name) {
		return convert(rules, tags("poison"), 0, CORPSE, sent(name));
	}

	private static String item(ItemRules rules, String tagList, String material, String name) {
		return convert(rules, tagList.isEmpty() ? 0 : tags(tagList.split(",")), material(material), WORLD, sent(name));
	}

	@Test
	void realSkyrimItemsThatStayedInSkyrimNowConvert() {
		ItemRules rules = defaults();
		// Names and tags as Skyrim.esm, the DLCs, the Creation Club plugins and USSEP give them (VendorItem* keywords).
		assertEquals("minecraft:torch 4", item(rules, "", "none", "Torch"));
		assertEquals("minecraft:charcoal 1", item(rules, "clutter", "none", "Charcoal"));
		assertEquals("minecraft:bucket 1", item(rules, "clutter", "none", "Bucket"));
		assertEquals("minecraft:bowl 1", item(rules, "clutter", "none", "Wooden Bowl"));
		assertEquals("minecraft:glass_bottle 1", item(rules, "clutter", "none", "Tankard"));
		assertEquals("minecraft:glass_bottle 1", item(rules, "clutter", "none", "Goblet"));
		assertEquals("minecraft:glass_bottle 1", item(rules, "clutter", "none", "Empty Wine Bottle"));
		assertEquals("minecraft:glass_bottle 1", item(rules, "clutter", "none", "Torchbug in a Jar"));
		assertEquals("minecraft:honey_bottle 1", item(rules, "clutter", "none", "Honey Jar"));
		assertEquals("minecraft:candle 1", item(rules, "clutter", "none", "Candlestick"));
		assertEquals("minecraft:candle 2", item(rules, "clutter", "none", "Glazed Candles"));
		assertEquals("minecraft:lantern 1", item(rules, "clutter", "none", "Lantern"));
		assertEquals("minecraft:string 2", item(rules, "", "none", "Linen Wrap"));
		assertEquals("minecraft:iron_nugget 1", item(rules, "clutter", "none", "Knife"));
		assertEquals("minecraft:iron_shovel 1", item(rules, "", "none", "Shovel"));
		assertEquals("minecraft:iron_hoe 1", item(rules, "", "none", "Pitchfork"));
		assertEquals("minecraft:iron_pickaxe 1", item(rules, "weapon", "iron", "Pickaxe"));
		assertEquals("minecraft:iron_pickaxe 1", convert(rules, tags("weapon"), material("iron"), CORPSE, "Notched Pickaxe"));
		assertEquals("minecraft:iron_axe 1", convert(rules, tags("weapon"), material("iron"), BOUGHT, "Woodcutter's Axe"));
		assertEquals("minecraft:fishing_rod 1", item(rules, "weapon", "none", "Fishing Rod"));
		assertEquals("minecraft:bone 2", item(rules, "ingredient", "none", "Large Antlers"));
		assertEquals("minecraft:bone 2", item(rules, "", "none", "Goat Horns"));
		assertEquals("minecraft:bone 2", item(rules, "ingredient", "none", "Bear Claws"));
		assertEquals("minecraft:bone 1", item(rules, "ingredient", "none", "Ice Wraith Teeth"));
		assertEquals("minecraft:turtle_scute 1", item(rules, "ingredient", "none", "Mudcrab Chitin"));
		assertEquals("minecraft:slime_ball 1", item(rules, "ingredient", "none", "Troll Fat"));
		assertEquals("minecraft:red_mushroom 1", item(rules, "ingredient", "none", "Bleeding Crown"));
		assertEquals("minecraft:wither_rose 1", item(rules, "ingredient", "none", "Deathbell"));
		assertEquals("minecraft:wither_rose 1", item(rules, "ingredient", "none", "Nightshade"));
		assertEquals("minecraft:gunpowder 1", item(rules, "ingredient", "none", "Vampire Dust"));
		assertEquals("minecraft:blaze_powder 1", item(rules, "ingredient", "none", "Fire Salts"));
		assertEquals("minecraft:packed_ice 1", item(rules, "ingredient", "none", "Frost Salts"));
		assertEquals("minecraft:ender_pearl 1", item(rules, "ingredient", "none", "Void Salts"));
		assertEquals("minecraft:tropical_fish 1", item(rules, "ingredient", "none", "River Betty"));
		assertEquals("minecraft:redstone 4", item(rules, "gem", "none", "Ruby"));
		assertEquals("minecraft:redstone 4", item(rules, "gem", "none", "Flawless Garnet"));
		assertEquals("minecraft:lapis_lazuli 4", item(rules, "gem", "none", "Sapphire"));
		assertEquals("minecraft:lapis_lazuli 8", item(rules, "gem", "none", "Flawless Sapphire"));
		assertEquals("minecraft:diamond 2", item(rules, "gem", "none", "Flawless Diamond"));
		assertEquals("minecraft:amethyst_shard 1", item(rules, "gem", "none", "Soul Gem Fragment"));
		assertEquals("skycraft:dwarven_metal_ingot 1", item(rules, "clutter", "none", "Dwemer Bowl"));
		assertEquals("skycraft:dwarven_metal_ingot 2", item(rules, "oreingot", "none", "Dwemer Gyro"));
		assertEquals("skycraft:dwarven_metal_ingot 3", item(rules, "oreingot", "none", "Large Decorative Dwemer Strut"));
		assertEquals("minecraft:clay_ball 4", item(rules, "clutter", "none", "Clay"));
		assertEquals("minecraft:cobblestone 4", item(rules, "oreingot", "none", "Quarried Stone"));
		assertEquals("minecraft:oak_planks 4", item(rules, "", "none", "Sawn Log"));
		assertEquals("minecraft:iron_nugget 1", item(rules, "", "none", "Nails"));
		assertEquals("minecraft:iron_nugget 4", item(rules, "", "none", "Iron Fittings"));
		assertEquals("minecraft:iron_nugget 3", item(rules, "", "none", "Lock"));
		assertEquals("minecraft:glass 2", item(rules, "clutter", "none", "Glass"));
		assertEquals("minecraft:wheat 1", item(rules, "clutter", "none", "Straw"));
		assertEquals("minecraft:wheat 1", item(rules, "foodraw", "none", "Wheat"));
		assertEquals("minecraft:sweet_berries 2", item(rules, "foodraw", "none", "Juniper Berries"));
		assertEquals("minecraft:salmon 1", item(rules, "foodraw", "none", "Arctic Char"));
		assertEquals("minecraft:pufferfish 1", item(rules, "foodraw", "none", "Scorpion Fish"));
		assertEquals("minecraft:egg 1", item(rules, "food,ingredient", "none", "Chicken's Egg"));
		assertEquals("minecraft:sweet_berries 2", item(rules, "food", "none", "Snowberries"));
	}

	@Test
	void keepsakesQuestPiecesAndGearStay() {
		ItemRules rules = defaults();
		assertEquals("keep", item(rules, "", "none", "Lockpick"));
		assertEquals("keep", item(rules, "gold,clutter", "none", "Gold"));
		assertEquals("keep", item(rules, "book", "none", "Spell Tome: Flames"));
		assertEquals("keep", item(rules, "book", "none", "Scroll of Call to Arms"));
		assertEquals("keep", item(rules, "soulgem", "none", "Petty Soul Gem"));
		assertEquals("keep", item(rules, "clutter", "none", "Ysgramor's Soup Spoon"));
		assertEquals("keep", item(rules, "", "none", "Jeweled Goblet"));
		assertEquals("keep", item(rules, "", "none", "Wylandriah's Spoon"));
		assertEquals("keep", item(rules, "", "none", "Mammoth Tusk Powder"));
		assertEquals("keep", item(rules, "oreingot", "none", "Sheogorath-Shaped Amber"));
		assertEquals("keep", item(rules, "gem", "none", "Exquisite Sapphire"));
		assertEquals("keep", item(rules, "foodraw", "none", "Jug of Milk"));
		assertEquals("keep", item(rules, "foodraw,ingredient", "none", "Salmon Roe"));
		assertEquals("keep", item(rules, "clutter", "none", "Charred Skeever Hide"));
		// Household rules never see gear: a hammer that is a weapon stays a weapon, and so does a knife.
		assertEquals("keep", item(rules, "weapon", "steel", "Dawnguard Rune Hammer"));
		assertEquals("keep", item(rules, "weapon,clutter", "none", "Knife"));
		assertEquals("keep", item(rules, "armor", "steel", "Steel Shin Boots"));
		// Clothing and jewelry wait for the user's call.
		assertEquals("keep", item(rules, "clothing", "none", "Fine Boots"));
		assertEquals("keep", item(rules, "jewelry", "none", "Gold Diamond Ring"));
		assertEquals("keep", item(rules, "", "none", "Girl's Yellow Dress"));
		// Lockpicking enchantments are not lockpicks.
		assertEquals("minecraft:leather 1", item(rules, "armor", "hide", "Leather Bracers of Lockpicking"));
	}

	@Test
	void pendingClothingAndJewelryRulesWorkOnceUncommented() {
		// What the user gets by removing the '#' in front of the two pending sections.
		StringBuilder text = new StringBuilder();
		int uncommented = 0;
		for (String line : ItemRules.DEFAULT_TEXT.split("\\R")) {
			if (line.startsWith("#tag clothing") || line.startsWith("#tag jewelry") || line.startsWith("#name girl's") || line.startsWith("#name boy's")) {
				line = line.substring(1);
				uncommented++;
			}
			text.append(line).append('\n');
		}
		assertEquals(26, uncommented);
		List<String> problems = new ArrayList<>();
		ItemRules rules = ItemRules.parse(text.toString(), problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals("minecraft:leather_boots 1", item(rules, "clothing", "none", "Fine Boots"));
		assertEquals("minecraft:leather_boots 1", item(rules, "clothing", "none", "Barkeeper's Shoes"));
		assertEquals("minecraft:leather_helmet 1", item(rules, "clothing", "none", "Adept Hood"));
		assertEquals("minecraft:leather_helmet 1", item(rules, "clothing", "none", "Chef's Hat"));
		assertEquals("minecraft:leather_leggings 1", item(rules, "clothing", "none", "Cicero's Gloves"));
		assertEquals("minecraft:leather_chestplate 1", item(rules, "clothing", "none", "Hooded Monk Robes"));
		assertEquals("minecraft:leather_chestplate 1", item(rules, "clothing", "none", "Fine Clothes"));
		assertEquals("minecraft:leather_chestplate 1", item(rules, "clothing", "none", "Belted Tunic"));
		assertEquals("minecraft:leather_chestplate 1", item(rules, "", "none", "Girl's Yellow Dress"));
		assertEquals("minecraft:gold_nugget 3 + minecraft:diamond 1",
			rules.match(new ItemRules.Item(tags("jewelry"), 0, WORLD, "Gold Diamond Ring")).describe());
		assertEquals("minecraft:gold_nugget 3 + minecraft:lapis_lazuli 2",
			rules.match(new ItemRules.Item(tags("jewelry"), 0, WORLD, "Silver Sapphire Necklace")).describe());
		assertEquals("minecraft:gold_nugget 4", item(rules, "jewelry", "none", "Gold Necklace"));
		assertEquals("minecraft:iron_nugget 4", item(rules, "jewelry", "none", "Silver Ring"));
		assertEquals("minecraft:copper_nugget 4", item(rules, "jewelry", "none", "Copper and Onyx Circlet"));
		assertEquals("minecraft:iron_nugget 3", item(rules, "jewelry", "none", "Saarthal Amulet"));
	}

	private static void assertPotion(ItemRules rules, String expected, String skyrimName) {
		assertEquals(expected, potion(rules, skyrimName), skyrimName);
	}

	private static void assertPoison(ItemRules rules, String expected, String skyrimName) {
		assertEquals(expected, poison(rules, skyrimName), skyrimName);
	}

	@Test
	void skyrimPotionsBecomeMinecraftPotionsByEffectAndTier() {
		ItemRules rules = defaults();
		// Names from Skyrim.esm, the DLCs and USSEP; crafted potions are "Potion of " + the effect.
		assertPotion(rules, "minecraft:potion{instant_health:0:0} 1", "Potion of Minor Healing");
		assertPotion(rules, "minecraft:potion{instant_health:1:0} 1", "Potion of Plentiful Healing");
		assertPotion(rules, "minecraft:potion{instant_health:2:0} 1", "Potion of Ultimate Healing");
		assertPotion(rules, "minecraft:potion{instant_health:0:0} 1", "Potion of Restore Health");
		assertPotion(rules, "minecraft:potion{instant_health:1:0+absorption:1:66+saturation:6:0} 1", "Potion of Vigorous Well-being");
		assertPotion(rules, "minecraft:potion{health_boost:1:84} 1", "Potion of Health");
		assertPotion(rules, "minecraft:potion{health_boost:2:156} 1", "Elixir of Health");
		assertPotion(rules, "minecraft:potion{health_boost:1:84} 1", "Potion of Fortify Health");
		assertPotion(rules, "minecraft:potion{regeneration:0:99} 1", "Draught of Regeneration");
		assertPotion(rules, "minecraft:potion{regeneration:0:72} 1", "Potion of Regenerate Health");
		assertPotion(rules, "minecraft:potion{absorption:0:20} 1", "Potion of Minor Magicka");
		assertPotion(rules, "minecraft:potion{absorption:3:60} 1", "Potion of Ultimate Magicka");
		assertPotion(rules, "minecraft:potion{absorption:0:84} 1", "Potion of Extra Magicka");
		assertPotion(rules, "minecraft:potion{absorption:0:180} 1", "Potion of Lasting Potency");
		assertPotion(rules, "minecraft:potion{absorption:1:240} 1", "Solution of Lasting Potency");
		assertPotion(rules, "minecraft:potion{absorption:0:180} 1", "Potion of Regenerate Magicka");
		assertPotion(rules, "minecraft:potion{saturation:9:0} 1", "Potion of Plentiful Stamina");
		assertPotion(rules, "minecraft:potion{speed:1:132+jump_boost:1:132} 1", "Solution of Enhanced Stamina");
		assertPotion(rules, "minecraft:potion{speed:0:156+saturation:1:0} 1", "Potion of Vigor");
		assertPotion(rules, "minecraft:potion{speed:0:156+saturation:1:0} 1", "Potion of Regenerate Stamina");
		assertPotion(rules, "minecraft:potion{strength:2:156} 1", "Elixir of the Warrior");
		assertPotion(rules, "minecraft:potion{strength:0:84} 1", "Potion of Fortify Two-handed");
		assertPotion(rules, "minecraft:potion{strength:2:180+resistance:1:180+speed:0:180} 1", "Grand Elixir of Conflict");
		assertPotion(rules, "minecraft:potion{night_vision:0:174} 1", "Draught of True Shot");
		assertPotion(rules, "minecraft:potion{absorption:1:132} 1", "Philter of the Defender");
		assertPotion(rules, "minecraft:potion{resistance:2:156+slowness:0:156} 1", "Elixir of the Knight");
		assertPotion(rules, "minecraft:potion{resistance:0:84+speed:0:84} 1", "Potion of Fortify Light Armor");
		assertPotion(rules, "minecraft:potion{invisibility:0:48} 1", "Draught of Light Feet");
		assertPotion(rules, "minecraft:potion{invisibility:0:30} 1", "Potion of Brief Invisibility");
		assertPotion(rules, "minecraft:potion{invisibility:0:120} 1", "Potion of Prolonged Invisibility");
		assertPotion(rules, "minecraft:potion{invisibility:0:180+instant_health:2:0} 1", "Prime Elixir of Escape");
		assertPotion(rules, "minecraft:potion{luck:2:180} 1", "Prime Elixir of Larceny");
		assertPotion(rules, "minecraft:potion{luck:0:84} 1", "Potion of Fortify Lockpicking");
		assertPotion(rules, "minecraft:potion{hero_of_the_village:2:252} 1", "Elixir of Haggling");
		assertPotion(rules, "minecraft:potion{hero_of_the_village:0:108} 1", "Potion of Fortify Persuasion");
		assertPotion(rules, "minecraft:potion{haste:2:156} 1", "Blacksmith's Elixir");
		assertPotion(rules, "minecraft:potion{haste:0:84} 1", "Potion of Fortify Alchemy");
		assertPotion(rules, "minecraft:potion{haste:1:132} 1", "Enchanter's Philter");
		assertPotion(rules, "minecraft:potion{resistance:0:108} 1", "Draught of Alteration");
		assertPotion(rules, "minecraft:potion{strength:1:156} 1", "Conjurer's Elixir");
		assertPotion(rules, "minecraft:potion{strength:1:132+fire_resistance:0:132} 1", "Philter of Destruction");
		assertPotion(rules, "minecraft:potion{invisibility:0:28+night_vision:0:84} 1", "Potion of Illusion");
		assertPotion(rules, "minecraft:potion{regeneration:0:108} 1", "Draught of the Healer");
		assertPotion(rules, "minecraft:potion{regeneration:0:84} 1", "Potion of Fortify Restoration");
		assertPotion(rules, "minecraft:potion{strength:0:156} 1", "Potion of Fortify Carry Weight");
		assertPotion(rules, "minecraft:potion{strength:1:264} 1", "Elixir of Strength");
		assertPotion(rules, "minecraft:potion{fire_resistance:0:204} 1", "Philter of Resist Fire");
		assertPotion(rules, "minecraft:potion{resistance:0:156} 1", "Elixir of Resist Cold");
		assertPotion(rules, "minecraft:potion{resistance:0:84} 1", "Potion of Resist Frost");
		assertPotion(rules, "minecraft:potion{resistance:0:108+fire_resistance:0:108} 1", "Draught of Resist Shock");
		assertPotion(rules, "minecraft:potion{resistance:0:84} 1", "Soul Husk Extract");
		assertPotion(rules, "minecraft:potion{regeneration:0:28} 1", "Potion of Resist Poison");
		assertPotion(rules, "minecraft:potion{water_breathing:0:198+conduit_power:0:198} 1", "Elixir of Waterbreathing");
		assertPotion(rules, "minecraft:potion{dolphins_grace:0:84} 1", "Potion of Waterwalking");
		assertPotion(rules, "minecraft:milk_bucket 1", "Potion of Cure Disease");
		assertPotion(rules, "minecraft:potion{night_vision:0:192} 1", "Potion of Night Eye");
		// Philter of the Phantom has no vendor keyword without USSEP.
		assertEquals("minecraft:potion{invisibility:0:60} 1", convert(rules, 0, 0, WORLD, "Philter of the Phantom"));
		// Bought in a Skyrim shop converts the same way.
		assertEquals("minecraft:potion{instant_health:0:0} 1", convert(rules, tags("potion"), 0, BOUGHT, "Potion of Minor Healing"));
	}

	@Test
	void potionsTakeTheirWholeSkyrimNameAndColor() {
		ItemRules rules = defaults();
		ItemRules.Result healing = rules.match(new ItemRules.Item(tags("potion"), 0, CONTAINER, "Potion of Minor Healing"));
		assertEquals("Potion of Minor Healing", healing.name());
		assertEquals(0xC62828, healing.color());
		ItemRules.Result twoHanded = rules.match(new ItemRules.Item(tags("potion"), 0, CONTAINER, sent("Potion of Fortify Two-handed")));
		assertEquals("Potion of Fortify Two-handed", twoHanded.name());
		assertEquals(-1, twoHanded.color());
		ItemRules.Result magicka = rules.match(new ItemRules.Item(tags("potion"), 0, CONTAINER, sent("Potion of Regenerate Magicka")));
		assertEquals("Potion of Regenerate Magicka", magicka.name());
		assertEquals(0x2F5FD0, magicka.color());
		ItemRules.Result stamina = rules.match(new ItemRules.Item(tags("potion"), 0, CONTAINER, "Potion of Stamina"));
		assertEquals(0x3A9A3A, stamina.color());
		// Shared cuts stay cut, without the trailing space.
		ItemRules.Result lingering = rules.match(new ItemRules.Item(tags("poison"), 0, CORPSE, sent("Poison of Lingering Damage Health")));
		assertEquals("Poison of Lingering Damage", lingering.name());
		// Milk is a plain item, so it keeps Minecraft's name.
		assertNull(rules.match(new ItemRules.Item(tags("potion"), 0, CONTAINER, "Potion of Cure Poison")).name());
	}

	@Test
	void skyrimPoisonsBecomeSplashPotionsByEffectAndTier() {
		ItemRules rules = defaults();
		assertPoison(rules, "minecraft:splash_potion{instant_damage:0:0} 1", "Weak Poison");
		assertPoison(rules, "minecraft:splash_potion{instant_damage:2:0} 1", "Deadly Poison");
		assertPoison(rules, "minecraft:splash_potion{instant_damage:0:0} 1", "Poison of Damage Health");
		assertPoison(rules, "minecraft:splash_potion{instant_damage:1:0} 1", "Ice Wraith Bane");
		assertPoison(rules, "minecraft:splash_potion{poison:1:16} 1", "Potent Lingering Poison");
		assertPoison(rules, "minecraft:splash_potion{poison:0:13} 1", "Poison of Lingering Damage Health");
		assertPoison(rules, "minecraft:splash_potion{wither:0:10} 1", "Poison of Ravage Health");
		assertPoison(rules, "minecraft:splash_potion{weakness:1:34} 1", "Deadly Magicka Poison");
		assertPoison(rules, "minecraft:splash_potion{weakness:1:40+unluck:0:40} 1", "Persisting Magicka Poison");
		assertPoison(rules, "minecraft:splash_potion{weakness:0:34+unluck:0:34} 1", "Poison of Damage Magicka Regen");
		assertPoison(rules, "minecraft:splash_potion{weakness:0:76+unluck:1:76} 1", "Deadly Recovery Poison");
		assertPoison(rules, "minecraft:splash_potion{weakness:0:28} 1", "Poison of Ravage Magicka");
		assertPoison(rules, "minecraft:splash_potion{hunger:1:22} 1", "Virulent Stamina Poison");
		assertPoison(rules, "minecraft:splash_potion{hunger:2:40+slowness:0:40} 1", "Unceasing Stamina Poison");
		assertPoison(rules, "minecraft:splash_potion{slowness:0:62+hunger:0:62} 1", "Malign Vigor Poison");
		assertPoison(rules, "minecraft:splash_potion{slowness:0:34+hunger:0:34} 1", "Poison of Damage Stamina Regen");
		assertPoison(rules, "minecraft:splash_potion{mining_fatigue:0:16+hunger:0:16} 1", "Poison of Ravage Stamina");
		assertPoison(rules, "minecraft:splash_potion{slowness:4:10+mining_fatigue:2:10} 1", "Virulent Paralysis Poison");
		assertPoison(rules, "minecraft:splash_potion{slowness:4:3+mining_fatigue:2:3} 1", "Weak Paralysis Poison");
		assertPoison(rules, "minecraft:splash_potion{darkness:0:18+weakness:0:18} 1", "Potent Fear Poison");
		assertPoison(rules, "minecraft:splash_potion{nausea:0:5} 1", "Weak Frenzy Poison");
		assertPoison(rules, "minecraft:splash_potion{slowness:0:21} 1", "Poison of Slow");
		assertPoison(rules, "minecraft:splash_potion{weakness:1:39} 1", "Deadly Aversion to Fire");
		assertPoison(rules, "minecraft:splash_potion{slowness:1:33} 1", "Malign Aversion to Frost");
		assertPoison(rules, "minecraft:splash_potion{glowing:0:21+weakness:0:21} 1", "Poison of Weakness to Shock");
		assertPoison(rules, "minecraft:splash_potion{unluck:0:27+weakness:0:27} 1", "Potent Aversion to Magic");
		assertPoison(rules, "minecraft:splash_potion{poison:0:7+weakness:0:21} 1", "Poison of Weakness to Poison");
		assertPoison(rules, "minecraft:splash_potion{poison:0:6+slowness:1:8} 1", "Frostbite Venom");
	}

	@Test
	void questPotionsAndOddDrinksStay() {
		ItemRules rules = defaults();
		assertEquals("keep", potion(rules, "The White Phial (Full)"));
		assertEquals("keep", potion(rules, "Unknown Potion"));
		assertEquals("keep", poison(rules, "Lotus Extract"));
		assertEquals("keep", poison(rules, "Nightshade Extract"));
		assertEquals("keep", convert(rules, 0, 0, WORLD, "Skooma"));
		assertEquals("keep", convert(rules, 0, 0, WORLD, "Potion of Blood"));
	}

	@Test
	void tagNamesForLogs() {
		assertEquals("food,foodraw", ItemRules.tagNames(tags("food", "foodraw")));
	}

	@Test
	void filledBigSoulGemsBecomePortalKeys() {
		ItemRules rules = defaults();
		// Skyrim's filled base forms share the empty gems' names; the filled tag tells them apart.
		assertEquals("skycraft:filled_soul_gem 1", convert(rules, tags("soulgem", "filled"), 0, CONTAINER, "Grand Soul Gem"));
		assertEquals("skycraft:filled_soul_gem 1", convert(rules, tags("soulgem", "filled"), 0, BOUGHT, "Greater Soul Gem"));
		assertEquals("skycraft:filled_soul_gem 1", convert(rules, tags("soulgem", "filled"), 0, CORPSE, "Black Soul Gem"));
		assertEquals("keep", convert(rules, tags("soulgem"), 0, CONTAINER, "Grand Soul Gem"));
		assertEquals("keep", convert(rules, tags("soulgem"), 0, BOUGHT, "Black Soul Gem"));
		assertEquals("keep", convert(rules, tags("soulgem", "filled"), 0, CONTAINER, "Common Soul Gem"));
		assertEquals("keep", convert(rules, tags("soulgem", "filled"), 0, CONTAINER, "Petty Soul Gem"));
		assertEquals("keep", convert(rules, tags("soulgem", "filled"), 0, WORLD, "Azura's Star"));
		assertEquals("keep", convert(rules, tags("soulgem", "filled"), 0, WORLD, "The Black Star"));
		// Soul gem fragments are misc items, not soul gems: never portal keys, and with their real gem tag amethyst.
		assertEquals("keep", convert(rules, tags("clutter"), 0, CONTAINER, "Soul Gem Fragment"));
		assertEquals("minecraft:amethyst_shard 1", convert(rules, tags("gem"), 0, CONTAINER, "Soul Gem Fragment"));
		assertEquals("soulgem,filled", ItemRules.tagNames(tags("soulgem", "filled")));
	}
}
