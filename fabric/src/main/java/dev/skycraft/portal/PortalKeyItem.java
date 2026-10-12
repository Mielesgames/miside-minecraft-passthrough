package dev.skycraft.portal;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * A portal key with no behaviour of its own (the tonal key, Seamstone): what it does on a lit portal is
 * SkyPortals.useOnPortal, through Fabric's UseBlockCallback, like the Daedra heart's. This only tells the
 * player how to use it.
 */
public final class PortalKeyItem extends Item {
	private final List<String> lines;

	public PortalKeyItem(Item.Properties properties, List<String> lines) {
		super(properties);
		this.lines = List.copyOf(lines);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		for (int i = 0; i < this.lines.size(); i++) {
			out.accept(Component.literal(this.lines.get(i)).withStyle(i == 0 ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
		}
	}
}
