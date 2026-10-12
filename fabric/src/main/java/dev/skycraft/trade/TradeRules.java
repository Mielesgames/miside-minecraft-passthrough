package dev.skycraft.trade;

import dev.skycraft.link.Proto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * What Skyrim merchants trade as Minecraft items, and how each town prices it.
 *
 * <p>Two plain text files, so both can be edited without a rebuild. {@code skycraft-trades.txt}
 * lists every trade a merchant of a category can offer, one per line:
 * <pre>
 * # category sell|buy count item price-in-coins
 * weapon sell 1 minecraft:iron_sword 40
 * hide buy 4 minecraft:leather 4
 * </pre>
 * {@code sell} means the merchant sells to the player, {@code buy} that the merchant buys from the
 * player. {@code skycraft-towns.txt} sets what each town pays with and what's cheap or dear there:
 * <pre>
 * Whiterun = copper gold ; food 0.7, weapon 0.9
 * * = copper gold
 * </pre>
 * A multiplier applies both ways: a town where food is plentiful sells it cheap and pays little for
 * it. Towns match ignoring case; unknown towns use the {@code *} line. Prices are in coins (see
 * {@link Currency}). Pure Java on purpose, so it's unit tested without Minecraft.
 */
public final class TradeRules {
	/** Trade categories, in Proto.VENDOR_* bit order. */
	public static final List<String> CATEGORIES = List.of("weapon", "armor", "ingot", "potion", "ingredient", "food", "arrow", "jewelry", "book", "hide",
		"tool", "stolen");
	private static final int[] CATEGORY_BITS = { Proto.VENDOR_WEAPON, Proto.VENDOR_ARMOR, Proto.VENDOR_INGOT, Proto.VENDOR_POTION,
		Proto.VENDOR_INGREDIENT, Proto.VENDOR_FOOD, Proto.VENDOR_ARROW, Proto.VENDOR_JEWELRY, Proto.VENDOR_BOOK, Proto.VENDOR_HIDE, Proto.VENDOR_TOOL,
		Proto.VENDOR_STOLEN };

	/** Most offers one merchant shows. */
	public static final int MAX_OFFERS = 12;
	/** Highest price a trades line may set, far above anything two stacks of currency can pay. */
	public static final int MAX_PRICE = 100_000;

	/** One line of the trades file. */
	public record Trade(String category, boolean sell, int count, String item, int price) {
	}

	/** One line of the towns file. {@code currencies} are item ids. */
	public record Town(String name, Set<String> currencies, Map<String, Double> multipliers) {
		public double multiplier(String category) {
			return this.multipliers.getOrDefault(category, 1.0);
		}
	}

	/** One offer of a merchant, priced for its town; {@code price} is in coins. */
	public record Offer(boolean sell, String item, int count, int price, String category) {
	}

	/** Used when the towns file has no {@code *} line. */
	private static final Town FALLBACK = new Town("*", Set.of(Currency.COPPER, Currency.NUGGET, Currency.GOLD), Map.of());

	public static final String DEFAULT_TRADES = """
		# SkyCraft merchant trades: what Skyrim merchants trade as Minecraft items.
		# One trade per line:  category sell|buy count item price
		#   sell = the merchant sells to you, buy = the merchant buys from you
		#   price is in coins: copper ingot 1, gold nugget 2, gold ingot 18, emerald 24, diamond 96
		#   categories: weapon armor ingot potion ingredient food arrow jewelry book hide tool stolen
		# A merchant shows up to 12 of the trades its Skyrim shop deals in; general goods stores
		# deal in everything except stolen goods, fences in stolen goods. What each town pays with
		# and what's cheap there is in skycraft-towns.txt. Restart Skyrim after editing.

		weapon sell 1 minecraft:iron_sword 40
		weapon sell 1 minecraft:bow 30
		weapon sell 1 minecraft:crossbow 45
		weapon sell 1 minecraft:shield 25
		weapon buy 1 minecraft:iron_sword 16
		weapon buy 1 minecraft:bow 12

		armor sell 1 minecraft:iron_helmet 40
		armor sell 1 minecraft:iron_chestplate 64
		armor sell 1 minecraft:iron_leggings 56
		armor sell 1 minecraft:iron_boots 32
		armor sell 1 minecraft:chainmail_chestplate 48
		armor sell 1 minecraft:leather_chestplate 16
		armor buy 1 minecraft:iron_chestplate 26
		armor buy 1 minecraft:leather_chestplate 6

		ingot sell 4 minecraft:iron_ingot 24
		ingot sell 8 minecraft:coal 6
		ingot sell 1 minecraft:diamond 200
		# Obsidian for portal frames (ten make one): dear, and cheapest where ingots are (Markarth, the mining towns)
		ingot sell 2 minecraft:obsidian 72
		ingot buy 4 minecraft:iron_ingot 12
		ingot buy 4 minecraft:raw_iron 10
		ingot buy 2 minecraft:raw_gold 14
		ingot buy 16 minecraft:coal 4

		potion sell 1 minecraft:golden_apple 60
		potion sell 4 minecraft:experience_bottle 30
		potion sell 4 minecraft:glass_bottle 4
		potion buy 4 minecraft:glass_bottle 1
		potion buy 4 minecraft:spider_eye 6

		ingredient sell 8 minecraft:redstone 8
		ingredient sell 8 minecraft:glowstone_dust 12
		ingredient sell 2 minecraft:blaze_powder 24
		ingredient sell 8 minecraft:sugar 4
		ingredient buy 8 minecraft:spider_eye 6
		ingredient buy 16 minecraft:sugar 3

		food sell 6 minecraft:bread 6
		food sell 4 minecraft:cooked_beef 8
		food sell 8 minecraft:apple 5
		food sell 6 minecraft:baked_potato 4
		food sell 1 minecraft:cake 10
		food buy 20 minecraft:wheat 4
		food buy 20 minecraft:potato 3
		food buy 8 minecraft:beef 4
		food buy 8 minecraft:cod 4

		arrow sell 16 minecraft:arrow 8
		arrow sell 8 minecraft:spectral_arrow 12
		arrow buy 32 minecraft:feather 4
		arrow buy 32 minecraft:flint 4

		jewelry sell 1 minecraft:emerald 48
		jewelry sell 4 minecraft:amethyst_shard 12
		jewelry sell 1 minecraft:clock 30
		jewelry buy 2 minecraft:amethyst_shard 3
		jewelry buy 1 minecraft:clock 12

		book sell 1 minecraft:book 6
		book sell 1 minecraft:writable_book 10
		book sell 1 minecraft:map 12
		book sell 1 minecraft:bookshelf 24
		book buy 6 minecraft:paper 2
		book buy 1 minecraft:book 2

		hide sell 4 minecraft:leather 8
		hide sell 4 minecraft:rabbit_hide 4
		hide buy 4 minecraft:leather 4
		hide buy 8 minecraft:bone 2
		hide buy 16 minecraft:rotten_flesh 1

		tool sell 1 minecraft:iron_pickaxe 40
		tool sell 1 minecraft:iron_shovel 14
		tool sell 1 minecraft:shears 12
		tool sell 1 minecraft:fishing_rod 10
		tool sell 16 minecraft:torch 4
		tool sell 1 minecraft:lantern 6
		tool sell 1 minecraft:bucket 20
		tool sell 1 minecraft:obsidian 40
		tool buy 1 minecraft:iron_pickaxe 16
		tool buy 16 minecraft:string 3

		stolen sell 4 minecraft:tnt 40
		stolen sell 1 minecraft:name_tag 30
		stolen sell 1 minecraft:saddle 36
		stolen buy 1 minecraft:name_tag 12
		stolen buy 1 minecraft:saddle 14
		stolen buy 1 minecraft:clock 16
		""";

	public static final String DEFAULT_TOWNS = """
		# SkyCraft towns: what Skyrim towns pay with and what's cheap or dear there.
		# One town per line:  Town Name = currencies ; category multiplier, category multiplier, ...
		#   currencies: copper (copper ingots), gold (gold nuggets and ingots), emerald, diamond
		#   a multiplier below 1 means plentiful: cheap to buy, and merchants pay less for it
		#   categories: weapon armor ingot potion ingredient food arrow jewelry book hide tool stolen
		# Town names match Skyrim's, ignoring case. The * line is for everywhere else.
		# Restart Skyrim after editing.

		Whiterun = copper gold ; food 0.7, hide 0.8, weapon 0.9, armor 0.9
		Riverwood = copper gold ; weapon 0.8, armor 0.8, food 0.8, book 1.3
		Rorikstead = copper ; food 0.6, hide 0.8, tool 1.2, book 1.4
		Solitude = gold emerald diamond ; jewelry 1.4, book 1.2, potion 1.2, weapon 1.2, armor 1.2, food 1.2, stolen 1.4
		Dragon Bridge = copper gold ; food 0.9, hide 0.8, tool 1.1
		Morthal = copper ; ingredient 0.7, potion 0.8, food 1.2, weapon 1.3
		Dawnstar = copper gold ; ingot 0.7, tool 0.8, food 1.3, hide 1.1
		Winterhold = copper gold ; book 0.6, potion 0.9, ingredient 0.9, food 1.4, weapon 1.3
		Windhelm = gold emerald ; weapon 0.9, armor 0.9, ingot 0.9, food 1.1, stolen 1.2
		Kynesgrove = copper ; ingot 0.7, tool 0.9, food 1.2
		Riften = copper gold ; stolen 0.6, food 0.8, potion 0.9, jewelry 1.2
		Ivarstead = copper ; food 0.8, hide 0.8, arrow 0.9, book 1.3
		Shor's Stone = copper ; ingot 0.6, tool 0.9, food 1.3
		Falkreath = copper gold ; hide 0.7, arrow 0.8, food 0.9, ingot 1.2
		Markarth = gold emerald diamond ; ingot 0.7, jewelry 1.5, armor 1.2, weapon 1.2, food 1.3, stolen 1.3
		Karthwasten = copper ; ingot 0.6, tool 0.9, food 1.3
		Raven Rock = copper emerald ; ingot 0.8, hide 0.9, ingredient 1.2, food 1.4
		* = copper gold
		""";

	private final List<Trade> trades;
	private final Map<String, Town> towns;

	private TradeRules(List<Trade> trades, Map<String, Town> towns) {
		this.trades = trades;
		this.towns = towns;
	}

	public List<Trade> trades() {
		return this.trades;
	}

	public Map<String, Town> towns() {
		return this.towns;
	}

	/** Shipped defaults; any problem with them is a bug. */
	public static TradeRules defaults(List<String> problems) {
		return parse(DEFAULT_TRADES, DEFAULT_TOWNS, problems);
	}

	/** Parses both files. Bad lines are skipped and reported to {@code problems}, once each. */
	public static TradeRules parse(String tradesText, String townsText, List<String> problems) {
		return new TradeRules(List.copyOf(parseTrades(tradesText, problems)), Map.copyOf(parseTowns(townsText, problems)));
	}

	private static String normalizeItem(String item) {
		String id = item.toLowerCase(Locale.ROOT);
		return id.indexOf(':') < 0 ? "minecraft:" + id : id;
	}

	private static String townKey(String town) {
		return town == null || town.isBlank() ? "*" : town.strip().toLowerCase(Locale.ROOT);
	}

	private static List<Trade> parseTrades(String text, List<String> problems) {
		List<Trade> out = new ArrayList<>();
		int n = 0;
		for (String raw : text.split("\\R")) {
			n++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String where = "trades line " + n + ": ";
			String[] bits = line.split("\\s+");
			if (bits.length != 5) {
				problems.add(where + "expected 'category sell|buy count item price'");
				continue;
			}
			String category = bits[0].toLowerCase(Locale.ROOT);
			if (!CATEGORIES.contains(category)) {
				problems.add(where + "unknown category '" + bits[0] + "'");
				continue;
			}
			String way = bits[1].toLowerCase(Locale.ROOT);
			if (!way.equals("sell") && !way.equals("buy")) {
				problems.add(where + "expected sell or buy, not '" + bits[1] + "'");
				continue;
			}
			int count, price;
			try {
				count = Integer.parseInt(bits[2]);
				price = Integer.parseInt(bits[4]);
			} catch (NumberFormatException e) {
				problems.add(where + "count and price must be whole numbers");
				continue;
			}
			if (count < 1 || count > 64) {
				problems.add(where + "count must be 1 to 64");
				continue;
			}
			if (price < 1 || price > MAX_PRICE) {
				// Above that, price times multipliers could overflow into a 1-coin price.
				problems.add(where + "price must be 1 to " + MAX_PRICE + " coins");
				continue;
			}
			out.add(new Trade(category, way.equals("sell"), count, normalizeItem(bits[3]), price));
		}
		return out;
	}

	private static Map<String, Town> parseTowns(String text, List<String> problems) {
		Map<String, Town> out = new HashMap<>();
		int n = 0;
		for (String raw : text.split("\\R")) {
			n++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String where = "towns line " + n + ": ";
			int eq = line.indexOf('=');
			if (eq <= 0) {
				problems.add(where + "no '='");
				continue;
			}
			String name = line.substring(0, eq).strip();
			String right = line.substring(eq + 1);
			int semi = right.indexOf(';');
			String currencyText = semi < 0 ? right : right.substring(0, semi);
			String multiplierText = semi < 0 ? "" : right.substring(semi + 1);
			String bad = null;
			Set<String> currencies = new LinkedHashSet<>();
			for (String word : currencyText.strip().split("\\s+")) {
				if (word.isEmpty()) {
					continue;
				}
				List<String> items = Currency.itemsFor(word);
				if (items == null) {
					bad = "unknown currency '" + word + "' (copper, gold, emerald or diamond)";
					break;
				}
				currencies.addAll(items);
			}
			if (bad == null && currencies.isEmpty()) {
				bad = "needs at least one currency";
			}
			Map<String, Double> multipliers = new HashMap<>();
			if (bad == null) {
				for (String part : multiplierText.split(",")) {
					String p = part.strip();
					if (p.isEmpty()) {
						continue;
					}
					String[] bits = p.split("\\s+");
					String category = bits[0].toLowerCase(Locale.ROOT);
					if (bits.length != 2 || !CATEGORIES.contains(category)) {
						bad = "expected 'category multiplier', not '" + p + "'";
						break;
					}
					double m;
					try {
						m = Double.parseDouble(bits[1]);
					} catch (NumberFormatException e) {
						bad = "bad multiplier in '" + p + "'";
						break;
					}
					if (!(m > 0.0 && m <= 10.0)) {
						bad = "multiplier must be above 0 and at most 10 in '" + p + "'";
						break;
					}
					multipliers.put(category, m);
				}
			}
			if (bad == null && name.isEmpty()) {
				bad = "no town name";
			}
			if (bad == null && out.containsKey(townKey(name))) {
				bad = "town '" + name + "' is listed twice";
			}
			if (bad != null) {
				problems.add(where + bad);
				continue;
			}
			out.put(townKey(name), new Town(name, Set.copyOf(currencies), Map.copyOf(multipliers)));
		}
		return out;
	}

	/** The town's line, ignoring case, or the {@code *} line. */
	public Town town(String name) {
		Town t = this.towns.get(townKey(name));
		if (t == null) {
			t = this.towns.get("*");
		}
		return t != null ? t : FALLBACK;
	}

	/** Categories a merchant with this Proto.VENDOR_* mask deals in. */
	public static Set<String> categoriesFor(int vendorMask) {
		Set<String> out = new HashSet<>();
		for (int i = 0; i < CATEGORIES.size(); i++) {
			boolean any = (vendorMask & Proto.VENDOR_ANY) != 0 && CATEGORY_BITS[i] != Proto.VENDOR_STOLEN;
			if (any || (vendorMask & CATEGORY_BITS[i]) != 0) {
				out.add(CATEGORIES.get(i));
			}
		}
		return out;
	}

	/**
	 * Up to {@link #MAX_OFFERS} offers for a merchant: the trades of its categories, priced for the
	 * town and current demand ({@code demand} may be null), picked by {@code seed} (the same seed
	 * always picks the same trades), sells first.
	 */
	public List<Offer> offersFor(int vendorMask, String town, long seed, Demand demand) {
		Set<String> categories = categoriesFor(vendorMask);
		List<Trade> pool = new ArrayList<>();
		for (Trade t : this.trades) {
			if (categories.contains(t.category())) {
				pool.add(t);
			}
		}
		Collections.shuffle(pool, new Random(seed));
		Town where = this.town(town);
		List<Offer> sells = new ArrayList<>();
		List<Offer> buys = new ArrayList<>();
		for (Trade t : pool.subList(0, Math.min(MAX_OFFERS, pool.size()))) {
			int price = Math.max(1, (int) Math.round(t.price() * where.multiplier(t.category())));
			if (demand != null) {
				price = demand.apply(price, town, t.item());
			}
			(t.sell() ? sells : buys).add(new Offer(t.sell(), t.item(), t.count(), price, t.category()));
		}
		List<Offer> out = new ArrayList<>(sells);
		out.addAll(buys);
		return out;
	}

	/**
	 * {@code o}'s price for {@code count} of its items instead of {@code o.count()} (one trade moves
	 * at most a stack): rounded up when the merchant sells, down when it buys, at least 1 coin.
	 */
	public static int priceFor(Offer o, int count) {
		if (count >= o.count()) {
			return o.price();
		}
		long part = (long) o.price() * Math.max(0, count);
		long price = o.sell() ? (part + o.count() - 1) / o.count() : part / o.count();
		return (int) Math.max(1, price);
	}

	/** Currency items an offer may be paid in: the town's, minus the traded item itself. */
	public static Set<String> currenciesFor(Town town, String item) {
		Set<String> out = new HashSet<>(town.currencies());
		out.remove(item);
		return out;
	}
}
