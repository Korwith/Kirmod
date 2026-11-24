package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.client.Minecraft;

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
 */
public abstract class KirModuleBiomeColor extends KirModule
{
    private final Field fieldColorBuffer;
    private BiomeGenBase[] biomes;
    private String[] valueNames;
    private int[] originalBuffer;

    /** The colors originalBuffer had when the color was last applied. */
    private int[] originalCopy;
    private int[] kirmodBuffer;
    private int appliedValue = -1;

    /**
     * @param colorBuffer the colormap's static int[] field, e.g. ColorizerGrass.grassBuffer
     */
    protected KirModuleBiomeColor(Field colorBuffer, String key, String title, String buttonTitle, String commandName, String ... aliases)
    {
        super(key, title, buttonTitle, 0, commandName, aliases);
        this.fieldColorBuffer = colorBuffer;
    }

    /** The biome's color from this module's colormap, with the colormap as the texture pack has it. */
    protected abstract int getBiomeColor(BiomeGenBase biome);

    /** Points the game at a colormap. */
    protected abstract void setColorBuffer(int[] buffer);

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
                return;
            }

            this.originalCopy = (int[])this.originalBuffer.clone();
            this.setColorBuffer(this.originalBuffer);

            if (var3 > 0)
            {
                this.getValueNames();
                this.kirmodBuffer = new int[this.originalBuffer.length];
                Arrays.fill(this.kirmodBuffer, this.getBiomeColor(this.biomes[var3 - 1]));
                this.setColorBuffer(this.kirmodBuffer);
            }

            // Chunks only need building again if a biome's color is or was applied: the first check after
            // startup only records the colormap, and a pack change already rebuilt them.
            if (var3 > 0 || this.appliedValue > 0)
            {
                KirUtil.reloadRenderers();
            }

            this.appliedValue = var3;
        }
        catch (IllegalAccessException var5)
        {
            KirUtil.log("Could not change the " + this.getTitle().toLowerCase(), var5);
        }
    }
}
