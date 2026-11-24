package net.minecraft.src;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Legacy Storage Blocks: "On" draws iron, gold, diamond and emerald blocks the way old Minecraft did
 * (up to Beta 1.1): a top, a side whose lower half is shaded darker, and a darker bottom. Emerald blocks
 * never had those, so theirs are the diamond ones in emerald green.
 *
 * The four blocks are swapped for subclasses that pick a Legacy tile per face while the setting is on.
 * The tiles are 16x16 PNGs in kirmod/storage/ (made by tools/convert_legacy_storage_blocks.py), drawn
 * into terrain.png by a KirModuleChests.TilesFX like the Legacy chest tiles, so they cost 12 sprites.
 * While Rubies is on, the emerald block's tiles are drawn from the red kirmod/ruby/ruby_*.png instead.
 */
public class KirModuleStorageBlocks extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    public static final String TEXTURE_DIR = KirModuleChests.TEXTURE_DIR + "storage/";

    /** The Legacy tiles as (sprite index, texture, texture), drawn by tilesFX. */
    private static final List tiles = new ArrayList();
    private static final KirModuleChests.TilesFX tilesFX = new KirModuleChests.TilesFX(tiles, "Legacy storage block")
    {
        /** The alternate images are the ruby ones for the emerald block (see KirModuleRubies). */
        protected boolean useAlternate()
        {
            return mod_Kirmod.RUBIES.showRubies();
        }
    };

    private boolean available = false;

    public KirModuleStorageBlocks()
    {
        super("legacyStorageBlocks", "Legacy Storage Blocks", "Legacy Storage", 0, "storageblocks", "legacystorage");
    }

    public String getCategory()
    {
        return CATEGORY_VISUALS;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Draws iron, gold, diamond and emerald blocks like old Minecraft: a shaded lower half on each side and a darker bottom.",
            describeValue("Off", "the blocks look as in your texture pack."),
            describeValue("On", "the old Beta 1.1 textures, and a matching emerald block. These replace your texture pack's.")
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

    /** True when the storage blocks use the Legacy tiles. */
    public boolean useLegacyTextures()
    {
        return this.getValue() == 1 && this.available && this.enabled;
    }

    protected void onValueChanged()
    {
        KirUtil.reloadRenderers();
    }

    public void install()
    {
        try
        {
            boolean var1 = replace(Block.blockSteel, "iron", "blockSteel", "ai");
            boolean var2 = replace(Block.blockGold, "gold", "blockGold", "ah");
            boolean var3 = replace(Block.blockDiamond, "diamond", "blockDiamond", "ax");
            boolean var4 = replace(Block.blockEmerald, "emerald", "blockEmerald", "bV");
            this.available = var1 || var2 || var3 || var4;
        }
        catch (Exception var5)
        {
            KirUtil.log("Could not install legacy storage blocks", var5);
        }
    }

    /** Swaps one storage block for a KirBlockStorage with its three Legacy tiles. */
    private static boolean replace(Block original, String name, String mcpName, String obfName) throws IllegalAccessException
    {
        String var7 = name.equals("emerald") ? KirModuleRubies.TEXTURE_DIR + "ruby" : TEXTURE_DIR + name;
        int var4 = addTile(TEXTURE_DIR + name + "_top.png", var7 + "_top.png");
        int var5 = addTile(TEXTURE_DIR + name + "_side.png", var7 + "_side.png");
        int var6 = addTile(TEXTURE_DIR + name + "_bottom.png", var7 + "_bottom.png");
        return KirUtil.replaceBlock(original, new KirBlockStorage(original.blockID, original.blockIndexInTexture, var4, var5, var6), mcpName, obfName);
    }

    /** Reserves a terrain.png sprite for a Legacy tile drawn by tilesFX; ruby is its image while Rubies is on. */
    private static int addTile(String file, String ruby)
    {
        int var1 = ModLoader.getUniqueSpriteIndex("/terrain.png");
        tiles.add(new Object[] {Integer.valueOf(var1), file, ruby});
        return var1;
    }

    /** The Legacy tile animation, added through ModLoader.addAnimation(); see mod_Kirmod.registerAnimation(). */
    static TextureFX getTilesFX(Minecraft mc)
    {
        return tilesFX.readd(mc);
    }

    static boolean hasTiles()
    {
        return !tiles.isEmpty();
    }

    public static class KirBlockStorage extends BlockOreStorage
    {
        private final int top;
        private final int side;
        private final int bottom;

        /** The Ruby Block tile, set by KirModuleRubies; -1 without one. */
        int rubyTile = -1;

        public KirBlockStorage(int par1, int par2, int top, int side, int bottom)
        {
            super(KirUtil.constructSlot(par1), par2);
            this.top = top;
            this.side = side;
            this.bottom = bottom;
        }

        /** Side 0 is the bottom and 1 the top; the item in the inventory uses this too. */
        public int getBlockTextureFromSide(int par1)
        {
            if (mod_Kirmod.STORAGE_BLOCKS.useLegacyTextures() && this.top >= 0)
            {
                return par1 == 1 ? this.top : (par1 == 0 ? this.bottom : this.side);
            }

            return this.rubyTile >= 0 && mod_Kirmod.RUBIES.showRubies() ? this.rubyTile : super.getBlockTextureFromSide(par1);
        }
    }
}
