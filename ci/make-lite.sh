#!/usr/bin/env bash
# Lays out Kirmod Lite in OUT (default build/lite) for the OmniMCP action: a copy of src/ with
# KirEdition.LITE set to true, resources/ without the bundled texture packs, skin packs and capes, and an
# omnimcp.json that builds them. The block textures (chests, storage blocks, rubies, sticky pistons,
# festive chests) are kept. Build it with: config: build/lite/omnimcp.json
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build/lite}"

rm -rf "$OUT"
mkdir -p "$OUT"

cp -R "$ROOT/src" "$OUT/src"
EDITION="$OUT/src/net/minecraft/src/KirEdition.java"
sed -i 's/public static final boolean LITE = false;/public static final boolean LITE = true;/' "$EDITION"
grep -q 'public static final boolean LITE = true;' "$EDITION" || { echo "could not set KirEdition.LITE" >&2; exit 1; }

mkdir -p "$OUT/resources"
cp -R "$ROOT/resources/." "$OUT/resources/"
rm -rf "$OUT/resources/kirmod/texturepacks" "$OUT/resources/kirmod/skins" "$OUT/resources/kirmod/capes"

python3 - "$ROOT" "$OUT" <<'EOF'
import json, os, sys

root, out = sys.argv[1], sys.argv[2]
cfg = json.load(open(os.path.join(root, 'omnimcp.json')))

def rel(path):
    """A path from the original config, relative to the new one."""
    return os.path.relpath(os.path.join(root, path), out)

for key in ('libraries', 'patches'):
    if key in cfg:
        cfg[key] = {side: rel(p) for side, p in cfg[key].items()}

# Sources and resources are the lite copies next to the new config; the version is read from there too.
# The menus Lite leaves out are still compiled (KirCapes and others refer to them) but not shipped.
cfg['exclude'] = list(cfg.get('exclude') or []) + [
    'net/minecraft/src/GuiKirmodTexturePacks',
    'net/minecraft/src/GuiKirmodSkins',
    'net/minecraft/src/GuiKirmodCapes',
]
cfg['filename'] =cfg.get('filename', '{name}-{version}.zip').replace('{name}', '{name}-Lite')

json.dump(cfg, open(os.path.join(out, 'omnimcp.json'), 'w'), indent=2)
EOF

echo "Kirmod Lite laid out in $OUT"
