package net.minecraft.src;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Draws Kirmod's replacement tiles over vanilla's own terrain.png tiles, in place (1.37): the Textures
 * group (Ore Outlines, Grass Texture, Cobblestone, Bricks, Mob Spawner, Wool, End Stone, Gravel, Glowstone) and the
 * Ruby Ore of Rubies. Unlike KirModuleChests.TilesFX it reserves no sprites (ModLoader has only 32 free
 * terrain sprites and Kirmod already uses most of them) and swaps no blocks: the blocks keep their tile
 * index and the tile itself is drawn over.
 *
 * Every tick each managed tile is asked for its image (getImage); a tile with one gets it written, scaled
 * to the atlas' tile size. A tile that just lost its image (a setting turned off) gets the texture pack's
 * own tile back, read from the pack's terrain.png, once. A texture pack change uploads the pack's
 * terrain.png again, so readd() forgets what was written and reads the pack's tiles again.
 */
public class KirTerrainOverlay extends TextureFX
{
    /** The tiles this draws: vanilla's ores, grass, cobblestone... */
    static final int[] TILES = new int[] {
        32, 33, 34, 50, 51, 160, 171, // ores (gold, iron, coal, diamond, redstone, lapis, emerald)
        0,                            // grass top
        16, 36,                       // cobblestone, mossy cobblestone
        7,                            // bricks
        65,                           // mob spawner
        19,                           // gravel
        175,                          // end stone
        105,                          // glowstone
        210, 194, 178, 162, 146, 130, 114, 225, 209, 193, 177, 161, 145, 129, 113 // coloured wool
    };

    private static final KirTerrainOverlay INSTANCE = new KirTerrainOverlay();

    /** Images by path, read on first use and again after a texture pack change. */
    private final Map images = new HashMap();

    /** Each tile's buffer at bufferTile size, by path; cleared when the size or anaglyph changes. */
    private final Map buffers = new HashMap();
    private int bufferTile;
    private boolean bufferAnaglyph;

    /** The path last written to each tile of TILES (null: the pack's own). */
    private final String[] written = new String[TILES.length];

    /** The pack's terrain.png, for putting its tiles back; read when first needed. */
    private BufferedImage packTerrain;
    private boolean failed;

    private KirTerrainOverlay()
    {
        super(0);
        this.tileSize = 0;
        this.tileImage = 0;
        this.iconIndex = TILES[0];
    }

    /** The animation, ready to be added through ModLoader.addAnimation() again; see mod_Kirmod.registerAnimation(). */
    static TextureFX get(Minecraft mc)
    {
        INSTANCE.images.clear();
        INSTANCE.buffers.clear();
        INSTANCE.packTerrain = null;
        INSTANCE.failed = false;

        for (int var1 = 0; var1 < INSTANCE.written.length; ++var1)
        {
            INSTANCE.written[var1] = null;
        }

        return KirTexturePacks.readdAnimation(mc, INSTANCE);
    }

    /** The image to draw over a tile right now, or null for the pack's own. */
    private static String getImage(int tile)
    {
        if (tile == 171)
        {
            // Emerald ore: Rubies' Ruby Ore, with or without Ore Outlines.
            boolean var1 = mod_Kirmod.ORE_OUTLINES.isOn();

            if (mod_Kirmod.RUBIES.showRubies())
            {
                return KirModuleRubies.TEXTURE_DIR + (var1 ? "ruby_ore_outline.png" : "ruby_ore.png");
            }

            return var1 ? KirModuleTexture.ORES_DIR + "emerald_outline.png" : null;
        }

        return KirModuleTexture.getImage(tile);
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
            KirUtil.log("Could not draw Kirmod's block textures", var2);
        }
    }

    private void write() throws Exception
    {
        RenderEngine var1 = ModLoader.getMinecraftInstance().renderEngine;
        boolean var2 = false;
        int var3 = 0;

        for (int var4 = 0; var4 < TILES.length; ++var4)
        {
            String var5 = getImage(TILES[var4]);

            if (var5 == null && this.written[var4] == null)
            {
                continue;
            }

            if (!var2)
            {
                this.bindImage(var1);
                var3 = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) / 16;

                if (var3 != this.bufferTile || this.bufferAnaglyph != this.anaglyphEnabled)
                {
                    this.buffers.clear();
                    this.bufferTile = var3;
                    this.bufferAnaglyph = this.anaglyphEnabled;
                }

                var2 = true;
            }

            ByteBuffer var6 = var5 != null ? this.getBuffer(var1, var5, var3) : this.getPackBuffer(var1, TILES[var4], var3);
            this.written[var4] = var5;

            if (var6 != null)
            {
                var6.rewind();
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, TILES[var4] % 16 * var3, TILES[var4] / 16 * var3, var3, var3, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, var6);
            }
        }
    }

    private ByteBuffer getBuffer(RenderEngine engine, String path, int size) throws Exception
    {
        ByteBuffer var4 = (ByteBuffer)this.buffers.get(path);

        if (var4 == null)
        {
            BufferedImage var5 = (BufferedImage)this.images.get(path);

            if (var5 == null)
            {
                var5 = ModLoader.loadImage(engine, path);
                this.images.put(path, var5);
            }

            var4 = KirModuleChests.TilesFX.toBuffer(var5, size, this.anaglyphEnabled);
            this.buffers.put(path, var4);
        }

        return var4;
    }

    /** The texture pack's own tile, to put back. */
    private ByteBuffer getPackBuffer(RenderEngine engine, int tile, int size) throws Exception
    {
        String var4 = "#pack" + tile;
        ByteBuffer var5 = (ByteBuffer)this.buffers.get(var4);

        if (var5 == null)
        {
            if (this.packTerrain == null)
            {
                this.packTerrain = ModLoader.loadImage(engine, "/terrain.png");
            }

            int var6 = this.packTerrain.getWidth() / 16;
            var5 = KirModuleChests.TilesFX.toBuffer(this.packTerrain.getSubimage(tile % 16 * var6, tile / 16 * var6, var6, var6), size, this.anaglyphEnabled);
            this.buffers.put(var4, var5);
        }

        return var5;
    }
}
