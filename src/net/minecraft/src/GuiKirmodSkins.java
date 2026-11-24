package net.minecraft.src;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Kirmod > Skins...: a grid of skins to pick from, with a preview of the one worn. Built like
 * GuiKirmodCapes.
 *
 * "Owned Skins" holds Default Skin (whatever the game would show) and the skin of the logged-in
 * Microsoft account (fetched by KirCapes; when not logged in, a warning says so). Each bundled skin pack
 * follows under its own header, which folds the pack open or shut when clicked; only the pack of the
 * worn skin starts open. "Skin Folder" holds the PNGs in .minecraft/skins/ (Open Skin Folder opens it,
 * and new files show up while the screen is open). The text field at the bottom takes the path of any
 * skin PNG on disk; Import copies it into the skin folder and wears it, and Browse... picks one with a
 * file dialog.
 *
 * In a world the preview is the player, turning slowly (drag it to turn it yourself); in the main menu
 * it is the skin on a bare player model. Both, and the tiles, show the skin's outer layer: the hat, and
 * for 64x64 skins the jacket, sleeves and trouser legs (see KirSkins.Model64).
 */
public class GuiKirmodSkins extends GuiScreen
{
    private static final int BROWSE_ID = 1;
    private static final int IMPORT_ID = 2;
    private static final int FOLDER_ID = 3;
    private static final int DONE_ID = 4;

    private static final int TILE_W = 66;
    private static final int TILE_H = 92;
    private static final int GAP = 6;
    private static final int SCROLLBAR_W = 6;

    /** Skins in the tiles are a 16x32 texel front view drawn this many times larger. */
    private static final int BODY_SCALE = 2;

    private static final int COLOR_GOLD = 0xFFD860;
    private static final int COLOR_GRAY = 0xA0A0A0;
    private static final int COLOR_ERROR = 0xFF6060;
    private static final int COLOR_WARNING = 0xFFAA40;
    private static final int COLOR_OK = 0x80FF80;

    /** The last path typed, kept while the game runs so it is still there when the screen reopens. */
    private static String lastPath = "";

    /** The packs folded open, kept while the game runs; null until the screen first opens. */
    private static Set openPacks;

    private final GuiScreen parentScreen;
    private final List headers = new ArrayList();
    private final List tiles = new ArrayList();

    /** GL texture per skin id, or -1 if the skin could not be read. */
    private final Map textures = new HashMap();

    /** Ids of the loaded skins that are 64x64, i.e. have a separate left arm and leg and an outer layer. */
    private final Set squareSkins = new HashSet();

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
    private boolean draggingPreview;
    private int lastMouseX;
    private float previewYaw = 150.0F;
    private long lastFrameTime;

    /** The id of the worn skin, or KirSkins.DEFAULT; cached so the skin folder isn't listed every frame. */
    private String selectedId;
    private KirSkins.Skin selected;

    private String status;
    private int statusColor;
    private long statusUntil;

    private int ticks;
    private long ownedDirStamp;
    private int accountVersion;

    /** Set by the file dialog's thread, picked up in updateScreen(). */
    private volatile String chosenPath;
    private volatile boolean browsing;

    private static ModelBiped skinModel;
    private static KirSkins.Model64 squareSkinModel;

    private static class Header
    {
        final String title;

        /** The pack this header folds, or null for a section that is always open. */
        final String pack;
        final List hint;
        final int hintColor;
        final int y;

        Header(String title, String pack, List hint, int hintColor, int y)
        {
            this.title = title;
            this.pack = pack;
            this.hint = hint;
            this.hintColor = hintColor;
            this.y = y;
        }
    }

    private static class Tile
    {
        /** null for Default Skin. */
        final KirSkins.Skin skin;
        final int x;
        final int y;
        final List lines;
        final boolean truncated;

        Tile(KirSkins.Skin skin, int x, int y, List lines, boolean truncated)
        {
            this.skin = skin;
            this.x = x;
            this.y = y;
            this.lines = lines;
            this.truncated = truncated;
        }

        String getId()
        {
            return this.skin == null ? KirSkins.DEFAULT : this.skin.id;
        }

        String getDisplayName()
        {
            return this.skin == null ? "Default Skin" : this.skin.getDisplayName();
        }
    }

    public GuiKirmodSkins(GuiScreen par1GuiScreen)
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
        int var1 = this.width >= 420 ? 120 : 96;
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
        this.controlList.add(new GuiButton(FOLDER_ID, this.width / 2 - 154, this.height - 26, 150, 20, "Open Skin Folder"));
        this.controlList.add(new GuiButton(DONE_ID, this.width / 2 + 4, this.height - 26, 150, 20, StringTranslate.getInstance().translateKey("gui.done")));
        this.ownedDirStamp = this.getOwnedDirStamp();

        if (KirCapes.getAccountState() == KirCapes.ACCOUNT_FAILED)
        {
            KirCapes.fetchAccountCapes();
        }

        if (openPacks == null)
        {
            openPacks = new HashSet();
            KirSkins.Skin var3 = KirSkins.getSelected();

            if (var3 != null && var3.pack != null)
            {
                openPacks.add(var3.pack);
            }
        }

        this.buildTiles();
    }

    /** Lays out the section headers and tiles for the current width, skin lists and open packs. */
    private void buildTiles()
    {
        this.headers.clear();
        this.tiles.clear();
        this.selected = KirSkins.getSelected();
        this.selectedId = this.selected == null ? KirSkins.DEFAULT : this.selected.id;

        this.accountVersion = KirCapes.getAccountVersion();
        ArrayList var1 = new ArrayList();
        var1.add((Object)null);
        var1.addAll(KirSkins.getAccountSkins());
        int var2 = KirCapes.getAccountState();
        String var3 = null;
        int var4 = COLOR_GRAY;

        if (var2 == KirCapes.ACCOUNT_NOT_LOGGED_IN)
        {
            var3 = "Warning: you are not logged in to a Microsoft account, so your owned skin can't be loaded.";
            var4 = COLOR_WARNING;
        }
        else if (var2 == KirCapes.ACCOUNT_LOADING)
        {
            var3 = "Loading your skin...";
        }
        else if (var2 == KirCapes.ACCOUNT_FAILED)
        {
            var3 = "Couldn't load your skin: " + KirCapes.getAccountError();
            var4 = COLOR_ERROR;
        }

        int var5 = this.addSection("Owned Skins", (String)null, var3, var4, var1, 4);
        List var6 = KirSkins.getPacks();

        for (int var7 = 0; var7 < var6.size(); ++var7)
        {
            KirSkins.Pack var8 = (KirSkins.Pack)var6.get(var7);
            boolean var9 = openPacks.contains(var8.name);
            String var10 = (var9 ? "- " : "+ ") + var8.name + " (" + var8.skins.size() + ")";
            var5 = this.addSection(var10, var8.name, (String)null, COLOR_GRAY, var9 ? var8.skins : new ArrayList(), var5 + (var7 == 0 ? 8 : 2));
        }

        List var11 = KirSkins.getOwned();
        var5 = this.addSection("Skin Folder", (String)null, var11.isEmpty() ? "Put skin PNGs in .minecraft/skins, or import one below, to see them here" : null, COLOR_GRAY, var11, var5 + 8);
        this.contentHeight = var5 + 4;
        this.scrollBy(0.0F);
    }

    /** Adds a header (with an optional hint line under it) and its tiles at content y; returns the y below. */
    private int addSection(String title, String pack, String hint, int hintColor, List skins, int y)
    {
        List var7 = hint == null ? null : this.fontRenderer.listFormattedStringToWidth(hint, this.listRight - this.listLeft - SCROLLBAR_W - 16);
        this.headers.add(new Header(title, pack, var7, hintColor, y));
        int var8 = y + 14 + (var7 == null ? 0 : var7.size() * 10 + 2);
        return skins.isEmpty() ? var8 : this.layoutTiles(skins, var8 + 2);
    }

    /** Places one section's tiles in rows starting at content y, and returns the y below them. */
    private int layoutTiles(List skins, int y)
    {
        int var3 = this.listRight - this.listLeft - SCROLLBAR_W;
        int var4 = Math.max(1, (var3 - 8 + GAP) / (TILE_W + GAP));
        int var5 = (var3 - (var4 * (TILE_W + GAP) - GAP)) / 2;

        for (int var6 = 0; var6 < skins.size(); ++var6)
        {
            KirSkins.Skin var7 = (KirSkins.Skin)skins.get(var6);
            String var8 = var7 == null ? "Default Skin" : var7.getDisplayName();
            List var9 = new ArrayList(this.fontRenderer.listFormattedStringToWidth(var8, TILE_W - 4));
            boolean var10 = var9.size() > 2;

            if (var10)
            {
                StringBuilder var11 = new StringBuilder();

                for (int var12 = 1; var12 < var9.size(); ++var12)
                {
                    var11.append(var12 > 1 ? " " : "").append(var9.get(var12));
                }

                String var13 = this.fontRenderer.trimStringToWidth(var11.toString(), TILE_W - 4 - this.fontRenderer.getStringWidth(".."));
                var9 = new ArrayList(var9.subList(0, 1));
                var9.add(var13 + "..");
            }

            this.tiles.add(new Tile(var7, var5 + var6 % var4 * (TILE_W + GAP), y + var6 / var4 * (TILE_H + GAP), var9, var10));
        }

        int var14 = (skins.size() + var4 - 1) / var4;
        return y + var14 * (TILE_H + GAP) - (var14 > 0 ? GAP : 0);
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
            KirSkins.openOwnedDir();
        }
        else if (par1GuiButton.id == DONE_ID)
        {
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    private void select(KirSkins.Skin skin)
    {
        KirSkins.select(skin);
        this.buildTiles();
    }

    /** Imports the skin file named in the text field and wears it, or says what is wrong with it. */
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
            this.setStatus("Type the path of a skin PNG, or press Browse...", COLOR_GRAY);
            return;
        }

        KirSkins.Skin var2;

        try
        {
            var2 = KirSkins.importSkin(new File(var1));
        }
        catch (IOException var4)
        {
            this.setStatus(var4.getMessage(), COLOR_ERROR);
            return;
        }

        if (var2 == null)
        {
            this.setStatus("Could not import " + new File(var1).getName(), COLOR_ERROR);
            return;
        }

        this.textures.remove(var2.id);
        lastPath = var1;
        this.ownedDirStamp = this.getOwnedDirStamp();
        this.select(var2);
        this.scroll = (float)this.contentHeight;
        this.scrollBy(0.0F);
        this.setStatus("Imported and now wearing " + var2.getDisplayName(), COLOR_OK);
    }

    /** Opens a file dialog on Swing's thread; the result is picked up in updateScreen(). */
    private void browse()
    {
        if (this.browsing)
        {
            return;
        }

        this.browsing = true;
        this.setStatus("Choose a skin in the file dialog...", COLOR_GRAY);
        final File var1 = this.pathField.getText().trim().length() > 0 ? new File(this.pathField.getText().trim()).getParentFile() : null;
        javax.swing.SwingUtilities.invokeLater(new Runnable()
        {
            public void run()
            {
                javax.swing.JFrame var1x = null;

                try
                {
                    javax.swing.JFileChooser var2 = new javax.swing.JFileChooser(var1 != null && var1.isDirectory() ? var1 : new File(System.getProperty("user.home")));
                    var2.setDialogTitle("Choose a skin to import");
                    var2.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Skin images (*.png)", new String[] {"png"}));
                    // An owner that is always on top keeps the dialog in front of the game window.
                    var1x = new javax.swing.JFrame();
                    var1x.setAlwaysOnTop(true);

                    if (var2.showOpenDialog(var1x) == javax.swing.JFileChooser.APPROVE_OPTION && var2.getSelectedFile() != null)
                    {
                        GuiKirmodSkins.this.chosenPath = var2.getSelectedFile().getAbsolutePath();
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

                    GuiKirmodSkins.this.browsing = false;
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
        else if (!this.browsing && this.status != null && this.status.startsWith("Choose a skin"))
        {
            this.status = null;
        }

        if (KirCapes.getAccountVersion() != this.accountVersion)
        {
            this.buildTiles();
        }

        // Like the texture pack screen, notice skins dropped into the folder while the screen is open.
        if (++this.ticks % 20 == 0)
        {
            long var2 = this.getOwnedDirStamp();

            if (var2 != this.ownedDirStamp)
            {
                this.ownedDirStamp = var2;
                this.forgetTextures("owned:");

                if (this.selectedId.startsWith("owned:"))
                {
                    KirSkins.reload();
                }

                this.buildTiles();
            }
        }
    }

    /** Changes whenever a file in the skin folder is added, removed or rewritten. */
    private long getOwnedDirStamp()
    {
        File[] var1 = KirSkins.getOwnedDir().listFiles();
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

        Header var4 = this.getPackHeaderAt(par1, par2);

        if (var4 != null)
        {
            this.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);

            if (!openPacks.remove(var4.pack))
            {
                openPacks.add(var4.pack);
            }

            this.forgetTextures("pack:" + var4.pack + "/");
            this.buildTiles();
            return;
        }

        Tile var5 = this.getTileAt(par1, par2);

        if (var5 != null)
        {
            this.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);

            if (!var5.getId().equals(this.selectedId))
            {
                this.select(var5.skin);
                this.setStatus(var5.skin == null ? "Back to the default skin" : "Now wearing " + var5.skin.getDisplayName(), COLOR_OK);
            }
        }
        else if (par1 >= this.previewLeft && par1 < this.previewRight && par2 >= this.listTop && par2 < this.listBottom)
        {
            this.draggingPreview = true;
            this.lastMouseX = par1;
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

    private boolean isInList(int mouseX, int mouseY)
    {
        return mouseX >= this.listLeft && mouseX < this.listRight - SCROLLBAR_W && mouseY >= this.listTop && mouseY < this.listBottom;
    }

    /** The pack header row under the mouse, or null. */
    private Header getPackHeaderAt(int mouseX, int mouseY)
    {
        if (!this.isInList(mouseX, mouseY))
        {
            return null;
        }

        for (int var3 = 0; var3 < this.headers.size(); ++var3)
        {
            Header var4 = (Header)this.headers.get(var3);
            int var5 = this.listTop + var4.y - (int)this.scroll;

            if (var4.pack != null && mouseY >= var5 && mouseY < var5 + 13)
            {
                return var4;
            }
        }

        return null;
    }

    private Tile getTileAt(int mouseX, int mouseY)
    {
        if (!this.isInList(mouseX, mouseY))
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
        this.forgetTextures("");
    }

    /** Frees the tile textures of skins whose id starts with prefix. */
    private void forgetTextures(String prefix)
    {
        Iterator var2 = this.textures.entrySet().iterator();

        while (var2.hasNext())
        {
            Map.Entry var3 = (Map.Entry)var2.next();

            if (((String)var3.getKey()).startsWith(prefix))
            {
                int var4 = ((Integer)var3.getValue()).intValue();

                if (var4 >= 0)
                {
                    this.mc.renderEngine.deleteTexture(var4);
                }

                this.squareSkins.remove(var3.getKey());
                var2.remove();
            }
        }
    }

    /** The skin's GL texture, loaded the first time it is drawn; -1 if the image is unusable. */
    private int getTexture(KirSkins.Skin skin)
    {
        Integer var2 = (Integer)this.textures.get(skin.id);

        if (var2 == null)
        {
            BufferedImage var3 = skin.loadImage();
            var2 = Integer.valueOf(var3 != null ? this.mc.renderEngine.allocateAndSetupTexture(var3) : -1);
            this.textures.put(skin.id, var2);

            if (KirSkins.isSquare(var3))
            {
                this.squareSkins.add(skin.id);
            }
        }

        return var2.intValue();
    }

    /* ------------------------------------------------------------------ Drawing */

    /**
     * Draws the screen and all the components in it.
     */
    public void drawScreen(int par1, int par2, float par3)
    {
        long var4 = System.currentTimeMillis();
        float var6 = this.lastFrameTime == 0L ? 0.0F : Math.min(0.1F, (float)(var4 - this.lastFrameTime) / 1000.0F);
        this.lastFrameTime = var4;

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

        if (this.draggingPreview)
        {
            if (Mouse.isButtonDown(0))
            {
                this.previewYaw += (float)(par1 - this.lastMouseX) * 2.0F;
                this.lastMouseX = par1;
            }
            else
            {
                this.draggingPreview = false;
            }
        }
        else
        {
            this.previewYaw += var6 * 30.0F;
        }

        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, "Skins", this.width / 2, 8, 16777215);

        if (this.status != null && var4 < this.statusUntil)
        {
            this.drawCenteredString(this.fontRenderer, this.status, this.width / 2, 19, this.statusColor);
        }
        else
        {
            this.drawCenteredString(this.fontRenderer, "Only you can see the skin you wear. Click a pack to open it", this.width / 2, 19, 0x707070);
        }

        this.drawPanel(this.previewLeft, this.listTop, this.previewRight, this.listBottom);
        this.drawPanel(this.listLeft, this.listTop, this.listRight, this.listBottom);
        this.drawList(par1, par2);
        this.drawPreview();
        this.pathField.drawTextBox();

        if (this.pathField.getText().length() == 0 && !this.pathField.isFocused())
        {
            this.fontRenderer.drawString("Import skin: path to a .png file", 13, this.pathFieldY + 5, 0x707070);
        }

        super.drawScreen(par1, par2, par3);
        Tile var7 = this.getTileAt(par1, par2);

        if (var7 != null && (var7.truncated || var7.skin != null && this.getTexture(var7.skin) < 0))
        {
            this.drawTooltip(var7.skin != null && this.getTexture(var7.skin) < 0 ? var7.getDisplayName() + " (can't be read)" : var7.getDisplayName(), par1, par2);
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
        Header var4 = this.getPackHeaderAt(mouseX, mouseY);

        for (int var5 = 0; var5 < this.headers.size(); ++var5)
        {
            Header var6 = (Header)this.headers.get(var5);
            int var7 = this.listTop + var6.y - (int)this.scroll;

            if (var7 + 14 + (var6.hint == null ? 0 : var6.hint.size() * 10) < this.listTop || var7 > this.listBottom)
            {
                continue;
            }

            int var8 = this.listLeft + 8;

            if (var6 == var4)
            {
                drawRect(this.listLeft + 4, var7, this.listLeft + var3 - 4, var7 + 13, 0x30FFFFFF);
            }

            this.fontRenderer.drawStringWithShadow(var6.title, var8, var7 + 2, var6.pack != null && var6 != var4 ? 0xE0C060 : COLOR_GOLD);
            int var9 = var8 + this.fontRenderer.getStringWidth(var6.title) + 6;
            drawRect(var9, var7 + 6, this.listLeft + var3 - 8, var7 + 7, 0x50FFD860);

            if (var6.hint != null)
            {
                for (int var10 = 0; var10 < var6.hint.size(); ++var10)
                {
                    this.fontRenderer.drawString((String)var6.hint.get(var10), var8, var7 + 15 + var10 * 10, var6.hintColor);
                }
            }
        }

        Tile var11 = this.getTileAt(mouseX, mouseY);

        for (int var12 = 0; var12 < this.tiles.size(); ++var12)
        {
            Tile var13 = (Tile)this.tiles.get(var12);
            int var14 = this.getTileScreenY(var13);

            if (var14 + TILE_H > this.listTop && var14 < this.listBottom)
            {
                this.drawTile(var13, this.listLeft + var13.x, var14, var13 == var11);
            }
        }

        this.endClip();

        if (this.hasScrollbar())
        {
            int var15 = this.listRight - SCROLLBAR_W;
            int var16 = this.getThumbHeight();
            int var17 = this.listTop + (int)(this.scroll / (float)(this.contentHeight - (this.listBottom - this.listTop)) * (float)(this.listBottom - this.listTop - var16));
            drawRect(var15, this.listTop, this.listRight, this.listBottom, 0x60000000);
            drawRect(var15 + 1, var17 + 1, this.listRight - 1, var17 + var16 - 1, this.draggingScroll ? 0xFFE0E0E0 : 0xFF909090);
        }
    }

    private void drawTile(Tile tile, int x, int y, boolean hovered)
    {
        boolean var5 = tile.getId().equals(this.selectedId);

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

        int var6 = x + (TILE_W - 16 * BODY_SCALE) / 2;
        int var7 = y + 5;
        int var8 = 32 * BODY_SCALE;

        if (tile.skin == null)
        {
            this.drawSkinFront(this.mc.renderEngine.getTexture("/mob/char.png"), false, var6, var7, BODY_SCALE);
        }
        else
        {
            int var9 = this.getTexture(tile.skin);

            if (var9 >= 0)
            {
                this.drawSkinFront(var9, this.squareSkins.contains(tile.skin.id), var6, var7, BODY_SCALE);
            }
            else
            {
                drawRect(var6, var7, var6 + 16 * BODY_SCALE, var7 + var8, 0xFF301010);
                this.drawCenteredString(this.fontRenderer, "?", var6 + 8 * BODY_SCALE, var7 + var8 / 2 - 4, COLOR_ERROR);
            }
        }

        int var10 = var5 ? 0xFFE070 : (hovered ? 0xFFFFFF : 0xC8C8C8);

        for (int var11 = 0; var11 < tile.lines.size(); ++var11)
        {
            this.drawCenteredString(this.fontRenderer, (String)tile.lines.get(var11), x + TILE_W / 2, var7 + var8 + 4 + var11 * 9, var10);
        }
    }

    /**
     * Draws the front of a skin flat, 16x32 texels at (x, y): head, body, arms and legs, then the outer
     * layer. A 64x32 skin has only the hat as outer layer and its left limbs are the right ones mirrored,
     * as the model does; a square (64x64) skin has its own left limbs and a jacket, sleeves and trouser
     * legs too.
     */
    private void drawSkinFront(int texture, boolean square, int x, int y, int scale)
    {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        this.mc.renderEngine.bindTexture(texture);
        Tessellator var6 = Tessellator.instance;
        int var7 = square ? 64 : 32;
        var6.startDrawingQuads();
        this.addFace(var6, x, y, scale, var7, 8, 8, 8, 8, 4, 0, false);
        this.addFace(var6, x, y, scale, var7, 20, 20, 8, 12, 4, 8, false);
        this.addFace(var6, x, y, scale, var7, 44, 20, 4, 12, 0, 8, false);
        this.addFace(var6, x, y, scale, var7, 4, 20, 4, 12, 4, 20, false);

        if (square)
        {
            this.addFace(var6, x, y, scale, var7, 36, 52, 4, 12, 12, 8, false);
            this.addFace(var6, x, y, scale, var7, 20, 52, 4, 12, 8, 20, false);
            this.addFace(var6, x, y, scale, var7, 20, 36, 8, 12, 4, 8, false);
            this.addFace(var6, x, y, scale, var7, 44, 36, 4, 12, 0, 8, false);
            this.addFace(var6, x, y, scale, var7, 52, 52, 4, 12, 12, 8, false);
            this.addFace(var6, x, y, scale, var7, 4, 36, 4, 12, 4, 20, false);
            this.addFace(var6, x, y, scale, var7, 4, 52, 4, 12, 8, 20, false);
        }
        else
        {
            this.addFace(var6, x, y, scale, var7, 44, 20, 4, 12, 12, 8, true);
            this.addFace(var6, x, y, scale, var7, 4, 20, 4, 12, 8, 20, true);
        }

        this.addFace(var6, x, y, scale, var7, 40, 8, 8, 8, 4, 0, false);
        var6.draw();
        GL11.glDisable(GL11.GL_BLEND);
    }

    /**
     * Adds the w x h texel rectangle at (u, v) of a 64-wide skin that is texHeight texels high, drawn at
     * texel (dx, dy) of the front view.
     */
    private void addFace(Tessellator tess, int x, int y, int scale, int texHeight, int u, int v, int w, int h, int dx, int dy, boolean mirror)
    {
        double var12 = (double)(x + dx * scale);
        double var14 = (double)(y + dy * scale);
        double var16 = var12 + (double)(w * scale);
        double var18 = var14 + (double)(h * scale);
        double var20 = (double)(mirror ? u + w : u) / 64.0D;
        double var22 = (double)(mirror ? u : u + w) / 64.0D;
        double var24 = (double)v / (double)texHeight;
        double var26 = (double)(v + h) / (double)texHeight;
        tess.addVertexWithUV(var12, var18, (double)this.zLevel, var20, var26);
        tess.addVertexWithUV(var16, var18, (double)this.zLevel, var22, var26);
        tess.addVertexWithUV(var16, var14, (double)this.zLevel, var22, var24);
        tess.addVertexWithUV(var12, var14, (double)this.zLevel, var20, var24);
    }

    /** The left panel: the player (or a bare model in the skin) turning, and the worn skin's name. */
    private void drawPreview()
    {
        int var1 = (this.previewLeft + this.previewRight) / 2;
        this.drawCenteredString(this.fontRenderer, "Preview", var1, this.listTop + 5, COLOR_GRAY);
        int var2 = this.listTop + 18;
        int var3 = this.listBottom - 30;

        if (this.mc.thePlayer != null && this.mc.theWorld != null)
        {
            int var4 = Math.min(40, Math.min((this.previewRight - this.previewLeft) / 2, (var3 - var2) * 10 / 19));
            this.drawPlayer(var1, var3 - 2, var4);
        }
        else
        {
            int var5 = this.selected != null ? this.getTexture(this.selected) : this.mc.renderEngine.getTexture("/mob/char.png");

            if (var5 >= 0)
            {
                int var6 = Math.min(100, (var3 - var2) * 9 / 10);
                this.drawSkinModel(var5, this.selected != null && this.squareSkins.contains(this.selected.id), var1, var2 + (var3 - var2 - var6) / 2, var6);
            }
        }

        String var7 = this.selected == null ? "Default Skin" : this.selected.getDisplayName();
        String var8 = this.selected == null ? "" : this.selected.getSource();
        this.drawCenteredString(this.fontRenderer, this.trimToPreview(var7), var1, this.listBottom - 24, COLOR_GOLD);
        this.drawCenteredString(this.fontRenderer, this.trimToPreview(var8), var1, this.listBottom - 13, 0x808080);
    }

    private String trimToPreview(String text)
    {
        int var2 = this.previewRight - this.previewLeft - 8;
        String var3 = this.fontRenderer.trimStringToWidth(text, var2);
        return var3.equals(text) ? text : this.fontRenderer.trimStringToWidth(text, var2 - this.fontRenderer.getStringWidth("..")) + "..";
    }

    /** The player turned to previewYaw, drawn like the inventory screen does; feet at (x, y). */
    private void drawPlayer(int x, int y, int scale)
    {
        EntityClientPlayerMP var4 = this.mc.thePlayer;
        float var5 = var4.renderYawOffset;
        float var6 = var4.prevRenderYawOffset;
        float var7 = var4.rotationYaw;
        float var8 = var4.prevRotationYaw;
        float var9 = var4.rotationPitch;
        float var10 = var4.prevRotationPitch;
        float var11 = var4.rotationYawHead;
        float var12 = var4.prevRotationYawHead;
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glPushMatrix();
        GL11.glTranslatef((float)x, (float)y, 50.0F);
        GL11.glScalef((float)(-scale), (float)scale, (float)scale);
        GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
        GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-10.0F, 1.0F, 0.0F, 0.0F);
        var4.renderYawOffset = var4.prevRenderYawOffset = this.previewYaw;
        var4.rotationYaw = var4.prevRotationYaw = this.previewYaw;
        var4.rotationYawHead = var4.prevRotationYawHead = this.previewYaw;
        var4.rotationPitch = var4.prevRotationPitch = 0.0F;
        GL11.glTranslatef(0.0F, var4.yOffset, 0.0F);
        RenderManager.instance.playerViewY = 180.0F;
        RenderManager.instance.renderEntityWithPosYaw(var4, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
        var4.renderYawOffset = var5;
        var4.prevRenderYawOffset = var6;
        var4.rotationYaw = var7;
        var4.prevRotationYaw = var8;
        var4.rotationPitch = var9;
        var4.prevRotationPitch = var10;
        var4.rotationYawHead = var11;
        var4.prevRotationYawHead = var12;
        GL11.glPopMatrix();
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
    }

    /**
     * A bare player model in the skin, its head at (x, y) and height pixels tall, turned to previewYaw. A
     * square (64x64) skin uses KirSkins.Model64, whose outer-layer boxes are drawn with their limbs.
     */
    private void drawSkinModel(int texture, boolean square, int x, int y, int height)
    {
        if (skinModel == null)
        {
            skinModel = new ModelBiped(0.0F);
            squareSkinModel = new KirSkins.Model64((ModelBiped)null);
        }

        ModelBiped var6 = square ? squareSkinModel : skinModel;

        // The model is 2 units tall (head top at -0.5, feet at 1.5) at 1/16 per texel.
        float var5 = (float)height / 2.0F;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glPushMatrix();
        GL11.glTranslatef((float)x, (float)y + var5 * 0.5F, 100.0F);
        GL11.glScalef(-var5, var5, var5);
        GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
        GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
        GL11.glRotatef(-10.0F, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef(this.previewYaw, 0.0F, 1.0F, 0.0F);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.renderEngine.bindTexture(texture);
        float var7 = 0.0625F;
        var6.bipedHead.render(var7);
        var6.bipedBody.render(var7);
        var6.bipedRightArm.render(var7);
        var6.bipedLeftArm.render(var7);
        var6.bipedRightLeg.render(var7);
        var6.bipedLeftLeg.render(var7);
        var6.bipedHeadwear.render(var7);
        GL11.glPopMatrix();
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
    }

    private void drawTooltip(String text, int x, int y)
    {
        int var4 = this.fontRenderer.getStringWidth(text);
        int var5 = Math.min(x + 10, this.width - var4 - 6);
        int var6 = y - 14;
        this.zLevel = 300.0F;
        drawRect(var5 - 3, var6 - 3, var5 + var4 + 3, var6 + 11, 0xF0100010);
        drawRect(var5 - 3, var6 - 3, var5 + var4 + 3, var6 - 2, 0xFF5000A0);
        drawRect(var5 - 3, var6 + 10, var5 + var4 + 3, var6 + 11, 0xFF28007F);
        this.fontRenderer.drawStringWithShadow(text, var5, var6, 16777215);
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
