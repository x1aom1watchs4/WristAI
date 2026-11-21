#!/usr/bin/env python3
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1] / "src" / "common"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
letters = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя"

def render(out, size, font_size):
    out.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype(FONT, font_size)
    for char in letters:
        image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        bounds = draw.textbbox((0, 0), char, font=font)
        width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
        x = (size - width) / 2 - bounds[0]
        y = (size - height) / 2 - bounds[1] - 1
        draw.text((round(x), round(y)), char, font=font, fill=(255, 255, 255, 255))
        # Glyphs are white with transparency, so grayscale+alpha stores the exact same pixels with fewer channels.\n        image.convert("LA").save(out / ("u%04X.png" % ord(char)), optimize=True, compress_level=9)

render(ROOT / "keyboard", 34, 25)
render(ROOT / "glyphs", 28, 22)
print("Generated keyboard and UI glyphs for %d Cyrillic characters" % len(letters))
