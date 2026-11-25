"""Generates the brand icon, Android mipmaps and the web default avatar.

Usage: python tools/brand-assets.py [source.png]
When a source image is given (a real logo), it is used instead of the built-in mark.
"""
from PIL import Image, ImageDraw, ImageFont
import base64
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRAND = os.path.join(ROOT, "android", "brand")
RES = os.path.join(ROOT, "android", "res")
AVATAR_TS = os.path.join(ROOT, "src", "components", "avatar", "defaultAvatar.ts")
S = 1024


def rounded_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def draw_mark():
    """Dark forge plate with an anvil and sparks."""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    top = (26, 22, 26)
    bottom = (58, 32, 20)
    for y in range(S):
        t = y / (S - 1)
        d.line(
            [(0, y), (S, y)],
            fill=(
                int(top[0] + (bottom[0] - top[0]) * t),
                int(top[1] + (bottom[1] - top[1]) * t),
                int(top[2] + (bottom[2] - top[2]) * t),
                255,
            ),
        )
    # Warm glow behind the anvil.
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([250, 380, 774, 900], fill=(255, 120, 40, 70))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)

    steel = (238, 238, 240, 255)
    shade = (196, 198, 204, 255)
    # Anvil: horn, top plate, waist, base.
    d.polygon([(312, 470), (128, 552), (320, 566)], fill=steel)
    d.polygon([(286, 470), (744, 470), (716, 544), (300, 544)], fill=steel)
    d.polygon([(300, 516), (744, 516), (716, 544), (300, 544)], fill=shade)
    d.polygon([(436, 544), (604, 544), (578, 668), (462, 668)], fill=steel)
    d.polygon([(578, 544), (604, 544), (578, 668)], fill=shade)
    d.polygon([(352, 668), (676, 668), (714, 734), (314, 734)], fill=steel)
    d.polygon([(314, 706), (714, 706), (714, 734), (314, 734)], fill=shade)
    # Sparks.
    for cx, cy, r in ((300, 370, 13), (512, 330, 18), (726, 372, 11), (612, 286, 8), (392, 286, 8)):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 158, 66, 255))
    return img


def load_mark(source):
    """Pad a real logo onto a white square so the whole artwork survives launcher masks."""
    if source:
        art = Image.open(source).convert("RGBA")
        flat = Image.new("RGBA", art.size, (255, 255, 255, 255))
        flat.alpha_composite(art)
        side = max(art.size)
        square = Image.new("RGBA", (side, side), (255, 255, 255, 255))
        square.paste(flat, ((side - art.width) // 2, (side - art.height) // 2))
        return square.resize((S, S), Image.LANCZOS)
    return draw_mark()


def build(source=None):
    os.makedirs(BRAND, exist_ok=True)
    mark = load_mark(source)
    mark = mark.resize((S, S), Image.LANCZOS)
    icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    icon.paste(mark, (0, 0), rounded_mask(S, int(S * 0.22)))
    icon.save(os.path.join(BRAND, "icon.png"))

    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for bucket, size in sizes.items():
        folder = os.path.join(RES, f"mipmap-{bucket}")
        os.makedirs(folder, exist_ok=True)
        icon.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))

    # Circular default avatar for the web UI.
    avatar = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    draw = ImageDraw.Draw(avatar)
    if source:
        # White disc with the whole logo fitted inside, so no letter is clipped.
        draw.ellipse([0, 0, 255, 255], fill=(255, 255, 255, 255))
        inner = mark.resize((216, 216), Image.LANCZOS)
        avatar.paste(inner, (20, 20), inner)
    else:
        circle = Image.new("L", (256, 256), 0)
        ImageDraw.Draw(circle).ellipse([0, 0, 255, 255], fill=255)
        avatar.paste(mark.resize((256, 256), Image.LANCZOS), (0, 0), circle)
    buffer = io.BytesIO()
    avatar.save(buffer, format="PNG", optimize=True)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")

    os.makedirs(os.path.dirname(AVATAR_TS), exist_ok=True)
    with open(AVATAR_TS, "w", encoding="utf-8") as handle:
        handle.write(
            "/** Built-in assistant avatar (generated from android/brand/icon.png). */\n"
            f"export const DEFAULT_ASSISTANT_AVATAR =\n  'data:image/png;base64,{encoded}'\n"
        )
    print("brand icon, mipmaps and default avatar updated")


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else None)
