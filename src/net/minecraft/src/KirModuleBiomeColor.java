package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Base of the global color modules (grass, foliage): makes everything that takes its color from one of
 * the game's colormaps the color of one biome.
 *
 * Colors are looked up in the colormap by temperature and rainfall. Filling the whole colormap with one
 * biome's color makes everything that uses it that color. The texture pack's colormap is kept so
 * "Default" can put it back.
 *
 * Changing the texture pack doesn't give the game a new colormap: RenderEngine.refreshTextures() reads
 * the new pack's colormap into the same int[] it handed out at startup. So a copy of it is kept, and
 * when its contents change the biome's color is looked up again in the new pack's colormap.
 *
 * OptiFine doesn't ask ColorizerGrass and ColorizerFoliage: its CustomColorizer keeps its own reference
 * to the pack's colormap (grassColors, foliageColors) and looks colors up in it directly, so with
 * OptiFine only swapping the vanilla field changed nothing. Its field is pointed at the same colormap,
 * and checked every frame, since CustomColorizer.update() points it back at the pack's on a pack change.
 *
 * The settings screen shows a dropdown under the row with a tile per biome, tinted with that biome's
 * color from the pack's colormap, to show what each value looks like; clicking a tile picks it.
 */
public abstract class KirModuleBiomeColor extends KirModule
{
    private static final int TILE = 16;
    private static final int TILE_GAP = 2;
    private static final int PREVIEW_PAD = 4;
    private static final int PREVIEW_LINE_H = 12;

    private static final Class classCustomColorizer = findCustomColorizer();

    private final Field fieldColorBuffer;
    private final Field fieldOptifineBuffer;
    private BiomeGenBase[] biomes;
    private String[] valueNames;
    private int[] originalBuffer;

    /** The colors originalBuffer had when the color was last applied. */
    private int[] originalCopy;
    private int[] kirmodBuffer;
    private int appliedValue = -1;

    /** Each biome's color in the pack's colormap (biomes order), for the preview; null until first worked out. */
    private int[] biomeColors;

    /**
     * @param colorBuffer    the colormap's static int[] field, e.g. ColorizerGrass.grassBuffer
     * @param optifineBuffer the name of OptiFine's CustomColorizer field holding the same colormap
     */
    protected KirModuleBiomeColor(Field colorBuffer, String optifineBuffer, String key, String title, String buttonTitle, String commandName, String ... aliases)
    {
        super(key, title, buttonTitle, 0, commandName, aliases);
        this.fieldColorBuffer = colorBuffer;
        this.fieldOptifineBuffer = findOptifineField(optifineBuffer);
    }

    /** The biome's color from this module's colormap, with the colormap as the texture pack has it. */
    protected abstract int getBiomeColor(BiomeGenBase biome);

    /** Points the game at a colormap. */
    protected abstract void setColorBuffer(int[] buffer);

    /** The terrain.png index of the texture the preview tiles show, tinted (grass top, leaves). */
    protected abstract int getPreviewTexture();

    /** OptiFine's CustomColorizer, or null without OptiFine. It is in the default package, like Minecraft's classes. */
    private static Class findCustomColorizer()
    {
        String[] var0 = new String[] {"CustomColorizer", "net.minecraft.src.CustomColorizer"};

        for (int var1 = 0; var1 < var0.length; ++var1)
        {
            try
            {
                return Class.forName(var0[var1]);
            }
            catch (Throwable var3)
            {
                ;
            }
        }

        return null;
    }

    private static Field findOptifineField(String name)
    {
        if (classCustomColorizer == null)
        {
            return null;
        }

        try
        {
            Field var1 = classCustomColorizer.getDeclaredField(name);
            var1.setAccessible(true);
            return var1.getType() == int[].class ? var1 : null;
        }
        catch (Throwable var2)
        {
            KirUtil.log("OptiFine's CustomColorizer." + name + " not found", (Throwable)null);
            return null;
        }
    }

    /** "Default" followed by the name of every biome. */
    public String[] getValueNames()
    {
        if (this.valueNames == null)
        {
            ArrayList var1 = new ArrayList();

            for (int var2 = 0; var2 < BiomeGenBase.biomeList.length; ++var2)
            {
                if (BiomeGenBase.biomeList[var2] != null)
                {
                    var1.add(BiomeGenBase.biomeList[var2]);
                }
            }

            this.biomes = (BiomeGenBase[])var1.toArray(new BiomeGenBase[var1.size()]);
            this.valueNames = new String[this.biomes.length + 1];
            this.valueNames[0] = "Default";

            for (int var3 = 0; var3 < this.biomes.length; ++var3)
            {
                this.valueNames[var3 + 1] = this.biomes[var3].biomeName;
            }
        }

        return this.valueNames;
    }

    public boolean isAvailable()
    {
        return this.fieldColorBuffer != null;
    }

    public void update(Minecraft mc)
    {
        if (this.fieldColorBuffer == null)
        {
            return;
        }

        try
        {
            int[] var2 = (int[])this.fieldColorBuffer.get((Object)null);

            if (var2 != this.kirmodBuffer && var2 != this.originalBuffer)
            {
                this.originalBuffer = var2;
                this.appliedValue = -1;
            }

            int var3 = this.getValue();
            boolean var4 = this.originalCopy != null && !Arrays.equals(this.originalCopy, this.originalBuffer);

            if (var3 == this.appliedValue && !var4)
            {
                if (this.updateOptifine(var3))
                {
                    KirUtil.reloadRenderers();
                }

                return;
            }

            this.originalCopy = (int[])this.originalBuffer.clone();
            this.setColorBuffer(this.originalBuffer);
            this.getValueNames();

            // With the pack's colormap in place, every biome's color for the preview.
            int[] var5 = new int[this.biomes.length];

            for (int var6 = 0; var6 < var5.length; ++var6)
            {
                var5[var6] = this.getBiomeColor(this.biomes[var6]);
            }

            this.biomeColors = var5;

            if (var3 > 0)
            {
                this.kirmodBuffer = new int[this.originalBuffer.length];
                Arrays.fill(this.kirmodBuffer, var5[var3 - 1]);
                this.setColorBuffer(this.kirmodBuffer);
            }

            this.updateOptifine(var3);

            // Chunks only need building again if a biome's color is or was applied: the first check after
            // startup only records the colormap, and a pack change already rebuilt them.
            if (var3 > 0 || this.appliedValue > 0)
            {
                KirUtil.reloadRenderers();
            }

            this.appliedValue = var3;
        }
        catch (IllegalAccessException var7)
        {
            KirUtil.log("Could not change the " + this.getTitle().toLowerCase(), var7);
        }
    }

    /**
     * Points OptiFine's copy of the colormap at Kirmod's while a biome's color is applied, and back at the
     * pack's for Default; returns whether it changed. OptiFine leaves it null when custom colors are off or the pack has no colormap,
     * and then uses the vanilla colors, so a null is left alone.
     */
    private boolean updateOptifine(int value) throws IllegalAccessException
    {
        if (this.fieldOptifineBuffer == null)
        {
            return false;
        }

        int[] var2 = (int[])this.fieldOptifineBuffer.get((Object)null);

        if (var2 == null)
        {
            return false;
        }

        int[] var3 = value > 0 && this.kirmodBuffer != null ? this.kirmodBuffer : this.originalBuffer;

        if (var2 != var3 && var3 != null)
        {
            // Only Kirmod's buffer or the pack's colormap are put back; one OptiFine loaded itself (a
            // custom colormap from the pack) is replaced only while a biome's color is applied.
            if (value > 0 || var2 == this.kirmodBuffer)
            {
                this.fieldOptifineBuffer.set((Object)null, var3);
                return true;
            }
        }

        return false;
    }

    public int getPreviewHeight(int width)
    {
        int var2 = this.getValueNames().length;
        int var3 = this.getTilesPerRow(width);
        int var4 = (var2 + var3 - 1) / var3;
        return PREVIEW_PAD + PREVIEW_LINE_H + var4 * (TILE + TILE_GAP) - TILE_GAP + PREVIEW_PAD;
    }

    private int getTilesPerRow(int width)
    {
        return Math.max(1, (width - 2 * PREVIEW_PAD + TILE_GAP) / (TILE + TILE_GAP));
    }

    /** The value whose tile is at mouseX, mouseY, or -1. */
    private int getTileAt(int left, int y, int right, int mouseX, int mouseY)
    {
        int var6 = this.getTilesPerRow(right - left);
        int var7 = mouseX - left - PREVIEW_PAD;
        int var8 = mouseY - y - PREVIEW_PAD - PREVIEW_LINE_H;

        if (var7 < 0 || var8 < 0 || var7 % (TILE + TILE_GAP) >= TILE || var8 % (TILE + TILE_GAP) >= TILE)
        {
            return -1;
        }

        int var9 = var7 / (TILE + TILE_GAP);
        int var10 = var8 / (TILE + TILE_GAP);
        int var11 = var10 * var6 + var9;
        return var9 < var6 && var11 < this.getValueNames().length ? var11 : -1;
    }

    public void drawPreview(Minecraft mc, int left, int y, int right, int mouseX, int mouseY)
    {
        String[] var7 = this.getValueNames();
        int var8 = this.getTileAt(left, y, right, mouseX, mouseY);
        int var9 = var8 >= 0 ? var8 : this.getValue();
        String var10 = var9 == 0 ? "Default §7(each biome its own)" : var7[var9];

        if (this.biomeColors != null && var9 > 0)
        {
            var10 = var10 + " §7#" + Integer.toHexString(0x1000000 | this.biomeColors[var9 - 1] & 0xFFFFFF).substring(1).toUpperCase();
        }

        mc.fontRenderer.drawString(mc.fontRenderer.trimStringToWidth((var8 >= 0 ? "§e" : "§f") + var10, right - left - 2 * PREVIEW_PAD), left + PREVIEW_PAD, y + PREVIEW_PAD + 1, 0xFFFFFF);

        if (this.biomeColors == null)
        {
            return;
        }

        int var11 = this.getTilesPerRow(right - left);
        int var12 = mc.renderEngine.getTexture("/terrain.png");

        for (int var13 = 0; var13 < var7.length; ++var13)
        {
            int var14 = left + PREVIEW_PAD + var13 % var11 * (TILE + TILE_GAP);
            int var15 = y + PREVIEW_PAD + PREVIEW_LINE_H + var13 / var11 * (TILE + TILE_GAP);

            if (var13 == this.getValue())
            {
                Gui.drawRect(var14 - 1, var15 - 1, var14 + TILE + 1, var15 + TILE + 1, 0xFF94E4D3);
            }
            else if (var13 == var8)
            {
                Gui.drawRect(var14 - 1, var15 - 1, var14 + TILE + 1, var15 + TILE + 1, 0xFFFFFFFF);
            }

            Gui.drawRect(var14, var15, var14 + TILE, var15 + TILE, 0xFF000000);
            mc.renderEngine.bindTexture(var12);

            if (var13 == 0)
            {
                // Default: a quarter each of plains, forest, jungle and taiga, as they are in the pack.
                int var16 = TILE / 2;
                this.drawTile(var14, var15, 0, 0, var16, this.colorOf(BiomeGenBase.plains));
                this.drawTile(var14, var15, var16, 0, var16, this.colorOf(BiomeGenBase.forest));
                this.drawTile(var14, var15, 0, var16, var16, this.colorOf(BiomeGenBase.jungle));
                this.drawTile(var14, var15, var16, var16, var16, this.colorOf(BiomeGenBase.taiga));
            }
            else
            {
                this.drawTile(var14, var15, 0, 0, TILE, this.biomeColors[var13 - 1]);
            }
        }

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private int colorOf(BiomeGenBase biome)
    {
        for (int var2 = 0; var2 < this.biomes.length; ++var2)
        {
            if (this.biomes[var2] == biome)
            {
                return this.biomeColors[var2];
            }
        }

        return 0xFFFFFF;
    }

    /** Draws part of the preview texture (dx, dy, size, in 16ths of the icon) tinted with color. */
    private void drawTile(int x, int y, int dx, int dy, int size, int color)
    {
        int var7 = this.getPreviewTexture();
        float var8 = (float)(var7 % 16 * 16 + dx) / 256.0F;
        float var9 = (float)(var7 / 16 * 16 + dy) / 256.0F;
        float var10 = (float)size / 256.0F;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glColor4f((float)(color >> 16 & 255) / 255.0F, (float)(color >> 8 & 255) / 255.0F, (float)(color & 255) / 255.0F, 1.0F);
        Tessellator var11 = Tessellator.instance;
        var11.startDrawingQuads();
        var11.addVertexWithUV((double)(x + dx), (double)(y + dy + size), 0.0D, (double)var8, (double)(var9 + var10));
        var11.addVertexWithUV((double)(x + dx + size), (double)(y + dy + size), 0.0D, (double)(var8 + var10), (double)(var9 + var10));
        var11.addVertexWithUV((double)(x + dx + size), (double)(y + dy), 0.0D, (double)(var8 + var10), (double)var9);
        var11.addVertexWithUV((double)(x + dx), (double)(y + dy), 0.0D, (double)var8, (double)var9);
        var11.draw();
    }

    public boolean previewClicked(int left, int y, int right, int mouseX, int mouseY, int button)
    {
        int var7 = this.getTileAt(left, y, right, mouseX, mouseY);

        if (var7 < 0 || button != 0 || var7 == this.getValue())
        {
            return false;
        }

        this.setValue(var7);
        return true;
    }
}
