package dev.skycraft.breach;

import static dev.skycraft.link.Proto.THEME_DWEMER;
import static dev.skycraft.link.Proto.THEME_FALMER;
import static dev.skycraft.link.Proto.THEME_FORT;
import static dev.skycraft.link.Proto.THEME_LAIR;
import static dev.skycraft.link.Proto.THEME_MINE;
import static dev.skycraft.link.Proto.THEME_NORDIC;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

/**
 * The Minecraft blocks, effects and tool checks for each breach theme (BreachPlanner decides where
 * they go). Server thread.
 */
final class BreachBlocks {
	private BreachBlocks() {
	}

	/** A theme's shell blocks: main, alt, rare, accent (BreachPlanner.MAIN ... ACCENT). */
	static Block[] palette(int theme) {
		return switch (theme) {
			case THEME_NORDIC -> new Block[] { Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS };
			case THEME_DWEMER -> new Block[] { Blocks.CUT_COPPER.waxed().unaffected(), Blocks.COPPER_BLOCK.waxed().unaffected(), Blocks.COPPER_GRATE.waxed().unaffected(),
				Blocks.CHISELED_COPPER.waxed().unaffected() };
			case THEME_FALMER -> new Block[] { Blocks.TUFF_BRICKS, Blocks.TUFF, Blocks.CHISELED_TUFF, Blocks.CHISELED_TUFF_BRICKS };
			case THEME_MINE -> new Block[] { Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.MOSSY_COBBLESTONE, Blocks.OAK_LOG };
			case THEME_FORT -> new Block[] { Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS };
			case THEME_LAIR -> new Block[] { Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CHISELED_POLISHED_BLACKSTONE };
			default -> new Block[] { Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE, Blocks.STONE, Blocks.MOSSY_STONE_BRICKS };
		};
	}

	/** The shell block for a cell of the room (its frame and walls; never the door). */
	static BlockState shell(BreachRoom room, int x, int y, int z) {
		Block[] blocks = palette(room.theme());
		return blocks[Math.clamp(room.variant(x, y, z), 0, blocks.length - 1)].defaultBlockState();
	}

	/** Whether this block is one of the theme's shell blocks (so it's the room's own wall). */
	static boolean isShellBlock(int theme, BlockState state) {
		for (Block b : palette(theme)) {
			if (state.is(b)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the tool is a pickaxe at least this good: a pickaxe (vanilla tag) that is the right
	 * tool for a block needing that tier (iron ore needs stone, diamond ore iron, obsidian diamond).
	 */
	static boolean pickaxeAtLeast(ItemStack tool, int tier) {
		if (tool.isEmpty() || !tool.is(ItemTags.PICKAXES)) {
			return false;
		}
		Block reference = switch (tier) {
			case BreachPlanner.TIER_STONE -> Blocks.IRON_ORE;
			case BreachPlanner.TIER_IRON -> Blocks.DIAMOND_ORE;
			case BreachPlanner.TIER_DIAMOND -> Blocks.OBSIDIAN;
			default -> Blocks.STONE;
		};
		return tool.isCorrectToolForDrops(reference.defaultBlockState());
	}

	/** Only stone and metal walls are gated (a mine's plank beams come down with an axe as usual). */
	static boolean gated(BlockState state) {
		return state.is(BlockTags.MINEABLE_WITH_PICKAXE);
	}

	// ---- furniture ----------------------------------------------------------------------------

	/** The sealed door: an iron door pair (only opens for Skyrim), facing into the wall from the room. */
	static BlockState door(Direction facing, boolean right, boolean upper) {
		return Blocks.IRON_DOOR.defaultBlockState()
			.setValue(DoorBlock.FACING, facing)
			.setValue(DoorBlock.HINGE, right ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT)
			.setValue(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER)
			.setValue(DoorBlock.OPEN, false);
	}

	static boolean isDoor(BlockState state) {
		return state.is(Blocks.IRON_DOOR);
	}

	static BlockState chest(Direction facing) {
		return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
	}

	/** The lantern hanging from the middle of the ceiling. */
	static BlockState lantern(int theme) {
		Block block = switch (theme) {
			case THEME_NORDIC, THEME_FALMER, THEME_LAIR -> Blocks.SOUL_LANTERN;
			case THEME_DWEMER -> Blocks.COPPER_LANTERN.waxed().unaffected();
			default -> Blocks.LANTERN;
		};
		return block.defaultBlockState().trySetValue(LanternBlock.HANGING, true);
	}

	/** A wall light either side of the sealed door, pointing into the room. */
	static BlockState wallTorch(int theme, Direction out) {
		Block block = switch (theme) {
			case THEME_FALMER, THEME_LAIR -> Blocks.SOUL_WALL_TORCH;
			case THEME_DWEMER -> Blocks.COPPER_WALL_TORCH;
			default -> Blocks.WALL_TORCH;
		};
		return block.defaultBlockState().trySetValue(BlockStateProperties.HORIZONTAL_FACING, out);
	}

	/** What stands in a floor corner (0..3 as BreachRoom.FLOOR_CORNERS), or null for nothing. */
	static @Nullable BlockState floorCorner(int theme, int corner) {
		return switch (theme) {
			case THEME_NORDIC -> corner < 2 ? Blocks.DECORATED_POT.defaultBlockState() : corner == 3 ? Blocks.SKELETON_SKULL.defaultBlockState() : null;
			case THEME_DWEMER -> corner < 2 ? Blocks.LIGHTNING_ROD.waxed().unaffected().defaultBlockState() : null;
			case THEME_FALMER -> corner == 0 ? Blocks.COBWEB.defaultBlockState() : corner == 3 ? Blocks.BROWN_MUSHROOM.defaultBlockState() : null;
			case THEME_MINE -> Blocks.OAK_FENCE.defaultBlockState();
			case THEME_FORT -> corner < 2 ? Blocks.BARREL.defaultBlockState().trySetValue(BlockStateProperties.FACING, Direction.UP)
				: corner == 3 ? Blocks.HAY_BLOCK.defaultBlockState() : null;
			case THEME_LAIR -> corner < 2 ? Blocks.DYED_CANDLE.pick(DyeColor.BLACK).defaultBlockState().trySetValue(CandleBlock.CANDLES, 3).trySetValue(CandleBlock.LIT, true)
				: corner == 3 ? Blocks.WITHER_SKELETON_SKULL.defaultBlockState() : null;
			default -> corner == 0 || corner == 3 ? Blocks.MOSS_CARPET.defaultBlockState() : null;
		};
	}

	/** How many blocks tall a floor corner's piece is (pillars and pipes reach the ceiling). */
	static int floorCornerHeight(int theme, int corner) {
		return switch (theme) {
			case THEME_DWEMER -> corner < 2 ? BreachRoom.HEIGHT : 1;
			case THEME_MINE -> BreachRoom.HEIGHT;
			default -> 1;
		};
	}

	/** What hangs in a ceiling corner (0..3 as BreachRoom.CEILING_CORNERS), or null for nothing. */
	static @Nullable BlockState ceilingCorner(int theme, int corner) {
		return switch (theme) {
			case THEME_NORDIC -> corner == 0 || corner == 3 ? Blocks.COBWEB.defaultBlockState() : null;
			case THEME_DWEMER -> corner >= 2 ? Blocks.IRON_CHAIN.defaultBlockState() : null;
			case THEME_FALMER -> Blocks.COBWEB.defaultBlockState();
			case THEME_LAIR -> corner < 2 ? Blocks.COBWEB.defaultBlockState() : null;
			case THEME_MINE, THEME_FORT -> null;
			default -> corner == 1 ? Blocks.COBWEB.defaultBlockState() : null;
		};
	}

	// ---- leaks --------------------------------------------------------------------------------

	/**
	 * A wisp of what's behind the rock: particles at {@code (px, py, pz)} (in the tunnel by the
	 * rock), and now and then a sound from the room's side ({@code (sx, sy, sz)}).
	 */
	static void leak(ServerLevel level, int theme, double px, double py, double pz, double sx, double sy, double sz, boolean sound, RandomSource random) {
		ParticleOptions particle;
		int count;
		double spread;
		double speed;
		switch (theme) {
			case THEME_NORDIC -> {
				particle = random.nextBoolean() ? ParticleTypes.WHITE_ASH : ParticleTypes.SNOWFLAKE;
				count = 6;
				spread = 0.35;
				speed = 0.005;
			}
			case THEME_DWEMER -> {
				particle = random.nextInt(3) == 0 ? ParticleTypes.CLOUD : ParticleTypes.WHITE_SMOKE;
				count = 4;
				spread = 0.2;
				speed = 0.02;
			}
			case THEME_FALMER -> {
				particle = ParticleTypes.DRIPPING_WATER;
				count = 2;
				spread = 0.3;
				speed = 0.0;
			}
			case THEME_MINE -> {
				particle = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.GRAVEL.defaultBlockState());
				count = 5;
				spread = 0.4;
				speed = 0.0;
			}
			case THEME_FORT, THEME_LAIR -> {
				particle = ParticleTypes.SMOKE;
				count = 4;
				spread = 0.25;
				speed = 0.01;
			}
			default -> {
				particle = ParticleTypes.DRIPPING_DRIPSTONE_WATER;
				count = 2;
				spread = 0.3;
				speed = 0.0;
			}
		}
		level.sendParticles(particle, px, py, pz, count, spread, spread, spread, speed);
		if (!sound) {
			return;
		}
		float pitch = 0.8F + random.nextFloat() * 0.3F;
		switch (theme) {
			case THEME_NORDIC -> {
				if (random.nextBoolean()) {
					level.playSound(null, sx, sy, sz, SoundEvents.SOUL_ESCAPE, SoundSource.AMBIENT, 0.8F, pitch);
				} else {
					level.playSound(null, sx, sy, sz, SoundEvents.ZOMBIE_AMBIENT, SoundSource.AMBIENT, 0.35F, 0.55F); // a draugr's groan
				}
			}
			case THEME_DWEMER -> {
				if (random.nextBoolean()) {
					level.playSound(null, sx, sy, sz, SoundEvents.PISTON_EXTEND, SoundSource.AMBIENT, 0.3F, 0.6F);
				} else {
					level.playSound(null, sx, sy, sz, SoundEvents.ANVIL_LAND, SoundSource.AMBIENT, 0.12F, 1.7F); // a far off clink
				}
			}
			case THEME_FALMER -> level.playSound(null, sx, sy, sz, SoundEvents.SPIDER_AMBIENT, SoundSource.AMBIENT, 0.4F, 0.7F);
			case THEME_MINE -> level.playSound(null, sx, sy, sz, random.nextBoolean() ? SoundEvents.GRAVEL_FALL : SoundEvents.STONE_HIT, SoundSource.AMBIENT, 0.5F, pitch);
			case THEME_FORT -> level.playSound(null, sx, sy, sz, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.AMBIENT, 0.6F, pitch);
			case THEME_LAIR -> level.playSound(null, sx, sy, sz, SoundEvents.BAT_AMBIENT, SoundSource.AMBIENT, 0.4F, pitch);
			default -> level.playSound(null, sx, sy, sz, SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, SoundSource.AMBIENT, 0.7F, pitch);
		}
	}
}
