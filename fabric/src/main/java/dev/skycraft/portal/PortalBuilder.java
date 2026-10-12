package dev.skycraft.portal;

import dev.skycraft.SkyCraft;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import org.jspecify.annotations.Nullable;

/**
 * Builds the network's own portals into a world (a key's partner portal at a world's arrival point, the
 * crack's portal at the ending) and makes arrival spots safe. Server thread only.
 */
final class PortalBuilder {
	/** The Nether arrival is searched for this far round 0, 0 (blocks), on this grid. */
	private static final int NETHER_SEARCH = 32, NETHER_STEP = 4;
	private static final int NETHER_MIN_Y = 64, NETHER_MAX_Y = 100, NETHER_START_Y = 70;
	/** Where the Nether arrival is carved when no open spot is found. */
	private static final int NETHER_CARVE_Y = 80;

	private PortalBuilder() {
	}

	/** What fills sealed off lava or water: the world's own stone. */
	private static BlockState filler(ServerLevel level) {
		return level.dimension() == Level.NETHER ? Blocks.NETHERRACK.defaultBlockState() : Blocks.STONE.defaultBlockState();
	}

	private static BlockPos pos(int[] p) {
		return new BlockPos(p[0], p[1], p[2]);
	}

	/** Loads (generating if needed) every chunk a box touches. */
	private static void load(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				level.getChunk(cx, cz);
			}
		}
	}

	/** Lava, water, fire or magma: what a traveller mustn't arrive next to. */
	private static boolean hazard(BlockState s) {
		return !s.getFluidState().isEmpty() || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.MAGMA_BLOCK);
	}

	/** Whether a plan's spot has someone else's portal in it (lit portal blocks where ours would go). */
	static boolean occupied(ServerLevel level, PortalPlan plan) {
		int[] b = plan.bounds();
		load(level, b[0], b[2], b[3], b[5]);
		for (List<int[]> part : List.of(plan.portal(), plan.frame(), plan.clear())) {
			for (int[] p : part) {
				if (level.getBlockState(pos(p)).is(Blocks.NETHER_PORTAL)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Builds a plan: seals lava, water and fire round it, clears the apron, floors it, puts up the obsidian
	 * frame and lights it the way flint and steel would (PortalShape). Returns the lit rectangle, or null if
	 * the portal wouldn't light.
	 */
	static SkyPortals.@Nullable Shape build(ServerLevel level, PortalPlan plan) {
		int[] b = plan.bounds();
		load(level, b[0], b[2], b[3], b[5]);
		BlockState fill = filler(level);
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
		for (int[] p : plan.shell()) {
			BlockPos at = pos(p);
			if (hazard(level.getBlockState(at))) {
				level.setBlock(at, fill, Block.UPDATE_ALL);
			}
		}
		for (int[] p : plan.clear()) {
			level.setBlock(pos(p), air, Block.UPDATE_ALL);
		}
		for (int[] p : plan.portal()) {
			level.setBlock(pos(p), air, Block.UPDATE_ALL);
		}
		for (int[] p : plan.floor()) {
			BlockPos at = pos(p);
			BlockState s = level.getBlockState(at);
			if (hazard(s) || !s.isFaceSturdy(level, at, Direction.UP)) {
				level.setBlock(at, obsidian, Block.UPDATE_ALL);
			}
		}
		for (int[] p : plan.frame()) {
			level.setBlock(pos(p), obsidian, Block.UPDATE_ALL);
		}
		BlockPos inside = pos(plan.at(0, 0, 0));
		Direction.Axis axis = plan.axis() == PortalStore.AXIS_X ? Direction.Axis.X : Direction.Axis.Z;
		Optional<PortalShape> shape = PortalShape.findEmptyPortalShape(level, inside, axis);
		if (shape.isEmpty()) {
			SkyCraft.LOG.warn("SkyCraft-portal: the frame built at {} in {} won't light", inside.toShortString(), level.dimension().identifier());
			return null;
		}
		shape.get().createPortalBlocks(level);
		return SkyPortals.shapeAt(level, inside);
	}

	/**
	 * Makes an arrival spot safe to stand on, unless it is already (solid ground under two blocks of air,
	 * nothing hot or wet round it): a 3 by 3 floor, air three high above it, lava, water and fire round it
	 * sealed off. Portals and their obsidian (a partner standing behind the spot) are left alone. For
	 * arrivals with no portal of their own (the Seamstone onto a world's arrival point).
	 */
	static void pad(ServerLevel level, int bx, int by, int bz) {
		load(level, bx - 2, bz - 2, bx + 2, bz + 2);
		if (standable(level, bx, by, bz, 1)) {
			return;
		}
		BlockState fill = filler(level);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -2; dy <= 3; dy++) {
					BlockPos at = new BlockPos(bx + dx, by + dy, bz + dz);
					BlockState s = level.getBlockState(at);
					if (s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.OBSIDIAN)) {
						continue;
					}
					boolean inner = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
					if (inner && dy >= 0 && dy <= 2) {
						if (!s.isAir()) {
							level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
						}
					} else if (inner && dy == -1) {
						if (hazard(s) || !s.isFaceSturdy(level, at, Direction.UP)) {
							level.setBlock(at, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
						}
					} else if (hazard(s)) {
						level.setBlock(at, fill, Block.UPDATE_ALL);
					}
				}
			}
		}
	}

	/**
	 * The Nether's arrival point: the nearest open spot round 0, 0 between y 64 and 100 with solid ground, two
	 * blocks of air and no lava, fire or magma within two blocks; else a pocket carved at y 80. Feet
	 * {x, y, z} and a yaw (south). Found once; SkyPortals records it in the network.
	 */
	static double[] netherArrival(ServerLevel nether) {
		for (int r = 0; r <= NETHER_SEARCH; r += NETHER_STEP) {
			for (int dx = -r; dx <= r; dx += NETHER_STEP) {
				for (int dz = -r; dz <= r; dz += NETHER_STEP) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue; // this ring only
					}
					load(nether, dx - 2, dz - 2, dx + 2, dz + 2);
					for (int y = NETHER_START_Y; y >= NETHER_MIN_Y; y--) {
						if (safe(nether, dx, y, dz)) {
							return new double[] { dx + 0.5, y, dz + 0.5, 0.0 };
						}
					}
					for (int y = NETHER_START_Y + 1; y <= NETHER_MAX_Y; y++) {
						if (safe(nether, dx, y, dz)) {
							return new double[] { dx + 0.5, y, dz + 0.5, 0.0 };
						}
					}
				}
			}
		}
		SkyCraft.LOG.info("SkyCraft-portal: no open ground near the Nether's middle between y {} and {}; carving a pocket at 0 {} 0", NETHER_MIN_Y, NETHER_MAX_Y,
			NETHER_CARVE_Y);
		pad(nether, 0, NETHER_CARVE_Y, 0);
		return new double[] { 0.5, NETHER_CARVE_Y, 0.5, 0.0 };
	}

	/** Solid ground under three blocks of air, and nothing hot or wet within two blocks. */
	private static boolean safe(ServerLevel level, int x, int y, int z) {
		return standable(level, x, y, z, 2) && level.getBlockState(new BlockPos(x, y + 2, z)).isAir();
	}

	/** Solid ground under two blocks of air, and nothing hot or wet within {@code reach} blocks. */
	private static boolean standable(ServerLevel level, int x, int y, int z, int reach) {
		BlockPos floor = new BlockPos(x, y - 1, z);
		BlockState f = level.getBlockState(floor);
		if (hazard(f) || !f.isFaceSturdy(level, floor, Direction.UP) || !level.getBlockState(new BlockPos(x, y, z)).isAir()
			|| !level.getBlockState(new BlockPos(x, y + 1, z)).isAir()) {
			return false;
		}
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				for (int dy = -1; dy <= 3; dy++) {
					if (hazard(level.getBlockState(new BlockPos(x + dx, y + dy, z + dz)))) {
						return false;
					}
				}
			}
		}
		return true;
	}
}
