package net.minecraft.src;

/**
 * A Kirmod plugin: a group of modules shipped by another mod, with its own section in the sidebar of
 * the settings screen (like Sodium Extra under Sodium). Kirmod's own modules belong to KIRMOD.
 *
 * A plugin mod (Kirmod Extras, say) makes one KirPlugin, then from its load():
 *
 *   mod_Kirmod.registerPlugin(PLUGIN);
 *   mod_Kirmod.registerModule(PLUGIN, new MyModule());
 *   mod_Kirmod.registerModuleKey(new KeyBinding("My Key", Keyboard.KEY_J), myModule);
 *   mod_Kirmod.registerPluginKey(PLUGIN, null, MY_OWN_KEY, "What it does."); // a key the mod handles itself
 *
 * Both kinds of keys are listed in the settings screen's Hotkeys panel (Kirmod 1.28), where they can be
 * rebound. Module keys are watched by Kirmod itself, so they work under Forge as well as ModLoader.
 *
 * Its modules' getCategory() names the plugin's own tabs: two plugins can both have a "Gameplay" tab.
 * Values still live in config/Kirmod.cfg under each module's key, so a module can move between Kirmod
 * and a plugin without losing its setting. The plugin mod's getPriorities() should say
 * "after:mod_Kirmod".
 */
public class KirPlugin
{
    /** Kirmod itself; the first section of the sidebar. */
    public static final KirPlugin KIRMOD = new KirPlugin("Kirmod", "1.36", "Kirmod's own settings.", 331);

    private final String name;
    private final String version;
    private final String description;
    private final int iconItemId;
    private ItemStack icon;

    /**
     * @param name        shown as the sidebar section's heading
     * @param version     shown in its tooltip
     * @param description the heading's tooltip
     * @param iconItemId  item (or block) id drawn left of the heading, or -1 for none
     */
    public KirPlugin(String name, String version, String description, int iconItemId)
    {
        this.name = name;
        this.version = version;
        this.description = description;
        this.iconItemId = iconItemId;
    }

    public String getName()
    {
        return this.name;
    }

    public String getVersion()
    {
        return this.version;
    }

    public String getDescription()
    {
        return this.description;
    }

    /** The heading's icon, or null. */
    public ItemStack getIcon()
    {
        if (this.icon == null && this.iconItemId >= 0 && this.iconItemId < Item.itemsList.length && Item.itemsList[this.iconItemId] != null)
        {
            this.icon = new ItemStack(this.iconItemId, 1, 0);
        }

        return this.icon;
    }

    /**
     * The category whose tab holds category as a labelled group, or null if category is a tab of its
     * own. Plugins override this for subcategories of their own tabs (new in Kirmod 1.27); by default
     * it is Kirmod's (KirModule.getParentCategory), so a plugin's "World" group sits under "Gameplay".
     */
    public String getParentCategory(String category)
    {
        return KirModule.getParentCategory(category);
    }

    /** What one of this plugin's tabs is for (its tooltip), or null. */
    public String getCategoryDescription(String category)
    {
        return null;
    }

    public String toString()
    {
        return this.name;
    }
}
