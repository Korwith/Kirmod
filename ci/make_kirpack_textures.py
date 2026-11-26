"""Makes Kirmod's ore outline, ruby ore and beta grass tiles from Kirpack (argv: vanilla terrain.png, Kirpack dir, Kirpack Dark dir, out resources/kirmod)."""
import os, sys
from PIL import Image

van = Image.open(sys.argv[1]).convert('RGBA')
kp = Image.open(os.path.join(sys.argv[2], 'terrain.png')).convert('RGBA')
dark_bg = os.path.join(sys.argv[3], 'gui', 'background.png')
out = sys.argv[4]

def tile(im, i):
    ts = im.size[0] // 16
    return im.crop((i % 16 * ts, i // 16 * ts, i % 16 * ts + ts, i // 16 * ts + ts))

def save(im, *path):
    p = os.path.join(out, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p)

# Kirpack's outlined ores.
for name, i in [('gold', 32), ('iron', 33), ('coal', 34), ('diamond', 50), ('redstone', 51), ('lapis', 160)]:
    save(tile(kp, i), 'ores', name + '_outline.png')

# Kirpack has no emerald outline: the diamond's light/dark border pattern in emerald greens.
diamond = tile(kp, 50)
LIGHT_D = (0x5d, 0xec, 0xf5)

def outlined(base, light, dark):
    im = base.copy()
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                im.putpixel((x, y), (light if diamond.getpixel((x, y))[:3] == LIGHT_D else dark) + (255,))
    return im

emerald = tile(van, 171)
save(outlined(emerald, (23, 221, 98), (0, 123, 24)), 'ores', 'emerald_outline.png')

# Ruby ore: the emerald ore's gems in the Ruby Block's reds.
RUBY = {(23, 221, 98): (220, 36, 54), (217, 255, 235): (255, 214, 219), (0, 123, 24): (128, 12, 28), (95, 173, 125): (176, 92, 102)}
ruby = emerald.copy()
for y in range(16):
    for x in range(16):
        p = ruby.getpixel((x, y))
        if p[:3] in RUBY:
            ruby.putpixel((x, y), RUBY[p[:3]] + (p[3],))
save(ruby, 'ruby', 'ruby_ore.png')
save(outlined(ruby, (220, 36, 54), (128, 12, 28)), 'ruby', 'ruby_ore_outline.png')

# Beta Grass: Kirpack's grass top (tinted by the biome color like vanilla's).
save(tile(kp, 0), 'grass', 'grass_top_beta.png')

# Dark Theme: Kirpack Dark's menu background, for the vanilla settings screens.
save(Image.open(dark_bg).convert('RGBA'), 'dark', 'background.png')

# Legacy block textures from Kirpack, for the Textures group.
for name, i in [('cobblestone_beta', 16), ('mossy_cobblestone_beta', 36), ('bricks_alpha', 7), ('mob_spawner_beta', 65),
                ('gravel_legacy', 19), ('end_stone_legacy', 175), ('glowstone_legacy', 105)]:
    save(tile(kp, i), 'legacy', name + '.png')

WOOL = [('orange', 210), ('magenta', 194), ('light_blue', 178), ('yellow', 162), ('lime', 146), ('pink', 130), ('gray', 114),
        ('light_gray', 225), ('cyan', 209), ('purple', 193), ('blue', 177), ('brown', 161), ('green', 145), ('red', 129), ('black', 113)]
for name, i in WOOL:
    save(tile(kp, i), 'legacy', 'wool_' + name + '.png')
