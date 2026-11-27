package net.minecraft.src;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Kirmod > Backups > Browse Backups... (1.38): every world that has backups on the left, the selected world's
 * backups on the right (newest first, with their date, age, size and whether Auto Backup or "Backup Now" made
 * them), and the progress of a backup or restore under way along the bottom.
 *
 * "Restore as Copy" unzips the selected backup into a new world next to the others (KirBackups.restore), so
 * neither the world nor its backups are ever overwritten. "Delete" asks first. Laid out like GuiKirmod: flat,
 * semi-transparent panels over the world, or over the dirt background at the main menu.
 */
public class GuiKirmodBackups extends GuiScreen
{
    private static final int DONE_ID = 0;
    private static final int RESTORE_ID = 1;
    private static final int DELETE_ID = 2;
    private static final int FOLDER_ID = 3;
    private static final int BACKUP_ID = 4;
    private static final int CLOSE_ID = 5;

    private static final int MARGIN = 6;
    private static final int HEADER_H = 18;
    private static final int BUTTON_H = 18;
    private static final int WORLD_ROW_H = 24;
    private static final int BACKUP_ROW_H = 26;
    private static final int STATUS_H = 30;
    private static final int MAX_W = 520;

    private static final int COLOR_PANEL = 0x90000000;
    private static final int COLOR_PANEL_HOVER = 0xE0000000;
    private static final int COLOR_SELECTED = 0xD0303030;
    private static final int COLOR_HEADER = 0xB0000000;
    private static final int COLOR_ACCENT = 0xFF94E4D3;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xA0A0A0;
    private static final int COLOR_TAB_TEXT = 0xA8B4B1;
    private static final int COLOR_AUTO = 0xFF3D6F86;
    private static final int COLOR_MANUAL = 0xFF7A5A2A;

    private final GuiScreen parentScreen;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE d MMM yyyy, HH:mm:ss", Locale.ENGLISH);

    /** World folders with backups, and the selected world's backups. */
    private List worlds = new ArrayList();
    private List backups = new ArrayList();
    private String selectedWorld;
    private KirBackups.Backup selectedBackup;

    /** Each world's backup count and total size, for its row. */
    private int[] worldCounts = new int[0];
    private long[] worldSizes = new long[0];

    /** Layout, from initGui. */
    private int left;
    private int right;
    private int listTop;
    private int listBottom;
    private int worldsRight;
    private int backupsLeft;
    private int statusTop;

    private float worldScroll;
    private float backupScroll;

    /** The backup state last seen, to reload the lists once a backup or restore finishes. */
    private int lastState = -1;

    private GuiIngame hiddenHud;

    public GuiKirmodBackups(GuiScreen parent)
    {
        this.parentScreen = parent;
    }

    public void initGui()
    {
        Keyboard.enableRepeatEvents(true);

        if (this.mc.theWorld != null && this.mc.ingameGUI != null && !(this.mc.ingameGUI instanceof GuiKirmod.HudHider))
        {
            this.hiddenHud = this.mc.ingameGUI;
            this.mc.ingameGUI = new GuiKirmod.HudHider(this.mc, this.hiddenHud);
        }

        int var1 = Math.min(MAX_W, this.width - 2 * MARGIN);
        this.left = (this.width - var1) / 2;
        this.right = this.left + var1;
        this.listTop = MARGIN + HEADER_H + 4;
        int var2 = this.height - MARGIN - BUTTON_H;
        this.statusTop = var2 - 4 - STATUS_H;
        this.listBottom = this.statusTop - 4;
        this.worldsRight = this.left + Math.max(110, Math.min(160, var1 * 3 / 10));
        this.backupsLeft = this.worldsRight + 4;

        this.controlList.clear();
        // Header: Open Folder and the close button on the right.
        int var3 = this.addButton(CLOSE_ID, this.right, MARGIN, "x", HEADER_H);
        this.addButton(FOLDER_ID, var3 - 2, MARGIN, "Open Folder", 0);

        // Footer: Backup Now on the left; Restore, Delete and Done on the right.
        int var4 = this.addButton(DONE_ID, this.right, var2, "Done", 70);
        var4 = this.addButton(DELETE_ID, var4 - 2, var2, "Delete", 0);
        this.addButton(RESTORE_ID, var4 - 2, var2, "Restore as Copy", 0);
        int var5 = this.getButtonWidth("Backup Now");
        this.controlList.add(new GuiKirmod.FlatButton(BACKUP_ID, this.left, var2, var5, BUTTON_H, "Backup Now"));

        this.reload();
        this.updateButtons();
    }

    private int getButtonWidth(String text)
    {
        return this.fontRenderer.getStringWidth(text) + 14;
    }

    /** Adds a flat button whose right edge is at right; returns its left edge. Width 0 fits the text. */
    private int addButton(int id, int right, int y, String text, int width)
    {
        int var6 = width > 0 ? width : this.getButtonWidth(text);
        this.controlList.add(new GuiKirmod.FlatButton(id, right - var6, y, var6, HEADER_H, text));
        return right - var6;
    }

    /** Reads the backups folder again, keeping the selection where it can. */
    private void reload()
    {
        this.worlds = KirBackups.getBackedUpWorlds();
        IntegratedServer var1 = KirBackups.getServer(this.mc);
        String var2 = var1 == null ? null : var1.getFolderName();

        // The world being played comes first, even before its first backup.
        if (var2 != null)
        {
            this.worlds.remove(var2);
            this.worlds.add(0, var2);
        }

        this.worldCounts = new int[this.worlds.size()];
        this.worldSizes = new long[this.worlds.size()];

        for (int var3 = 0; var3 < this.worlds.size(); ++var3)
        {
            List var4 = KirBackups.getBackups((String)this.worlds.get(var3));
            this.worldCounts[var3] = var4.size();

            for (int var5 = 0; var5 < var4.size(); ++var5)
            {
                this.worldSizes[var3] += ((KirBackups.Backup)var4.get(var5)).size;
            }
        }

        if (this.selectedWorld == null || !this.worlds.contains(this.selectedWorld))
        {
            this.selectedWorld = this.worlds.isEmpty() ? null : (String)this.worlds.get(0);
            this.backupScroll = 0.0F;
        }

        this.loadBackups();
        this.scrollWorlds(0.0F);
    }

    private void loadBackups()
    {
        File var1 = this.selectedBackup == null ? null : this.selectedBackup.file;
        this.backups = this.selectedWorld == null ? new ArrayList() : KirBackups.getBackups(this.selectedWorld);
        this.selectedBackup = null;

        for (int var2 = 0; var2 < this.backups.size(); ++var2)
        {
            KirBackups.Backup var3 = (KirBackups.Backup)this.backups.get(var2);

            if (var3.file.equals(var1))
            {
                this.selectedBackup = var3;
            }
        }

        this.scrollBackups(0.0F);
        this.updateButtons();
    }

    private void updateButtons()
    {
        for (int var1 = 0; var1 < this.controlList.size(); ++var1)
        {
            GuiButton var2 = (GuiButton)this.controlList.get(var1);

            if (var2.id == RESTORE_ID || var2.id == DELETE_ID)
            {
                var2.enabled = this.selectedBackup != null && !KirBackups.isBusy();
            }
            else if (var2.id == BACKUP_ID)
            {
                var2.enabled = KirBackups.whyNot(this.mc) == null;
            }
        }
    }

    private String getDisplayName(String folder)
    {
        return KirBackups.getDisplayName(folder);
    }

    /* ------------------------------------------------------------------ Input */

    protected void actionPerformed(GuiButton par1GuiButton)
    {
        if (!par1GuiButton.enabled)
        {
            return;
        }

        switch (par1GuiButton.id)
        {
            case DONE_ID:
            case CLOSE_ID:
                this.mc.displayGuiScreen(this.parentScreen);
                break;

            case RESTORE_ID:
                if (this.selectedBackup != null)
                {
                    this.mc.displayGuiScreen(new GuiYesNo(this, "Restore this backup as a new world?", "\"" + this.getDisplayName(this.selectedBackup.folder) + "\" from " + this.dateFormat.format(new Date(this.selectedBackup.date)) + ". The world itself is not changed.", RESTORE_ID));
                }

                break;

            case DELETE_ID:
                if (this.selectedBackup != null)
                {
                    this.mc.displayGuiScreen(new GuiYesNo(this, "Delete this backup?", this.dateFormat.format(new Date(this.selectedBackup.date)) + " (" + KirBackups.formatSize(this.selectedBackup.size) + "). This can't be undone.", DELETE_ID));
                }

                break;

            case FOLDER_ID:
                File var2 = this.selectedWorld != null ? KirBackups.getWorldBackupDir(this.selectedWorld) : KirBackups.getBackupsDir();
                var2.mkdirs();
                KirCapes.openDir(var2);
                break;

            case BACKUP_ID:
                KirBackups.start(this.mc, false);
                this.updateButtons();
        }
    }

    public void confirmClicked(boolean par1, int par2)
    {
        if (par1 && this.selectedBackup != null)
        {
            if (par2 == DELETE_ID)
            {
                if (!this.selectedBackup.file.delete())
                {
                    KirUtil.log("Could not delete " + this.selectedBackup.file, (Throwable)null);
                }

                this.selectedBackup = null;
            }
            else if (par2 == RESTORE_ID)
            {
                KirBackups.restore(this.selectedBackup);
            }
        }

        // Showing this screen again runs initGui, which reloads the lists.
        this.mc.displayGuiScreen(this);
    }

    protected void mouseClicked(int par1, int par2, int par3)
    {
        super.mouseClicked(par1, par2, par3);

        if (par3 != 0 || par2 < this.listTop || par2 >= this.listBottom)
        {
            return;
        }

        if (par1 >= this.left && par1 < this.worldsRight)
        {
            int var4 = (par2 - this.listTop - 14 + (int)this.worldScroll) / WORLD_ROW_H;

            if (par2 >= this.listTop + 14 && var4 >= 0 && var4 < this.worlds.size())
            {
                this.playClick();
                String var5 = (String)this.worlds.get(var4);

                if (!var5.equals(this.selectedWorld))
                {
                    this.selectedWorld = var5;
                    this.selectedBackup = null;
                    this.backupScroll = 0.0F;
                    this.loadBackups();
                }
            }
        }
        else if (par1 >= this.backupsLeft && par1 < this.right)
        {
            int var6 = (par2 - this.listTop - 14 + (int)this.backupScroll) / BACKUP_ROW_H;

            if (par2 >= this.listTop + 14 && var6 >= 0 && var6 < this.backups.size())
            {
                this.playClick();
                this.selectedBackup = (KirBackups.Backup)this.backups.get(var6);
                this.updateButtons();
            }
        }
    }

    public void handleMouseInput()
    {
        super.handleMouseInput();
        int var1 = Mouse.getEventDWheel();

        if (var1 != 0)
        {
            int var2 = Mouse.getEventX() * this.width / this.mc.displayWidth;
            float var3 = var1 > 0 ? -1.0F : 1.0F;

            if (var2 < this.worldsRight)
            {
                this.scrollWorlds(var3 * (float)WORLD_ROW_H * 2.0F);
            }
            else
            {
                this.scrollBackups(var3 * (float)BACKUP_ROW_H * 2.0F);
            }
        }
    }

    protected void keyTyped(char par1, int par2)
    {
        if (par2 == Keyboard.KEY_ESCAPE)
        {
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }

        // Up and down move through the backups.
        if ((par2 == Keyboard.KEY_UP || par2 == Keyboard.KEY_DOWN) && !this.backups.isEmpty())
        {
            int var3 = this.backups.indexOf(this.selectedBackup);
            var3 = var3 < 0 ? 0 : Math.max(0, Math.min(this.backups.size() - 1, var3 + (par2 == Keyboard.KEY_UP ? -1 : 1)));
            this.selectedBackup = (KirBackups.Backup)this.backups.get(var3);
            int var4 = var3 * BACKUP_ROW_H;
            int var5 = this.listBottom - this.listTop - 14;

            if ((float)var4 < this.backupScroll)
            {
                this.backupScroll = (float)var4;
            }
            else if ((float)(var4 + BACKUP_ROW_H) > this.backupScroll + (float)var5)
            {
                this.backupScroll = (float)(var4 + BACKUP_ROW_H - var5);
            }

            this.updateButtons();
        }
    }

    private void scrollWorlds(float amount)
    {
        int var2 = Math.max(0, this.worlds.size() * WORLD_ROW_H - (this.listBottom - this.listTop - 14));
        this.worldScroll = Math.max(0.0F, Math.min((float)var2, this.worldScroll + amount));
    }

    private void scrollBackups(float amount)
    {
        int var2 = Math.max(0, this.backups.size() * BACKUP_ROW_H - (this.listBottom - this.listTop - 14));
        this.backupScroll = Math.max(0.0F, Math.min((float)var2, this.backupScroll + amount));
    }

    private void playClick()
    {
        this.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);
    }

    public void updateScreen()
    {
        int var1 = KirBackups.getState();

        if (var1 != this.lastState)
        {
            // A backup or restore just finished (or started): the lists and buttons change with it.
            if (this.lastState >= 0 && !KirBackups.isBusy())
            {
                this.reload();
            }

            this.lastState = var1;
        }

        this.updateButtons();
    }

    public void onGuiClosed()
    {
        Keyboard.enableRepeatEvents(false);

        if (this.hiddenHud != null)
        {
            if (this.mc.ingameGUI instanceof GuiKirmod.HudHider)
            {
                this.mc.ingameGUI = this.hiddenHud;
            }

            this.hiddenHud = null;
        }
    }

    /* ------------------------------------------------------------------ Drawing */

    public void drawScreen(int par1, int par2, float par3)
    {
        if (this.mc.theWorld == null)
        {
            this.drawBackground(0);
        }
        else
        {
            if (mod_Kirmod.MENU_BLUR.isEnabled())
            {
                mod_Kirmod.MENU_BLUR.blurScreen(this.mc, this.width, this.height);
            }

            if (mod_Kirmod.MENU_DARKNESS.isEnabled() && mod_Kirmod.MENU_DARKNESS.getValue() > 0)
            {
                drawRect(0, 0, this.width, this.height, (int)(mod_Kirmod.MENU_DARKNESS.getAlpha() * 255.0F) << 24);
            }
        }

        // Header: the icon and the title.
        int var4 = this.getHeaderRight();
        drawRect(this.left, MARGIN, var4, MARGIN + HEADER_H, COLOR_HEADER);
        KirBackupHud.drawIcon(this.mc, this.left + 5, MARGIN + 2, 0.5F, KirBackups.isBusy(), 1.0F);
        this.fontRenderer.drawString("§lWorld Backups", this.left + 18, MARGIN + (HEADER_H - 8) / 2, COLOR_TEXT);
        String var5 = KirBackups.getBackupsDir().getPath();
        int var6 = this.left + 22 + this.fontRenderer.getStringWidth("§lWorld Backups") + 8;
        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var5, var4 - var6 - 6), var6, MARGIN + (HEADER_H - 8) / 2, COLOR_TEXT_DIM);

        this.drawWorlds(par1, par2);
        this.drawBackups(par1, par2);
        this.drawStatus();
        super.drawScreen(par1, par2, par3);
    }

    /** Where the header's title bar ends: left of its buttons. */
    private int getHeaderRight()
    {
        int var1 = this.right;

        for (int var2 = 0; var2 < this.controlList.size(); ++var2)
        {
            GuiButton var3 = (GuiButton)this.controlList.get(var2);

            if (var3.yPosition == MARGIN)
            {
                var1 = Math.min(var1, var3.xPosition - 2);
            }
        }

        return var1;
    }

    private void drawColumnHeader(int left, int right, String text, String count)
    {
        drawRect(left, this.listTop, right, this.listTop + 12, COLOR_HEADER);
        this.fontRenderer.drawString(text, left + 5, this.listTop + 2, COLOR_ACCENT & 0xFFFFFF);

        if (count != null)
        {
            this.fontRenderer.drawString(count, right - 5 - this.fontRenderer.getStringWidth(count), this.listTop + 2, COLOR_TEXT_DIM);
        }
    }

    private void drawWorlds(int mouseX, int mouseY)
    {
        drawRect(this.left, this.listTop, this.worldsRight, this.listBottom, COLOR_PANEL);
        this.drawColumnHeader(this.left, this.worldsRight, "Worlds", String.valueOf(this.worlds.size()));
        int var3 = this.listTop + 14;
        this.beginScissor(this.left, var3, this.worldsRight - this.left, this.listBottom - var3);
        IntegratedServer var4 = KirBackups.getServer(this.mc);
        String var5 = var4 == null ? null : var4.getFolderName();

        for (int var6 = 0; var6 < this.worlds.size(); ++var6)
        {
            int var7 = var3 + var6 * WORLD_ROW_H - (int)this.worldScroll;

            if (var7 + WORLD_ROW_H <= var3 || var7 >= this.listBottom)
            {
                continue;
            }

            String var8 = (String)this.worlds.get(var6);
            boolean var9 = var8.equals(this.selectedWorld);
            boolean var10 = mouseX >= this.left && mouseX < this.worldsRight && mouseY >= Math.max(var7, var3) && mouseY < Math.min(var7 + WORLD_ROW_H, this.listBottom);

            if (var9 || var10)
            {
                drawRect(this.left, var7, this.worldsRight, var7 + WORLD_ROW_H, var9 ? COLOR_SELECTED : 0x60000000);
            }

            if (var9)
            {
                drawRect(this.worldsRight - 2, var7, this.worldsRight, var7 + WORLD_ROW_H, COLOR_ACCENT);
            }

            int var11 = this.worldsRight - this.left - 14;
            int var12 = this.left + 6;

            if (var8.equals(var5))
            {
                // A dot for the world being played.
                drawRect(var12, var7 + 6, var12 + 4, var7 + 10, 0xFF55FF55);
                var12 += 7;
                var11 -= 7;
            }

            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(this.getDisplayName(var8), var11), var12, var7 + 4, var9 || var10 ? COLOR_TEXT : COLOR_TAB_TEXT);
            int var13 = this.worldCounts[var6];
            String var14 = var13 == 0 ? "No backups yet" : var13 + (var13 == 1 ? " backup, " : " backups, ") + KirBackups.formatSize(this.worldSizes[var6]);
            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var14, this.worldsRight - this.left - 12), this.left + 6, var7 + 14, COLOR_TEXT_DIM);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        if (this.worlds.isEmpty())
        {
            this.drawCenteredString(this.fontRenderer, "No backups", (this.left + this.worldsRight) / 2, var3 + 8, COLOR_TEXT_DIM);
        }
    }

    private void drawBackups(int mouseX, int mouseY)
    {
        drawRect(this.backupsLeft, this.listTop, this.right, this.listBottom, COLOR_PANEL);
        String var3 = this.selectedWorld == null ? "Backups" : this.getDisplayName(this.selectedWorld);
        this.drawColumnHeader(this.backupsLeft, this.right, this.fontRenderer.trimStringToWidth(var3, this.right - this.backupsLeft - 60), this.backups.size() + (this.backups.size() == 1 ? " backup" : " backups"));
        int var4 = this.listTop + 14;
        this.beginScissor(this.backupsLeft, var4, this.right - this.backupsLeft, this.listBottom - var4);

        for (int var5 = 0; var5 < this.backups.size(); ++var5)
        {
            int var6 = var4 + var5 * BACKUP_ROW_H - (int)this.backupScroll;

            if (var6 + BACKUP_ROW_H <= var4 || var6 >= this.listBottom)
            {
                continue;
            }

            KirBackups.Backup var7 = (KirBackups.Backup)this.backups.get(var5);
            boolean var8 = var7 == this.selectedBackup;
            boolean var9 = mouseX >= this.backupsLeft && mouseX < this.right && mouseY >= Math.max(var6, var4) && mouseY < Math.min(var6 + BACKUP_ROW_H, this.listBottom);
            drawRect(this.backupsLeft, var6, this.right, var6 + BACKUP_ROW_H - 1, var8 ? COLOR_SELECTED : (var9 ? COLOR_PANEL_HOVER : 0x30000000));

            if (var8)
            {
                drawRect(this.backupsLeft, var6, this.backupsLeft + 2, var6 + BACKUP_ROW_H - 1, COLOR_ACCENT);
            }

            // The badge: AUTO or MANUAL, on the right.
            String var10 = var7.automatic ? "AUTO" : "MANUAL";
            int var11 = this.fontRenderer.getStringWidth(var10) + 8;
            int var12 = this.right - 8 - var11;
            int var13 = var6 + (BACKUP_ROW_H - 12) / 2;
            drawRect(var12, var13, var12 + var11, var13 + 11, var7.automatic ? COLOR_AUTO : COLOR_MANUAL);
            drawRect(var12, var13, var12 + var11, var13 + 1, 0x40FFFFFF);
            this.fontRenderer.drawString(var10, var12 + 4, var13 + 2, COLOR_TEXT);

            int var14 = this.backupsLeft + 8;
            int var15 = var12 - 6 - var14;
            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(this.dateFormat.format(new Date(var7.date)), var15), var14, var6 + 4, var8 || var9 ? COLOR_TEXT : 0xE0E0E0);
            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(KirBackups.formatAgo(var7.date) + "  ·  " + KirBackups.formatSize(var7.size), var15), var14, var6 + 14, COLOR_TEXT_DIM);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        if (this.backups.isEmpty())
        {
            String var16 = this.selectedWorld == null ? "Backups you make show up here" : "No backups of this world yet";
            this.drawCenteredString(this.fontRenderer, var16, (this.backupsLeft + this.right) / 2, var4 + 8, COLOR_TEXT_DIM);
            this.drawCenteredString(this.fontRenderer, "Use Backup Now, or turn on Auto Backup", (this.backupsLeft + this.right) / 2, var4 + 20, COLOR_TEXT_DIM);
        }

        // A thin scroll indicator.
        int var17 = this.backups.size() * BACKUP_ROW_H;
        int var18 = this.listBottom - var4;

        if (var17 > var18)
        {
            int var19 = Math.max(12, var18 * var18 / var17);
            int var20 = var4 + (int)(this.backupScroll * (float)(var18 - var19) / (float)(var17 - var18));
            drawRect(this.right - 3, var20, this.right - 1, var20 + var19, 0xA0FFFFFF);
        }
    }

    /** The strip over the footer: the backup or restore under way, else how the last one went, else a summary. */
    private void drawStatus()
    {
        drawRect(this.left, this.statusTop, this.right, this.statusTop + STATUS_H, COLOR_PANEL);
        int var1 = KirBackups.getState();
        boolean var2 = KirBackups.isBusy();
        int var3 = this.left + 6;
        int var4 = this.right - 6;
        int var5 = this.statusTop + 5;

        if (var2)
        {
            float var6 = KirBackupHud.getShownProgress();
            String var7 = (var1 == KirBackups.RESTORING ? "Restoring " : "Backing up ") + KirBackups.getWorldName() + "  ·  " + KirBackups.getDetail();
            String var8 = var1 == KirBackups.SAVING ? "" : (int)(var6 * 100.0F) + "%";
            int var9 = this.fontRenderer.getStringWidth(var8);
            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var7, var4 - var3 - var9 - 8), var3, var5, COLOR_TEXT);
            this.fontRenderer.drawString(var8, var4 - var9, var5, COLOR_ACCENT & 0xFFFFFF);
            KirBackupHud.drawProgressBar(var3, var5 + 11, var4 - var3, 9, var6, true, false, var1 == KirBackups.SAVING);
            return;
        }

        String var10;
        int var11 = COLOR_TEXT_DIM;

        if (var1 == KirBackups.DONE || var1 == KirBackups.FAILED)
        {
            var10 = KirBackups.getResult();
            var11 = var1 == KirBackups.FAILED ? 0xFF7070 : 0x7EE08A;
        }
        else
        {
            long var12 = 0L;

            for (int var14 = 0; var14 < this.worldSizes.length; ++var14)
            {
                var12 += this.worldSizes[var14];
            }

            var10 = this.worlds.isEmpty() ? "No backups yet" : this.worlds.size() + (this.worlds.size() == 1 ? " world, " : " worlds, ") + KirBackups.formatSize(var12) + " of backups";
        }

        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var10, var4 - var3), var3, var5, var11);
        KirBackupHud.drawProgressBar(var3, var5 + 11, var4 - var3, 9, var1 == KirBackups.DONE || var1 == KirBackups.FAILED ? 1.0F : 0.0F, false, var1 == KirBackups.FAILED, false);
    }

    /** Clips drawing to a rectangle in GUI coordinates. */
    private void beginScissor(int x, int y, int width, int height)
    {
        ScaledResolution var5 = new ScaledResolution(this.mc.gameSettings, this.mc.displayWidth, this.mc.displayHeight);
        int var6 = var5.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * var6, this.mc.displayHeight - (y + height) * var6, Math.max(0, width) * var6, Math.max(0, height) * var6);
    }
}
