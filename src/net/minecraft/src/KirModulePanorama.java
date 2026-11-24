package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * Panorama Screenshots (from PanoramaAssist): "On" makes F2 take the six pictures of a title screen
 * panorama from where you stand. /panorama takes one whatever this is set to. See KirCamera.
 *
 * PanoramaAssist also replaced GuiMainMenu to show panoramas unblurred; that part is left out.
 */
public class KirModulePanorama extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    public KirModulePanorama()
    {
        super("panoramaScreenshots", "Panorama Screenshots", "Panorama F2", 0, "panoramaf2");
    }

    public String getCategory()
    {
        return KirModuleIsometric.CATEGORY_CAMERA;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Makes F2 take a title screen panorama: six square pictures from your eyes, four around you and one up and down, snapped to the nearest compass direction.",
            describeValue("Off", "F2 takes a normal (or isometric) screenshot."),
            describeValue("On", "F2 takes a panorama. It takes priority over Isometric Screenshots."),
            note("Saved in screenshots/DATE_panorama/ as panorama0.png to panorama5.png (1024x1024); put them in a texture pack's title/bg/ folder to use them on the title screen. /panorama takes one any time.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public void install()
    {
        mod_CommandLib.registerCommand(new ClientCommand("panorama", "", "Takes a title screen panorama from where you stand")
        {
            public void execute(String[] args)
            {
                Minecraft var2 = ModLoader.getMinecraftInstance();

                if (var2.theWorld == null)
                {
                    throw new ClientCommandException("Join a world first.");
                }

                // Chat is still open; the camera starts once it has closed.
                KirModulePanorama.pending = true;
            }
        });
    }

    static boolean pending = false;

    public void update(Minecraft mc)
    {
        if (pending && mc.currentScreen == null && mc.theWorld != null)
        {
            pending = false;

            if (!KirCamera.startPanorama(mc))
            {
                mc.ingameGUI.getChatGUI().printChatMessage("\u00a7cA Kirmod camera is busy; try again in a moment.");
            }
        }

        KirCamera.updatePanorama(mc, this.getValue() == 1);
    }
}
