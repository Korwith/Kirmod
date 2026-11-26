package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Rubies: "On" turns emeralds into rubies, client side only. The emerald item shows the unused ruby icon
 * that has been in vanilla gui/items.png since 1.3 (column 10, row 10) and is called "Ruby"; the block of
 * emerald gets a red tile (kirmod/ruby/ruby_block.png, made by tools/convert_ruby_blocks.py) and is called
 * "Ruby Block". With Legacy Storage Blocks on too, the block's Legacy tiles turn red as well.
 *
 * Nothing in the world or the save changes: the item keeps its id, so trading and recipes are untouched.
 * Since 1.37 emerald ore turns into Ruby Ore too (kirmod/ruby/ruby_ore.png, or ruby_ore_outline.png with
 * Ore Outlines on), drawn over the emerald ore tile by KirTerrainOverlay.
 *
 * The block uses a KirModuleStorageBlocks.KirBlockStorage (swapped in by Legacy Storage Blocks, or here
 * when that module is disabled), and its tile costs one terrain.png sprite, drawn by a TilesFX. The names
 * are written into StringTranslate's table, and again after a language change replaces the table.
 */
public class KirModuleRubies extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    public static final String TEXTURE_DIR = KirModuleChests.TEXTURE_DIR + "ruby/";

    /** The ruby in gui/items.png. */
    public static final int RUBY_ICON = 10 * 16 + 10;

    private static final String ITEM_KEY = "item.emerald.name";
    private static final String BLOCK_KEY = "tile.blockEmerald.name";
    private static final String ITEM_NAME = "Ruby";
    private static final String BLOCK_NAME = "Ruby Block";
    private static final String ORE_KEY = "tile.oreEmerald.name";
    private static final String ORE_NAME = "Ruby Ore";

    private static final List tiles = new ArrayList();
    private static final KirModuleChests.TilesFX tilesFX = new KirModuleChests.TilesFX(tiles, "Ruby Block");

    private boolean available = false;
    private int emeraldIcon = -1;

    /** The translation table the names were last written into, and what was there before. */
    private Properties namedTable;
    private String oldItemName;
    private String oldBlockName;
    private String oldOreName;
    private Field tableField;

    public KirModuleRubies()
    {
        super("rubies", "Rubies", "Rubies", 0, "rubies", "ruby");
    }

    public String getCategory()
    {
        return CATEGORY_VISUALS;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Turns emeralds into rubies: the ruby icon hidden in vanilla's items.png, a red Ruby Block and Ruby Ore. Only the look and the names change; trading and recipes work as before.",
            describeValue("Off", "emeralds and blocks of emerald as normal."),
            describeValue("On", "\"Ruby\" items, \"Ruby Block\" blocks and red Ruby Ore (outlined too with Ore Outlines on).")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return this.available;
    }

    /** True while emeralds are shown as rubies. */
    public boolean showRubies()
    {
        return this.getValue() == 1 && this.available && this.enabled;
    }

    protected void onValueChanged()
    {
        KirUtil.reloadRenderers();
    }

    public void install()
    {
        try
        {
            this.emeraldIcon = Item.emerald.iconIndex;
            Block var1 = Block.blocksList[Block.blockEmerald.blockID];

            if (!(var1 instanceof KirModuleStorageBlocks.KirBlockStorage))
            {
                // Legacy Storage Blocks is disabled: swap the block here, without Legacy tiles.
                KirModuleStorageBlocks.KirBlockStorage var2 = new KirModuleStorageBlocks.KirBlockStorage(var1.blockID, var1.blockIndexInTexture, -1, -1, -1);

                if (!KirUtil.replaceBlock(var1, var2, "blockEmerald", "bV"))
                {
                    var1 = null;
                }
                else
                {
                    var1 = var2;
                }
            }

            if (var1 != null)
            {
                int var3 = ModLoader.getUniqueSpriteIndex("/terrain.png");
                tiles.add(new Object[] {Integer.valueOf(var3), TEXTURE_DIR + "ruby_block.png", TEXTURE_DIR + "ruby_block.png"});
                ((KirModuleStorageBlocks.KirBlockStorage)var1).rubyTile = var3;
            }

            this.tableField = KirUtil.findField(StringTranslate.class, "translateTable", "b");
            this.available = true;
        }
        catch (Exception var4)
        {
            KirUtil.log("Could not install rubies", var4);
        }
    }

    public void update(Minecraft mc)
    {
        if (!this.available)
        {
            return;
        }

        boolean var2 = this.showRubies();
        int var3 = var2 ? RUBY_ICON : this.emeraldIcon;

        if (Item.emerald.iconIndex != var3)
        {
            Item.emerald.iconIndex = var3;
        }

        this.updateNames(var2);
    }

    /** Writes the ruby names into the current translation table, or puts the old ones back. */
    private void updateNames(boolean rubies)
    {
        if (this.tableField == null)
        {
            return;
        }

        Properties var2;

        try
        {
            var2 = (Properties)this.tableField.get(StringTranslate.getInstance());
        }
        catch (Exception var4)
        {
            KirUtil.log("Could not rename emeralds", var4);
            this.tableField = null;
            return;
        }

        if (var2 != this.namedTable)
        {
            // A new language: nothing of ours is in it.
            this.namedTable = null;
        }

        if (rubies && this.namedTable == null && var2 != null)
        {
            this.oldItemName = var2.getProperty(ITEM_KEY);
            this.oldBlockName = var2.getProperty(BLOCK_KEY);
            this.oldOreName = var2.getProperty(ORE_KEY);
            var2.setProperty(ITEM_KEY, ITEM_NAME);
            var2.setProperty(BLOCK_KEY, BLOCK_NAME);
            var2.setProperty(ORE_KEY, ORE_NAME);
            this.namedTable = var2;
        }
        else if (!rubies && this.namedTable != null)
        {
            restore(var2, ITEM_KEY, this.oldItemName);
            restore(var2, BLOCK_KEY, this.oldBlockName);
            restore(var2, ORE_KEY, this.oldOreName);
            this.namedTable = null;
        }
    }

    private static void restore(Properties table, String key, String value)
    {
        if (value == null)
        {
            table.remove(key);
        }
        else
        {
            table.setProperty(key, value);
        }
    }

    /** The Ruby Block tile animation, added through ModLoader.addAnimation(); see mod_Kirmod.registerAnimation(). */
    static TextureFX getTilesFX(Minecraft mc)
    {
        return tilesFX.readd(mc);
    }

    static boolean hasTiles()
    {
        return !tiles.isEmpty();
    }
}
