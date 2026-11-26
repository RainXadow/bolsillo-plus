"""
Genera ic_launcher_512.png (512x512) para Play Console.
Requiere: pip install pillow
"""
from PIL import Image, ImageDraw
import math

SIZE = 512
SCALE = SIZE / 108.0  # 108 es el viewportWidth del vector Android

img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


# ── Helpers ────────────────────────────────────────────────────────────────────

def s(v):
    """Escala un valor del espacio 108x108 al espacio 512x512."""
    return v * SCALE


def sc(pts):
    """Escala una lista de puntos (x,y)."""
    return [(x * SCALE, y * SCALE) for x, y in pts]


def rounded_rect_points(x0, y0, x1, y1, r, steps=8):
    """Genera los puntos de un rectángulo con esquinas redondeadas."""
    pts = []
    corners = [
        (x0 + r, y0 + r, 180, 270),
        (x1 - r, y0 + r, 270, 360),
        (x1 - r, y1 - r, 0, 90),
        (x0 + r, y1 - r, 90, 180),
    ]
    for cx, cy, a_start, a_end in corners:
        for i in range(steps + 1):
            a = math.radians(a_start + (a_end - a_start) * i / steps)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


# ── 1. Fondo: gradiente lineal índigo ─────────────────────────────────────────
bg = Image.new("RGBA", (SIZE, SIZE))
bg_draw = ImageDraw.Draw(bg)

color_start = (26, 35, 126)    # #1A237E
color_end   = (57, 73, 171)    # #3949AB

for i in range(SIZE):
    t = i / (SIZE - 1)
    r = int(color_start[0] + t * (color_end[0] - color_start[0]))
    g = int(color_start[1] + t * (color_end[1] - color_start[1]))
    b = int(color_start[2] + t * (color_end[2] - color_start[2]))
    # diagonal: dibujar líneas a 45°
    bg_draw.line([(0, i), (i, 0)], fill=(r, g, b, 255))
    bg_draw.line([(SIZE - 1 - i, SIZE - 1), (SIZE - 1, SIZE - 1 - i)], fill=(r, g, b, 255))

# relleno central (las líneas diagonales no cubren todo el cuadrado)
for y in range(SIZE):
    for x in range(SIZE):
        idx = (x + y) / (2 * (SIZE - 1))
        r = int(color_start[0] + idx * (color_end[0] - color_start[0]))
        g = int(color_start[1] + idx * (color_end[1] - color_start[1]))
        b = int(color_start[2] + idx * (color_end[2] - color_start[2]))
        bg.putpixel((x, y), (r, g, b, 255))

img.paste(bg, (0, 0))

# ── 2. Highlight radial suave (arriba-centro) ─────────────────────────────────
hl = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
hl_draw = ImageDraw.Draw(hl)
cx, cy, radius = s(54), s(20), s(45)
for step in range(30):
    t = step / 30
    r_cur = radius * (1 - t)
    alpha = int(32 * (1 - t))
    hl_draw.ellipse(
        [cx - r_cur, cy - r_cur, cx + r_cur, cy + r_cur],
        fill=(92, 132, 200, alpha)
    )
img = Image.alpha_composite(img, hl)

draw = ImageDraw.Draw(img)

# ── 3. Sombra de la cartera ───────────────────────────────────────────────────
shadow_pts = rounded_rect_points(31, 45, 87, 89, 8)
draw.polygon(sc(shadow_pts), fill=(0, 0, 0, 24))

# ── 4. Cuerpo de la cartera (blanco) ──────────────────────────────────────────
wallet_pts = rounded_rect_points(20, 42, 84, 86, 8)
draw.polygon(sc(wallet_pts), fill=(255, 255, 255, 255))

# ── 5. Lengüeta superior (clasp) ──────────────────────────────────────────────
clasp_pts = rounded_rect_points(34, 34, 66, 42, 4)
draw.polygon(sc(clasp_pts), fill=(255, 255, 255, 255))

# ── 6. Ranura para tarjeta (índigo claro) ─────────────────────────────────────
slot_pts = [
    (20, 42), (28, 42), (76, 42), (84, 42),
    (84, 50), (84, 62), (20, 62), (20, 50)
]
draw.polygon(sc(slot_pts), fill=(232, 234, 246, 255))
# esquinas superiores redondeadas
slot_full = rounded_rect_points(20, 42, 84, 62, 8)
draw.polygon(sc(slot_full), fill=(232, 234, 246, 255))

# ── 7. Tarjeta (índigo 100) ───────────────────────────────────────────────────
card_pts = rounded_rect_points(23, 48, 71, 60, 3)
draw.polygon(sc(card_pts), fill=(197, 202, 233, 255))

# ── 8. Chip de la tarjeta ─────────────────────────────────────────────────────
chip_pts = rounded_rect_points(28, 50, 40, 58, 2)
draw.polygon(sc(chip_pts), fill=(159, 168, 218, 255))

# ── 9. Divisor horizontal ─────────────────────────────────────────────────────
draw.rectangle([s(20), s(62), s(84), s(64)], fill=(159, 168, 218, 255))

# ── 10. Moneda (círculo) ──────────────────────────────────────────────────────
cx_c, cy_c, r_c = s(68), s(74), s(9)
draw.ellipse([cx_c - r_c, cy_c - r_c, cx_c + r_c, cy_c + r_c],
             fill=(232, 234, 246, 255),
             outline=(159, 168, 218, 255),
             width=int(s(1.5)))

# Símbolo euro (dos rayas horizontales)
lw = max(2, int(s(2)))
draw.line([s(62), s(72), s(69), s(72)], fill=(121, 134, 203, 255), width=lw)
draw.line([s(62), s(76), s(69), s(76)], fill=(121, 134, 203, 255), width=lw)

# ── 11. Badge "+" verde ────────────────────────────────────────────────────────
cx_b, cy_b, r_b = s(76), s(32), s(14)
draw.ellipse([cx_b - r_b, cy_b - r_b, cx_b + r_b, cy_b + r_b],
             fill=(46, 125, 50, 255))

# Highlight interior del badge
hl2 = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
hl2_draw = ImageDraw.Draw(hl2)
hl2_draw.polygon(
    sc([(76, 18), (90, 18), (90, 32), (84, 20)]),
    fill=(255, 255, 255, 20)
)
img = Image.alpha_composite(img, hl2)
draw = ImageDraw.Draw(img)

# Cruz "+" — barra vertical
bar_w = max(2, int(s(5)))
draw.rectangle([s(73.5), s(21), s(78.5), s(43)], fill=(255, 255, 255, 255))
# Cruz "+" — barra horizontal
draw.rectangle([s(65), s(29.5), s(87), s(34.5)], fill=(255, 255, 255, 255))

# ── 12. Guardar ───────────────────────────────────────────────────────────────
out_path = "ic_launcher_512.png"
img.save(out_path, "PNG", optimize=True)
print(f"Icono generado: {out_path}  ({SIZE}x{SIZE} px)")
