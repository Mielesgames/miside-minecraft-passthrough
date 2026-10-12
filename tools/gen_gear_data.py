"""Generate the SkyCraft gear set JSON: item model definitions, item models, equipment assets,
recipes, repair tags, the vanilla item tags (swords, axes, *_armor) and English names (merged
into the existing en_us.json).

    python tools/gen_gear_data.py

Schemas follow the vanilla Minecraft 26.3 jar (assets/minecraft/items, models/item, equipment,
data/minecraft/recipe, tags/item). The tier list must match GearTiers.java; GearResourcesTest
checks that every registered item has its files.
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "fabric", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "skycraft")
DATA = os.path.join(RES, "data", "skycraft")
NS = "skycraft"

# id, English name, crafting ingot (same order, names and ingots as GearTiers.TIERS). The iron tier
# says "Skyrim" so its names differ from vanilla's Iron Sword, Iron Helmet and Iron Boots.
TIERS = [
    ("iron", "Skyrim Iron", "minecraft:iron_ingot"),
    ("steel", "Steel", "skycraft:steel_ingot"),
    ("orcish", "Orcish", "skycraft:orichalcum_ingot"),
    ("dwarven", "Dwarven", "skycraft:dwarven_metal_ingot"),
    ("elven", "Elven", "skycraft:refined_moonstone"),
    ("glass", "Glass", "skycraft:refined_malachite"),
    ("ebony", "Ebony", "skycraft:ebony_ingot"),
    ("daedric", "Daedric", "skycraft:ebony_ingot"),
]
PIECES = [("sword", "Sword"), ("war_axe", "War Axe"), ("helmet", "Helmet"), ("cuirass", "Cuirass"), ("greaves", "Greaves"), ("boots", "Boots")]
HANDHELD = {"sword", "war_axe"}
# The vanilla item tag each piece joins, like vanilla's own gear: the enchantable/* tags (what the
# enchanting table and anvil offer) are built from these, and a sword's sweep attack needs minecraft:swords.
VANILLA_TAGS = {"sword": "swords", "war_axe": "axes", "helmet": "head_armor", "cuirass": "chest_armor", "greaves": "leg_armor", "boots": "foot_armor"}
MATERIALS = [
    ("steel_ingot", "Steel Ingot"),
    ("orichalcum_ingot", "Orichalcum Ingot"),
    ("dwarven_metal_ingot", "Dwarven Metal Ingot"),
    ("refined_moonstone", "Refined Moonstone"),
    ("refined_malachite", "Refined Malachite"),
    ("ebony_ingot", "Ebony Ingot"),
    ("daedra_heart", "Daedra Heart"),
    ("leather_strips", "Leather Strips"),
]

# Crafting table patterns: M = tier ingot, L = leather strips, H = daedra heart (daedric only).
PATTERNS = {
    "sword": ["M", "M", "L"],
    "war_axe": ["MM", "ML", " L"],
    "helmet": ["MMM", "MLM"],
    "cuirass": ["MLM", "MMM", "MMM"],
    "greaves": ["MMM", "MLM", "M M"],
    "boots": ["MLM", "M M"],
}
DAEDRIC_PATTERNS = {
    "sword": ["MH", "M ", "L "],
    "war_axe": ["MM", "MH", " L"],
    "helmet": ["MHM", "MLM"],
    "cuirass": ["MLM", "MHM", "MMM"],
    "greaves": ["MHM", "MLM", "M M"],
    "boots": ["MLM", "MHM"],
}
COAL = ["minecraft:coal", "minecraft:charcoal"]


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def item_files(item_id, parent):
    write(os.path.join(ASSETS, "items", item_id + ".json"), {"model": {"type": "minecraft:model", "model": NS + ":item/" + item_id}})
    write(os.path.join(ASSETS, "models", "item", item_id + ".json"), {"parent": parent, "textures": {"layer0": NS + ":item/" + item_id}})


def shaped(name, pattern, key, result, count=1, category="equipment"):
    used = {c for row in pattern for c in row if c != " "}
    res = {"id": result}
    if count != 1:
        res = {"count": count, "id": result}
    write(os.path.join(DATA, "recipe", name + ".json"),
          {"type": "minecraft:crafting_shaped", "category": category, "key": {k: v for k, v in key.items() if k in used}, "pattern": pattern, "result": res})


def shapeless(name, ingredients, result, count=1):
    res = {"id": result} if count == 1 else {"count": count, "id": result}
    write(os.path.join(DATA, "recipe", name + ".json"), {"type": "minecraft:crafting_shapeless", "category": "misc", "ingredients": ingredients, "result": res})


def cooking(name, kind, ingredient, result, xp, time):
    write(os.path.join(DATA, "recipe", name + ".json"),
          {"type": "minecraft:" + kind, "category": "misc", "cookingtime": time, "experience": xp, "group": result.split(":")[1], "ingredient": ingredient,
           "result": {"id": result}})


def main():
    lang = {}
    vanilla_tags = {tag: [] for tag in VANILLA_TAGS.values()}
    for item_id, name in MATERIALS:
        item_files(item_id, "minecraft:item/generated")
        lang["item.%s.%s" % (NS, item_id)] = name
    for tier, tier_name, ingot in TIERS:
        for piece, piece_name in PIECES:
            item_id = tier + "_" + piece
            item_files(item_id, "minecraft:item/handheld" if piece in HANDHELD else "minecraft:item/generated")
            lang["item.%s.%s" % (NS, item_id)] = tier_name + " " + piece_name
            vanilla_tags[VANILLA_TAGS[piece]].append(NS + ":" + item_id)
            pattern = (DAEDRIC_PATTERNS if tier == "daedric" else PATTERNS)[piece]
            shaped(item_id, pattern, {"M": ingot, "L": NS + ":leather_strips", "H": NS + ":daedra_heart"}, NS + ":" + item_id)
        write(os.path.join(ASSETS, "equipment", tier + ".json"),
              {"layers": {"humanoid": [{"texture": NS + ":" + tier}], "humanoid_leggings": [{"texture": NS + ":" + tier}]}})
        write(os.path.join(DATA, "tags", "item", tier + "_repair_materials.json"), {"values": [ingot]})
    for tag, values in vanilla_tags.items():
        # Merged into vanilla's tag (no "replace"), so vanilla's own members stay.
        write(os.path.join(RES, "data", "minecraft", "tags", "item", tag + ".json"), {"values": values})
    lang["itemGroup.%s.gear" % NS] = "SkyCraft Gear"

    # Materials from vanilla items, so every tier can be made without Skyrim loot (except the daedra heart).
    shapeless("steel_ingot", ["minecraft:iron_ingot", COAL], NS + ":steel_ingot")
    shapeless("leather_strips", ["minecraft:leather"], NS + ":leather_strips", count=4)
    shapeless("orichalcum_ingot", ["minecraft:iron_ingot", "minecraft:copper_ingot", COAL], NS + ":orichalcum_ingot")
    shapeless("dwarven_metal_ingot", ["minecraft:copper_ingot", "minecraft:copper_ingot", "minecraft:gold_ingot"], NS + ":dwarven_metal_ingot", count=2)
    shapeless("ebony_ingot", ["minecraft:diamond", "minecraft:obsidian", "minecraft:obsidian"], NS + ":ebony_ingot")
    cooking("refined_moonstone_from_smelting", "smelting", "minecraft:quartz", NS + ":refined_moonstone", 0.7, 200)
    cooking("refined_moonstone_from_blasting", "blasting", "minecraft:quartz", NS + ":refined_moonstone", 0.7, 100)
    cooking("refined_malachite_from_smelting", "smelting", "minecraft:emerald", NS + ":refined_malachite", 1.0, 200)
    cooking("refined_malachite_from_blasting", "blasting", "minecraft:emerald", NS + ":refined_malachite", 1.0, 100)

    lang_path = os.path.join(ASSETS, "lang", "en_us.json")
    with open(lang_path, encoding="utf-8") as f:
        existing = json.load(f)
    existing.update(lang)
    write(lang_path, existing)
    print("wrote %d items, %d tiers, lang now has %d keys" % (len(MATERIALS) + len(TIERS) * len(PIECES), len(TIERS), len(existing)))


if __name__ == "__main__":
    main()
