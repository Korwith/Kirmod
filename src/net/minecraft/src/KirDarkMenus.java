package net.minecraft.src;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Dark Theme for vanilla's settings screens (1.37): Options, Video Settings, Controls, Language, Snooper
 * Settings, Texture Packs and OptiFine's settings screens get the look of Kirpack Dark.
 *
 * Those screens are vanilla classes, so nothing is drawn differently: while one is open (and Dark Theme is
 * on), the textures they draw with are changed instead. In gui.png the normal button rows (v 66 to 85)
 * are darkened by a share of the Dark Theme Tint (5/8 of it, so the default 80% gives Kirpack Dark's half
 * brightness), leaving hovered and disabled buttons as they are; background.png (the menu background
 * outside a world) becomes Kirpack Dark's grey stone (kirmod/dark/background.png). Both are put back as
 * the pack has them as soon as no such screen is open, so the main menu, the HUD and every other screen
 * stay as they were. The rows are written again every frame while needed, which also covers the pack's
 * textures being uploaded again after a texture pack change.
 */
public class KirDarkMenus
{
    private static final String GUI = "/gui/gui.png";
    private static final String BACKGROUND = "/gui/background.png";
    private static final String DARK_BACKGROUND = KirModuleChests.TEXTURE_DIR + "dark/background.png";

    /** The normal button rows of gui.png (at 256 wide). */
    private static final int BUTTON_Y = 66;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_W = 200;

    /** The texture pack the images were read from; its gui.png and background.png. */
    private static Object pack;
    private static BufferedImage packGui;
    private static BufferedImage packBackground;
    private static BufferedImage darkBackground;

    /** Whether the textures are darkened now, and the tint they were darkened with. */
    private static boolean applied;
    private static boolean failed;

    /** Called every frame (from KirModuleDarkTheme.update). */
    static void update(Minecraft mc)
    {
        if (failed || mc.renderEngine == null)
        {
            return;
        }

        boolean var1 = KirTheme.isDark() && isSettingsScreen(mc.currentScreen);

        if (!var1 && !applied)
        {
            return;
        }

        try
        {
            Object var2 = mc.texturePackList.getSelectedTexturePack();

            if (var2 != pack)
            {
                // A new pack has uploaded its own textures, undarkened.
                pack = var2;
                packGui = null;
                packBackground = null;
                applied = false;

                if (!var1)
                {
                    return;
                }
            }

            int var3 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            try
            {
                if (var1)
                {
                    // Every frame: cheap, and the pack's textures may have been uploaded again meanwhile.
                    write(mc, true);
                    applied = true;
                }
                else
                {
                    write(mc, false);
                    applied = false;
                }
            }
            finally
            {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, var3);
            }
        }
        catch (Throwable var5)
        {
            failed = true;
            KirUtil.log("Could not apply the Dark Theme to the options screens", var5);
        }
    }

    /** Vanilla's settings screens and OptiFine's (GuiQualitySettingsOF, GuiDetailSettingsOF...). */
    static boolean isSettingsScreen(GuiScreen screen)
    {
        if (screen == null)
        {
            return false;
        }

        if (screen instanceof GuiOptions || screen instanceof GuiVideoSettings || screen instanceof GuiControls || screen instanceof GuiLanguage || screen instanceof GuiSnooper || screen instanceof GuiTexturePacks)
        {
            return true;
        }

        String var1 = screen.getClass().getName();
        var1 = var1.substring(var1.lastIndexOf('.') + 1);
        return var1.startsWith("Gui") && var1.endsWith("OF");
    }

    /** The last buffers written, and what they were made for, so nothing is made again every frame. */
    private static ByteBuffer guiBuffer;
    private static ByteBuffer backgroundBuffer;
    private static String bufferKey;

    private static void write(Minecraft mc, boolean dark) throws Exception
    {
        RenderEngine var2 = mc.renderEngine;

        if (packGui == null)
        {
            packGui = ModLoader.loadImage(var2, GUI);
            packBackground = ModLoader.loadImage(var2, BACKGROUND);
            bufferKey = null;
        }

        if (darkBackground == null)
        {
            darkBackground = ModLoader.loadImage(var2, DARK_BACKGROUND);
        }

        int var3 = var2.getTexture(GUI);
        int var4 = var2.getTexture(BACKGROUND);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, var3);
        int var5 = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, var4);
        int var6 = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int var7 = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        float var8 = var5 / 256.0F;
        int var9 = Math.round(BUTTON_W * var8);
        int var10 = Math.round(BUTTON_H * var8);
        String var11 = dark + "/" + KirTheme.coatAlpha() + "/" + var5 + "/" + var6 + "x" + var7;

        if (!var11.equals(bufferKey))
        {
            // gui.png: the normal button rows, darkened or as the pack has them.
            float var12 = packGui.getWidth() / 256.0F;
            BufferedImage var13 = scaled(packGui.getSubimage(0, Math.round(BUTTON_Y * var12), Math.round(BUTTON_W * var12), Math.round(BUTTON_H * var12)), var9, var10);
            guiBuffer = toBuffer(var13, dark ? 1.0F - 0.625F * KirTheme.coatAlpha() / 255.0F : 1.0F);

            // background.png: Kirpack Dark's stone, or the pack's own.
            backgroundBuffer = toBuffer(scaled(dark ? darkBackground : packBackground, var6, var7), 1.0F);
            bufferKey = var11;
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, var3);
        guiBuffer.rewind();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, Math.round(BUTTON_Y * var8), var9, var10, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, guiBuffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, var4);
        backgroundBuffer.rewind();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, var6, var7, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, backgroundBuffer);
    }

    private static BufferedImage scaled(BufferedImage image, int width, int height)
    {
        if (image.getWidth() == width && image.getHeight() == height)
        {
            return image;
        }

        BufferedImage var3 = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D var4 = var3.createGraphics();
        var4.drawImage(image, 0, 0, width, height, (java.awt.image.ImageObserver)null);
        var4.dispose();
        return var3;
    }

    /** RGBA bytes, each color channel times keep. */
    private static ByteBuffer toBuffer(BufferedImage image, float keep)
    {
        int var2 = image.getWidth();
        int var3 = image.getHeight();
        int[] var4 = image.getRGB(0, 0, var2, var3, (int[])null, 0, var2);
        ByteBuffer var5 = ByteBuffer.allocateDirect(var4.length * 4);

        for (int var6 = 0; var6 < var4.length; ++var6)
        {
            int var7 = var4[var6];
            var5.put((byte)Math.round((var7 >> 16 & 255) * keep)).put((byte)Math.round((var7 >> 8 & 255) * keep)).put((byte)Math.round((var7 & 255) * keep)).put((byte)(var7 >>> 24));
        }

        var5.flip();
        return var5;
    }
}
