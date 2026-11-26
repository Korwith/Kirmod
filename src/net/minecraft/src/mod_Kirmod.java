package net.minecraft.src;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Kirmod: a collection of client tweaks configured from Options > Kirmod..., from chat commands
 * (through mod_CommandLib) or from .minecraft/config/Kirmod.cfg.
 *
 * Every feature is a KirModule in its own file; this class only keeps the list of modules and gives
 * each one its button, command and config entries. Modules with a category are in a submenu of the
 * settings screen; hovering over a button shows the module's tooltip (KirModule.getTooltip()):
 *   - KirModuleFurnaces        Efficient Furnaces (Performance)
 *   - KirModuleTileSuppressor  Tile Suppressor (Performance)
 *   - KirModuleSignTicking     Sign Ticking (Performance)
 *   - KirModuleTileEntityDistance  Tile Entity Render Distance (a slider, Performance)
 *   - KirModuleEntityDistance  Entity Render Distance (a slider, Performance)
 *   - KirModuleChests          Chest Models (Visuals; legacy chest textures live in kirmod/)
 *   - KirModuleFestiveChests   Festive Chests (Visuals; its textures live in kirmod/festive/)
 *   - KirModuleStorageBlocks   Legacy Storage Blocks (Visuals; its textures live in kirmod/storage/)
 *   - KirModuleStickyPistons   Sticky Piston Sides (Visuals; kirmod/sticky_piston_side.png)
 *   - KirModuleGrassColor      Global Grass Color (Visuals > Overworld)
 *   - KirModuleFoliageColor    Global Foliage Color (Visuals > Overworld; both built on KirModuleBiomeColor)
 *   - KirModuleBlockOutline    Block Outline (Visuals)
 *   - KirModuleHeldItemName    Held Item Name (Visuals; from InventoryManagerPlus)
 *   - KirModuleBetaLighting    Beta Lighting (Visuals > Overworld)
 *   - KirModuleEndSky          End Overworld Sky (Visuals > End Dimension)
 *   - KirModuleEndWeather      End Weather (Visuals > End Dimension)
 *   - KirModuleIsometric       Isometric Screenshots (Camera)
 *   - KirModuleIsometricDirection  Isometric Direction (Camera)
 *   - KirModuleIsometricDistance   Isometric Distance (a slider, Camera)
 *   - KirModulePanorama        Panorama Screenshots (Camera; from PanoramaAssist; all four use KirCamera)
 *   - KirModuleMusic           Music Frequency (Music)
 *   - KirModuleMusicPitch      Music Pitch (a slider, Music)
 *   - KirModuleMusicOption     Order, Sound Test, 0x10c, Music Discs, Nether and End Soundtrack (Music)
 *
 * The Music category (1.36) is not listed in the settings screen: its modules are set in the Music screen
 * (GuiKirmodMusic, the header's "Music..."), where the soundtrack is picked. KirMusic plays the music.
 *   - KirModuleRenderCrashGuard  Render Crash Guard (Other; from EndermanCrashFix)
 *   - KirModuleOptifineRenderFix OptiFine Render Fix (OptiFine Patches; KirPlayerManager sends chunks out to the render distance)
 *   - KirModuleMultiCoreChunks Multi-Core Chunks (OptiFine Patches; KirChunkBuilder, KirChunkCache, KirTessellator)
 *
 * KirRename adds /rename and /clearname (from InventoryManagerPlus).
 *
 * KirWorldHook and KirInteraction are the world-tick and click/pickup hooks several modules share;
 * KirRenderGlobal is the world renderer hook of Tile Entity Render Distance, Block Outline and Beta
 * Lighting (whose chunks KirWorldRenderer builds with KirRenderBlocks), and
 * KirCamera the view of Freecam and isometric screenshots.
 *
 * Plugins (KirPlugin) are other mods that add their own section of tabs to the settings screen's
 * sidebar, below Kirmod's: Kirmod Extras (mod_KirmodExtras) has every gameplay module (Kirmod's own
 * Gameplay tab moved there in 1.33), Minecart Speed and Freecam. Their modules use the same config file,
 * commands and hooks as Kirmod's own.
 * KirCapes (with its screen GuiKirmodCapes, Kirmod > Capes...) draws the cape the player picked, and
 * KirSkins (GuiKirmodSkins, Kirmod > Skins...) the skin.
 *
 * The module keys (those plugins add with registerModuleKey, such as Kirmod Extras' Instant XP Pickup K) are listed
 * in Options > Controls; each one switches its module and says so in chat. Kirmod watches them itself
 * (KirHotkey.poll), so they work under Forge too, whichever mod registered them. They and the keys
 * plugins handle themselves (registerPluginKey) are listed and rebound in the settings screen's Hotkeys
 * panel (1.28).
 *
 * Setting "KEY.enabled=false" in Kirmod.cfg switches a module off entirely: it is not installed, so
 * the blocks, renderers and hooks it would change stay vanilla (useful if another mod clashes with it).
 * Other mods can add their own modules with registerModule().
 *
 * Kirmod does not replace any base class, so it runs next to Forge and other jar mods.
 */
public class mod_Kirmod extends BaseMod
{
    public static final String CONFIG_FILE_NAME = "Kirmod.cfg";

    /*
     * Built-in modules, grouped by category: Performance, Visuals, Camera, OptiFine Patches, Interface, then Other. The registration
     * order below is the order of the buttons in GuiKirmod, and the submenu buttons are in order of each
     * category's first module.
     */
    public static final KirModuleFurnaces FURNACES = new KirModuleFurnaces();
    public static final KirModuleTileSuppressor TILE_SUPPRESSOR = new KirModuleTileSuppressor();
    public static final KirModuleSignTicking SIGN_TICKING = new KirModuleSignTicking();
    public static final KirModuleTileEntityDistance TILE_ENTITY_DISTANCE = new KirModuleTileEntityDistance();
    public static final KirModuleEntityDistance ENTITY_DISTANCE = new KirModuleEntityDistance();
    public static final KirModuleChests CHEST_MODELS = new KirModuleChests();
    public static final KirModuleFestiveChests FESTIVE_CHESTS = new KirModuleFestiveChests();
    public static final KirModuleStorageBlocks STORAGE_BLOCKS = new KirModuleStorageBlocks();
    public static final KirModuleRubies RUBIES = new KirModuleRubies();
    public static final KirModuleStickyPistons STICKY_PISTONS = new KirModuleStickyPistons();
    public static final KirModuleTexture ORE_OUTLINES = KirModuleTexture.oreOutlines();
    public static final KirModuleTexture GRASS_TEXTURE = KirModuleTexture.grass();
    public static final KirModuleTexture COBBLESTONE_TEXTURE = KirModuleTexture.cobblestone();
    public static final KirModuleTexture BRICKS_TEXTURE = KirModuleTexture.bricks();
    public static final KirModuleTexture MOB_SPAWNER_TEXTURE = KirModuleTexture.mobSpawner();
    public static final KirModuleTexture WOOL_TEXTURE = KirModuleTexture.wool();
    public static final KirModuleTexture END_STONE_TEXTURE = KirModuleTexture.endStone();
    public static final KirModuleTexture GRAVEL_TEXTURE = KirModuleTexture.gravel();
    public static final KirModuleTexture GLOWSTONE_TEXTURE = KirModuleTexture.glowstone();
    public static final KirModuleGrassColor GRASS_COLOR = new KirModuleGrassColor();
    public static final KirModuleFoliageColor FOLIAGE_COLOR = new KirModuleFoliageColor();
    public static final KirModuleBlockOutline BLOCK_OUTLINE = new KirModuleBlockOutline();
    public static final KirModuleBetaLighting BETA_LIGHTING = new KirModuleBetaLighting();
    public static final KirModuleHeldItemName HELD_ITEM_NAME = new KirModuleHeldItemName();
    public static final KirModuleEndSky END_SKY = new KirModuleEndSky();
    public static final KirModuleEndWeather END_WEATHER = new KirModuleEndWeather();
    public static final KirModuleIsometric ISOMETRIC = new KirModuleIsometric();
    public static final KirModuleIsometricDirection ISOMETRIC_DIRECTION = new KirModuleIsometricDirection();
    public static final KirModuleIsometricDistance ISOMETRIC_DISTANCE = new KirModuleIsometricDistance();
    public static final KirModulePanorama PANORAMA = new KirModulePanorama();
    public static final KirModuleMenuDarkness MENU_DARKNESS = new KirModuleMenuDarkness();
    public static final KirModuleMenuBlur MENU_BLUR = new KirModuleMenuBlur();
    public static final KirModuleDarkTheme DARK_THEME = new KirModuleDarkTheme();
    public static final KirModuleDarkThemeTint DARK_THEME_TINT = new KirModuleDarkThemeTint();
    public static final KirModuleMusic MUSIC_FREQUENCY = new KirModuleMusic();
    public static final KirModuleMusicPitch MUSIC_PITCH = new KirModuleMusicPitch();
    public static final KirModuleMusicOption MUSIC_ORDER = KirModuleMusicOption.order();
    public static final KirModuleMusicOption MUSIC_SOUND_TEST = KirModuleMusicOption.soundTest();
    public static final KirModuleMusicOption MUSIC_0X10C = KirModuleMusicOption.x10c();
    public static final KirModuleMusicOption MUSIC_DISCS = KirModuleMusicOption.discs();
    public static final KirModuleMusicOption MUSIC_NETHER = KirModuleMusicOption.nether();
    public static final KirModuleMusicOption MUSIC_END = KirModuleMusicOption.end();
    public static final KirModuleRenderCrashGuard RENDER_CRASH_GUARD = new KirModuleRenderCrashGuard();
    public static final KirModuleIntegratedServer INTEGRATED_SERVER = new KirModuleIntegratedServer();
    public static final KirModuleOptifineRenderFix OPTIFINE_RENDER_FIX = new KirModuleOptifineRenderFix();
    public static final KirModuleMultiCoreChunks MULTICORE_CHUNKS = new KirModuleMultiCoreChunks();

    private static final List modules = new ArrayList();
    private static final Properties config = new Properties();
    private static boolean loaded = false;
    private static GuiScreen screenToOpen;

    /** The sidebar sections, Kirmod's first. */
    private static final List plugins = new ArrayList();

    /** Module keys waiting for load() to register them with ModLoader (see registerModuleKey). */
    private static final List KEYS = new ArrayList();

    /** The loaded mod, which ModLoader sends the module keys to; null before load(). */
    private static mod_Kirmod instance;

    static
    {
        plugins.add(KirPlugin.KIRMOD);
        registerModule(FURNACES);
        registerModule(TILE_SUPPRESSOR);
        registerModule(SIGN_TICKING);
        registerModule(TILE_ENTITY_DISTANCE);
        registerModule(ENTITY_DISTANCE);
        registerModule(CHEST_MODELS);
        registerModule(FESTIVE_CHESTS);
        registerModule(STORAGE_BLOCKS);
        registerModule(RUBIES);
        registerModule(STICKY_PISTONS);
        registerModule(ORE_OUTLINES);
        registerModule(GRASS_TEXTURE);
        registerModule(COBBLESTONE_TEXTURE);
        registerModule(BRICKS_TEXTURE);
        registerModule(MOB_SPAWNER_TEXTURE);
        registerModule(WOOL_TEXTURE);
        registerModule(END_STONE_TEXTURE);
        registerModule(GRAVEL_TEXTURE);
        registerModule(GLOWSTONE_TEXTURE);
        registerModule(GRASS_COLOR);
        registerModule(FOLIAGE_COLOR);
        registerModule(BLOCK_OUTLINE);
        registerModule(HELD_ITEM_NAME);
        registerModule(BETA_LIGHTING);
        registerModule(END_SKY);
        registerModule(END_WEATHER);
        registerModule(ISOMETRIC);
        registerModule(ISOMETRIC_DIRECTION);
        registerModule(ISOMETRIC_DISTANCE);
        registerModule(PANORAMA);
        registerModule(OPTIFINE_RENDER_FIX);
        registerModule(MULTICORE_CHUNKS);
        registerModule(MENU_DARKNESS);
        registerModule(MENU_BLUR);
        registerModule(DARK_THEME);
        registerModule(DARK_THEME_TINT);
        registerModule(MUSIC_FREQUENCY);
        registerModule(MUSIC_PITCH);
        registerModule(MUSIC_ORDER);
        registerModule(MUSIC_SOUND_TEST);
        registerModule(MUSIC_0X10C);
        registerModule(MUSIC_DISCS);
        registerModule(MUSIC_NETHER);
        registerModule(MUSIC_END);
        registerModule(RENDER_CRASH_GUARD);
        registerModule(INTEGRATED_SERVER);
    }

    public String getVersion()
    {
        return "1.36 for 1.3.2";
    }

    public String getPriorities()
    {
        return "after:mod_CommandLib";
    }

    public void load()
    {
        loadConfig();
        loaded = true;
        Iterator var1 = modules.iterator();

        while (var1.hasNext())
        {
            startModule((KirModule)var1.next());
        }

        // Writes the defaults, including the KEY.enabled switches, on first start.
        saveConfig();
        if (!KirEdition.LITE)
        {
            KirSkins.load();
            KirCapes.load();
        }

        ModLoader.setInGameHook(this, true, false);
        ModLoader.setInGUIHook(this, true, false);

        instance = this;

        for (int var2 = 0; var2 < KEYS.size(); ++var2)
        {
            registerKeyWithModLoader((KeyBinding)KEYS.get(var2));
        }

        KEYS.clear();

        registerMainCommand();
        KirRename.registerCommands();

        if (!KirEdition.LITE)
        {
            KirCapes.registerCommand();
            KirSkins.registerCommand();
            KirTexturePacks.registerCommand();
        }
    }

    /**
     * Adds the texture pack hook, an animation that ticks KirTexturePacks even at the main menu, and the
     * ones that draw the Legacy chest, storage block and sticky piston tiles. ModLoader calls this again whenever the
     * texture pack changes.
     */
    public void registerAnimation(Minecraft mc)
    {
        ModLoader.addAnimation(KirTexturePacks.getTickFX(mc));

        // The earliest point the menus can be darkened at startup, and again after a pack change (1.37).
        KirDarkMenus.reapply(mc);

        if (CHEST_MODELS.isEnabled() && KirModuleChests.chestTop >= 0)
        {
            ModLoader.addAnimation(KirModuleChests.getTilesFX(mc));
        }

        if (STORAGE_BLOCKS.isEnabled() && KirModuleStorageBlocks.hasTiles())
        {
            ModLoader.addAnimation(KirModuleStorageBlocks.getTilesFX(mc));
        }

        if (RUBIES.isEnabled() && KirModuleRubies.hasTiles())
        {
            ModLoader.addAnimation(KirModuleRubies.getTilesFX(mc));
        }

        if (STICKY_PISTONS.isEnabled() && KirModuleStickyPistons.hasTiles())
        {
            ModLoader.addAnimation(KirModuleStickyPistons.getTilesFX(mc));
        }

        // The Textures group and Ruby Ore, drawn over vanilla's own tiles (1.37).
        if (KirModuleTexture.anyEnabled())
        {
            ModLoader.addAnimation(KirTerrainOverlay.get(mc));
        }
    }

    /**
     * Module keys are not handled here: KirHotkey.poll watches them every frame. ModLoader would only send
     * Kirmod the keys registered while Kirmod itself was loading (under Forge), not a plugin's.
     */
    public void keyboardEvent(KeyBinding key)
    {
    }

    /** Draws the player's own entity with KirSkins' renderer, which handles 64x64 skins. */
    public void addRenderer(Map renderers)
    {
        renderers.put(EntityClientPlayerMP.class, new KirSkins.PlayerRenderer());
    }

    public boolean onTickInGame(float partialTicks, Minecraft mc)
    {
        // Commands run while GuiChat is still open and GuiChat closes itself afterwards, so the
        // settings screen is opened once the chat is gone.
        if (screenToOpen != null && mc.currentScreen == null)
        {
            mc.displayGuiScreen(screenToOpen);
            screenToOpen = null;
        }

        KirHotkey.poll(mc);
        updateModules(mc);
        return true;
    }

    public boolean onTickInGUI(float partialTicks, Minecraft mc, GuiScreen screen)
    {
        if (screen != null && screen.getClass() == GuiOptions.class)
        {
            GuiKirmodOptions.replace(mc, (GuiOptions)screen);
        }

        // Only keeps track of held keys here; a key held as a screen closes doesn't fire.
        KirHotkey.poll(mc);
        updateModules(mc);
        return true;
    }

    private static void updateModules(Minecraft mc)
    {
        Iterator var1 = modules.iterator();

        while (var1.hasNext())
        {
            KirModule var2 = (KirModule)var1.next();

            if (var2.enabled)
            {
                var2.update(mc);
            }
        }

        KirInteraction.showClientMessages(mc);

        if (!KirEdition.LITE)
        {
            KirCapes.update(mc);
            KirSkins.update(mc);
        }

        KirTexturePacks.update(mc);

        try
        {
            KirMusic.update(mc);
        }
        catch (Throwable var3)
        {
            KirUtil.log("Music player failed", var3);
        }
    }

    /** Opens a screen once chat is closed; for commands, which run while GuiChat is still open. */
    static void openScreenNextTick(GuiScreen screen)
    {
        screenToOpen = screen;
    }

    /* ------------------------------------------------------------------ Modules */

    /**
     * Adds a module. Modules registered before Kirmod loads are started from load(); ones registered
     * later (from another mod's load(), say) are started straight away.
     */
    public static void registerModule(KirModule module)
    {
        registerModule(KirPlugin.KIRMOD, module);
    }

    /** Adds a module to a plugin's section of the sidebar (see KirPlugin); registers the plugin if needed. */
    public static void registerModule(KirPlugin plugin, KirModule module)
    {
        if (findModule(module.getKey()) != null)
        {
            KirUtil.log("Module '" + module.getKey() + "' registered twice, ignoring the second one", (Throwable)null);
            return;
        }

        registerPlugin(plugin);
        module.plugin = plugin;
        modules.add(module);

        if (loaded)
        {
            startModule(module);
            saveConfig();
        }
    }

    /** Adds a plugin's section to the sidebar, below the ones already there. Registering it again does nothing. */
    public static void registerPlugin(KirPlugin plugin)
    {
        if (!plugins.contains(plugin))
        {
            plugins.add(plugin);
            KirUtil.log("Plugin " + plugin.getName() + " " + plugin.getVersion() + " registered", (Throwable)null);
        }
    }

    /** Every registered plugin, Kirmod first, in sidebar order. */
    public static List getPlugins()
    {
        return Collections.unmodifiableList(plugins);
    }

    /**
     * Adds a key to Options > Controls and to the Hotkeys panel that switches module to its next value and
     * says so in chat, while no screen is open. A module switched off in the config gets no key. Kirmod
     * watches the key itself (KirHotkey.poll), so it works under ModLoader and Forge alike.
     */
    public static void registerModuleKey(KeyBinding key, KirModule module)
    {
        KirHotkey.addModuleKey(key, module);

        if (!module.isEnabled() || !modules.contains(module))
        {
            return;
        }

        if (instance != null)
        {
            registerKeyWithModLoader(key);
        }
        else
        {
            KEYS.add(key);
        }
    }

    /**
     * Lists a key the plugin's own mod registered (ModLoader.registerKey) and handles in Kirmod's Hotkeys
     * panel, where it can be seen and rebound. Kirmod does nothing when it is pressed. group (or null) puts
     * it in a labelled group under the plugin's name; description is its tooltip. New in Kirmod 1.28.
     */
    public static void registerPluginKey(KirPlugin plugin, String group, KeyBinding key, String description)
    {
        registerPluginKey(plugin, group, key, description, 0);
    }

    /**
     * The same for a key pressed while another is held (modifier, an LWJGL key code), such as Debug Overlays'
     * F3 + G. The panel shows it as "F3 + G" and rebinds only the second key. It clashes only with other combos
     * on the same keys, and while the modifier is held, Kirmod's module keys on the same key don't fire. Such a
     * key is usually not one of the game's KeyBindings (not in Controls), so the plugin saves it itself. New in
     * Kirmod 1.31.
     */
    public static void registerPluginKey(KirPlugin plugin, String group, KeyBinding key, String description, int modifier)
    {
        registerPlugin(plugin);
        KirHotkey.addPluginKey(plugin, group, key, description, modifier);
    }

    /**
     * Only so the key is in Options > Controls and options.txt: under Forge its key events go to whichever
     * mod is loading now, and Kirmod's own keyboardEvent ignores them either way.
     */
    private static void registerKeyWithModLoader(KeyBinding key)
    {
        ModLoader.registerKey(instance, key, false);
    }

    /** Reads the module's config entries, then installs it and registers its command if it is enabled. */
    private static void startModule(KirModule module)
    {
        module.enabled = !"false".equalsIgnoreCase(config.getProperty(module.getKey() + ".enabled", "true").trim());
        String var1 = config.getProperty(module.getKey());

        if (var1 != null)
        {
            module.loadValue(module.findValue(var1));
        }

        if (!module.enabled)
        {
            KirUtil.log(module.getTitle() + " is switched off in " + CONFIG_FILE_NAME, (Throwable)null);
            return;
        }

        try
        {
            module.install();
        }
        catch (Throwable var3)
        {
            KirUtil.log("Could not install " + module.getTitle(), var3);
        }

        if (module.getCommandName() != null)
        {
            registerModuleCommand(module);
        }
    }

    /** Every registered module, enabled or not, in button order. */
    public static List getModules()
    {
        return Collections.unmodifiableList(modules);
    }

    /** The modules shown in the settings screen. */
    public static List getEnabledModules()
    {
        ArrayList var0 = new ArrayList();
        Iterator var1 = modules.iterator();

        while (var1.hasNext())
        {
            KirModule var2 = (KirModule)var1.next();

            if (var2.enabled)
            {
                var0.add(var2);
            }
        }

        return var0;
    }

    /** The enabled modules of one of Kirmod's own categories (null for the main screen), in button order. */
    public static List getEnabledModules(String category)
    {
        return getEnabledModules(KirPlugin.KIRMOD, category);
    }

    /** The enabled modules of one of a plugin's categories, in button order. */
    public static List getEnabledModules(KirPlugin plugin, String category)
    {
        ArrayList var1 = new ArrayList();
        Iterator var2 = getEnabledModules().iterator();

        while (var2.hasNext())
        {
            KirModule var3 = (KirModule)var2.next();

            if (var3.plugin == plugin && (category == null ? var3.getCategory() == null : category.equals(var3.getCategory())))
            {
                var1.add(var3);
            }
        }

        return var1;
    }

    /**
     * The categories with a button on the screen of category parent (null for the main screen): those
     * with at least one enabled module in them or in a category under them, in order of their first module.
     */
    public static List getCategories(String parent)
    {
        return getCategories(KirPlugin.KIRMOD, parent);
    }

    /** Like getCategories(String), for one plugin's section of the sidebar. */
    public static List getCategories(KirPlugin plugin, String parent)
    {
        ArrayList var1 = new ArrayList();
        Iterator var2 = getEnabledModules().iterator();

        while (var2.hasNext())
        {
            KirModule var6 = (KirModule)var2.next();

            if (var6.plugin != plugin)
            {
                continue;
            }

            String var3 = var6.getCategory();

            // The Music screen has these (1.36), not the settings screen.
            if (KirModule.CATEGORY_MUSIC.equals(var3))
            {
                continue;
            }

            // Walks up from the module's category: the top one for the sidebar, else the one right under parent.
            String var5 = null;
            String var4 = var3;
            int var7 = 0;

            while (var4 != null && var7++ < 16)
            {
                String var8 = plugin.getParentCategory(var4);

                if (parent == null ? var8 == null : parent.equals(var8))
                {
                    var5 = var4;
                    break;
                }

                var4 = var8;
            }

            if (var5 != null && !var1.contains(var5))
            {
                var1.add(var5);
            }
        }

        return var1;
    }

    /** Finds a module by key or title, ignoring case, spaces and punctuation. Returns null if unknown. */
    public static KirModule findModule(String name)
    {
        Iterator var1 = modules.iterator();

        while (var1.hasNext())
        {
            KirModule var2 = (KirModule)var1.next();

            if (var2.matches(name))
            {
                return var2;
            }
        }

        return null;
    }

    /** The registered module with this exact config key (from any plugin), or null. Cheap enough per frame. */
    public static KirModule getModule(String key)
    {
        for (int var1 = 0; var1 < modules.size(); ++var1)
        {
            KirModule var2 = (KirModule)modules.get(var1);

            if (var2.getKey().equals(key))
            {
                return var2;
            }
        }

        return null;
    }

    /* ------------------------------------------------------------------ Server hooks for modules */

    /** Runs every enabled module's onServerWorldTick; from KirWorldHook, on the server thread. */
    static void tickServerWorld(World world)
    {
        for (int var1 = 0; var1 < modules.size(); ++var1)
        {
            KirModule var2 = (KirModule)modules.get(var1);

            if (var2.enabled)
            {
                try
                {
                    var2.onServerWorldTick(world);
                }
                catch (RuntimeException var4)
                {
                    KirUtil.log(var2.getTitle() + " failed in the world tick", var4);
                }
            }
        }
    }

    /** Runs every enabled module's onServerWorldTickEnd; from KirWorldHook, on the server thread. */
    static void tickServerWorldEnd(World world)
    {
        for (int var1 = 0; var1 < modules.size(); ++var1)
        {
            KirModule var2 = (KirModule)modules.get(var1);

            if (var2.enabled)
            {
                try
                {
                    var2.onServerWorldTickEnd(world);
                }
                catch (RuntimeException var4)
                {
                    KirUtil.log(var2.getTitle() + " failed in the world tick", var4);
                }
            }
        }
    }

    /** Whether any enabled module wants onServerPlayerTick. */
    /** Whether an enabled module wants this right-click to use the item instead of activating the block (KirModule.usesItemInsteadOfActivating). */
    public static boolean usesItemInsteadOfActivating(EntityPlayer player, ItemStack stack)
    {
        for (int var2 = 0; var2 < modules.size(); ++var2)
        {
            KirModule var3 = (KirModule)modules.get(var2);

            if (var3.enabled && var3.usesItemInsteadOfActivating(player, stack))
            {
                return true;
            }
        }

        return false;
    }

    /** Asks the enabled modules about a right-click on a block (KirModule.onRightClickBlock); true if one dealt with it. */
    static boolean handleRightClickBlock(Minecraft mc, EntityPlayer player, World world, ItemStack stack, int x, int y, int z, int side)
    {
        for (int var8 = 0; var8 < modules.size(); ++var8)
        {
            KirModule var9 = (KirModule)modules.get(var8);

            if (var9.enabled && var9.onRightClickBlock(mc, player, world, stack, x, y, z, side))
            {
                return true;
            }
        }

        return false;
    }

    /** Lets the enabled modules add to the host player's saved data (KirModule.onSaveHostPlayer). */
    static void saveHostPlayer(EntityPlayer player, NBTTagCompound tag)
    {
        for (int var2 = 0; var2 < modules.size(); ++var2)
        {
            KirModule var3 = (KirModule)modules.get(var2);

            if (var3.enabled)
            {
                try
                {
                    var3.onSaveHostPlayer(player, tag);
                }
                catch (RuntimeException var5)
                {
                    KirUtil.log(var3.getTitle() + " failed saving the player", var5);
                }
            }
        }
    }

    static boolean hasServerPlayerTicks()
    {
        for (int var0 = 0; var0 < modules.size(); ++var0)
        {
            KirModule var1 = (KirModule)modules.get(var0);

            if (var1.enabled && var1.hasServerPlayerTick())
            {
                return true;
            }
        }

        return false;
    }

    /** True while a module's onWindowClick runs; its own clicks then go straight through. */
    private static boolean inWindowClick = false;

    /** Asks the enabled modules about an inventory click (KirModule.onWindowClick); true if one did it. */
    static boolean handleWindowClick(Minecraft mc, int windowId, int slot, int button, boolean shift, EntityPlayer player)
    {
        if (inWindowClick)
        {
            return false;
        }

        inWindowClick = true;

        try
        {
            for (int var6 = 0; var6 < modules.size(); ++var6)
            {
                KirModule var7 = (KirModule)modules.get(var6);

                if (var7.enabled && var7.onWindowClick(mc, windowId, slot, button, shift, player))
                {
                    return true;
                }
            }
        }
        catch (RuntimeException var8)
        {
            KirUtil.log("An inventory click hook failed", var8);
        }
        finally
        {
            inWindowClick = false;
        }

        return false;
    }

    /** Runs onServerPlayerTick of the enabled modules that want it, for the host player. */
    static void tickServerPlayer(EntityPlayer player)
    {
        for (int var1 = 0; var1 < modules.size(); ++var1)
        {
            KirModule var2 = (KirModule)modules.get(var1);

            if (var2.enabled && var2.hasServerPlayerTick())
            {
                var2.onServerPlayerTick(player);
            }
        }
    }

    public static void resetAll()
    {
        Iterator var0 = getEnabledModules().iterator();

        while (var0.hasNext())
        {
            ((KirModule)var0.next()).resetValue();
        }
    }

    /* ------------------------------------------------------------------ Options screen */

    /** The vanilla options screen plus a "Kirmod..." button in the free slot next to Snooper Settings. */
    public static class GuiKirmodOptions extends GuiOptions
    {
        private static final java.lang.reflect.Field fieldParent = KirUtil.findField(GuiOptions.class, "parentScreen", "c");

        public GuiKirmodOptions(GuiScreen par1GuiScreen, GameSettings par2GameSettings)
        {
            super(par1GuiScreen, par2GameSettings);
        }

        /** Swaps an open vanilla options screen for this one, keeping the screen it returns to. */
        static void replace(Minecraft mc, GuiOptions screen)
        {
            if (fieldParent != null)
            {
                try
                {
                    mc.displayGuiScreen(new GuiKirmodOptions((GuiScreen)fieldParent.get(screen), mc.gameSettings));
                }
                catch (Exception var3)
                {
                    KirUtil.log("Could not add the Kirmod button to the options screen", var3);
                }
            }
        }

        public void initGui()
        {
            super.initGui();
            this.controlList.add(new GuiButton(105, this.width / 2 - 152, this.height / 6 + 144 - 6, 150, 20, "Kirmod..."));
        }

        protected void actionPerformed(GuiButton par1GuiButton)
        {
            if (par1GuiButton.enabled && par1GuiButton.id == 105)
            {
                this.mc.gameSettings.saveOptions();
                this.mc.displayGuiScreen(new GuiKirmod(this));
            }
            else
            {
                super.actionPerformed(par1GuiButton);
            }
        }
    }

    /* ------------------------------------------------------------------ Config */

    private static File getConfigFile()
    {
        return new File(Minecraft.getMinecraftDir(), "config/" + CONFIG_FILE_NAME);
    }

    private static void loadConfig()
    {
        File var0 = getConfigFile();

        if (!var0.exists())
        {
            return;
        }

        try
        {
            FileInputStream var1 = new FileInputStream(var0);

            try
            {
                config.load(var1);
            }
            finally
            {
                var1.close();
            }
        }
        catch (IOException var6)
        {
            KirUtil.log("Could not read " + var0, var6);
        }
    }

    /** A Kirmod.cfg entry that isn't a module value, such as the chosen cape. */
    public static String getSetting(String key, String defaultValue)
    {
        return config.getProperty(key, defaultValue);
    }

    /** Sets a Kirmod.cfg entry that isn't a module value, without saving yet (the next saveConfig() writes it). */
    public static void setSettingQuietly(String key, String value)
    {
        config.setProperty(key, value);
    }

    /** Sets and saves a Kirmod.cfg entry that isn't a module value. */
    public static void setSetting(String key, String value)
    {
        config.setProperty(key, value);
        saveConfig();
    }

    /** Writes every module's value and enabled switch. Entries of modules not loaded right now are kept. */
    public static void saveConfig()
    {
        if (!loaded)
        {
            return;
        }

        Iterator var0 = modules.iterator();

        while (var0.hasNext())
        {
            KirModule var1 = (KirModule)var0.next();
            config.setProperty(var1.getKey(), var1.getValueName());
            config.setProperty(var1.getKey() + ".enabled", String.valueOf(var1.enabled));
        }

        File var2 = getConfigFile();

        try
        {
            var2.getParentFile().mkdirs();
            FileOutputStream var3 = new FileOutputStream(var2);

            try
            {
                config.store(var3, "Kirmod settings. KEY.enabled=false switches a module off completely (takes effect on restart).");
            }
            finally
            {
                var3.close();
            }
        }
        catch (IOException var8)
        {
            KirUtil.log("Could not write " + var2, var8);
        }
    }

    /* ------------------------------------------------------------------ Commands */

    private static void registerMainCommand()
    {
        mod_CommandLib.registerCommand(new ClientCommand("kirmod", "[gui | list | get <option> | set <option> <value> | reset [option]]", "Kirmod settings", "km")
        {
            public void execute(String[] args)
            {
                String var2 = args.length > 0 ? args[0].toLowerCase() : "list";

                if (var2.equals("gui"))
                {
                    mod_Kirmod.openScreenNextTick(new GuiKirmod((GuiScreen)null));
                }
                else if (var2.equals("list"))
                {
                    this.reply("§eKirmod settings:");
                    Iterator var3 = mod_Kirmod.modules.iterator();

                    while (var3.hasNext())
                    {
                        KirModule var4 = (KirModule)var3.next();
                        this.reply(var4.getKey() + ": " + (var4.enabled ? "§a" + var4.getValueName() : "§8switched off in " + CONFIG_FILE_NAME));
                    }
                }
                else if (var2.equals("get") && args.length == 2)
                {
                    showOption(this, parseModule(args[1]));
                }
                else if (var2.equals("set") && args.length >= 3)
                {
                    setFromArgs(this, parseModule(args[1]), args, 2);
                }
                else if (var2.equals("reset") && args.length <= 2)
                {
                    if (args.length == 2)
                    {
                        KirModule var5 = parseModule(args[1]);
                        var5.resetValue();
                        this.reply(var5.getDisplayString());
                    }
                    else
                    {
                        resetAll();
                        this.reply("All Kirmod settings reset to their defaults.");
                    }
                }
                else
                {
                    this.failUsage();
                }
            }
        });
    }

    /** One command per module: no argument shows it, an argument sets it. */
    private static void registerModuleCommand(final KirModule module)
    {
        mod_CommandLib.registerCommand(new ClientCommand(module.getCommandName(), "[value]", "Shows or sets " + module.getTitle(), module.getCommandAliases())
        {
            public void execute(String[] args)
            {
                if (args.length == 0)
                {
                    showOption(this, module);
                }
                else
                {
                    setFromArgs(this, module, args, 0);
                }
            }
        });
    }

    private static KirModule parseModule(String name)
    {
        KirModule var1 = findModule(name);

        if (var1 == null || !var1.enabled)
        {
            StringBuilder var2 = new StringBuilder();
            Iterator var3 = getEnabledModules().iterator();

            while (var3.hasNext())
            {
                var2.append(var2.length() > 0 ? ", " : "").append(((KirModule)var3.next()).getKey());
            }

            throw new ClientCommandException((var1 == null ? "Unknown option '" + name + "'" : var1.getTitle() + " is switched off in " + CONFIG_FILE_NAME) + ". Options: " + var2);
        }

        return var1;
    }

    /** Sets a module from args[from..], joined with spaces so multi-word values like biome names work. */
    private static void setFromArgs(ClientCommand command, KirModule module, String[] args, int from)
    {
        StringBuilder var4 = new StringBuilder();

        for (int var5 = from; var5 < args.length; ++var5)
        {
            var4.append(var5 > from ? " " : "").append(args[var5]);
        }

        int var6 = module.findValue(var4.toString());

        if (var6 < 0)
        {
            throw new ClientCommandException("Unknown value '" + var4 + "' for " + module.getTitle() + ". Values: " + joinValues(module));
        }

        module.setValue(var6);
        command.reply(module.getDisplayString());
    }

    private static void showOption(ClientCommand command, KirModule module)
    {
        command.reply(module.getDisplayString());
        command.reply("§7Values: " + joinValues(module));
    }

    private static String joinValues(KirModule module)
    {
        String[] var1 = module.getValueNames();
        StringBuilder var2 = new StringBuilder();

        for (int var3 = 0; var3 < var1.length; ++var3)
        {
            var2.append(var3 > 0 ? ", " : "").append(var1[var3]);
        }

        return var2.toString();
    }
}
