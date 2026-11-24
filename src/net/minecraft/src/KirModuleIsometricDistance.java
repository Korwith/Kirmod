package net.minecraft.src;

/**
 * Isometric Distance: a slider from 8 to 256 blocks (48 by default), how far the isometric screenshot
 * camera is from you. Further away shows a bigger area. See KirCamera.
 */
public class KirModuleIsometricDistance extends KirModule
{
    public static final int STEP = 8;
    public static final int MAX_DISTANCE = 256;
    public static final int DEFAULT_DISTANCE = 48;

    /** "8" to "256" in steps of 8: value i is (i + 1) * 8 blocks. */
    private static final String[] VALUES = new String[MAX_DISTANCE / STEP];

    static
    {
        for (int var0 = 0; var0 < VALUES.length; ++var0)
        {
            VALUES[var0] = String.valueOf((var0 + 1) * STEP);
        }
    }

    public KirModuleIsometricDistance()
    {
        super("isometricDistance", "Isometric Distance", "Iso Distance", DEFAULT_DISTANCE / STEP - 1, "isodistance", "isodist");
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
        return KirModuleIsometric.CATEGORY_CAMERA;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "How far the isometric screenshot camera is from you. Further away shows a bigger area.",
            describeValue(STEP + " to " + MAX_DISTANCE, "blocks, in steps of " + STEP + ". The default is " + DEFAULT_DISTANCE + "."),
            note("Only loaded chunks show, so far shots need a high render distance.")
        };
    }

    public String getButtonString()
    {
        return super.getButtonString() + " blocks";
    }

    /** Accepts any number of blocks, rounded to the nearest step, as well as the value names. */
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

    /** The camera's distance in blocks. */
    public int getDistance()
    {
        return (this.getValue() + 1) * STEP;
    }
}
