package net.minecraft.src;

/**
 * Background Darkness (Interface): how much the Kirmod settings screen darkens the world behind its
 * panels, from Off to 90%. GuiKirmod draws a black layer of this opacity over the world (after Background
 * Blur) before its panels, so the menu stays readable over bright or busy scenery.
 */
public class KirModuleMenuDarkness extends KirModule
{
    private static final String[] VALUES = new String[10];

    static
    {
        VALUES[0] = "Off";

        for (int var0 = 1; var0 < VALUES.length; ++var0)
        {
            VALUES[var0] = var0 * 10 + "%";
        }
    }

    public KirModuleMenuDarkness()
    {
        super("menuDarkness", "Background Darkness", "Darkness", 0, "menudarkness");
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
        return CATEGORY_INTERFACE;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Darkens the world behind the Kirmod settings screen so its text is easier to read. Changes show straight away.",
            describeValue("Off", "the world shows through the panels unchanged."),
            describeValue("10% to 90%", "a black layer of that strength over the world."),
            note("Only in a world; the main menu keeps its dirt background.")
        };
    }

    /** Alpha of the black layer, 0 to 0.9. */
    public float getAlpha()
    {
        return (float)this.getValue() / 10.0F;
    }
}
