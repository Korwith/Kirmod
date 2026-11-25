package net.minecraft.src;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Options > Kirmod...: the settings screen, laid out like Sodium's video settings. Semi-transparent
 * panels are drawn over the world (over the dirt background at the main menu):
 *
 * - the header: a search box, then "Texture Packs...", "Skin Packs...", "Capes..." and "Music..." (the
 *   texture pack screen GuiKirmodTexturePacks, the skin picker GuiKirmodSkins, the cape picker
 *   GuiKirmodCapes and, since 1.36, the music screen GuiKirmodMusic) and an "x" that closes the screen;
 * - on the left, one section per plugin (KirPlugin: Kirmod's own first, then Kirmod Extras and any
 *   other), headed by its icon and name, with one tab per top-level category of its modules
 *   (KirModule.getCategory(), parents from KirPlugin.getParentCategory()), like Sodium Extra under Sodium;
 * - on the right, the options of the selected category, one row each: the category's own modules first,
 *   then a labelled group per subcategory (Visuals > Overworld, say). The list scrolls with the mouse
 *   wheel or its scrollbar. Off/On modules get a checkbox, isSlider() modules a slider and the others
 *   their value, which a click moves to the next value and a right-click (or shift-click) back;
 * - at the bottom, "Reset to Defaults" (asks first) and "Done".
 * - at the bottom of the sidebar, above the integrated server status, the Hotkeys panel (1.28): how many
 *   keys Kirmod and its plugins have and whether any clash, with a preview of them when there is room.
 *   Clicking it lists every one on the right (KirHotkey), grouped by plugin, each with a key cap: click
 *   it and press the new key (or a mouse button past the second), right-click it for its default.
 *
 * Since 1.30 the whole layout is a centred frame, as in Sodium 0.6: the sidebar, the list (a bold title
 * bar, then a header bar per group with an accent diamond) and a right column with the tooltip of the
 * hovered row and the footer buttons. Screens too narrow for that column fall back to the full-width list.
 * Since 1.32 the list has two columns of rows when they fit beside the right column (layOutEntries): the
 * groups flow down the left column and on down the right, and screens without room for both get one.
 * The accent colour (Sodium's mint) marks the selected tab, group headers, checkboxes, sliders and focus.
 *
 * Typing in the search box lists the modules of every category whose name matches, grouped by category.
 * Settings are applied straight away and saved (sliders once they are let go). Resting the mouse on a
 * row, tab or button shows its tooltip: for a module, its title and KirModule.getTooltip().
 */
public class GuiKirmod extends GuiScreen
{
    private static final int TEXTURE_PACKS_ID = 1;
    private static final int SKINS_ID = 2;
    private static final int CAPES_ID = 3;
    private static final int CLOSE_ID = 4;
    private static final int RESET_ID = 5;
    private static final int DONE_ID = 6;
    private static final int MUSIC_ID = 7;

    /** How long the mouse has to rest on something before its tooltip shows, in milliseconds. */
    private static final long TOOLTIP_DELAY = 500L;

    /** Widest a tooltip line gets before it wraps. */
    private static final int TOOLTIP_WIDTH = 240;

    private static final int MARGIN = 6;
    private static final int HEADER_H = 18;
    private static final int ROW_H = 18;
    private static final int TAB_H = 18;
    /** A group's header bar ("◆ Overworld"), plus the gap under it. */
    private static final int GROUP_LABEL_H = 18;
    private static final int GROUP_LABEL_BAR_H = 16;
    private static final int GROUP_GAP = 5;

    /** A plugin's heading in the sidebar: its icon at full size, the name and the version under it. */
    private static final int HEADING_H = 26;

    /**
     * Sodium's layout: the sidebar, the option list and a column on the right for the tooltip of the hovered
     * row and the footer buttons, centred on the screen. Narrower screens shrink the right column, and
     * below MIN_INFO_W drop it: the list then reaches the right edge and tooltips float under the rows.
     */
    private static final int SIDEBAR_MIN_W = 100;
    private static final int LIST_W = 230;
    private static final int LIST_MIN_W = 200;
    private static final int INFO_W = 210;
    private static final int MIN_INFO_W = 130;
    private static final int MIN_SEARCH_W = 120;

    /** The gap between the list's two columns (1.32). */
    private static final int COL_GAP = 4;

    /**
     * Splitting a group across the columns costs this much extra height when choosing where the right column
     * starts, so a split between groups wins unless it leaves the columns much more uneven.
     */
    private static final int SPLIT_PENALTY = 2 * ROW_H;

    /** Space between two plugins' sections of the sidebar. */
    private static final int SECTION_GAP = 4;
    private static final int SLIDER_W = 90;

    /** The integrated server status panel at the bottom of the sidebar: a title and three lines. */
    private static final int STATUS_H = 46;

    /** Stands for the status panel as the thing the mouse rests on (for its tooltip). */
    private static final Object STATUS_PANEL = new Object();

    /** The Hotkeys panel above it: a title line, a summary line, then as many preview lines as fit. */
    private static final int HOTKEYS_H = 28;
    private static final int HOTKEYS_LINE_H = 10;
    private static final int HOTKEYS_MAX_PREVIEW = 8;
    private static final Object HOTKEYS_PANEL = new Object();

    /** Key caps: a clash, and the cap waiting for a key. */
    private static final int COLOR_CONFLICT = 0xFFFF5555;

    /** Sodium's colours: panels, hovered panels, the checkbox fill and the selected tab. */
    private static final int COLOR_PANEL = 0x90000000;
    private static final int COLOR_PANEL_HOVER = 0xE0000000;
    private static final int COLOR_SELECTED = 0xD0303030;
    private static final int COLOR_ACCENT = 0xFF94E4D3;
    private static final int COLOR_HEADER = 0xB0000000;
    private static final int COLOR_TAB_TEXT = 0xA8B4B1;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xA0A0A0;

    private final GuiScreen parentScreen;

    /** The selected tab: a top-level category of a plugin. */
    private KirPlugin selectedPlugin = KirPlugin.KIRMOD;
    private String selectedCategory;

    /** A subcategory to scroll to once the screen is laid out, from the constructor. */
    private String scrollToCategory;

    private float scroll;
    private String searchText = "";

    /** The sidebar (plugin headings and tabs, as SideItems), the search box and the rows on the right (rebuilt by initGui). */
    private List tabs = new ArrayList();
    private int sidebarBottom;

    /** How far the sidebar is scrolled, when its tabs don't fit (small windows, many plugins). */
    private float sideScroll;
    private GuiTextField searchField;
    private int searchX;
    private int searchY;
    private int searchRight;
    private final List entries = new ArrayList();
    private int contentHeight;

    /** Layout, worked out in initGui. */
    /** The centred frame everything is drawn in (left is the sidebar's left edge). */
    private int left;
    private int right;

    /** The right column (tooltips, footer buttons, the hint), or -1 when the screen is too narrow for it. */
    private int infoLeft = -1;
    private int footerTop;
    private int sidebarRight;
    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;

    /** How many columns the list has: 2 when there is room (1.32), else 1. */
    private int listCols = 1;

    /** Top of the status panel, or -1 when the window is too short for it. */
    private int statusTop = -1;

    /** The Hotkeys panel: its top (-1 when the window is too short), bottom and number of preview lines. */
    private int hotkeysTop = -1;
    private int hotkeysBottom;
    private int hotkeysPreview;

    /** Whether the right side shows the hotkeys instead of a tab, and the hotkey waiting for a new key. */
    private boolean hotkeysSelected;
    private KirHotkey listening;

    /** The slider being dragged, or null; whether the scrollbar is. */
    private KirModule draggedSlider;
    private boolean draggingScrollbar;
    private int scrollbarGrab;

    /** The game's HUD while this screen hides it (see HudHider), else null. */
    private GuiIngame hiddenHud;

    /** What the mouse rests on (a module, a tab's category name or a button) and since when. */
    private Object hovered;
    private long hoverStart;

    public GuiKirmod(GuiScreen par1GuiScreen)
    {
        this(par1GuiScreen, (String)null);
    }

    /** Opens the screen on one of Kirmod's own categories, scrolled to it if it is a subcategory. */
    public GuiKirmod(GuiScreen par1GuiScreen, String category)
    {
        this(par1GuiScreen, KirPlugin.KIRMOD, category);
    }

    /** Opens the screen on a plugin's category tab, scrolled to it if it is a subcategory. */
    public GuiKirmod(GuiScreen par1GuiScreen, KirPlugin plugin, String category)
    {
        this.parentScreen = par1GuiScreen;
        this.selectedPlugin = plugin;

        if (category != null)
        {
            String var3 = category;

            while (plugin.getParentCategory(var3) != null)
            {
                var3 = plugin.getParentCategory(var3);
            }

            this.selectedCategory = var3;
            this.scrollToCategory = var3.equals(category) ? null : category;
        }
    }

    /**
     * Adds the buttons (and other controls) to the screen in question.
     */
    public void initGui()
    {
        Keyboard.enableRepeatEvents(true);

        if (this.mc.theWorld != null && this.mc.ingameGUI != null && !(this.mc.ingameGUI instanceof HudHider))
        {
            // Like Sodium, the panels show the world without the hotbar and crosshair showing through.
            this.hiddenHud = this.mc.ingameGUI;
            this.mc.ingameGUI = new HudHider(this.mc, this.hiddenHud);
        }

        this.tabs = buildSidebar();

        if (this.selectedCategory == null || this.findTab(this.selectedPlugin, this.selectedCategory) == null)
        {
            SideItem var0 = this.firstTab((KirPlugin)null);
            this.selectedPlugin = var0 == null ? KirPlugin.KIRMOD : var0.plugin;
            this.selectedCategory = var0 == null ? null : var0.category;
        }

        // Widths: the sidebar fits its longest tab or heading, the header its buttons and some search box.
        int var4 = SIDEBAR_MIN_W;
        Iterator var5 = this.tabs.iterator();

        while (var5.hasNext())
        {
            var4 = Math.max(var4, ((SideItem)var5.next()).getWidth(this.fontRenderer));
        }

        var4 = Math.min(var4, Math.max(70, this.width / 4));
        int var10 = HEADER_H + 6 + this.getFlatButtonWidth("Music...") + 2 + MIN_SEARCH_W;

        if (!KirEdition.LITE)
        {
            var10 += this.getFlatButtonWidth("Capes...") + this.getFlatButtonWidth("Skin Packs...") + this.getFlatButtonWidth("Texture Packs...");
        }

        int var11 = this.width - 2 * MARGIN;

        // Two columns of options when they fit next to the right column (1.32), else one, as before.
        int[] var17 = layOutWidths(var4, 2, var11, var10);
        this.listCols = 2;

        if (var17 == null)
        {
            var17 = layOutWidths(var4, 1, var11, var10);
            this.listCols = 1;
        }

        if (var17 == null)
        {
            // Too narrow for the right column: the list takes the whole width, the footer goes under it.
            var17 = new int[] {var11, var11 - var4 - 4, 0};
            this.listCols = var17[1] >= 2 * LIST_MIN_W + COL_GAP ? 2 : 1;
        }

        int var13 = var17[0];
        int var12 = var17[1];
        int var14 = var17[2];

        this.left = (this.width - var13) / 2;
        this.right = this.left + var13;

        // Header: the buttons from the right, the search box takes the rest.
        int var1 = this.right;
        var1 = this.addFlatButton(CLOSE_ID, var1, MARGIN, "x", HEADER_H);
        var1 = this.addFlatButton(MUSIC_ID, var1 - 2, MARGIN, "Music...", 0);

        // Kirmod Lite (KirEdition) has no texture pack, skin or cape screens.
        if (!KirEdition.LITE)
        {
            var1 = this.addFlatButton(CAPES_ID, var1 - 2, MARGIN, "Capes...", 0);
            var1 = this.addFlatButton(SKINS_ID, var1 - 2, MARGIN, "Skin Packs...", 0);
            var1 = this.addFlatButton(TEXTURE_PACKS_ID, var1 - 2, MARGIN, "Texture Packs...", 0);
        }

        this.searchX = this.left + 6;
        this.searchY = MARGIN + (HEADER_H - 8) / 2;
        this.searchRight = var1 - 2;
        this.searchField = new GuiTextField(this.fontRenderer, this.searchX, this.searchY, this.searchRight - 6 - this.searchX, 8);
        this.searchField.setEnableBackgroundDrawing(false);
        this.searchField.setMaxStringLength(48);
        this.searchField.setText(this.searchText);

        this.sidebarRight = this.left + var4;
        this.listLeft = this.sidebarRight + 4;
        this.listRight = this.listLeft + var12;
        this.listTop = MARGIN + HEADER_H + 4;
        int var2 = this.height - MARGIN - HEADER_H;

        if (var14 > 0)
        {
            // Like Sodium's Apply and Done: stacked at the bottom of the right column.
            this.infoLeft = this.listRight + 4;
            int var15 = Math.max(70, this.getFlatButtonWidth("Reset to Defaults"));
            this.addFlatButton(DONE_ID, this.right, var2, "Done", var15);
            this.footerTop = var2 - HEADER_H - 2;
            this.addFlatButton(RESET_ID, this.right, this.footerTop, "Reset to Defaults", var15);
            this.listBottom = this.height - MARGIN;
        }
        else
        {
            // Footer: Done and Reset to Defaults from the right.
            this.infoLeft = -1;
            int var3 = this.addFlatButton(DONE_ID, this.right, var2, "Done", 70);
            this.addFlatButton(RESET_ID, var3 - 2, var2, "Reset to Defaults", 0);
            this.footerTop = var2;
            this.listBottom = var2 - 4;
        }

        this.statusTop = this.listBottom - STATUS_H;
        int var6 = this.listTop;

        for (int var7 = 0; var7 < this.tabs.size(); ++var7)
        {
            SideItem var8 = (SideItem)this.tabs.get(var7);

            if (var8.category == null && var7 > 0)
            {
                var6 += SECTION_GAP;
            }

            var8.y = var6;
            var6 += var8.getHeight();
        }

        this.sidebarBottom = var6;

        // The sidebar scrolls above the panel; it only goes when that would leave room for very few tabs.
        if (!mod_Kirmod.INTEGRATED_SERVER.isEnabled() || this.statusTop - 4 < this.listTop + 4 * TAB_H)
        {
            this.statusTop = -1;
        }

        this.layOutHotkeys();
        this.rebuildEntries();

        if (this.scrollToCategory != null)
        {
            this.scroll = (float)this.getGroupY(this.scrollToCategory);
            this.scrollToCategory = null;
        }

        this.scrollBy(0.0F);

        // Keep the selected tab in view.
        SideItem var9 = this.findTab(this.selectedPlugin, this.selectedCategory);

        if (var9 != null && var9.y + TAB_H - this.sideScroll > this.getSideBottom())
        {
            this.sideScroll = (float)(var9.y + TAB_H - this.getSideBottom());
        }

        this.scrollSidebarBy(0.0F);
    }

    /**
     * The frame's width, the list's width and the right column's width for a list of cols columns next to a
     * sidebar sideW wide, in at most maxW (but at least minW, the header's need); null when the right column
     * would be narrower than MIN_INFO_W even with the list narrowed to its least.
     */
    private static int[] layOutWidths(int sideW, int cols, int maxW, int minW)
    {
        int var4 = cols * LIST_W + (cols - 1) * COL_GAP;
        int var5 = cols * LIST_MIN_W + (cols - 1) * COL_GAP;
        int var6 = Math.min(maxW, Math.max(sideW + 4 + var4 + 4 + INFO_W, minW));
        int var7 = var6 - sideW - 8 - var4;

        if (var7 < MIN_INFO_W)
        {
            // Narrow the list a little before giving up the right column.
            int var8 = Math.min(MIN_INFO_W - var7, var4 - var5);
            var4 -= var8;
            var7 += var8;
        }

        return var7 < MIN_INFO_W ? null : new int[] {var6, var4, var7};
    }

    /**
     * Places the Hotkeys panel above the status panel (or at the bottom). It shows preview lines only where
     * the tabs would leave the space empty anyway, and goes when the tabs would be left too little room.
     */
    private void layOutHotkeys()
    {
        this.hotkeysTop = -1;
        this.hotkeysPreview = 0;
        int var1 = KirHotkey.getShown().size();

        if (var1 == 0)
        {
            return;
        }

        int var2 = this.statusTop >= 0 ? this.statusTop - 4 : this.listBottom;
        int var3 = var2 - HOTKEYS_H - 4 - this.sidebarBottom;
        this.hotkeysPreview = Math.max(0, Math.min(Math.min(var1, HOTKEYS_MAX_PREVIEW), var3 / HOTKEYS_LINE_H));

        // Without room for every key, the last line says how many more there are; one line for that alone is no use.
        if (this.hotkeysPreview == 1 && var1 > 1)
        {
            this.hotkeysPreview = 0;
        }

        this.hotkeysBottom = var2;
        this.hotkeysTop = var2 - HOTKEYS_H - this.hotkeysPreview * HOTKEYS_LINE_H;

        if (this.hotkeysTop - 4 < this.listTop + 3 * TAB_H)
        {
            this.hotkeysTop = -1;
            this.hotkeysPreview = 0;
        }
    }

    /** Bottom of the sidebar's visible part: the Hotkeys panel, the status panel, or the bottom of the list. */
    private int getSideBottom()
    {
        return this.hotkeysTop >= 0 ? this.hotkeysTop - 4 : (this.statusTop >= 0 ? this.statusTop - 4 : this.listBottom);
    }

    private int getSideMaxScroll()
    {
        return Math.max(0, this.sidebarBottom - this.getSideBottom());
    }

    private void scrollSidebarBy(float amount)
    {
        this.sideScroll = Math.max(0.0F, Math.min((float)this.getSideMaxScroll(), this.sideScroll + amount));
    }

    /** Top of a sidebar item on screen. */
    private int getSideY(SideItem item)
    {
        return item.y - (int)this.sideScroll;
    }

    /** Each plugin with options: its heading, then a tab per top-level category. */
    private static List buildSidebar()
    {
        ArrayList var0 = new ArrayList();
        Iterator var1 = mod_Kirmod.getPlugins().iterator();

        while (var1.hasNext())
        {
            KirPlugin var2 = (KirPlugin)var1.next();
            List var3 = mod_Kirmod.getCategories(var2, (String)null);

            if (!var3.isEmpty())
            {
                var0.add(new SideItem(var2, (String)null));
                Iterator var4 = var3.iterator();

                while (var4.hasNext())
                {
                    var0.add(new SideItem(var2, (String)var4.next()));
                }
            }
        }

        return var0;
    }

    /** The tab of a plugin's category, or null. */
    private SideItem findTab(KirPlugin plugin, String category)
    {
        Iterator var3 = this.tabs.iterator();

        while (var3.hasNext())
        {
            SideItem var4 = (SideItem)var3.next();

            if (var4.plugin == plugin && var4.category != null && var4.category.equals(category))
            {
                return var4;
            }
        }

        return null;
    }

    /** The first tab of a plugin (of any plugin if null), or null. */
    private SideItem firstTab(KirPlugin plugin)
    {
        Iterator var2 = this.tabs.iterator();

        while (var2.hasNext())
        {
            SideItem var3 = (SideItem)var2.next();

            if (var3.category != null && (plugin == null || var3.plugin == plugin))
            {
                return var3;
            }
        }

        return null;
    }

    /** The width of a flat button that fits its text. */
    private int getFlatButtonWidth(String text)
    {
        return this.fontRenderer.getStringWidth(text) + 14;
    }

    /** Adds a flat button whose right edge is at right; returns its left edge. Width 0 fits the text. */
    private int addFlatButton(int id, int right, int y, String text, int width)
    {
        int var6 = width > 0 ? width : this.getFlatButtonWidth(text);
        this.controlList.add(new FlatButton(id, right - var6, y, var6, HEADER_H, text));
        return right - var6;
    }

    /** Fills entries with the rows of the selected tab, or of the search results. */
    private void rebuildEntries()
    {
        this.entries.clear();
        String var1 = KirUtil.normalize(this.searchText);

        if (var1.length() > 0)
        {
            Iterator var2 = this.tabs.iterator();

            while (var2.hasNext())
            {
                SideItem var4 = (SideItem)var2.next();

                if (var4.category != null)
                {
                    // Other plugins' groups say whose they are: "Kirmod Extras > Gameplay".
                    this.addSearchResults(var4.plugin, var4.category, var1, var4.plugin == KirPlugin.KIRMOD ? null : var4.plugin.getName());
                }
            }

            this.addHotkeySearchResults(var1);
        }
        else if (this.hotkeysSelected)
        {
            this.entries.add(Entry.title((KirPlugin)null, "Hotkeys"));
            this.addHotkeyEditor();
        }
        else if (this.selectedCategory != null)
        {
            // Like Sodium: the plugin's title bar, then a header bar per group, the tab's own one first.
            this.entries.add(Entry.title(this.selectedPlugin, this.selectedPlugin.getName()));
            this.addGroup(this.selectedPlugin, this.selectedCategory, this.selectedCategory);
        }

        this.layOutEntries();
    }

    /**
     * Gives each entry its column and its top within the list, and sets contentHeight. The title bar spans
     * both columns; under it the entries flow down the left column, then on down the right one, like a
     * newspaper, so they read in order. The right column starts where the two come out most even, between
     * groups if that is nearly as even; a group split across them repeats its header at the top of the right.
     */
    private void layOutEntries()
    {
        int var1 = 0;
        int var2 = 0;

        if (!this.entries.isEmpty() && ((Entry)this.entries.get(0)).title)
        {
            Entry var3 = (Entry)this.entries.get(0);
            var3.col = -1;
            var3.y = 0;
            var1 = var3.height;
            var2 = 1;

            if (this.entries.size() > 1 && isGap((Entry)this.entries.get(1)))
            {
                // The gap under the title bar is above both columns.
                Entry var21 = (Entry)this.entries.get(1);
                var21.col = -1;
                var21.y = var1;
                var1 += var21.height;
                var2 = 2;
            }
        }

        int var4 = this.entries.size();
        int var5 = var4;

        if (this.listCols == 2)
        {
            int var6 = Integer.MAX_VALUE;
            int var7 = 0;

            for (int var8 = var2; var8 < var4; ++var8)
            {
                var7 += ((Entry)this.entries.get(var8)).height;
            }

            int var9 = 0;

            // var10: where the right column would start; var9: the left column's height above it.
            for (int var10 = var2; var10 <= var4; ++var10)
            {
                if (var10 > var2)
                {
                    var9 += ((Entry)this.entries.get(var10 - 1)).height;
                }

                if (var10 == var2 && var10 < var4)
                {
                    continue;
                }

                int var11;

                if (var10 == var4)
                {
                    var11 = var9;
                }
                else
                {
                    Entry var12 = (Entry)this.entries.get(var10 - 1);
                    Entry var13 = (Entry)this.entries.get(var10);

                    // Never leave a header at the bottom of the left column.
                    if (var12.label != null && !var12.title)
                    {
                        continue;
                    }

                    int var14 = var9 - (isGap(var12) ? var12.height : 0);
                    int var15 = var7 - var9 - (isGap(var13) ? var13.height : 0);
                    var11 = Math.max(var14, var15);

                    if (isRow(var13))
                    {
                        // Within a group: its header shows again over the right column.
                        if (this.findGroupLabel(var10) == null)
                        {
                            continue;
                        }

                        var11 = Math.max(var14, var15 + GROUP_LABEL_H) + SPLIT_PENALTY;
                    }
                }

                if (var11 < var6)
                {
                    var6 = var11;
                    var5 = var10;
                }
            }

            if (var5 < var4 && isRow((Entry)this.entries.get(var5)))
            {
                this.entries.add(var5, new Entry((KirModule)null, this.findGroupLabel(var5), GROUP_LABEL_H));
                ++var4;
            }
        }

        int var16 = var1;
        int var17 = var1;

        for (int var18 = var2; var18 < var4; ++var18)
        {
            Entry var19 = (Entry)this.entries.get(var18);
            boolean var20 = var18 >= var5;

            if (isGap(var19) && (var18 == var5 || var18 == var5 - 1))
            {
                // No gap at the top or bottom of a column.
                var19.col = var20 ? 1 : 0;
                var19.y = var20 ? var17 : var16;
                var19.hidden = true;
                continue;
            }

            var19.col = var20 ? 1 : 0;

            if (var20)
            {
                var19.y = var17;
                var17 += var19.height;
            }
            else
            {
                var19.y = var16;
                var16 += var19.height;
            }
        }

        this.contentHeight = Math.max(var16, var17);
    }

    /** The label of the group the entry at index belongs to, or null. */
    private String findGroupLabel(int index)
    {
        for (int var2 = index - 1; var2 >= 0; --var2)
        {
            Entry var3 = (Entry)this.entries.get(var2);

            if (var3.label != null && !var3.title)
            {
                return var3.label;
            }

            if (!isRow(var3))
            {
                return null;
            }
        }

        return null;
    }

    /** Whether an entry is a row (a module, a hotkey or Reset All Hotkeys), not a header, title or gap. */
    private static boolean isRow(Entry entry)
    {
        return entry.module != null || entry.hotkey != null || entry.resetKeys;
    }

    private static boolean isGap(Entry entry)
    {
        return !isRow(entry) && entry.label == null;
    }

    /** Left edge of an entry's column on screen. */
    private int getEntryLeft(Entry entry)
    {
        return entry.col == 1 ? this.listLeft + this.getColWidth() + COL_GAP : this.listLeft;
    }

    /** Right edge of an entry's column on screen. */
    private int getEntryRight(Entry entry)
    {
        return entry.col == 0 && this.listCols == 2 ? this.listLeft + this.getColWidth() : this.getRowRight();
    }

    /** Width of one column of rows. */
    private int getColWidth()
    {
        int var1 = this.getRowRight() - this.listLeft;
        return this.listCols == 2 ? (var1 - COL_GAP) / 2 : var1;
    }

    /** The entry of a module's row, or null. */
    private Entry findEntry(KirModule module)
    {
        Iterator var2 = this.entries.iterator();

        while (var2.hasNext())
        {
            Entry var3 = (Entry)var2.next();

            if (var3.module == module)
            {
                return var3;
            }
        }

        return null;
    }

    /** Adds a category's modules (labelled with label, if not null), then its subcategories' groups. */
    private void addGroup(KirPlugin plugin, String category, String label)
    {
        List var4 = mod_Kirmod.getEnabledModules(plugin, category);
        this.addRows(var4, category, label);
        Iterator var5 = mod_Kirmod.getCategories(plugin, category).iterator();

        while (var5.hasNext())
        {
            String var6 = (String)var5.next();
            this.addGroup(plugin, var6, var6);
        }
    }

    private void addSearchResults(KirPlugin plugin, String category, String query, String path)
    {
        String var4 = path == null ? category : path + " > " + category;
        ArrayList var5 = new ArrayList();
        Iterator var6 = mod_Kirmod.getEnabledModules(plugin, category).iterator();

        while (var6.hasNext())
        {
            KirModule var7 = (KirModule)var6.next();

            if (KirUtil.normalize(var7.getTitle()).indexOf(query) >= 0 || KirUtil.normalize(var7.getButtonTitle()).indexOf(query) >= 0 || KirUtil.normalize(var7.getKey()).indexOf(query) >= 0 || KirUtil.normalize(var4).indexOf(query) >= 0)
            {
                var5.add(var7);
            }
        }

        this.addRows(var5, category, var4);
        Iterator var8 = mod_Kirmod.getCategories(plugin, category).iterator();

        while (var8.hasNext())
        {
            this.addSearchResults(plugin, (String)var8.next(), query, var4);
        }
    }

    private void addRows(List modules, String category, String label)
    {
        if (modules.isEmpty())
        {
            return;
        }

        if (!this.entries.isEmpty())
        {
            this.entries.add(new Entry(null, null, GROUP_GAP));
        }

        if (label != null)
        {
            this.entries.add(new Entry(null, label, GROUP_LABEL_H));
        }

        Iterator var4 = modules.iterator();

        while (var4.hasNext())
        {
            this.entries.add(new Entry((KirModule)var4.next(), category, ROW_H));
        }
    }

    /** Every hotkey, under a label per plugin (and group), then the Reset All Hotkeys row. */
    private void addHotkeyEditor()
    {
        this.addHotkeyRows(KirHotkey.getShown(), (String)null);

        if (!KirHotkey.getShown().isEmpty())
        {
            this.entries.add(new Entry(null, null, GROUP_GAP));
            this.entries.add(Entry.resetKeys());
        }
    }

    /** The hotkeys whose name, plugin or key matches the search, under "Hotkeys". */
    private void addHotkeySearchResults(String query)
    {
        ArrayList var2 = new ArrayList();
        Iterator var3 = KirHotkey.getShown().iterator();

        while (var3.hasNext())
        {
            KirHotkey var4 = (KirHotkey)var3.next();

            if (KirUtil.normalize(var4.getTitle()).indexOf(query) >= 0 || KirUtil.normalize("hotkeys " + var4.getPlugin().getName()).indexOf(query) >= 0)
            {
                var2.add(var4);
            }
        }

        this.addHotkeyRows(var2, "Hotkeys");
    }

    /** Hotkey rows with a label wherever the plugin or group changes (prefix, if not null, goes before it). */
    private void addHotkeyRows(List hotkeys, String prefix)
    {
        String var3 = null;
        Iterator var4 = hotkeys.iterator();

        while (var4.hasNext())
        {
            KirHotkey var5 = (KirHotkey)var4.next();
            String var6 = var5.getPlugin().getName() + (var5.group != null ? " > " + var5.group : "");

            if (prefix != null)
            {
                var6 = prefix + " > " + var6;
            }

            if (!var6.equals(var3))
            {
                if (!this.entries.isEmpty())
                {
                    this.entries.add(new Entry(null, null, GROUP_GAP));
                }

                this.entries.add(new Entry(null, var6, GROUP_LABEL_H));
                var3 = var6;
            }

            this.entries.add(Entry.hotkey(var5));
        }
    }

    /** The list entry at x, y, or null. */
    private Entry getEntryAt(int x, int y)
    {
        if (x < this.listLeft || x >= this.getRowRight() || y < this.listTop || y >= this.listBottom)
        {
            return null;
        }

        for (int var4 = 0; var4 < this.entries.size(); ++var4)
        {
            Entry var5 = (Entry)this.entries.get(var4);
            int var3 = this.getEntryY(var5);

            if (!var5.hidden && y >= var3 && y < var3 + var5.height && x >= this.getEntryLeft(var5) && x < this.getEntryRight(var5))
            {
                return var5;
            }
        }

        return null;
    }

    /** Top of an entry on screen. */
    private int getEntryY(Entry entry)
    {
        return this.listTop - (int)this.scroll + entry.y;
    }

    /** Shows the hotkeys on the right, like a tab. */
    void selectHotkeys()
    {
        this.hotkeysSelected = true;
        this.listening = null;
        this.searchText = "";

        if (this.searchField != null)
        {
            this.searchField.setText("");
            this.rebuildEntries();
        }

        this.scroll = 0.0F;
    }

    /** Gives the hotkey waiting for one its new key (0: none) and stops waiting. */
    private void bindListening(int code)
    {
        KirHotkey var2 = this.listening;

        if (var2 != null && var2.modifier != 0 && code == var2.modifier)
        {
            // Pressing F3 for an F3 + key combo: keep waiting for the key itself.
            return;
        }

        this.listening = null;

        if (var2 != null)
        {
            var2.setKeyCode(this.mc, code);
            this.playClick();
        }
    }

    /** Top of a subcategory's group within the list (its gap), or 0. */
    private int getGroupY(String category)
    {
        for (int var3 = 0; var3 < this.entries.size(); ++var3)
        {
            Entry var4 = (Entry)this.entries.get(var3);

            if (var4.module == null && !var4.title && category.equals(var4.label))
            {
                // Its gap, if it has one, in the same column.
                Entry var5 = var3 > 0 ? (Entry)this.entries.get(var3 - 1) : null;
                return var5 != null && isGap(var5) && !var5.hidden && var5.col == var4.col ? var5.y : var4.y;
            }
        }

        return 0;
    }

    private int getMaxScroll()
    {
        return Math.max(0, this.contentHeight - (this.listBottom - this.listTop));
    }

    private void scrollBy(float amount)
    {
        this.scroll = Math.max(0.0F, Math.min((float)this.getMaxScroll(), this.scroll + amount));
    }

    private boolean hasScrollbar()
    {
        return this.getMaxScroll() > 0;
    }

    /** Right edge of the rows, left of the scrollbar when there is one. */
    private int getRowRight()
    {
        return this.hasScrollbar() ? this.listRight - 6 : this.listRight;
    }

    /** The module row at x, y, or null. */
    private KirModule getModuleAt(int x, int y)
    {
        Entry var3 = this.getEntryAt(x, y);
        return var3 == null ? null : var3.module;
    }

    /** Top of a module's row on screen (the bottom of the list if it has none). */
    private int getRowY(KirModule module)
    {
        Entry var2 = this.findEntry(module);
        return var2 == null ? this.listTop - (int)this.scroll + this.contentHeight : this.getEntryY(var2);
    }

    /** Right edge of a module's row on screen. */
    private int getRowRight(KirModule module)
    {
        Entry var2 = this.findEntry(module);
        return var2 == null ? this.getRowRight() : this.getEntryRight(var2);
    }

    /** The tab or plugin heading at x, y, or null. */
    private SideItem getTabAt(int x, int y)
    {
        if (x >= this.left && x < this.sidebarRight && y >= this.listTop && y < this.getSideBottom())
        {
            for (int var3 = 0; var3 < this.tabs.size(); ++var3)
            {
                SideItem var4 = (SideItem)this.tabs.get(var3);

                if (y >= this.getSideY(var4) && y < this.getSideY(var4) + var4.getHeight())
                {
                    return var4;
                }
            }
        }

        return null;
    }

    private boolean isOverHotkeys(int x, int y)
    {
        return this.hotkeysTop >= 0 && x >= this.left && x < this.sidebarRight && y >= this.hotkeysTop && y < this.hotkeysBottom;
    }

    private boolean isOverStatus(int x, int y)
    {
        return this.statusTop >= 0 && x >= this.left && x < this.sidebarRight && y >= this.statusTop && y < this.listBottom;
    }

    private static boolean isCheckbox(KirModule module)
    {
        String[] var1 = module.getValueNames();
        return var1.length == 2 && ("Off".equals(var1[0]) && "On".equals(var1[1]) || "On".equals(var1[0]) && "Off".equals(var1[1]));
    }

    /** Left edge of a slider's track in a row ending at rowRight. */
    private int getSliderLeft(int rowRight)
    {
        return rowRight - 6 - this.getSliderWidth();
    }

    private int getSliderWidth()
    {
        return Math.min(SLIDER_W, this.getColWidth() / 3);
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
            case TEXTURE_PACKS_ID:
                // Picking a pack there reloads the textures (KirTexturePacks.select()).
                if (!KirEdition.LITE)
                {
                    this.mc.displayGuiScreen(new GuiKirmodTexturePacks(this));
                }

                break;

            case SKINS_ID:
                if (!KirEdition.LITE)
                {
                    this.mc.displayGuiScreen(new GuiKirmodSkins(this));
                }

                break;

            case CAPES_ID:
                if (!KirEdition.LITE)
                {
                    this.mc.displayGuiScreen(new GuiKirmodCapes(this));
                }

                break;

            case MUSIC_ID:
                this.mc.displayGuiScreen(new GuiKirmodMusic(this));
                break;

            case RESET_ID:
                this.mc.displayGuiScreen(new GuiYesNo(this, "Reset every Kirmod setting to its default?", KirEdition.LITE ? "" : "Your cape and skin are kept.", RESET_ID));
                break;

            case CLOSE_ID:
            case DONE_ID:
                this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    /** Answer of the Reset to Defaults confirmation; showing this screen again rebuilds it. */
    public void confirmClicked(boolean par1, int par2)
    {
        if (par1 && par2 == RESET_ID)
        {
            mod_Kirmod.resetAll();
        }

        this.mc.displayGuiScreen(this);
    }

    protected void mouseClicked(int par1, int par2, int par3)
    {
        if (this.listening != null)
        {
            // Waiting for a key: the third mouse button and up are bound like keys (as in Controls); the
            // left or right button stops waiting, and then does what it would anyway.
            if (par3 >= 2)
            {
                this.bindListening(par3 - 100);
                return;
            }

            Entry var12 = this.getEntryAt(par1, par2);
            boolean var13 = var12 != null && var12.hotkey == this.listening;
            this.listening = null;

            if (var13)
            {
                return;
            }
        }

        super.mouseClicked(par1, par2, par3);
        this.searchField.mouseClicked(par1, par2, par3);
        SideItem var4 = this.getTabAt(par1, par2);

        if (par3 == 0 && this.isOverHotkeys(par1, par2))
        {
            this.playClick();
            this.selectHotkeys();
            return;
        }

        Entry var14 = this.getEntryAt(par1, par2);

        if (var14 != null && var14.hotkey != null && (par3 == 0 || par3 == 1))
        {
            this.playClick();

            if (par3 == 0)
            {
                this.listening = var14.hotkey;
            }
            else
            {
                var14.hotkey.setKeyCode(this.mc, var14.hotkey.defaultCode);
            }

            return;
        }

        if (var14 != null && var14.resetKeys && par3 == 0)
        {
            this.playClick();
            KirHotkey.resetAll(this.mc);
            return;
        }

        if (var4 != null && par3 == 0)
        {
            // A heading opens its plugin's first tab, like Sodium.
            SideItem var11 = var4.category != null ? var4 : this.firstTab(var4.plugin);
            this.playClick();
            this.hotkeysSelected = false;
            this.selectedPlugin = var11.plugin;
            this.selectedCategory = var11.category;
            this.searchText = "";
            this.searchField.setText("");
            this.scroll = 0.0F;
            this.rebuildEntries();
            return;
        }

        if (par3 == 0 && this.isOverStatus(par1, par2))
        {
            // Opens the tab with the Integrated Server option, scrolled to it.
            KirModule var8 = mod_Kirmod.INTEGRATED_SERVER;
            String var9 = var8.getCategory();

            while (KirPlugin.KIRMOD.getParentCategory(var9) != null)
            {
                var9 = KirPlugin.KIRMOD.getParentCategory(var9);
            }

            this.playClick();
            this.hotkeysSelected = false;
            this.selectedPlugin = KirPlugin.KIRMOD;
            this.selectedCategory = var9;
            this.searchText = "";
            this.searchField.setText("");
            this.scroll = 0.0F;
            this.rebuildEntries();
            int var10 = this.getRowY(var8);

            if (var10 >= 0)
            {
                this.scrollBy((float)(var10 - this.listTop - (this.listBottom - this.listTop - ROW_H) / 2));
            }

            return;
        }

        if (par3 == 0 && this.hasScrollbar() && par1 >= this.listRight - 4 && par1 < this.listRight && par2 >= this.listTop && par2 < this.listBottom)
        {
            int var5 = this.getThumbTop();
            int var6 = this.getThumbHeight();
            this.draggingScrollbar = true;
            // Clicking the track off the thumb jumps there with the thumb centred on the mouse.
            this.scrollbarGrab = par2 >= var5 && par2 < var5 + var6 ? par2 - var5 : var6 / 2;
            this.dragScrollbar(par2);
            return;
        }

        KirModule var7 = this.getModuleAt(par1, par2);

        if (var7 == null || par3 != 0 && par3 != 1)
        {
            return;
        }

        if (var7.getLockedMessage() != null)
        {
            // The tooltip says why; the chat that would say so is hidden behind this screen.
            this.hovered = var7;
            this.hoverStart = 0L;
            return;
        }

        if (var7.isSlider())
        {
            int var8 = this.getSliderLeft(this.getRowRight(var7));

            if (par3 == 0 && par1 >= var8 - 4 && par1 < var8 + this.getSliderWidth() + 4)
            {
                this.draggedSlider = var7;
                this.slideTo(var7, par1);
            }
            else
            {
                // Off the track, a click steps one value, like a cycling option.
                this.playClick();
                var7.cycleValue(par3 == 1 || isShiftKeyDown() ? -1 : 1);
            }
        }
        else
        {
            this.playClick();
            var7.cycleValue(par3 == 1 || isShiftKeyDown() ? -1 : 1);
        }
    }

    protected void mouseMovedOrUp(int par1, int par2, int par3)
    {
        super.mouseMovedOrUp(par1, par2, par3);

        if (par3 == 0)
        {
            if (this.draggedSlider != null)
            {
                this.draggedSlider = null;
                mod_Kirmod.saveConfig();
            }

            this.draggingScrollbar = false;
        }
    }

    public void handleMouseInput()
    {
        super.handleMouseInput();
        int var1 = Mouse.getEventDWheel();

        if (var1 != 0)
        {
            int var2 = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int var3 = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

            if (var2 >= this.left && var2 < this.sidebarRight && var3 >= this.listTop && var3 < this.getSideBottom())
            {
                this.scrollSidebarBy(var1 > 0 ? (float)(-TAB_H * 2) : (float)(TAB_H * 2));
            }
            else
            {
                this.scrollBy(var1 > 0 ? (float)(-ROW_H * 2) : (float)(ROW_H * 2));
            }
        }
    }

    protected void keyTyped(char par1, int par2)
    {
        if (this.listening != null)
        {
            // Esc stops waiting, Backspace leaves the key unbound, any other key is the new one.
            if (par2 == Keyboard.KEY_ESCAPE)
            {
                this.listening = null;
            }
            else if (par2 == Keyboard.KEY_BACK)
            {
                this.bindListening(0);
            }
            else if (par2 != 0)
            {
                this.bindListening(par2);
            }

            return;
        }

        if (par2 == Keyboard.KEY_ESCAPE)
        {
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }

        if (!this.searchField.isFocused() && par1 >= ' ' && par1 != 127 && !isCtrlKeyDown())
        {
            // Typing anywhere searches, like Sodium.
            this.searchField.setFocused(true);
        }

        if (this.searchField.textboxKeyTyped(par1, par2) && !this.searchField.getText().equals(this.searchText))
        {
            this.searchText = this.searchField.getText();
            this.listening = null;
            this.scroll = 0.0F;
            this.rebuildEntries();
        }
    }

    public void updateScreen()
    {
        this.searchField.updateCursorCounter();
    }

    /**
     * Called when the screen is unloaded. Used to disable keyboard repeat events
     */
    public void onGuiClosed()
    {
        Keyboard.enableRepeatEvents(false);

        if (this.hiddenHud != null)
        {
            if (this.mc.ingameGUI instanceof HudHider)
            {
                this.mc.ingameGUI = this.hiddenHud;
            }

            this.hiddenHud = null;
        }

        // In case the screen closes while a slider is still held.
        mod_Kirmod.saveConfig();
    }

    private void playClick()
    {
        this.mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);
    }

    /** Applies the slider value under mouse x, without saving yet. */
    private void slideTo(KirModule module, int mouseX)
    {
        int var3 = this.getSliderLeft(this.getRowRight(module));
        float var4 = Math.max(0.0F, Math.min(1.0F, (float)(mouseX - var3) / (float)this.getSliderWidth()));
        module.applyValue(Math.round(var4 * (float)(module.getValueNames().length - 1)));
    }

    private int getThumbHeight()
    {
        int var1 = this.listBottom - this.listTop;
        return Math.max(16, var1 * var1 / Math.max(1, this.contentHeight));
    }

    private int getThumbTop()
    {
        int var1 = this.listBottom - this.listTop - this.getThumbHeight();
        return this.listTop + (int)(this.scroll * (float)var1 / (float)Math.max(1, this.getMaxScroll()));
    }

    private void dragScrollbar(int mouseY)
    {
        int var2 = this.listBottom - this.listTop - this.getThumbHeight();
        float var3 = var2 <= 0 ? 0.0F : (float)(mouseY - this.scrollbarGrab - this.listTop) / (float)var2;
        this.scroll = 0.0F;
        this.scrollBy(var3 * (float)this.getMaxScroll());
    }

    /* ------------------------------------------------------------------ Drawing */

    /**
     * Draws the screen and all the components in it.
     */
    public void drawScreen(int par1, int par2, float par3)
    {
        if (this.draggedSlider != null)
        {
            if (Mouse.isButtonDown(0))
            {
                this.slideTo(this.draggedSlider, par1);
            }
            else
            {
                this.draggedSlider = null;
                mod_Kirmod.saveConfig();
            }
        }

        if (this.draggingScrollbar)
        {
            this.dragScrollbar(par2);
        }

        if (this.mc.theWorld == null)
        {
            this.drawBackground(0);
        }
        else
        {
            // Interface tab: blur, then darken the world behind the panels.
            if (mod_Kirmod.MENU_BLUR.isEnabled())
            {
                mod_Kirmod.MENU_BLUR.blurScreen(this.mc, this.width, this.height);
            }

            if (mod_Kirmod.MENU_DARKNESS.isEnabled() && mod_Kirmod.MENU_DARKNESS.getValue() > 0)
            {
                drawRect(0, 0, this.width, this.height, (int)(mod_Kirmod.MENU_DARKNESS.getAlpha() * 255.0F) << 24);
            }
        }

        // Search box, underlined in the accent colour while typing.
        drawRect(this.left, MARGIN, this.searchRight, MARGIN + HEADER_H, this.searchField.isFocused() ? COLOR_PANEL_HOVER : COLOR_PANEL);

        if (this.searchField.isFocused())
        {
            drawRect(this.left, MARGIN + HEADER_H - 1, this.searchRight, MARGIN + HEADER_H, COLOR_ACCENT);
        }

        this.searchField.drawTextBox();

        if (this.searchField.getText().length() == 0 && !this.searchField.isFocused())
        {
            this.fontRenderer.drawString("Search options...", this.searchX, this.searchY, COLOR_TEXT_DIM);
        }

        this.drawTabs(par1, par2);
        this.drawHotkeysPanel(par1, par2);
        this.drawServerStatus(par1, par2);
        this.drawList(par1, par2);
        String var17 = null;

        if (this.listening != null)
        {
            var17 = "\u00a7fPress a key\u00a77   Esc: cancel   Backspace: none";
        }
        else if (this.hasHotkeyRow())
        {
            var17 = "Click: change key   Right-click: default";
        }
        else if (this.hasCyclingRow())
        {
            var17 = "Right-click or shift-click to go back a value";
        }

        if (var17 != null && this.infoLeft >= 0)
        {
            // In the right column, over the footer buttons.
            List var19 = this.fontRenderer.listFormattedStringToWidth(var17, this.right - this.infoLeft - 4);
            int var20 = this.footerTop - 4 - var19.size() * 10;

            for (int var21 = 0; var21 < var19.size(); ++var21)
            {
                String var22 = (String)var19.get(var21);
                this.fontRenderer.drawString(var22, this.right - 2 - this.fontRenderer.getStringWidth(var22), var20 + var21 * 10, COLOR_TEXT_DIM);
            }
        }
        else if (var17 != null)
        {
            int var4 = Integer.MAX_VALUE;

            for (int var5 = 0; var5 < this.controlList.size(); ++var5)
            {
                GuiButton var6 = (GuiButton)this.controlList.get(var5);

                if (var6.id == RESET_ID)
                {
                    var4 = var6.xPosition;
                }
            }

            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var17, var4 - this.left - 8), this.left + 2, this.height - MARGIN - HEADER_H + (HEADER_H - 8) / 2, COLOR_TEXT_DIM);
        }

        super.drawScreen(par1, par2, par3);
        this.drawTooltip(par1, par2);
    }

    private void drawTabs(int mouseX, int mouseY)
    {
        if (this.tabs.isEmpty())
        {
            return;
        }

        SideItem var3 = this.getTabAt(mouseX, mouseY);
        boolean var4 = this.searchText.length() > 0;
        int var13 = this.getSideBottom();
        this.beginScissor(this.left, this.listTop, this.sidebarRight - this.left, var13 - this.listTop);

        // One panel for the whole column, as in Sodium; tabs only draw over it when selected or hovered.
        drawRect(this.left, this.listTop, this.sidebarRight, var13, COLOR_PANEL);

        for (int var5 = 0; var5 < this.tabs.size(); ++var5)
        {
            SideItem var6 = (SideItem)this.tabs.get(var5);
            int var7 = this.getSideY(var6);
            int var14 = var6.getHeight();

            if (var7 + var14 <= this.listTop || var7 >= var13)
            {
                continue;
            }

            if (var6.category == null)
            {
                // A plugin's heading, like Sodium's: darker, its icon at full size, the name and the version.
                drawRect(this.left, var7, this.sidebarRight, var7 + var14, var6 == var3 ? COLOR_PANEL_HOVER : COLOR_HEADER);
                ItemStack var11 = var6.plugin.getIcon();
                int var12 = this.left + 4;

                if (var11 != null)
                {
                    this.drawIcon(var11, var12, var7 + (var14 - 16) / 2, 1.0F);
                    var12 += 20;
                }
                else
                {
                    var12 += 2;
                }

                int var15 = this.sidebarRight - var12 - 4;
                this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var6.plugin.getName(), var15), var12, var7 + 4, COLOR_TEXT);
                this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var6.plugin.getVersion(), var15), var12, var7 + 14, COLOR_ACCENT & 0xFFFFFF);
                continue;
            }

            int var10 = var7 + (TAB_H - 8) / 2;
            boolean var8 = !var4 && !this.hotkeysSelected && var6.plugin == this.selectedPlugin && var6.category.equals(this.selectedCategory);
            if (var8 || var6 == var3)
            {
                drawRect(this.left, var7, this.sidebarRight, var7 + TAB_H, var8 ? COLOR_SELECTED : 0x60000000);
            }

            if (var8)
            {
                // Sodium's selected tab: white text and an accent bar along the right edge.
                drawRect(this.sidebarRight - 2, var7, this.sidebarRight, var7 + TAB_H, COLOR_ACCENT);
            }

            String var9 = this.fontRenderer.trimStringToWidth(var6.category, this.sidebarRight - this.left - 16);
            this.fontRenderer.drawString(var9, this.left + 8, var10, var8 || var6 == var3 ? COLOR_TEXT : COLOR_TAB_TEXT);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        if (this.getSideMaxScroll() > 0)
        {
            // A thin scroll indicator along the sidebar's right edge (the wheel scrolls it).
            int var14 = var13 - this.listTop;
            int var15 = Math.max(12, var14 * var14 / (this.sidebarBottom - this.listTop));
            int var16 = this.listTop + (int)(this.sideScroll * (float)(var14 - var15) / (float)this.getSideMaxScroll());
            // In the gap next to the sidebar, so it never covers the selected tab's accent bar.
            drawRect(this.sidebarRight + 1, var16, this.sidebarRight + 3, var16 + var15, 0xA0FFFFFF);
        }
    }

    /** Draws an item at half size (8x8) with its top left at x, y. */
    private void drawIcon(ItemStack stack, int x, int y)
    {
        this.drawIcon(stack, x, y, 0.5F);
    }

    /** Draws an item scaled (1: 16x16) with its top left at x, y. */
    private void drawIcon(ItemStack stack, int x, int y, float scale)
    {
        GL11.glPushMatrix();
        GL11.glTranslatef((float)x, (float)y, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem var4 = new RenderItem();
        var4.zLevel = 50.0F;

        try
        {
            var4.renderItemIntoGUI(this.fontRenderer, this.mc.renderEngine, stack, 0, 0);
        }
        catch (RuntimeException var6)
        {
            // A broken icon shouldn't break the screen.
        }

        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    /**
     * The Hotkeys panel above the server status: a key cap icon, "Hotkeys" and how many there are, a line
     * saying whether any clash (or how many were changed), then a preview of the keys when there is room.
     */
    private void drawHotkeysPanel(int mouseX, int mouseY)
    {
        if (this.hotkeysTop < 0)
        {
            return;
        }

        List var3 = KirHotkey.getShown();
        boolean var4 = this.hotkeysSelected && this.searchText.length() == 0;
        int var5 = this.sidebarRight - this.left - 8;
        int var6 = this.left + 4;
        int var7 = this.hotkeysTop + 4;
        drawRect(this.left, this.hotkeysTop, this.sidebarRight, this.hotkeysBottom, var4 ? COLOR_SELECTED : (this.isOverHotkeys(mouseX, mouseY) ? COLOR_PANEL_HOVER : COLOR_PANEL));

        if (var4)
        {
            drawRect(this.sidebarRight - 2, this.hotkeysTop, this.sidebarRight, this.hotkeysBottom, COLOR_ACCENT);
        }

        // A small key cap as the icon.
        drawRect(var6, var7, var6 + 9, var7 + 8, 0xFFB0B0B0);
        drawRect(var6 + 1, var7 + 1, var6 + 8, var7 + 6, 0xFF2A2A2A);
        drawRect(var6 + 3, var7 + 2, var6 + 6, var7 + 3, COLOR_ACCENT);
        String var8 = String.valueOf(var3.size());
        int var9 = this.fontRenderer.getStringWidth(var8);
        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth("Hotkeys", var5 - 13 - var9 - 4), var6 + 13, var7, var4 ? COLOR_TEXT : 0xC8C8C8);
        this.fontRenderer.drawString(var8, var6 + var5 - var9, var7, COLOR_TEXT_DIM);
        var7 += 10;
        int var10 = 0;
        int var11 = 0;
        Iterator var12 = var3.iterator();

        while (var12.hasNext())
        {
            KirHotkey var13 = (KirHotkey)var12.next();

            if (!var13.getConflicts(this.mc).isEmpty())
            {
                ++var10;
            }

            if (var13.isChanged())
            {
                ++var11;
            }
        }

        String var14 = var10 > 0 ? var10 + (var10 == 1 ? " key clashes" : " keys clash") : (var11 > 0 ? var11 + " changed" : "Click to edit");
        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var14, var5), var6, var7, var10 > 0 ? 0xFF7070 : COLOR_TEXT_DIM);

        for (int var15 = 0; var15 < this.hotkeysPreview; ++var15)
        {
            var7 += HOTKEYS_LINE_H;

            if (var15 == this.hotkeysPreview - 1 && var3.size() > this.hotkeysPreview)
            {
                this.fontRenderer.drawString("+" + (var3.size() - var15) + " more", var6, var7, COLOR_TEXT_DIM);
                break;
            }

            KirHotkey var16 = (KirHotkey)var3.get(var15);
            String var17 = var16.getKeyName();
            int var18 = this.fontRenderer.getStringWidth(var17);
            int var19 = var6 + var5 - var18;
            boolean var20 = !var16.getConflicts(this.mc).isEmpty();
            drawRect(var19 - 3, var7 - 1, var6 + var5 + 2, var7 + 9, var20 ? 0x80FF5555 : 0x60FFFFFF);
            this.fontRenderer.drawString(var17, var19, var7, var16.key.keyCode == 0 ? COLOR_TEXT_DIM : COLOR_TEXT);
            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var16.getTitle(), var19 - 6 - var6), var6, var7, 0xE0E0E0);
        }
    }

    /** The integrated server's status at the bottom of the sidebar (see KirModuleIntegratedServer). */
    private void drawServerStatus(int mouseX, int mouseY)
    {
        if (this.statusTop < 0)
        {
            return;
        }

        KirModuleIntegratedServer var3 = mod_Kirmod.INTEGRATED_SERVER;
        String[] var4 = var3.getStatusLines(this.mc);
        int var5 = this.sidebarRight - this.left - 6;
        int var6 = this.left + 4;
        int var7 = this.statusTop + 4;
        drawRect(this.left, this.statusTop, this.sidebarRight, this.listBottom, this.isOverStatus(mouseX, mouseY) ? COLOR_PANEL_HOVER : COLOR_PANEL);
        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth("Integrated Server", var5), var6, var7, 0xC8C8C8);
        var7 += 10;
        drawRect(var6, var7 + 2, var6 + 4, var7 + 6, var3.isOnline(this.mc) ? 0xFF55FF55 : 0xFFFF5555);
        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var4[0], var5 - 7), var6 + 7, var7, COLOR_TEXT);

        for (int var8 = 1; var8 < var4.length; ++var8)
        {
            var7 += 10;
            this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(var4[var8], var5), var6, var7, COLOR_TEXT);
        }
    }

    private void drawList(int mouseX, int mouseY)
    {
        int var3 = this.getRowRight();
        KirModule var4 = this.draggedSlider != null ? this.draggedSlider : this.getModuleAt(mouseX, mouseY);
        Entry var18 = this.draggedSlider != null ? null : this.getEntryAt(mouseX, mouseY);
        this.beginScissor(this.listLeft, this.listTop, this.listRight - this.listLeft, this.listBottom - this.listTop);
        for (int var6 = 0; var6 < this.entries.size(); ++var6)
        {
            Entry var7 = (Entry)this.entries.get(var6);
            int var5 = this.getEntryY(var7);
            int var10 = this.getEntryLeft(var7);
            var3 = this.getEntryRight(var7);

            if (!var7.hidden && var5 + var7.height > this.listTop && var5 < this.listBottom)
            {
                if (var7.hotkey != null)
                {
                    this.drawHotkeyRow(var7.hotkey, var10, var5, var3, var7 == var18);
                }
                else if (var7.resetKeys)
                {
                    this.drawResetKeysRow(var10, var5, var3, var7 == var18);
                }
                else if (var7.module != null)
                {
                    this.drawRow(var7.module, var10, var5, var3, var7.module == var4);
                }
                else if (var7.title)
                {
                    this.drawTitleBar(var7, var10, var5, var3);
                }
                else if (var7.label != null)
                {
                    this.drawGroupHeader(var7.label, var10, var5, var3);
                }
            }
        }

        if (this.entries.isEmpty() || this.entries.size() == 1 && ((Entry)this.entries.get(0)).title)
        {
            String var8 = this.searchText.length() > 0 ? "No options match \"" + this.searchText + "\"" : (this.hotkeysSelected ? "No hotkeys" : "No options here");
            this.drawCenteredString(this.fontRenderer, var8, (this.listLeft + this.listRight) / 2, this.listTop + 12, COLOR_TEXT_DIM);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        if (this.hasScrollbar())
        {
            drawRect(this.listRight - 3, this.listTop, this.listRight - 1, this.listBottom, COLOR_PANEL);
            int var9 = this.getThumbTop();
            drawRect(this.listRight - 4, var9, this.listRight, var9 + this.getThumbHeight(), this.draggingScrollbar ? COLOR_ACCENT : 0xFFC0C0C0);
        }
    }

    /** Sodium's page title: a dark bar with the plugin's icon and its name in bold. */
    private void drawTitleBar(Entry entry, int left, int y, int right)
    {
        drawRect(left, y, right, y + ROW_H, COLOR_HEADER);
        int var4 = left + 6;

        if (entry.titlePlugin != null && entry.titlePlugin.getIcon() != null)
        {
            this.drawIcon(entry.titlePlugin.getIcon(), var4, y + (ROW_H - 8) / 2);
            var4 += 12;
        }

        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth("\u00a7l" + entry.label, right - var4 - 4), var4, y + (ROW_H - 8) / 2, COLOR_TEXT);
    }

    /** Sodium's group header: a dark bar with an accent diamond and the group's name in the accent colour. */
    private void drawGroupHeader(String label, int left, int y, int right)
    {
        drawRect(left, y, right, y + GROUP_LABEL_BAR_H, COLOR_HEADER);
        int var4 = left + 6;
        int var5 = y + GROUP_LABEL_BAR_H / 2;
        drawRect(var4 + 2, var5 - 2, var4 + 3, var5 - 1, COLOR_ACCENT);
        drawRect(var4 + 1, var5 - 1, var4 + 4, var5, COLOR_ACCENT);
        drawRect(var4, var5, var4 + 5, var5 + 1, COLOR_ACCENT);
        drawRect(var4 + 1, var5 + 1, var4 + 4, var5 + 2, COLOR_ACCENT);
        drawRect(var4 + 2, var5 + 2, var4 + 3, var5 + 3, COLOR_ACCENT);
        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(label, right - var4 - 14), var4 + 9, y + (GROUP_LABEL_BAR_H - 8) / 2, COLOR_ACCENT & 0xFFFFFF);
    }

    private void drawRow(KirModule module, int left, int y, int right, boolean hovered)
    {
        drawRect(left, y, right, y + ROW_H, hovered ? COLOR_PANEL_HOVER : COLOR_PANEL);
        int var5 = y + (ROW_H - 8) / 2;
        int var6 = module.isAvailable() && module.getLockedMessage() == null ? COLOR_TEXT : COLOR_TEXT_DIM;
        int var7;

        if (isCheckbox(module))
        {
            var7 = right - 6 - 9;
            int var8 = y + (ROW_H - 9) / 2;
            boolean var17 = "On".equals(module.getValueName());

            // Sodium's checkbox: an accent border and fill when on, a white border when off.
            drawRect(var7, var8, var7 + 9, var8 + 9, var17 ? COLOR_ACCENT : 0xFFFFFFFF);
            drawRect(var7 + 1, var8 + 1, var7 + 8, var8 + 8, 0xFF000000);

            if (var17)
            {
                drawRect(var7 + 2, var8 + 2, var7 + 7, var8 + 7, COLOR_ACCENT);
            }
        }
        else if (module.isSlider())
        {
            int var9 = this.getSliderLeft(right);
            int var10 = this.getSliderWidth();
            int var11 = y + ROW_H / 2;
            int var12 = module.getValueNames().length - 1;
            int var13 = var9 + (var12 <= 0 ? 0 : module.getValue() * var10 / var12);

            // The track: accent up to the thumb, grey after it.
            drawRect(var9, var11, var13, var11 + 1, COLOR_ACCENT);
            drawRect(var13, var11, var9 + var10, var11 + 1, 0xFF8A8A8A);
            drawRect(var13 - 1, y + 4, var13 + 2, y + ROW_H - 4, 0xFFFFFFFF);
            String var14 = module.getValueName();
            var7 = var9 - 8 - this.fontRenderer.getStringWidth(var14);
            this.fontRenderer.drawString(var14, var7, var5, var6);
        }
        else
        {
            String var15 = module.getValueName();
            var7 = right - 6 - this.fontRenderer.getStringWidth(var15);
            this.fontRenderer.drawString(var15, var7, var5, var6);
        }

        String var16 = this.fontRenderer.trimStringToWidth(module.getTitle(), var7 - left - 14);
        this.fontRenderer.drawString(var16, left + 6, var5, var6);
    }

    /**
     * A hotkey's row: its name, the value of the module it switches (dimmed), a dot when it isn't on its
     * default key, and a key cap with the key. The cap is red when another binding uses the same key, and
     * glows while waiting for a new key.
     */
    private void drawHotkeyRow(KirHotkey hotkey, int left, int y, int right, boolean hovered)
    {
        boolean var5 = hotkey == this.listening;
        drawRect(left, y, right, y + ROW_H, var5 ? COLOR_SELECTED : (hovered ? COLOR_PANEL_HOVER : COLOR_PANEL));

        if (var5)
        {
            drawRect(left, y, left + 2, y + ROW_H, COLOR_ACCENT);
        }

        int var6 = y + (ROW_H - 8) / 2;
        boolean var7 = !hotkey.getConflicts(this.mc).isEmpty();
        String var8 = var5 ? "Press a key" : hotkey.getKeyName();
        int var9 = Math.max(36, this.fontRenderer.getStringWidth(var8) + 14);
        int var10 = right - 6 - var9;
        int var11 = y + 3;
        int var12 = var10 + var9;
        int var13 = var11 + 11;
        int var14;

        if (var5)
        {
            // Pulses between the accent colour and white.
            float var15 = (float)(Math.sin((double)(System.currentTimeMillis() % 1000L) / 1000.0D * Math.PI * 2.0D) * 0.5D + 0.5D);
            int var16 = 0x94 + (int)((float)(0xFF - 0x94) * var15);
            int var17 = 0xE4 + (int)((float)(0xFF - 0xE4) * var15);
            int var18 = 0xD3 + (int)((float)(0xFF - 0xD3) * var15);
            var14 = 0xFF000000 | var16 << 16 | var17 << 8 | var18;
        }
        else
        {
            var14 = var7 ? COLOR_CONFLICT : (hovered ? 0xFFFFFFFF : 0xFF6A6A6A);
        }

        // The cap: a border, a dark face with a lighter top edge, and a shadow under it.
        drawRect(var10, var13, var12, var13 + 1, 0x90000000);
        drawRect(var10, var11, var12, var13, var14);
        drawRect(var10 + 1, var11 + 1, var12 - 1, var13 - 1, var5 ? 0xFF1C3430 : (var7 ? 0xFF3A1A1A : 0xFF222222));
        drawRect(var10 + 1, var11 + 1, var12 - 1, var11 + 2, 0x30FFFFFF);
        int var19 = var5 ? var14 & 0xFFFFFF : (var7 ? 0xFF8080 : (hotkey.key.keyCode == 0 ? COLOR_TEXT_DIM : COLOR_TEXT));
        this.fontRenderer.drawString(var8, var10 + (var9 - this.fontRenderer.getStringWidth(var8)) / 2, var11 + 2, var19);
        int var20 = var10 - 6;

        if (var7)
        {
            this.fontRenderer.drawString("!", var20 - 2, var6, 0xFF5555);
            var20 -= 8;
        }

        if (hotkey.isChanged())
        {
            drawRect(var20 - 3, var6 + 2, var20, var6 + 5, COLOR_ACCENT);
            var20 -= 7;
        }

        if (hotkey.module != null)
        {
            String var21 = hotkey.module.getValueName();
            int var22 = this.fontRenderer.getStringWidth(var21);

            if (var20 - var22 > left + 60)
            {
                var20 -= var22;
                this.fontRenderer.drawString(var21, var20, var6, COLOR_TEXT_DIM);
                var20 -= 6;
            }
        }

        this.fontRenderer.drawString(this.fontRenderer.trimStringToWidth(hotkey.getTitle(), var20 - left - 8), left + 6, var6, COLOR_TEXT);
    }

    /** "Reset All Hotkeys", centred, dimmed while every key is on its default. */
    private void drawResetKeysRow(int left, int y, int right, boolean hovered)
    {
        boolean var4 = false;
        Iterator var5 = KirHotkey.getShown().iterator();

        while (var5.hasNext())
        {
            var4 |= ((KirHotkey)var5.next()).isChanged();
        }

        drawRect(left, y, right, y + ROW_H, hovered ? COLOR_PANEL_HOVER : COLOR_PANEL);

        if (hovered)
        {
            drawRect(left, y + ROW_H - 1, right, y + ROW_H, 0xFFFFFFFF);
        }

        this.drawCenteredString(this.fontRenderer, "Reset All Hotkeys", (left + right) / 2, y + (ROW_H - 8) / 2, var4 ? COLOR_TEXT : COLOR_TEXT_DIM);
    }

    /** Whether hotkey rows are shown, which the footer hint is about. */
    private boolean hasHotkeyRow()
    {
        Iterator var1 = this.entries.iterator();

        while (var1.hasNext())
        {
            if (((Entry)var1.next()).hotkey != null)
            {
                return true;
            }
        }

        return false;
    }

    /** Clips drawing to a rectangle in GUI coordinates. */
    private void beginScissor(int x, int y, int width, int height)
    {
        int var5 = new ScaledResolution(this.mc.gameSettings, this.mc.displayWidth, this.mc.displayHeight).getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * var5, this.mc.displayHeight - (y + height) * var5, width * var5, height * var5);
    }

    /** Whether a row shown steps through values on click, which the footer hint is about. */
    private boolean hasCyclingRow()
    {
        Iterator var1 = this.entries.iterator();

        while (var1.hasNext())
        {
            KirModule var2 = ((Entry)var1.next()).module;

            if (var2 != null && !isCheckbox(var2))
            {
                return true;
            }
        }

        return false;
    }

    /** Draws the tooltip of what the mouse rests on, once it has rested there long enough. */
    private void drawTooltip(int mouseX, int mouseY)
    {
        Entry var19 = this.getEntryAt(mouseX, mouseY);
        Object var3 = null;
        int var4 = 0;
        int var5 = 0;
        int var6 = 0;
        Entry var25 = null;

        if (var19 != null && (var19.module != null || var19.hotkey != null || var19.resetKeys))
        {
            var3 = var19.module != null ? (Object)var19.module : (var19.hotkey != null ? (Object)var19.hotkey : (Object)var19);
            var25 = var19;
            var4 = this.getEntryLeft(var19);
            var5 = this.getEntryY(var19);
            var6 = this.getEntryRight(var19) - var4;
        }

        // With the right column, a row's tooltip shows there at once, level with the row, like Sodium's.
        boolean var24 = this.infoLeft >= 0 && var25 != null;

        if (var24)
        {
            var4 = this.infoLeft;
            var6 = this.right - this.infoLeft;
        }
        else if (var25 != null)
        {
            // No right column: the row's tooltip floats under it (set above).
        }
        else if (this.isOverHotkeys(mouseX, mouseY))
        {
            var3 = HOTKEYS_PANEL;
            var4 = this.sidebarRight + 4;
            var5 = this.hotkeysTop;
            var6 = Math.min(TOOLTIP_WIDTH + 12, this.listRight - var4);
        }
        else if (this.isOverStatus(mouseX, mouseY))
        {
            var3 = STATUS_PANEL;
            var4 = this.sidebarRight + 4;
            var5 = this.statusTop;
            var6 = Math.min(TOOLTIP_WIDTH + 12, this.listRight - var4);
        }
        else if ((var3 = this.getTabAt(mouseX, mouseY)) != null)
        {
            var4 = this.sidebarRight + 4;
            var5 = this.getSideY((SideItem)var3) - ROW_H;
            var6 = Math.min(TOOLTIP_WIDTH + 12, this.listRight - var4);
        }
        else
        {
            for (int var7 = 0; var7 < this.controlList.size(); ++var7)
            {
                GuiButton var8 = (GuiButton)this.controlList.get(var7);

                if (mouseX >= var8.xPosition && mouseY >= var8.yPosition && mouseX < var8.xPosition + ((FlatButton)var8).getWidth() && mouseY < var8.yPosition + HEADER_H)
                {
                    var3 = var8;
                    var6 = Math.min(TOOLTIP_WIDTH + 12, this.width - 2 * MARGIN);
                    var4 = Math.max(MARGIN, Math.min(this.width - MARGIN - var6, var8.xPosition + ((FlatButton)var8).getWidth() - var6));
                    var5 = var8.yPosition;
                }
            }
        }

        if (var3 != this.hovered)
        {
            this.hovered = var3;
            this.hoverStart = System.currentTimeMillis();
        }

        if (var3 == null || this.draggedSlider != null || this.draggingScrollbar || this.listening != null || !var24 && System.currentTimeMillis() - this.hoverStart < TOOLTIP_DELAY)
        {
            return;
        }

        List var9 = this.getTooltip(var3);

        if (var9 == null)
        {
            return;
        }

        List var10 = this.wrapTooltip(var9, var24 ? var6 - 12 : Math.min(TOOLTIP_WIDTH, var6 - 12));

        if (var24 && var10.size() * 10 + 8 > this.footerTop - 4 - this.listTop)
        {
            // Too tall for the right column: float it under the row instead, where it can be wider.
            var24 = false;
            var4 = this.getEntryLeft(var25);
            var6 = this.getEntryRight(var25) - var4;
            var10 = this.wrapTooltip(var9, Math.min(TOOLTIP_WIDTH, var6 - 12));
        }

        int var13 = 0;
        Iterator var14 = var10.iterator();

        while (var14.hasNext())
        {
            var13 = Math.max(var13, this.fontRenderer.getStringWidth((String)var14.next()));
        }

        // A panel under the row (or above it when there is no room), lined up with its left edge.
        int var15 = var10.size() * 10 + 8;
        int var16 = var13 + 12;
        int var17 = var5 + ROW_H + 2;

        if (var24)
        {
            var16 = var6;
            var17 = Math.max(this.listTop, Math.min(var5, this.footerTop - 4 - var15));
        }
        else if (var17 + var15 > this.height - 2)
        {
            var17 = Math.max(2, var5 - var15 - 2);
        }

        GL11.glPushMatrix();
        GL11.glTranslatef(0.0F, 0.0F, 300.0F);
        drawRect(var4, var17, var4 + var16, var17 + var15, 0xF0101010);
        drawRect(var4, var17, var4 + 1, var17 + var15, COLOR_ACCENT);

        for (int var18 = 0; var18 < var10.size(); ++var18)
        {
            this.fontRenderer.drawString((String)var10.get(var18), var4 + 6, var17 + 5 + var18 * 10, COLOR_TEXT);
        }

        GL11.glPopMatrix();
    }

    private List wrapTooltip(List lines, int width)
    {
        ArrayList var3 = new ArrayList();
        Iterator var4 = lines.iterator();

        while (var4.hasNext())
        {
            var3.addAll(this.fontRenderer.listFormattedStringToWidth((String)var4.next(), width));
        }

        return var3;
    }

    /** The unwrapped tooltip lines of a module, tab or button, title first, or null if it has none. */
    private List getTooltip(Object hovered)
    {
        ArrayList var2 = new ArrayList();

        if (hovered == STATUS_PANEL)
        {
            var2.add("§eIntegrated Server");
            var2.add("Since 1.3, singleplayer worlds run on a server inside the game, on its own thread, and the client shows a copy. Online: it is running this world. Offline: no world is open, or the world is a remote server's, or it runs on the client because Integrated Server is Off.");
            var2.add("§bUp§f: how long it has been running. §bTPS§f: ticks per second (20 is full speed) and the average time a tick takes.");
            var2.add("§7Click to go to the Integrated Server setting.");
            return var2;
        }

        if (hovered == HOTKEYS_PANEL)
        {
            var2.add("\u00a7eHotkeys");
            var2.add("The keys of Kirmod and its plugins, in one place. Click to see them all and change them.");
            var2.add("\u00a7cRed\u00a7f: another key binding (the game's or a mod's) uses the same key, so one press does both.");
            var2.add("\u00a77They are also in Options > Controls; a change in either shows in both.");
            return var2;
        }

        if (hovered instanceof KirHotkey)
        {
            KirHotkey var20 = (KirHotkey)hovered;
            var2.add("\u00a7e" + var20.getTitle() + " \u00a77(" + var20.getPlugin().getName() + ")");

            if (var20.module != null)
            {
                var2.add("Switches " + var20.module.getTitle() + " to its next value and says so in chat. Now: " + var20.module.getValueName() + ".");
            }
            else if (var20.description != null)
            {
                var2.add(var20.description);
            }

            var2.add("\u00a7bKey\u00a7f: " + var20.getKeyName() + (var20.isChanged() ? "  \u00a77(default " + var20.comboName(var20.defaultCode) + ")" : "  \u00a77(default)"));
            List var21 = var20.getConflicts(this.mc);

            if (!var21.isEmpty())
            {
                StringBuilder var22 = new StringBuilder();

                for (int var23 = 0; var23 < var21.size(); ++var23)
                {
                    var22.append(var23 == 0 ? "" : ", ").append(var21.get(var23));
                }

                var2.add("\u00a7cAlso used by: " + var22);
            }

            var2.add("\u00a77Click, then press the new key. Right-click for the default.");
            return var2;
        }

        if (hovered instanceof Entry && ((Entry)hovered).resetKeys)
        {
            var2.add("\u00a7eReset All Hotkeys");
            var2.add("Puts every key in this list back on its default. Other key bindings are not changed.");
            return var2;
        }

        if (hovered instanceof KirModule)
        {
            KirModule var3 = (KirModule)hovered;
            String[] var4 = var3.getTooltip();
            var2.add("§e" + var3.getTitle() + (var3.isAvailable() ? "" : " §c(unavailable)"));

            if (var3.getLockedMessage() != null)
            {
                var2.add("§c" + var3.getLockedMessage());
            }

            if (var4 != null)
            {
                Collections.addAll(var2, var4);
            }

            if (var3.getCommandName() != null)
            {
                var2.add("§7Command: /" + var3.getCommandName());
            }
        }
        else if (hovered instanceof SideItem && ((SideItem)hovered).category == null)
        {
            KirPlugin var12 = ((SideItem)hovered).plugin;
            var2.add("§e" + var12.getName() + " §7" + var12.getVersion());

            if (var12.getDescription() != null)
            {
                var2.add(var12.getDescription());
            }

            var2.add("§7" + (var12 == KirPlugin.KIRMOD ? "Plugins add their own sections below this one." : "A Kirmod plugin. Click to open its first tab."));
        }
        else if (hovered instanceof SideItem)
        {
            KirPlugin var13 = ((SideItem)hovered).plugin;
            String var5 = ((SideItem)hovered).category;
            var2.add("§e" + var5);
            String var6 = var13 == KirPlugin.KIRMOD ? getCategoryDescription(var5) : var13.getCategoryDescription(var5);

            if (var6 != null)
            {
                var2.add(var6);
            }

            Iterator var7 = mod_Kirmod.getCategories(var13, var5).iterator();

            if (var7.hasNext())
            {
                StringBuilder var8 = new StringBuilder();

                while (var7.hasNext())
                {
                    var8.append(var8.length() == 0 ? "" : ", ").append(var7.next());
                }

                var2.add("§7Also has: " + var8);
            }
        }
        else if (hovered instanceof GuiButton)
        {
            switch (((GuiButton)hovered).id)
            {
                case TEXTURE_PACKS_ID:
                    var2.add("§eTexture Packs");
                    var2.add("Pick the texture pack in use from the built-in Console Edition packs or your own. The game's textures reload as soon as you pick one.");
                    break;

                case SKINS_ID:
                    var2.add("§eSkin Packs");
                    var2.add("Pick the skin drawn on your player from the bundled skin packs or your own image. Only you can see it.");
                    break;

                case CAPES_ID:
                    var2.add("§eCapes");
                    var2.add("Pick the cape drawn on your player: one your account owns, one from the gallery or your own image. Only you can see it.");
                    break;

                case MUSIC_ID:
                    var2.add("\u00a7eMusic");
                    var2.add("Pick the soundtrack: the game's own, C418's Volume Alpha or Volume Beta, All Music or your own, from .minecraft/soundtracks. Shuffle or in order, Music Frequency, Music Pitch, Nether and End soundtracks, and play, pause and skip.");
                    break;

                case RESET_ID:
                    var2.add("§eReset to Defaults");
                    var2.add("Puts every Kirmod setting, in every category, back to its default value. Asks first." + (KirEdition.LITE ? "" : " Your cape and skin are kept."));
                    break;

                default:
                    return null;
            }
        }
        else
        {
            return null;
        }

        return var2;
    }

    /** What a built-in category is for, or null for one another mod added. */
    private static String getCategoryDescription(String category)
    {
        return KirModule.CATEGORY_PERFORMANCE.equals(category) ? "Settings that make the game run faster." : (KirModule.CATEGORY_VISUALS.equals(category) ? "Settings that change how the world looks." : (KirModule.CATEGORY_OVERWORLD.equals(category) ? "Settings for how the overworld looks." : (KirModule.CATEGORY_END.equals(category) ? "Settings for how the End looks." : (KirModule.CATEGORY_GAMEPLAY.equals(category) ? "Settings that change how the game plays." : (KirModule.CATEGORY_ITEMS.equals(category) ? "Settings for items, crafting, fishing and villager trading." : (KirModule.CATEGORY_WORLD.equals(category) ? "Settings for world rules: liquids and redstone quirks." : (KirModuleIsometric.CATEGORY_CAMERA.equals(category) ? "The screenshot cameras: isometric and panorama." : (KirModule.CATEGORY_OPTIFINE.equals(category) ? "Fixes and additions for OptiFine's features (most also help without OptiFine)." : (KirModule.CATEGORY_INTERFACE.equals(category) ? "Settings for the menus: how much this screen darkens and blurs the world behind it, and the Dark Theme (and its tint) of the plugins' screens." : (KirModule.CATEGORY_OTHER.equals(category) ? "Everything else." : null))))))))));
    }

    /** One line of the sidebar: a plugin's heading (category null) or one of its tabs. */
    private static class SideItem
    {
        final KirPlugin plugin;
        final String category;

        /** Top on screen, set by initGui. */
        int y;

        SideItem(KirPlugin plugin, String category)
        {
            this.plugin = plugin;
            this.category = category;
        }

        int getHeight()
        {
            return this.category != null ? TAB_H : HEADING_H;
        }

        /** The sidebar width this item needs: a tab's name, or a heading's icon, name and version. */
        int getWidth(FontRenderer font)
        {
            return this.category != null ? font.getStringWidth(this.category) + 22 : 26 + Math.max(font.getStringWidth(this.plugin.getName()), font.getStringWidth(this.plugin.getVersion())) + 6;
        }
    }

    /**
     * One line of the option list: a module's row, a hotkey's row, the Reset All Hotkeys row, a group label,
     * or a gap (all null).
     */
    private static class Entry
    {
        final KirModule module;
        final String label;
        final int height;
        KirHotkey hotkey;
        boolean resetKeys;

        /** The bar at the top of a page: label in bold, after titlePlugin's icon (if not null). */
        boolean title;
        KirPlugin titlePlugin;

        /** Set by layOutEntries: the column (0 left, 1 right, -1 across both), the top within the list, and whether it takes no room (a gap at a column's end). */
        int col;
        int y;
        boolean hidden;

        Entry(KirModule module, String label, int height)
        {
            this.module = module;
            this.label = module == null ? label : null;
            this.height = height;
        }

        static Entry hotkey(KirHotkey hotkey)
        {
            Entry var1 = new Entry((KirModule)null, (String)null, ROW_H);
            var1.hotkey = hotkey;
            return var1;
        }

        static Entry title(KirPlugin plugin, String text)
        {
            Entry var2 = new Entry((KirModule)null, text, ROW_H);
            var2.title = true;
            var2.titlePlugin = plugin;
            return var2;
        }

        static Entry resetKeys()
        {
            Entry var0 = new Entry((KirModule)null, (String)null, ROW_H);
            var0.resetKeys = true;
            return var0;
        }
    }

    /**
     * Stands in for the game's HUD (mc.ingameGUI) while the screen is open: draws nothing, and passes
     * everything else (ticks, chat, the record message) on to the real one, which is put back on close.
     */
    static class HudHider extends GuiIngame
    {
        private final Minecraft game;
        private final GuiIngame hud;

        HudHider(Minecraft mc, GuiIngame hud)
        {
            super(mc);
            this.game = mc;
            this.hud = hud;
        }

        /** Only the GL setup the screen drawn next relies on: the 2D projection and the state the HUD leaves. */
        public void renderGameOverlay(float par1, boolean par2, int par3, int par4)
        {
            this.game.entityRenderer.setupOverlayRendering();
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
        }

        public void updateTick()
        {
            this.hud.updateTick();
        }

        public void setRecordPlayingMessage(String par1Str)
        {
            this.hud.setRecordPlayingMessage(par1Str);
        }

        public GuiNewChat getChatGUI()
        {
            return this.hud.getChatGUI();
        }

        public int getUpdateCounter()
        {
            return this.hud.getUpdateCounter();
        }
    }

    /** A flat, semi-transparent button like Sodium's. */
    static class FlatButton extends GuiButton
    {
        FlatButton(int id, int x, int y, int width, int height, String text)
        {
            super(id, x, y, width, height, text);
        }

        int getWidth()
        {
            return this.width;
        }

        public void drawButton(Minecraft par1Minecraft, int par2, int par3)
        {
            if (this.drawButton)
            {
                boolean var4 = par2 >= this.xPosition && par3 >= this.yPosition && par2 < this.xPosition + this.width && par3 < this.yPosition + this.height;
                drawRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, var4 && this.enabled ? COLOR_PANEL_HOVER : COLOR_PANEL);

                if (var4 && this.enabled)
                {
                    drawRect(this.xPosition, this.yPosition + this.height - 1, this.xPosition + this.width, this.yPosition + this.height, COLOR_ACCENT);
                }

                FontRenderer var5 = par1Minecraft.fontRenderer;
                var5.drawString(this.displayString, this.xPosition + (this.width - var5.getStringWidth(this.displayString)) / 2, this.yPosition + (this.height - 8) / 2, this.enabled ? COLOR_TEXT : COLOR_TEXT_DIM);
            }
        }
    }
}
