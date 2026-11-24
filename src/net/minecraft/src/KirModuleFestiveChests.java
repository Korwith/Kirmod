package net.minecraft.src;

/**
 * Festive Chests: "On" wraps chests and ender chests as presents, in both Chest Models. The textures are
 * in kirmod/festive/ (see KirModuleChests.FESTIVE_DIR); this module is only the switch, which Chest
 * Models' renderers and Legacy tiles read.
 */
public class KirModuleFestiveChests extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    public KirModuleFestiveChests()
    {
        super("festiveChests", "Festive Chests", "Festive Chests", 0, "festivechests", "festive");
    }

    public String getCategory()
    {
        return CATEGORY_VISUALS;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Wraps chests and ender chests up as presents, all year round. Works with both Chest Models.",
            describeValue("Off", "chests look as in your texture pack."),
            describeValue("On", "red presents for chests, green ones for double chests and purple ones for ender chests."),
            note("Textures from Craemon's Christmas Chests All Year (MIT).")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    /** Needs Chest Models, whose blocks and renderers draw the textures. */
    public boolean isAvailable()
    {
        return mod_Kirmod.CHEST_MODELS.isEnabled() && mod_Kirmod.CHEST_MODELS.isAvailable();
    }

    /** True when chests use the festive textures. */
    public boolean isFestive()
    {
        return this.getValue() == 1 && this.enabled && this.isAvailable();
    }
}
