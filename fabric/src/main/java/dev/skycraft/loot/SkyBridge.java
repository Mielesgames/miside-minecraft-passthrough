package dev.skycraft.loot;

import dev.skycraft.SkyCraft;
import dev.skycraft.config.Config;
import dev.skycraft.config.Features;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Looting bridge, Minecraft side. Skyrim reports every item that lands in the player's Skyrim
 * inventory; items that match a conversion rule in {@code config/skycraft-items.txt} are given to
 * the Minecraft player and Skyrim is told to remove the original (EV_TAKE_ITEM). Everything else
 * stays in Skyrim. Server thread only.
 */
public final class SkyBridge {
	private static final String FILE = "skycraft-items.txt";
	/** Most Minecraft stacks one Skyrim pickup turns into; the rest stays in Skyrim. */
	static final int MAX_STACKS = 64;
	private static ItemRules rules;

	private SkyBridge() {
	}

	private static final SkyLink.SkyState SKY = new SkyLink.SkyState();

	/** Converts every queued Skyrim item for the host player. */
	public static void tick(ServerPlayer host) {
		SkyLink.drainItems(item -> convert(host, item));
	}

	/**
	 * How many of {@code count} Skyrim items convert at {@code perItem} each, keeping the total within
	 * {@link #MAX_STACKS} stacks of {@code maxStack} (potions don't stack, so 64 potions at most).
	 */
	static int convertible(int count, int perItem, int maxStack) {
		return Math.min(count, MAX_STACKS * Math.max(1, maxStack) / Math.max(1, perItem));
	}

	/**
	 * {@code n} of {@code item}, holding {@code potion} and {@code effects} when it has them (potions, splash and
	 * lingering potions, tipped arrows), in {@code color} (0xRRGGBB, -1 for Minecraft's mix), named {@code name}.
	 */
	static ItemStack stack(Item item, int n, Holder<Potion> potion, List<MobEffectInstance> effects, int color, String name) {
		ItemStack stack = new ItemStack(item, n);
		if (potion != null || !effects.isEmpty()) {
			stack.set(DataComponents.POTION_CONTENTS,
				new PotionContents(Optional.ofNullable(potion), color >= 0 ? Optional.of(color) : Optional.empty(), effects, Optional.empty()));
		}
		if (name != null) {
			// A potion's own name comes from its contents, so the Skyrim name goes in CUSTOM_NAME, not italic like a renamed item's.
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false)));
		}
		return stack;
	}

	/** One Minecraft item a rule gives, found in the registries; {@code max} is its stack size. */
	private record Output(ItemRules.Result rule, Item item, Holder<Potion> potion, List<MobEffectInstance> effects, int max) {
	}

	/** {@code out}'s item, potion and effects from the registries; null (and a warning) when one is unknown. */
	private static Output resolve(ItemRules.Result out, String skyrimName) {
		Identifier key = Identifier.tryParse(out.item());
		Item item = key != null ? BuiltInRegistries.ITEM.getValue(key) : null;
		if (item == null || item == Items.AIR) {
			SkyCraft.LOG.warn("SkyCraft-loot: unknown item '{}' in {}, {} stays in Skyrim", out.item(), FILE, skyrimName);
			return null;
		}
		Holder<Potion> potion = null;
		if (out.potion() != null) {
			// ItemRules only lets vanilla potion ids through, so this finds it.
			potion = BuiltInRegistries.POTION.get(Identifier.withDefaultNamespace(out.potion())).orElse(null);
			if (potion == null) {
				SkyCraft.LOG.warn("SkyCraft-loot: unknown potion '{}' in {}, {} stays in Skyrim", out.potion(), FILE, skyrimName);
				return null;
			}
		}
		List<MobEffectInstance> effects = new ArrayList<>();
		for (ItemRules.Effect e : out.effects()) {
			// ItemRules only lets 26.3 effect ids through, and match() already picked the values for the name's tier.
			Holder<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.withDefaultNamespace(e.id())).orElse(null);
			if (effect == null) {
				SkyCraft.LOG.warn("SkyCraft-loot: unknown effect '{}' in {}, {} stays in Skyrim", e.id(), FILE, skyrimName);
				return null;
			}
			effects.add(new MobEffectInstance(effect, Math.max(1, e.seconds() * 20), e.amplifier()));
		}
		return new Output(out, item, potion, List.copyOf(effects), new ItemStack(item).getMaxStackSize());
	}

	/** Puts {@code stack} in the player's inventory; whatever doesn't fit drops at their feet. */
	static void give(ServerPlayer player, ItemStack stack) {
		player.getInventory().add(stack);
		if (!stack.isEmpty()) {
			ItemEntity dropped = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), stack);
			dropped.setDefaultPickUpDelay();
			player.level().addFreshEntity(dropped);
		}
	}

	private static void convert(ServerPlayer player, SkyLink.Item it) {
		if (it.count() <= 0) {
			return;
		}
		if (it.source() == Proto.SRC_CONTAINER && it.chest() && Features.on(Features.Feature.DUNGEON_CHESTS) && SkyLink.readSkyState(SKY)) {
			DungeonLoot.onLooted(player, it, SKY.place);
		}
		if (!Features.on(Features.Feature.ITEM_CONVERSION)) {
			return;
		}
		if (it.quest()) {
			SkyCraft.LOG.info("SkyCraft-loot: {} is a quest item, stays in Skyrim", it.name());
			return;
		}
		ItemRules.Result result = rules().match(new ItemRules.Item(it.tags(), it.material(), it.source(), it.name()));
		if (result.keep()) {
			SkyCraft.LOG.info("SkyCraft-loot: {} x {} stays in Skyrim [{}]", it.count(), it.name(), ItemRules.tagNames(it.tags()));
			return;
		}
		// The rule's item, then any it gives along with it ("+" in the rule).
		List<ItemRules.Result> wanted = new ArrayList<>();
		wanted.add(result);
		wanted.addAll(result.also());
		List<Output> outputs = new ArrayList<>();
		int count = it.count();
		for (ItemRules.Result out : wanted) {
			Output o = resolve(out, it.name());
			if (o == null) {
				return;
			}
			outputs.add(o);
			count = Math.min(count, convertible(it.count(), out.perItem(), o.max()));
		}
		for (Output o : outputs) {
			for (int left = count * o.rule().perItem(); left > 0; ) {
				int n = Math.min(left, o.max());
				give(player, stack(o.item(), n, o.potion(), o.effects(), o.rule().color(), o.rule().name()));
				left -= n;
			}
		}
		SkyLink.pushEvent(Proto.EV_TAKE_ITEM, it.formId(), count, 0.0F, 0.0F, 0.0F, 0);
		int taken = count;
		String became = outputs.stream().map(o -> taken * o.rule().perItem() + " " + o.rule().label()).collect(Collectors.joining(" + "));
		SkyCraft.LOG.info("SkyCraft-loot: {} x {} became {}{}", count, it.name(), became,
			count < it.count() ? " (" + (it.count() - count) + " more stay in Skyrim)" : "");
	}

	private static ItemRules rules() {
		if (rules == null) {
			List<String> problems = new ArrayList<>();
			rules = ItemRules.parse(Config.read(FILE, ItemRules.DEFAULT_TEXT), problems);
			problems.forEach(p -> SkyCraft.LOG.warn("SkyCraft-loot: {} {}", FILE, p));
			SkyCraft.LOG.info("SkyCraft-loot: {} conversion rules loaded", rules.size());
		}
		return rules;
	}
}
