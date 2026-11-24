package net.minecraft.src;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Entity Render Distance: a slider from 10% to 190% of vanilla's entity render distance, with 100%
 * ("Default") in the middle.
 *
 * Vanilla draws an entity while it is closer than 64 times its size times its renderDistanceWeight
 * (1, or 10 for other players). Every frame each client entity's weight is set to its own weight times
 * the setting. A few projectiles (arrows, snowballs, fireballs, ender eyes, fishing hooks) use a fixed
 * distance of their own, which this doesn't change.
 */
public class KirModuleEntityDistance extends KirModule
{
    /** "10%" to "190%" in steps of 10, with "Default" (100%) in the middle: value i is (i + 1) * 10%. */
    private static final String[] VALUES = new String[19];

    static
    {
        for (int var0 = 0; var0 < VALUES.length; ++var0)
        {
            VALUES[var0] = var0 == 9 ? "Default" : (var0 + 1) * 10 + "%";
        }
    }

    /** Each entity's own renderDistanceWeight, for the entities whose weight was changed. */
    private Map baseWeights = new IdentityHashMap();

    public KirModuleEntityDistance()
    {
        super("entityDistance", "Entity Render Distance", "Entity Distance", 9, "entitydistance");
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
            "How far away mobs, animals, players, items and other entities are drawn. Lower is faster with many entities around.",
            describeValue("10% to 90%", "drawn closer than vanilla."),
            describeValue("Default", "vanilla distance (about 64 blocks for a pig)."),
            describeValue("110% to 190%", "drawn further than vanilla."),
            note("The server only sends entities within a set range (about 80 blocks for mobs), so a higher setting can't show anything past that.")
        };
    }

    /** Accepts a percentage, rounded to the nearest step, as well as the value names. */
    public int findValue(String name)
    {
        try
        {
            int var2 = Integer.parseInt(name.trim().replace("%", ""));
            return Math.max(0, Math.min(VALUES.length - 1, Math.round((float)var2 / 10.0F) - 1));
        }
        catch (NumberFormatException var3)
        {
            return super.findValue(name);
        }
    }

    public void update(Minecraft mc)
    {
        boolean var2 = this.getValue() == this.getDefaultValue();

        if (var2 && this.baseWeights.isEmpty())
        {
            return;
        }

        double var3 = (double)(this.getValue() + 1) * 0.1D;
        IdentityHashMap var5 = new IdentityHashMap();
        List var6 = mc.theWorld == null ? null : mc.theWorld.loadedEntityList;

        for (int var7 = 0; var6 != null && var7 < var6.size(); ++var7)
        {
            Entity var8 = (Entity)var6.get(var7);
            Double var9 = (Double)this.baseWeights.get(var8);

            if (var9 == null)
            {
                var9 = Double.valueOf(var8.renderDistanceWeight);
            }

            var8.renderDistanceWeight = var2 ? var9.doubleValue() : var9.doubleValue() * var3;

            if (!var2)
            {
                var5.put(var8, var9);
            }
        }

        // Entities that left the world are dropped; back at the default, every weight is restored.
        this.baseWeights = var5;
    }
}
