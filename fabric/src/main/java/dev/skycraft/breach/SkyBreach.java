package dev.skycraft.breach;

import dev.skycraft.SkyCraft;
import dev.skycraft.config.Features;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.loot.DungeonLoot;
import dev.skycraft.net.SkyNet;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyDig;
import dev.skycraft.world.SkyTri;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Breach doors, Minecraft side: a hidden antechamber under each dungeon entrance Skyrim reports
 * (SkyLink.readBreaches). Dig down to it, break through its themed walls (with a good enough
 * pickaxe) and open its sealed door: Skyrim takes the player into the real dungeon
 * (EV_ENTER_DUNGEON) and marks it on the map.
 *
 * <p>How it fits digging (SkyDig): a room is planned (BreachPlanner) once the land over it is
 * known, and kept in the world's {@code skycraft-breaches.txt}. Until it's reached it's ordinary
 * virtual rock. The first time any of its cells is dug into (a reveal, a mined surface or an
 * explosion; see {@link #claim}) the whole room is built at once: the shell becomes the theme's
 * blocks, then every cell of it is marked dug (Skyrim's geometry cut out; a shell cell only once
 * its block is in, so the room never opens into the rock's void), the interior open air, and the
 * door, lights, decor and (for an uncleared dungeon) a loot chest go in. Near a room the player's
 * tunnel leaks the theme's particles and sounds. Planning, leaks and opening the seal are the
 * host's (their Skyrim is on the link); the walls and door are real blocks for everyone.
 */
public final class SkyBreach {
	private static final String FILE = "skycraft-breaches.txt";
	/** Opening a seal at most this often (a load screen follows). */
	private static final long OPEN_COOLDOWN_NANOS = 3_000_000_000L;
	private static final double LEAK_RANGE = 10.0;
	/** Rooms planned per second at most (each looks at the land over it once). */
	private static final int PLANS_PER_REFRESH = 4;
	/** Send to clients, no shape updates: doors, fences and torches stay exactly as placed. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
	private static final List<SkyLink.Breach> TABLE = new ArrayList<>();
	/** The latest record of each entrance near the host (in {@link #currentWorld}). */
	private static final Map<Integer, SkyLink.Breach> NEAR = new HashMap<>();
	/** Entrances whose room can't go anywhere (too steep, out of the world, in another's way). */
	private static final Set<Long> GAVE_UP = new HashSet<>();
	private static final Random RANDOM = new Random();
	/** The Skyrim worldspace the host is outdoors in, or -1 (indoors, loading, unlinked). */
	private static int currentWorld = -1;
	private static @Nullable MinecraftServer storeServer;
	private static @Nullable BreachStore store;
	private static long lastOpen;
	private static boolean openedOnce;

	/**
	 * A sealed door the host opened that Skyrim hasn't acted on yet. Skyrim can refuse silently (the
	 * door went stale, the player is riding or dead, too far, already moving): no world change within
	 * {@link #PENDING_NANOS} means it did.
	 */
	private record Pending(int doorRef, String name, UUID player, long since, int worldId, int teleportSeq, int epoch) {
	}

	private static final long PENDING_NANOS = 6_000_000_000L;
	private static @Nullable Pending pending;

	private SkyBreach() {
	}

	public static void init() {
		UseBlockCallback.EVENT.register(SkyBreach::onUse);
		PlayerBlockBreakEvents.BEFORE.register(SkyBreach::beforeBreak);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			store = null;
			storeServer = null;
			GAVE_UP.clear();
			reset();
		});
	}

	/** The link dropped or nobody is playing: forget what Skyrim last reported. */
	public static void reset() {
		currentWorld = -1;
		NEAR.clear();
		TABLE.clear();
		pending = null;
	}

	private static boolean on() {
		return Features.on(Features.Feature.BREACH_DOORS);
	}

	// ---- store --------------------------------------------------------------------------------

	private static BreachStore store(MinecraftServer server) {
		BreachStore s = store;
		if (s == null || storeServer != server) {
			s = load(server);
			store = s;
			storeServer = server;
		}
		return s;
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE).normalize();
	}

	private static BreachStore load(MinecraftServer server) {
		Path path = path(server);
		if (!Files.exists(path)) {
			return new BreachStore();
		}
		try {
			List<String> problems = new ArrayList<>();
			BreachStore s = BreachStore.parse(Files.readAllLines(path, StandardCharsets.UTF_8), problems);
			problems.forEach(p -> SkyCraft.LOG.warn("SkyCraft-breach: {} {}", path, p));
			SkyCraft.LOG.info("SkyCraft-breach: {} antechambers known in this world", s.size());
			return s;
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft-breach: couldn't read {}: {}", path, e.toString());
			return new BreachStore();
		}
	}

	private static void save(MinecraftServer server, BreachStore s) {
		Path path = path(server);
		Path tmp = path.resolveSibling(FILE + ".tmp");
		try {
			Files.write(tmp, s.lines(), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			SkyCraft.LOG.warn("SkyCraft-breach: couldn't write {}: {}", path, e.toString());
		}
	}

	/**
	 * Rooms planned before each Skyrim world had a space of its own (SkySpace) are round the origin; a
	 * room of a worldspace other than Tamriel (whose space is the origin) moves into its world's space.
	 * Its blocks are cells dug in that world, which SpaceMigration moves with the world's other dug cells.
	 * Returns how many rooms moved.
	 */
	public static int moveIntoSpaces(MinecraftServer server) {
		BreachStore s = store(server);
		List<BreachStore.Entry> moved = new ArrayList<>();
		for (BreachStore.Entry e : s.all()) {
			BreachRoom r = e.room();
			if (r.world() == dev.skycraft.link.WorldSpaces.TAMRIEL || r.world() == 0 || dev.skycraft.link.WorldSpaces.spaceAt(r.bx(), r.bz()) != 0) {
				continue;
			}
			int dx = dev.skycraft.link.SkySpace.originX(r.world()), dz = dev.skycraft.link.SkySpace.originZ(r.world());
			if (dx == 0 && dz == 0) {
				continue;
			}
			moved.add(new BreachStore.Entry(new BreachRoom(r.world(), r.doorRef(), r.bx() + dx, r.floorY(), r.bz() + dz, r.facing(), r.shell(), r.theme()),
				e.state(), e.name()));
		}
		for (BreachStore.Entry e : moved) {
			s.put(e);
			SkyCraft.LOG.info("SkyCraft-breach: the antechamber of {} moved into its world's space ({} {})", displayName(e), e.room().bx(), e.room().bz());
		}
		if (!moved.isEmpty()) {
			save(server, s);
		}
		return moved.size();
	}

	private static String displayName(BreachStore.Entry e) {
		if (!e.name().isEmpty()) {
			return e.name();
		}
		SkyLink.Breach b = e.room().world() == currentWorld ? NEAR.get(e.room().doorRef()) : null;
		if (b != null && !b.name().isEmpty()) {
			return b.name();
		}
		return "Unknown " + BreachPlanner.themeName(e.room().theme());
	}

	// ---- every tick (host) --------------------------------------------------------------------

	/** Every server tick, for the host player. {@code players}: everyone in the world. */
	public static void tick(MinecraftServer server, ServerPlayer host, List<ServerPlayer> players) {
		if (!on()) {
			return;
		}
		int t = server.getTickCount();
		ServerLevel level = host.level();
		if (pending != null && t % 5 == 0) {
			checkPending(server);
		}
		if (t % 20 == 0) {
			refresh(server, level);
		}
		if (currentWorld == -1) {
			return;
		}
		if (t % 10 == 0) {
			checkInside(level, players);
		}
		if (t % 5 == 0) {
			leak(level, host);
		}
	}

	/** Once a second: where the host's Skyrim is, the entrances near them, and rooms for new ones. */
	private static void refresh(MinecraftServer server, ServerLevel level) {
		if (!SkyLink.readSkyState(SKY) || !SKY.inGame() || SKY.loading()) {
			return;
		}
		if ((SKY.place & Proto.PLACE_INTERIOR) != 0) {
			reset(); // no dungeon entrances indoors (Skyrim sends none): no rooms
			return;
		}
		if (SKY.worldId != currentWorld) {
			NEAR.clear();
		}
		currentWorld = SKY.worldId;
		int world = SkyLink.readBreaches(TABLE);
		if (world == -1 || world != currentWorld) {
			return; // a torn read, or the table is still for the last world
		}
		BreachStore s = store(server);
		int tried = 0;
		int before = s.size();
		NEAR.clear();
		for (SkyLink.Breach b : TABLE) {
			if (b.doorRef() == 0 || !Double.isFinite(b.x()) || !Double.isFinite(b.y()) || !Double.isFinite(b.z())) {
				continue;
			}
			NEAR.put(b.doorRef(), b);
			long key = BreachPlanner.key(world, b.doorRef());
			if (tried < PLANS_PER_REFRESH && s.get(world, b.doorRef()) == null && !GAVE_UP.contains(key)) {
				if (plan(level, s, world, b)) {
					tried++;
				}
			}
		}
		if (s.size() != before) {
			save(server, s);
		}
	}

	/**
	 * Works out a room for an entrance once the land over it is known (and the chunks it's in are
	 * loaded). Returns false if not yet (try again later), true once it's planned or given up on.
	 */
	private static boolean plan(ServerLevel level, BreachStore s, int world, SkyLink.Breach b) {
		if (!SkyCollision.active()) {
			return false;
		}
		int doorX = (int) Math.floor(b.x()), doorZ = (int) Math.floor(b.z());
		BreachRoom room = BreachPlanner.place(world, b.doorRef(), doorX, doorZ, b.yaw(), b.theme(), b.level(), 0);
		int[][] footprint = BreachPlanner.footprint(room);
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (int[] p : footprint) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minZ = Math.min(minZ, p[1]);
			maxZ = Math.max(maxZ, p[1]);
		}
		// The land over the footprint (where it was before anything was dug), well round the door's height.
		SkyDig.Probe probe = new SkyDig.Probe().around(minX, b.y() - 64.0, minZ, maxX + 1, b.y() + 48.0, maxZ + 1);
		double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
		for (int[] p : footprint) {
			double h = probe.landHeight(p[0] + 0.5, p[1] + 0.5);
			if (Double.isNaN(h) || !SkyCollision.isKnown(p[0], (int) Math.floor(h), p[1])) {
				return false; // not known there yet: never build blind
			}
			low = Math.min(low, h);
			high = Math.max(high, h);
		}
		long key = BreachPlanner.key(world, b.doorRef());
		int floorY = BreachPlanner.floorY(b.level(), room.shell(), low, high);
		if (floorY == BreachPlanner.NO_ROOM) {
			giveUp(key, b, "the land over it is too steep");
			return true;
		}
		room = room.withFloorY(floorY);
		if (room.minY() <= level.getMinY() || room.maxY() >= level.getMaxY()) {
			giveUp(key, b, "it would be outside the world's height");
			return true;
		}
		BreachStore.Entry other = s.overlapping(room, 1);
		if (other != null) {
			giveUp(key, b, "it would run into " + displayName(other) + "'s");
			return true;
		}
		if (!loaded(level, room)) {
			return false;
		}
		if (anyDug(level, room)) {
			// An old tunnel (dug before this room was planned) runs through it: a room built round it
			// would have holes and be found without ever breaking in.
			giveUp(key, b, "someone already dug through there");
			return true;
		}
		s.put(new BreachStore.Entry(room, 0, b.name()));
		SkyCraft.LOG.info("SkyCraft-breach: planned {} ({}, level {}) under door {} at {} {} {}: {} blocks down, walls {} thick, needs {}", b.name(),
			BreachPlanner.themeName(room.theme()), b.level(), String.format("%08X", b.doorRef()), room.x(BreachRoom.DOOR_U, BreachRoom.LENGTH), floorY,
			room.z(BreachRoom.DOOR_U, BreachRoom.LENGTH), (int) Math.floor(low) - room.maxY(), room.shell(), BreachPlanner.pickaxeName(BreachPlanner.tier(room.theme())));
		return true;
	}

	private static void giveUp(long key, SkyLink.Breach b, String why) {
		if (GAVE_UP.add(key)) {
			SkyCraft.LOG.info("SkyCraft-breach: no antechamber for {} (door {}): {}", b.name(), String.format("%08X", b.doorRef()), why);
		}
	}

	// ---- building -----------------------------------------------------------------------------

	/**
	 * Called by SkyDig (and SkyDigBlast) before a cell is dug out of Skyrim's geometry. If the cell
	 * is part of an antechamber in that Skyrim world, the room is built whole (the first time) and
	 * this returns true: the cell is the room's, leave it be.
	 */
	public static boolean claim(ServerLevel level, int world, BlockPos pos) {
		if (!on()) {
			return false;
		}
		BreachStore s = store(level.getServer());
		if (s.size() == 0) {
			return false;
		}
		BreachStore.Entry e = s.roomAt(world, pos.getX(), pos.getY(), pos.getZ());
		if (e == null) {
			return false;
		}
		if (!e.has(BreachStore.BUILT)) {
			build(level, s, e);
		}
		return true;
	}

	private static BlockPos at(BreachRoom r, int u, int v, int w) {
		return new BlockPos(r.x(u, w), r.y(v), r.z(u, w));
	}

	private static BlockPos at(BreachRoom r, int[] slot) {
		return at(r, slot[0], slot[1], slot[2]);
	}

	/** Whether every chunk the room is in is loaded (its four corners cover them: it's under 16 across). */
	private static boolean loaded(ServerLevel level, BreachRoom r) {
		for (BlockPos corner : new BlockPos[] { new BlockPos(r.minX(), r.minY(), r.minZ()), new BlockPos(r.maxX(), r.minY(), r.minZ()),
			new BlockPos(r.minX(), r.minY(), r.maxZ()), new BlockPos(r.maxX(), r.minY(), r.maxZ()) }) {
			if (!level.isLoaded(corner)) {
				return false;
			}
		}
		return true;
	}

	private static boolean anyDug(ServerLevel level, BreachRoom r) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = r.minX(); x <= r.maxX(); x++) {
			for (int z = r.minZ(); z <= r.maxZ(); z++) {
				for (int y = r.minY(); y <= r.maxY(); y++) {
					if (SkyDig.isDug(level, r.world(), p.set(x, y, z))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void build(ServerLevel level, BreachStore s, BreachStore.Entry e) {
		BreachRoom r = e.room();
		if (!loaded(level, r)) {
			return; // all of it or nothing; it's built when next dug into
		}
		// Cells dug before (an old tunnel through here) stay as they are: that part of the wall is
		// already breached.
		List<BlockPos> fresh = new ArrayList<>();
		for (int x = r.minX(); x <= r.maxX(); x++) {
			for (int y = r.minY(); y <= r.maxY(); y++) {
				for (int z = r.minZ(); z <= r.maxZ(); z++) {
					BlockPos p = new BlockPos(x, y, z);
					if (!SkyDig.isDug(level, r.world(), p)) {
						fresh.add(p);
					}
				}
			}
		}
		// The walls and the door first, then every fresh cell is the room's (Skyrim's geometry cut
		// out): a cell is never open to Skyrim and Minecraft before its block is in.
		Direction facing = Direction.from2DDataValue(r.facing());
		int walls = 0;
		List<BlockPos> claimed = new ArrayList<>(fresh.size());
		for (BlockPos p : fresh) {
			int kind = r.kind(p.getX(), p.getY(), p.getZ());
			if (kind == BreachRoom.OUTSIDE) {
				continue;
			}
			if (kind != BreachRoom.INTERIOR) {
				BlockState here = level.getBlockState(p);
				if (here.isAir() || here.canBeReplaced()) {
					BlockState state = kind == BreachRoom.DOOR
						? BreachBlocks.door(facing, r.u(p.getX(), p.getZ()) == BreachRoom.DOOR_U + 1, r.v(p.getY()) == 1)
						: BreachBlocks.shell(r, p.getX(), p.getY(), p.getZ());
					level.setBlock(p, state, FLAGS);
					walls++;
				}
				if (level.getBlockState(p).isAir()) {
					continue; // no wall went in (it can't be open): it stays Skyrim's rock
				}
			}
			claimed.add(p);
		}
		SkyDig.markDug(level, r.world(), claimed);
		furnish(level, r);
		BreachStore.Entry built = e.with(BreachStore.BUILT);
		built = loot(level, built);
		s.put(built);
		save(level.getServer(), s);
		SkyCraft.LOG.info("SkyCraft-breach: built the antechamber of {} ({}) at {} {} {}: {} wall blocks{}", displayName(e), BreachPlanner.themeName(r.theme()),
			r.x(BreachRoom.DOOR_U, BreachRoom.LENGTH), r.floorY(), r.z(BreachRoom.DOOR_U, BreachRoom.LENGTH), walls,
			built.has(BreachStore.LOOT) && !e.has(BreachStore.LOOT) ? ", loot decided" : "");
	}

	/** Lights and decor, in the interior's open cells. */
	private static void furnish(ServerLevel level, BreachRoom r) {
		Direction facing = Direction.from2DDataValue(r.facing());
		int theme = r.theme();
		put(level, at(r, BreachRoom.LANTERN_SLOT), BreachBlocks.lantern(theme));
		for (int[] slot : BreachRoom.TORCH_SLOTS) {
			put(level, at(r, slot), BreachBlocks.wallTorch(theme, facing.getOpposite()));
		}
		for (int i = 0; i < BreachRoom.FLOOR_CORNERS.length; i++) {
			BlockState state = BreachBlocks.floorCorner(theme, i);
			if (state == null) {
				continue;
			}
			int[] c = BreachRoom.FLOOR_CORNERS[i];
			for (int k = 0; k < BreachBlocks.floorCornerHeight(theme, i); k++) {
				put(level, at(r, c[0], c[1] + k, c[2]), state);
			}
		}
		for (int i = 0; i < BreachRoom.CEILING_CORNERS.length; i++) {
			BlockState state = BreachBlocks.ceilingCorner(theme, i);
			if (state != null) {
				put(level, at(r, BreachRoom.CEILING_CORNERS[i]), state);
			}
		}
	}

	private static void put(ServerLevel level, BlockPos pos, BlockState state) {
		if (level.getBlockState(pos).isAir()) {
			level.setBlock(pos, state, FLAGS);
		}
	}

	/**
	 * The breach chest, decided once per dungeon: a chest of one of the dungeon chest loot tables
	 * (skycraft-dungeons.txt) if the dungeon isn't cleared. Needs the dungeon's latest record from
	 * Skyrim; without one it's decided later (when someone is inside the room).
	 */
	private static BreachStore.Entry loot(ServerLevel level, BreachStore.Entry e) {
		if (e.has(BreachStore.LOOT)) {
			return e;
		}
		BreachRoom r = e.room();
		SkyLink.Breach b = r.world() == currentWorld ? NEAR.get(r.doorRef()) : null;
		if (b == null) {
			return e;
		}
		if (b.cleared()) {
			SkyCraft.LOG.info("SkyCraft-breach: {} is cleared: no breach chest", displayName(e));
			return e.with(BreachStore.LOOT);
		}
		BlockPos pos = at(r, BreachRoom.CHEST_SLOT);
		if (!level.getBlockState(pos).isAir()) {
			return e.with(BreachStore.LOOT); // something is in the way: no chest, don't try again
		}
		String table = DungeonLoot.pickTable(RANDOM);
		ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(table == null ? "minecraft:chests/simple_dungeon" : table));
		if (level.getServer().reloadableRegistries().getLootTable(key) == LootTable.EMPTY) {
			SkyCraft.LOG.warn("SkyCraft-breach: unknown loot table '{}', using minecraft:chests/simple_dungeon", key.identifier());
			key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse("minecraft:chests/simple_dungeon"));
		}
		level.setBlock(pos, BreachBlocks.chest(Direction.from2DDataValue(r.right())), FLAGS);
		if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity chest) {
			chest.setLootTable(key, level.getRandom().nextLong()); // rolled when first opened, like a vanilla dungeon chest
			chest.setChanged();
		}
		SkyCraft.LOG.info("SkyCraft-breach: breach chest for {} at {} ({})", displayName(e), pos.toShortString(), key.identifier());
		return e.with(BreachStore.LOOT);
	}

	// ---- inside a room ------------------------------------------------------------------------

	/** Players who broke into a room: its name, once; and its chest if that wasn't decided yet. */
	private static void checkInside(ServerLevel level, List<ServerPlayer> players) {
		BreachStore s = store(level.getServer());
		if (s.size() == 0) {
			return;
		}
		List<BreachStore.Entry> rooms = new ArrayList<>();
		for (BreachStore.Entry e : s.all()) {
			if (e.room().world() == currentWorld && e.has(BreachStore.BUILT) && (!e.has(BreachStore.ENTERED) || !e.has(BreachStore.LOOT))) {
				rooms.add(e);
			}
		}
		for (BreachStore.Entry e : rooms) {
			for (ServerPlayer p : players) {
				if (p.level() != level || p.isSpectator() || !e.room().inInterior(p.getX(), p.getY() + 0.1, p.getZ())) {
					continue;
				}
				BreachStore.Entry now = loot(level, e);
				if (!now.has(BreachStore.ENTERED)) {
					now = now.with(BreachStore.ENTERED);
					p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
					p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(displayName(e) + " (breach)").withStyle(ChatFormatting.GOLD)));
					SkyCraft.LOG.info("SkyCraft-breach: {} broke into {}", p.getName().getString(), displayName(e));
				}
				if (now != e) {
					s.put(now);
					save(level.getServer(), s);
				}
				break;
			}
		}
	}

	// ---- leaks --------------------------------------------------------------------------------

	/**
	 * Underground within LEAK_RANGE of a room nobody has broken into yet: now and then the theme
	 * seeps through the rock (particles where the host's tunnel meets the rock toward the room, a
	 * sound from the room's side).
	 */
	private static void leak(ServerLevel level, ServerPlayer host) {
		if (!host.isAlive() || host.isSpectator()) {
			return;
		}
		BreachStore s = store(level.getServer());
		Vec3 eye = host.getEyePosition();
		BreachStore.Entry best = null;
		double bestDistance = LEAK_RANGE;
		for (BreachStore.Entry e : s.all()) {
			if (e.room().world() != currentWorld || e.has(BreachStore.ENTERED)) {
				continue;
			}
			double d = e.room().distanceTo(eye.x, eye.y, eye.z);
			if (d <= bestDistance) {
				bestDistance = d;
				best = e;
			}
		}
		if (best == null) {
			return;
		}
		RandomSource random = host.getRandom();
		if (random.nextDouble() > 0.25 + 0.5 * (1.0 - bestDistance / LEAK_RANGE)) {
			return;
		}
		SkyTri land = SkyDig.landAbove(eye.x, eye.y, eye.z);
		if (land == null || land.heightAt(eye.x, eye.z) - eye.y < 2.0) {
			return; // not underground
		}
		double[] q = new double[3];
		best.room().nearestPoint(eye.x, eye.y, eye.z, q);
		double dx = q[0] - eye.x, dy = q[1] - eye.y, dz = q[2] - eye.z;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double along = 0.6;
		if (length > 1.0E-3) {
			dx /= length;
			dy /= length;
			dz /= length;
			// Out along the tunnel toward the room, up to where the rock is.
			for (double step = 0.5; step <= Math.min(length, 4.0); step += 0.5) {
				BlockPos p = BlockPos.containing(eye.x + dx * step, eye.y + dy * step, eye.z + dz * step);
				if (!level.getBlockState(p).isAir() || !SkyDig.isDug(level, currentWorld, p)) {
					break;
				}
				along = step;
			}
		}
		BreachBlocks.leak(level, best.room().theme(), eye.x + dx * along, eye.y + dy * along, eye.z + dz * along, q[0], q[1], q[2], random.nextInt(5) == 0, random);
	}

	// ---- the sealed door and the walls ----------------------------------------------------------

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide() || !(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp) || player.isSpectator() || !on()
			|| !dev.skycraft.SkyDimension.inMirror(level)) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (!BreachBlocks.isDoor(state)) {
			return InteractionResult.PASS;
		}
		BreachStore.Entry e = store(serverLevel.getServer()).anyRoomAt(pos.getX(), pos.getY(), pos.getZ());
		if (e == null || e.room().kind(pos.getX(), pos.getY(), pos.getZ()) != BreachRoom.DOOR) {
			return InteractionResult.PASS;
		}
		if (hand == InteractionHand.MAIN_HAND) {
			openSeal(serverLevel, sp, pos, e);
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * The host opens a sealed door: Skyrim takes them into the dungeon. The request stays pending
	 * (further clicks ignored) until Skyrim moves them or {@link #checkPending} gives up on it.
	 */
	private static void openSeal(ServerLevel level, ServerPlayer player, BlockPos pos, BreachStore.Entry e) {
		if (!SkyNet.isHost(player)) {
			player.sendOverlayMessage(Component.literal("The seal answers only to the host's Skyrim."));
			return;
		}
		if (pending != null) {
			return; // Skyrim is (maybe) already taking them in
		}
		long now = System.nanoTime();
		if (openedOnce && now - lastOpen < OPEN_COOLDOWN_NANOS) {
			return;
		}
		if (!SkyLink.readSkyState(SKY) || !SKY.inGame() || SKY.loading() || SKY.menuOpen() || (SKY.place & Proto.PLACE_INTERIOR) != 0
			|| SKY.worldId != e.room().world()) {
			player.sendOverlayMessage(Component.literal("The seal won't move."));
			return;
		}
		lastOpen = now;
		openedOnce = true;
		String name = displayName(e);
		pending = new Pending(e.room().doorRef(), name, player.getUUID(), now, SKY.worldId, SKY.teleportSeq, SkyCollision.epoch());
		SkyLink.pushEvent(Proto.EV_ENTER_DUNGEON, e.room().doorRef(), 0.0F, 0.0F, 0.0F, 0.0F, 0);
		level.playSound(null, pos, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.6F);
		player.sendOverlayMessage(Component.literal("The seal gives way: " + name));
		SkyCraft.LOG.info("SkyCraft-breach: {} opened the sealed door of {} (door {})", player.getName().getString(), name, String.format("%08X", e.room().doorRef()));
	}

	/**
	 * Did Skyrim take the host in? Any sign of a move counts (a load screen, a new world or cell, a
	 * teleport, Skyrim clearing its world for Minecraft); none within PENDING_NANOS means Skyrim
	 * refused, and the door can be tried again.
	 */
	private static void checkPending(MinecraftServer server) {
		Pending p = pending;
		if (p == null) {
			return;
		}
		boolean moved = SkyCollision.epoch() != p.epoch();
		if (SkyLink.readSkyState(SKY)) {
			moved |= SKY.loading() || SKY.worldId != p.worldId() || SKY.teleportSeq != p.teleportSeq();
		}
		if (moved) {
			pending = null;
			SkyCraft.LOG.info("SkyCraft-breach: Skyrim is taking the player into {}", p.name());
			return;
		}
		if (System.nanoTime() - p.since() < PENDING_NANOS) {
			return;
		}
		pending = null;
		ServerPlayer player = server.getPlayerList().getPlayer(p.player());
		if (player != null) {
			player.sendOverlayMessage(Component.literal("The way in won't budge. Try again."));
		}
		SkyCraft.LOG.info("SkyCraft-breach: Skyrim didn't take the player into {} (door {}): it refused the request", p.name(), String.format("%08X", p.doorRef()));
	}

	/**
	 * Breaking a room's wall takes a good enough pickaxe for its theme, and the sealed door can't be
	 * broken at all (creative mode aside).
	 */
	private static boolean beforeBreak(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
		if (level.isClientSide() || !(level instanceof ServerLevel serverLevel) || player.isCreative() || !on() || !dev.skycraft.SkyDimension.inMirror(level)) {
			return true;
		}
		BreachStore s = store(serverLevel.getServer());
		if (s.size() == 0) {
			return true;
		}
		BreachStore.Entry e = s.anyRoomAt(pos.getX(), pos.getY(), pos.getZ());
		if (e == null) {
			return true;
		}
		BreachRoom r = e.room();
		int kind = r.kind(pos.getX(), pos.getY(), pos.getZ());
		if (kind == BreachRoom.DOOR && BreachBlocks.isDoor(state)) {
			player.sendOverlayMessage(Component.literal("The seal can't be broken. Open it."));
			return false;
		}
		if ((kind == BreachRoom.SHELL || kind == BreachRoom.FRAME) && BreachBlocks.isShellBlock(r.theme(), state) && BreachBlocks.gated(state)) {
			int tier = BreachPlanner.tier(r.theme());
			if (!BreachBlocks.pickaxeAtLeast(player.getMainHandItem(), tier)) {
				String need = BreachPlanner.pickaxeName(tier) + (tier > BreachPlanner.TIER_WOOD ? " or better" : "");
				player.sendOverlayMessage(Component.literal("This " + BreachPlanner.themeName(r.theme()) + " wall needs " + need + "."));
				return false;
			}
		}
		return true;
	}
}
