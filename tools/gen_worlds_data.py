#!/usr/bin/env python3
"""
Generates the data for SkyCraft's worlds (see docs/FEATURES.md, "Worlds", and docs/LORE.md):

  The Nether         data/minecraft/dimension/the_nether.json (vanilla generator, added to the mirror world)
  Tamriel Wilds      dimension, dimension type, noise settings, density functions, biomes, features,
                     surface (material) rules, two structures with their NBT templates, biome tags
  Home               dimension, dimension type and biome (the terrain itself is Java: dev.skycraft.worlds)

Usage (from the repo root):
  python3 tools/gen_worlds_data.py --jar <path to minecraft-26.3-client.jar> [--out fabric/src/main/resources]

The jar is read for the vanilla amplified density functions, which Tamriel Wilds rewires to its own
offset, factor and jaggedness. Everything else is written from the tables below, so tuning a biome means
editing this file and running it again. Output is deterministic; commit what it writes.
"""
import argparse
import gzip
import io
import json
import os
import struct
import zipfile

NS = 'skycraft'
DATA_VERSION = 5023  # Minecraft 26.3 world/data version (version.json "world_version")
BIG = 1.7976931348623157e308

OUT = None


def write_json(rel, obj):
	path = os.path.join(OUT, 'data', rel)
	os.makedirs(os.path.dirname(path), exist_ok=True)
	with open(path, 'w', encoding='utf-8', newline='\n') as f:
		json.dump(obj, f, indent=2)
		f.write('\n')


# ----------------------------------------------------------------------------------------------
# Material (surface) rule helpers, 26.3 schema (worldgen/material_rule, worldgen/material_condition)

def block(state):
	return {'type': 'minecraft:block', 'result_state': state}


def cond(condition, then):
	return {'type': 'minecraft:condition', 'if_true': condition, 'then_run': then}


def seq(*rules):
	return {'type': 'minecraft:sequence', 'sequence': list(rules)}


def biome_is(*ids):
	ids = [i if ':' in i else NS + ':' + i for i in ids]
	return {'type': 'minecraft:biome', 'biome_is': ids[0] if len(ids) == 1 else ids}


def noise(name, lo, hi):
	return {'type': 'minecraft:noise_threshold', 'max_threshold': hi, 'min_threshold': lo, 'noise': name}


def y_above(y, mult=0, stone=False):
	return {'type': 'minecraft:y_above', 'add_stone_depth': stone, 'anchor': {'absolute': y}, 'surface_depth_multiplier': mult}


def not_(c):
	return {'type': 'minecraft:not', 'invert': c}


STEEP = {'type': 'minecraft:steep'}
ON_FLOOR = 'minecraft:on_floor'
UNDER_FLOOR = 'minecraft:under_floor'
ON_CEILING = 'minecraft:on_ceiling'
NOT_UNDERWATER = 'minecraft:not_underwater'
NOT_UNDER_DEEP_WATER = 'minecraft:not_under_deep_water'
DEEP_UNDER_FLOOR = 'minecraft:deep_under_floor'

# Skyrim's grey granite: mottled stone with andesite, tuff and diorite.
def strata(bottom, top, spacing):
	"""Wavy horizontal bands: each band's floor rises and falls with the column's surface depth noise."""
	kinds = ['minecraft:tuff', 'minecraft:andesite', 'minecraft:andesite', 'minecraft:diorite', 'minecraft:tuff', 'minecraft:andesite']
	rules = []
	for i, y in enumerate(range(bottom, top, spacing)):
		thick = 3 if kinds[i % len(kinds)] == 'minecraft:diorite' else 5
		rules.append(cond(y_above(y, 3), cond(not_(y_above(y + thick, 3)), block(kinds[i % len(kinds)]))))
	return rules


GRANITE = seq(
	*strata(52, 320, 17),
	cond(noise('minecraft:surface', 0.62, BIG), block('minecraft:andesite')),
	cond(noise('minecraft:surface', -BIG, -0.72), block('minecraft:tuff')),
	block('minecraft:stone'),
)
DARK_ROCK = seq(
	cond(noise('minecraft:surface', 0.2, BIG), block('minecraft:smooth_basalt')),
	cond(noise('minecraft:surface', -BIG, -0.45), block('minecraft:tuff')),
	block('minecraft:blackstone'),
)


def snowcap():
	return seq(
		cond(noise('minecraft:packed_ice', 0.0, 0.2), block('minecraft:packed_ice')),
		cond(noise('minecraft:ice', 0.0, 0.025), block('minecraft:ice')),
		block('minecraft:snow_block'),
	)


def tamriel_surface():
	# The top block of each land column, by biome. Every branch ends in a block, so later rules only
	# see what an earlier one skipped.
	floors = {
		'whiterun_tundra': seq(
			cond(noise('minecraft:surface', 0.55, BIG), block('minecraft:coarse_dirt')),
			cond(noise('minecraft:gravel', 0.62, BIG), block('minecraft:gravel')),
			block('minecraft:grass_block'),
		),
		'falkreath_pines': seq(
			cond(noise('minecraft:surface', 0.85, BIG), block('minecraft:coarse_dirt')),
			cond(noise('minecraft:surface', 0.15, 0.45), block('minecraft:moss_block')),
			cond(noise('minecraft:surface', -0.2, 0.15), block('minecraft:podzol')),
			block('minecraft:grass_block'),
		),
		'the_rift': seq(
			cond(noise('minecraft:surface', 0.7, BIG), block('minecraft:coarse_dirt')),
			cond(noise('minecraft:surface', -0.08, 0.0), block('minecraft:podzol')),
			block('minecraft:grass_block'),
		),
		'eastmarch_springs': seq(
			cond(noise('minecraft:surface', 0.66, BIG), block('minecraft:basalt')),
			cond(noise('minecraft:surface', 0.56, 0.66), block('minecraft:smooth_basalt')),
			cond(noise('minecraft:surface_secondary', 0.66, BIG), block('minecraft:sulfur')),
			cond(noise('minecraft:surface', -BIG, -0.6), block('minecraft:coarse_dirt')),
			block('minecraft:grass_block'),
		),
		'the_reach': seq(
			cond(noise('minecraft:surface', 0.42, BIG), block('minecraft:andesite')),
			cond(noise('minecraft:surface', -BIG, -0.55), block('minecraft:tuff')),
			cond(noise('minecraft:surface', -0.1, -0.02), block('minecraft:gravel')),
			block('minecraft:grass_block'),
		),
		'the_pale': seq(
			cond(noise('minecraft:surface', 0.3, BIG), block('minecraft:snow_block')),
			cond(noise('minecraft:packed_ice', 0.35, 0.45), block('minecraft:packed_ice')),
			block('minecraft:grass_block'),
		),
		'winterhold_peaks': snowcap(),
		'solstheim_ash': seq(
			cond(noise('minecraft:surface_secondary', 0.42, BIG), block('minecraft:mycelium')),
			cond(noise('minecraft:surface', 0.5, BIG), block('minecraft:gravel')),
			cond(noise('minecraft:surface', -BIG, -0.55), block('minecraft:tuff')),
			cond(noise('minecraft:calcite', -0.06, 0.06), block('minecraft:smooth_basalt')),
			block('minecraft:light_gray_concrete_powder'),
		),
		'tamriel_river': block('minecraft:grass_block'),
		'sea_of_ghosts': seq(
			cond(noise('minecraft:surface', -BIG, 0.0), block('minecraft:gravel')),
			block('minecraft:stone'),
		),
	}
	under = {
		'solstheim_ash': block('minecraft:tuff'),
		'winterhold_peaks': block('minecraft:snow_block'),
		'eastmarch_springs': seq(cond(noise('minecraft:surface', 0.56, BIG), block('minecraft:basalt')), block('minecraft:dirt')),
		'sea_of_ghosts': block('minecraft:gravel'),
	}
	river_bed = seq(
		cond(noise('minecraft:surface', 0.3, BIG), block('minecraft:sand')),
		cond(noise('minecraft:surface', -0.2, -0.1), block('minecraft:clay')),
		block('minecraft:gravel'),
	)
	floor_rules = [
		# Ash cliffs are dark; every other cliff is granite.
		cond(biome_is('solstheim_ash'), cond(STEEP, DARK_ROCK)),
		cond(STEEP, GRANITE),
		# Snow line: a ragged band around y 195, lower in the cold north (freeze_top_layer adds snow
		# layers above it by temperature too).
		cond(y_above(195, 3), cond(not_(biome_is('solstheim_ash')), snowcap())),
		cond(biome_is('tamriel_river', 'sea_of_ghosts'), cond(not_(NOT_UNDERWATER), river_bed)),
		cond(not_(NOT_UNDER_DEEP_WATER), block('minecraft:gravel')),
	]
	for b, rule in floors.items():
		if b == 'sea_of_ghosts':
			floor_rules.append(cond(biome_is(b), rule))
		else:
			floor_rules.append(cond(biome_is(b), cond(NOT_UNDERWATER, rule)))
	floor_rules.append(cond(NOT_UNDERWATER, block('minecraft:grass_block')))
	floor_rules.append(seq(cond(ON_CEILING, block('minecraft:stone')), block('minecraft:dirt')))
	under_rules = [cond(STEEP, GRANITE)]
	for b, rule in under.items():
		under_rules.append(cond(biome_is(b), rule))
	under_rules.append(block('minecraft:dirt'))
	return seq(
		cond(ON_FLOOR, seq(*floor_rules)),
		cond(NOT_UNDER_DEEP_WATER, cond(UNDER_FLOOR, seq(*under_rules))),
		cond(ON_FLOOR, seq(cond(ON_CEILING, block('minecraft:stone')), block('minecraft:gravel'))),
	)


def tamriel_stone():
	# Below the surface: vanilla deepslate gradient at the bottom, granite mottling in the mountains.
	return seq(
		cond({
			'type': 'minecraft:vertical_gradient',
			'false_at_and_above': {'absolute': 8},
			'random_name': 'minecraft:deepslate',
			'true_at_and_below': {'absolute': 0},
		}, block('minecraft:deepslate')),
		cond(y_above(48), GRANITE),
	)


def tamriel_material_rule():
	return seq(
		'minecraft:bedrock_floor',
		'minecraft:overworld/copper_ore_vein',
		'minecraft:overworld/iron_ore_vein',
		cond({'type': 'minecraft:above_preliminary_surface'}, NS + ':tamriel_wilds/surface'),
		NS + ':tamriel_wilds/stone',
	)


# ----------------------------------------------------------------------------------------------
# Terrain: amplified's shape, tamed. Mountains (low erosion) take amplified's height and jaggedness,
# open land (high erosion) keeps the normal overworld height so valleys stay broad and flat, and the
# normal (larger) factor everywhere keeps the 3D noise from tearing peaks into floating spikes.

def gradient(frm, fv, to, tv):
	return {'type': 'minecraft:gradient', 'axis': 'y', 'from_coordinate': frm, 'from_value': fv, 'to_coordinate': to, 'to_value': tv}


def lerp(alpha, first, second):
	return {'type': 'minecraft:lerp', 'alpha': alpha, 'first': first, 'second': second}


def tamriel_density(jar):
	d = NS + ':tamriel_wilds/'
	write_json(NS + '/worldgen/density_function/tamriel_wilds/mountains.json', {
		'type': 'minecraft:cache',
		'input': {
			'type': 'minecraft:clamp',
			'input': {
				'type': 'minecraft:add',
				'left': {'type': 'minecraft:mul', 'left': 'minecraft:overworld/erosion', 'right': -2.2},
				'right': 0.05,
			},
			'max': 1.0,
			'min': 0.0,
		},
	})
	write_json(NS + '/worldgen/density_function/tamriel_wilds/offset.json', {
		'type': 'minecraft:cache',
		'input': lerp(d + 'mountains', 'minecraft:overworld/offset', 'minecraft:overworld_amplified/offset'),
	})
	write_json(NS + '/worldgen/density_function/tamriel_wilds/jaggedness.json', {
		'type': 'minecraft:cache',
		'input': lerp(d + 'mountains', 'minecraft:overworld/jaggedness', {
			'type': 'minecraft:mul', 'left': 'minecraft:overworld_amplified/jaggedness', 'right': 0.8,
		}),
	})
	write_json(NS + '/worldgen/density_function/tamriel_wilds/factor.json', {
		'type': 'minecraft:cache',
		'input': {'type': 'minecraft:mul', 'left': 'minecraft:overworld/factor', 'right': 1.15},
	})
	# depth, sloped_cheese, final_density, preliminary_surface_level and chunk_surface_level are vanilla
	# amplified's, rewired to the functions above.
	z = zipfile.ZipFile(jar)
	for name in ('depth', 'sloped_cheese', 'final_density', 'preliminary_surface_level', 'chunk_surface_level'):
		raw = z.read('data/minecraft/worldgen/density_function/overworld_amplified/%s.json' % name).decode('utf-8')
		raw = raw.replace('minecraft:overworld_amplified/', d)
		write_json(NS + '/worldgen/density_function/tamriel_wilds/%s.json' % name, json.loads(raw))
	settings = json.loads(z.read('data/minecraft/worldgen/noise_settings/amplified.json').decode('utf-8'))
	text = json.dumps(settings).replace('minecraft:overworld_amplified/', d)
	settings = json.loads(text)
	settings['material_rule'] = NS + ':tamriel_wilds'
	settings['debug_functions'] = [
		{'function': d + 'final_density', 'label': 'N'},
		{'function': 'minecraft:overworld/temperature', 'label': 'T'},
		{'function': 'minecraft:overworld/vegetation', 'label': 'V'},
		{'function': 'minecraft:overworld/continents', 'label': 'C'},
		{'function': 'minecraft:overworld/erosion', 'label': 'E'},
		{'function': d + 'mountains', 'label': 'M'},
		{'function': 'minecraft:overworld/ridges', 'label': 'W'},
	]
	write_json(NS + '/worldgen/noise_settings/tamriel_wilds.json', settings)


# ----------------------------------------------------------------------------------------------
# Features

def placed(feature, placement):
	return {'feature': feature, 'placement': placement}


SQUARE = {'type': 'minecraft:in_square'}
BIOME = {'type': 'minecraft:biome'}


def heightmap(h):
	return {'type': 'minecraft:heightmap', 'heightmap': h}


def count(n):
	return {'type': 'minecraft:count', 'count': n}


def rarity(n):
	return {'type': 'minecraft:rarity_filter', 'chance': n}


def weighted_count(*pairs):
	return count({'type': 'minecraft:weighted_list', 'distribution': [{'data': d, 'weight': w} for d, w in pairs]})


def would_survive(sapling):
	return {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:would_survive', 'state': sapling}}


def tri(m):
	return {'type': 'minecraft:trapezoid', 'max': m, 'min': -m, 'plateau': 0}


PATCH_SPREAD = [
	{'type': 'minecraft:offset', 'x': tri(7), 'y': tri(3), 'z': tri(7)},
	{'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:air'}},
]


def patch(feature, first, tries):
	return placed(feature, first + [SQUARE, heightmap('MOTION_BLOCKING'), BIOME, count(tries)] + PATCH_SPREAD)


def trees(feature, counter, sapling):
	return placed(feature, [counter, SQUARE, {'type': 'minecraft:surface_water_depth_filter', 'max_water_depth': 0},
		heightmap('OCEAN_FLOOR'), BIOME, would_survive(sapling)])


def boulder(state):
	return {
		'type': 'minecraft:block_blob',
		'can_place_on': {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:forest_rock_can_place_on'},
		'state': state,
	}


def weighted_blocks(*pairs):
	return {'type': 'minecraft:simple_block', 'to_place': {'type': 'minecraft:weighted', 'entries': [{'data': d, 'weight': w} for d, w in pairs]}}


FEATURES = {
	'andesite_boulder': boulder('minecraft:andesite'),
	'tuff_boulder': boulder('minecraft:tuff'),
	'basalt_boulder': boulder('minecraft:basalt'),
	'blackstone_boulder': boulder('minecraft:blackstone'),
	'tundra_flowers': weighted_blocks(('minecraft:allium', 3), ('minecraft:azure_bluet', 3), ('minecraft:cornflower', 2), ('minecraft:dandelion', 1)),
	'reach_flowers': weighted_blocks(('minecraft:poppy', 6), ({'id': 'minecraft:rose_bush', 'properties': {'half': 'lower'}}, 1)),
	'tundra_grass': weighted_blocks(('minecraft:short_dry_grass', 3), ('minecraft:tall_dry_grass', 2), ('minecraft:short_grass', 2)),
	'hot_spring': {
		'type': 'minecraft:lake',
		'barrier': {'id': 'minecraft:sulfur'},
		'can_place_feature': {'type': 'minecraft:true'},
		'can_replace_with_air_or_fluid': {'type': 'minecraft:not', 'predicate': {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:features_cannot_replace'}},
		'can_replace_with_barrier': {'type': 'minecraft:not', 'predicate': {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:lava_pool_stone_cannot_replace'}},
		'fluid': {'id': 'minecraft:water', 'properties': {'level': '0'}},
	},
	'hot_spring_magma': {
		'type': 'minecraft:underwater_magma',
		'floor_search_range': 5,
		'placement_probability_per_valid_position': 0.8,
		'placement_radius_around_floor': 1,
	},
}

PLACED = {
	'tundra_boulder': placed(NS + ':andesite_boulder', [rarity(3), SQUARE, heightmap('MOTION_BLOCKING'), BIOME]),
	'reach_boulder': placed(NS + ':tuff_boulder', [count(1), SQUARE, heightmap('MOTION_BLOCKING'), BIOME]),
	'basalt_boulder': placed(NS + ':basalt_boulder', [rarity(2), SQUARE, heightmap('MOTION_BLOCKING'), BIOME]),
	'ash_boulder': placed(NS + ':blackstone_boulder', [rarity(3), SQUARE, heightmap('MOTION_BLOCKING'), BIOME]),
	'tundra_pines': trees('minecraft:trees_taiga', weighted_count((0, 7), (1, 2), (2, 1)), 'minecraft:spruce_sapling'),
	'reach_pines': trees('minecraft:trees_taiga', weighted_count((0, 4), (1, 1)), 'minecraft:spruce_sapling'),
	'eastmarch_pines': trees('minecraft:trees_snowy', weighted_count((0, 5), (1, 2)), 'minecraft:spruce_sapling'),
	'pale_spruces': trees('minecraft:trees_snowy', weighted_count((0, 4), (1, 3), (2, 1)), 'minecraft:spruce_sapling'),
	'falkreath_spruces': trees('minecraft:trees_old_growth_spruce_taiga', weighted_count((13, 9), (15, 1)), 'minecraft:spruce_sapling'),
	'rift_birches': trees('minecraft:birch_leaf_litter', weighted_count((0, 3), (1, 2), (2, 1)), 'minecraft:birch_sapling'),
	'falkreath_ferns': patch('minecraft:taiga_grass', [count(7)], 32),
	'tundra_grass': patch(NS + ':tundra_grass', [count(6)], 32),
	'eastmarch_grass': patch(NS + ':tundra_grass', [count(3)], 32),
	'tundra_flowers': patch(NS + ':tundra_flowers', [rarity(3)], 48),
	'reach_flowers': patch(NS + ':reach_flowers', [rarity(2)], 48),
	'hot_spring': placed(NS + ':hot_spring', [rarity(5), SQUARE, heightmap('WORLD_SURFACE_WG'), BIOME]),
	'hot_spring_magma': placed(NS + ':hot_spring_magma', [
		count(40), SQUARE,
		{'type': 'minecraft:height_range', 'height': {'type': 'minecraft:uniform', 'max_inclusive': {'absolute': 256}, 'min_inclusive': {'above_bottom': 0}}},
		{'type': 'minecraft:surface_relative_threshold_filter', 'heightmap': 'OCEAN_FLOOR_WG', 'max_inclusive': -2},
		BIOME,
	]),
	'ash_mushroom_groves': placed('minecraft:mushroom_island_vegetation', [count(6), SQUARE, heightmap('MOTION_BLOCKING'), BIOME]),
	# Home: classic oaks, one or two a chunk.
	'home_oaks': trees('minecraft:oak', weighted_count((0, 3), (1, 4), (2, 2), (3, 1)), 'minecraft:oak_sapling'),
}

# Global order per decoration step. Every biome lists a subsequence of these, so the feature sorter
# never sees two biomes disagree on order.
ORES = [
	'minecraft:ore_dirt', 'minecraft:ore_gravel', 'minecraft:ore_granite_upper', 'minecraft:ore_granite_lower',
	'minecraft:ore_diorite_upper', 'minecraft:ore_diorite_lower', 'minecraft:ore_andesite_upper', 'minecraft:ore_andesite_lower',
	'minecraft:ore_tuff', 'minecraft:ore_coal_upper', 'minecraft:ore_coal_lower', 'minecraft:ore_iron_upper', 'minecraft:ore_iron_middle',
	'minecraft:ore_iron_small', 'minecraft:ore_gold', 'minecraft:ore_gold_lower', 'minecraft:ore_redstone', 'minecraft:ore_redstone_lower',
	'minecraft:ore_diamond', 'minecraft:ore_diamond_medium', 'minecraft:ore_diamond_large', 'minecraft:ore_diamond_buried',
	'minecraft:ore_lapis', 'minecraft:ore_lapis_buried', 'minecraft:ore_copper', 'minecraft:ore_emerald', 'minecraft:underwater_magma',
	'skycraft:hot_spring_magma', 'minecraft:disk_sand', 'minecraft:disk_clay', 'minecraft:disk_gravel',
]
STEPS = [
	[],
	['minecraft:lake_lava_underground', 'minecraft:lake_lava_surface', 'skycraft:hot_spring'],
	['minecraft:iceberg_packed', 'minecraft:iceberg_blue', 'minecraft:amethyst_geode', 'minecraft:forest_rock', 'skycraft:tundra_boulder',
		'skycraft:reach_boulder', 'skycraft:basalt_boulder', 'skycraft:ash_boulder'],
	['minecraft:monster_room', 'minecraft:monster_room_deep'],
	['minecraft:blue_ice'],
	[],
	ORES,
	[],
	['minecraft:spring_water', 'minecraft:spring_lava', 'minecraft:spring_lava_frozen'],
	['minecraft:glow_lichen', 'skycraft:falkreath_spruces', 'minecraft:trees_dappled_forest', 'skycraft:rift_birches', 'skycraft:pale_spruces',
		'skycraft:eastmarch_pines', 'skycraft:tundra_pines', 'skycraft:reach_pines', 'skycraft:home_oaks', 'skycraft:ash_mushroom_groves',
		'minecraft:patch_large_fern', 'skycraft:falkreath_ferns', 'minecraft:patch_leaf_litter', 'minecraft:patch_red_shrub',
		'minecraft:patch_grass_forest', 'skycraft:tundra_grass', 'skycraft:eastmarch_grass', 'minecraft:patch_grass_plain',
		'minecraft:patch_grass_normal', 'skycraft:tundra_flowers', 'skycraft:reach_flowers', 'minecraft:flower_default',
		'minecraft:brown_mushroom_taiga', 'minecraft:red_mushroom_taiga', 'minecraft:brown_mushroom_normal', 'minecraft:red_mushroom_normal',
		'minecraft:patch_berry_common', 'minecraft:patch_sugar_cane', 'minecraft:seagrass_river', 'minecraft:kelp_cold', 'minecraft:seagrass_cold'],
	['minecraft:freeze_top_layer'],
]


def feature_steps(*ids):
	want = set(ids)
	missing = want - {f for step in STEPS for f in step}
	assert not missing, missing
	return [[f for f in step if f in want] for step in STEPS]


LAND_BASE = (
	['minecraft:lake_lava_underground', 'minecraft:amethyst_geode', 'minecraft:monster_room', 'minecraft:monster_room_deep',
		'minecraft:spring_water', 'minecraft:spring_lava', 'minecraft:glow_lichen', 'minecraft:freeze_top_layer'] + ORES[:-5] + ORES[-3:]
)


# ----------------------------------------------------------------------------------------------
# Biomes

def mob(t, w, n, m=None):
	return {'type': 'minecraft:' + t, 'count': n if m is None else {'type': 'minecraft:uniform', 'max_inclusive': m, 'min_inclusive': n}, 'weight': w}


MONSTERS = [mob('spider', 100, 4), mob('zombie', 95, 4), mob('zombie_villager', 5, 1), mob('skeleton', 100, 4), mob('creeper', 100, 4),
	mob('enderman', 10, 1, 4), mob('witch', 5, 1)]
COLD_MONSTERS = [mob('spider', 100, 4), mob('zombie', 95, 4), mob('skeleton', 20, 4), mob('stray', 80, 4), mob('creeper', 100, 4),
	mob('enderman', 10, 1, 4), mob('witch', 5, 1)]


def spawns(creature, monster=MONSTERS, water=None):
	cats = {'ambient': [mob('bat', 10, 8)], 'monster': monster}
	if creature:
		cats['creature'] = creature
	if water:
		cats['water_creature'] = water
	return {'argument': {'spawn_costs': {}, 'spawns_by_category': cats}, 'modifier': 'overlay'}


def music(sound):
	return {'default': {'max_delay': 24000, 'min_delay': 12000, 'sound': sound}}


CARVERS = ['minecraft:cave', 'minecraft:cave_extra_underground', 'minecraft:canyon']


def biome(temperature, downfall, colors, features, creature, monster=MONSTERS, sound=None, extra_attributes=None, frozen=False, water=None, carvers=CARVERS):
	attributes = {}
	if sound:
		attributes['minecraft:audio/background_music'] = music(sound)
	attributes['minecraft:gameplay/natural_mob_spawns'] = spawns(creature, monster, water)
	attributes['minecraft:visual/sky_color'] = colors['sky']
	attributes['minecraft:visual/fog_color'] = colors['fog']
	attributes['minecraft:visual/water_fog_color'] = colors.get('water_fog', '#050533')
	attributes.update(extra_attributes or {})
	effects = {'water_color': colors['water']}
	for key in ('grass', 'foliage', 'dry_foliage'):
		if key in colors:
			effects[key + '_color'] = colors[key]
	out = {
		'attributes': attributes,
		'carvers': carvers,
		'downfall': downfall,
		'effects': effects,
		'features': feature_steps(*features),
		'has_precipitation': True,
		'temperature': temperature,
	}
	if frozen:
		out['temperature_modifier'] = 'frozen'
	return out


PASTURE = [mob('sheep', 12, 4), mob('cow', 8, 4), mob('chicken', 10, 4), mob('pig', 10, 4)]
WILD = [mob('rabbit', 4, 2, 3), mob('fox', 8, 2, 4), mob('wolf', 8, 4)]
COLD = [mob('rabbit', 4, 2, 3), mob('fox', 8, 2, 4), mob('wolf', 6, 4), mob('polar_bear', 1, 1, 2)]


def tamriel_biomes():
	b = {}
	b['whiterun_tundra'] = biome(0.4, 0.2,
		{'grass': '#c2b25a', 'foliage': '#90a14c', 'dry_foliage': '#a6893e', 'water': '#3d6aa1', 'water_fog': '#0a2a4a', 'sky': '#86a9e2', 'fog': '#cfd8e0'},
		LAND_BASE + ['skycraft:tundra_boulder', 'skycraft:tundra_pines', 'skycraft:tundra_grass', 'minecraft:patch_grass_plain',
			'skycraft:tundra_flowers', 'minecraft:patch_sugar_cane'],
		PASTURE + [mob('horse', 2, 2, 4), mob('rabbit', 4, 2, 3)], sound='minecraft:music.overworld.meadow')
	b['falkreath_pines'] = biome(0.25, 0.8,
		{'grass': '#4d7a36', 'foliage': '#3d6a2c', 'water': '#2f5a6b', 'water_fog': '#0b2430', 'sky': '#7f9cc6', 'fog': '#a9b8b2'},
		LAND_BASE + ['minecraft:forest_rock', 'skycraft:falkreath_spruces', 'minecraft:patch_large_fern', 'skycraft:falkreath_ferns',
			'minecraft:brown_mushroom_taiga', 'minecraft:red_mushroom_taiga', 'minecraft:patch_berry_common'],
		[mob('wolf', 8, 4)] + WILD[:2] + [mob('pig', 6, 4)], sound='minecraft:music.overworld.old_growth_taiga')
	b['the_rift'] = biome(0.55, 0.6,
		{'grass': '#c6973a', 'foliage': '#e2812c', 'dry_foliage': '#9a4612', 'water': '#3a6c8a', 'water_fog': '#0d2a3a', 'sky': '#89aee8', 'fog': '#dacfba'},
		LAND_BASE + ['minecraft:trees_dappled_forest', 'skycraft:rift_birches', 'minecraft:patch_leaf_litter', 'minecraft:patch_red_shrub',
			'minecraft:patch_grass_forest', 'minecraft:brown_mushroom_normal', 'minecraft:patch_sugar_cane'],
		PASTURE[:2] + WILD, sound='minecraft:music.overworld.forest')
	b['eastmarch_springs'] = biome(0.2, 0.4,
		{'grass': '#a3a45c', 'foliage': '#7e904a', 'water': '#4f9a8c', 'water_fog': '#1d4a44', 'sky': '#8aa7d2', 'fog': '#d9d4ae'},
		LAND_BASE + ['skycraft:hot_spring', 'skycraft:hot_spring_magma', 'skycraft:basalt_boulder', 'skycraft:eastmarch_pines',
			'skycraft:eastmarch_grass'],
		COLD[:3] + [mob('goat', 5, 1, 3)], monster=COLD_MONSTERS, sound='minecraft:music.overworld.snowy_slopes')
	b['the_reach'] = biome(0.35, 0.3,
		{'grass': '#5e7c44', 'foliage': '#4c6a36', 'water': '#3a5f7a', 'water_fog': '#0d2433', 'sky': '#8aa5ca', 'fog': '#bcbfbf'},
		LAND_BASE + ['skycraft:reach_boulder', 'skycraft:reach_pines', 'minecraft:patch_grass_normal', 'skycraft:reach_flowers'],
		[mob('goat', 8, 1, 3)] + WILD[:2], sound='minecraft:music.overworld.stony_peaks')
	b['the_pale'] = biome(-0.5, 0.5,
		{'grass': '#80a69b', 'foliage': '#60866e', 'water': '#3d57d6', 'water_fog': '#050533', 'sky': '#a6b9e2', 'fog': '#e2e8f0'},
		LAND_BASE + ['skycraft:pale_spruces'],
		COLD, monster=COLD_MONSTERS, sound='minecraft:music.overworld.snowy_slopes')
	b['winterhold_peaks'] = biome(-0.7, 0.9,
		{'grass': '#80a69b', 'foliage': '#60866e', 'water': '#3938c9', 'water_fog': '#050533', 'sky': '#b5c5ec', 'fog': '#eaf0f8'},
		LAND_BASE + ['minecraft:spring_lava_frozen'],
		[mob('goat', 5, 1, 3)], monster=COLD_MONSTERS, sound='minecraft:music.overworld.frozen_peaks')
	b['solstheim_ash'] = biome(0.8, 0.1,
		{'grass': '#8a8a6c', 'foliage': '#7a7a5e', 'water': '#5a6a70', 'water_fog': '#1f262a', 'sky': '#a9a39c', 'fog': '#9d968e'},
		LAND_BASE + ['minecraft:lake_lava_surface', 'skycraft:ash_boulder', 'skycraft:ash_mushroom_groves', 'minecraft:brown_mushroom_normal',
			'minecraft:red_mushroom_normal'],
		[mob('mooshroom', 4, 2, 3)], sound='minecraft:music.nether.basalt_deltas',
		extra_attributes={'minecraft:visual/ambient_particles': {
			'argument': [{'particle': {'type': 'minecraft:white_ash'}, 'probability': 0.02}], 'modifier': 'append'}})
	b['tamriel_river'] = biome(0.3, 0.5,
		{'grass': '#7c9a4e', 'foliage': '#5f8a3e', 'water': '#3a6d9e', 'water_fog': '#0b2440', 'sky': '#87a8e0', 'fog': '#cdd6de'},
		LAND_BASE + ['minecraft:patch_grass_normal', 'minecraft:patch_sugar_cane', 'minecraft:seagrass_river'],
		[], water=[mob('salmon', 5, 1, 5)])
	b['sea_of_ghosts'] = biome(0.0, 0.5,
		{'water': '#2b4a6b', 'water_fog': '#0e1c2c', 'sky': '#8fa7cd', 'fog': '#c6d1dd'},
		['minecraft:iceberg_packed', 'minecraft:iceberg_blue', 'minecraft:blue_ice', 'minecraft:amethyst_geode', 'minecraft:monster_room',
			'minecraft:monster_room_deep', 'minecraft:spring_water', 'minecraft:kelp_cold', 'minecraft:seagrass_cold',
			'minecraft:freeze_top_layer'] + ORES[:-5] + ORES[-3:],
		[], water=[mob('salmon', 15, 1, 5), mob('cod', 15, 3, 6), mob('squid', 3, 1, 4)], frozen=True)
	return b


def home_biome():
	# Steve's old seed: plain old grass, oaks and flowers; colors close to the old fixed ones.
	return biome(0.8, 0.4,
		{'grass': '#6dbb48', 'foliage': '#57a52f', 'water': '#3f76e4', 'water_fog': '#050533', 'sky': '#7ba4ff', 'fog': '#c0d8ff'},
		['minecraft:lake_lava_underground', 'minecraft:spring_water', 'minecraft:spring_lava', 'skycraft:home_oaks', 'minecraft:patch_grass_plain',
			'minecraft:flower_default', 'minecraft:patch_sugar_cane', 'minecraft:freeze_top_layer'] + ORES[:-5] + ORES[-3:],
		[mob('sheep', 12, 4), mob('pig', 10, 4), mob('chicken', 10, 4), mob('cow', 8, 4)], sound=None, carvers=[])


# ----------------------------------------------------------------------------------------------
# Biome source: disjoint climate boxes (temperature, humidity, continentalness, erosion, weirdness).

PARAMS = ('temperature', 'humidity', 'continentalness', 'erosion', 'weirdness')
FULL = (-1.0, 1.0)


def box(t=FULL, h=FULL, c=(-0.19, 1.0), e=FULL, w=FULL):
	return [t, h, c, e, w]


RIVER = box(c=(-0.11, 0.55), e=(-0.2225, 1.0), w=(-0.05, 0.05))


def subtract(a, b):
	"""Splits box a into disjoint boxes covering a minus b."""
	inter = [(max(x[0], y[0]), min(x[1], y[1])) for x, y in zip(a, b)]
	if any(lo >= hi for lo, hi in inter):
		return [a]
	out = []
	rest = list(a)
	for i in range(len(a)):
		lo, hi = rest[i]
		if lo < inter[i][0]:
			piece = list(rest)
			piece[i] = (lo, inter[i][0])
			out.append(piece)
		if inter[i][1] < hi:
			piece = list(rest)
			piece[i] = (inter[i][1], hi)
			out.append(piece)
		rest[i] = inter[i]
	return out


def climate_entries():
	land = [
		('winterhold_peaks', box(c=(-0.11, 1.0), e=(-1.0, -0.78))),
		('winterhold_peaks', box(t=(-1.0, -0.15), c=(-0.11, 1.0), e=(-0.78, -0.375))),
		('the_pale', box(t=(-1.0, -0.45), e=(-0.375, 1.0))),
		('eastmarch_springs', box(t=(-0.45, -0.15), e=(-0.375, 1.0))),
		('whiterun_tundra', box(t=(-0.15, 0.7), h=(-1.0, -0.1), e=(-0.2225, 1.0))),
		('the_reach', box(t=(-0.15, 0.7), h=(-1.0, -0.1), e=(-0.78, -0.2225))),
		('falkreath_pines', box(t=(-0.15, 0.2), h=(-0.1, 1.0), e=(-0.78, 1.0))),
		('the_rift', box(t=(0.2, 0.7), h=(-0.1, 1.0), e=(-0.78, 1.0))),
		('solstheim_ash', box(t=(0.7, 1.0), e=(-0.78, 1.0))),
	]
	# Land starts at continentalness -0.19, so the coast strip takes the land biome behind it; peaks start
	# inland at -0.11. The river box is cut out of every land box.
	entries = [('sea_of_ghosts', box(c=(-1.2, -0.19)))]
	for name, bx in land:
		for piece in subtract(bx, RIVER):
			entries.append((name, piece))
	entries.append(('tamriel_river', RIVER))
	out = []
	for name, bx in entries:
		params = {p: [round(lo, 4), round(hi, 4)] for p, (lo, hi) in zip(PARAMS, bx)}
		params['depth'] = 0.0
		params['offset'] = 0.0
		out.append({'biome': NS + ':' + name, 'parameters': params})
	return out


# ----------------------------------------------------------------------------------------------
# Dimensions

def overworld_like_type(min_y, height, sky, fog, cloud_height):
	return {
		'ambient_light': 0.0,
		'attributes': {
			'minecraft:audio/ambient_sounds': {'mood': {'block_search_extent': 8, 'offset': 2.0, 'sound': 'minecraft:ambient.cave', 'tick_delay': 6000}},
			'minecraft:audio/background_music': {
				'creative': {'max_delay': 24000, 'min_delay': 12000, 'sound': 'minecraft:music.creative'},
				'default': {'max_delay': 24000, 'min_delay': 12000, 'sound': 'minecraft:music.game'},
			},
			'minecraft:gameplay/bed_rule': {'can_set_spawn': 'always', 'can_sleep': 'when_dark', 'error_message': {'translate': 'block.minecraft.bed.no_sleep'}},
			'minecraft:gameplay/respawn_anchor_works': False,
			'minecraft:visual/ambient_light_color': '#0a0a0a',
			'minecraft:visual/cloud_color': '#ccffffff',
			'minecraft:visual/cloud_height': cloud_height,
			'minecraft:visual/fog_color': fog,
			'minecraft:visual/sky_color': sky,
		},
		'coordinate_scale': 1.0,
		'default_clock': 'minecraft:overworld',
		'has_ceiling': False,
		'has_ender_dragon_fight': False,
		'has_skylight': True,
		'height': height,
		'infiniburn': '#minecraft:infiniburn_overworld',
		'logical_height': height,
		'min_y': min_y,
		'monster_spawn_block_light_limit': 0,
		'monster_spawn_light_level': {'type': 'minecraft:uniform', 'max_inclusive': 7, 'min_inclusive': 0},
		'timelines': '#minecraft:in_overworld',
	}


def dimensions():
	# The mirror world preset has only an overworld; a data pack dimension under the vanilla key adds
	# the Nether to existing worlds too (WorldDimensions.bake merges data pack stems with saved ones).
	write_json('minecraft/dimension/the_nether.json', {
		'type': 'minecraft:the_nether',
		'generator': {
			'type': 'minecraft:noise',
			'biome_source': {'type': 'minecraft:multi_noise', 'preset': 'minecraft:nether'},
			'settings': 'minecraft:nether',
		},
	})
	write_json(NS + '/dimension_type/tamriel_wilds.json', overworld_like_type(-64, 384, '#86a9e2', '#cfd8e0', 192.33))
	write_json(NS + '/dimension/tamriel_wilds.json', {
		'type': NS + ':tamriel_wilds',
		'generator': {
			'type': 'minecraft:noise',
			'biome_source': {'type': 'minecraft:multi_noise', 'biomes': climate_entries()},
			'settings': NS + ':tamriel_wilds',
		},
	})
	write_json(NS + '/dimension_type/home.json', overworld_like_type(0, 256, '#7ba4ff', '#c0d8ff', 108.33))
	write_json(NS + '/dimension/home.json', {
		'type': NS + ':home',
		'generator': {
			'type': NS + ':classic',
			'biome_source': {'type': 'minecraft:fixed', 'biome': NS + ':home_fields'},
		},
	})


# ----------------------------------------------------------------------------------------------
# Structure templates (NBT), written without any library.

def _tag(kind):
	return {'byte': 1, 'short': 2, 'int': 3, 'long': 4, 'float': 5, 'double': 6, 'string': 8, 'list': 9, 'compound': 10}[kind]


class Tag:
	def __init__(self, kind, value, elem=None):
		self.kind, self.value, self.elem = kind, value, elem


def _payload(out, t):
	k, v = t.kind, t.value
	if k == 'byte':
		out.write(struct.pack('>b', v))
	elif k == 'short':
		out.write(struct.pack('>h', v))
	elif k == 'int':
		out.write(struct.pack('>i', v))
	elif k == 'long':
		out.write(struct.pack('>q', v))
	elif k == 'float':
		out.write(struct.pack('>f', v))
	elif k == 'double':
		out.write(struct.pack('>d', v))
	elif k == 'string':
		b = v.encode('utf-8')
		out.write(struct.pack('>H', len(b)))
		out.write(b)
	elif k == 'list':
		out.write(struct.pack('>b', _tag(t.elem) if v else 0))
		out.write(struct.pack('>i', len(v)))
		for item in v:
			_payload(out, item)
	elif k == 'compound':
		for name, item in v.items():
			out.write(struct.pack('>b', _tag(item.kind)))
			nb = name.encode('utf-8')
			out.write(struct.pack('>H', len(nb)))
			out.write(nb)
			_payload(out, item)
		out.write(b'\x00')


def nbt(value):
	"""Python value to Tag: dict->compound, str->string, int->int, list of ints->list of int, list of dicts->list of compound."""
	if isinstance(value, Tag):
		return value
	if isinstance(value, dict):
		return Tag('compound', {k: nbt(v) for k, v in value.items()})
	if isinstance(value, str):
		return Tag('string', value)
	if isinstance(value, bool):
		return Tag('byte', 1 if value else 0)
	if isinstance(value, int):
		return Tag('int', value)
	if isinstance(value, list):
		items = [nbt(v) for v in value]
		return Tag('list', items, items[0].kind if items else 'compound')
	raise TypeError(value)


def write_nbt(rel, root):
	buf = io.BytesIO()
	buf.write(b'\x0a\x00\x00')
	_payload(buf, nbt(root))
	path = os.path.join(OUT, 'data', rel)
	os.makedirs(os.path.dirname(path), exist_ok=True)
	with open(path, 'wb') as f:
		# Fixed mtime keeps the output byte-for-byte stable.
		with gzip.GzipFile(fileobj=f, mode='wb', mtime=0) as g:
			g.write(buf.getvalue())


class Template:
	"""Voxels keyed by (x, y, z). AIR clears terrain; positions never set keep the terrain."""

	def __init__(self):
		self.blocks = {}
		self.nbt = {}

	def set(self, x, y, z, state, props=None, data=None):
		self.blocks[(x, y, z)] = (state, tuple(sorted((props or {}).items())))
		if data is not None:
			self.nbt[(x, y, z)] = data
		elif (x, y, z) in self.nbt:
			del self.nbt[(x, y, z)]

	def fill(self, x0, y0, z0, x1, y1, z1, state, props=None):
		for x in range(min(x0, x1), max(x0, x1) + 1):
			for y in range(min(y0, y1), max(y0, y1) + 1):
				for z in range(min(z0, z1), max(z0, z1) + 1):
					self.set(x, y, z, state, props)

	def save(self, rel):
		xs = [p[0] for p in self.blocks]
		ys = [p[1] for p in self.blocks]
		zs = [p[2] for p in self.blocks]
		mx, my, mz = min(xs), min(ys), min(zs)
		size = [max(xs) - mx + 1, max(ys) - my + 1, max(zs) - mz + 1]
		palette = []
		index = {}
		blocks = []
		for pos in sorted(self.blocks, key=lambda p: (p[1], p[2], p[0])):
			key = self.blocks[pos]
			if key not in index:
				index[key] = len(palette)
				entry = {'id': key[0]}
				if key[1]:
					entry['properties'] = dict(key[1])
				palette.append(entry)
			b = {'pos': [pos[0] - mx, pos[1] - my, pos[2] - mz], 'state': index[key]}
			if pos in self.nbt:
				b['nbt'] = self.nbt[pos]
			blocks.append(b)
		write_nbt(rel, {'size': size, 'entities': Tag('list', [], 'compound'), 'blocks': blocks, 'palette': palette, 'DataVersion': DATA_VERSION})
		return size


def dwemer_tower():
	"""A Dwemer bronze tower: waxed cut copper walls with weathered bands, lit lamps, a ladder to a domed top."""
	t = Template()
	r = 4.6
	height = 24

	def inside(x, z, rad):
		return (x - 5) ** 2 + (z - 5) ** 2 <= rad * rad

	# Foundation two below the surface, and a polished stone plinth.
	for x in range(11):
		for z in range(11):
			if inside(x, z, r + 0.6):
				t.fill(x, 0, z, x, 1, z, 'minecraft:polished_andesite')
	for y in range(2, height):
		for x in range(11):
			for z in range(11):
				if not inside(x, z, r):
					continue
				wall = not inside(x, z, r - 1.2)
				if not wall:
					t.set(x, y, z, 'minecraft:air')
					continue
				band = (y - 2) % 6
				if band == 0:
					t.set(x, y, z, 'minecraft:waxed_weathered_cut_copper')
				elif band == 3 and (x in (0, 10) or z in (0, 10)):
					t.set(x, y, z, 'minecraft:waxed_copper_grate')
				else:
					t.set(x, y, z, 'minecraft:waxed_cut_copper')
	# Floors every six blocks, with a ladder shaft at the north wall.
	for fy in (8, 14, 20):
		for x in range(11):
			for z in range(11):
				if inside(x, z, r - 1.2):
					t.set(x, fy, z, 'minecraft:waxed_cut_copper_slab', {'type': 'top', 'waterlogged': 'false'})
		t.set(5, fy, 2, 'minecraft:air')
	t.fill(2, 2, 5, 8, 2, 5, 'minecraft:polished_andesite')
	t.fill(4, 2, 4, 6, 2, 6, 'minecraft:polished_andesite')
	for y in range(3, height - 1):
		t.set(5, y, 2, 'minecraft:ladder', {'facing': 'south', 'waterlogged': 'false'})
	# Doorway on the south side.
	t.fill(5, 3, 9, 5, 5, 10, 'minecraft:air')
	t.fill(4, 3, 10, 4, 5, 10, 'minecraft:waxed_weathered_cut_copper')
	t.fill(6, 3, 10, 6, 5, 10, 'minecraft:waxed_weathered_cut_copper')
	t.set(5, 6, 10, 'minecraft:waxed_weathered_cut_copper')
	# Lamps: redstone lamps kept lit by a redstone block set in the wall behind them, and copper bulbs.
	for (lx, lz, bx, bz) in ((1, 5, 0, 5), (9, 5, 10, 5)):
		for ly in (5, 11, 17):
			t.set(bx, ly, bz, 'minecraft:redstone_block')
			t.set(lx, ly, lz, 'minecraft:redstone_lamp', {'lit': 'true'})
	for ly in (6, 12, 18):
		t.set(5, ly, 8, 'minecraft:waxed_copper_bulb', {'lit': 'true', 'powered': 'false'})
	# Dome and spire.
	for y in range(height, height + 4):
		rad = r - (y - height + 1) * 1.1
		for x in range(11):
			for z in range(11):
				if inside(x, z, rad):
					t.set(x, y, z, 'minecraft:waxed_copper_block' if y < height + 3 else 'minecraft:waxed_exposed_copper')
	t.set(5, height + 4, 5, 'minecraft:lightning_rod', {'facing': 'up', 'powered': 'false', 'waterlogged': 'false'})
	# A strongbox on the top floor.
	t.set(7, 21, 5, 'minecraft:chest', {'facing': 'west', 'type': 'single', 'waterlogged': 'false'},
		{'id': 'minecraft:chest', 'LootTable': 'minecraft:chests/simple_dungeon'})
	return t


def nordic_barrow():
	"""A Nordic burial mound: a grassy barrow with a stone doorway, a small crypt, an urn and a loot chest."""
	t = Template()
	cx, cz = 7, 7
	for x in range(15):
		for z in range(15):
			d = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
			top = int(round(6.2 - d * 0.85))
			if top < 0:
				continue
			for y in range(0, top + 1):
				t.set(x, y, z, 'minecraft:grass_block' if y == top else 'minecraft:dirt')
	# Crypt inside: stone brick shell around an air room.
	t.fill(4, 0, 4, 10, 5, 10, 'minecraft:stone_bricks')
	for (x, y, z) in ((4, 2, 6), (10, 3, 8), (6, 5, 4), (8, 0, 10), (4, 4, 9)):
		t.set(x, y, z, 'minecraft:mossy_stone_bricks')
	t.set(10, 1, 5, 'minecraft:cracked_stone_bricks')
	t.fill(5, 1, 5, 9, 4, 9, 'minecraft:air')
	t.fill(5, 0, 5, 9, 0, 9, 'minecraft:cobblestone')
	# Sarcophagus.
	t.fill(6, 1, 6, 8, 1, 6, 'minecraft:stone_brick_slab', {'type': 'bottom', 'waterlogged': 'false'})
	t.set(7, 2, 6, 'minecraft:skeleton_skull', {'rotation': '0', 'powered': 'false'})
	t.set(5, 1, 5, 'minecraft:decorated_pot', {'cracked': 'false', 'facing': 'south', 'waterlogged': 'false'})
	t.set(9, 1, 5, 'minecraft:candle', {'candles': '3', 'lit': 'true', 'waterlogged': 'false'})
	t.set(5, 4, 9, 'minecraft:cobweb')
	t.set(9, 4, 6, 'minecraft:cobweb')
	t.set(9, 1, 8, 'minecraft:chest', {'facing': 'west', 'type': 'single', 'waterlogged': 'false'},
		{'id': 'minecraft:chest', 'LootTable': 'minecraft:chests/simple_dungeon'})
	# Doorway and short passage on the south face.
	t.fill(7, 1, 10, 7, 3, 14, 'minecraft:air')
	t.fill(6, 1, 11, 6, 3, 14, 'minecraft:stone_bricks')
	t.fill(8, 1, 11, 8, 3, 14, 'minecraft:stone_bricks')
	t.fill(6, 4, 11, 8, 4, 14, 'minecraft:stone_bricks')
	t.fill(7, 0, 10, 7, 0, 14, 'minecraft:cobblestone')
	t.set(6, 4, 14, 'minecraft:chiseled_stone_bricks')
	t.set(8, 4, 14, 'minecraft:chiseled_stone_bricks')
	t.set(7, 4, 14, 'minecraft:spruce_log', {'axis': 'x'})
	t.set(7, 1, 10, 'minecraft:iron_bars', {'east': 'false', 'north': 'false', 'south': 'false', 'west': 'false', 'waterlogged': 'false'})
	return t


STRUCTURES = {
	'dwemer_tower': {
		'build': dwemer_tower,
		'biomes': ['the_reach', 'eastmarch_springs', 'whiterun_tundra', 'the_rift', 'the_pale'],
		'spacing': 40, 'separation': 14, 'salt': 1712026931,
		'adaptation': 'beard_thin', 'start_height': -2,
	},
	'nordic_barrow': {
		'build': nordic_barrow,
		'biomes': ['whiterun_tundra', 'falkreath_pines', 'the_rift', 'the_pale', 'the_reach', 'eastmarch_springs'],
		'spacing': 28, 'separation': 10, 'salt': 1405181777,
		'adaptation': 'beard_thin', 'start_height': 0,
	},
}


def structures():
	for name, s in STRUCTURES.items():
		size = s['build']().save(NS + '/structure/%s.nbt' % name)
		write_json(NS + '/worldgen/template_pool/%s.json' % name, {
			'elements': [{
				'element': {
					'element_type': 'minecraft:single_pool_element',
					'location': NS + ':' + name,
					'processors': {'processors': []},
					'projection': 'rigid',
				},
				'weight': 1,
			}],
			'fallback': 'minecraft:empty',
		})
		write_json(NS + '/worldgen/structure/%s.json' % name, {
			'type': 'minecraft:jigsaw',
			'biomes': '#%s:has_structure/%s' % (NS, name),
			'max_distance_from_center': 80,
			'project_start_to_heightmap': 'WORLD_SURFACE_WG',
			'size': 1,
			'spawn_overrides': {},
			'start_height': {'absolute': s['start_height']},
			'start_pool': NS + ':' + name,
			'step': 'surface_structures',
			'terrain_adaptation': s['adaptation'],
			'use_expansion_hack': False,
		})
		write_json(NS + '/worldgen/structure_set/%s.json' % name, {
			'placement': {'type': 'minecraft:random_spread', 'salt': s['salt'], 'separation': s['separation'], 'spacing': s['spacing']},
			'structures': [{'structure': NS + ':' + name, 'weight': 1}],
		})
		write_json(NS + '/tags/worldgen/biome/has_structure/%s.json' % name, {'values': [NS + ':' + b for b in s['biomes']]})
		print('structure', name, 'size', size)
	# Vanilla structures that suit the Wilds: mineshafts under every land biome, old camps in the forests,
	# ruined portals (Oblivion's touch) in the mountains.
	land = ['whiterun_tundra', 'falkreath_pines', 'the_rift', 'eastmarch_springs', 'the_reach', 'the_pale', 'winterhold_peaks', 'solstheim_ash']
	write_json('minecraft/tags/worldgen/biome/has_structure/mineshaft.json', {'replace': False, 'values': [NS + ':' + b for b in land]})
	write_json('minecraft/tags/worldgen/biome/has_structure/abandoned_camp_old_growth_spruce_taiga.json', {'replace': False, 'values': [NS + ':falkreath_pines']})
	write_json('minecraft/tags/worldgen/biome/has_structure/abandoned_camp_dappled_forest.json', {'replace': False, 'values': [NS + ':the_rift']})
	write_json('minecraft/tags/worldgen/biome/has_structure/ruined_portal_mountain.json', {'replace': False, 'values': [NS + ':the_reach', NS + ':winterhold_peaks']})


def main():
	global OUT
	ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	ap.add_argument('--jar', required=True, help='minecraft-26.3-client.jar (for the vanilla amplified density functions)')
	ap.add_argument('--out', default=os.path.join(os.path.dirname(__file__), '..', 'fabric', 'src', 'main', 'resources'))
	args = ap.parse_args()
	OUT = os.path.normpath(args.out)
	dimensions()
	tamriel_density(args.jar)
	write_json(NS + '/worldgen/material_rule/tamriel_wilds.json', tamriel_material_rule())
	write_json(NS + '/worldgen/material_rule/tamriel_wilds/surface.json', tamriel_surface())
	write_json(NS + '/worldgen/material_rule/tamriel_wilds/stone.json', tamriel_stone())
	for name, f in FEATURES.items():
		write_json(NS + '/worldgen/feature/%s.json' % name, f)
	for name, p in PLACED.items():
		write_json(NS + '/worldgen/placed_feature/%s.json' % name, p)
	for name, b in tamriel_biomes().items():
		write_json(NS + '/worldgen/biome/%s.json' % name, b)
	write_json(NS + '/worldgen/biome/home_fields.json', home_biome())
	structures()
	print('wrote worlds data under', OUT)


if __name__ == '__main__':
	main()
