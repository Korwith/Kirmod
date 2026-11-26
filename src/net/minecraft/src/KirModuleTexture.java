package net.minecraft.src;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A block texture choice of the Textures group (General > Textures, 1.37): the texture pack's tiles, or
 * older ones from Kirpack, bundled in kirmod/. Each module names the terrain.png tiles it replaces and the
 * image for each; KirTerrainOverlay draws them over the pack's tiles in place, so a change shows straight
 * away and no block is swapped. Emerald ore is KirTerrainOverlay's own case (Ore Outlines and Rubies).
 */
public class KirModuleTexture extends KirModule
{
    public static final String ORES_DIR = KirModuleChests.TEXTURE_DIR + "ores/";
    public static final String LEGACY_DIR = KirModuleChests.TEXTURE_DIR + "legacy/";

    /** Every texture module created, for getImage. */
    private static final List all = new ArrayList();

    private final String[] values;
    private final int[] tiles;
    private final String[] images;
    private final String[] tooltip;

    /**
     * @param values  "Normal" then the name of the replacement, e.g. {"Normal", "Beta"}
     * @param tiles   the terrain.png tiles replaced
     * @param images  the image for each tile, under kirmod/
     */
    KirModuleTexture(String key, String title, String buttonTitle, String[] values, String commandName, int[] tiles, String[] images, String[] tooltip)
    {
        super(key, title, buttonTitle, 0, commandName);
        this.values = values;
        this.tiles = tiles;
        this.images = images;
        this.tooltip = tooltip;
        all.add(this);
    }

    public String getCategory()
    {
        return CATEGORY_TEXTURES;
    }

    public String[] getValueNames()
    {
        return this.values;
    }

    public String[] getTooltip()
    {
        return this.tooltip;
    }

    /** True while the replacement tiles are drawn. */
    public boolean isOn()
    {
        return this.enabled && this.getValue() == 1;
    }

    /** The image an enabled texture module draws over a tile right now, or null. */
    static String getImage(int tile)
    {
        Iterator var1 = all.iterator();

        while (var1.hasNext())
        {
            KirModuleTexture var2 = (KirModuleTexture)var1.next();

            if (var2.isOn())
            {
                for (int var3 = 0; var3 < var2.tiles.length; ++var3)
                {
                    if (var2.tiles[var3] == tile)
                    {
                        return var2.images[var3];
                    }
                }
            }
        }

        return null;
    }

    /** True if any texture module (or Rubies, for the ruby ore) is installed, so KirTerrainOverlay is needed. */
    static boolean anyEnabled()
    {
        Iterator var0 = all.iterator();

        while (var0.hasNext())
        {
            if (((KirModuleTexture)var0.next()).enabled)
            {
                return true;
            }
        }

        return mod_Kirmod.RUBIES.isEnabled();
    }

    private static String[] paths(String dir, String[] names)
    {
        String[] var2 = new String[names.length];

        for (int var3 = 0; var3 < names.length; ++var3)
        {
            var2[var3] = dir + names[var3] + ".png";
        }

        return var2;
    }

    private static String[] tooltip(String what, String off, String on, String value)
    {
        return new String[] {what, describeValue("Normal", off), describeValue(value, on), note("Replaces your texture pack's tiles while not Normal.")};
    }

    static KirModuleTexture oreOutlines()
    {
        return new KirModuleTexture("oreOutlines", "Ore Outlines", "Ore Outlines", new String[] {"Off", "On"}, "oreoutlines",
            new int[] {32, 33, 34, 50, 51, 160},
            paths(ORES_DIR, new String[] {"gold_outline", "iron_outline", "coal_outline", "diamond_outline", "redstone_outline", "lapis_outline"}),
            new String[] {
                "Gives every ore a border in the color of what it drops, as in Kirpack, so ores stand out from stone.",
                describeValue("Off", "ores as in your texture pack."),
                describeValue("On", "coal, iron, gold, redstone, lapis, diamond and emerald ore get outlines (ruby ore too, with Rubies on)."),
                note("Replaces your texture pack's ore tiles while On.")
            });
    }

    static KirModuleTexture grass()
    {
        return new KirModuleTexture("grassTexture", "Grass Texture", "Grass", new String[] {"Normal", "Beta"}, "grasstexture",
            new int[] {0}, new String[] {KirModuleChests.TEXTURE_DIR + "grass/grass_top_beta.png"},
            tooltip("The texture of the top of grass blocks.", "the grass top of your texture pack.", "Beta's grass top. It takes the biome's color as usual.", "Beta"));
    }

    static KirModuleTexture cobblestone()
    {
        return new KirModuleTexture("cobblestoneTexture", "Cobblestone Texture", "Cobblestone", new String[] {"Normal", "Beta"}, "cobblestonetexture",
            new int[] {16, 36}, paths(LEGACY_DIR, new String[] {"cobblestone_beta", "mossy_cobblestone_beta"}),
            tooltip("The texture of cobblestone and mossy cobblestone.", "the cobblestone of your texture pack.", "Beta's cobblestone and mossy cobblestone.", "Beta"));
    }

    static KirModuleTexture bricks()
    {
        return new KirModuleTexture("bricksTexture", "Bricks Texture", "Bricks", new String[] {"Normal", "Alpha"}, "brickstexture",
            new int[] {7}, paths(LEGACY_DIR, new String[] {"bricks_alpha"}),
            tooltip("The texture of brick blocks.", "the bricks of your texture pack.", "Alpha's bricks.", "Alpha"));
    }

    static KirModuleTexture mobSpawner()
    {
        return new KirModuleTexture("mobSpawnerTexture", "Mob Spawner Texture", "Mob Spawner", new String[] {"Normal", "Beta"}, "spawnertexture",
            new int[] {65}, paths(LEGACY_DIR, new String[] {"mob_spawner_beta"}),
            tooltip("The texture of mob spawner cages.", "the mob spawner of your texture pack.", "Beta's mob spawner.", "Beta"));
    }

    static KirModuleTexture wool()
    {
        return new KirModuleTexture("woolTexture", "Wool Texture", "Wool", new String[] {"Normal", "Legacy"}, "wooltexture",
            new int[] {210, 194, 178, 162, 146, 130, 114, 225, 209, 193, 177, 161, 145, 129, 113},
            paths(LEGACY_DIR, new String[] {"wool_orange", "wool_magenta", "wool_light_blue", "wool_yellow", "wool_lime", "wool_pink", "wool_gray",
                "wool_light_gray", "wool_cyan", "wool_purple", "wool_blue", "wool_brown", "wool_green", "wool_red", "wool_black"}),
            tooltip("The texture of colored wool (white wool is unchanged).", "the wool of your texture pack.", "the old colored wool, as in Kirpack.", "Legacy"));
    }

    static KirModuleTexture endStone()
    {
        return new KirModuleTexture("endStoneTexture", "End Stone Texture", "End Stone", new String[] {"Normal", "Legacy"}, "endstonetexture",
            new int[] {175}, paths(LEGACY_DIR, new String[] {"end_stone_legacy"}),
            tooltip("The texture of end stone.", "the end stone of your texture pack.", "the old end stone, as in Kirpack.", "Legacy"));
    }

    static KirModuleTexture gravel()
    {
        return new KirModuleTexture("gravelTexture", "Gravel Texture", "Gravel", new String[] {"Normal", "Legacy"}, "graveltexture",
            new int[] {19}, paths(LEGACY_DIR, new String[] {"gravel_legacy"}),
            tooltip("The texture of gravel.", "the gravel of your texture pack.", "the old gravel, as in Kirpack.", "Legacy"));
    }

    static KirModuleTexture glowstone()
    {
        return new KirModuleTexture("glowstoneTexture", "Glowstone Texture", "Glowstone", new String[] {"Normal", "Legacy"}, "glowstonetexture",
            new int[] {105}, paths(LEGACY_DIR, new String[] {"glowstone_legacy"}),
            tooltip("The texture of glowstone.", "the glowstone of your texture pack.", "the old glowstone, as in Kirpack.", "Legacy"));
    }
}
