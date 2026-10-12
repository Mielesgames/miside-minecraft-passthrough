package dev.skycraft.loot;

import dev.skycraft.SkyCraft;
import dev.skycraft.config.Config;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * The first time the player takes anything from a chest in a Skyrim dungeon, a Minecraft chest loot
 * table is rolled into their inventory too. Which table comes from weighted lines in
 * {@code config/skycraft-dungeons.txt}; opened chests are remembered in
 * {@code config/skycraft-opened-chests.txt} so each chest pays out once.
 */
public final class DungeonLoot {
	public static final String DEFAULT_TEXT = """
		# SkyCraft dungeon chests. The first time you loot a chest in a Skyrim dungeon, one of these
		# Minecraft loot tables is rolled into your inventory as well. Format: <loot table> <weight>
		minecraft:chests/simple_dungeon 6
		minecraft:chests/abandoned_mineshaft 4
		minecraft:chests/ruined_portal 2
		minecraft:chests/stronghold_corridor 1
		minecraft:chests/buried_treasure 1
		""";
	private static final String FILE = "skycraft-dungeons.txt";
	private static final String OPENED_FILE = "skycraft-opened-chests.txt";
	private static final Random RANDOM = new Random();

	public record Table(String id, int weight) {
	}

	private static List<Table> tables;
	private static Set<Integer> opened;

	private DungeonLoot() {
	}

	/** Parses weighted loot table lines; bad lines go to {@code problems}. */
	public static List<Table> parse(String text, List<String> problems) {
		List<Table> out = new ArrayList<>();
		int n = 0;
		for (String raw : text.split("\\R")) {
			n++;
			String line = raw.strip();
			int hash = line.indexOf('#');
			if (hash >= 0) {
				line = line.substring(0, hash).strip();
			}
			if (line.isEmpty()) {
				continue;
			}
			String[] parts = line.split("\\s+");
			int weight = 1;
			if (parts.length > 2) {
				problems.add("line " + n + ": expected '<loot table> <weight>'");
				continue;
			}
			if (parts.length == 2) {
				try {
					weight = Integer.parseInt(parts[1]);
				} catch (NumberFormatException e) {
					problems.add("line " + n + ": weight '" + parts[1] + "' is not a number");
					continue;
				}
			}
			if (weight <= 0 || !parts[0].matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				problems.add("line " + n + ": bad loot table or weight");
				continue;
			}
			out.add(new Table(parts[0], weight));
		}
		return out;
	}

	/** Picks one table by weight, or null if there are none. */
	public static String pick(List<Table> tables, Random random) {
		int total = 0;
		for (Table t : tables) {
			total += t.weight();
		}
		if (total <= 0) {
			return null;
		}
		int r = random.nextInt(total);
		for (Table t : tables) {
			r -= t.weight();
			if (r < 0) {
				return t.id();
			}
		}
		return tables.getLast().id();
	}

	/** Whether this looted item should roll a dungeon chest (and marks the chest as rolled). */
	static boolean shouldRoll(SkyLink.Item it, int place, Set<Integer> opened) {
		if (it.source() != Proto.SRC_CONTAINER || !it.chest() || it.container() == 0 || (place & Proto.PLACE_DUNGEON) == 0) {
			return false;
		}
		return opened.add(it.container());
	}

	/**
	 * Called for every item the player loots in Skyrim, before conversion. Server thread. Rolls like
	 * {@code /loot give <player> loot <table>}, except that what doesn't fit in the inventory drops at
	 * the player's feet instead of being lost.
	 */
	public static void onLooted(ServerPlayer player, SkyLink.Item it, int place) {
		Set<Integer> seen = opened();
		if (!shouldRoll(it, place, seen)) {
			return;
		}
		remember(it.container());
		String table = pick(tables(), RANDOM);
		if (table == null) {
			return;
		}
		ServerLevel level = player.level();
		LootTable loot = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(table)));
		if (loot == LootTable.EMPTY) {
			SkyCraft.LOG.warn("SkyCraft-loot: unknown loot table '{}' in {}", table, FILE);
			return;
		}
		List<ItemStack> stacks = loot.getRandomItems(new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, player.position())
			.create(LootContextParamSets.CHEST));
		for (ItemStack stack : stacks) {
			SkyBridge.give(player, stack);
		}
		SkyCraft.LOG.info("SkyCraft-loot: first loot of dungeon chest {} rolled {} ({} stacks)", String.format("%08X", it.container()), table, stacks.size());
	}

	/** One of the configured dungeon chest tables, picked by weight (breach chests use these too), or null if there are none. */
	public static String pickTable(Random random) {
		return pick(tables(), random);
	}

	private static List<Table> tables() {
		if (tables == null) {
			List<String> problems = new ArrayList<>();
			tables = parse(Config.read(FILE, DEFAULT_TEXT), problems);
			problems.forEach(p -> SkyCraft.LOG.warn("SkyCraft-loot: {} {}", FILE, p));
		}
		return tables;
	}

	/** Adds the chest ids (hex, one per line) to {@code out}; returns how many lines were not one. */
	static int parseOpened(List<String> lines, Set<Integer> out) {
		int bad = 0;
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty()) {
				continue;
			}
			try {
				out.add(Integer.parseUnsignedInt(line, 16));
			} catch (NumberFormatException e) {
				bad++; // e.g. a line cut short by a crash; the chests after it still count as opened
			}
		}
		return bad;
	}

	private static Set<Integer> opened() {
		if (opened == null) {
			opened = new HashSet<>();
			Path path = FabricLoader.getInstance().getConfigDir().resolve(OPENED_FILE);
			try {
				if (Files.exists(path)) {
					int bad = parseOpened(Files.readAllLines(path, StandardCharsets.UTF_8), opened);
					if (bad > 0) {
						SkyCraft.LOG.warn("SkyCraft-loot: skipped {} unreadable lines in {}", bad, path);
					}
				}
			} catch (IOException e) {
				SkyCraft.LOG.warn("SkyCraft-loot: couldn't read {}: {}", path, e.toString());
			}
		}
		return opened;
	}

	private static void remember(int container) {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(OPENED_FILE);
		try {
			Files.writeString(path, String.format("%08X%n", container), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft-loot: couldn't write {}: {}", path, e.toString());
		}
	}
}
