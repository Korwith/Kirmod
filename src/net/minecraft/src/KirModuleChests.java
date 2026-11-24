package net.minecraft.src;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Chest Models: "Legacy" draws chests and ender chests as plain 16x16x16 textured blocks with no
 * opening animation, which is also cheaper to render.
 *
 * The chest blocks are swapped for subclasses that switch to the normal block renderer in legacy mode,
 * and their tile entity renderers for ones that draw nothing in legacy mode. The renderers also swap in
 * the Festive Chests textures (KirModuleFestiveChests).
 */
public class KirModuleChests extends KirModule
{
    private static final String[] VALUES = new String[] {"Normal", "Legacy"};

    /*
     * Legacy chest textures. These are plain 16x16 PNGs on the classpath (lib/kirmod/ in MCP, the
     * kirmod/ folder in the mod zip for a release). Edit the PNGs to change the look; the paths are
     * here so other code can reuse them.
     */
    public static final String TEXTURE_DIR = "/kirmod/";
    public static final String TEX_CHEST_TOP = TEXTURE_DIR + "chest_top.png";
    public static final String TEX_CHEST_SIDE = TEXTURE_DIR + "chest_side.png";
    public static final String TEX_CHEST_FRONT = TEXTURE_DIR + "chest_front.png";
    public static final String TEX_CHEST_DOUBLE_FRONT_LEFT = TEXTURE_DIR + "chest_double_front_left.png";
    public static final String TEX_CHEST_DOUBLE_FRONT_RIGHT = TEXTURE_DIR + "chest_double_front_right.png";
    public static final String TEX_CHEST_DOUBLE_BACK_LEFT = TEXTURE_DIR + "chest_double_back_left.png";
    public static final String TEX_CHEST_DOUBLE_BACK_RIGHT = TEXTURE_DIR + "chest_double_back_right.png";
    public static final String TEX_ENDER_CHEST_TOP = TEXTURE_DIR + "enderchest_top.png";
    public static final String TEX_ENDER_CHEST_SIDE = TEXTURE_DIR + "enderchest_side.png";
    public static final String TEX_ENDER_CHEST_FRONT = TEXTURE_DIR + "enderchest_front.png";

    /**
     * Festive Chests textures: chest.png, largechest.png and enderchest.png in the layout of /item/, and
     * Legacy tiles named like the ones above, plus chest_double_top_{west,east,north,south}.png for the
     * halves of a double chest (plain double chests use chest_top.png there).
     */
    public static final String FESTIVE_DIR = TEXTURE_DIR + "festive/";

    /** terrain.png sprite indices of the Legacy tiles; -1 until install() runs. */
    public static int chestTop = -1;
    public static int chestSide = -1;
    public static int chestFront = -1;
    public static int chestDoubleFrontLeft = -1;
    public static int chestDoubleFrontRight = -1;
    public static int chestDoubleBackLeft = -1;
    public static int chestDoubleBackRight = -1;
    public static int enderChestTop = -1;
    public static int enderChestSide = -1;
    public static int enderChestFront = -1;
    public static int chestDoubleTopWest = -1;
    public static int chestDoubleTopEast = -1;
    public static int chestDoubleTopNorth = -1;
    public static int chestDoubleTopSouth = -1;

    /** The Legacy tiles as (sprite index, plain texture, festive texture), drawn by tilesFX. */
    private static final List tiles = new ArrayList();
    private static final TilesFX tilesFX = new TilesFX(tiles, "Legacy chest")
    {
        protected boolean useAlternate()
        {
            return mod_Kirmod.FESTIVE_CHESTS.isFestive();
        }
    };

    private static final Field fieldSpecialRenderers = KirUtil.findField(TileEntityRenderer.class, "specialRendererMap", "m");

    private boolean available = false;

    public KirModuleChests()
    {
        super("chestModels", "Chest Models", "Chest Models", 0, "chests");
    }

    public String getCategory()
    {
        return CATEGORY_VISUALS;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Changes how chests and ender chests are drawn.",
            describeValue("Normal", "vanilla 3D chest models with an opening lid."),
            describeValue("Legacy", "plain full-block chests like old Minecraft, with no lid animation. Faster to draw when there are lots of chests.")
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

    /** True when chests render as plain textured cubes. */
    public boolean useLegacyChests()
    {
        return this.getValue() == 1 && this.available && this.enabled && chestTop >= 0;
    }

    protected void onValueChanged()
    {
        KirUtil.reloadRenderers();
    }

    public void install()
    {
        chestTop = addTile(TEX_CHEST_TOP, "chest_top.png");
        chestSide = addTile(TEX_CHEST_SIDE, "chest_side.png");
        chestFront = addTile(TEX_CHEST_FRONT, "chest_front.png");
        chestDoubleFrontLeft = addTile(TEX_CHEST_DOUBLE_FRONT_LEFT, "chest_double_front_left.png");
        chestDoubleFrontRight = addTile(TEX_CHEST_DOUBLE_FRONT_RIGHT, "chest_double_front_right.png");
        chestDoubleBackLeft = addTile(TEX_CHEST_DOUBLE_BACK_LEFT, "chest_double_back_left.png");
        chestDoubleBackRight = addTile(TEX_CHEST_DOUBLE_BACK_RIGHT, "chest_double_back_right.png");
        chestDoubleTopWest = addTile(TEX_CHEST_TOP, "chest_double_top_west.png");
        chestDoubleTopEast = addTile(TEX_CHEST_TOP, "chest_double_top_east.png");
        chestDoubleTopNorth = addTile(TEX_CHEST_TOP, "chest_double_top_north.png");
        chestDoubleTopSouth = addTile(TEX_CHEST_TOP, "chest_double_top_south.png");
        enderChestTop = addTile(TEX_ENDER_CHEST_TOP, "enderchest_top.png");
        enderChestSide = addTile(TEX_ENDER_CHEST_SIDE, "enderchest_side.png");
        enderChestFront = addTile(TEX_ENDER_CHEST_FRONT, "enderchest_front.png");

        if (fieldSpecialRenderers == null)
        {
            KirUtil.log("Legacy chests are unavailable (tile entity renderers not found)", (Throwable)null);
            return;
        }

        try
        {
            boolean var1 = KirUtil.replaceBlock(Block.chest, new KirBlockChest(Block.chest.blockID), "chest", "au");
            boolean var2 = KirUtil.replaceBlock(Block.enderChest, new KirBlockEnderChest(Block.enderChest.blockID), "enderChest", "bS");
            Map var3 = (Map)fieldSpecialRenderers.get(TileEntityRenderer.instance);
            TileEntitySpecialRenderer var4 = new KirChestRenderer();
            TileEntitySpecialRenderer var5 = new KirEnderChestRenderer();
            var4.setTileEntityRenderer(TileEntityRenderer.instance);
            var5.setTileEntityRenderer(TileEntityRenderer.instance);
            var3.put(TileEntityChest.class, var4);
            var3.put(TileEntityEnderChest.class, var5);
            this.available = var1 && var2;
        }
        catch (Exception var6)
        {
            KirUtil.log("Could not install legacy chests", var6);
        }
    }

    public static class KirBlockChest extends BlockChest
    {
        public KirBlockChest(int par1)
        {
            super(KirUtil.constructSlot(par1));
        }

        public int getRenderType()
        {
            return mod_Kirmod.CHEST_MODELS.useLegacyChests() ? 0 : super.getRenderType();
        }

        public boolean shouldSideBeRendered(IBlockAccess par1IBlockAccess, int par2, int par3, int par4, int par5)
        {
            return mod_Kirmod.CHEST_MODELS.useLegacyChests() && par1IBlockAccess.getBlockId(par2, par3, par4) == this.blockID ? false : super.shouldSideBeRendered(par1IBlockAccess, par2, par3, par4, par5);
        }

        public int getBlockTexture(IBlockAccess par1IBlockAccess, int par2, int par3, int par4, int par5)
        {
            return mod_Kirmod.CHEST_MODELS.useLegacyChests() ? getChestTexture(par1IBlockAccess, par2, par3, par4, par5) : super.getBlockTexture(par1IBlockAccess, par2, par3, par4, par5);
        }

        public int getBlockTextureFromSide(int par1)
        {
            return mod_Kirmod.CHEST_MODELS.useLegacyChests() ? (par1 < 2 ? chestTop : (par1 == 3 ? chestFront : chestSide)) : super.getBlockTextureFromSide(par1);
        }
    }

    public static class KirBlockEnderChest extends BlockEnderChest
    {
        public KirBlockEnderChest(int par1)
        {
            super(KirUtil.constructSlot(par1));
        }

        public int getRenderType()
        {
            return mod_Kirmod.CHEST_MODELS.useLegacyChests() ? 0 : super.getRenderType();
        }

        public int getBlockTextureFromSideAndMetadata(int par1, int par2)
        {
            if (!mod_Kirmod.CHEST_MODELS.useLegacyChests())
            {
                return super.getBlockTextureFromSideAndMetadata(par1, par2);
            }

            // Metadata is the facing; it is 0 for the item, whose front is side 3.
            int var3 = par2 >= 2 && par2 <= 5 ? par2 : 3;
            return par1 < 2 ? enderChestTop : (par1 == var3 ? enderChestFront : enderChestSide);
        }
    }

    public static class KirChestRenderer extends TileEntityChestRenderer
    {
        public void renderTileEntityAt(TileEntity par1TileEntity, double par2, double par4, double par6, float par8)
        {
            if (!mod_Kirmod.CHEST_MODELS.useLegacyChests())
            {
                super.renderTileEntityAt(par1TileEntity, par2, par4, par6, par8);
            }
        }

        protected void bindTextureByName(String par1Str)
        {
            super.bindTextureByName(getModelTexture(par1Str));
        }
    }

    public static class KirEnderChestRenderer extends TileEntityEnderChestRenderer
    {
        public void renderTileEntityAt(TileEntity par1TileEntity, double par2, double par4, double par6, float par8)
        {
            if (!mod_Kirmod.CHEST_MODELS.useLegacyChests())
            {
                super.renderTileEntityAt(par1TileEntity, par2, par4, par6, par8);
            }
        }

        protected void bindTextureByName(String par1Str)
        {
            super.bindTextureByName(getModelTexture(par1Str));
        }
    }

    /** The texture to draw a chest model with in place of a vanilla /item/ one (chest.png etc.). */
    private static String getModelTexture(String path)
    {
        return mod_Kirmod.FESTIVE_CHESTS.isFestive() && path.startsWith("/item/") ? FESTIVE_DIR + path.substring(6) : path;
    }

    /** Reserves a terrain.png sprite for a Legacy tile drawn by tilesFX; festive is a name in FESTIVE_DIR. */
    private static int addTile(String plain, String festive)
    {
        int var2 = ModLoader.getUniqueSpriteIndex("/terrain.png");
        tiles.add(new Object[] {Integer.valueOf(var2), plain, FESTIVE_DIR + festive});
        return var2;
    }

    /** The Legacy tile animation, added through ModLoader.addAnimation(); see mod_Kirmod.registerAnimation(). */
    static TextureFX getTilesFX(Minecraft mc)
    {
        return tilesFX.readd(mc);
    }

    /**
     * Draws fixed tiles into terrain.png, scaled to the atlas' tile size: the Legacy chest tiles (plain or
     * festive), and Legacy Storage Blocks' tiles. Like ModLoader's texture overrides it writes them every
     * tick, since terrain.png is read again from the pack now and then. tileSize 0 keeps RenderEngine
     * (and Forge) from writing a tile of its own.
     */
    static class TilesFX extends TextureFX
    {
        /** Each tile as (sprite index, image path, alternate image path). */
        private final List tiles;

        /** What the tiles are, for the log. */
        private final String name;

        /** Image then alternate image of each tile, read from the pack on the next tick when null. */
        BufferedImage[] images;
        private ByteBuffer[] buffers;
        private int bufferTile;
        private boolean bufferFestive;
        private boolean bufferAnaglyph;
        boolean failed;

        TilesFX(List tiles, String name)
        {
            super(0);
            this.tiles = tiles;
            this.name = name;
            this.tileSize = 0;
            this.tileImage = 0;
        }

        /**
         * Gets the animation ready to be added through ModLoader.addAnimation() again: the textures are read
         * again (the new texture pack may have its own) and a failure is retried.
         *
         * Forge (FML) keeps one animation per (iconIndex, tileImage) and drops the others, so each TilesFX
         * takes its first sprite as its iconIndex; with tileSize 0 nothing is written there for it.
         */
        TextureFX readd(Minecraft mc)
        {
            this.images = null;
            this.failed = false;

            if (!this.tiles.isEmpty())
            {
                this.iconIndex = ((Integer)((Object[])this.tiles.get(0))[0]).intValue();
            }

            return KirTexturePacks.readdAnimation(mc, this);
        }

        /** True to draw each tile's alternate image (the festive one, for chests). */
        protected boolean useAlternate()
        {
            return false;
        }

        public void onTick()
        {
            if (this.failed)
            {
                return;
            }

            try
            {
                this.write();
            }
            catch (Throwable var2)
            {
                // Logged once: retrying would spam the log every tick.
                this.failed = true;
                KirUtil.log("Could not draw the " + this.name + " textures", var2);
            }
        }

        private void write() throws Exception
        {
            RenderEngine var1 = ModLoader.getMinecraftInstance().renderEngine;

            if (this.images == null)
            {
                this.images = new BufferedImage[this.tiles.size() * 2];

                for (int var2 = 0; var2 < this.tiles.size(); ++var2)
                {
                    Object[] var3 = (Object[])this.tiles.get(var2);
                    this.images[var2 * 2] = ModLoader.loadImage(var1, (String)var3[1]);
                    this.images[var2 * 2 + 1] = ModLoader.loadImage(var1, (String)var3[2]);
                }

                this.buffers = null;
            }

            this.bindImage(var1);
            int var8 = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) / 16;
            boolean var9 = this.useAlternate();

            if (this.buffers == null || this.bufferTile != var8 || this.bufferFestive != var9 || this.bufferAnaglyph != this.anaglyphEnabled)
            {
                this.buffers = new ByteBuffer[this.tiles.size()];
                this.bufferTile = var8;
                this.bufferFestive = var9;
                this.bufferAnaglyph = this.anaglyphEnabled;

                for (int var4 = 0; var4 < this.tiles.size(); ++var4)
                {
                    this.buffers[var4] = toBuffer(this.images[var4 * 2 + (var9 ? 1 : 0)], var8, this.anaglyphEnabled);
                }
            }

            for (int var10 = 0; var10 < this.tiles.size(); ++var10)
            {
                int var5 = ((Integer)((Object[])this.tiles.get(var10))[0]).intValue();
                this.buffers[var10].rewind();
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, var5 % 16 * var8, var5 / 16 * var8, var8, var8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.buffers[var10]);
            }
        }

        /** An image scaled to size x size as RGBA bytes, grey for 3D anaglyph like ModTextureStatic. */
        private static ByteBuffer toBuffer(BufferedImage image, int size, boolean anaglyph)
        {
            BufferedImage var3 = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D var4 = var3.createGraphics();
            var4.drawImage(image, 0, 0, size, size, 0, 0, image.getWidth(), image.getHeight(), (ImageObserver)null);
            var4.dispose();
            int[] var5 = var3.getRGB(0, 0, size, size, (int[])null, 0, size);
            ByteBuffer var6 = ByteBuffer.allocateDirect(var5.length * 4);

            for (int var7 = 0; var7 < var5.length; ++var7)
            {
                int var8 = var5[var7] >> 16 & 255;
                int var9 = var5[var7] >> 8 & 255;
                int var10 = var5[var7] & 255;

                if (anaglyph)
                {
                    var8 = var9 = var10 = (var8 + var9 + var10) / 3;
                }

                var6.put((byte)var8).put((byte)var9).put((byte)var10).put((byte)(var5[var7] >>> 24));
            }

            var6.flip();
            return var6;
        }
    }

    /** Legacy texture for one face of a chest in the world, joining double chests. */
    public static int getChestTexture(IBlockAccess world, int x, int y, int z, int side)
    {
        int var5 = world.getBlockId(x, y, z);
        int var6 = world.getBlockMetadata(x, y, z);
        int var7 = var6 >= 2 && var6 <= 5 ? var6 : 3;
        int var8 = 0;
        int var9 = 0;

        if (world.getBlockId(x - 1, y, z) == var5)
        {
            var8 = -1;
        }
        else if (world.getBlockId(x + 1, y, z) == var5)
        {
            var8 = 1;
        }
        else if (world.getBlockId(x, y, z - 1) == var5)
        {
            var9 = -1;
        }
        else if (world.getBlockId(x, y, z + 1) == var5)
        {
            var9 = 1;
        }

        if (side < 2)
        {
            // Top and bottom: the half on the side away from the other chest.
            return var8 != 0 ? (var8 < 0 ? chestDoubleTopEast : chestDoubleTopWest) : (var9 < 0 ? chestDoubleTopSouth : (var9 > 0 ? chestDoubleTopNorth : chestTop));
        }

        if (var8 == 0 && var9 == 0)
        {
            return side == var7 ? chestFront : chestSide;
        }

        // The ends of a double chest use the single chest side.
        if (var8 != 0 ? side == 4 || side == 5 : side == 2 || side == 3)
        {
            return chestSide;
        }

        // Direction to the right of someone looking at this face from outside.
        int var10 = side == 3 ? 1 : (side == 2 ? -1 : 0);
        int var11 = side == 4 ? 1 : (side == 5 ? -1 : 0);
        boolean var12 = var8 * var10 + var9 * var11 < 0;
        return side == var7 ? (var12 ? chestDoubleFrontRight : chestDoubleFrontLeft) : (var12 ? chestDoubleBackRight : chestDoubleBackLeft);
    }
}
