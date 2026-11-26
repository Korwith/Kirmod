package net.minecraft.src;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Dark Theme for vanilla's own screens (1.37): the main menu (buttons and logo), the pause menu, Options,
 * Video Settings, Controls, the world and server lists and every other vanilla screen get the look of
 * Kirpack Dark.
 *
 * Those screens are vanilla classes, so nothing is drawn differently: while Dark Theme is on, the textures
 * they draw with are changed instead. In gui.png the normal button rows (v 66 to 85) and the language
 * button (v 106 to 125) are darkened by a share of the Dark Theme Tint (5/8 of it, so the default 80% gives
 * Kirpack Dark's half brightness), leaving hovered and disabled buttons as they are; the logo
 * (title/mclogo.png) is darkened the same way; background.png (the dirt behind menus) becomes Kirpack
 * Dark's grey stone (kirmod/dark/background.png). The HUD never draws from those parts of gui.png.
 *
 * The textures stay changed for as long as the theme is on, whichever screen is open. (Before, they were
 * changed only while a settings screen was open; ModLoader runs its hooks after the frame is drawn, so a
 * screen's first frame was always drawn with the previous screen's textures.) They are written again when
 * the theme, the tint or the pack changes, right after Kirmod reloads the textures (reapply), every frame
 * while a screen is open and 20 times a second from KirTexturePacks' tick, which also runs at the main
 * menu: that covers textures reloaded behind Kirmod's back (F3+T, OptiFine's video settings).
 */
public class KirDarkMenus
{
    private static final String GUI = "/gui/gui.png";

    /** gui.png's normal button rows and language button, the menu background and the logo. */
    private static final Patch BUTTONS = new Patch(GUI, 0, 66, 200, 20, null);
    private static final Patch LANGUAGE = new Patch(GUI, 0, 106, 20, 20, null);
    private static final Patch BACKGROUND = new Patch("/gui/background.png", 0, 0, 0, 0, KirModuleChests.TEXTURE_DIR + "dark/background.png");
    private static final Patch LOGO = new Patch("/title/mclogo.png", 0, 0, 0, 0, null);
    private static final Patch[] PATCHES = new Patch[] {BUTTONS, LANGUAGE, BACKGROUND, LOGO};

    /** The texture pack the images were read from. */
    private static Object pack;

    /** What the textures hold now: "dark/<tint alpha>", or null for the pack's own pixels. */
    private static String applied;

    /** gui.png's GL texture while it is darkened, else -1; and how much its button rows keep. */
    private static int darkGuiTexture = -1;
    private static float darkGuiKeep = 1.0F;

    private static boolean failed;

    /** Every frame in a world (KirModuleDarkTheme.update): rewritten while a screen is open. */
    static void update(Minecraft mc)
    {
        apply(mc, mc.currentScreen != null, mc.currentScreen instanceof GuiMainMenu);
    }

    /** 20 times a second, also at the main menu (KirTexturePacks' tick hook). */
    static void tick(Minecraft mc)
    {
        if (mod_Kirmod.DARK_THEME.enabled)
        {
            apply(mc, true, mc.currentScreen instanceof GuiMainMenu);
        }
    }

    /** Right after the textures were reloaded (RenderEngine.refreshTextures), before anything is drawn. */
    static void reapply(Minecraft mc)
    {
        applied = null;

        if (mod_Kirmod.DARK_THEME.enabled)
        {
            apply(mc, true, true);
        }
    }

    /**
     * Puts the textures in the state the theme wants. refresh writes them even if they should be in it
     * already (the pack may have uploaded its own again); the logo is only refreshed with refreshLogo, as
     * it is only seen at the main menu and is the largest.
     */
    private static void apply(Minecraft mc, boolean refresh, boolean refreshLogo)
    {
        if (failed || mc.renderEngine == null || mc.texturePackList == null)
        {
            return;
        }

        try
        {
            Object var4 = mc.texturePackList.getSelectedTexturePack();

            if (var4 != pack)
            {
                // A new pack has uploaded its own textures, undarkened.
                pack = var4;
                applied = null;

                for (int var5 = 0; var5 < PATCHES.length; ++var5)
                {
                    PATCHES[var5].forget();
                }
            }

            boolean var6 = KirTheme.isDark();
            String var7 = var6 ? "dark/" + KirTheme.coatAlpha() : null;
            boolean var8 = var7 == null ? applied != null : !var7.equals(applied);

            if (!var8 && (!refresh || !var6))
            {
                return;
            }

            float var9 = var6 ? 1.0F - 0.625F * KirTheme.coatAlpha() / 255.0F : 1.0F;
            int var10 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            try
            {
                BUTTONS.write(mc.renderEngine, var6, var9);
                LANGUAGE.write(mc.renderEngine, var6, var9);
                BACKGROUND.write(mc.renderEngine, var6, var9);

                if (var8 || refreshLogo)
                {
                    LOGO.write(mc.renderEngine, var6, var9);
                }
            }
            finally
            {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, var10);
            }

            applied = var7;
            darkGuiTexture = var6 ? mc.renderEngine.getTexture(GUI) : -1;
            darkGuiKeep = var9;
        }
        catch (Throwable var12)
        {
            failed = true;
            darkGuiTexture = -1;
            KirUtil.log("Could not apply the Dark Theme to the menus", var12);
        }
    }

    /**
     * How much of its brightness the bound texture's button rows already keep: below 1 while gui.png is
     * bound and darkened. KirTheme.textureColor divides by it, so plugin buttons aren't darkened twice.
     */
    static float boundTextureKeep()
    {
        if (darkGuiTexture < 0 || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != darkGuiTexture)
        {
            return 1.0F;
        }

        return darkGuiKeep;
    }

    /** One part of a texture: darkened (or replaced by darkImage) with the theme on, the pack's own with it off. */
    private static class Patch
    {
        private final String texture;

        /** The part, in 256ths of the texture's width (gui.png's layout); w 0 is the whole texture. */
        private final int u;
        private final int v;
        private final int w;
        private final int h;

        /** Shown instead of the pack's pixels when dark, or null to darken the pack's. */
        private final String darkImage;

        private BufferedImage packImage;
        private BufferedImage darkSource;

        /** The last pixels written, and what they were made for, so nothing is made again every frame. */
        private ByteBuffer buffer;
        private String bufferKey;

        Patch(String texture, int u, int v, int w, int h, String darkImage)
        {
            this.texture = texture;
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
            this.darkImage = darkImage;
        }

        void forget()
        {
            this.packImage = null;
            this.bufferKey = null;
        }

        void write(RenderEngine engine, boolean dark, float keep) throws Exception
        {
            if (this.packImage == null)
            {
                this.packImage = ModLoader.loadImage(engine, this.texture);
                this.bufferKey = null;
            }

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, engine.getTexture(this.texture));
            int var4 = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int var5 = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            float var6 = var4 / 256.0F;
            int var7 = this.w == 0 ? var4 : Math.round(this.w * var6);
            int var8 = this.w == 0 ? var5 : Math.round(this.h * var6);
            String var9 = dark + "/" + keep + "/" + var4 + "x" + var5;

            if (!var9.equals(this.bufferKey))
            {
                BufferedImage var10;
                float var11 = 1.0F;

                if (dark && this.darkImage != null)
                {
                    if (this.darkSource == null)
                    {
                        this.darkSource = ModLoader.loadImage(engine, this.darkImage);
                    }

                    var10 = this.darkSource;
                }
                else
                {
                    float var12 = this.packImage.getWidth() / 256.0F;
                    var10 = this.w == 0 ? this.packImage : this.packImage.getSubimage(Math.round(this.u * var12), Math.round(this.v * var12), Math.round(this.w * var12), Math.round(this.h * var12));

                    if (dark)
                    {
                        var11 = keep;
                    }
                }

                this.buffer = toBuffer(scaled(var10, var7, var8), var11);
                this.bufferKey = var9;
            }

            this.buffer.rewind();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, Math.round(this.u * var6), Math.round(this.v * var6), var7, var8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.buffer);
        }
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
