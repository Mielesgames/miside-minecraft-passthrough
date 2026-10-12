package dev.skycraft.worlds;

import dev.skycraft.SkyCraft;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Builds {@link SteveCamp}'s layout into the world, from the decoration of the camp's anchor chunk. */
final class HomeCamp {
	private HomeCamp() {}

	static void build(WorldGenLevel level) {
		int baseY = SteveCamp.FLOOR_Y + 1;
		List<SteveCamp.Placement> layout = SteveCamp.layout();
		for (SteveCamp.Placement p : layout) {
			level.setBlock(at(p, baseY), state(p), Block.UPDATE_CLIENTS);
		}
		for (SteveCamp.Placement p : layout) {
			BlockPos pos = at(p, baseY);
			switch (p.piece()) {
				case CHEST -> {
					if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
						int slot = 0;
						for (SteveCamp.Stack s : SteveCamp.CHEST) {
							chest.setItem(slot++, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(s.item())), s.count()));
						}
					}
				}
				case LECTERN -> {
					if (level.getBlockEntity(pos) instanceof LecternBlockEntity lectern) {
						lectern.setBook(journal());
					}
				}
				case SIGN -> writeSign(level, pos, SteveCamp.signLines(p.sign()));
				default -> {
				}
			}
		}
		SkyCraft.LOG.info("SkyCraft-worlds: built Steve's camp at {} {} {}", SteveCamp.ORIGIN_X, baseY, SteveCamp.ORIGIN_Z);
	}

	/**
	 * Gives a sign its text through the chunk's pending block entity data. During generation the sign's
	 * block entity has no level, and SignBlockEntity.setText would push a block update to it; the pending
	 * data is loaded into the sign when the chunk finishes. Waxed, so the count can't be edited.
	 */
	private static void writeSign(WorldGenLevel level, BlockPos pos, List<String> lines) {
		SignText.Mutable text = SignText.EMPTY.asMutable();
		for (int i = 0; i < lines.size(); i++) {
			text.setLine(i, Component.literal(lines.get(i)));
		}
		RegistryOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:sign");
		tag.putInt("x", pos.getX());
		tag.putInt("y", pos.getY());
		tag.putInt("z", pos.getZ());
		tag.put("front_text", SignText.CODEC.encodeStart(ops, text.asImmutable()).getOrThrow());
		tag.put("back_text", SignText.CODEC.encodeStart(ops, SignText.EMPTY).getOrThrow());
		tag.putBoolean("is_waxed", true);
		level.getChunk(pos).setBlockEntityNbt(tag);
	}

	private static BlockPos at(SteveCamp.Placement p, int baseY) {
		return new BlockPos(SteveCamp.ORIGIN_X + p.dx(), baseY + p.dy(), SteveCamp.ORIGIN_Z + p.dz());
	}

	static ItemStack journal() {
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		List<Filterable<Component>> pages = SteveCamp.JOURNAL_PAGES.stream()
			.map(page -> Filterable.<Component>passThrough(Component.literal(page)))
			.toList();
		book.set(DataComponents.WRITTEN_BOOK_CONTENT,
			new WrittenBookContent(Filterable.passThrough(SteveCamp.JOURNAL_TITLE), SteveCamp.JOURNAL_AUTHOR, 0, pages, true));
		return book;
	}

	private static Direction direction(SteveCamp.Facing facing) {
		return switch (facing) {
			case NORTH -> Direction.NORTH;
			case SOUTH -> Direction.SOUTH;
			case EAST -> Direction.EAST;
			case WEST -> Direction.WEST;
		};
	}

	private static BlockState state(SteveCamp.Placement p) {
		Direction facing = direction(p.facing());
		return switch (p.piece()) {
			case AIR -> Blocks.AIR.defaultBlockState();
			case DIRT -> Blocks.DIRT.defaultBlockState();
			case BED_FOOT -> Blocks.BED.red().defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(BlockStateProperties.BED_PART, BedPart.FOOT);
			case BED_HEAD -> Blocks.BED.red().defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
			case CHEST -> Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
			case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE.defaultBlockState();
			case FURNACE -> Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, facing);
			case LECTERN -> Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, facing).setValue(LecternBlock.HAS_BOOK, true);
			case WALL_TORCH -> Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing);
			// Standing sign rotation counts sixteenths of a turn from south, clockwise.
			case SIGN -> Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation(facing));
		};
	}

	private static int rotation(Direction facing) {
		return switch (facing) {
			case SOUTH -> 0;
			case WEST -> 4;
			case NORTH -> 8;
			default -> 12;
		};
	}
}
