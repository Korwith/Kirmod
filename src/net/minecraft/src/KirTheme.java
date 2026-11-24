package net.minecraft.src;

import org.lwjgl.opengl.GL11;

/**
 * Kirmod's global Dark Theme (1.30), for plugin screens drawn in vanilla's container style.
 *
 * The theme is a black coat over a screen's surfaces, its alpha set by Interface > Dark Theme Tint
 * (10% to 90%, default 60% = 153 of 255): panels, slots,
 * wells, buttons, tabs. A plugin draws its surfaces through this class and draws its items and text
 * afterwards, so those are never darkened. Plugins never keep a dark palette of their own; the switch
 * is Kirmod > Interface > Dark Theme.
 *
 *   Gui.drawRect(x1, y1, x2, y2, KirTheme.surface(0xFFC6C6C6)); // a panel color, coated
 *   KirTheme.textureColor();                                     // before a gui.png button / tab
 *   KirTheme.coat(x1, y1, x2, y2);                               // coat what is already drawn there
 *   font.drawString(KirTheme.text(s), x, y, KirTheme.text(0xFF404040));
 *
 * surface() and textureColor() give exactly what the coat would over an opaque pixel (each channel
 * times keep(), 102/255 at the default), so they suit shapes with cut corners or transparent texture pixels, where a coat
 * rectangle would also darken what is behind them.
 */
public class KirTheme
{
    /** The coat's opacity, 0-255: Interface > Dark Theme Tint (default 60%, alpha 153). */
    public static int coatAlpha()
    {
        return mod_Kirmod.DARK_THEME_TINT.getAlpha();
    }

    /** The coat color: black at coatAlpha(). */
    public static int coatColor()
    {
        return coatAlpha() << 24;
    }

    /** What an opaque channel keeps under the coat (0.4 at the default 60%). */
    public static float keep()
    {
        return (255 - coatAlpha()) / 255.0F;
    }

    /** Whether the dark theme is on. */
    public static boolean isDark()
    {
        return mod_Kirmod.DARK_THEME.isDark();
    }

    /** A surface color as it looks under the coat (alpha unchanged); the color itself when the theme is off. */
    public static int surface(int argb)
    {
        if (!isDark())
        {
            return argb;
        }

        int r = Math.round((argb >> 16 & 255) * keep());
        int g = Math.round((argb >> 8 & 255) * keep());
        int b = Math.round((argb & 255) * keep());
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** glColor for a textured surface (a gui.png button, a creative tab), times an optional tint. */
    public static void textureColor()
    {
        textureColor(1.0F, 1.0F, 1.0F);
    }

    public static void textureColor(float r, float g, float b)
    {
        float k = isDark() ? keep() : 1.0F;
        GL11.glColor4f(r * k, g * k, b * k, 1.0F);
    }

    /** Lays the coat over a rectangle already drawn, leaving the GL color white. */
    public static void coat(int x1, int y1, int x2, int y2)
    {
        if (isDark())
        {
            Gui.drawRect(x1, y1, x2, y2, coatColor());
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * Lays the coat over a vanilla container panel already drawn (inventory.png, crafting.png...), whose
     * corners are cut by two pixels, so the transparent corner pixels stay uncoated.
     */
    public static void coatPanel(int x1, int y1, int x2, int y2)
    {
        coatPanel(x1, y1, x2, y2, 0, 0, 0, 0);
    }

    /**
     * coatPanel, leaving a hole uncovered: something drawn with the panel that is content, like the
     * inventory's player model. The hole must not touch the panel's corners.
     */
    public static void coatPanel(int x1, int y1, int x2, int y2, int hx1, int hy1, int hx2, int hy2)
    {
        if (!isDark())
        {
            return;
        }

        int coat = coatColor();
        Gui.drawRect(x1 + 2, y1, x2 - 2, y1 + 1, coat);
        Gui.drawRect(x1 + 1, y1 + 1, x2 - 1, y1 + 2, coat);

        if (hx2 > hx1 && hy2 > hy1)
        {
            Gui.drawRect(x1, y1 + 2, x2, hy1, coat);
            Gui.drawRect(x1, hy1, hx1, hy2, coat);
            Gui.drawRect(hx2, hy1, x2, hy2, coat);
            Gui.drawRect(x1, hy2, x2, y2 - 2, coat);
        }
        else
        {
            Gui.drawRect(x1, y1 + 2, x2, y2 - 2, coat);
        }

        Gui.drawRect(x1 + 1, y2 - 2, x2 - 1, y2 - 1, coat);
        Gui.drawRect(x1 + 2, y2 - 1, x2 - 2, y2, coat);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * A text color that reads on coated panels: colors darker than mid-grey (vanilla's 0x404040 label,
     * dark greens and reds) are turned light, darkest becoming lightest so emphasis is kept, with their
     * hue. Light colors, and every color with the theme off, are returned as they are.
     */
    public static int text(int argb)
    {
        if (!isDark())
        {
            return argb;
        }

        int r = argb >> 16 & 255;
        int g = argb >> 8 & 255;
        int b = argb & 255;
        float lum = r * 0.299F + g * 0.587F + b * 0.114F;

        if (lum >= 160.0F)
        {
            return argb;
        }

        float target = 255.0F - lum * 0.6F;
        float max = Math.max(r, Math.max(g, b));
        float nr, ng, nb;

        if (max < 1.0F)
        {
            nr = ng = nb = target;
        }
        else
        {
            // Scale up keeping the hue; whatever the brightest channel can't take is made up by mixing in white.
            float k = Math.min(target / Math.max(lum, 1.0F), 255.0F / max);
            nr = r * k;
            ng = g * k;
            nb = b * k;
            float got = nr * 0.299F + ng * 0.587F + nb * 0.114F;

            if (got < target && got < 255.0F)
            {
                float t = (target - got) / (255.0F - got);
                nr += (255.0F - nr) * t;
                ng += (255.0F - ng) * t;
                nb += (255.0F - nb) * t;
            }
        }

        return argb & 0xFF000000 | clamp(nr) << 16 | clamp(ng) << 8 | clamp(nb);
    }

    /** Formatting codes for dark text (black, dark blue/green/aqua/red/purple, dark gray) turned light. */
    public static String text(String s)
    {
        if (s == null || !isDark() || s.indexOf('§') < 0)
        {
            return s;
        }

        StringBuilder sb = new StringBuilder(s.length());

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            sb.append(c);

            if (c == '§' && i + 1 < s.length())
            {
                char code = Character.toLowerCase(s.charAt(++i));
                int at = "0123458".indexOf(code);
                sb.append(at < 0 ? s.charAt(i) : "f9abcd7".charAt(at));
            }
        }

        return sb.toString();
    }

    private static int clamp(float v)
    {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
