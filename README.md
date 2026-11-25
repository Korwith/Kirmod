# Kirmod
Performance enhancement and improvement mod for Minecraft 1.3.2.

## Building

Every push is built by GitHub Actions with [MCPCommit](https://github.com/Korwith/MCPCommit) (`Korwith/MCPCommit@main`,
so each run uses MCPCommit's main branch as it is at that moment). The settings are in `omnimcp.json`:

- MCP 7.2 decompiles Minecraft 1.3.2 with Risugami's ModLoader merged in (downloaded from archive.org, checked by sha256).
- `src/` is the mod. `ci/libs/` (CommandLib stubs) is compiled with it but not shipped; Kirmod Extras and Storage
  Spaces also compile against it.
- `ci/patches/client/` makes vanilla `Tessellator`'s constructor protected so `KirTessellator` compiles; `Tessellator`
  is excluded, so the release never ships a base class (it would break Forge).
- `resources/` is copied into the zip as-is. The version comes from `mod_Kirmod.getVersion()`.

The result is `mod_Kirmod-<version>.zip` in the run's artifacts, for `.minecraft/mods/`. Pushing a tag like `v1.36`
also publishes it as a GitHub release.

### Kirmod Lite

Every push also builds `mod_Kirmod-Lite-<version>.zip` as a second artifact (and release file). It is the same mod
without the Texture Packs, Skin Packs and Capes screens, their header buttons and commands, and the bundled packs and
capes, so it is much smaller. The block textures (chests, storage blocks, rubies, sticky pistons) and HD texture pack
support stay, and vanilla's texture pack screen works as usual. `ci/make-lite.sh` lays it out in `build/lite/`: a copy
of `src/` with `KirEdition.LITE` set to `true`, `resources/` without `kirmod/texturepacks`, `skins` and `capes`, and an
`omnimcp.json` that leaves the three screens' classes out of the zip.

```bash
ci/make-lite.sh && python3 ../MCPCommit/backend/build_mod.py all --config build/lite/omnimcp.json --out dist
```

To build locally (Python 3.8+, JDK 8):

```bash
python3 ../MCPCommit/backend/build_mod.py all --config omnimcp.json --out dist
```
