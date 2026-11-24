package net.minecraft.src;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Sticky Piston Sides: "On" gives sticky pistons a side texture of their own, with the wooden head
 * painted slime green, so they can be told from normal pistons from the side. In vanilla both use tile
 * 108.
 *
 * The sticky piston block is swapped for a subclass that returns the new tile for its sides, and the
 * piston head for one that does the same for a sticky head (metadata bit 8), so an extended or moving
 * sticky piston matches. The tile is kirmod/sticky_piston_side.png (piston_side_sticky.png from the
 * Programmer Art Redstone Tweaks pack, after Vanilla Tweaks' StickyPistonSides), drawn into terrain.png
 * by a KirModuleChests.TilesFX.
 */
public class KirModuleStickyPistons extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    public static final String TEX_STICKY_SIDE = KirModuleChests.TEXTURE_DIR + "sticky_piston_side.png";

    /** Vanilla's piston side tile, shared by both pistons and the head. */
    private static final int PISTON_SIDE = 108;

    /** terrain.png sprite index of the sticky side tile; -1 until install() runs. */
    public static int stickySide = -1;

    private static final List tiles = new ArrayList();
    private static final KirModuleChests.TilesFX tilesFX = new KirModuleChests.TilesFX(tiles, "sticky piston side");

    private boolean available = false;

    public KirModuleStickyPistons()
    {
        super("stickyPistonSides", "Sticky Piston Sides", "Sticky Sides", 0, "stickysides", "stickypistons");
    }

    public String getCategory()
    {
        return CATEGORY_VISUALS;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Gives sticky pistons their own side texture, with a slime green head, so you can tell them from normal pistons from the side.",
            describeValue("Off", "sticky pistons look like normal pistons from the side, as in vanilla."),
            describeValue("On", "the head of a sticky piston is green on its sides too, extended or not."),
            note("Texture from Programmer Art Redstone Tweaks (Vanilla Tweaks' Sticky Piston Sides).")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return this.available;
    }

    public boolean useStickySide()
    {
        return this.getValue() == 1 && this.available && this.enabled;
    }

    protected void onValueChanged()
    {
        KirUtil.reloadRenderers();
    }

    public void install()
    {
        stickySide = ModLoader.getUniqueSpriteIndex("/terrain.png");
        tiles.add(new Object[] {Integer.valueOf(stickySide), TEX_STICKY_SIDE, TEX_STICKY_SIDE});

        try
        {
            Block var1 = Block.pistonStickyBase;
            boolean var2 = KirUtil.replaceBlock(var1, new KirBlockStickyPiston(var1.blockID, var1.blockIndexInTexture), "pistonStickyBase", "V");
            Block var3 = Block.pistonExtension;
            KirUtil.replaceBlock(var3, new KirBlockPistonHead(var3.blockID, var3.blockIndexInTexture), "pistonExtension", "aa");
            this.available = var2;
        }
        catch (Exception var5)
        {
            KirUtil.log("Could not install sticky piston sides", var5);
        }
    }

    /** The side tile animation, added through ModLoader.addAnimation(); see mod_Kirmod.registerAnimation(). */
    static TextureFX getTilesFX(Minecraft mc)
    {
        return tilesFX.readd(mc);
    }

    static boolean hasTiles()
    {
        return !tiles.isEmpty();
    }

    public static class KirBlockStickyPiston extends BlockPistonBase
    {
        public KirBlockStickyPiston(int par1, int par2)
        {
            super(KirUtil.constructSlot(par1), par2, true);
        }

        public int getBlockTextureFromSideAndMetadata(int par1, int par2)
        {
            int var3 = super.getBlockTextureFromSideAndMetadata(par1, par2);
            return var3 == PISTON_SIDE && mod_Kirmod.STICKY_PISTONS.useStickySide() ? stickySide : var3;
        }
    }

    public static class KirBlockPistonHead extends BlockPistonExtension
    {
        public KirBlockPistonHead(int par1, int par2)
        {
            super(KirUtil.constructSlot(par1), par2);
        }

        /** Metadata bit 8 marks the head of a sticky piston. */
        public int getBlockTextureFromSideAndMetadata(int par1, int par2)
        {
            int var3 = super.getBlockTextureFromSideAndMetadata(par1, par2);
            return var3 == PISTON_SIDE && (par2 & 8) != 0 && mod_Kirmod.STICKY_PISTONS.useStickySide() ? stickySide : var3;
        }
    }
}
