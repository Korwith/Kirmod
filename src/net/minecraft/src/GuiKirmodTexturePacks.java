package net.minecraft.src;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * The texture pack screen, in place of vanilla's (KirTexturePacks.update() swaps it in): a grid of packs
 * to pick from, with a preview of the one in use. Built like GuiKirmodSkins.
 *
 * "Built-in Texture Packs" holds Default and the Console Edition packs bundled with Kirmod; "Other
 * Texture Packs" holds the bundled packs ported from other versions (Classic Faithful 32x and 64x);
 * "Custom Texture Packs" holds the zips and folders in .minecraft/texturepacks/ (Open Texture Pack
 * Folder opens it, and new files show up while the screen is open). The text field at the bottom takes
 * the path of a texture pack zip or folder anywhere on disk; Import copies it into the folder and
 * switches to it, and Browse... picks one with a file dialog. Each tile shows the pack's resolution
 * (16x, 32x, 64x...).
 *
 * The preview is the pack in use: its icon, name and description, and some of its blocks and items,
 * drawn from the loaded terrain.png and items.png.
 */
public class GuiKirmodTexturePacks extends GuiScreen
{
    private static final int BROWSE_ID = 1;
    private static final int IMPORT_ID = 2;
    private static final int FOLDER_ID = 3;
    private static final int DONE_ID = 4;

    private static final int TILE_W = 74;
    private static final int TILE_H = 82;
    private static final int ICON = 48;
    private static final int GAP = 6;
    private static final int SCROLLBAR_W = 6;

    private static final int COLOR_GOLD = 0xFFD860;
    private static final int COLOR_GRAY = 0xA0A0A0;
    private static final int COLOR_ERROR = 0xFF6060;
    private static final int COLOR_OK = 0x80FF80;

    /** Terrain tiles shown in the preview: grass, stone, dirt, planks, cobblestone, bricks, log, bookshelf. */
    private static final int[] PREVIEW_BLOCKS = {3, 1, 2, 4, 16, 7, 20, 35};

    /** Items shown in the preview: diamond sword, iron pickaxe, apple, diamond. */
    private static final int[] PREVIEW_ITEMS = {67, 98, 10, 55};

    /** The last path typed, kept while the game runs so it is still there when the screen reopens. */
    private static String lastPath = "";

    private final GuiScreen parentScreen;
    private final List headers = new ArrayList();
    private final List tiles = new ArrayList();

    private GuiTextField pathField;
    private int pathFieldY;
    private int previewLeft;
    private int previewRight;
    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;
    private int contentHeight;
    private float scroll;
    private boolean draggingScroll;

    private TexturePackBase selected;

    private String status;
    private int statusColor;
    private long statusUntil;

    private int ticks;
    private long customDirStamp;

    /** Set by the file dialog's thread, picked up in updateScreen(). */
    private volatile String chosenPath;
    private volatile boolean browsing;

    private static class Header
    {
        final String title;
        final List hint;
        final int y;

        Header(String title, List hint, int y)
        {
            this.title = title;
            this.hint = hint;
            this.y = y;
        }
    }

    private static class Tile
    {
        final TexturePackBase pack;
        final int x;
        final int y;
        final List lines;
        final boolean truncated;

        Tile(TexturePackBase pack, int x, int y, List lines, boolean truncated)
        {
            this.pack = pack;
            this.x = x;
            this.y = y;
            this.lines = lines;
            this.truncated = truncated;
        }
    }

    public GuiKirmodTexturePacks(GuiScreen par1GuiScreen)
    {
        this.parentScreen = par1GuiScreen;
    }

    /**
     * Adds the buttons (and other controls) to the screen in question.
     */
    public void initGui()
    {
        Keyboard.enableRepeatEvents(true);
        this.controlList.clear();
        int var1 = this.width >= 420 ? 130 : 100;
        this.previewLeft = 8;
        this.previewRight = this.previewLeft + var1;
        this.listLeft = this.previewRight + 6;
        this.listRight = this.width - 8;
        this.listTop = 32;
        this.listBottom = this.height - 56;

        int var2 = this.height - 50;
        this.pathFieldY = var2 + 1;
        this.pathField = new GuiTextField(this.fontRenderer, 9, var2 + 1, this.width - 18 - 128, 18);
        this.pathField.setMaxStringLength(1024);
        this.pathField.setText(lastPath);
        this.controlList.add(new GuiButton(BROWSE_ID, this.width - 8 - 124, var2, 60, 20, "Browse..."));
        this.controlList.add(new GuiButton(IMPORT_ID, this.width - 8 - 60, var2, 60, 20, "Import"));
        this.controlList.add(new GuiButton(FOLDER_ID, this.width / 2 - 154, this.height - 26, 150, 20, "Open Texture Pack Folder"));
        this.controlList.add(new GuiButton(DONE_ID, this.width / 2 + 4, this.height - 26, 150, 20, StringTranslate.getInstance().translateKey("gui.done")));
        KirTexturePacks.refreshCustom(this.mc);
        this.customDirStamp = this.getCustomDirStamp();
        this.buildTiles();
    }

    /** Lays out the section headers and tiles for the current width and pack lists. */
    private void buildTiles()
    {
        this.headers.clear();
        this.tiles.clear();
        this.selected = KirTexturePacks.getSelected(this.mc);
        ArrayList var1 = new ArrayList();
        var1.add(KirTexturePacks.getDefault(this.mc));
        var1.addAll(KirTexturePacks.getBuiltins(KirTexturePacks.CATEGORY_BUILTIN));
        int var2 = this.addSection("Built-in Texture Packs", (String)null, var1, 4);
        List var4 = KirTexturePacks.getBuiltins(KirTexturePacks.CATEGORY_OTHER);

        if (!var4.isEmpty())
        {
            var2 = this.addSection("Other Texture Packs", (String)null, var4, var2 + 8);
        }

        List var3 = KirTexturePacks.getCustom(this.mc);
        var2 = this.addSection("Custom Texture Packs", var3.isEmpty() ? "Put texture pack zips in .minecraft/texturepacks, or import one below, to see them here" : null, var3, var2 + 8);
        this.contentHeight = var2 + 4;
        this.scrollBy(0.0F);
    }

    /** Adds a header (with an optional hint line under it) and its tiles at content y; returns the y below. */
    private int addSection(String title, String hint, List packs, int y)
    {
        List var5 = hint == null ? null : this.fontRenderer.listFormattedStringToWidth(hint, this.listRight - this.listLeft - SCROLLBAR_W - 16);
        this.headers.add(new Header(title, var5, y));
        int var6 = y + 14 + (var5 == null ? 0 : var5.size() * 10 + 2);
        return packs.isEmpty() ? var6 : this.layoutTiles(packs, var6 + 2);
    }

    /** Places one section's tiles in rows starting at content y, and returns the y below them. */
    private int layoutTiles(List packs, int y)
    {
        int var3 = this.listRight - this.listLeft - SCROLLBAR_W;
        int var4 = Math.max(1, (var3 - 8 + GAP) / (TILE_W + GAP));
        int var5 = (var3 - (var4 * (TILE_W + GAP) - GAP)) / 2;

        for (int var6 = 0; var6 < packs.size(); ++var6)
        {
            TexturePackBase var7 = (TexturePackBase)packs.get(var6);
            List var8 = new ArrayList(this.fontRenderer.listFormattedStringToWidth(KirTexturePacks.getName(var7), TILE_W - 4));
            boolean var9 = var8.size() > 2;

            if (var9)
            {
                StringBuilder var10 = new StringBuilder();

                for (int var11 = 1; var11 < var8.size(); ++var11)
                {
                    var10.append(var11 > 1 ? " " : "").append(var8.get(var11));
                }

                String var12 = this.fontRenderer.trimStringToWidth(var10.toString(), TILE_W - 4 - this.fontRenderer.getStringWidth(".."));
                var8 = new ArrayList(var8.subList(0, 1));
                var8.add(var12 + "..");
            }

            this.tiles.add(new Tile(var7, var5 + var6 % var4 * (TILE_W + GAP), y + var6 / var4 * (TILE_H + GAP), var8, var9));
        }

        int var13 = (packs.size() + var4 - 1) / var4;
        return y + var13 * (TILE_H + GAP) - (var13 > 0 ? GAP : 0);
    }

    /**
     * Fired when a control is clicked. This is the equivalent of ActionListener.actionPerformed(ActionEvent e).
     */
    protected void actionPerformed(GuiButton par1GuiButton)
    {
        if (!par1GuiButton.enabled)
        {
            return;
        }

        if (par1GuiButton.id == BROWSE_ID)
        {
            this.browse();
        }
        else if (par1GuiButton.id == IMPORT_ID)
        {
            this.importPath();
        }
        else if (par1GuiButton.id == FOLDER_ID)
        {
            KirTexturePacks.openCustomDir();
        }
        else if (par1GuiButton.id == DONE_ID)
        {
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    private void select(TexturePackBase pack)
    {
        KirTexturePacks.select(this.mc, pack);
        this.buildTiles();
    }

    /** Imports the texture pack named in the text field and switches to it, or says what is wrong with it. */
    private void importPath()
    {
        String var1 = this.pathField.getText().trim();

        if (var1.length() > 1 && (var1.startsWith("\"") && var1.endsWith("\"") || var1.startsWith("'") && var1.endsWith("'")))
        {
            var1 = var1.substring(1, var1.length() - 1);
        }

        if (var1.startsWith("file://"))
        {
            var1 = var1.substring(7);
        }

        if (var1.startsWith("~"))
        {
            var1 = System.getProperty("user.home") + var1.substring(1);
        }

        if (var1.length() == 0)
        {
            this.setStatus("Type the path of a texture pack zip or folder, or press Browse...", COLOR_GRAY);
            return;
        }

        TexturePackBase var2;

        try
        {
            var2 = KirTexturePacks.importPack(this.mc, new File(var1));
        }
        catch (IOException var4)
        {
            this.setStatus(var4.getMessage(), COLOR_ERROR);
            return;
        }

        lastPath = var1;
        this.customDirStamp = this.getCustomDirStamp();
        this.select(var2);
        this.scroll = (float)this.contentHeight;
        this.scrollBy(0.0F);
        this.setStatus("Imported and now using " + KirTexturePacks.getName(var2), COLOR_OK);
    }

    /** Opens a file dialog on Swing's thread; the result is picked up in updateScreen(). */
    private void browse()
    {
        if (this.browsing)
        {
            return;
        }

        this.browsing = true;
        this.setStatus("Choose a texture pack in the file dialog...", COLOR_GRAY);
        final File var1 = this.pathField.getText().trim().length() > 0 ? new File(this.pathField.getText().trim()).getParentFile() : null;
        javax.swing.SwingUtilities.invokeLater(new Runnable()
        {
            public void run()
            {
                javax.swing.JFrame var1x = null;

                try
                {
                    javax.swing.JFileChooser var2 = new javax.swing.JFileChooser(var1 != null && var1.isDirectory() ? var1 : new File(System.getProperty("user.home")));
                    var2.setDialogTitle("Choose a texture pack (zip or folder) to import");
                    var2.setFileSelectionMode(javax.swing.JFileChooser.FILES_AND_DIRECTORIES);
                    var2.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Texture packs (*.zip, folders)", new String[] {"zip"}));
                    // An owner that is always on top keeps the dialog in front of the game window.
                    var1x = new javax.swing.JFrame();
                    var1x.setAlwaysOnTop(true);

                    if (var2.showOpenDialog(var1x) == javax.swing.JFileChooser.APPROVE_OPTION && var2.getSelectedFile() != null)
                    {
                        GuiKirmodTexturePacks.this.chosenPath = var2.getSelectedFile().getAbsolutePath();
                    }
                }
                catch (Throwable var6)
                {
                    KirUtil.log("Could not open the file dialog", var6);
                }
                finally
                {
                    if (var1x != null)
                    {
                        var1x.dispose();
                    }

                    GuiKirmodTexturePacks.this.browsing = false;
                }
            }
        });
    }

    private void setStatus(String text, int color)
    {
        this.status = text;
        this.statusColor = color;
        this.statusUntil = System.currentTimeMillis() + 5000L;
    }

    /**
     * Called from the main game loop to update the screen.
     */
    public void updateScreen()
    {
        this.pathField.updateCursorCounter();
        String var1 = this.chosenPath;

        if (var1 != null)
        {
            this.chosenPath = null;
            this.pathField.setText(var1);
            this.importPath();
        }
        else if (!this.browsing && this.status != null && this.status.startsWith("Choose a texture pack"))
        {
            this.status = null;
        }

        // Like vanilla's screen, notice packs dropped into the folder while the screen is open.
        if (++this.ticks % 20 == 0)
        {
            long var2 = this.getCustomDirStamp();

            if (var2 != this.customDirStamp)
            {
                this.customDirStamp = var2;
                KirTexturePacks.forgetResolutions();
                KirTexturePacks.refreshCustom(this.mc);
                this.buildTiles();
            }
        }
    }

    /** Changes whenever a file in the texture pack folder is added, removed or rewritten. */
    private long getCustomDirStamp()
    {
        File[] var1 = KirTexturePacks.getCustomDir().listFiles();
        long var2 = 0L;

        if (var1 != null)
        {
            for (int var4 = 0; var4 < var1.length; ++var4)
            {
                var2 = var2 * 31L + var1[var4].getName().hashCode() + var1[var4].lastModified() + var1[var4].length();
            }
        }

        return var2;
    }

    /**
     * Fired when a key is typed. This is the equivalent of KeyListener.keyTyped(KeyEvent e).
     */
    protected void keyTyped(char par1, int par2)
    {
        if (this.pathField.isFocused() && par2 != Keyboard.KEY_ESCAPE)
        {
            if (par2 == Keyboard.KEY_RETURN)
            {
                this.importPath();
            }
            else
            {
                this.pathField.textboxKeyTyped(par1, par2);
                lastPath = this.pathField.getText();
            }
        }
        else
        {
            super.keyTyped(par1, par2);
        }
    }

    /**
     * Called when the mouse is clicked.
     */
    protected void mouseClicked(int par1, int par2, int par3)
    {
        super.mouseClicked(par1, par2, par3);
        this.pathField.mouseClicked(par1, par2, par3);

        if (par3 != 0)
        {
            return;
        }

        if (this.hasScrollbar() && par1 >= this.listRight - SCROLLBAR_W && par1 < this.listRight && par2 >= this.listTop && par2 < this.listBottom)
        {
            this.draggingScroll = true;
            this.dragScrollTo(par2);
            return;
        }

        Tile var4 = this.getTileAt(par1, par2);

        if (var4 != null)
        {
            this.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);

            if (var4.pack != this.selected)
            {
                this.select(var4.pack);
                this.setStatus("Now using " + KirTexturePacks.getName(var4.pack), COLOR_OK);
            }
        }
    }

    /**
     * Handles mouse input.
     */
    public void handleMouseInput()
    {
        super.handleMouseInput();
        int var1 = Mouse.getEventDWheel();

        if (var1 != 0)
        {
            this.scrollBy(var1 > 0 ? -(TILE_H + GAP) / 2.0F : (TILE_H + GAP) / 2.0F);
        }
    }

    private void scrollBy(float amount)
    {
        float var2 = (float)Math.max(0, this.contentHeight - (this.listBottom - this.listTop));
        this.scroll = Math.max(0.0F, Math.min(var2, this.scroll + amount));
    }

    private boolean hasScrollbar()
    {
        return this.contentHeight > this.listBottom - this.listTop;
    }

    private void dragScrollTo(int mouseY)
    {
        int var2 = this.listBottom - this.listTop;
        int var3 = this.getThumbHeight();
        float var4 = (float)(mouseY - this.listTop - var3 / 2) / (float)Math.max(1, var2 - var3);
        this.scroll = 0.0F;
        this.scrollBy(var4 * (float)(this.contentHeight - var2));
    }

    private int getThumbHeight()
    {
        int var1 = this.listBottom - this.listTop;
        return Math.max(16, var1 * var1 / Math.max(1, this.contentHeight));
    }

    private Tile getTileAt(int mouseX, int mouseY)
    {
        if (mouseX < this.listLeft || mouseX >= this.listRight - SCROLLBAR_W || mouseY < this.listTop || mouseY >= this.listBottom)
        {
            return null;
        }

        for (int var3 = 0; var3 < this.tiles.size(); ++var3)
        {
            Tile var4 = (Tile)this.tiles.get(var3);
            int var5 = this.listLeft + var4.x;
            int var6 = this.getTileScreenY(var4);

            if (mouseX >= var5 && mouseX < var5 + TILE_W && mouseY >= var6 && mouseY < var6 + TILE_H)
            {
                return var4;
            }
        }

        return null;
    }

    private int getTileScreenY(Tile tile)
    {
        return this.listTop + tile.y - (int)this.scroll;
    }

    /**
     * Called when the screen is unloaded. Used to disable keyboard repeat events
     */
    public void onGuiClosed()
    {
        Keyboard.enableRepeatEvents(false);
    }

    /* ------------------------------------------------------------------ Drawing */

    /**
     * Draws the screen and all the components in it.
     */
    public void drawScreen(int par1, int par2, float par3)
    {
        long var4 = System.currentTimeMillis();

        if (this.draggingScroll)
        {
            if (Mouse.isButtonDown(0))
            {
                this.dragScrollTo(par2);
            }
            else
            {
                this.draggingScroll = false;
            }
        }

        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, "Texture Packs", this.width / 2, 8, 16777215);

        if (this.status != null && var4 < this.statusUntil)
        {
            this.drawCenteredString(this.fontRenderer, this.status, this.width / 2, 19, this.statusColor);
        }
        else
        {
            this.drawCenteredString(this.fontRenderer, "Click a pack to use it. Rest the mouse on one to read about it", this.width / 2, 19, 0x707070);
        }

        this.drawPanel(this.previewLeft, this.listTop, this.previewRight, this.listBottom);
        this.drawPanel(this.listLeft, this.listTop, this.listRight, this.listBottom);
        this.drawList(par1, par2);
        this.drawPreview();
        this.pathField.drawTextBox();

        if (this.pathField.getText().length() == 0 && !this.pathField.isFocused())
        {
            this.fontRenderer.drawString("Import texture pack: path to a .zip or folder", 13, this.pathFieldY + 5, 0x707070);
        }

        super.drawScreen(par1, par2, par3);
        Tile var6 = this.getTileAt(par1, par2);

        if (var6 != null)
        {
            ArrayList var7 = new ArrayList();
            int var8 = KirTexturePacks.getResolution(var6.pack);
            var7.add("§e" + KirTexturePacks.getName(var6.pack) + (var8 > 0 ? " §7(" + var8 + "x)" : ""));
            String var9 = KirTexturePacks.getDescription(var6.pack);

            if (var9.length() > 0)
            {
                var7.addAll(this.fontRenderer.listFormattedStringToWidth(var9, 200));
            }

            this.drawTooltip(var7, par1, par2);
        }
    }

    /** A dark inset box with a thin border. */
    private void drawPanel(int x0, int y0, int x1, int y1)
    {
        drawRect(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF000000);
        drawRect(x0, y0, x1, y1, 0xB0101014);
        drawRect(x0, y0, x1, y0 + 1, 0x30FFFFFF);
    }

    private void drawList(int mouseX, int mouseY)
    {
        this.startClip(this.listLeft, this.listTop, this.listRight, this.listBottom);
        int var3 = this.listRight - this.listLeft - SCROLLBAR_W;

        for (int var4 = 0; var4 < this.headers.size(); ++var4)
        {
            Header var5 = (Header)this.headers.get(var4);
            int var6 = this.listTop + var5.y - (int)this.scroll;

            if (var6 + 14 + (var5.hint == null ? 0 : var5.hint.size() * 10) < this.listTop || var6 > this.listBottom)
            {
                continue;
            }

            int var7 = this.listLeft + 8;
            this.fontRenderer.drawStringWithShadow(var5.title, var7, var6 + 2, COLOR_GOLD);
            int var8 = var7 + this.fontRenderer.getStringWidth(var5.title) + 6;
            drawRect(var8, var6 + 6, this.listLeft + var3 - 8, var6 + 7, 0x50FFD860);

            if (var5.hint != null)
            {
                for (int var9 = 0; var9 < var5.hint.size(); ++var9)
                {
                    this.fontRenderer.drawString((String)var5.hint.get(var9), var7, var6 + 15 + var9 * 10, COLOR_GRAY);
                }
            }
        }

        Tile var10 = this.getTileAt(mouseX, mouseY);

        for (int var11 = 0; var11 < this.tiles.size(); ++var11)
        {
            Tile var12 = (Tile)this.tiles.get(var11);
            int var13 = this.getTileScreenY(var12);

            if (var13 + TILE_H > this.listTop && var13 < this.listBottom)
            {
                this.drawTile(var12, this.listLeft + var12.x, var13, var12 == var10);
            }
        }

        this.endClip();

        if (this.hasScrollbar())
        {
            int var14 = this.listRight - SCROLLBAR_W;
            int var15 = this.getThumbHeight();
            int var16 = this.listTop + (int)(this.scroll / (float)(this.contentHeight - (this.listBottom - this.listTop)) * (float)(this.listBottom - this.listTop - var15));
            drawRect(var14, this.listTop, this.listRight, this.listBottom, 0x60000000);
            drawRect(var14 + 1, var16 + 1, this.listRight - 1, var16 + var15 - 1, this.draggingScroll ? 0xFFE0E0E0 : 0xFF909090);
        }
    }

    private void drawTile(Tile tile, int x, int y, boolean hovered)
    {
        boolean var5 = tile.pack == this.selected;

        if (var5)
        {
            drawRect(x, y, x + TILE_W, y + TILE_H, 0xFFFFC000);
            drawRect(x + 1, y + 1, x + TILE_W - 1, y + TILE_H - 1, 0xFF3A2C08);
        }
        else if (hovered)
        {
            drawRect(x, y, x + TILE_W, y + TILE_H, 0xFFB0B0B0);
            drawRect(x + 1, y + 1, x + TILE_W - 1, y + TILE_H - 1, 0xFF2A2A30);
        }
        else
        {
            drawRect(x, y, x + TILE_W, y + TILE_H, 0x60000000);
        }

        int var6 = x + (TILE_W - ICON) / 2;
        int var7 = y + 5;
        this.drawIcon(tile.pack, var6, var7, ICON);
        this.drawResolutionBadge(KirTexturePacks.getResolution(tile.pack), var6 + ICON + 2, var7 + ICON - 9);
        int var8 = var5 ? 0xFFE070 : (hovered ? 0xFFFFFF : 0xC8C8C8);

        for (int var9 = 0; var9 < tile.lines.size(); ++var9)
        {
            this.drawCenteredString(this.fontRenderer, (String)tile.lines.get(var9), x + TILE_W / 2, var7 + ICON + 4 + var9 * 9, var8);
        }
    }

    /** The pack's pack.png, size x size at (x, y). */
    private void drawIcon(TexturePackBase pack, int x, int y, int size)
    {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        pack.func_77535_b(this.mc.renderEngine);
        this.drawTexturedRect(x, y, size, size, 0.0D, 0.0D, 1.0D, 1.0D);
    }

    /** "16x", "32x" or "64x" in a small box whose right edge is at x. Higher resolutions get warmer colors. */
    private void drawResolutionBadge(int resolution, int right, int y)
    {
        if (resolution <= 0)
        {
            return;
        }

        String var4 = resolution + "x";
        int var5 = this.fontRenderer.getStringWidth(var4);
        int var6 = resolution >= 128 ? 0xFFC060FF : (resolution >= 64 ? 0xFFFFB030 : (resolution >= 32 ? 0xFF50D050 : 0xFF909090));
        int var7 = right - var5 - 3;
        drawRect(var7 - 1, y - 1, right + 1, y + 10, 0xFF000000);
        drawRect(var7, y, right, y + 9, var6 & 0xFF000000 | (var6 & 0xFEFEFE) >> 1);
        this.fontRenderer.drawStringWithShadow(var4, var7 + 2, y + 1, var6 & 0xFFFFFF);
    }

    /** The left panel: the pack in use, its icon, name, resolution and some of its blocks and items. */
    private void drawPreview()
    {
        int var1 = (this.previewLeft + this.previewRight) / 2;
        int var2 = this.previewRight - this.previewLeft;
        this.drawCenteredString(this.fontRenderer, "In Use", var1, this.listTop + 5, COLOR_GRAY);
        int var3 = this.listTop + 17;
        // The block and item samples get room first; the icon takes what is left, up to 64.
        int var7 = Math.min(24, (var2 - 16) / 4);
        int var14 = this.listBottom - this.listTop;

        if (var14 < 17 + 32 + 28 + var7 * 3 + 10 + 18)
        {
            var7 = 16;
        }

        int var4 = Math.max(24, Math.min(Math.min(64, var2 - 24), var14 - 17 - 28 - var7 * 3 - 10 - 18));
        this.drawIcon(this.selected, var1 - var4 / 2, var3, var4);
        var3 += var4 + 4;
        this.drawCenteredString(this.fontRenderer, this.trimToPreview(KirTexturePacks.getName(this.selected)), var1, var3, COLOR_GOLD);
        var3 += 10;
        int var5 = KirTexturePacks.getResolution(this.selected);
        String var6 = KirTexturePacks.getCategory(this.selected) + (var5 > 0 ? ", " + var5 + "x" : "");
        this.drawCenteredString(this.fontRenderer, var6, var1, var3, 0x808080);
        var3 += 14;

        // Four blocks per row, then a row of items, each drawn from the loaded atlas.
        int var8 = var1 - var7 * 2;

        if (var3 + var7 * 3 + 4 <= this.listBottom - 4)
        {
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.mc.renderEngine.getTexture("/terrain.png"));

            for (int var9 = 0; var9 < PREVIEW_BLOCKS.length; ++var9)
            {
                this.drawAtlasTile(PREVIEW_BLOCKS[var9], var8 + var9 % 4 * var7, var3 + var9 / 4 * var7, var7);
            }

            var3 += var7 * 2 + 4;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.mc.renderEngine.getTexture("/gui/items.png"));

            for (int var10 = 0; var10 < PREVIEW_ITEMS.length; ++var10)
            {
                this.drawAtlasTile(PREVIEW_ITEMS[var10], var8 + var10 * var7, var3, var7);
            }

            GL11.glDisable(GL11.GL_BLEND);
            var3 += var7 + 6;
        }

        String var11 = KirTexturePacks.getDescription(this.selected);
        List var12 = this.fontRenderer.listFormattedStringToWidth(var11, var2 - 10);

        for (int var13 = 0; var13 < var12.size() && var3 + 9 <= this.listBottom - 2; ++var13)
        {
            this.drawCenteredString(this.fontRenderer, (String)var12.get(var13), var1, var3, 0x909090);
            var3 += 9;
        }
    }

    /** Draws one tile of the bound 16x16-tile atlas at (x, y), size pixels wide. */
    private void drawAtlasTile(int index, int x, int y, int size)
    {
        double var5 = (double)(index % 16) / 16.0D;
        double var7 = (double)(index / 16) / 16.0D;
        this.drawTexturedRect(x, y, size, size, var5, var7, var5 + 0.0625D, var7 + 0.0625D);
    }

    private void drawTexturedRect(int x, int y, int w, int h, double u0, double v0, double u1, double v1)
    {
        Tessellator var14 = Tessellator.instance;
        var14.startDrawingQuads();
        var14.addVertexWithUV((double)x, (double)(y + h), (double)this.zLevel, u0, v1);
        var14.addVertexWithUV((double)(x + w), (double)(y + h), (double)this.zLevel, u1, v1);
        var14.addVertexWithUV((double)(x + w), (double)y, (double)this.zLevel, u1, v0);
        var14.addVertexWithUV((double)x, (double)y, (double)this.zLevel, u0, v0);
        var14.draw();
    }

    private String trimToPreview(String text)
    {
        int var2 = this.previewRight - this.previewLeft - 8;
        String var3 = this.fontRenderer.trimStringToWidth(text, var2);
        return var3.equals(text) ? text : this.fontRenderer.trimStringToWidth(text, var2 - this.fontRenderer.getStringWidth("..")) + "..";
    }

    private void drawTooltip(List lines, int x, int y)
    {
        int var4 = 0;

        for (int var5 = 0; var5 < lines.size(); ++var5)
        {
            var4 = Math.max(var4, this.fontRenderer.getStringWidth((String)lines.get(var5)));
        }

        int var6 = lines.size() * 10 - 1;
        int var7 = Math.min(x + 10, this.width - var4 - 6);
        int var8 = Math.max(4, Math.min(y - 14, this.height - var6 - 6));
        this.zLevel = 300.0F;
        drawRect(var7 - 3, var8 - 3, var7 + var4 + 3, var8 + var6 + 2, 0xF0100010);
        drawRect(var7 - 3, var8 - 3, var7 + var4 + 3, var8 - 2, 0xFF5000A0);
        drawRect(var7 - 3, var8 + var6 + 1, var7 + var4 + 3, var8 + var6 + 2, 0xFF28007F);

        for (int var9 = 0; var9 < lines.size(); ++var9)
        {
            this.fontRenderer.drawStringWithShadow((String)lines.get(var9), var7, var8 + var9 * 10, var9 == 0 ? 0xFFFFFF : 0xB0B0B0);
        }

        this.zLevel = 0.0F;
    }

    /** Clips drawing to a rectangle in GUI coordinates. */
    private void startClip(int x0, int y0, int x1, int y1)
    {
        int var5 = this.mc.displayWidth / this.width;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x0 * var5, this.mc.displayHeight - y1 * var5, (x1 - x0) * var5, (y1 - y0) * var5);
    }

    private void endClip()
    {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
