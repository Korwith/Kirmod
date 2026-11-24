package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * Tile Entity Render Distance: a slider from 8 to 120 blocks, with vanilla's 64 ("Default") in the
 * middle. Chests, signs, mob spawners, enchantment tables and the like are drawn up to that far away.
 * KirRenderGlobal does the rendering; at the default it leaves vanilla's alone.
 */
public class KirModuleTileEntityDistance extends KirModule
{
    private static final int STEP = 8;
    private static final int DEFAULT_DISTANCE = 64;

    /** "8" to "120" in steps of 8, with "Default" (64) in the middle: value i is (i + 1) * 8 blocks. */
    private static final String[] VALUES = new String[15];

    static
    {
        for (int var0 = 0; var0 < VALUES.length; ++var0)
        {
            int var1 = (var0 + 1) * STEP;
            VALUES[var0] = var1 == DEFAULT_DISTANCE ? "Default" : String.valueOf(var1);
        }
    }

    public KirModuleTileEntityDistance()
    {
        super("tileEntityDistance", "Tile Entity Render Distance", "TE Distance", DEFAULT_DISTANCE / STEP - 1, "tedistance", "tileentitydistance");
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isSlider()
    {
        return true;
    }

    public String getCategory()
    {
        return CATEGORY_PERFORMANCE;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "How far away chests, signs, mob spawners, enchantment tables and other tile entities are drawn. Lower is faster in busy bases.",
            describeValue("8 to 56 blocks", "drawn closer than vanilla."),
            describeValue("Default", "vanilla, 64 blocks."),
            describeValue("72 to 120 blocks", "drawn further than vanilla, as far as the chunks are loaded.")
        };
    }

    public boolean isAvailable()
    {
        return KirRenderGlobal.isAvailable();
    }

    public String getButtonString()
    {
        return this.getValue() == this.getDefaultValue() ? super.getButtonString() : super.getButtonString() + " blocks";
    }

    /** Accepts a distance in blocks, rounded to the nearest step, as well as the value names. */
    public int findValue(String name)
    {
        try
        {
            int var2 = Integer.parseInt(name.trim());
            return Math.max(0, Math.min(VALUES.length - 1, Math.round((float)var2 / (float)STEP) - 1));
        }
        catch (NumberFormatException var3)
        {
            return super.findValue(name);
        }
    }

    /** The render distance in blocks, or 0 for vanilla's. */
    public int getDistance()
    {
        return this.enabled && this.getValue() != this.getDefaultValue() ? (this.getValue() + 1) * STEP : 0;
    }

    public void update(Minecraft mc)
    {
        if (this.getDistance() > 0)
        {
            KirRenderGlobal.install(mc);
        }
    }
}
