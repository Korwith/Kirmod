package net.minecraft.src;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Drawing for World Backups (1.38): the progress bar of the Backups tab and the backup browser, the backup
 * icon (kirmod/backup.png: an arrow dropping into a chest) and the corner of the HUD that shows it while a
 * backup runs outside Kirmod's screens.
 */
public class KirBackupHud extends Gui
{
    public static final String ICON = "/kirmod/backup.png";

    /** The icon's size, and the rows of its texture (16x32) that hold the arrow and the chest. */
    public static final int ICON_W = 16;
    public static final int ICON_H = 30;
    private static final int ARROW_TOP = 1;
    private static final int CHEST_TOP = 15;
    private static final int ICON_BOTTOM = 31;

    private static final int COLOR_ACCENT = 0xFF94E4D3;
    private static final int COLOR_ACCENT_DARK = 0xFF4FA597;
    private static final int COLOR_FAILED = 0xFFE05555;
    private static final int COLOR_FAILED_DARK = 0xFF9A2E2E;

    /** How long the HUD keeps showing a finished backup, and fades it out. */
    private static final long HUD_LINGER_MS = 3500L;
    private static final long HUD_FADE_MS = 700L;

    private static final KirBackupHud INSTANCE = new KirBackupHud();

    /** The bar's shown progress, eased towards the real one so it glides instead of jumping. */
    private static float shownProgress;
    private static long lastFrame;

    /** The progress to draw: eased towards KirBackups.getProgress(), once per frame. */
    public static float getShownProgress()
    {
        long var0 = System.currentTimeMillis();
        float var2 = KirBackups.getProgress();
        float var3 = Math.min(1.0F, (float)(var0 - lastFrame) / 120.0F);

        if (var0 - lastFrame > 1000L || var2 < shownProgress)
        {
            shownProgress = var2;
        }
        else if (var0 != lastFrame)
        {
            shownProgress += (var2 - shownProgress) * var3;
        }

        lastFrame = var0;
        return shownProgress;
    }

    /**
     * A rounded-looking bar from x to x + width: a dark inset track, a two-tone fill with moving diagonal
     * stripes while running, and a sheen sweeping along it. Indeterminate bars (the first step, whose length
     * isn't known) show a block sliding back and forth instead.
     */
    public static void drawProgressBar(int x, int y, int width, int height, float progress, boolean running, boolean failed, boolean indeterminate)
    {
        int var8 = failed ? COLOR_FAILED : COLOR_ACCENT;
        int var9 = failed ? COLOR_FAILED_DARK : COLOR_ACCENT_DARK;
        long var10 = System.currentTimeMillis();

        // Track: a dark border with the corners left out, and an inner shadow along the top.
        drawRect(x + 1, y, x + width - 1, y + height, 0xFF0B0B0B);
        drawRect(x, y + 1, x + width, y + height - 1, 0xFF0B0B0B);
        drawRect(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF262626);
        drawRect(x + 1, y + 1, x + width - 1, y + 2, 0xFF1A1A1A);
        int var12 = x + 1;
        int var13 = y + 1;
        int var14 = width - 2;
        int var15 = height - 2;
        int var16;
        int var17;

        if (indeterminate)
        {
            var16 = Math.max(8, var14 / 4);
            float var18 = (float)(var10 % 1400L) / 1400.0F;
            float var19 = var18 < 0.5F ? var18 * 2.0F : 2.0F - var18 * 2.0F;
            var19 = var19 * var19 * (3.0F - 2.0F * var19);
            var17 = var12 + (int)(var19 * (float)(var14 - var16));
        }
        else
        {
            var17 = var12;
            var16 = Math.round((float)var14 * Math.max(0.0F, Math.min(1.0F, progress)));
        }

        if (var16 <= 0)
        {
            return;
        }

        int var20 = var17 + var16;
        // Fill: the lighter top half over the darker bottom, like a lit tube.
        drawRect(var17, var13, var20, var13 + var15, var9);
        drawRect(var17, var13, var20, var13 + (var15 + 1) / 2, var8);
        drawRect(var17, var13, var20, var13 + 1, 0x60FFFFFF);

        if (running)
        {
            // Diagonal stripes moving to the right.
            int var21 = (int)(var10 / 60L % 8L);

            for (int var22 = 0; var22 < var15; ++var22)
            {
                for (int var23 = var17 - 8 + var21 - var22; var23 < var20; var23 += 8)
                {
                    int var24 = Math.max(var23, var17);
                    int var25 = Math.min(var23 + 3, var20);

                    if (var25 > var24)
                    {
                        drawRect(var24, var13 + var22, var25, var13 + var22 + 1, 0x24FFFFFF);
                    }
                }
            }

            // A sheen sweeping along the fill every two seconds.
            int var26 = var17 - 16 + (int)((float)(var10 % 2000L) / 2000.0F * (float)(var16 + 32));

            for (int var27 = 0; var27 < 12; ++var27)
            {
                int var28 = var26 + var27;

                if (var28 >= var17 && var28 < var20)
                {
                    int var29 = (int)(60.0F * (1.0F - Math.abs((float)var27 - 5.5F) / 6.0F));
                    drawRect(var28, var13, var28 + 1, var13 + var15, var29 << 24 | 0xFFFFFF);
                }
            }
        }

        // The leading edge.
        if (!indeterminate && var16 < var14)
        {
            drawRect(var20 - 1, var13, var20, var13 + var15, 0xA0FFFFFF);
        }

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * The backup icon at x, y (ICON_W x ICON_H at scale 1). While animate, the arrow drops into the chest over
     * and over and the chest's lid bounces as it lands.
     */
    public static void drawIcon(Minecraft mc, int x, int y, float scale, boolean animate, float alpha)
    {
        GL11.glPushMatrix();
        GL11.glTranslatef((float)x, (float)y, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mc.renderEngine.getTexture(ICON));
        GL11.glColor4f(1.0F, 1.0F, 1.0F, alpha);
        float var6 = 0.0F;
        float var7 = 0.0F;

        if (animate)
        {
            // 0..1 over 900 ms: the arrow falls from 5 px up, then the chest dips a pixel as it lands.
            float var8 = (float)(System.currentTimeMillis() % 900L) / 900.0F;

            if (var8 < 0.7F)
            {
                float var9 = var8 / 0.7F;
                var6 = -5.0F * (1.0F - var9 * var9);
            }
            else
            {
                var7 = var8 < 0.85F ? 1.0F : 0.0F;
            }
        }

        // Arrow first, so the chest covers its tip as it drops in.
        drawIconPart(ARROW_TOP, CHEST_TOP, var6);
        drawIconPart(CHEST_TOP, ICON_BOTTOM, var7);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    /** Rows top to bottom of the icon texture, drawn offset by dy. */
    private static void drawIconPart(int top, int bottom, float dy)
    {
        Tessellator var3 = Tessellator.instance;
        float var4 = (float)(top - ARROW_TOP) + dy;
        float var5 = (float)(bottom - ARROW_TOP) + dy;
        float var6 = (float)top / 32.0F;
        float var7 = (float)bottom / 32.0F;
        var3.startDrawingQuads();
        var3.addVertexWithUV(0.0D, (double)var5, 0.0D, 0.0D, (double)var7);
        var3.addVertexWithUV((double)ICON_W, (double)var5, 0.0D, 1.0D, (double)var7);
        var3.addVertexWithUV((double)ICON_W, (double)var4, 0.0D, 1.0D, (double)var6);
        var3.addVertexWithUV(0.0D, (double)var4, 0.0D, 0.0D, (double)var6);
        var3.draw();
    }

    /**
     * The HUD corner while a backup runs (and a few seconds after): the animated icon, "Backing up..." with the
     * percentage, and a slim bar. Top right, out of the way of the debug screen and the chat. From Kirmod's
     * in-game hook after the frame is drawn, so it shows over the game and over other screens, but not over
     * Kirmod's own screens, which show the backup themselves.
     */
    public static void drawHud(Minecraft mc)
    {
        int var1 = KirBackups.getState();

        if (var1 == KirBackups.IDLE || mc.theWorld == null || mc.gameSettings.hideGUI || mc.currentScreen instanceof GuiKirmod || mc.currentScreen instanceof GuiKirmodBackups)
        {
            return;
        }

        if (!mod_Kirmod.AUTO_BACKUP.showsHud())
        {
            return;
        }

        boolean var2 = KirBackups.isBusy();
        float var3 = 1.0F;

        if (!var2)
        {
            long var4 = System.currentTimeMillis() - KirBackups.getFinishedAt();

            if (var4 > HUD_LINGER_MS)
            {
                return;
            }

            var3 = Math.min(1.0F, (float)(HUD_LINGER_MS - var4) / (float)HUD_FADE_MS);
        }

        if (var3 < 0.05F)
        {
            return;
        }

        boolean var6 = var1 == KirBackups.FAILED;
        ScaledResolution var7 = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        int var8 = var7.getScaledWidth();
        FontRenderer var9 = mc.fontRenderer;
        String var10 = var6 ? "Backup failed" : (var2 ? (var1 == KirBackups.RESTORING ? "Restoring..." : "Backing up...") : "World backed up");
        String var11 = var6 ? "See the Backups tab" : (var2 ? (var1 == KirBackups.SAVING ? "Saving" : (int)(getShownProgress() * 100.0F) + "%") : KirBackups.getResult());
        int var12 = Math.max(80, Math.max(var9.getStringWidth(var10), var9.getStringWidth(var11)) + 4);
        var12 = Math.min(var12, 160);
        int var13 = 6 + ICON_W + 6 + var12 + 6;
        int var14 = var8 - var13 - 4;
        int var15 = 4;
        int var16 = 38;
        int var17 = (int)(var3 * 255.0F);

        mc.entityRenderer.setupOverlayRendering();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // A dark panel with an accent strip along its left edge.
        drawRect(var14, var15, var14 + var13, var15 + var16, (int)(var3 * 160.0F) << 24);
        drawRect(var14, var15, var14 + 2, var15 + var16, (var6 ? COLOR_FAILED : COLOR_ACCENT) & 0xFFFFFF | var17 << 24);

        drawIcon(mc, var14 + 6, var15 + 4, 1.0F, var2, var3);
        int var18 = var14 + 6 + ICON_W + 6;
        int var19 = var17 << 24;
        var9.drawStringWithShadow(var9.trimStringToWidth(var10, var12), var18, var15 + 7, 0xFFFFFF | var19);
        var9.drawStringWithShadow(var9.trimStringToWidth(var11, var12), var18, var15 + 17, (var6 ? 0xFF8080 : 0xA8B4B1) | var19);

        if (var2 || var1 == KirBackups.DONE)
        {
            drawProgressBar(var18, var15 + 28, var12, 5, var2 ? getShownProgress() : 1.0F, var2, false, var1 == KirBackups.SAVING);
        }

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDisable(GL11.GL_BLEND);
    }
}
