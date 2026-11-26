package net.minecraft.src;

/**
 * Dark Theme Tint (Interface, 1.30): how strong the Dark Theme's black coat is, from 10% to 90%
 * (default 80% since 1.37, alpha 204 of 255). KirTheme reads it every time it draws, so a change shows straight away.
 */
public class KirModuleDarkThemeTint extends KirModule
{
    private static final String[] VALUES = new String[9];
    /** Index of the default, 80% (60% before 1.37). */
    public static final int DEFAULT = 7;

    static
    {
        for (int var0 = 0; var0 < VALUES.length; ++var0)
        {
            VALUES[var0] = (var0 + 1) * 10 + "%";
        }
    }

    public KirModuleDarkThemeTint()
    {
        super("darkThemeTint", "Dark Theme Tint", "Tint", DEFAULT, "darkthemetint");
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
            "How dark the Dark Theme makes the plugins' screens: the strength of its black coat. Only matters while Dark Theme is On. Changes show straight away.",
            describeValue("10% to 90%", "a black coat of that strength over panels, slots, buttons and tabs; items are never darkened."),
            note("Default 80%.")
        };
    }

    /** The coat's alpha, 0 to 255. */
    public int getAlpha()
    {
        return Math.round((this.getValue() + 1) * 10 * 255 / 100.0F);
    }
}
