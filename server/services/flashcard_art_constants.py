import os
import math
from typing import Tuple
from PIL import ImageDraw, ImageFont

STATIC_FLASHCARDS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "static",
    "flashcards"
)
os.makedirs(STATIC_FLASHCARDS_DIR, exist_ok=True)

# Pre-defined curated palettes tailored to emotional tones
THEME_PALETTES = {
    "ephemeral": {
        "bg_top": (13, 14, 30),
        "bg_bottom": (35, 18, 52),
        "glow": (212, 136, 255),
        "accent": (245, 197, 85),
        "symbol": "flower",
        "title": "The Whispering Petal",
        "palette_hex": "#D488FF",
    },
    "serendipity": {
        "bg_top": (8, 22, 28),
        "bg_bottom": (16, 48, 56),
        "glow": (78, 222, 200),
        "accent": (255, 215, 105),
        "symbol": "compass",
        "title": "Celestial Compass",
        "palette_hex": "#4EDECA",
    },
    "resilience": {
        "bg_top": (28, 14, 10),
        "bg_bottom": (52, 25, 15),
        "glow": (255, 120, 60),
        "accent": (255, 210, 110),
        "symbol": "flame",
        "title": "Unyielding Flame",
        "palette_hex": "#FF783C",
    },
    "default": {
        "bg_top": (14, 17, 26),
        "bg_bottom": (26, 32, 50),
        "glow": (140, 180, 255),
        "accent": (245, 200, 95),
        "symbol": "crystal",
        "title": "Luminous Arcana",
        "palette_hex": "#8CB4FF",
    }
}

def get_font(size: int, bold: bool = False) -> ImageFont.ImageFont:
    """Safely loads system fonts on macOS / Linux with full UTF-8 Vietnamese support."""
    font_names = []
    if bold:
        font_names = [
            "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
            "/System/Library/Fonts/Supplemental/Times New Roman Bold.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        ]
    else:
        font_names = [
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        ]
    for path in font_names:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                pass
    return ImageFont.load_default()

def draw_celestial_symbol(draw: ImageDraw.ImageDraw, center: Tuple[int, int], radius: int, glow_color: Tuple[int, int, int], symbol_type: str):
    """Draws intricate, glowing sacred geometry and symbolic artwork."""
    cx, cy = center
    
    # 1. Concentric orbital rings with dashed markers
    for r in [radius + 50, radius + 25, radius, radius - 30, radius - 60]:
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(*glow_color, 120), width=2)
    
    # 2. Radiating celestial rays
    num_rays = 16
    for i in range(num_rays):
        angle = i * (2 * math.pi / num_rays)
        x1 = cx + int((radius - 20) * math.cos(angle))
        y1 = cy + int((radius - 20) * math.sin(angle))
        x2 = cx + int((radius + 45) * math.cos(angle))
        y2 = cy + int((radius + 45) * math.sin(angle))
        ray_alpha = 180 if i % 2 == 0 else 90
        draw.line([x1, y1, x2, y2], fill=(245, 215, 120, ray_alpha), width=2 if i % 4 == 0 else 1)
        
    # 3. Inner Symbol Art
    if symbol_type == "flower":  # Ephemeral: Glowing Lotus / Sacred Dandelion
        num_petals = 12
        petal_r = radius - 30
        for i in range(num_petals):
            angle = i * (2 * math.pi / num_petals)
            px = cx + int(petal_r * math.cos(angle))
            py = cy + int(petal_r * math.sin(angle))
            draw.ellipse([px - 28, py - 28, px + 28, py + 28], outline=(*glow_color, 160), width=2)
            draw.line([cx, cy, px, py], fill=(245, 215, 120, 110), width=1)
        draw.ellipse([cx - 20, cy - 20, cx + 20, cy + 20], fill=(255, 235, 160, 240), outline=(255, 255, 255, 255), width=2)
        
    elif symbol_type == "compass":  # Serendipity: Star Compass / Astrolabe
        for points in [8, 4]:
            step = (2 * math.pi) / (points * 2)
            pts = []
            outer = radius - 10 if points == 8 else radius - 35
            inner = 35 if points == 8 else 20
            for i in range(points * 2):
                r_curr = outer if i % 2 == 0 else inner
                a_curr = i * step - (math.pi / 2)
                pts.append((cx + int(r_curr * math.cos(a_curr)), cy + int(r_curr * math.sin(a_curr))))
            draw.polygon(pts, fill=(*glow_color, 70), outline=(245, 215, 120, 220))
        draw.ellipse([cx - 14, cy - 14, cx + 14, cy + 14], fill=(255, 245, 200, 255))
        
    elif symbol_type == "flame":  # Resilience: Undying Sacred Fire
        for layer in range(4):
            offset_y = layer * 16
            f_rad = (radius - 20) - (layer * 18)
            flame_pts = [
                (cx, cy - f_rad - 40 + offset_y),
                (cx + f_rad, cy + f_rad // 2 + offset_y),
                (cx + f_rad // 2, cy + f_rad + offset_y),
                (cx, cy + f_rad - 10 + offset_y),
                (cx - f_rad // 2, cy + f_rad + offset_y),
                (cx - f_rad, cy + f_rad // 2 + offset_y),
            ]
            fill_c = (255, 120 + layer * 35, 40 + layer * 40, 110 + layer * 35)
            draw.polygon(flame_pts, fill=fill_c, outline=(255, 220, 140, 200))
        draw.ellipse([cx - 15, cy + 10, cx + 15, cy + 40], fill=(255, 255, 220, 240))
        
    else:  # Luminous crystal
        crystal_pts = [
            (cx, cy - radius + 15),
            (cx + radius - 35, cy - 15),
            (cx + radius - 45, cy + radius - 30),
            (cx, cy + radius),
            (cx - radius + 45, cy + radius - 30),
            (cx - radius + 35, cy - 15)
        ]
        draw.polygon(crystal_pts, fill=(*glow_color, 80), outline=(245, 215, 120, 220))
        for pt in crystal_pts:
            draw.line([cx, cy, pt[0], pt[1]], fill=(255, 255, 255, 120), width=1)
        draw.ellipse([cx - 16, cy - 16, cx + 16, cy + 16], fill=(255, 255, 255, 220))
