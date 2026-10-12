package dev.skycraft.portal;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;

/**
 * A Skyrim soul gem in Minecraft, the key to the portal network: a filled one (converted from a filled
 * Greater, Grand or Black soul gem) attunes to a lit portal it's used on; the attuned one it becomes
 * binds that portal to the next lit portal it's used on, and is spent (SkyPortals.useGem).
 */
public final class SoulGemItem extends Item {
	private final boolean attuned;

	public SoulGemItem(Item.Properties properties, boolean attuned) {
		super(properties);
		this.attuned = attuned;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!context.getLevel().getBlockState(context.getClickedPos()).is(Blocks.NETHER_PORTAL)) {
			return InteractionResult.PASS;
		}
		// The client only knows it's a portal (the network lives on the server): take the click, so it
		// isn't passed on to Skyrim as an activation.
		if (context.getLevel().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		return SkyPortals.useGem(context, this.attuned);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		if (!this.attuned) {
			out.accept(Component.literal("Use on a lit Nether portal to attune").withStyle(ChatFormatting.GRAY));
			return;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		String place = data != null ? data.copyTag().getStringOr(SkyPortals.KEY_PLACE, "") : "";
		out.accept(Component.literal(place.isEmpty() ? "Attuned to a portal" : "Attuned to the portal at " + place).withStyle(ChatFormatting.LIGHT_PURPLE));
		out.accept(Component.literal("Use on a second lit portal to bind the two").withStyle(ChatFormatting.GRAY));
	}
}
