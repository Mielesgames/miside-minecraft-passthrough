#!/usr/bin/env python3
"""
Builds a standalone data pack that previews SkyCraft's worlds in a plain vanilla Minecraft 26.3 world,
without Skyrim and without the mod.

  python3 tools/build_worlds_preview.py [--out dist/skycraft-worlds-preview.zip]

What the pack holds: The Nether (for worlds that lack one) and Tamriel Wilds with its biomes, features,
surface rules and the Dwemer tower and Nordic barrow structures. Tamriel Wilds is pure data, so it runs as
is; without the mod it uses the world's own seed instead of the pinned lore seed, so create the world with
seed 4201 to see the same land as the mod (the tower by the arrival point stands at 91, 293).

Home (Steve's old seed and the Far Lands) is Java code, so it needs the mod and is left out.

Use it:
  1. Create a new world (Singleplayer, Create New World, More, Data Packs: drop the zip in, or copy it into
     <world>/datapacks before the first start). Structures need "Generate Structures" on.
  2. Visit:   /execute in skycraft:tamriel_wilds run tp @s 91.5 120 306.5 180 0
     Nether:  /execute in minecraft:the_nether run tp @s 0 80 0
     Find:    /execute in skycraft:tamriel_wilds run locate biome skycraft:the_rift
              /execute in skycraft:tamriel_wilds run locate structure skycraft:dwemer_tower
"""
import argparse
import json
import os
import zipfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..'))
DATA = os.path.join(ROOT, 'fabric', 'src', 'main', 'resources', 'data')
PACK_FORMAT = 121  # Minecraft 26.3 data pack format (version.json pack_version.data_major)

# Data folders the preview takes; everything else in the mod's data (recipes, item tags) needs the mod.
INCLUDE = (
	'minecraft/dimension/',
	'minecraft/tags/worldgen/',
	'skycraft/dimension/tamriel_wilds.json',
	'skycraft/dimension_type/tamriel_wilds.json',
	'skycraft/worldgen/',
	'skycraft/structure/',
	'skycraft/tags/worldgen/',
)
# Home's pieces that only the mod can load (its generator type is registered in Java), and the mirror
# world preset, whose dimension type is the mod's.
EXCLUDE = (
	'skycraft/worldgen/world_preset/',
	'skycraft/worldgen/biome/home_fields.json',
	'skycraft/worldgen/placed_feature/home_oaks.json',
)


def main():
	ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	ap.add_argument('--out', default=os.path.join(ROOT, 'dist', 'skycraft-worlds-preview.zip'))
	args = ap.parse_args()
	files = []
	for root, _, names in os.walk(DATA):
		for n in names:
			rel = os.path.relpath(os.path.join(root, n), DATA).replace(os.sep, '/')
			if rel.startswith(INCLUDE) and not rel.startswith(EXCLUDE):
				files.append(rel)
	files.sort()
	os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
	mcmeta = {
		'pack': {
			'description': 'SkyCraft worlds preview: Tamriel Wilds and The Nether (Home needs the mod)',
			'min_format': PACK_FORMAT,
			'max_format': PACK_FORMAT,
		}
	}
	with zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED) as z:
		z.writestr('pack.mcmeta', json.dumps(mcmeta, indent=2) + '\n')
		for rel in files:
			z.write(os.path.join(DATA, rel), 'data/' + rel)
	print('wrote %s (%d files)' % (args.out, len(files)))


if __name__ == '__main__':
	main()
