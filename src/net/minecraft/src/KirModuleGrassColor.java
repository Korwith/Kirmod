package net.minecraft.src;

/**
 * Global Grass Color: makes all grass (blocks, tall grass, ferns) the color of one biome. See
 * KirModuleBiomeColor.
 */
public class KirModuleGrassColor extends KirModuleBiomeColor
{
    public KirModuleGrassColor()
    {
        super(KirUtil.findField(ColorizerGrass.class, "grassBuffer", "a"), "grassColor", "Global Grass Color", "Grass", "grasscolor", "grass");
    }

    public String getCategory()
    {
        return CATEGORY_OVERWORLD;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Makes grass, tall grass and ferns the same color everywhere: the color they have in one biome.",
            describeValue("Default", "each biome has its own grass color, as in your texture pack."),
            describeValue("A biome name", "all grass has that biome's color. Click through the biomes to pick one.")
        };
    }

    protected int getBiomeColor(BiomeGenBase biome)
    {
        return biome.getBiomeGrassColor();
    }

    protected void setColorBuffer(int[] buffer)
    {
        ColorizerGrass.setGrassBiomeColorizer(buffer);
    }
}
