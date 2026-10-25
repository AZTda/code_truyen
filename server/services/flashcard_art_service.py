import os
import math
import random
import hashlib
import logging
from PIL import Image, ImageDraw

logger = logging.getLogger("truyencloud.flashcard_art")

from services.flashcard_art_constants import (
    STATIC_FLASHCARDS_DIR,
    THEME_PALETTES,
    get_font as _get_font,
    draw_celestial_symbol,
)

class FlashcardArtService:
    """Generates high-definition, editorial luxury flashcard artwork for vocabulary words."""

    def __init__(self):
        self.width = 760
        self.height = 1100

    def get_or_render_flashcard(
        self,
        word: str,
        ipa: str = "",
        cefr_level: str = "C1",
        meaning: str = "",
        mnemonic: str = "",
        symbol_type: str = "flower",
        palette_hex: str = "#D488FF"
    ) -> str:
        """Renders and caches a high-definition PNG flashcard poster."""
        safe_word = "".join(c for c in word.lower() if c.isalnum() or c in "-_")
        # Check for real AI concept illustration first
        jpg_filename = f"{safe_word}.jpg"
        jpg_filepath = os.path.join(STATIC_FLASHCARDS_DIR, jpg_filename)
        if os.path.exists(jpg_filepath) and os.path.getsize(jpg_filepath) > 1000:
            return f"/static/flashcards/{jpg_filename}"

        # Tên tệp gồm hash MỌI đầu vào vẽ poster (IPA, nghĩa, mnemonic, cấp độ, màu...): trước đây chỉ có
        # từ, nên poster của người gọi đầu tiên (kể cả IPA/nghĩa do họ tự điền) bị phát cho mọi người sau.
        render_inputs = "\x1f".join([word.lower(), ipa or "", cefr_level or "", meaning or "", mnemonic or "",
                                     symbol_type or "", palette_hex or ""])
        render_hash = hashlib.sha1(render_inputs.encode("utf-8")).hexdigest()[:10]
        filename = f"{safe_word}_{render_hash}_flashcard.png"
        filepath = os.path.join(STATIC_FLASHCARDS_DIR, filename)

        # Return cached if exists
        if os.path.exists(filepath) and os.path.getsize(filepath) > 1000:
            return f"/static/flashcards/{filename}"

        # Palette selection
        palette = THEME_PALETTES.get(safe_word, THEME_PALETTES.get("default")).copy()
        if palette_hex and palette_hex.startswith("#") and len(palette_hex) == 7:
            try:
                h = palette_hex.lstrip("#")
                r, g, b = tuple(int(h[i:i+2], 16) for i in (0, 2, 4))
                palette["glow"] = (r, g, b)
                palette["palette_hex"] = palette_hex
            except Exception:
                pass
        if symbol_type:
            palette["symbol"] = symbol_type

        # Create canvas with alpha channel
        img = Image.new("RGBA", (self.width, self.height), (0, 0, 0, 255))
        draw = ImageDraw.Draw(img, "RGBA")

        # 1. Background linear gradient
        top_color = palette["bg_top"]
        bot_color = palette["bg_bottom"]
        for y in range(self.height):
            factor = y / float(self.height)
            r = int(top_color[0] + factor * (bot_color[0] - top_color[0]))
            g = int(top_color[1] + factor * (bot_color[1] - top_color[1]))
            b = int(top_color[2] + factor * (bot_color[2] - top_color[2]))
            draw.line([(0, y), (self.width, y)], fill=(r, g, b, 255))

        # 2. Star dust particles
        random.seed(hash(word) % 10000)
        for _ in range(120):
            sx = random.randint(30, self.width - 30)
            sy = random.randint(30, self.height - 30)
            s_radius = random.choice([1, 1, 2, 3])
            s_alpha = random.randint(40, 180)
            draw.ellipse(
                [sx - s_radius, sy - s_radius, sx + s_radius, sy + s_radius],
                fill=(255, 255, 255, s_alpha)
            )

        # 3. Ambient center glow aura (Smooth radial gradient overlay)
        cx, cy = self.width // 2, 430
        glow_layer = Image.new("RGBA", (self.width, self.height), (0, 0, 0, 0))
        glow_draw = ImageDraw.Draw(glow_layer, "RGBA")
        glow_r = 180
        for gr in range(glow_r, 0, -6):
            intensity = math.sin((1.0 - (gr / float(glow_r))) * (math.pi / 2))
            alpha = int(intensity * 40)
            glow_draw.ellipse(
                [cx - gr, cy - gr, cx + gr, cy + gr],
                fill=(*palette["glow"], alpha)
            )
        img = Image.alpha_composite(img, glow_layer)
        draw = ImageDraw.Draw(img, "RGBA")

        # 4. Metallic Glassmorphic Outer & Inner Borders
        margin = 36
        draw.rounded_rectangle(
            [margin, margin, self.width - margin, self.height - margin],
            radius=28,
            outline=(210, 175, 110, 160),
            width=2
        )
        inner_m = margin + 10
        draw.rounded_rectangle(
            [inner_m, inner_m, self.width - inner_m, self.height - inner_m],
            radius=20,
            outline=(*palette["glow"], 70),
            width=1
        )

        # Corner luxury ornamental brackets
        corner_len = 24
        for x, y, dx, dy in [
            (margin + 6, margin + 6, 1, 1),
            (self.width - margin - 6, margin + 6, -1, 1),
            (margin + 6, self.height - margin - 6, 1, -1),
            (self.width - margin - 6, self.height - margin - 6, -1, -1),
        ]:
            draw.line([x, y, x + dx * corner_len, y], fill=(245, 210, 120, 230), width=2)
            draw.line([x, y, x, y + dy * corner_len], fill=(245, 210, 120, 230), width=2)
            draw.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(255, 240, 180, 255))

        # 5. Header: Badge & CEFR
        font_tag = _get_font(18, bold=True)
        header_text = f"TRUYENCLOUD • CEFR {cefr_level.upper()}"
        bbox = draw.textbbox((0, 0), header_text, font=font_tag)
        tw = bbox[2] - bbox[0]
        draw.text(((self.width - tw) // 2, margin + 30), header_text, fill=(245, 205, 115, 240), font=font_tag)

        # 6. Celestial Metaphor Symbol
        draw_celestial_symbol(draw, (cx, cy), radius=120, glow_color=palette["glow"], symbol_type=palette["symbol"])

        # 7. Word Title (Grand Editorial Serif)
        font_word = _get_font(52, bold=True)
        display_word = word.lower()
        w_bbox = draw.textbbox((0, 0), display_word, font=font_word)
        ww = w_bbox[2] - w_bbox[0]
        wy = 620
        # Shadow glow
        draw.text(((self.width - ww) // 2 + 1, wy + 2), display_word, fill=(0, 0, 0, 200), font=font_word)
        draw.text(((self.width - ww) // 2, wy), display_word, fill=(255, 255, 255, 255), font=font_word)

        # 8. IPA Phonetic
        font_ipa = _get_font(24)
        clean_ipa = ipa if ipa else "/.../"
        # Avoid missing glyphs by normalizing
        clean_ipa = clean_ipa.replace("□", "").strip()
        ipa_bbox = draw.textbbox((0, 0), clean_ipa, font=font_ipa)
        iw = ipa_bbox[2] - ipa_bbox[0]
        draw.text(((self.width - iw) // 2, wy + 68), clean_ipa, fill=(*palette["glow"], 230), font=font_ipa)

        # 9. Vietnamese Meaning Pill
        font_meaning = _get_font(24, bold=True)
        m_text = meaning if meaning else "Từ vựng văn học"
        m_bbox = draw.textbbox((0, 0), m_text, font=font_meaning)
        mw = m_bbox[2] - m_bbox[0]
        my = wy + 115
        pill_pad_x = 24
        pill_pad_y = 8
        draw.rounded_rectangle(
            [(self.width - mw) // 2 - pill_pad_x, my - pill_pad_y, (self.width + mw) // 2 + pill_pad_x, my + (m_bbox[3] - m_bbox[1]) + pill_pad_y],
            radius=16,
            fill=(0, 0, 0, 120),
            outline=(245, 205, 115, 160),
            width=1
        )
        draw.text(((self.width - mw) // 2, my), m_text, fill=(255, 225, 130, 255), font=font_meaning)

        # 10. Visual Mnemonic Box (Gemini Memory Hook)
        box_y = my + 60
        box_margin = margin + 24
        box_w = self.width - 2 * box_margin
        box_h = 145
        draw.rounded_rectangle(
            [box_margin, box_y, box_margin + box_w, box_y + box_h],
            radius=18,
            fill=(20, 24, 40, 210),
            outline=(*palette["glow"], 110),
            width=1
        )

        font_mnemonic_title = _get_font(18, bold=True)
        bullet_x = box_margin + 20
        bullet_y = box_y + 16
        # Draw glowing golden dot bullet
        draw.ellipse([bullet_x, bullet_y + 5, bullet_x + 7, bullet_y + 12], fill=(245, 205, 115, 255))
        draw.text((bullet_x + 14, bullet_y), "THẦN CHÚ THỊ GIÁC (AI MNEMONIC)", fill=(245, 205, 115, 255), font=font_mnemonic_title)

        font_mnemonic_body = _get_font(20)
        clean_mnemonic = mnemonic.strip() if mnemonic else f"Hình tượng ẩn dụ sâu sắc cho từ '{word}'."
        # Wrap mnemonic text to fit box
        words = clean_mnemonic.split()
        lines = []
        curr_line = ""
        for w in words:
            test_line = f"{curr_line} {w}".strip()
            tb = draw.textbbox((0, 0), test_line, font=font_mnemonic_body)
            if (tb[2] - tb[0]) < (box_w - 40):
                curr_line = test_line
            else:
                lines.append(curr_line)
                curr_line = w
        if curr_line:
            lines.append(curr_line)

        # Draw lines
        for idx, line in enumerate(lines[:3]):
            draw.text((box_margin + 20, box_y + 48 + idx * 28), line, fill=(230, 235, 245, 240), font=font_mnemonic_body)

        # 11. Footer Branding
        font_footer = _get_font(15)
        footer_text = "ANTIGRAVITY GEMINI • VISUAL MEMORY ARCHIVE"
        ft_bbox = draw.textbbox((0, 0), footer_text, font=font_footer)
        fw = ft_bbox[2] - ft_bbox[0]
        draw.text(((self.width - fw) // 2, self.height - margin - 32), footer_text, fill=(160, 175, 205, 150), font=font_footer)

        # Save image
        img.save(filepath, "PNG", optimize=True)
        logger.info(f"Generated flashcard art for '{word}' at {filepath}")
        return f"/static/flashcards/{filename}"

flashcard_art_service = FlashcardArtService()
