package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * Tile Suppressor: freezes furnaces, or all tile entities, at or below Y 4.
 *
 * Frozen tile entities are kept out of the world's tick (see KirWorldHook), which only matters for
 * worlds the client runs itself (singleplayer and LAN host) and for client-side ticking. Frozen tile
 * entities can't be opened either: right-clicking one prints a chat message instead (see
 * KirInteraction).
 */
public class KirModuleTileSuppressor extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "Furnaces", "All"};

    /** Tile entities at or below this Y level are frozen. */
    public static final int MAX_Y = 4;

    public KirModuleTileSuppressor()
    {
        super("tileSuppressor", "Tile Suppressor", "Tile Suppressor", 0, "tilesuppressor", "suppressor");
    }

    public String getCategory()
    {
        return CATEGORY_PERFORMANCE;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Freezes tile entities at or below Y " + MAX_Y + ", so big furnace (or machine) arrays near bedrock stop using any time. Frozen blocks can't be opened; you get a chat message instead.",
            describeValue("Off", "everything ticks normally."),
            describeValue("Furnaces", "furnaces at or below Y " + MAX_Y + " are frozen."),
            describeValue("All", "every tile entity at or below Y " + MAX_Y + " is frozen (chests, dispensers, brewing stands...). Signs and spawners can still be clicked."),
            note("Works in singleplayer and on a LAN world you host.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return KirWorldHook.isAvailable();
    }

    public boolean suppressesAny()
    {
        return this.enabled && this.getValue() != 0;
    }

    /** Whether a tile entity may tick with the current setting. */
    public boolean shouldTickTileEntity(TileEntity tileEntity)
    {
        int var2 = this.enabled ? this.getValue() : 0;
        return var2 == 0 || tileEntity.yCoord > MAX_Y ? true : (var2 == 2 ? false : !(tileEntity instanceof TileEntityFurnace));
    }

    /**
     * The chat message shown instead of opening the block at x, y, z, or null if it may be used. Only
     * frozen tile entities that do something when clicked are blocked; signs, spawners, moving pistons
     * and end portals are not, so blocks can still be placed against them.
     */
    public String getBlockedMessage(World world, int x, int y, int z)
    {
        TileEntity var5 = y <= MAX_Y ? world.getBlockTileEntity(x, y, z) : null;

        if (var5 == null || this.shouldTickTileEntity(var5) || var5 instanceof TileEntitySign || var5 instanceof TileEntityMobSpawner || var5 instanceof TileEntityPiston || var5 instanceof TileEntityEndPortal)
        {
            return null;
        }

        return (this.getValue() == 1 ? "Furnaces" : "Tile entities") + " are disabled at or below Y" + MAX_Y + ".";
    }

    public void update(Minecraft mc)
    {
        KirWorldHook.installAll(mc);
        KirInteraction.installClient(mc);
    }
}
