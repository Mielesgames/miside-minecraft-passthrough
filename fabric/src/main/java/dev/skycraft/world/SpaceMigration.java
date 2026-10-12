package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkySpace;
import dev.skycraft.link.WorldSpaces;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Each Skyrim world's blocks in a space of its own (SkySpace), for a Minecraft world made before that,
 * when all of Skyrim's worlds shared the mirror's coordinates round the origin. Runs once, when the
 * host's world starts (then the space table says {@code migrated}):
 * <ul>
 * <li>Tamriel's space is the origin: everything built and dug outside in Tamriel stays as it is.</li>
 * <li>Every other Skyrim world's dug cells (kept per world in each chunk) move into its space, with the
 * blocks in them: the stone revealed round its holes, and anything put into them.</li>
 * <li>So does whatever stands against them: blocks joined to them face to face, out to
 * {@link #BUILD_REACH} blocks, unless that build also touches another world's dug cells (then nobody
 * can tell whose it is, and it stays).</li>
 * <li>Breach antechambers of worldspaces other than Tamriel move too (SkyBreach.moveIntoSpaces).</li>
 * <li>Blocks nothing ties to a world (say a house on an interior's floor, never dug round) stay where
 * they are: in Tamriel's space, over where that interior's rooms were.</li>
 * </ul>
 * A cell dug in two worlds keeps its block where it is and the other world gets a copy. Block entities
 * (chests, furnaces) go with their contents; mobs and dropped items don't. The world is saved before
 * the table marks it migrated, so a crash part way just means it runs again.
 */
public final class SpaceMigration {
	/** How far from a world's dug cells a build standing against them still counts as that world's. */
	static final int BUILD_REACH = 12;
	/** The most blocks of builds moved per world (a safety stop). */
	static final int BUILD_LIMIT = 50_000;
	private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	/** Place moved blocks without anything reacting (no falling sand, no drops, no neighbour updates). */
	private static final int PLACE = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	private SpaceMigration() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(SpaceMigration::started);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SkySpace.close());
	}

	private static void started(MinecraftServer server) {
		WorldSpaces table = SkySpace.open(server.getWorldPath(LevelResource.ROOT));
		// Guests hear of every new space (SkyNet).
		SkySpace.setOnChange(() -> server.execute(() -> dev.skycraft.net.SkyNet.sendSpaces(server)));
		if (table.migrated() >= WorldSpaces.MIGRATION) {
			return;
		}
		ServerLevel mirror = server.overworld();
		if (!dev.skycraft.SkyDimension.inMirror(mirror)) {
			return; // not a SkyCraft world
		}
		long start = System.nanoTime();
		try {
			int moved = migrate(server, mirror);
			moved += dev.skycraft.breach.SkyBreach.moveIntoSpaces(server);
			if (moved > 0) {
				server.saveAllChunks(true, true, true); // before the table says it's done
			}
			table.setMigrated(WorldSpaces.MIGRATION);
			SkySpace.save();
			SkyCraft.LOG.info("SkyCraft-space: this world's Skyrim worlds are in their own spaces now ({} ms)", (System.nanoTime() - start) / 1_000_000);
		} catch (RuntimeException | IOException e) {
			SkyCraft.LOG.error("SkyCraft-space: couldn't sort this world's blocks into their Skyrim worlds' spaces; trying again next time", e);
		}
	}

	/** Returns how many dug cells and blocks moved. */
	private static int migrate(MinecraftServer server, ServerLevel level) throws IOException {
		// 1. Which chunks of the shared space hold another Skyrim world's dug cells (read from disk, not loaded).
		Map<Long, TreeSet<Integer>> chunks = scan(server, level);
		if (chunks.isEmpty()) {
			SkyCraft.LOG.info("SkyCraft-space: nothing dug outside Tamriel before spaces: nothing to move");
			return 0;
		}
		// 2. Their cells, per world; then each world's blocks go (before the cells: whose a block is
		// depends on which worlds dug round it).
		Map<Integer, LongOpenHashSet> cells = new TreeMap<>();
		Map<Long, LevelChunk> loaded = new java.util.HashMap<>();
		for (long key : chunks.keySet()) {
			LevelChunk chunk = level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
			loaded.put(key, chunk);
			for (SkyDig.DugSection s : SkyDig.column(chunk).sections()) {
				if (s.world() == WorldSpaces.TAMRIEL || s.world() == 0 || s.bits().length != 64) {
					continue;
				}
				LongOpenHashSet set = cells.computeIfAbsent(s.world(), w -> new LongOpenHashSet());
				forEachCell(chunk.getPos(), s, set::add);
			}
		}
		int moved = 0;
		for (Map.Entry<Integer, LongOpenHashSet> e : cells.entrySet()) {
			moved += moveWorld(level, e.getKey(), e.getValue());
		}
		// 3. The dug cells themselves, chunk by chunk (whole sections: spaces are a whole number of chunks apart).
		for (LevelChunk chunk : loaded.values()) {
			SkyDig.DugColumn column = SkyDig.column(chunk);
			SkyDig.DugColumn kept = column;
			for (SkyDig.DugSection s : column.sections()) {
				if (s.world() == WorldSpaces.TAMRIEL || s.world() == 0) {
					continue;
				}
				int dx = SkySpace.originX(s.world()) >> 4, dz = SkySpace.originZ(s.world()) >> 4;
				LevelChunk target = level.getChunk(chunk.getPos().x() + dx, chunk.getPos().z() + dz);
				SkyDig.setColumn(target, SkyDig.column(target).merged(s));
				kept = kept.without(s.world());
			}
			if (kept != column) {
				SkyDig.setColumn(chunk, kept);
			}
		}
		for (Map.Entry<Integer, LongOpenHashSet> e : cells.entrySet()) {
			moved += e.getValue().size();
		}
		return moved;
	}

	/**
	 * Moves the blocks of one Skyrim world (its dug cells' blocks, and builds standing against them) from
	 * the shared space into its own. Returns how many blocks moved.
	 */
	private static int moveWorld(ServerLevel level, int world, LongOpenHashSet dug) {
		int space = SkySpace.space(world, null);
		int dx = WorldSpaces.originX(space), dz = WorldSpaces.originZ(space);
		LongOpenHashSet take = new LongOpenHashSet();
		LongOpenHashSet copy = new LongOpenHashSet();
		for (long cell : dug) {
			BlockPos pos = BlockPos.of(cell);
			if (level.getBlockState(pos).isAir()) {
				continue;
			}
			(dugElsewhere(level, world, pos) ? copy : take).add(cell);
		}
		int builds = collectBuilds(level, world, dug, take);
		int blocks = 0;
		for (LongOpenHashSet set : List.of(take, copy)) {
			boolean keep = set == copy;
			for (long cell : set) {
				if (moveBlock(level, BlockPos.of(cell), dx, dz, keep)) {
					blocks++;
				}
			}
		}
		SkyCraft.LOG.info("SkyCraft-space: Skyrim world {} ({} dug cells) moved into space {} at {} {}: {} blocks ({} of them builds standing against its holes{})",
			String.format("%08X", world), dug.size(), space, dx, dz, blocks, builds, copy.isEmpty() ? "" : ", " + copy.size() + " copied (dug in another world too)");
		return blocks;
	}

	/**
	 * Adds to {@code take} the blocks joined (face to face) to the world's dug cells that are no world's
	 * dug cells themselves, out to BUILD_REACH: a build put up in that world against its holes. A group
	 * that reaches another world's dug cells is left alone. Returns how many were added.
	 */
	private static int collectBuilds(ServerLevel level, int world, LongOpenHashSet dug, LongOpenHashSet take) {
		LongOpenHashSet seen = new LongOpenHashSet();
		int added = 0;
		for (long cell : dug) {
			for (Direction d : Direction.values()) {
				long start = BlockPos.offset(cell, d);
				if (dug.contains(start) || seen.contains(start) || !isBuild(level, start)) {
					continue;
				}
				// One group of joined blocks, from here.
				LongOpenHashSet group = new LongOpenHashSet();
				Long2IntOpenHashMap depth = new Long2IntOpenHashMap();
				LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
				boolean theirs = false;
				queue.enqueue(start);
				depth.put(start, 1);
				seen.add(start);
				while (!queue.isEmpty() && !theirs) {
					long p = queue.dequeueLong();
					group.add(p);
					int dd = depth.get(p);
					for (Direction n : Direction.values()) {
						long q = BlockPos.offset(p, n);
						if (dug.contains(q) || seen.contains(q)) {
							continue;
						}
						if (dugElsewhere(level, world, BlockPos.of(q))) {
							theirs = true; // it's against another world's holes too: can't tell whose
							break;
						}
						if (dd >= BUILD_REACH || !isBuild(level, q)) {
							continue;
						}
						seen.add(q);
						depth.put(q, dd + 1);
						queue.enqueue(q);
					}
				}
				if (theirs) {
					continue;
				}
				if (take.size() + group.size() > BUILD_LIMIT + dug.size()) {
					SkyCraft.LOG.warn("SkyCraft-space: Skyrim world {}: more than {} blocks stand against its holes; the rest stay put",
						String.format("%08X", world), BUILD_LIMIT);
					return added;
				}
				take.addAll(group);
				added += group.size();
			}
		}
		return added;
	}

	/** A Minecraft block (not air) in a cell no Skyrim world dug. */
	private static boolean isBuild(ServerLevel level, long cell) {
		BlockPos pos = BlockPos.of(cell);
		if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) {
			return false;
		}
		LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		return !chunk.getBlockState(pos).isAir() && !anyDug(chunk, pos);
	}

	/** Whether any Skyrim world dug this cell. */
	private static boolean anyDug(LevelChunk chunk, BlockPos pos) {
		int bit = (pos.getX() & 15) + 16 * (pos.getZ() & 15) + 256 * (pos.getY() & 15);
		for (SkyDig.DugSection s : SkyDig.column(chunk).sections()) {
			if (s.sectionY() == pos.getY() >> 4 && s.bits().length == 64 && ((s.bits()[bit >> 6] >>> (bit & 63)) & 1L) != 0) {
				return true;
			}
		}
		return false;
	}

	/** Whether a Skyrim world other than {@code world} dug this cell (Tamriel among them). */
	private static boolean dugElsewhere(ServerLevel level, int world, BlockPos pos) {
		LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		int bit = (pos.getX() & 15) + 16 * (pos.getZ() & 15) + 256 * (pos.getY() & 15);
		for (SkyDig.DugSection s : SkyDig.column(chunk).sections()) {
			if (s.world() != world && s.sectionY() == pos.getY() >> 4 && s.bits().length == 64 && ((s.bits()[bit >> 6] >>> (bit & 63)) & 1L) != 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The block at {@code pos} (and its block entity) to the same place in the world's space, unless
	 * something is there already. {@code keep}: leave the original too. Returns whether it moved.
	 */
	private static boolean moveBlock(ServerLevel level, BlockPos pos, int dx, int dz, boolean keep) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}
		BlockPos to = pos.offset(dx, 0, dz);
		BlockState there = level.getBlockState(to);
		if (!there.isAir() && !there.canBeReplaced()) {
			return false;
		}
		BlockEntity entity = level.getBlockEntity(pos);
		CompoundTag data = entity != null ? entity.saveWithFullMetadata(level.registryAccess()) : null;
		level.setBlock(to, state, PLACE);
		if (data != null) {
			BlockEntity copy = BlockEntity.loadStatic(to, state, data, level.registryAccess());
			if (copy != null) {
				level.setBlockEntity(copy);
			}
		}
		if (!keep) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE); // its block entity goes without dropping anything
		}
		return true;
	}

	private interface CellSink {
		void accept(long cell);
	}

	private static void forEachCell(ChunkPos chunk, SkyDig.DugSection s, CellSink sink) {
		int x0 = chunk.getMinBlockX(), z0 = chunk.getMinBlockZ(), y0 = s.sectionY() << 4;
		for (int bit = 0; bit < 4096; bit++) {
			if (((s.bits()[bit >> 6] >>> (bit & 63)) & 1L) != 0) {
				sink.accept(BlockPos.asLong(x0 + (bit & 15), y0 + (bit >> 8), z0 + ((bit >> 4) & 15)));
			}
		}
	}

	// ---- finding the chunks ------------------------------------------------------------------------

	/**
	 * Every saved chunk of the shared space (the region files round the origin) whose dug cells include a
	 * Skyrim world other than Tamriel: chunk key -> those worlds.
	 */
	private static Map<Long, TreeSet<Integer>> scan(MinecraftServer server, ServerLevel level) throws IOException {
		Path dir = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
		Map<Long, TreeSet<Integer>> out = new TreeMap<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		String attachments = AttachmentTarget.NBT_ATTACHMENT_KEY;
		String dugKey = SkyDig.DUG.identifier().toString();
		int regionsLimit = Math.floorDiv(WorldSpaces.HALF, 512);
		List<ChunkPos> positions = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*.mca")) {
			for (Path file : files) {
				Matcher m = REGION.matcher(file.getFileName().toString());
				if (!m.matches()) {
					continue;
				}
				int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
				if (rx < -regionsLimit || rx >= regionsLimit || rz < -regionsLimit || rz >= regionsLimit) {
					continue; // outside the shared space: made since spaces (or a test area)
				}
				byte[] header = new byte[4096];
				try (InputStream in = Files.newInputStream(file)) {
					if (in.readNBytes(header, 0, 4096) < 4096) {
						continue;
					}
				}
				for (int i = 0; i < 1024; i++) {
					if (header[i * 4] != 0 || header[i * 4 + 1] != 0 || header[i * 4 + 2] != 0) {
						positions.add(new ChunkPos(rx * 32 + (i & 31), rz * 32 + (i >> 5)));
					}
				}
			}
		}
		// Read them through Minecraft's own storage (it knows unsaved changes and every compression), a batch at a time.
		int batch = 512;
		for (int i = 0; i < positions.size(); i += batch) {
			List<ChunkPos> part = positions.subList(i, Math.min(positions.size(), i + batch));
			List<CompletableFuture<Optional<CompoundTag>>> reads = new ArrayList<>(part.size());
			for (ChunkPos pos : part) {
				reads.add(level.getChunkSource().chunkMap.read(pos));
			}
			for (int k = 0; k < part.size(); k++) {
				Optional<CompoundTag> tag;
				try {
					tag = reads.get(k).join();
				} catch (RuntimeException e) {
					SkyCraft.LOG.warn("SkyCraft-space: couldn't read chunk {}: {}", part.get(k), e.toString());
					continue;
				}
				TreeSet<Integer> worlds = otherWorlds(tag.flatMap(t -> t.getCompound(attachments)).flatMap(a -> a.getList(dugKey)));
				if (!worlds.isEmpty()) {
					out.put(part.get(k).pack(), worlds);
				}
			}
		}
		SkyCraft.LOG.info("SkyCraft-space: looked through {} saved chunks round the origin: {} hold other Skyrim worlds' dug cells {}", positions.size(), out.size(),
			out.values().stream().flatMap(TreeSet::stream).distinct().map(w -> String.format("%08X", w)).toList());
		return out;
	}

	/** The Skyrim worlds other than Tamriel (and 0) in a saved chunk's dug sections. */
	private static TreeSet<Integer> otherWorlds(Optional<ListTag> sections) {
		TreeSet<Integer> worlds = new TreeSet<>();
		if (sections.isEmpty()) {
			return worlds;
		}
		ListTag list = sections.get();
		for (int i = 0; i < list.size(); i++) {
			int world = list.getCompoundOrEmpty(i).getIntOr("world", 0);
			if (world != 0 && world != WorldSpaces.TAMRIEL) {
				worlds.add(world);
			}
		}
		return worlds;
	}
}
