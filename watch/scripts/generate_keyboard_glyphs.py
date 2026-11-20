#!/usr/bin/env python3
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parents[1] / "src" / "common" / "glyphs"
OUT.mkdir(parents=True, exist_ok=True)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
font = ImageFont.truetype(FONT, 22)
letters = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя"
for char in letters:
    image = Image.new("RGBA", (28, 28), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    bounds = draw.textbbox((0, 0), char, font=font, stroke_width=0)
    width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
    x = (28 - width) / 2 - bounds[0]
    y = (28 - height) / 2 - bounds[1] - 1
    draw.text((round(x), round(y)), char, font=font, fill=(255, 255, 255, 255))
    image.save(OUT / ("u%04X.png" % ord(char)), optimize=True)
print("Generated %d Cyrillic keyboard glyphs in %s" % (len(letters), OUT))
