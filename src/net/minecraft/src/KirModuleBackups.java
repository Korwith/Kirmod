package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * Auto Backup (1.38), the first row of the Backups tab: how often the singleplayer world is backed up
 * (KirBackups). Its panel under the row, always open, shows the backup under way with a progress bar (or
 * the countdown to the next one), with "Backup Now" and "Browse Backups..." (GuiKirmodBackups).
 */
public class KirModuleBackups extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "5 min", "10 min", "15 min", "30 min", "1 hour", "2 hours"};
    private static final long[] MINUTES = new long[] {0L, 5L, 10L, 15L, 30L, 60L, 120L};

    private static final int PANEL_H = 74;
    private static final int BUTTON_H = 16;

    private static final int COLOR_PANEL = 0x90000000;
    private static final int COLOR_PANEL_HOVER = 0xE0000000;
    private static final int COLOR_ACCENT = 0xFF94E4D3;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xA0A0A0;
    private static final int COLOR_GOOD = 0x7EE08A;
    private static final int COLOR_BAD = 0xFF7070;

    /** How long the panel keeps saying how the last backup went. */
    private static final long RESULT_MS = 15000L;

    /** When an automatic backup was last tried, so a failing one waits a whole interval before the next try. */
    private long lastAutoAttempt;

    public KirModuleBackups()
    {
        super("autoBackup", "Auto Backup", "Auto Backup", 0, "autobackup", "backups");
    }

    public String getCategory()
    {
        return CATEGORY_BACKUPS;
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Backs up the singleplayer world you are playing every so often, as a zip in .minecraft/backups/<world>. The world is saved first and saving pauses only while its files are copied, so the game keeps running.",
            describeValue("Off", "only \"Backup Now\" makes backups."),
            describeValue("5 min to 2 hours", "time played in the world between backups. The first one comes that long after you open it."),
            note("An icon in the top right corner shows a backup running while you play. Keep Backups sets how many automatic ones are kept.")
        };
    }

    /** The time between automatic backups in milliseconds, or 0 when Off. */
    public long getInterval()
    {
        return MINUTES[this.getValue()] * 60000L;
    }

    /** Whether the HUD shows backups running (Backup Indicator). */
    public boolean showsHud()
    {
        return !mod_Kirmod.BACKUP_HUD.isEnabled() || mod_Kirmod.BACKUP_HUD.getValue() == 0;
    }

    /** When the next automatic backup is due, or 0 if none is. */
    public long getNextBackup()
    {
        long var1 = this.getInterval();
        long var3 = KirBackups.getNextAutoBackup(var1);
        return var1 <= 0L || var3 <= 0L ? 0L : Math.max(var3, this.lastAutoAttempt + var1);
    }

    public void update(Minecraft mc)
    {
        KirBackups.tick(mc);

        if (KirBackups.getServer(mc) == null)
        {
            return;
        }

        KirWorldHook.installAll(mc);
        long var2 = this.getNextBackup();

        if (var2 > 0L && System.currentTimeMillis() >= var2 && !KirBackups.isBusy())
        {
            this.lastAutoAttempt = System.currentTimeMillis();
            KirBackups.start(mc, true);
        }
    }

    public void onServerWorldTickEnd(World world)
    {
        KirBackups.onServerTick(world);
    }

    /* ------------------------------------------------------------------ The panel under the row */

    public boolean isPreviewAlwaysOpen()
    {
        return true;
    }

    public int getPreviewHeight(int width)
    {
        return PANEL_H;
    }

    public void drawPreview(Minecraft mc, int left, int y, int right, int mouseX, int mouseY)
    {
        FontRenderer var7 = mc.fontRenderer;
        int var8 = KirBackups.getState();
        boolean var9 = KirBackups.isBusy();
        long var10 = System.currentTimeMillis();
        boolean var12 = !var9 && (var8 == KirBackups.DONE || var8 == KirBackups.FAILED) && var10 - KirBackups.getFinishedAt() < RESULT_MS;
        IntegratedServer var13 = KirBackups.getServer(mc);
        String var14 = KirBackups.whyNot(mc);

        // A soft accent glow behind the icon while a backup runs.
        if (var9)
        {
            int var15 = (int)(40.0F + 30.0F * (float)Math.sin((double)var10 / 300.0D));
            Gui.drawRect(left + 4, y + 4, left + 24, y + 38, var15 << 24 | COLOR_ACCENT & 0xFFFFFF);
        }

        KirBackupHud.drawIcon(mc, left + 6, y + 6, 1.0F, var9, 1.0F);
        int var16 = left + 30;
        int var17 = right - 6;
        int var18 = var17 - var16;

        // Title: what is being backed up, or the world that would be.
        String var19;

        if (var9)
        {
            var19 = (var8 == KirBackups.RESTORING ? "Restoring " : "Backing up ") + "§l" + KirBackups.getWorldName();
        }
        else if (var13 != null)
        {
            var19 = "§l" + var13.getWorldName();
        }
        else
        {
            var19 = "No world open";
        }

        var7.drawString(var7.trimStringToWidth(var19, var18), var16, y + 7, COLOR_TEXT);

        // Under it: the step, how the last backup went, or when this world was last backed up.
        String var20;
        int var21 = COLOR_TEXT_DIM;
        int var22 = (right - left - 12 - 4) / 2;
        int var23 = y + PANEL_H - 6 - BUTTON_H;
        boolean var24 = isOver(mouseX, mouseY, left + 6, var23, left + 6 + var22, var23 + BUTTON_H);
        boolean var25 = isOver(mouseX, mouseY, right - 6 - var22, var23, right - 6, var23 + BUTTON_H);

        if (var24 && var14 != null && !var9)
        {
            var20 = var14;
            var21 = COLOR_BAD;
        }
        else if (var9)
        {
            var20 = KirBackups.getDetail();
        }
        else if (var12)
        {
            var20 = KirBackups.getResult();
            var21 = var8 == KirBackups.FAILED ? COLOR_BAD : COLOR_GOOD;
        }
        else if (var13 != null)
        {
            long var26 = KirBackups.getLastBackupOfCurrentWorld();
            var20 = var26 > 0L ? "Last backup " + KirBackups.formatAgo(var26) : "Not backed up yet";
        }
        else
        {
            var20 = var14 != null ? var14 : "";
        }

        var7.drawString(var7.trimStringToWidth(var20, var18), var16, y + 18, var21);

        // The bar: the backup's progress, else the countdown to the next automatic one.
        int var28 = y + 30;
        String var29;
        String var30 = "";

        if (var9)
        {
            float var31 = KirBackupHud.getShownProgress();
            KirBackupHud.drawProgressBar(var16, var28, var18, 9, var31, true, false, var8 == KirBackups.SAVING);
            var30 = var8 == KirBackups.SAVING ? "" : (int)(var31 * 100.0F) + "%";
            long var32 = KirBackups.getBytesTotal();
            long var34 = KirBackups.getSpeed();

            if (var8 == KirBackups.SAVING || var32 <= 0L)
            {
                var29 = "Saving...";
            }
            else
            {
                var29 = KirBackups.formatSize(Math.min(KirBackups.getBytesDone(), var32)) + " / " + KirBackups.formatSize(var32);

                if (var34 > 0L)
                {
                    var29 = var29 + "  ·  " + KirBackups.formatSize(var34) + "/s";
                }
            }
        }
        else if (var12)
        {
            KirBackupHud.drawProgressBar(var16, var28, var18, 9, 1.0F, false, var8 == KirBackups.FAILED, false);
            var29 = var8 == KirBackups.FAILED ? "Nothing was written" : "Done in " + KirBackups.formatDuration(KirBackups.getFinishedAt() - KirBackups.getStartedAt());
        }
        else
        {
            long var36 = this.getNextBackup();
            long var38 = this.getInterval();
            float var40 = var36 > 0L && var38 > 0L ? 1.0F - (float)(var36 - var10) / (float)var38 : 0.0F;
            KirBackupHud.drawProgressBar(var16, var28, var18, 9, var40, false, false, false);

            if (var13 == null)
            {
                var29 = "Backups run in singleplayer worlds";
            }
            else if (var38 <= 0L)
            {
                var29 = "Auto Backup is off";
            }
            else
            {
                var29 = "Next backup in " + KirBackups.formatCountdown(var36 - var10);
            }
        }

        int var41 = var7.getStringWidth(var30);
        var7.drawString(var7.trimStringToWidth(var29, var18 - var41 - 6), var16, var28 + 12, COLOR_TEXT_DIM);
        var7.drawString(var30, var17 - var41, var28 + 12, COLOR_ACCENT & 0xFFFFFF);

        // The buttons.
        drawButton(var7, left + 6, var23, left + 6 + var22, "Backup Now", var14 == null, var24);
        drawButton(var7, right - 6 - var22, var23, right - 6, "Browse Backups...", true, var25);
    }

    public boolean previewClicked(int left, int y, int right, int mouseX, int mouseY, int button)
    {
        if (button != 0)
        {
            return false;
        }

        Minecraft var7 = ModLoader.getMinecraftInstance();
        int var8 = (right - left - 12 - 4) / 2;
        int var9 = y + PANEL_H - 6 - BUTTON_H;

        if (isOver(mouseX, mouseY, left + 6, var9, left + 6 + var8, var9 + BUTTON_H))
        {
            return KirBackups.start(var7, false);
        }

        if (isOver(mouseX, mouseY, right - 6 - var8, var9, right - 6, var9 + BUTTON_H))
        {
            var7.displayGuiScreen(new GuiKirmodBackups(var7.currentScreen));
            return true;
        }

        return false;
    }

    private static boolean isOver(int mouseX, int mouseY, int left, int top, int right, int bottom)
    {
        return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
    }

    /** A flat button like the settings screen's, with an accent underline when hovered. */
    static void drawButton(FontRenderer font, int left, int top, int right, String text, boolean enabled, boolean hovered)
    {
        Gui.drawRect(left, top, right, top + BUTTON_H, hovered && enabled ? COLOR_PANEL_HOVER : COLOR_PANEL);
        Gui.drawRect(left, top, right, top + 1, 0x18FFFFFF);

        if (hovered && enabled)
        {
            Gui.drawRect(left, top + BUTTON_H - 1, right, top + BUTTON_H, COLOR_ACCENT);
        }

        String var7 = font.trimStringToWidth(text, right - left - 4);
        font.drawString(var7, left + (right - left - font.getStringWidth(var7)) / 2, top + (BUTTON_H - 8) / 2, enabled ? COLOR_TEXT : COLOR_TEXT_DIM);
    }
}
