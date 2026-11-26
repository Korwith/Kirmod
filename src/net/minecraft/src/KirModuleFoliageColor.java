package net.minecraft.src;

/**
 * Global Foliage Color: makes foliage in the world (oak and jungle leaves, vines) the color of one
 * biome. Spruce and birch leaves, and leaves and vines as items, have fixed colors in vanilla and stay
 * as they are. See KirModuleBiomeColor.
 */
public class KirModuleFoliageColor extends KirModuleBiomeColor
{
    public KirModuleFoliageColor()
    {
        super(KirUtil.findField(ColorizerFoliage.class, "foliageBuffer", "a"), "foliageColors", "foliageColor", "Global Foliage Color", "Foliage", "foliagecolor", "foliage", "leaves");
    }

    public String getCategory()
    {
        return CATEGORY_OVERWORLD;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Makes leaves and vines the same color everywhere: the color they have in one biome. Birch and spruce leaves have fixed colors and don't change.",
            describeValue("Default", "each biome has its own foliage color, as in your texture pack."),
            describeValue("A biome name", "all leaves have that biome's color. Click through the biomes to pick one."),
            note("Click the arrow at the left of the row for a tile of each biome's color; click a tile to pick it.")
        };
    }

    protected int getBiomeColor(BiomeGenBase biome)
    {
        return biome.getBiomeFoliageColor();
    }

    protected void setColorBuffer(int[] buffer)
    {
        ColorizerFoliage.getFoilageBiomeColorizer(buffer);
    }

    /** The fancy oak leaves. */
    protected int getPreviewTexture()
    {
        return 52;
    }
}
