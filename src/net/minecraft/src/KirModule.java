package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * One Kirmod feature: a setting with a fixed list of values, plus the code that applies it.
 *
 * Modules are registered with mod_Kirmod.registerModule(). mod_Kirmod gives each one a button in the
 * Options > Kirmod... screen, a chat command, a value in config/Kirmod.cfg and a "KEY.enabled" switch
 * there. A module switched off in the config is never installed, so whatever it changes in the game
 * (blocks, renderers, hooks) is left completely vanilla.
 *
 * To add a feature, extend this class, implement getValueNames() and override the hooks you need, then
 * register it (see mod_Kirmod's static block for the built-in modules).
 */
public abstract class KirModule
{
    /**
     * The General tab (1.37): no modules of its own, it holds the Performance, Visuals, Textures and OptiFine
     * Patches groups (the first two and the last used to be tabs of their own).
     */
    public static final String CATEGORY_GENERAL = "General";

    /** Category of modules that make the game run faster; shown under Kirmod > General > Performance. */
    public static final String CATEGORY_PERFORMANCE = "Performance";

    /** Category of modules that change how the world looks; shown under Kirmod > General > Visuals. */
    public static final String CATEGORY_VISUALS = "Visuals";

    /** Category of modules about how the overworld looks; shown under Kirmod > General > Visuals > Overworld. */
    public static final String CATEGORY_OVERWORLD = "Overworld";

    /** Category of modules about how the End looks; shown under Kirmod > General > Visuals > End Dimension. */
    public static final String CATEGORY_END = "End Dimension";

    /** Category of modules that change how the game plays; shown under Kirmod > Gameplay... */
    public static final String CATEGORY_GAMEPLAY = "Gameplay";

    /** Category of item, crafting and trading changes; shown under Kirmod > Gameplay... > Items & Trading... */
    public static final String CATEGORY_ITEMS = "Items & Trading";

    /** Category of world rules (liquids, redstone quirks); shown under Kirmod > Gameplay... > World... */
    public static final String CATEGORY_WORLD = "World";

    /**
     * Category of block texture choices (Ore Outlines, Beta grass, Alpha bricks...; KirModuleTexture), shown
     * under Kirmod > General > Textures (1.37).
     */
    public static final String CATEGORY_TEXTURES = "Textures";

    /** Category of fixes and additions for OptiFine's features; shown under Kirmod > General > OptiFine Patches. */
    public static final String CATEGORY_OPTIFINE = "OptiFine Patches";

    /** Category of settings for Kirmod's own settings screen; the Interface tab. */
    public static final String CATEGORY_INTERFACE = "Interface";

    /**
     * Category of the music settings (1.36). The settings screen doesn't list it: its modules are set in the
     * Music screen (GuiKirmodMusic), opened from the header's "Music..." button.
     */
    public static final String CATEGORY_MUSIC = "Music";

    /** Category of everything else; shown under Kirmod > Other... */
    public static final String CATEGORY_OTHER = "Other";

    private final String key;
    private final String title;
    private final String buttonTitle;
    private final int defaultValue;
    private final String commandName;
    private final String[] commandAliases;
    private int value;

    /** Set from the config before install(); a disabled module is never installed or updated. */
    boolean enabled = true;

    /** The plugin whose sidebar section has this module's tab; set by mod_Kirmod.registerModule. */
    KirPlugin plugin = KirPlugin.KIRMOD;

    /**
     * @param key          config key and option name for commands, e.g. "musicFrequency"
     * @param title        full name, e.g. "Music Frequency"
     * @param buttonTitle  short name for the 150px settings button
     * @param defaultValue index into getValueNames()
     * @param commandName  chat command that shows or sets this option, or null for none
     * @param aliases      default aliases of that command
     */
    protected KirModule(String key, String title, String buttonTitle, int defaultValue, String commandName, String ... aliases)
    {
        this.key = key;
        this.title = title;
        this.buttonTitle = buttonTitle;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.commandName = commandName;
        this.commandAliases = aliases;
    }

    /** Names of the values this option can take, in cycling order. */
    public abstract String[] getValueNames();

    /** Called once from mod_Kirmod.load() if the module is enabled. Swap blocks, add textures etc. here. */
    public void install() {}

    /** Called every frame, in game and in menus, while the module is enabled. */
    public void update(Minecraft mc) {}

    /** Called after the player changes the value (not when it is read from the config at startup). */
    protected void onValueChanged() {}

    /**
     * Called on the integrated server's thread for each of its worlds, every tick, right before the
     * world ticks its entities. Needs KirWorldHook installed (KirWorldHook.installAll(mc) from update()).
     */
    public void onServerWorldTick(World world) {}

    /** Like onServerWorldTick, but after the world's entities have ticked. */
    public void onServerWorldTickEnd(World world) {}

    /**
     * True to have onServerPlayerTick called. Asked every server tick, so keep it cheap; the hook it
     * needs is installed while any enabled module says true.
     */
    public boolean hasServerPlayerTick()
    {
        return false;
    }

    /** Called on the integrated server's thread every tick for the host player (singleplayer or LAN). */
    public void onServerPlayerTick(EntityPlayer player) {}

    /**
     * Called on the client before each inventory click goes out (PlayerControllerMP.windowClick: a click in
     * any container screen, or one another mod makes). Return true if the module did the click itself; the
     * original click is then skipped. Clicks the module makes from here go straight through, without
     * calling this again. Needs Kirmod's player controller: call KirInteraction.installClient(mc) from
     * update(). New in Kirmod 1.29.
     *
     * @param slot   the container's slot number, or -999 for a click outside the screen (drop)
     * @param button 0 left, 1 right
     * @param shift  whether it is a shift-click (vanilla never sends shift for -999)
     */
    public boolean onWindowClick(Minecraft mc, int windowId, int slot, int button, boolean shift, EntityPlayer player)
    {
        return false;
    }

    /**
     * True if this right-click on a block should use the held item on it (place a block) instead of
     * activating the block (opening a chest), on the client and the integrated server alike. Needs
     * KirInteraction.installClient(mc) and, for the server side, hasServerPlayerTick(). Called only for enabled
     * modules. New in Kirmod 1.33 (Kirmod Extras' Sneak Place).
     */
    public boolean usesItemInsteadOfActivating(EntityPlayer player, ItemStack stack)
    {
        return false;
    }

    /**
     * Called on the client before a right-click on a block is handled (after usesItemInsteadOfActivating).
     * Return true if the module dealt with it; nothing is then sent to the server. Needs
     * KirInteraction.installClient(mc). New in Kirmod 1.33 (Kirmod Extras' Painting Picker).
     */
    public boolean onRightClickBlock(Minecraft mc, EntityPlayer player, World world, ItemStack stack, int x, int y, int z, int side)
    {
        return false;
    }

    /**
     * Called on the integrated server's thread when the host player is saved, with the NBT that goes into
     * level.dat as the player (also with Integrated Server Off). A module can add its own tags; vanilla ignores
     * them and reads them back with world.getWorldInfo().getPlayerNBTTagCompound(). Needs hasServerPlayerTick()
     * (while it is true, Kirmod hooks into player saving). New in Kirmod 1.33.
     */
    public void onSaveHostPlayer(EntityPlayer player, NBTTagCompound tag) {}

    /** True to show a slider over all values in the settings screen instead of a cycling button. */
    public boolean isSlider()
    {
        return false;
    }

    /**
     * The submenu of the settings screen this module's button is in, e.g. CATEGORY_GAMEPLAY, or null for
     * the main Kirmod screen. Each category gets a "NAME..." button on the main screen, or on its parent's
     * screen if it has one (see getParentCategory).
     */
    public String getCategory()
    {
        return null;
    }

    /**
     * The category whose screen has the button of this category, or null for the main screen. Categories
     * can nest more than one deep: General > Visuals > Overworld (1.37).
     */
    public static String getParentCategory(String category)
    {
        if (CATEGORY_OVERWORLD.equals(category) || CATEGORY_END.equals(category))
        {
            return CATEGORY_VISUALS;
        }

        if (CATEGORY_PERFORMANCE.equals(category) || CATEGORY_VISUALS.equals(category) || CATEGORY_TEXTURES.equals(category) || CATEGORY_OPTIFINE.equals(category))
        {
            return CATEGORY_GENERAL;
        }

        return CATEGORY_ITEMS.equals(category) || CATEGORY_WORLD.equals(category) ? CATEGORY_GAMEPLAY : null;
    }

    /**
     * Lines of the tooltip shown when the mouse rests on this module's button: what the setting does,
     * then what each value does. The settings screen adds the title above and wraps long lines; "\u00a7"
     * color codes may be used. Null for no tooltip.
     */
    public String[] getTooltip()
    {
        return null;
    }

    /** A tooltip line about one value, e.g. describeValue("Off", "signs are skipped."). */
    protected static String describeValue(String value, String description)
    {
        return "\u00a7b" + value + "\u00a7f: " + description;
    }

    /** A grey tooltip line for a caveat, e.g. where the setting works. */
    protected static String note(String text)
    {
        return "\u00a77" + text;
    }

    /**
     * Height of the preview dropdown under this module's row in the settings screen, for a row the given
     * width; 0 (the default) for none. A row with a preview gets an arrow that opens and closes it. New in
     * Kirmod 1.37 (Global Grass and Foliage Color).
     */
    public int getPreviewHeight(int width)
    {
        return 0;
    }

    /** Draws the preview dropdown (see getPreviewHeight) between left and right, from y down. */
    public void drawPreview(Minecraft mc, int left, int y, int right, int mouseX, int mouseY) {}

    /** A click in the preview dropdown; returns whether it did something (the screen then plays the click). */
    public boolean previewClicked(int left, int y, int right, int mouseX, int mouseY, int button)
    {
        return false;
    }

    /** False if the module could not hook into the game (another mod got there first, say). */
    public boolean isAvailable()
    {
        return true;
    }

    /** The plugin this module belongs to (KirPlugin.KIRMOD for Kirmod's own). */
    public KirPlugin getPlugin()
    {
        return this.plugin;
    }

    public String getKey()
    {
        return this.key;
    }

    public String getTitle()
    {
        return this.title;
    }

    public String getButtonTitle()
    {
        return this.buttonTitle;
    }

    public String getCommandName()
    {
        return this.commandName;
    }

    public String[] getCommandAliases()
    {
        return this.commandAliases;
    }

    public boolean isEnabled()
    {
        return this.enabled;
    }

    public int getValue()
    {
        return this.value;
    }

    public int getDefaultValue()
    {
        return this.defaultValue;
    }

    public String getValueName()
    {
        return this.getValueNames()[this.value];
    }

    /** Text shown by commands, e.g. "Efficient Furnaces: Optimized". */
    public String getDisplayString()
    {
        return this.title + ": " + this.getValueName() + (this.isAvailable() ? "" : " (unavailable)");
    }

    /** Button text for the settings screen, e.g. "Furnaces: Optimized". */
    public String getButtonString()
    {
        return this.buttonTitle + ": " + this.getValueName();
    }

    /**
     * Why the value can't be changed right now (in a world, say), or null if it can. setValue refuses
     * while this isn't null and says why in chat; the settings screen greys the row out.
     */
    public String getLockedMessage()
    {
        return null;
    }

    /** Sets the value, applies it to the running game and saves the config. */
    public void setValue(int value)
    {
        String var2 = value != this.value ? this.getLockedMessage() : null;

        if (var2 != null)
        {
            Minecraft var3 = ModLoader.getMinecraftInstance();

            if (var3 != null && var3.ingameGUI != null && var3.theWorld != null)
            {
                var3.ingameGUI.getChatGUI().printChatMessage("\u00a7c" + this.title + ": " + var2);
            }

            return;
        }

        if (this.applyValue(value))
        {
            mod_Kirmod.saveConfig();
        }
    }

    /**
     * Sets the value and applies it to the running game without saving; returns whether it changed.
     * Used while a slider is dragged, which saves once it is let go.
     */
    boolean applyValue(int value)
    {
        if (value >= 0 && value < this.getValueNames().length && value != this.value)
        {
            this.value = value;
            this.onValueChanged();
            return true;
        }

        return false;
    }

    /** Sets the value without applying or saving it; used when reading the config. */
    protected void loadValue(int value)
    {
        if (value >= 0 && value < this.getValueNames().length)
        {
            this.value = value;
        }
    }

    /** Moves to the next (direction 1) or previous (direction -1) value, wrapping around. */
    public void cycleValue(int direction)
    {
        int var2 = this.getValueNames().length;
        this.setValue(((this.value + direction) % var2 + var2) % var2);
    }

    public void resetValue()
    {
        this.setValue(this.defaultValue);
    }

    /** Finds a value by name (ignoring case, spaces and punctuation) or by index. Returns -1 if unknown. */
    public int findValue(String name)
    {
        String[] var2 = this.getValueNames();
        String var3 = KirUtil.normalize(name);

        for (int var4 = 0; var4 < var2.length; ++var4)
        {
            if (KirUtil.normalize(var2[var4]).equals(var3))
            {
                return var4;
            }
        }

        try
        {
            int var5 = Integer.parseInt(name.trim());
            return var5 >= 0 && var5 < var2.length ? var5 : -1;
        }
        catch (NumberFormatException var6)
        {
            return -1;
        }
    }

    /** Whether name refers to this module (its key, title or button title). */
    public boolean matches(String name)
    {
        String var2 = KirUtil.normalize(name);
        return KirUtil.normalize(this.key).equals(var2) || KirUtil.normalize(this.title).equals(var2) || KirUtil.normalize(this.buttonTitle).equals(var2);
    }
}
