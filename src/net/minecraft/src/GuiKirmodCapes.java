package net.minecraft.src;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Kirmod > Capes...: a grid of capes to pick from, with a preview of the one worn.
 *
 * "Owned Capes" holds Account Default (no override: the cape active on the account, see KirCapes), No
 * Cape and the capes of the logged-in Microsoft account (KirCapes fetches them
 * from the Minecraft services API; when not logged in, a warning says so and nothing is fetched). Each
 * category of the bundled gallery follows under its own header, which folds it open or shut when
 * clicked; only the category of the cape worn starts open. "Cape Folder" holds a custom file if one is worn and the
 * PNGs in .minecraft/capes/ (Open Cape Folder opens it, and new files show up while the screen is open,
 * like texture packs). The text field at the bottom takes the path of any cape PNG on disk; Browse...
 * picks one with a file dialog.
 *
 * In a world the preview is the player, turning slowly (drag it to turn it yourself); in the main menu
 * it is just the cape.
 */
public class GuiKirmodCapes extends GuiScreen
{
    private static final int BROWSE_ID = 1;
    private static final int USE_ID = 2;
    private static final int FOLDER_ID = 3;
    private static final int DONE_ID = 4;

    private static final int TILE_W = 66;
    private static final int TILE_H = 78;
    private static final int GAP = 6;
    private static final int SCROLLBAR_W = 6;

    /** Cape faces in the tiles are 10x16 texels drawn this many times larger. */
    private static final int FACE_SCALE = 3;

    private static final int COLOR_GOLD = 0xFFD860;
    private static final int COLOR_GRAY = 0xA0A0A0;
    private static final int COLOR_ERROR = 0xFF6060;
    private static final int COLOR_WARNING = 0xFFAA40;
    private static final int COLOR_OK = 0x80FF80;

    /** The last path typed, kept while the game runs so it is still there when the screen reopens. */
    private static String lastPath = "";

    /** The gallery categories folded open, kept while the game runs; null until the screen first opens. */
    private static Set openCategories;

    private final GuiScreen parentScreen;
    private final List headers = new ArrayList();
    private final List tiles = new ArrayList();

    /** GL texture per cape id, or -1 if the cape could not be read. */
    private final Map textures = new HashMap();

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

    /** The id of the worn cape, KirCapes.DEFAULT or KirCapes.NONE; cached so the cape folder isn't listed every frame. */
    private String selectedId;
    private KirCapes.Cape selected;

    private String status;
    private int statusColor;
    private long statusUntil;

    private int ticks;
    private long ownedDirStamp;
    private int accountVersion;

    /** Set by the file dialog's thread, picked up in updateScreen(). */
    private volatile String chosenPath;
    private volatile boolean browsing;

    private static ModelBiped capeModel;

    private static class Header
    {
        final String title;

        /** The category this header folds, or null for a section that is always open. */
        final String category;
        final List hint;
        final int hintColor;
        final int y;

        Header(String title, String category, List hint, int hintColor, int y)
        {
            this.title = title;
            this.category = category;
            this.hint = hint;
            this.hintColor = hintColor;
            this.y = y;
        }
    }

    private static class Tile
    {
        /** The cape's id, or KirCapes.DEFAULT for Account Default and KirCapes.NONE for No Cape. */
        final String id;

        /** null for No Cape, and for Account Default while the account wears no cape. */
        final KirCapes.Cape cape;
        final String name;
        final int x;
        final int y;
        final List lines;
        final boolean truncated;

        Tile(String id, KirCapes.Cape cape, String name, int x, int y, List lines, boolean truncated)
        {
            this.id = id;
            this.cape = cape;
            this.name = name;
            this.x = x;
            this.y = y;
            this.lines = lines;
            this.truncated = truncated;
        }

        String getId()
        {
            return this.id;
        }

        String getDisplayName()
        {
            return this.name;
        }
    }

    public GuiKirmodCapes(GuiScreen par1GuiScreen)
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
        this.controlList.add(new GuiButton(USE_ID, this.width - 8 - 60, var2, 60, 20, "Use File"));
        this.controlList.add(new GuiButton(FOLDER_ID, this.width / 2 - 154, this.height - 26, 150, 20, "Open Cape Folder"));
        this.controlList.add(new GuiButton(DONE_ID, this.width / 2 + 4, this.height - 26, 150, 20, StringTranslate.getInstance().translateKey("gui.done")));
        this.ownedDirStamp = this.getOwnedDirStamp();

        // Reopening the screen retries a fetch that failed (no internet, say).
        if (KirCapes.getAccountState() == KirCapes.ACCOUNT_FAILED)
        {
            KirCapes.fetchAccountCapes();
        }

        if (openCategories == null)
        {
            openCategories = new HashSet();
            KirCapes.Cape var3 = KirCapes.getSelected();

            if (var3 != null && var3.category != null)
            {
                openCategories.add(var3.category);
            }
        }

        this.buildTiles();
    }

    /** Lays out the section headers and tiles for the current width, cape lists and open categories. */
    private void buildTiles()
    {
        this.headers.clear();
        this.tiles.clear();
        this.selected = KirCapes.getSelected();
        this.selectedId = KirCapes.isDefault() ? KirCapes.DEFAULT : (this.selected == null ? KirCapes.NONE : this.selected.id);

        this.accountVersion = KirCapes.getAccountVersion();
        ArrayList var1 = new ArrayList();
        var1.add(KirCapes.DEFAULT);
        var1.add((Object)null);
        var1.addAll(KirCapes.getAccountCapes());
        int var2 = KirCapes.getAccountState();
        String var3 = null;
        int var4 = COLOR_GRAY;

        if (var2 == KirCapes.ACCOUNT_NOT_LOGGED_IN)
        {
            var3 = "Warning: you are not logged in to a Microsoft account, so your owned capes can't be loaded.";
            var4 = COLOR_WARNING;
        }
        else if (var2 == KirCapes.ACCOUNT_LOADING)
        {
            var3 = "Loading your capes...";
        }
        else if (var2 == KirCapes.ACCOUNT_FAILED)
        {
            var3 = "Couldn't load your capes: " + KirCapes.getAccountError();
            var4 = COLOR_ERROR;
        }
        else if (var1.size() == 2)
        {
            var3 = "This account doesn't own any capes";
        }

        int var5 = this.addSection("Owned Capes", (String)null, var3, var4, var1, 4);
        List var8 = KirCapes.getCategories();

        for (int var9 = 0; var9 < var8.size(); ++var9)
        {
            KirCapes.Category var10 = (KirCapes.Category)var8.get(var9);
            boolean var11 = openCategories.contains(var10.name);
            String var12 = (var11 ? "- " : "+ ") + var10.name + " (" + var10.capes.size() + ")";
            var5 = this.addSection(var12, var10.name, (String)null, COLOR_GRAY, var11 ? var10.capes : new ArrayList(), var5 + (var9 == 0 ? 8 : 2));
        }

        List var6 = KirCapes.getOwned();
        ArrayList var7 = new ArrayList();

        if (this.selected != null && this.selected.isCustomFile())
        {
            var7.add(this.selected);
        }

        var7.addAll(var6);
        var5 = this.addSection("Cape Folder", (String)null, var6.isEmpty() ? "Put cape PNGs in .minecraft/capes to see them here" : null, COLOR_GRAY, var7, var5 + 8);
        this.contentHeight = var5 + 4;
        this.scrollBy(0.0F);
    }

    /** Adds a header (with an optional hint line under it) and its tiles at content y; returns the y below. */
    private int addSection(String title, String category, String hint, int hintColor, List capes, int y)
    {
        List var7 = hint == null ? null : this.fontRenderer.listFormattedStringToWidth(hint, this.listRight - this.listLeft - SCROLLBAR_W - 16);
        this.headers.add(new Header(title, category, var7, hintColor, y));
        int var8 = y + 14 + (var7 == null ? 0 : var7.size() * 10 + 2);
        return capes.isEmpty() ? var8 : this.layoutTiles(capes, var8 + 2);
    }

    /**
     * Places one section's tiles in rows starting at content y, and returns the y below them. capes holds
     * Capes, null for No Cape and KirCapes.DEFAULT for Account Default.
     */
    private int layoutTiles(List capes, int y)
    {
        int var3 = this.listRight - this.listLeft - SCROLLBAR_W;
        int var4 = Math.max(1, (var3 - 8 + GAP) / (TILE_W + GAP));
        int var5 = (var3 - (var4 * (TILE_W + GAP) - GAP)) / 2;

        for (int var6 = 0; var6 < capes.size(); ++var6)
        {
            boolean var15 = capes.get(var6) == KirCapes.DEFAULT;
            KirCapes.Cape var7 = var15 ? KirCapes.getAccountActiveCape() : (KirCapes.Cape)capes.get(var6);
            String var16 = var15 ? KirCapes.DEFAULT : (var7 == null ? KirCapes.NONE : var7.id);
            String var8 = var15 ? "Account Default" : (var7 == null ? "No Cape" : var7.getDisplayName());
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

            this.tiles.add(new Tile(var16, var7, var8, var5 + var6 % var4 * (TILE_W + GAP), y + var6 / var4 * (TILE_H + GAP), var9, var10));
        }

        int var14 = (capes.size() + var4 - 1) / var4;
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
        else if (par1GuiButton.id == USE_ID)
        {
            this.useCustomPath();
        }
        else if (par1GuiButton.id == FOLDER_ID)
        {
            KirCapes.openOwnedDir();
        }
        else if (par1GuiButton.id == DONE_ID)
        {
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    private void select(KirCapes.Cape cape)
    {
        KirCapes.select(cape);
        this.buildTiles();
    }

    private void select(Tile tile)
    {
        if (tile.getId().equals(KirCapes.DEFAULT))
        {
            KirCapes.selectDefault();
            this.buildTiles();
            this.setStatus("Wearing your account's cape", COLOR_OK);
        }
        else
        {
            this.select(tile.cape);
            this.setStatus(tile.cape == null ? "Cape removed" : "Now wearing " + tile.cape.getDisplayName(), COLOR_OK);
        }
    }

    /** Wears the cape file named in the text field, or says what is wrong with it. */
    private void useCustomPath()
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
            this.setStatus("Type the path of a cape PNG, or press Browse...", COLOR_GRAY);
            return;
        }

        File var2 = new File(var1);

        if (!var2.isFile())
        {
            this.setStatus("File not found: " + var2.getName(), COLOR_ERROR);
            return;
        }

        KirCapes.Cape var3 = KirCapes.getCustomCape(var2);

        if (var3.loadImage() == null)
        {
            this.setStatus(var2.getName() + " is not a cape image (needs a 64x32 PNG)", COLOR_ERROR);
            return;
        }

        this.textures.remove(var3.id);
        lastPath = var1;
        this.select(var3);
        this.scroll = 0.0F;
        this.setStatus("Now wearing " + var3.getDisplayName(), COLOR_OK);
    }

    /** Opens a file dialog on Swing's thread; the result is picked up in updateScreen(). */
    private void browse()
    {
        if (this.browsing)
        {
            return;
        }

        this.browsing = true;
        this.setStatus("Choose a cape in the file dialog...", COLOR_GRAY);
        final File var1 = this.pathField.getText().trim().length() > 0 ? new File(this.pathField.getText().trim()).getParentFile() : KirCapes.getOwnedDir();
        javax.swing.SwingUtilities.invokeLater(new Runnable()
        {
            public void run()
            {
                javax.swing.JFrame var1x = null;

                try
                {
                    javax.swing.JFileChooser var2 = new javax.swing.JFileChooser(var1 != null && var1.isDirectory() ? var1 : KirCapes.getOwnedDir());
                    var2.setDialogTitle("Choose a cape");
                    var2.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Cape images (*.png)", new String[] {"png"}));
                    // An owner that is always on top keeps the dialog in front of the game window.
                    var1x = new javax.swing.JFrame();
                    var1x.setAlwaysOnTop(true);

                    if (var2.showOpenDialog(var1x) == javax.swing.JFileChooser.APPROVE_OPTION && var2.getSelectedFile() != null)
                    {
                        GuiKirmodCapes.this.chosenPath = var2.getSelectedFile().getAbsolutePath();
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

                    GuiKirmodCapes.this.browsing = false;
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
            this.useCustomPath();
        }
        else if (!this.browsing && this.status != null && this.status.startsWith("Choose a cape"))
        {
            this.status = null;
        }

        if (KirCapes.getAccountVersion() != this.accountVersion)
        {
            this.buildTiles();
        }

        // Like the texture pack screen, notice capes dropped into the folder while the screen is open.
        if (++this.ticks % 20 == 0)
        {
            long var2 = this.getOwnedDirStamp();

            if (var2 != this.ownedDirStamp)
            {
                this.ownedDirStamp = var2;
                this.forgetTextures("owned:");

                if (this.selectedId.startsWith("owned:"))
                {
                    KirCapes.reload();
                }

                this.buildTiles();
            }
        }
    }

    /** Changes whenever a file in the cape folder is added, removed or rewritten. */
    private long getOwnedDirStamp()
    {
        File[] var1 = KirCapes.getOwnedDir().listFiles();
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
                this.useCustomPath();
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

        Header var5 = this.getCategoryHeaderAt(par1, par2);

        if (var5 != null)
        {
            this.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);

            if (openCategories.remove(var5.category))
            {
                this.forgetCategoryTextures(var5.category);
            }
            else
            {
                openCategories.add(var5.category);
            }

            this.buildTiles();
            return;
        }

        Tile var4 = this.getTileAt(par1, par2);

        if (var4 != null)
        {
            this.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);

            if (!var4.getId().equals(this.selectedId))
            {
                this.select(var4);
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

    /** The category header row under the mouse, or null. */
    private Header getCategoryHeaderAt(int mouseX, int mouseY)
    {
        if (!this.isInList(mouseX, mouseY))
        {
            return null;
        }

        for (int var3 = 0; var3 < this.headers.size(); ++var3)
        {
            Header var4 = (Header)this.headers.get(var3);
            int var5 = this.listTop + var4.y - (int)this.scroll;

            if (var4.category != null && mouseY >= var5 && mouseY < var5 + 13)
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

    /** Frees the tile textures of capes whose id starts with prefix. */
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

                var2.remove();
            }
        }
    }

    /** Frees the tile textures of a gallery category's capes, once it is folded shut. */
    private void forgetCategoryTextures(String category)
    {
        List var2 = KirCapes.getGallery();

        for (int var3 = 0; var3 < var2.size(); ++var3)
        {
            KirCapes.Cape var4 = (KirCapes.Cape)var2.get(var3);
            Integer var5 = var4.category != null && var4.category.equals(category) ? (Integer)this.textures.remove(var4.id) : null;

            if (var5 != null && var5.intValue() >= 0)
            {
                this.mc.renderEngine.deleteTexture(var5.intValue());
            }
        }
    }

    /** The cape's GL texture, loaded the first time it is drawn; -1 if the image is unusable. */
    private int getTexture(KirCapes.Cape cape)
    {
        Integer var2 = (Integer)this.textures.get(cape.id);

        if (var2 == null)
        {
            BufferedImage var3 = cape.loadImage();
            var2 = Integer.valueOf(var3 != null ? this.mc.renderEngine.allocateAndSetupTexture(var3) : -1);
            this.textures.put(cape.id, var2);
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
        this.drawCenteredString(this.fontRenderer, "Capes", this.width / 2, 8, 16777215);

        if (this.status != null && var4 < this.statusUntil)
        {
            this.drawCenteredString(this.fontRenderer, this.status, this.width / 2, 19, this.statusColor);
        }
        else
        {
            this.drawCenteredString(this.fontRenderer, "Only you can see the cape you wear. Click a category to open it", this.width / 2, 19, 0x707070);
        }

        this.drawPanel(this.previewLeft, this.listTop, this.previewRight, this.listBottom);
        this.drawPanel(this.listLeft, this.listTop, this.listRight, this.listBottom);
        this.drawList(par1, par2);
        this.drawPreview();
        this.pathField.drawTextBox();

        if (this.pathField.getText().length() == 0 && !this.pathField.isFocused())
        {
            this.fontRenderer.drawString("Custom cape: path to a .png file", 13, this.pathFieldY + 5, 0x707070);
        }

        super.drawScreen(par1, par2, par3);
        Tile var7 = this.getTileAt(par1, par2);

        if (var7 != null && (var7.truncated || var7.cape != null && this.getTexture(var7.cape) < 0))
        {
            this.drawTooltip(var7.cape != null && this.getTexture(var7.cape) < 0 ? var7.getDisplayName() + " (can't be read)" : var7.getDisplayName(), par1, par2);
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
        Header var17 = this.getCategoryHeaderAt(mouseX, mouseY);

        for (int var4 = 0; var4 < this.headers.size(); ++var4)
        {
            Header var5 = (Header)this.headers.get(var4);
            int var6 = this.listTop + var5.y - (int)this.scroll;

            if (var6 + 14 + (var5.hint == null ? 0 : var5.hint.size() * 10) < this.listTop || var6 > this.listBottom)
            {
                continue;
            }

            int var7 = this.listLeft + 8;

            if (var5 == var17)
            {
                drawRect(this.listLeft + 4, var6, this.listLeft + var3 - 4, var6 + 13, 0x30FFFFFF);
            }

            this.fontRenderer.drawStringWithShadow(var5.title, var7, var6 + 2, var5.category != null && var5 != var17 ? 0xE0C060 : COLOR_GOLD);
            int var8 = var7 + this.fontRenderer.getStringWidth(var5.title) + 6;
            drawRect(var8, var6 + 6, this.listLeft + var3 - 8, var6 + 7, 0x50FFD860);

            if (var5.hint != null)
            {
                for (int var16 = 0; var16 < var5.hint.size(); ++var16)
                {
                    this.fontRenderer.drawString((String)var5.hint.get(var16), var7, var6 + 15 + var16 * 10, var5.hintColor);
                }
            }
        }

        Tile var9 = this.getTileAt(mouseX, mouseY);

        for (int var10 = 0; var10 < this.tiles.size(); ++var10)
        {
            Tile var11 = (Tile)this.tiles.get(var10);
            int var12 = this.getTileScreenY(var11);

            if (var12 + TILE_H > this.listTop && var12 < this.listBottom)
            {
                this.drawTile(var11, this.listLeft + var11.x, var12, var11 == var9);
            }
        }

        this.endClip();

        if (this.hasScrollbar())
        {
            int var13 = this.listRight - SCROLLBAR_W;
            int var14 = this.getThumbHeight();
            int var15 = this.listTop + (int)(this.scroll / (float)(this.contentHeight - (this.listBottom - this.listTop)) * (float)(this.listBottom - this.listTop - var14));
            drawRect(var13, this.listTop, this.listRight, this.listBottom, 0x60000000);
            drawRect(var13 + 1, var15 + 1, this.listRight - 1, var15 + var14 - 1, this.draggingScroll ? 0xFFE0E0E0 : 0xFF909090);
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

        int var6 = x + (TILE_W - 10 * FACE_SCALE) / 2;
        int var7 = y + 6;
        int var8 = 10 * FACE_SCALE;
        int var9 = 16 * FACE_SCALE;
        drawRect(var6 - 1, var7 - 1, var6 + var8 + 1, var7 + var9 + 1, 0xFF000000);

        if (tile.cape == null && tile.getId().equals(KirCapes.NONE))
        {
            this.drawNoCape(var6, var7, var8, var9);
        }
        else if (tile.cape == null)
        {
            // Account Default while the account wears no cape: vanilla's (usually none).
            drawRect(var6, var7, var6 + var8, var7 + var9, 0xFF26262C);
            this.drawCenteredString(this.fontRenderer, "-", var6 + var8 / 2, var7 + var9 / 2 - 4, COLOR_GRAY);
        }
        else
        {
            int var10 = this.getTexture(tile.cape);

            if (var10 >= 0)
            {
                this.drawCapeFace(var10, var6, var7, var8, var9);
            }
            else
            {
                drawRect(var6, var7, var6 + var8, var7 + var9, 0xFF301010);
                this.drawCenteredString(this.fontRenderer, "?", var6 + var8 / 2, var7 + var9 / 2 - 4, COLOR_ERROR);
            }
        }

        int var11 = var5 ? 0xFFE070 : (hovered ? 0xFFFFFF : 0xC8C8C8);

        for (int var12 = 0; var12 < tile.lines.size(); ++var12)
        {
            this.drawCenteredString(this.fontRenderer, (String)tile.lines.get(var12), x + TILE_W / 2, var7 + var9 + 5 + var12 * 9, var11);
        }
    }

    /** An empty cape with a red slash through it. */
    private void drawNoCape(int x, int y, int w, int h)
    {
        drawRect(x, y, x + w, y + h, 0xFF26262C);
        drawRect(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF1A1A1E);

        for (int var5 = 0; var5 < h - 8; var5 += 2)
        {
            int var6 = x + 4 + (w - 10) * var5 / (h - 8);
            int var7 = y + h - 6 - var5;
            drawRect(var6, var7, var6 + 3, var7 + 2, 0xFFD03030);
        }
    }

    /** Draws the outer face of a cape (texels 1,1 to 11,17 of a 64x32 cape) into a rectangle. */
    private void drawCapeFace(int texture, int x, int y, int w, int h)
    {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.renderEngine.bindTexture(texture);
        Tessellator var6 = Tessellator.instance;
        var6.startDrawingQuads();
        var6.addVertexWithUV((double)x, (double)(y + h), (double)this.zLevel, 1.0D / 64.0D, 17.0D / 32.0D);
        var6.addVertexWithUV((double)(x + w), (double)(y + h), (double)this.zLevel, 11.0D / 64.0D, 17.0D / 32.0D);
        var6.addVertexWithUV((double)(x + w), (double)y, (double)this.zLevel, 11.0D / 64.0D, 1.0D / 32.0D);
        var6.addVertexWithUV((double)x, (double)y, (double)this.zLevel, 1.0D / 64.0D, 1.0D / 32.0D);
        var6.draw();
    }

    /** The left panel: the player (or just the cape) turning, and the worn cape's name. */
    private void drawPreview()
    {
        int var1 = (this.previewLeft + this.previewRight) / 2;
        this.drawCenteredString(this.fontRenderer, "Preview", var1, this.listTop + 5, COLOR_GRAY);
        int var2 = this.listTop + 18;
        int var3 = this.listBottom - 30;
        boolean var4 = this.mc.thePlayer != null && this.mc.theWorld != null;

        if (var4)
        {
            int var5 = Math.min(40, Math.min((this.previewRight - this.previewLeft) / 2, (var3 - var2) * 10 / 19));
            this.drawPlayer(var1, var3 - 2, var5);
        }
        else if (this.selected != null && this.getTexture(this.selected) >= 0)
        {
            int var6 = Math.min(80, (var3 - var2) * 9 / 10);
            this.drawCapeModel(this.getTexture(this.selected), var1, var2 + (var3 - var2 - var6) / 2, var6);
        }
        else
        {
            this.drawCenteredString(this.fontRenderer, "No cape", var1, (var2 + var3) / 2 - 4, 0x707070);
        }

        boolean var10 = KirCapes.DEFAULT.equals(this.selectedId);
        String var7 = this.selected == null ? (var10 ? "Account Default" : "No Cape") : this.selected.getDisplayName();
        String var8 = var10 ? "Account default" : (this.selected == null ? "" : this.selected.getSource());
        String var9 = this.fontRenderer.trimStringToWidth(var7, this.previewRight - this.previewLeft - 8);

        if (!var9.equals(var7))
        {
            var9 = this.fontRenderer.trimStringToWidth(var7, this.previewRight - this.previewLeft - 8 - this.fontRenderer.getStringWidth("..")) + "..";
        }

        this.drawCenteredString(this.fontRenderer, var9, var1, this.listBottom - 24, COLOR_GOLD);
        this.drawCenteredString(this.fontRenderer, var8, var1, this.listBottom - 13, 0x808080);
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

    /** Just the cape, hanging from (x, y) and height pixels tall, turned to previewYaw. */
    private void drawCapeModel(int texture, int x, int y, int height)
    {
        if (capeModel == null)
        {
            capeModel = new ModelBiped(0.0F);
        }

        float var5 = (float)height;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glPushMatrix();
        GL11.glTranslatef((float)x, (float)y, 100.0F);
        GL11.glScalef(-var5, var5, var5);
        GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
        GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
        GL11.glRotatef(-10.0F, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef(this.previewYaw, 0.0F, 1.0F, 0.0F);
        GL11.glTranslatef(0.0F, 0.0F, 0.03125F);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.renderEngine.bindTexture(texture);
        capeModel.renderCloak(0.0625F);
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
