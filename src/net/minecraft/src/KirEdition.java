package net.minecraft.src;

/**
 * Which Kirmod build this is. The regular build has LITE = false. Kirmod Lite is built by
 * ci/make-lite.sh, which copies the sources, sets LITE = true here and leaves out the bundled texture
 * packs, skin packs and capes.
 *
 * Kirmod Lite has every feature of Kirmod except the Texture Packs, Skin Packs and Capes screens (and the
 * header buttons and commands that open them). The Legacy block textures (chests, storage blocks, rubies,
 * sticky pistons) and HD texture pack support stay. Vanilla's texture pack screen is left in place, so the
 * player can still use their own packs from .minecraft/texturepacks/.
 *
 * LITE is a compile-time constant, so javac drops the code it guards.
 */
public final class KirEdition
{
    public static final boolean LITE = false;

    private KirEdition()
    {
    }
}
