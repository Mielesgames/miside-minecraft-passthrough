package dev.skycraft.worlds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Turns a {@link Worlds.Arrival} into a spot to stand in a loaded level. Server thread only. */
public final class WorldArrivals {
	private WorldArrivals() {}

	/** Feet position for the arrival, generating its chunk if needed. */
	public static Vec3 resolve(ServerLevel level, Worlds.Arrival arrival) {
		BlockPos column = BlockPos.containing(arrival.x(), arrival.y(), arrival.z());
		level.getChunk(column);
		return switch (arrival.placement()) {
			case EXACT -> new Vec3(arrival.x(), arrival.y(), arrival.z());
			case SURFACE -> new Vec3(arrival.x(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), arrival.z());
			case CAVERN -> new Vec3(arrival.x(), cavern(level, column), arrival.z());
		};
	}

	/** Nearest y at or below, then above, the start with two open blocks over a sturdy floor. */
	private static int cavern(ServerLevel level, BlockPos start) {
		int minY = level.getMinY() + 1;
		int maxY = level.getMaxY() - 2;
		for (int y = Math.min(start.getY(), maxY); y >= minY; y--) {
			if (standable(level, start.atY(y))) {
				return y;
			}
		}
		for (int y = start.getY() + 1; y <= maxY; y++) {
			if (standable(level, start.atY(y))) {
				return y;
			}
		}
		return start.getY();
	}

	private static boolean standable(ServerLevel level, BlockPos feet) {
		BlockPos floor = feet.below();
		return level.getBlockState(feet).isAir()
			&& level.getBlockState(feet.above()).isAir()
			&& level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
	}
}
