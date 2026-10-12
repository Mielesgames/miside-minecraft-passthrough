package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.skycraft.SkyCraft;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import dev.skycraft.link.SkyLink;
import org.jspecify.annotations.Nullable;

/**
 * Digging into Skyrim's world. Skyrim's ground, rocks and cave walls are geometry, not blocks; this
 * turns them into blocks one at a time, as they're dug:
 * <ul>
 * <li><b>open</b>: mining a Skyrim surface (as long as mining the block it's made of takes) takes
 * the cell the surface is in out: Skyrim's geometry in it is gone (not drawn, no collision,
 * nothing for NPCs to stand on), with the drops of that block. Nothing sticks up out of the
 * ground: the cell is empty.</li>
 * <li><b>reveal</b>: the cells around a mined one that are wholly inside Skyrim's geometry become
 * blocks (dirt under grass, stone with ores, deepslate, then bedrock 64 blocks under the land,
 * however deep the land is over them and whatever is buried in it; stone inside rocks and cave
 * walls and out past an interior's rooms; see SkyGround). Cells only partly inside keep Skyrim's
 * surface, and the part under it shows as Minecraft walls, so a hole is always closed. Until a
 * cell is revealed, an undug cell next to a dug one is solid anyway where it's under the land
 * ({@link #wallShape}).</li>
 * </ul>
 * Which cells are dug is kept per chunk (saved with the world, synced to every player) and per
 * Skyrim world (worldspace or interior cell), and sent to Skyrim. Each Skyrim world has a space of its
 * own in the mirror (SkySpace), so its dug cells and the blocks they reveal are in its space only.
 * Opening and revealing are decided by the digging player's client, which knows Skyrim's geometry
 * around them; the server just applies it.
 */
public final class SkyDig {
	private SkyDig() {
	}

	/** One section's dug cells in one Skyrim world: bit x + 16z + 256y. */
	public record DugSection(int world, int sectionY, long[] bits) {
		static final Codec<DugSection> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("world").forGetter(DugSection::world),
			Codec.INT.fieldOf("y").forGetter(DugSection::sectionY),
			Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream).fieldOf("bits").forGetter(DugSection::bits)
		).apply(i, DugSection::new));
	}

	/** A chunk's dug cells. Immutable: changes make a new one (so the attachment syncs). */
	public record DugColumn(List<DugSection> sections) {
		public static final DugColumn EMPTY = new DugColumn(List.of());
		static final Codec<DugColumn> CODEC = DugSection.CODEC.listOf().xmap(DugColumn::new, DugColumn::sections);
		static final StreamCodec<ByteBuf, DugColumn> STREAM_CODEC = new StreamCodec<>() {
			@Override
			public DugColumn decode(ByteBuf buf) {
				int n = ByteBufCodecs.VAR_INT.decode(buf);
				List<DugSection> sections = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					int world = buf.readInt();
					int y = ByteBufCodecs.VAR_INT.decode(buf);
					long[] bits = new long[64];
					for (int k = 0; k < 64; k++) {
						bits[k] = buf.readLong();
					}
					sections.add(new DugSection(world, y, bits));
				}
				return new DugColumn(List.copyOf(sections));
			}

			@Override
			public void encode(ByteBuf buf, DugColumn column) {
				ByteBufCodecs.VAR_INT.encode(buf, column.sections.size());
				for (DugSection s : column.sections) {
					buf.writeInt(s.world);
					ByteBufCodecs.VAR_INT.encode(buf, s.sectionY);
					for (int k = 0; k < 64; k++) {
						buf.writeLong(s.bits.length == 64 ? s.bits[k] : 0L);
					}
				}
			}
		};

		public long @Nullable [] bits(int world, int sectionY) {
			for (DugSection s : this.sections) {
				if (s.world == world && s.sectionY == sectionY && s.bits.length == 64) {
					return s.bits;
				}
			}
			return null;
		}

		public boolean isDug(int world, int x, int y, int z) {
			long[] bits = bits(world, y >> 4);
			int bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
			return bits != null && ((bits[bit >> 6] >>> (bit & 63)) & 1L) != 0;
		}

		DugColumn with(int world, int x, int y, int z) {
			int sy = y >> 4;
			int bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
			List<DugSection> out = new ArrayList<>(this.sections.size() + 1);
			boolean found = false;
			for (DugSection s : this.sections) {
				if (s.world == world && s.sectionY == sy && s.bits.length == 64) {
					long[] bits = s.bits.clone();
					bits[bit >> 6] |= 1L << (bit & 63);
					out.add(new DugSection(world, sy, bits));
					found = true;
				} else {
					out.add(s);
				}
			}
			if (!found) {
				long[] bits = new long[64];
				bits[bit >> 6] |= 1L << (bit & 63);
				out.add(new DugSection(world, sy, bits));
			}
			return new DugColumn(List.copyOf(out));
		}

		/** This column without one Skyrim world's sections. */
		public DugColumn without(int world) {
			List<DugSection> out = new ArrayList<>(this.sections.size());
			for (DugSection s : this.sections) {
				if (s.world != world) {
					out.add(s);
				}
			}
			return out.size() == this.sections.size() ? this : new DugColumn(List.copyOf(out));
		}

		/** This column with a section's cells added (to its world's section at that height, if any). */
		public DugColumn merged(DugSection add) {
			List<DugSection> out = new ArrayList<>(this.sections.size() + 1);
			boolean found = false;
			for (DugSection s : this.sections) {
				if (s.world == add.world && s.sectionY == add.sectionY && s.bits.length == 64 && add.bits.length == 64) {
					long[] bits = s.bits.clone();
					for (int k = 0; k < 64; k++) {
						bits[k] |= add.bits[k];
					}
					out.add(new DugSection(s.world, s.sectionY, bits));
					found = true;
				} else {
					out.add(s);
				}
			}
			if (!found) {
				out.add(add);
			}
			return new DugColumn(List.copyOf(out));
		}
	}

	public static final AttachmentType<DugColumn> DUG = AttachmentRegistry.<DugColumn>builder()
		.persistent(DugColumn.CODEC)
		.syncWith(DugColumn.STREAM_CODEC, AttachmentSyncPredicate.all())
		.buildAndRegister(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dug"));

	/** Loads the class (registers the attachment) at mod start. */
	public static void init() {
		SkyCraft.LOG.info("SkyCraft: digging into Skyrim registered ({})", DUG.identifier());
	}

	public static DugColumn column(LevelChunk chunk) {
		DugColumn column = chunk.getAttached(DUG);
		return column != null ? column : DugColumn.EMPTY;
	}

	/** Replaces a chunk's dug cells (none: the attachment goes). Synced and saved like any dig. */
	public static void setColumn(LevelChunk chunk, DugColumn column) {
		chunk.setAttached(DUG, column.sections().isEmpty() ? null : column);
	}

	public static boolean isDug(Level level, int world, BlockPos pos) {
		return column(level.getChunkAt(pos)).isDug(world, pos.getX(), pos.getY(), pos.getZ());
	}

	// ---- server --------------------------------------------------------------------------------

	private static final double REACH = 8.0;

	private static boolean inReach(ServerPlayer player, BlockPos pos, double reach) {
		return player.getEyePosition().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= reach * reach;
	}

	/**
	 * A player mined Skyrim's geometry out of this cell (the cell the surface is in): it's gone,
	 * with the drops and the sound of mining what it was made of.
	 */
	public static void open(ServerPlayer player, int world, BlockPos pos, int material) {
		ServerLevel level = player.level();
		if (!destruction || !inReach(player, pos, REACH) || !level.isLoaded(pos) || player.isSpectator()) {
			return;
		}
		if (dev.skycraft.breach.SkyBreach.claim(level, world, pos)) {
			return; // a breach antechamber's cell (built whole the first time it's reached)
		}
		LevelChunk chunk = level.getChunkAt(pos);
		DugColumn column = column(chunk);
		if (column.isDug(world, pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		chunk.setAttached(DUG, column.with(world, pos.getX(), pos.getY(), pos.getZ()));
		BlockState state = materialState(material);
		if (!player.isCreative()) {
			ItemStack tool = player.getMainHandItem();
			ItemStack used = tool.copy();
			boolean harvest = player.hasCorrectToolForDrops(state);
			tool.mineBlock(level, state, pos, player);
			if (harvest) {
				state.getBlock().playerDestroy(level, player, pos, state, null, used);
			}
		}
		level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(state));
	}

	/** Cells around a mined one that are wholly inside Skyrim's geometry: blocks now. */
	public static void reveal(ServerPlayer player, int world, List<BlockPos> cells, int[] materials) {
		if (!destruction) {
			return;
		}
		ServerLevel level = player.level();
		for (int i = 0; i < cells.size() && i < materials.length; i++) {
			BlockPos pos = cells.get(i);
			if (inReach(player, pos, REACH + 4.0) && level.isLoaded(pos)) {
				digCell(level, world, pos, materials[i]);
			}
		}
	}

	/**
	 * Whether Minecraft digs into Skyrim at all: the pause menu's "Skyrim destruction" button, saved
	 * in config/skycraft.properties. Off, mining Skyrim's surfaces and explosions leave it alone (and
	 * breaking blocks in old holes digs no further); holes already dug stay. In a friend's world it's
	 * the host's setting that counts (their server does the digging).
	 */
	public static volatile boolean destruction = true;

	/** Marks a cell dug out of Skyrim's geometry (in that Skyrim world). Returns false if it was already. */
	public static boolean markDug(ServerLevel level, int world, BlockPos pos) {
		LevelChunk chunk = level.getChunkAt(pos);
		DugColumn column = column(chunk);
		if (column.isDug(world, pos.getX(), pos.getY(), pos.getZ())) {
			return false;
		}
		chunk.setAttached(DUG, column.with(world, pos.getX(), pos.getY(), pos.getZ()));
		return true;
	}

	/**
	 * Marks many cells dug at once, with one change per chunk (so one sync each): a whole breach
	 * antechamber. Returns the cells that weren't dug before.
	 */
	public static List<BlockPos> markDug(ServerLevel level, int world, List<BlockPos> cells) {
		java.util.Map<Long, List<BlockPos>> byChunk = new java.util.LinkedHashMap<>();
		for (BlockPos pos : cells) {
			long key = ((long) (pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
			byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(pos);
		}
		List<BlockPos> fresh = new ArrayList<>();
		for (List<BlockPos> list : byChunk.values()) {
			LevelChunk chunk = level.getChunkAt(list.getFirst());
			DugColumn before = column(chunk);
			DugColumn column = before;
			for (BlockPos pos : list) {
				if (!column.isDug(world, pos.getX(), pos.getY(), pos.getZ())) {
					column = column.with(world, pos.getX(), pos.getY(), pos.getZ());
					fresh.add(pos);
				}
			}
			if (column != before) {
				chunk.setAttached(DUG, column);
			}
		}
		return fresh;
	}

	/** A cell wholly inside Skyrim's geometry: dug out of it, and the block it's made of put there. */
	public static void digCell(ServerLevel level, int world, BlockPos pos, int material) {
		if (dev.skycraft.breach.SkyBreach.claim(level, world, pos)) {
			return; // a breach antechamber's cell (built whole the first time it's reached)
		}
		if (material > 0 && occupied(level, pos)) {
			return; // someone is in it, so it's open whatever Skyrim's geometry says: never bury anyone
		}
		if (!markDug(level, world, pos)) {
			return;
		}
		BlockState here = level.getBlockState(pos);
		// Keep whatever Minecraft block is already there (someone built into the ground).
		if (material > 0 && (here.isAir() || here.canBeReplaced())) {
			level.setBlock(pos, blockFor(level, pos, material), 3);
		}
	}

	/** Whether a player (not a spectator) is in this cell. */
	private static boolean occupied(ServerLevel level, BlockPos pos) {
		AABB cell = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
		for (ServerPlayer p : level.players()) {
			if (!p.isSpectator() && p.getBoundingBox().intersects(cell)) {
				return true;
			}
		}
		return false;
	}

	/** The block a Proto.DIG_* material stands for (stone without ores). */
	public static BlockState materialState(int material) {
		return switch (base(material)) {
			case DIG_GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
			case DIG_DIRT -> Blocks.DIRT.defaultBlockState();
			case DIG_COBBLE -> Blocks.COBBLESTONE.defaultBlockState();
			case DIG_SNOW -> Blocks.SNOW_BLOCK.defaultBlockState();
			case DIG_ICE -> Blocks.PACKED_ICE.defaultBlockState();
			case DIG_SAND -> Blocks.SAND.defaultBlockState();
			case DIG_GRAVEL -> Blocks.GRAVEL.defaultBlockState();
			case DIG_MUD -> Blocks.MUD.defaultBlockState();
			case DIG_OAK_LOG -> Blocks.OAK_LOG.defaultBlockState();
			case DIG_SPRUCE_LOG -> Blocks.SPRUCE_LOG.defaultBlockState();
			case DIG_BIRCH_LOG -> Blocks.BIRCH_LOG.defaultBlockState();
			case DIG_PLANKS -> Blocks.SPRUCE_PLANKS.defaultBlockState();
			case DIG_METAL -> Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState();
			case DIG_GLASS -> Blocks.GLASS.defaultBlockState();
			case DIG_ORGANIC -> Blocks.MOSS_BLOCK.defaultBlockState();
			case DIG_CLOTH -> Blocks.WOOL.pick(DyeColor.BROWN).defaultBlockState();
			case DIG_BONE -> Blocks.BONE_BLOCK.defaultBlockState();
			case DIG_WEB -> Blocks.COBWEB.defaultBlockState();
			case DIG_ASH -> Blocks.CONCRETE_POWDER.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
			case DIG_BEDROCK -> Blocks.BEDROCK.defaultBlockState();
			default -> Blocks.STONE.defaultBlockState();
		};
	}

	/** The block a Proto.DIG_* material becomes here. Stone has ores in it. */
	public static BlockState blockFor(ServerLevel level, BlockPos pos, int material) {
		int depthPlusOne = material >>> DEPTH_SHIFT & 0xFF;
		if (base(material) == DIG_STONE || base(material) == DIG_NONE) {
			return depthPlusOne > 0 ? oreByDepth(level.getSeed(), pos, depthPlusOne - 1) : stoneOrOre(level.getSeed(), pos);
		}
		return materialState(material);
	}

	/** Ores come in little clusters (a 2x2x2 cell picks one ore, then each block in it maybe). */
	static BlockState stoneOrOre(long seed, BlockPos pos) {
		long cell = mix(seed ^ mix(((long) (pos.getX() >> 1) * 73856093L) ^ ((long) (pos.getY() >> 1) * 19349663L) ^ ((long) (pos.getZ() >> 1) * 83492791L)));
		long block = mix(cell ^ ((pos.getX() & 1) | (pos.getY() & 1) << 1 | (pos.getZ() & 1) << 2));
		if ((block & 0xFF) >= 150) { // about 60% of a vein cell's blocks
			return Blocks.STONE.defaultBlockState();
		}
		int roll = (int) ((cell >>> 16) % 10000L);
		// Chances per vein cell, in 1/10000.
		if ((roll -= 330) < 0) {
			return Blocks.COAL_ORE.defaultBlockState();
		}
		if ((roll -= 220) < 0) {
			return Blocks.IRON_ORE.defaultBlockState();
		}
		if ((roll -= 170) < 0) {
			return Blocks.COPPER_ORE.defaultBlockState();
		}
		if ((roll -= 80) < 0) {
			return Blocks.REDSTONE_ORE.defaultBlockState();
		}
		if ((roll -= 45) < 0) {
			return Blocks.GOLD_ORE.defaultBlockState();
		}
		if ((roll -= 35) < 0) {
			return Blocks.LAPIS_ORE.defaultBlockState();
		}
		if ((roll -= 14) < 0) {
			return Blocks.DIAMOND_ORE.defaultBlockState();
		}
		if ((roll -= 5) < 0) {
			return Blocks.EMERALD_ORE.defaultBlockState();
		}
		return Blocks.STONE.defaultBlockState();
	}

	/**
	 * Stone or ore at a known depth under the land, roughly like Minecraft's own layers: coal and
	 * copper near the top, iron throughout, gold, redstone and lapis lower, diamonds deep, and
	 * deepslate (with deepslate ores) from {@link #DEEPSLATE_DEPTH} down to bedrock.
	 */
	static BlockState oreByDepth(long seed, BlockPos pos, int depth) {
		boolean deep = depth >= DEEPSLATE_DEPTH;
		long cell = mix(seed ^ mix(((long) (pos.getX() >> 1) * 73856093L) ^ ((long) (pos.getY() >> 1) * 19349663L) ^ ((long) (pos.getZ() >> 1) * 83492791L)));
		long block = mix(cell ^ ((pos.getX() & 1) | (pos.getY() & 1) << 1 | (pos.getZ() & 1) << 2));
		BlockState plain = deep ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
		if ((block & 0xFF) >= 150) { // about 60% of a vein cell's blocks
			return plain;
		}
		int[] chances = oreChances(depth); // per vein cell, in 1/10000: coal, copper, iron, gold, redstone, lapis, diamond, emerald
		BlockState[] ores = deep
			? new BlockState[] { Blocks.DEEPSLATE_COAL_ORE.defaultBlockState(), Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState(),
				Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(), Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState(),
				Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState(), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState() }
			: new BlockState[] { Blocks.COAL_ORE.defaultBlockState(), Blocks.COPPER_ORE.defaultBlockState(), Blocks.IRON_ORE.defaultBlockState(),
				Blocks.GOLD_ORE.defaultBlockState(), Blocks.REDSTONE_ORE.defaultBlockState(), Blocks.LAPIS_ORE.defaultBlockState(),
				Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.EMERALD_ORE.defaultBlockState() };
		int roll = (int) ((cell >>> 16) % 10000L);
		for (int i = 0; i < chances.length; i++) {
			if ((roll -= chances[i]) < 0) {
				return ores[i];
			}
		}
		return plain;
	}

	/** Ore chances per vein cell at a depth, in 1/10000: coal, copper, iron, gold, redstone, lapis, diamond, emerald. */
	static int[] oreChances(int depth) {
		if (depth < 8) {
			return new int[] { 450, 250, 200, 0, 0, 0, 0, 0 };
		}
		if (depth < 24) {
			return new int[] { 300, 200, 280, 40, 40, 30, 0, 5 };
		}
		if (depth < 40) {
			return new int[] { 120, 80, 260, 90, 120, 60, 25, 8 };
		}
		return new int[] { 40, 20, 200, 110, 200, 70, 70, 5 };
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	// ---- client: what's inside Skyrim's geometry (SkyGround) ------------------------------------

	/** Skyrim's surfaces as SkyGround sees them: SkyCollision's, as they were before anything was dug. */
	public static final SkyGround.Surfaces SURFACES = (minX, minY, minZ, maxX, maxY, maxZ, out) -> SkyCollision.originalSurfacesNear(
		new AABB(minX, minY, minZ, maxX, maxY, maxZ), out);

	/** Leave the cell alone: Skyrim geometry that can't be dug (a building) is there. */
	public static final int KEEP = SkyGround.KEEP;
	/** Open air, or only partly inside Skyrim's geometry (its surface stays; walls are drawn). */
	public static final int AIR = SkyGround.AIR;
	/** Blocks this far under the land's surface are bedrock. */
	public static final double BEDROCK_DEPTH = SkyGround.BEDROCK_DEPTH;
	/** Below this depth, stone is deepslate (with deepslate ores). */
	public static final double DEEPSLATE_DEPTH = SkyGround.DEEPSLATE_DEPTH;
	/** See SkyGround.DEPTH_SHIFT: how deep a stone material is, for ores. */
	public static final int DEPTH_SHIFT = SkyGround.DEPTH_SHIFT;

	/** A SkyGround probe on SkyCollision's surfaces. */
	public static final class Probe extends SkyGround.Probe {
		public Probe() {
			super(SURFACES);
		}

		@Override
		public Probe around(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			super.around(minX, minY, minZ, maxX, maxY, maxZ);
			return this;
		}
	}

	/** See SkyGround.classify: the block a cell next to a dug one becomes, AIR or KEEP. */
	public static int classify(int x, int y, int z) {
		return SkyGround.classify(SURFACES, x, y, z);
	}

	/** The Proto.DIG_* material without the depth classify() may add. */
	public static int base(int material) {
		return SkyGround.base(material);
	}

	/** The nearest stretch of land (Skyrim's terrain) above a point, within SkyGround.DEEP_REACH, or null. */
	public static @Nullable SkyTri landAbove(double x, double y, double z) {
		return SkyGround.landAbove(SURFACES, x, y, z);
	}

	/** What's this deep behind a surface of this material: grass has dirt under it, then stone. */
	public static int underground(int surface, double depth) {
		return SkyGround.underground(surface, depth);
	}

	// ---- collision for the walls of dug holes -------------------------------------------------

	private static final SkyLink.SkyState SERVER_SKY = new SkyLink.SkyState();
	private static volatile int serverWorld;
	private static volatile long serverWorldAt;
	// Worked-out walls by section (they depend only on Skyrim's surfaces, not on what's dug), shared
	// by every thread. NO_WALL stands for "none" (a map can't hold null).
	private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Long, VoxelShape>> WALLS =
		new java.util.concurrent.ConcurrentHashMap<>();
	private static final VoxelShape NO_WALL = Shapes.empty();

	/** Skyrim's world changed: every wall is worked out again. */
	public static void wallsChanged() {
		WALLS.clear();
	}

	/** Skyrim's surfaces in this box changed: the walls that could see them are worked out again. */
	public static void wallsChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		int reach = (int) Math.ceil(SkyGround.SIDE_REACH) + 1; // a cell looks this far sideways
		int down = reach + (int) Math.ceil(SkyGround.DEEP_REACH); // and this far up for the land over it
		for (int sx = (minX - reach) >> 4; sx <= (maxX + reach) >> 4; sx++) {
			for (int sy = (minY - down) >> 4; sy <= (maxY + (int) SkyGround.COLUMN_REACH) >> 4; sy++) {
				for (int sz = (minZ - reach) >> 4; sz <= (maxZ + reach) >> 4; sz++) {
					WALLS.remove(net.minecraft.core.SectionPos.asLong(sx, sy, sz));
				}
			}
		}
	}

	private static int worldFor(Level level) {
		if (level.isClientSide()) {
			DugLookup lookup = clientDug;
			return lookup != null ? clientWorld : 0;
		}
		long now = System.currentTimeMillis();
		if (now - serverWorldAt > 250) {
			synchronized (SERVER_SKY) {
				if (SkyLink.readSkyState(SERVER_SKY)) {
					serverWorld = SERVER_SKY.worldId;
				}
			}
			serverWorldAt = now;
		}
		return serverWorld;
	}

	/** Set by the client with clientDug: the Skyrim world its dug cells are for. */
	public static volatile int clientWorld;

	private static @Nullable DugColumn columnFor(net.minecraft.world.level.CollisionGetter level, int x, int z) {
		var chunk = level.getChunkForCollisions(x >> 4, z >> 4);
		return chunk instanceof LevelChunk lc ? lc.getAttached(DUG) : null;
	}

	/**
	 * The solid part of an undug cell next to a dug one: under the land's surface (the wall of a
	 * hole, drawn by the client's DigWalls), which Skyrim's own geometry (just a surface) doesn't
	 * make solid. Null for none. Any thread.
	 */
	public static @Nullable VoxelShape wallShape(net.minecraft.world.level.CollisionGetter getter, BlockPos pos) {
		if (!(getter instanceof Level level) || !SkyCollision.active()) {
			return null;
		}
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		DugColumn here = columnFor(getter, x, z);
		int world = worldFor(level);
		if (here != null && here.isDug(world, x, y, z)) {
			return null;
		}
		boolean nextToDug = false;
		for (Direction d : Direction.values()) {
			DugColumn column = d.getAxis() == Direction.Axis.Y ? here : columnFor(getter, x + d.getStepX(), z + d.getStepZ());
			if (column != null && column.isDug(world, x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) {
				nextToDug = true;
				break;
			}
		}
		if (!nextToDug) {
			return null;
		}
		if (WALLS.size() > 2048) {
			WALLS.clear();
		}
		var shapes = WALLS.computeIfAbsent(net.minecraft.core.SectionPos.asLong(x >> 4, y >> 4, z >> 4), k -> new java.util.concurrent.ConcurrentHashMap<>());
		long key = pos.asLong();
		VoxelShape cached = shapes.get(key);
		if (cached != null) {
			return cached == NO_WALL ? null : cached;
		}
		VoxelShape shape = computeWall(x, y, z);
		shapes.put(key, shape == null ? NO_WALL : shape);
		return shape;
	}

	private static @Nullable VoxelShape computeWall(int x, int y, int z) {
		Probe probe = new Probe().around(x, y, z, x + 1, y + 1, z + 1);
		// Under the land, however deep: solid up to the lowest the land gets over the cell (never
		// above the ground).
		double lowest = Double.POSITIVE_INFINITY;
		for (double[] c : new double[][] { { 0.05, 0.05 }, { 0.95, 0.05 }, { 0.05, 0.95 }, { 0.95, 0.95 }, { 0.5, 0.5 } }) {
			double h = probe.landOver(x + c[0], z + c[1]);
			if (!Double.isNaN(h)) {
				lowest = Math.min(lowest, h);
			}
		}
		if (Double.isFinite(lowest) && lowest - y > 0.05) {
			return lowest - y >= 0.999 ? Shapes.block() : Shapes.box(0, 0, 0, 1, lowest - y, 1);
		}
		// Rocks, cave walls, interiors: solid if the middle is inside (checked sideways too, so the
		// rock out past an interior's rooms holds).
		return probe.testSolid(x + 0.5, y + 0.5, z + 0.5) > AIR ? Shapes.block() : null;
	}

	/**
	 * Looks up whether a cell is dug in the client's world (set by the client; null on a server).
	 * Used by the crosshair pick to hit the walls of dug holes.
	 */
	public interface DugLookup {
		boolean isDug(int x, int y, int z);
	}

	public static volatile @Nullable DugLookup clientDug;
}
