"""Generate the portal network's item icons: original 16x16 soul gems, the tonal key and Seamstone.

Drawn from simple shapes in code (no images are read), with the same bevelled pixel style as
tools/gen_gear_textures.py: a violet crystal with a white soul glowing inside (filled), and the same
gem with a purple portal swirl and sparks round it (attuned, the gem that remembers a portal); a
Dwemer bronze key with an amethyst in its bow, a redstone inlay down the shaft and two tuning teeth
(the tonal key); a shard of the Far Lands' layered stone split by a glowing violet seam (Seamstone).

    python tools/gen_portal_textures.py                 # writes into the fabric resources
    python tools/gen_portal_textures.py --preview x.png # also writes an enlarged preview

Needs Pillow (pip install pillow). Output is deterministic. Item ids must match
fabric/src/main/java/dev/skycraft/portal/PortalStore.java (ITEM_IDS).
"""
import argparse
import os

from PIL import Image

from gen_gear_textures import ITEM_DIR, Icon, rgb

GEM = dict(outline=rgb("2a1240"), dark=rgb("5b2a8c"), mid=rgb("8a4fd0"), light=rgb("c9a6ff"))
SOUL = rgb("f4f0ff")
SOUL_GLOW = rgb("d9c8ff")
SWIRL = rgb("b04bff")
SWIRL_DARK = rgb("6a1fb0")
SPARK = rgb("f0d0ff")


def gem_shape():
    """A cut soul gem: a pointed crown on top, a long faceted body, a point below."""
    ic = Icon()
    ic.rect(7, 1, 8, 1, "g")
    ic.rect(6, 2, 9, 2, "g")
    ic.rect(5, 3, 10, 3, "g")
    ic.rect(4, 4, 11, 9, "g")
    ic.rect(5, 10, 10, 10, "g")
    ic.rect(6, 11, 9, 11, "g")
    ic.rect(7, 12, 8, 13, "g")
    return ic


def filled_gem():
    img = gem_shape().render({"g": GEM})
    px = img.load()
    # Facet lines down the body.
    for y in range(4, 10):
        px[6, y] = GEM["light"] if y < 7 else GEM["mid"]
        px[9, y] = GEM["dark"]
    # The soul: a bright core with a soft halo.
    for x, y in ((7, 6), (8, 6), (7, 7), (8, 7)):
        px[x, y] = SOUL
    for x, y in ((7, 5), (8, 5), (6, 6), (9, 6), (6, 7), (9, 7), (7, 8), (8, 8)):
        px[x, y] = SOUL_GLOW
    px[7, 2] = rgb("ffffff", 230)  # glint on the crown
    return img


def attuned_gem():
    img = filled_gem()
    px = img.load()
    # A portal swirl through the soul.
    for x, y in ((7, 6), (8, 7)):
        px[x, y] = SWIRL
    for x, y in ((8, 6), (7, 7)):
        px[x, y] = SOUL
    for x, y in ((6, 8), (9, 5), (5, 6), (10, 7)):
        px[x, y] = SWIRL_DARK
    # Sparks round the gem, like the particles a lit portal gives off.
    for x, y in ((2, 3), (13, 2), (1, 9), (14, 11), (3, 13), (12, 14)):
        px[x, y] = SPARK
    for x, y in ((2, 4), (13, 3), (14, 12)):
        px[x, y] = SWIRL
    return img


BRONZE = dict(outline=rgb("3a2410"), dark=rgb("7a4a1c"), mid=rgb("b07a34"), light=rgb("e0b060"))
AMETHYST = dict(dark=rgb("5e2f8e"), mid=rgb("9a5cc6"), light=rgb("e2c4ff"))
REDSTONE = rgb("d02020")
STONE = dict(outline=rgb("1c1b22"), dark=rgb("3e3d46"), mid=rgb("66656e"), light=rgb("a09fa8"))
STRATA = [rgb("5c5b65"), rgb("7a7883"), rgb("4b4652")]
SEAM_CORE = rgb("fff6ff")
SEAM_GLOW = rgb("c58cff")
SEAM_DEEP = rgb("7a3cc8")


def tonal_key():
    """A Dwemer key lying corner to corner: a round bow at the top left, the shaft, two teeth."""
    ic = Icon()
    for y in range(1, 8):
        for x in range(1, 8):
            if (x - 4) ** 2 + (y - 4) ** 2 <= 10:
                ic.put(x, y, "b")
    for t in range(8):
        ic.put(6 + t, 6 + t, "b")
        ic.put(7 + t, 6 + t, "b")
        ic.put(6 + t, 7 + t, "b")
    ic.rect(9, 12, 10, 13, "b")
    ic.rect(11, 14, 12, 15, "b")
    img = ic.render({"b": BRONZE})
    px = img.load()
    # The amethyst set in the bow, the tuning crystal.
    px[4, 4] = AMETHYST["light"]
    px[3, 4] = px[4, 3] = AMETHYST["mid"]
    px[5, 4] = px[4, 5] = AMETHYST["dark"]
    # Redstone inlay down the shaft.
    for t in (1, 3, 5):
        px[6 + t, 6 + t] = REDSTONE
    return img


def seamstone():
    """A shard of the Far Lands' strata, banded grey, split top to bottom by a glowing seam."""
    ic = Icon()
    rows = {2: (6, 9), 3: (4, 11), 4: (3, 12), 5: (3, 12), 6: (2, 12), 7: (2, 13), 8: (3, 13), 9: (3, 12),
            10: (4, 12), 11: (4, 11), 12: (5, 10), 13: (6, 9)}
    for y, (x0, x1) in rows.items():
        ic.rect(x0, y, x1, y, "s")
    img = ic.render({"s": STONE})
    px = img.load()
    for (x, y) in ic.cells:
        if px[x, y] == STONE["mid"]:
            px[x, y] = STRATA[(y // 2) % 3]
    seam = [(6, 3), (7, 4), (7, 5), (8, 6), (7, 7), (8, 8), (9, 9), (9, 10), (10, 11), (9, 12)]
    for x, y in seam:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q in ic.cells and q not in seam and px[q] != STONE["outline"]:
                px[q] = SEAM_GLOW if dx else SEAM_DEEP
    for x, y in seam:
        px[x, y] = SEAM_CORE
    for x, y in ((2, 2), (13, 3), (14, 10), (12, 14)):
        px[x, y] = SEAM_GLOW
    return img


ICONS = {
    "filled_soul_gem": filled_gem,
    "attuned_soul_gem": attuned_gem,
    "tonal_key": tonal_key,
    "seamstone": seamstone,
}


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--preview", help="also write an enlarged preview of the icons to this path")
    args = ap.parse_args()
    os.makedirs(ITEM_DIR, exist_ok=True)
    written = []
    for name, fn in ICONS.items():
        img = fn()
        img.save(os.path.join(ITEM_DIR, name + ".png"))
        written.append(img)
    print("wrote %d item textures" % len(written))
    if args.preview:
        scale = 12
        sheet = Image.new("RGBA", (len(written) * 18 * scale, 18 * scale), (70, 80, 90, 255))
        for i, img in enumerate(written):
            sheet.alpha_composite(img.resize((16 * scale, 16 * scale), Image.NEAREST), (i * 18 * scale + scale, scale))
        sheet.save(args.preview)
        print("preview:", args.preview)


if __name__ == "__main__":
    main()
