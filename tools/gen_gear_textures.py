"""Generate the SkyCraft gear set textures: original 16x16 item icons and 64x32 armor layers.

Everything is drawn from simple shapes in code (no images are read), one palette per tier:
iron grey, steel blue grey, orcish dark green, dwarven bronze, elven gold, glass green,
ebony near black with a sheen, daedric black and red.

    python tools/gen_gear_textures.py                 # writes into the fabric resources
    python tools/gen_gear_textures.py --preview x.png # also writes an enlarged contact sheet

Needs Pillow (pip install pillow). Output is deterministic, so rerunning only changes files when
this script changes. Tier ids must match fabric/src/main/java/dev/skycraft/gear/GearTiers.java.
"""
import argparse
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "fabric", "src", "main", "resources", "assets", "skycraft")
ITEM_DIR = os.path.join(ASSETS, "textures", "item")
HUMANOID_DIR = os.path.join(ASSETS, "textures", "entity", "equipment", "humanoid")
LEGGINGS_DIR = os.path.join(ASSETS, "textures", "entity", "equipment", "humanoid_leggings")


def rgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


# Per tier: outline, dark, mid, light (the metal), trim, trim dark, special (tier detail colour),
# and alpha for the metal (glass is see-through on items).
TIERS = {
    "iron": dict(outline="26282c", dark="6c6f75", mid="9fa3a9", light="d6d9de", trim="8a6440", trim_dark="5a3f27", special="c9ccd1", alpha=255),
    "steel": dict(outline="1f2630", dark="566273", mid="8794a8", light="c3cfdf", trim="3f4856", trim_dark="2a313b", special="e4ecf6", alpha=255),
    "orcish": dict(outline="141d12", dark="2f4529", mid="4d6a3f", light="7f9a62", trim="8c7a52", trim_dark="5c4f33", special="c8b98a", alpha=255),
    "dwarven": dict(outline="3a230d", dark="7c4e22", mid="b07c3a", light="e2b46a", trim="5e3c1b", trim_dark="3e2710", special="f3d69a", alpha=255),
    "elven": dict(outline="4a3508", dark="9a7020", mid="d1a83e", light="f6df8a", trim="6f8d55", trim_dark="4a6239", special="fff3c4", alpha=255),
    "glass": dict(outline="174d33", dark="2f8a5c", mid="5cc48c", light="b6f2cf", trim="b8a978", trim_dark="7d7150", special="eafff3", alpha=205),
    "ebony": dict(outline="060608", dark="16161c", mid="2a2a35", light="4f4f62", trim="3a3546", trim_dark="221f2a", special="a9a9c8", alpha=255),
    "daedric": dict(outline="050304", dark="1a1214", mid="2e2226", light="51404a", trim="8f1616", trim_dark="5a0c0c", special="ff4a2e", alpha=255),
}
TIER_ORDER = ["iron", "steel", "orcish", "dwarven", "elven", "glass", "ebony", "daedric"]

GRIP = dict(outline="1e140c", dark="4a3220", mid="6e4b2f", light="93684a")


def palette(tier):
    t = TIERS[tier]
    a = t["alpha"]
    return dict(
        outline=rgb(t["outline"]),
        dark=rgb(t["dark"], a),
        mid=rgb(t["mid"], a),
        light=rgb(t["light"], a),
        trim=rgb(t["trim"]),
        trim_dark=rgb(t["trim_dark"]),
        special=rgb(t["special"]),
    )


def grip_palette():
    return {k: rgb(v) for k, v in GRIP.items()}


# ---------------------------------------------------------------- 16x16 icons


class Icon:
    """A 16x16 grid of labelled cells. Each label is a part with its own palette."""

    def __init__(self):
        self.cells = {}  # (x, y) -> part name

    def put(self, x, y, part):
        if 0 <= x < 16 and 0 <= y < 16:
            self.cells[(x, y)] = part

    def rect(self, x0, y0, x1, y1, part):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, part)

    def render(self, parts):
        """parts: part name -> palette dict (outline, dark, mid, light). Bevel lit from the top left."""
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        px = img.load()
        filled = self.cells
        for (x, y), part in filled.items():
            pal = parts[part]
            if any((x + dx, y + dy) not in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = pal["outline"]
                continue
            up_left = (x - 1, y - 1) not in filled or (x - 1, y) not in filled or (x, y - 1) not in filled
            down_right = (x + 1, y + 1) not in filled or (x + 1, y) not in filled or (x, y + 1) not in filled
            if up_left and not down_right:
                px[x, y] = pal["light"]
            elif down_right and not up_left:
                px[x, y] = pal["dark"]
            else:
                px[x, y] = pal["mid"]
        return img


def flat(color):
    return dict(outline=color, dark=color, mid=color, light=color)


def sword_icon(tier):
    pal = palette(tier)
    ic = Icon()
    # Blade along x + y = 15 from the guard (lower left) to the tip (upper right).
    for y in range(16):
        for x in range(16):
            s, d = x + y, x - y
            if 13 <= s <= 16 and -3 <= d <= 11:
                ic.put(x, y, "blade")
            if s == 14 and d == 12 or s == 15 and d in (12, 13):
                ic.put(x, y, "blade")
            # Cross guard, across the blade.
            if d in (-5, -4) and 11 <= s <= 18:
                ic.put(x, y, "guard")
            # Grip and pommel continue the blade axis.
            if s in (14, 15) and -10 <= d <= -6:
                ic.put(x, y, "grip")
            if 13 <= s <= 16 and d in (-12, -11):
                ic.put(x, y, "pommel")
    parts = {"blade": pal, "guard": dict(pal, mid=pal["trim"], light=pal["light"], dark=pal["trim_dark"]), "grip": grip_palette(),
             "pommel": dict(pal, mid=pal["trim"], dark=pal["trim_dark"])}
    img = ic.render(parts)
    px = img.load()
    # Fuller (groove) down the middle of the blade.
    for y in range(16):
        for x in range(16):
            if x + y == 15 and -1 <= x - y <= 9:
                px[x, y] = pal["light"] if tier != "daedric" else pal["special"]
    tier_details(tier, img, "sword", pal)
    return img


def war_axe_icon(tier):
    pal = palette(tier)
    ic = Icon()
    for y in range(16):
        for x in range(16):
            s, d = x + y, x - y
            # Haft along x + y = 15.
            if s in (14, 15, 16) and -12 <= d <= 8 and (s == 15 or d % 2 == (s % 2)):
                ic.put(x, y, "haft")
            # Bearded head on the upper left of the haft: wider toward the cutting edge.
            if 8 <= s <= 14:
                half = 2 + (14 - s) // 2
                if 4 - half <= d <= 4 + half and d >= 0:
                    ic.put(x, y, "head")
            # Small back spike on the other side.
            if s in (16, 17) and 3 <= d <= 5:
                ic.put(x, y, "head")
    parts = {"haft": grip_palette(), "head": pal}
    img = ic.render(parts)
    px = img.load()
    # Bright cutting edge.
    for y in range(16):
        for x in range(16):
            s, d = x + y, x - y
            if s == 9 and 0 <= d <= 8 and img.getpixel((x, y))[3] > 0:
                px[x, y] = pal["light"]
            # Wrapped grip bands in the tier trim colour.
            if s == 15 and d in (-9, -7) :
                px[x, y] = pal["trim"]
    tier_details(tier, img, "war_axe", pal)
    return img


def helmet_icon(tier):
    pal = palette(tier)
    ic = Icon()
    ic.rect(5, 2, 10, 2, "metal")
    ic.rect(4, 3, 11, 3, "metal")
    ic.rect(3, 4, 12, 11, "metal")
    ic.rect(4, 12, 6, 12, "metal")
    ic.rect(9, 12, 11, 12, "metal")
    img = ic.render({"metal": pal})
    px = img.load()
    # T shaped face opening.
    for x in range(5, 11):
        px[x, 8] = pal["outline"]
    for y in range(9, 13):
        px[7, y] = pal["outline"]
        px[8, y] = pal["outline"]
    # Crest stripe.
    for y in range(3, 8):
        px[7, y] = pal["trim"]
        px[8, y] = pal["trim_dark"]
    tier_details(tier, img, "helmet", pal)
    return img


def cuirass_icon(tier):
    pal = palette(tier)
    ic = Icon()
    ic.rect(1, 2, 5, 5, "metal")    # left pauldron
    ic.rect(10, 2, 14, 5, "metal")  # right pauldron
    ic.rect(3, 3, 12, 12, "metal")  # torso
    ic.rect(6, 2, 9, 2, "metal")
    ic.rect(4, 13, 11, 13, "metal")
    img = ic.render({"metal": pal})
    px = img.load()
    # Neck opening.
    for x in range(6, 10):
        px[x, 2] = (0, 0, 0, 0)
    px[6, 3] = pal["outline"]
    px[9, 3] = pal["outline"]
    for x in range(7, 9):
        px[x, 3] = pal["outline"]
    # Belt and pauldron seams.
    for x in range(4, 12):
        px[x, 10] = pal["trim"]
    px[7, 10] = pal["special"]
    px[8, 10] = pal["special"]
    for y in range(3, 6):
        px[5, y] = pal["dark"]
        px[10, y] = pal["dark"]
    # Centre ridge.
    for y in range(5, 10):
        px[7, y] = pal["light"]
    tier_details(tier, img, "cuirass", pal)
    return img


def greaves_icon(tier):
    pal = palette(tier)
    ic = Icon()
    ic.rect(3, 1, 12, 4, "metal")    # waist
    ic.rect(3, 5, 7, 14, "metal")    # left leg
    ic.rect(8, 5, 12, 14, "metal")   # right leg
    img = ic.render({"metal": pal})
    px = img.load()
    for x in range(4, 12):
        px[x, 2] = pal["trim"]
    for y in range(5, 15):
        px[7, y] = pal["outline"]
        px[8, y] = pal["outline"]
    # Knee plates.
    for x in (4, 5, 6, 9, 10, 11):
        px[x, 9] = pal["light"]
        px[x, 10] = pal["dark"]
    tier_details(tier, img, "greaves", pal)
    return img


def boots_icon(tier):
    pal = palette(tier)
    ic = Icon()
    ic.rect(2, 6, 6, 12, "metal")    # left boot shaft
    ic.rect(0, 11, 6, 13, "metal")   # left toe
    ic.rect(9, 6, 13, 12, "metal")   # right boot shaft
    ic.rect(9, 11, 15, 13, "metal")  # right toe
    img = ic.render({"metal": pal})
    px = img.load()
    for x in list(range(3, 6)) + list(range(10, 13)):
        px[x, 8] = pal["trim"]
    for x in list(range(1, 6)) + list(range(10, 15)):
        px[x, 13] = pal["outline"]
    tier_details(tier, img, "boots", pal)
    return img


def tier_details(tier, img, piece, pal):
    """Small per tier touches on top of the shared silhouettes."""
    px = img.load()
    w, h = img.size

    def solid(x, y):
        return 0 <= x < w and 0 <= y < h and px[x, y][3] > 0

    if tier == "daedric":
        # Jagged red spikes and glowing seams.
        if piece == "sword":
            for d in (0, 4, 8):
                x, y = (17 + d) // 2, (17 - d) // 2  # x + y = 17, just outside the blade
                if 0 <= x < 16 and 0 <= y < 16:
                    px[x, y] = pal["trim"]
        if piece == "helmet":
            for x, y in ((2, 3), (1, 2), (1, 1), (13, 3), (14, 2), (14, 1)):
                px[x, y] = pal["trim"]
        if piece in ("cuirass", "greaves", "boots"):
            for y in range(h):
                for x in range(w):
                    if px[x, y] == pal["trim"]:
                        px[x, y] = pal["special"] if (x + y) % 3 == 0 else pal["trim"]
        if piece == "war_axe":
            for x, y in ((3, 4), (2, 5)):
                if solid(x, y):
                    px[x, y] = pal["special"]
    elif tier == "ebony":
        # A diagonal sheen across the dark metal.
        for y in range(h):
            for x in range(w):
                if solid(x, y) and x - y == 2 and px[x, y] != pal["outline"]:
                    px[x, y] = pal["special"]
    elif tier == "glass":
        for y in range(h):
            for x in range(w):
                if solid(x, y) and (x + 2 * y) % 7 == 0 and px[x, y] in (pal["mid"], pal["dark"]):
                    px[x, y] = pal["light"]
    elif tier == "orcish":
        if piece == "helmet":
            for x, y in ((3, 2), (2, 1), (12, 2), (13, 1)):
                px[x, y] = pal["trim"]
        if piece == "sword":
            for d in (2, 6):
                x, y = (17 + d) // 2, (17 - d) // 2
                px[x, y] = pal["outline"]
    elif tier == "dwarven":
        for y in range(h):
            for x in range(w):
                if solid(x, y) and px[x, y] == pal["mid"] and (x * 5 + y * 3) % 11 == 0:
                    px[x, y] = pal["special"]
    elif tier == "elven":
        if piece == "helmet":
            for x, y in ((7, 1), (8, 1), (7, 0)):
                px[x, y] = pal["light"]
    elif tier == "steel":
        for y in range(h):
            for x in range(w):
                if solid(x, y) and px[x, y] == pal["light"] and (x + y) % 4 == 0:
                    px[x, y] = pal["special"]


# ---------------------------------------------------------------- materials


def ingot_icon(colors, glint):
    ic = Icon()
    ic.rect(5, 5, 12, 5, "m")
    ic.rect(4, 6, 12, 6, "m")
    ic.rect(3, 7, 13, 7, "m")
    ic.rect(2, 8, 13, 10, "m")
    img = ic.render({"m": colors})
    px = img.load()
    for x in range(6, 12):
        px[x, 6] = colors["light"]
    px[9, 8] = glint
    px[10, 8] = glint
    return img


def gem_icon(colors, stripe=None):
    ic = Icon()
    ic.rect(6, 3, 9, 3, "g")
    ic.rect(5, 4, 10, 4, "g")
    ic.rect(4, 5, 11, 10, "g")
    ic.rect(5, 11, 10, 11, "g")
    ic.rect(6, 12, 9, 12, "g")
    img = ic.render({"g": colors})
    px = img.load()
    for x, y in ((6, 5), (7, 5), (6, 6)):
        px[x, y] = rgb("ffffff", 230)
    if stripe:
        for y in range(5, 12):
            for x in range(5, 11):
                if (x + y) % 3 == 0 and px[x, y][3] and px[x, y] != colors["outline"]:
                    px[x, y] = stripe
    return img


def heart_icon():
    c = dict(outline=rgb("2a0606"), dark=rgb("6a0f12"), mid=rgb("a3161b"), light=rgb("e04a3a"))
    ic = Icon()
    ic.rect(3, 4, 6, 4, "h")
    ic.rect(9, 4, 12, 4, "h")
    ic.rect(2, 5, 13, 8, "h")
    ic.rect(3, 9, 12, 9, "h")
    ic.rect(4, 10, 11, 10, "h")
    ic.rect(5, 11, 10, 11, "h")
    ic.rect(6, 12, 9, 12, "h")
    ic.rect(7, 13, 8, 13, "h")
    ic.rect(7, 2, 8, 3, "h")  # severed vessel
    img = ic.render({"h": c})
    px = img.load()
    for x, y in ((6, 6), (7, 7), (8, 7), (9, 8), (10, 9), (5, 8), (4, 7)):
        px[x, y] = rgb("1a0406")  # dark veins
    for x, y in ((9, 6), (10, 6)):
        px[x, y] = rgb("ff8a5a")  # ember glow
    return img


def strips_icon():
    c = grip_palette()
    ic = Icon()
    for i in range(3):
        y0 = 4 + i * 3
        for x in range(2 + i, 14 - i):
            ic.put(x, y0 + (x % 2), "s")
            ic.put(x, y0 + 1 + (x % 2), "s")
    img = ic.render({"s": c})
    px = img.load()
    for x in range(3, 13, 3):
        px[x, 5 + (x % 2)] = c["light"]
    return img


def material_icons():
    p = palette
    return {
        "steel_ingot": ingot_icon(dict(p("steel"), outline=p("steel")["outline"]), p("steel")["special"]),
        "orichalcum_ingot": ingot_icon(dict(outline=rgb("142012"), dark=rgb("2f4a2a"), mid=rgb("4f7344"), light=rgb("86a86c")), rgb("c98b4a")),
        "dwarven_metal_ingot": ingot_icon(p("dwarven"), p("dwarven")["special"]),
        "ebony_ingot": ingot_icon(p("ebony"), p("ebony")["special"]),
        "refined_moonstone": gem_icon(dict(outline=rgb("4a5a74"), dark=rgb("8fa3c4"), mid=rgb("c4d3ea"), light=rgb("eef4ff"))),
        "refined_malachite": gem_icon(dict(outline=rgb("0f3a26"), dark=rgb("1f7a4c"), mid=rgb("34a86a"), light=rgb("7fe0a8")), stripe=rgb("16603c")),
        "daedra_heart": heart_icon(),
        "leather_strips": strips_icon(),
    }


# ---------------------------------------------------------------- 64x32 armor layers

# Vanilla humanoid armor UV layout (64x32): each cube's faces as (x, y, w, h, kind).
def cube_faces(u, v, w, h, d):
    return [
        (u + d, v, w, d, "top"),
        (u + d + w, v, w, d, "bottom"),
        (u, v + d, d, h, "side"),
        (u + d, v + d, w, h, "front"),
        (u + d + w, v + d, d, h, "side"),
        (u + d + w + d, v + d, w, h, "back"),
    ]


HEAD = cube_faces(0, 0, 8, 8, 8)
BODY = cube_faces(16, 16, 8, 12, 4)
ARM = cube_faces(40, 16, 4, 12, 4)
LEG = cube_faces(0, 16, 4, 12, 4)


def paint_face(px, face, pal, tier, rows=None, plates=4, eye_slit=False):
    x0, y0, w, h, kind = face
    shade = {"top": "light", "bottom": "dark", "side": "dark", "front": "mid", "back": "mid"}[kind]
    r0, r1 = (0, h) if rows is None else rows
    if kind in ("top", "bottom"):
        r0, r1 = 0, h
    for yy in range(r0, r1):
        for xx in range(w):
            c = pal[shade]
            edge = xx == 0 or xx == w - 1 or yy == r0 or yy == r1 - 1
            if kind not in ("top", "bottom"):
                if edge:
                    c = pal["trim"] if kind == "front" else pal["outline"]
                elif (yy - r0) % plates == plates - 1:
                    c = pal["dark"]  # plate seam
                elif xx == 1 and kind == "front":
                    c = pal["light"]
            px[x0 + xx, y0 + yy] = c
    if kind in ("front", "back") and r1 - r0 >= 4 and w >= 4:
        for xx, yy in ((1, r0 + 1), (w - 2, r0 + 1)):
            px[x0 + xx, y0 + yy] = pal["special"]  # rivets
    if eye_slit and kind == "front":
        for xx in range(1, w - 1):
            px[x0 + xx, y0 + 3] = (0, 0, 0, 0)
        for yy in range(4, 7):
            px[x0 + 3, y0 + yy] = (0, 0, 0, 0)
            px[x0 + 4, y0 + yy] = (0, 0, 0, 0)
    # Tier touches on armor.
    for yy in range(r0, r1):
        for xx in range(w):
            x, y = x0 + xx, y0 + yy
            if px[x, y][3] == 0:
                continue
            if tier == "daedric" and kind != "bottom" and (xx + 2 * yy) % 6 == 0 and px[x, y] == pal["dark"]:
                px[x, y] = pal["trim"]
            elif tier == "ebony" and xx - yy % w == 0 and px[x, y] in (pal["mid"], pal["dark"]):
                px[x, y] = pal["special"]
            elif tier == "glass" and (xx + yy) % 5 == 0 and px[x, y] == pal["mid"]:
                px[x, y] = pal["light"]
            elif tier == "elven" and kind == "front" and xx == w // 2 and not (yy == r0 or yy == r1 - 1):
                px[x, y] = pal["light"]
            elif tier == "dwarven" and (yy - r0) % 4 == 1 and px[x, y] == pal["mid"]:
                px[x, y] = pal["light"]


def armor_layers(tier):
    pal = palette(tier)
    pal = dict(pal, dark=pal["dark"][:3] + (255,), mid=pal["mid"][:3] + (255,), light=pal["light"][:3] + (255,))  # armor renders as cutout
    outer = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = outer.load()
    for face in HEAD:  # helmet
        paint_face(px, face, pal, tier, eye_slit=True)
    for face in BODY:  # cuirass
        paint_face(px, face, pal, tier)
    for face in ARM:  # cuirass sleeves: upper arm only
        paint_face(px, face, pal, tier, rows=(0, 7))
    for face in LEG:  # boots: lower leg only
        paint_face(px, face, pal, tier, rows=(6, 12), plates=6)
    inner = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = inner.load()
    for face in BODY:  # greaves: waist
        if face[4] not in ("top",):
            paint_face(px, face, pal, tier, rows=(7, 12))
    for face in LEG:  # greaves: legs above the boots
        paint_face(px, face, pal, tier, rows=(0, 10))
    return outer, inner


# ---------------------------------------------------------------- main

PIECES = {
    "sword": sword_icon,
    "war_axe": war_axe_icon,
    "helmet": helmet_icon,
    "cuirass": cuirass_icon,
    "greaves": greaves_icon,
    "boots": boots_icon,
}


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--preview", help="also write an enlarged contact sheet of every texture to this path")
    args = ap.parse_args()
    for d in (ITEM_DIR, HUMANOID_DIR, LEGGINGS_DIR):
        os.makedirs(d, exist_ok=True)
    written = []
    for name, img in material_icons().items():
        written.append((name, img))
    for tier in TIER_ORDER:
        for piece, fn in PIECES.items():
            written.append((tier + "_" + piece, fn(tier)))
    for name, img in written:
        img.save(os.path.join(ITEM_DIR, name + ".png"))
    layers = []
    for tier in TIER_ORDER:
        outer, inner = armor_layers(tier)
        outer.save(os.path.join(HUMANOID_DIR, tier + ".png"))
        inner.save(os.path.join(LEGGINGS_DIR, tier + ".png"))
        layers.append((outer, inner))
    print("wrote %d item textures and %d armor layers" % (len(written), 2 * len(layers)))
    if args.preview:
        scale = 6
        cols = 8
        rows = (len(written) + cols - 1) // cols
        sheet_w = cols * 18 * scale
        sheet = Image.new("RGBA", (sheet_w, rows * 18 * scale + len(layers) * 34 * scale), (70, 80, 90, 255))
        for i, (_, img) in enumerate(written):
            x, y = (i % cols) * 18 * scale, (i // cols) * 18 * scale
            sheet.alpha_composite(img.resize((16 * scale, 16 * scale), Image.NEAREST), (x + scale, y + scale))
        base = rows * 18 * scale
        for i, (outer, inner) in enumerate(layers):
            y = base + i * 34 * scale
            sheet.alpha_composite(outer.resize((64 * scale, 32 * scale), Image.NEAREST), (0, y))
            sheet.alpha_composite(inner.resize((64 * scale, 32 * scale), Image.NEAREST), (66 * scale, y))
        sheet.save(args.preview)
        print("preview:", args.preview)


if __name__ == "__main__":
    main()
