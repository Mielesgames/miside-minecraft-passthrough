package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.config.Features;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Right click activates Skyrim's doors, containers, NPCs, items, furniture and levers the way G
 * does, when Minecraft has no use for the click. Minecraft goes first: whatever you hold is used,
 * Minecraft blocks and entities take the click, and an empty hand on a merchant opens the shop
 * (combat.SkyrimActorEntity.interact). Only a fresh press counts (mixin.MinecraftUseMixin), so
 * holding right click to draw a bow, raise a shield or eat never activates anything. Skyrim reads
 * its own crosshair when the request arrives and does nothing if nothing is there (Input.cpp,
 * UpdateClickActivate). Switch: click_to_activate.
 */
public final class ClickActivate {
	private static int logs;

	private ClickActivate() {
	}

	/** A press of the use key that no hand's block, entity or item interaction took (Minecraft.startUseItem ran to its end). */
	public static void onUnusedPress(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gui.screen() != null || player.isSpectator() || player.isUsingItem()) {
			return;
		}
		if (!Features.on(Features.Feature.CLICK_TO_ACTIVATE) || !SkyLink.active()) {
			return;
		}
		if (!dev.skycraft.SkyDimension.inMirror(player.level())) {
			return; // another dimension (Minecraft view): Skyrim's crosshair isn't on anything here
		}
		if (!leavesClickFree(player, player.getMainHandItem()) || !leavesClickFree(player, player.getOffhandItem())) {
			return;
		}
		SkyLink.pushEvent(Proto.EV_ACTIVATE, 0, 0.0F, 0.0F, 0.0F, 0.0F, 0);
		if (logs < 5) {
			logs++;
			SkyCraft.LOG.info("SkyCraft: right click had no Minecraft use; Skyrim activates what its crosshair is on");
		}
	}

	/**
	 * Whether a right click with this item in hand is free for Skyrim once Minecraft found no use for
	 * it here: an empty hand, a sword or pickaxe, an axe on something it can't strip, a block with
	 * nowhere to go. Items you hold right click with (bows, crossbows, tridents, shields, spears,
	 * food, potions, spyglasses, horns, brushes, bundles), armor and anything else right click puts
	 * on, and items still cooling down (an ender pearl just thrown) keep the click even when it did
	 * nothing: it was meant for them.
	 */
	static boolean leavesClickFree(LocalPlayer player, ItemStack stack) {
		if (stack.isEmpty()) {
			return true;
		}
		if (player.getCooldowns().isOnCooldown(stack)) {
			return false;
		}
		if (stack.getUseAnimation() != ItemUseAnimation.NONE || stack.getUseDuration(player) > 0 || stack.has(DataComponents.CONSUMABLE)) {
			return false;
		}
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable == null || !equippable.swappable();
	}
}
