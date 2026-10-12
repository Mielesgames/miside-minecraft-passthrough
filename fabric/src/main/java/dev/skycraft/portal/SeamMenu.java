package dev.skycraft.portal;

import dev.skycraft.portal.PortalStore.Target;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Seamstone's list of places (Destinations), shown as a vanilla chest of icons so the client needs
 * nothing new: one item per place, named after it. Clicking one picks it and closes the chest; nothing can
 * be taken out or put in (every click is swallowed here on the server, and the server's next sync puts
 * back anything the client guessed had moved). Server side only.
 */
final class SeamMenu extends ChestMenu {
	private static final List<MenuType<ChestMenu>> TYPES = List.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3, MenuType.GENERIC_9x4,
		MenuType.GENERIC_9x5, MenuType.GENERIC_9x6);

	private final List<Destinations.Entry> entries;
	private final BiConsumer<ServerPlayer, Target> chosen;
	private boolean done;

	private SeamMenu(int id, Inventory inventory, SimpleContainer icons, int rows, List<Destinations.Entry> entries, BiConsumer<ServerPlayer, Target> chosen) {
		super(TYPES.get(rows - 1), id, inventory, icons, rows);
		this.entries = entries;
		this.chosen = chosen;
	}

	/** Opens the list for this player; {@code chosen} gets the place they click. */
	static void open(ServerPlayer player, List<Destinations.Entry> entries, BiConsumer<ServerPlayer, Target> chosen) {
		int rows = Destinations.rows(entries.size());
		SimpleContainer icons = new SimpleContainer(rows * 9);
		for (int i = 0; i < entries.size() && i < rows * 9; i++) {
			icons.setItem(i, icon(entries.get(i)));
		}
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new SeamMenu(id, inventory, icons, rows, entries, chosen),
			Component.literal("Where should the crack open?")));
	}

	private static ItemStack icon(Destinations.Entry e) {
		var item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(e.icon())).orElse(Items.OBSIDIAN);
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(e.label()));
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(e.detail()))));
		return stack;
	}

	@Override
	public void clicked(int slot, int button, ContainerInput input, Player player) {
		if (this.done || !(player instanceof ServerPlayer sp)) {
			return;
		}
		if (slot >= 0 && slot < this.entries.size() && slot < this.getRowCount() * 9 && input == ContainerInput.PICKUP) {
			// The chest is closed on the next tick (SkyPortals), not here: the click packet's handler still
			// works on this menu after clicked returns.
			this.done = true;
			this.chosen.accept(sp, this.entries.get(slot).target());
		}
		this.sendAllDataToRemote(); // undo whatever the client thought it moved
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return player.isAlive();
	}
}
