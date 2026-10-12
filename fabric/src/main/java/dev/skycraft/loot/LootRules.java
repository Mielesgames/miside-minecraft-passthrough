package dev.skycraft.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What Minecraft items a dead Skyrim creature drops, by its Skyrim display name.
 *
 * <p>Plain text, one rule per line, so it can be edited without a rebuild:
 * <pre>
 * # Skyrim name words = Minecraft drops (item min-max, ...)
 * deer, elk = minecraft:beef 1-3, minecraft:leather 0-2
 * </pre>
 * A rule matches when one of its words is a whole word of the creature's name, ignoring case, so
 * "bear" matches "Cave Bear" but "wolf" doesn't match "Werewolf"; an entry of several words
 * ("sabre cat") needs all of them. The first matching rule wins.
 * Pure Java on purpose, so it's unit tested without Minecraft.
 */
public final class LootRules {
	public record Drop(String item, int min, int max) {
	}

	public record Rule(List<String> words, List<Drop> drops) {
	}

	/** Shipped defaults, written to the config folder on first run. */
	public static final String DEFAULT_TEXT = """
		# SkyCraft creature drops: what Skyrim creatures drop as Minecraft items when you kill them.
		# One rule per line:  name words = item min-max, item min-max, ...
		# A word matches a whole word of the creature's Skyrim name, ignoring case ("bear" matches
		# "Cave Bear"). The first matching line wins. Restart Skyrim after editing.
		deer, elk = minecraft:beef 1-3, minecraft:leather 0-2
		goat = minecraft:mutton 1-2
		hare, rabbit, fox = minecraft:rabbit 1-1, minecraft:rabbit_hide 0-1
		chicken, hen = minecraft:chicken 1-1, minecraft:feather 0-2
		cow = minecraft:beef 2-4, minecraft:leather 1-2
		mammoth = minecraft:beef 4-8, minecraft:leather 2-4
		bear, sabre = minecraft:beef 2-4, minecraft:leather 1-3
		wolf = minecraft:bone 1-2
		mudcrab, slaughterfish = minecraft:cod 1-2
		skeever = minecraft:rotten_flesh 1-1
		horker = minecraft:salmon 2-3
		""";

	private final List<Rule> rules;

	private LootRules(List<Rule> rules) {
		this.rules = rules;
	}

	public List<Rule> rules() {
		return this.rules;
	}

	/** Parses rule text. Bad lines are skipped and reported to {@code problems}. */
	public static LootRules parse(String text, List<String> problems) {
		List<Rule> out = new ArrayList<>();
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
			List<String> words = new ArrayList<>();
			for (String w : line.substring(0, eq).split(",")) {
				// Split like creature names are: "sabre cat" (or "sabre-cat") needs both words.
				String word = String.join(" ", ItemRules.words(w));
				if (!word.isEmpty()) {
					words.add(word);
				}
			}
			List<Drop> drops = new ArrayList<>();
			int problemsBefore = problems.size();
			for (String d : line.substring(eq + 1).split(",")) {
				String part = d.strip();
				if (part.isEmpty()) {
					continue;
				}
				String[] bits = part.split("\\s+");
				int min = 1, max = 1;
				try {
					if (bits.length >= 2) {
						String[] range = bits[1].split("-");
						min = Integer.parseInt(range[0]);
						max = range.length > 1 ? Integer.parseInt(range[1]) : min;
					}
				} catch (NumberFormatException e) {
					problems.add("line " + n + ": bad count in '" + part + "'");
					continue;
				}
				if (min < 0 || max < min || max > 64) {
					problems.add("line " + n + ": count out of range in '" + part + "'");
					continue;
				}
				drops.add(new Drop(bits[0], min, max));
			}
			if (words.isEmpty() || drops.isEmpty()) {
				if (problems.size() == problemsBefore) {
					problems.add("line " + n + ": needs at least one word and one drop");
				}
				continue;
			}
			out.add(new Rule(List.copyOf(words), List.copyOf(drops)));
		}
		return new LootRules(List.copyOf(out));
	}

	/** The first rule with an entry whose words are all whole words of {@code name}, or null. */
	public Rule match(String name) {
		List<String> nameWords = ItemRules.words(name);
		if (nameWords.isEmpty()) {
			return null;
		}
		for (Rule rule : this.rules) {
			for (String word : rule.words()) {
				if (word.indexOf(' ') < 0 ? nameWords.contains(word) : nameWords.containsAll(List.of(word.split(" ")))) {
					return rule;
				}
			}
		}
		return null;
	}

	/** Rolls a count for each drop; zero counts are left out. */
	public static List<Drop> roll(Rule rule, Random random) {
		List<Drop> out = new ArrayList<>();
		for (Drop d : rule.drops()) {
			int count = d.min() + (d.max() > d.min() ? random.nextInt(d.max() - d.min() + 1) : 0);
			if (count > 0) {
				out.add(new Drop(d.item(), count, count));
			}
		}
		return out;
	}
}
