package dev.skycraft;

import com.mojang.brigadier.CommandDispatcher;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * "/miside <option> <value>": how Steve's hits and clicks work on MiSide's world, kept with the
 * Minecraft world (misidecraft.properties in its folder) and told to the host every two seconds
 * (EV_SETTING), so a MiSide that starts later knows them too.
 */
public final class SkySettings {
	private SkySettings() {
	}

	/** An option: its host number (EV_SETTING's formId), what it does, and its values (flags = index). */
	private record Option(int id, String help, String... values) {
	}

	private static final Map<String, Option> OPTIONS = new LinkedHashMap<>();
	static {
		OPTIONS.put("mita", new Option(Proto.SETTING_MITA,
			"hitting a Mita: realistic = only her hair and clothes swing, minecraft = she is knocked back", "realistic", "minecraft"));
		OPTIONS.put("npcs", new Option(Proto.SETTING_NPCS,
			"hitting the other characters: realistic = only physics, minecraft = they are knocked back", "realistic", "minecraft"));
		OPTIONS.put("doors", new Option(Proto.SETTING_DOORS,
			"doors: realistic = hit them open, minecraft = right-click opens and closes, left-click breaks them (when block breaking is on)",
			"realistic", "minecraft"));
		OPTIONS.put("reactions", new Option(Proto.SETTING_REACTIONS,
			"the part of a character you hit (head, arm, body) sways back from the blow", "on", "off"));
		OPTIONS.put("sweep", new Option(Proto.SETTING_SWEEP,
			"a sword's sweep attack cuts a character in half: only her legs walk on", "on", "off"));
		OPTIONS.put("physics", new Option(Proto.SETTING_PHYSICS,
			"every hit also pushes MiSide's physics (doors, things lying around, hair) where you aim", "on", "off"));
	}

	private static final Map<String, String> DEFAULTS = Map.of("mita", "realistic", "npcs", "realistic", "doors", "realistic", "physics", "on", "reactions", "on", "sweep", "on");
	private static final Properties VALUES = new Properties();
	private static Path file;
	private static int ticks;

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			load(server);
			// /tick goes to the host's time too: a world always opens at the normal speed, so a rate
			// left too high (or a freeze) can never lock the game for good.
			server.tickRateManager().setTickRate(20.0F);
			server.tickRateManager().setFrozen(false);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (ticks++ % 40 == 0) {
				send(server);
			}
			keepTickRate(server);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	// The host's own time can't go faster than this, nor slower: /tick rate is kept within.
	private static final float MAX_TICK_RATE = 60.0F;
	private static final float MIN_TICK_RATE = 1.0F;

	private static void keepTickRate(MinecraftServer server) {
		var manager = server.tickRateManager();
		float rate = manager.tickrate();
		float kept = Math.max(MIN_TICK_RATE, Math.min(MAX_TICK_RATE, rate));
		if (kept != rate) {
			manager.setTickRate(kept);
			server.getPlayerList().broadcastSystemMessage(Component.literal(
				"MiSide's time goes along with /tick: the rate stays between " + (int) MIN_TICK_RATE + " and " + (int) MAX_TICK_RATE + ".").withStyle(ChatFormatting.YELLOW), false);
			SkyCraft.LOG.info("MiSideCraft: tick rate {} kept at {}", rate, kept);
		}
	}

	public static String get(String option) {
		return VALUES.getProperty(option, DEFAULTS.get(option));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("miside").executes(context -> {
			for (var name : OPTIONS.keySet()) {
				show(context.getSource(), name);
			}
			return 1;
		});
		for (var entry : OPTIONS.entrySet()) {
			String name = entry.getKey();
			var option = Commands.literal(name).executes(context -> {
				show(context.getSource(), name);
				return 1;
			});
			for (String value : entry.getValue().values()) {
				option.then(Commands.literal(value).executes(context -> set(context.getSource(), name, value)));
			}
			root.then(option);
		}
		dispatcher.register(root);
	}

	private static void show(CommandSourceStack source, String name) {
		var option = OPTIONS.get(name);
		source.sendSuccess(() -> Component.literal(name + ": ").withStyle(ChatFormatting.YELLOW)
			.append(Component.literal(get(name)).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(" (" + String.join(" | ", option.values()) + "; " + option.help() + ")").withStyle(ChatFormatting.GRAY)), false);
	}

	private static int set(CommandSourceStack source, String name, String value) {
		VALUES.setProperty(name, value);
		save();
		send(source.getServer());
		SkyCraft.LOG.info("MiSideCraft: /miside {} {}", name, value);
		source.sendSuccess(() -> Component.literal(name + " is " + value + " now."), false);
		return 1;
	}

	private static void send(MinecraftServer server) {
		if (!SkyLink.active()) {
			return;
		}
		for (var entry : OPTIONS.entrySet()) {
			int index = java.util.Arrays.asList(entry.getValue().values()).indexOf(get(entry.getKey()));
			SkyLink.pushEvent(Proto.EV_SETTING, entry.getValue().id(), 0, 0, 0, 0, Math.max(0, index));
		}
		// Whether Steve may break MiSide's things (the block breaking experiment).
		SkyLink.pushEvent(Proto.EV_SETTING, Proto.SETTING_BLOCK_BREAKING, 0, 0, 0, 0, SkyCraft.experiments(server) ? 1 : 0);
	}

	private static void load(MinecraftServer server) {
		VALUES.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("misidecraft.properties");
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				VALUES.load(reader);
			} catch (IOException e) {
				SkyCraft.LOG.warn("MiSideCraft: couldn't read {}", file, e);
			}
		}
		SkyCraft.LOG.info("MiSideCraft: settings {}", VALUES);
	}

	private static void save() {
		if (file == null) {
			return;
		}
		try (Writer writer = Files.newBufferedWriter(file)) {
			VALUES.store(writer, "MiSideCraft: /miside <option> <value>");
		} catch (IOException e) {
			SkyCraft.LOG.warn("MiSideCraft: couldn't write {}", file, e);
		}
	}
}
