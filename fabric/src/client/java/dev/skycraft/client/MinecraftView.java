package dev.skycraft.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.skycraft.SkyCraft;
import dev.skycraft.SkyDimension;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft view: in any dimension but the Skyrim mirror (the Nether, Tamriel Wilds, Home, ...)
 * Minecraft draws its own world, and that whole picture goes to Skyrim as an opaque full frame
 * (FrameExporter, Proto.OVERLAY_OPAQUE) with Proto.MC_OTHER_DIMENSION set. Skyrim shows it over its
 * screen, parks its own player and stops driving it from here (View.cpp); every Skyrim-coupled system
 * here stands down (SkyDimension.inMirror). Back in the mirror, the player's position there leads:
 * Skyrim moves its player to it and everything goes back to normal.
 *
 * <p>Debug: {@code /skyview} takes the player (host only) to the Nether, or any other dimension the
 * world has, and back again; {@code /skyview <dimension>} to a given one, {@code /skyview mirror} back.
 */
public final class MinecraftView {
	/**
	 * In another dimension Minecraft renders its whole world at no more than this height (keeping
	 * Skyrim's aspect ratio) and Skyrim scales it up: a 4K Skyrim reads back and copies a quarter of
	 * the pixels each frame. 0: Skyrim's own size. -Dskycraft.viewMaxHeight=...
	 */
	private static final int MAX_HEIGHT = Integer.getInteger("skycraft.viewMaxHeight", 1080);
	/** Frame rate while away and Skyrim is paused (its menu, Alt-Tab), when Skyrim's frames don't pace us. */
	static final int STALLED_FPS = 60;
	private static final long STATS_NANOS = 10_000_000_000L;

	private static boolean wasAway;
	private static boolean diedAway;
	private static boolean returnPending;
	private static String where = "";
	private static long awaySince;

	// Frame copies (FrameExporter), for the log every 10 s while away.
	private static int shipped, skipped;
	private static long copyNanos, copyMaxNanos, statsSince;
	private static int shippedW, shippedH;

	// /skyview: where the player was taken from, per player.
	private static final Map<UUID, Vec3> TAKEN_FROM = new HashMap<>();

	private MinecraftView() {
	}

	/** Client init: the debug command. */
	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommands.literal("skyview")
			.executes(c -> command(c.getSource(), null))
			.then(ClientCommands.argument("dimension", StringArgumentType.greedyString()).executes(c -> command(c.getSource(), StringArgumentType.getString(c, "dimension"))))));
	}

	/** True if the local player is in a dimension other than the Skyrim mirror (live, any time on the render thread). */
	public static boolean away() {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && !SkyDimension.inMirror(level);
	}

	/**
	 * What McState tells Skyrim (Proto.MC_OTHER_DIMENSION): away now, or just back but not yet taken
	 * back by {@link #frame} (the level changes mid frame, after SkyClient's frame start), so Skyrim
	 * only follows once the player is held at the way back and its teleport taken as done.
	 */
	static boolean reportAway() {
		return away() || wasAway;
	}

	/** Minecraft's own frame cap while linked: none (Skyrim's frames pace it) unless it draws a whole world with nothing pacing it. */
	public static int frameLimit() {
		return away() && SkyClient.skyrimStalled() ? STALLED_FPS : 260;
	}

	/** Once the player is back from another dimension alive: their position here leads (SkyClient). Consumed. */
	static boolean takeReturn() {
		boolean r = returnPending;
		returnPending = false;
		return r;
	}

	/** The size Minecraft renders at while away, for Skyrim's viewport {@code w} x {@code h}. */
	static int[] viewport(int w, int h) {
		if (MAX_HEIGHT <= 0 || h <= MAX_HEIGHT) {
			return new int[] { w, h };
		}
		int sw = Math.max(2, (int) Math.round((double) w * MAX_HEIGHT / h)) & ~1;
		return new int[] { sw, MAX_HEIGHT };
	}

	/** Start of every frame (SkyClient.beginFrame): follows the player between the mirror and other dimensions. */
	static void frame(Minecraft minecraft, boolean linked) {
		boolean away = away();
		SkyDimension.localInMirror = !away;
		if (away && minecraft.player != null && minecraft.player.isDeadOrDying()) {
			diedAway = true;
		}
		if (away == wasAway) {
			if (away && linked) {
				logStats(false);
			}
			return;
		}
		wasAway = away;
		long now = System.nanoTime();
		if (away) {
			diedAway = false;
			returnPending = false;
			awaySince = now;
			where = minecraft.level.dimension().identifier().toString();
			resetStats(now);
			SkyCraft.LOG.info("SkyCraft: Minecraft view: the player is in {}; Minecraft draws its own world and Skyrim's player waits (Skyrim link {})", where,
				linked ? "up" : "down");
		} else {
			// Back alive: Minecraft's position leads. Back by dying (respawned here): Skyrim's player
			// died too, and Skyrim puts the player where it reloads, as after any death. Out of the
			// world altogether (quit, disconnected), or Skyrim not there: Skyrim puts them back as on any
			// join or link.
			boolean inWorld = minecraft.level != null;
			returnPending = !diedAway && inWorld && linked;
			logStats(true);
			SkyCraft.LOG.info("SkyCraft: Minecraft view: {} after {} s in {}{}", inWorld ? "back in the Skyrim mirror" : "left the world",
				String.format("%.1f", (now - awaySince) / 1.0e9), where, diedAway ? " (died there; Skyrim takes over where it reloads)" : "");
		}
	}

	// ---- frame copy stats ----------------------------------------------------------------------

	static void noteShipped(long nanos, int width, int height, boolean opaque) {
		if (!opaque) {
			return;
		}
		shipped++;
		copyNanos += nanos;
		copyMaxNanos = Math.max(copyMaxNanos, nanos);
		shippedW = width;
		shippedH = height;
	}

	static void noteSkipped() {
		if (wasAway) {
			skipped++;
		}
	}

	private static void resetStats(long now) {
		shipped = skipped = 0;
		copyNanos = copyMaxNanos = 0;
		statsSince = now;
	}

	private static void logStats(boolean finalLine) {
		long now = System.nanoTime();
		if (!finalLine && now - statsSince < STATS_NANOS) {
			return;
		}
		double seconds = Math.max(1.0e-3, (now - statsSince) / 1.0e9);
		if (shipped > 0) {
			SkyCraft.LOG.info("SkyCraft: Minecraft view: {} full frames to Skyrim in {} s ({} a second), {}x{} ({} MB each), copy into shared memory {} ms on average, {} ms at most; {} skipped (GPU readback still busy)",
				shipped, String.format("%.1f", seconds), String.format("%.1f", shipped / seconds), shippedW, shippedH, String.format("%.1f", shippedW * (double) shippedH * 4.0 / (1 << 20)),
				String.format("%.2f", copyNanos / 1.0e6 / shipped), String.format("%.2f", copyMaxNanos / 1.0e6), skipped);
		}
		resetStats(now);
	}

	// ---- /skyview --------------------------------------------------------------------------------

	private static int command(FabricClientCommandSource source, @Nullable String dimension) {
		Minecraft minecraft = source.getClient();
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null) {
			source.sendError(Component.literal("/skyview works in your own world only."));
			return 0;
		}
		if (!SkyDimension.viewEnabled()) {
			source.sendError(Component.literal("The Minecraft view is off (minecraft_view in config/skycraft-features.txt)."));
			return 0;
		}
		boolean toMirror = dimension == null ? away() : dimension.strip().equalsIgnoreCase("mirror");
		Identifier wanted = null;
		if (!toMirror && dimension != null) {
			wanted = Identifier.tryParse(dimension.strip());
			if (wanted == null) {
				source.sendError(Component.literal("Not a dimension id: " + dimension.strip()));
				return 0;
			}
		}
		// Back to where Skyrim's player is (parked where the player left, unless Skyrim moved them since).
		SkyLinkPos sky = SkyClient.linked() && SkyClient.sky().inGame() ? new SkyLinkPos(SkyClient.sky().x, SkyClient.sky().y, SkyClient.sky().z) : null;
		UUID uuid = minecraft.player.getUUID();
		Identifier target = wanted;
		// After the chat screen has closed, on the server thread.
		minecraft.execute(() -> server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) {
				String result = toMirror ? back(server, player, sky) : away(server, player, target);
				minecraft.execute(() -> {
					if (minecraft.player != null) {
						minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(result));
					}
				});
			}
		}));
		return 1;
	}

	private record SkyLinkPos(double x, double y, double z) {
	}

	/** Server thread: to {@code wanted}, or the Nether, or any dimension that isn't the mirror. */
	private static String away(MinecraftServer server, ServerPlayer player, @Nullable Identifier wanted) {
		ServerLevel to = null;
		for (ServerLevel level : server.getAllLevels()) {
			Identifier id = level.dimension().identifier();
			if (wanted != null ? id.equals(wanted) : !SkyDimension.inMirror(level) && (to == null || level.dimension() == Level.NETHER)) {
				to = level;
			}
		}
		if (to == null) {
			return wanted != null ? "This world has no dimension " + wanted + "." : "This world has only the Skyrim mirror (the worlds data pack adds the Nether and others).";
		}
		if (to == player.level()) {
			return "Already in " + to.dimension().identifier() + ".";
		}
		if (SkyDimension.inMirror(to) && SkyDimension.inMirror(player.level())) {
			return to.dimension().identifier() + " mirrors Skyrim too (or minecraft_view is off): nothing to show.";
		}
		if (SkyDimension.inMirror(player.level())) {
			TAKEN_FROM.put(player.getUUID(), player.position());
		}
		BlockPos spot = safeSpot(to, player.getBlockX(), player.getBlockZ());
		player.teleportTo(to, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
		SkyCraft.LOG.info("SkyCraft: /skyview: {} to {} at {}", player.getName().getString(), to.dimension().identifier(), spot.toShortString());
		return "Off to " + to.dimension().identifier() + " (" + spot.toShortString() + "). /skyview again to go back.";
	}

	/** Server thread: back into the mirror where Skyrim's player is (or where /skyview took them from). */
	private static String back(MinecraftServer server, ServerPlayer player, @Nullable SkyLinkPos sky) {
		if (SkyDimension.inMirror(player.level())) {
			return "Already in the Skyrim mirror.";
		}
		ServerLevel mirror = null;
		for (ServerLevel level : server.getAllLevels()) {
			if (SkyDimension.inMirror(level) && (mirror == null || level.dimension() == Level.OVERWORLD)) {
				mirror = level;
			}
		}
		Vec3 from = TAKEN_FROM.remove(player.getUUID());
		Vec3 to = sky != null ? new Vec3(sky.x(), sky.y(), sky.z()) : from;
		if (mirror == null || to == null) {
			return "Don't know where Skyrim's player is: wait for Skyrim's game to load, then /skyview again.";
		}
		player.teleportTo(mirror, to.x, to.y, to.z, Set.of(), player.getYRot(), player.getXRot(), true);
		SkyCraft.LOG.info("SkyCraft: /skyview: {} back to the mirror at {}", player.getName().getString(), to);
		return "Back in the Skyrim mirror.";
	}

	/**
	 * Feet position of an open spot with a solid floor in this column (a cave or ledge in the Nether),
	 * or failing that a small obsidian platform at a middle height.
	 */
	private static BlockPos safeSpot(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4); // loaded (generated) now
		int min = level.getMinY() + 1;
		int top = Math.min(level.getMaxY(), level.getMinY() + level.getLogicalHeight()) - 3;
		int start = Math.min(top, Math.max(min, 100));
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = start; y >= min; y--) {
			if (standable(level, p, x, y, z)) {
				return new BlockPos(x, y, z);
			}
		}
		for (int y = start + 1; y <= top; y++) {
			if (standable(level, p, x, y, z)) {
				return new BlockPos(x, y, z);
			}
		}
		int y = Math.max(min + 1, Math.min(top - 1, (min + top) / 2));
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz), Blocks.OBSIDIAN.defaultBlockState());
				for (int dy = 0; dy < 3; dy++) {
					level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState());
				}
			}
		}
		return new BlockPos(x, y, z);
	}

	private static boolean standable(ServerLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
		BlockState floor = level.getBlockState(p.set(x, y - 1, z));
		if (!floor.isFaceSturdy(level, p, Direction.UP) || !floor.getFluidState().isEmpty() || floor.is(Blocks.MAGMA_BLOCK)) {
			return false;
		}
		for (int dy = 0; dy < 2; dy++) {
			BlockState s = level.getBlockState(p.set(x, y + dy, z));
			if (!s.isAir() || !s.getFluidState().isEmpty()) {
				return false;
			}
		}
		return true;
	}
}
