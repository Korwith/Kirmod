package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * Isometric Screenshots: "On" makes F2 take a screenshot from high up on one side of you, looking down
 * at the isometric angle (about 35 degrees), instead of the normal one. Isometric Direction picks the
 * side and Isometric Distance how far away the camera is. See KirCamera.
 */
public class KirModuleIsometric extends KirModule
{
    /** The submenu of Freecam and the isometric screenshot settings; shown under Kirmod > Camera... */
    public static final String CATEGORY_CAMERA = "Camera";

    private static final String[] VALUES = new String[] {"Off", "On"};

    public KirModuleIsometric()
    {
        super("isometricScreenshots", "Isometric Screenshots", "Isometric F2", 0, "isometric", "iso");
    }

    public String getCategory()
    {
        return CATEGORY_CAMERA;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Makes F2 take an aerial screenshot of the area around you, from up in the sky at an angle.",
            describeValue("Off", "F2 takes a normal screenshot."),
            describeValue("On", "F2 takes an isometric screenshot, from the side set by Isometric Direction and as far away as Isometric Distance. The HUD is left out."),
            note("Saved in screenshots/ as DATE_isometric.png. The view jumps there for a moment while the chunks load.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public void update(Minecraft mc)
    {
        // Panorama Screenshots, when on, has F2 instead.
        KirCamera.updateIsometric(mc, this.getValue() == 1 && !(mod_Kirmod.PANORAMA.isEnabled() && mod_Kirmod.PANORAMA.getValue() == 1));
    }
}
