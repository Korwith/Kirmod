package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * Dark Theme (Interface, 1.30): one switch for every screen Kirmod's plugins draw in vanilla's grey
 * container style (Ghost Shapes, Ghost Terrain, Storage Spaces, the recipe book...). When On, KirTheme
 * lays a black coat (Dark Theme Tint sets its strength, 80% by default) over their panels, slots, buttons and tabs. Items and
 * text are drawn after the coat, so they keep their colors; dark label text is lightened so it still
 * reads on the darker panels. Since 1.37 it also darkens vanilla's own menus: the main menu and its logo,
 * the pause menu, the settings screens and the rest (KirDarkMenus).
 */
public class KirModuleDarkTheme extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    public KirModuleDarkTheme()
    {
        super("darkTheme", "Dark Theme", "Dark Theme", 0, "darktheme");
    }

    public String getCategory()
    {
        return CATEGORY_INTERFACE;
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Darkens the vanilla-style screens of Kirmod's plugins (Ghost Shapes, Ghost Terrain, Storage Spaces, the recipe book) and Minecraft's own menus (the main menu and its logo, the pause menu, Options, Video Settings, Controls...) so they all share one dark look, like Kirpack Dark. Changes show straight away.",
            describeValue("Off", "vanilla's light grey panels."),
            describeValue("On", "a black coat (Dark Theme Tint sets how strong, 80% by default) over panels, slots, buttons and tabs. Items are not darkened, and dark text is lightened to stay readable."),
            note("Replaces the Storage Spaces plugin's own Light/Dark theme.")
        };
    }

    /** Darkens vanilla's menus (1.37); see KirDarkMenus. */
    public void update(Minecraft mc)
    {
        // With the frame hook, mod_Kirmod.beforeFrame does it before each frame instead, also at the main menu.
        if (!KirFrameHook.isActive(mc))
        {
            KirDarkMenus.update(mc);
        }
    }

    public boolean isDark()
    {
        return this.enabled && this.getValue() == 1;
    }
}
