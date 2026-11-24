package net.minecraft.src;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Background Blur (Interface): blurs the world behind the Kirmod settings screen, Off or 1 to 10.
 *
 * 1.3.2 has no framebuffers or shaders to lean on, so the blur works on the screen itself: the frame drawn
 * so far (the world) is copied into a texture, then drawn back over itself several times, shifted a little
 * each time, with alpha 1/(n+1) for the n-th copy so the result is the average of all of them (a box blur).
 * That is done horizontally, then (after copying again) vertically, and twice over, which comes close to a
 * Gaussian blur. Linear filtering smooths the taps between pixels. The radius scales with the GUI scale.
 */
public class KirModuleMenuBlur extends KirModule
{
    private static final String[] VALUES = new String[11];

    /** Most copies of the frame drawn per pass; fewer for small radii (about one per 4 screen pixels). */
    private static final int MAX_TAPS = 17;

    /** The texture the frame is copied into, and its (power of two) size. */
    private int texture = -1;
    private int textureWidth;
    private int textureHeight;

    static
    {
        VALUES[0] = "Off";

        for (int var0 = 1; var0 < VALUES.length; ++var0)
        {
            VALUES[var0] = String.valueOf(var0);
        }
    }

    public KirModuleMenuBlur()
    {
        super("menuBlur", "Background Blur", "Blur", 0, "menublur");
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
            "Blurs the world behind the Kirmod settings screen, like Sodium and newer Minecraft menus, so busy scenery does not get in the way of the text. Changes show straight away.",
            describeValue("Off", "the world stays sharp."),
            describeValue("1 to 10", "a light to a strong blur. Stronger blurs cost a little more frame time while the screen is open."),
            note("Only in a world; the main menu keeps its dirt background.")
        };
    }

    /** Blurs what has been drawn so far on screen. Call with the 2D (GUI) projection set up. */
    public void blurScreen(Minecraft mc, int guiWidth, int guiHeight)
    {
        if (this.getValue() == 0)
        {
            return;
        }

        int var4 = mc.displayWidth;
        int var5 = mc.displayHeight;

        if (var4 <= 0 || var5 <= 0)
        {
            return;
        }

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);

        try
        {
            this.prepareTexture(var4, var5);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            int var6 = new ScaledResolution(mc.gameSettings, var4, var5).getScaleFactor();
            // Radius in screen pixels: 1.5 GUI pixels per step, so it looks the same at every GUI scale.
            float var7 = (float)this.getValue() * 1.5F * (float)var6;

            for (int var8 = 0; var8 < 2; ++var8)
            {
                this.pass(var4, var5, guiWidth, guiHeight, var7, 0.0F);
                this.pass(var4, var5, guiWidth, guiHeight, 0.0F, var7);
            }
        }
        finally
        {
            GL11.glPopAttrib();
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** Copies the screen and draws it back several times spread over -radius..radius along (dx, dy). */
    private void pass(int displayWidth, int displayHeight, int guiWidth, int guiHeight, float dx, float dy)
    {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, displayWidth, displayHeight);
        float var7 = (float)displayWidth / (float)this.textureWidth;
        float var8 = (float)displayHeight / (float)this.textureHeight;
        Tessellator var9 = Tessellator.instance;
        int var16 = Math.min(MAX_TAPS, 2 * (int)Math.ceil((double)(Math.max(dx, dy) / 4.0F)) + 1);

        for (int var10 = 0; var10 < var16; ++var10)
        {
            // Each copy is drawn shifted on screen (not sampled shifted), so it never reads past the part of
            // the texture the frame was copied into; the strip it leaves uncovered keeps the result so far.
            float var11 = (float)var10 / (float)(var16 - 1) * 2.0F - 1.0F;
            double var12 = (double)(var11 * dx * (float)guiWidth / (float)displayWidth);
            double var14 = (double)(var11 * dy * (float)guiHeight / (float)displayHeight);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F / (float)(var10 + 1));
            var9.startDrawingQuads();
            // The texture's first row is the bottom of the screen.
            var9.addVertexWithUV(var12, (double)guiHeight + var14, 0.0D, 0.0D, 0.0D);
            var9.addVertexWithUV((double)guiWidth + var12, (double)guiHeight + var14, 0.0D, (double)var7, 0.0D);
            var9.addVertexWithUV((double)guiWidth + var12, var14, 0.0D, (double)var7, (double)var8);
            var9.addVertexWithUV(var12, var14, 0.0D, 0.0D, (double)var8);
            var9.draw();
        }
    }

    private void prepareTexture(int width, int height)
    {
        int var3 = 1;
        int var4 = 1;

        while (var3 < width)
        {
            var3 <<= 1;
        }

        while (var4 < height)
        {
            var4 <<= 1;
        }

        if (this.texture < 0)
        {
            this.texture = GL11.glGenTextures();
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);

        if (var3 != this.textureWidth || var4 != this.textureHeight)
        {
            this.textureWidth = var3;
            this.textureHeight = var4;
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, var3, var4, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
        }

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }
}
