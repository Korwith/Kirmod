package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * A hook into each world's tick, used by Tile Suppressor, Sign Ticking, Sneak Place, Item Magnet and
 * Minecart Speed Limit.
 *
 * Each world's profiler is wrapped in a KirWorldHook. World.updateEntities starts the "tileEntities"
 * profiler section right before it ticks loadedTileEntityList and starts "pendingTileEntities" right
 * after, both on the world's own thread. Tile entities that should not tick are moved out of the list
 * in between and put back afterwards. On the integrated server's worlds the same point is used to give
 * the host player Kirmod's server-side interaction handler (see KirInteraction), on the server thread.
 * The "regular" section, which starts right before the world ticks its entities, is where the integrated
 * server's fast minecarts are moved ahead (see KirModuleMinecartSpeed).
 * On the client's world, the rain is hidden from the entities between those two sections for End
 * Overworld Sky (see KirModuleEndSky).
 * Everything else is passed straight to the real profiler.
 */
public class KirWorldHook extends Profiler
{
    private static final Field fieldWorldProfiler = KirUtil.findField(World.class, "theProfiler", "F");
    private static final Field fieldEntityRemoval = KirUtil.findField(World.class, "entityRemoval", "b");

    private final World world;
    private final Profiler parent;
    private final List hidden = new ArrayList();

    public KirWorldHook(World world, Profiler parent)
    {
        this.world = world;
        this.parent = parent;
    }

    public static boolean isAvailable()
    {
        return fieldWorldProfiler != null && fieldEntityRemoval != null;
    }

    /** Keeps a KirWorldHook on the client world and every world of the integrated server. */
    public static void installAll(Minecraft mc)
    {
        install(mc.theWorld);
        IntegratedServer var1 = mc.getIntegratedServer();

        if (var1 != null && var1.theWorldServer != null)
        {
            for (int var2 = 0; var2 < var1.theWorldServer.length; ++var2)
            {
                install(var1.theWorldServer[var2]);
            }
        }
    }

    private static void install(World world)
    {
        if (world != null && isAvailable() && !(world.theProfiler instanceof KirWorldHook))
        {
            try
            {
                fieldWorldProfiler.set(world, new KirWorldHook(world, world.theProfiler));
            }
            catch (IllegalAccessException var2)
            {
                KirUtil.log("Could not hook into the world tick", var2);
            }
        }
    }

    /** Whether a tile entity may tick with the current Tile Suppressor and Sign Ticking settings. */
    public static boolean shouldTickTileEntity(TileEntity tileEntity)
    {
        return mod_Kirmod.TILE_SUPPRESSOR.shouldTickTileEntity(tileEntity) && mod_Kirmod.SIGN_TICKING.shouldTickTileEntity(tileEntity);
    }

    private static boolean suppressesAny()
    {
        return mod_Kirmod.TILE_SUPPRESSOR.suppressesAny() || mod_Kirmod.SIGN_TICKING.suppressesAny();
    }

    public void clearProfiling()
    {
        this.parent.clearProfiling();
    }

    public void startSection(String par1Str)
    {
        this.parent.startSection(par1Str);
    }

    public void endSection()
    {
        this.parent.endSection();
    }

    public List getProfilingData(String par1Str)
    {
        return this.parent.getProfilingData(par1Str);
    }

    public void endStartSection(String par1Str)
    {
        this.parent.endStartSection(par1Str);

        if ("regular".equals(par1Str))
        {
            if (this.world.isRemote && mod_Kirmod.END_SKY.enabled)
            {
                mod_Kirmod.END_SKY.hideRain(this.world);
            }

            if (!this.world.isRemote)
            {
                // Minecart Speed (Kirmod Extras) moves fast minecarts ahead here, say.
                mod_Kirmod.tickServerWorld(this.world);
            }
        }
        else if ("tileEntities".equals(par1Str))
        {
            if (this.world.isRemote && mod_Kirmod.END_SKY.enabled)
            {
                mod_Kirmod.END_SKY.showRain(this.world);
            }

            if (!this.world.isRemote)
            {
                KirInteraction.updateServerWorld(this.world);
                KirInteraction.runServerTasks(this.world);

                mod_Kirmod.tickServerWorldEnd(this.world);
            }

            this.hideSuppressed();
        }
        else if ("chunkMap".equals(par1Str))
        {
            if (!this.world.isRemote && mod_Kirmod.OPTIFINE_RENDER_FIX.enabled)
            {
                mod_Kirmod.OPTIFINE_RENDER_FIX.tickServerWorld(this.world);
            }
        }
        else if ("pendingTileEntities".equals(par1Str))
        {
            this.restoreSuppressed();
        }
    }

    public String func_76322_c()
    {
        return this.parent.func_76322_c();
    }

    private void hideSuppressed()
    {
        if (!suppressesAny())
        {
            return;
        }

        List var1 = this.world.loadedTileEntityList;
        ArrayList var2 = new ArrayList(var1.size());
        HashSet var3 = this.getUnloading();
        Iterator var4 = var1.iterator();

        while (var4.hasNext())
        {
            TileEntity var5 = (TileEntity)var4.next();

            // Invalid tile entities, and ones whose chunk is unloading, stay so the world removes them
            // as usual.
            if (!var5.isInvalid() && !shouldTickTileEntity(var5) && (var3 == null || !var3.contains(var5)))
            {
                this.hidden.add(var5);
            }
            else
            {
                var2.add(var5);
            }
        }

        if (!this.hidden.isEmpty())
        {
            var1.clear();
            var1.addAll(var2);
        }
    }

    /** Tile entities of unloaded chunks that the world removes at the end of this tick, or null. */
    private HashSet getUnloading()
    {
        try
        {
            List var1 = (List)fieldEntityRemoval.get(this.world);
            return var1.isEmpty() ? null : new HashSet(var1);
        }
        catch (IllegalAccessException var2)
        {
            return null;
        }
    }

    private void restoreSuppressed()
    {
        if (this.hidden.isEmpty())
        {
            return;
        }

        Iterator var1 = this.hidden.iterator();

        while (var1.hasNext())
        {
            TileEntity var2 = (TileEntity)var1.next();

            // Drop ones that were broken while they were out of the list. Chunks never unload during
            // the tile entity tick, so unlike the vanilla loop no per-entity chunk lookup is needed;
            // that lookup is most of what an idle sign costs.
            if (!var2.isInvalid())
            {
                this.world.loadedTileEntityList.add(var2);
            }
        }

        this.hidden.clear();
    }
}
