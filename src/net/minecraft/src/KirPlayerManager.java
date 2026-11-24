package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chunk sender of OptiFine Render Fix: a PlayerManager that sends each player the chunks out to the
 * client's render distance instead of the integrated server's fixed 10.
 *
 * Vanilla 1.3.2 sends a square of 10 chunks around the player in singleplayer (IntegratedPlayerList
 * sets viewDistance to 10, and PlayerManager refuses anything over 15). OptiFine draws up to 512 blocks
 * (32 chunks) and vanilla Far 256 (16), so everything past 160 blocks is never sent: the renderers are
 * there but empty, which looks like loading that never finishes.
 *
 * Simply raising the radius isn't enough: vanilla then loads (and generates) the whole square in one
 * tick when a player joins, respawns or changes dimension (4225 chunks at 32), and its send queue is a
 * LinkedList that the entity tracker searches every tick. So this manager tracks the chunks each player
 * watches: joining and moving load the vanilla square right away, as vanilla does, and the rest is
 * added a few chunks per tick, nearest first, while the player's send queue is short.
 *
 * The PlayerInstances and lists are shared with the replaced manager (its fields are copied over), so
 * chunks it was already watching keep working. With the radius at the vanilla 10 it sends exactly
 * what vanilla does. Server thread only.
 */
public class KirPlayerManager extends PlayerManager
{
    private static final Field fieldWorldManager = KirUtil.findField(WorldServer.class, "thePlayerManager", "M");
    private static final Field fieldPlayers = KirUtil.findField(PlayerManager.class, "field_72699_b", "players", "b");
    private static final Field fieldAllWatchers = KirUtil.findField(PlayerManager.class, "allChunkWathers", "playerInstances", "c");
    private static final Field fieldWatchersToUpdate = KirUtil.findField(PlayerManager.class, "chunkWathcherWithPlayers", "playerInstancesToUpdate", "d");
    private static final Field fieldViewDistance = KirUtil.findField(PlayerManager.class, "playerViewDistance", "playerViewRadius", "e");
    private static final Field fieldWorld = KirUtil.findField(PlayerManager.class, "theWorldServer", "a");

    /** New chunks are only queued while a player's send queue is shorter than this. */
    private static final int QUEUE_LOW = 20;

    /** Chunks added (and loaded or generated) per player per tick, at most; the server sends 5 a tick. */
    private static final int ADD_PER_TICK = 6;

    private static boolean failed = false;

    /** The radius vanilla used for this world (10 in singleplayer), loaded right away on join and move. */
    private final int baseRadius;

    /** The radius being sent now. */
    private int radius;

    private final List players;
    private final LongHashMap watchers;
    private final Map states = new IdentityHashMap();

    /** What one player watches. */
    private static class PlayerState
    {
        final Set watched = new HashSet();
        int centerX;
        int centerZ;

        /** Rings closer than this are complete, so filling starts here. */
        int fillRing = 0;
    }

    private KirPlayerManager(WorldServer world, PlayerManager original) throws IllegalAccessException
    {
        super(world, 3);
        this.baseRadius = fieldViewDistance.getInt(original);
        this.radius = this.baseRadius;
        this.players = (List)fieldPlayers.get(original);
        this.watchers = (LongHashMap)fieldAllWatchers.get(original);
        fieldPlayers.set(this, this.players);
        fieldAllWatchers.set(this, this.watchers);
        fieldWatchersToUpdate.set(this, fieldWatchersToUpdate.get(original));
        fieldViewDistance.setInt(this, this.baseRadius);
        fieldWorld.set(this, fieldWorld.get(original));

        // Players already in the world watch vanilla's square around their last chunk position.
        for (int var3 = 0; var3 < this.players.size(); ++var3)
        {
            EntityPlayerMP var4 = (EntityPlayerMP)this.players.get(var3);
            PlayerState var5 = new PlayerState();
            var5.centerX = (int)var4.field_71131_d >> 4;
            var5.centerZ = (int)var4.field_71132_e >> 4;

            for (int var6 = var5.centerX - this.baseRadius; var6 <= var5.centerX + this.baseRadius; ++var6)
            {
                for (int var7 = var5.centerZ - this.baseRadius; var7 <= var5.centerZ + this.baseRadius; ++var7)
                {
                    PlayerInstance var8 = this.getWatcher(var6, var7, false);

                    if (var8 != null && PlayerInstance.getPlayersInChunk(var8).contains(var4))
                    {
                        var5.watched.add(Long.valueOf(key(var6, var7)));
                    }
                }
            }

            this.states.put(var4, var5);
        }
    }

    public static boolean isAvailable()
    {
        return !failed && fieldWorldManager != null && fieldPlayers != null && fieldAllWatchers != null && fieldWatchersToUpdate != null && fieldViewDistance != null && fieldWorld != null;
    }

    /**
     * Puts a KirPlayerManager into the world (if it hasn't one) and sends chunks out to the given radius;
     * a radius below the world's vanilla one means the vanilla one. Server thread.
     */
    public static void update(WorldServer world, int wantedRadius)
    {
        if (!isAvailable())
        {
            return;
        }

        PlayerManager var2 = world.getPlayerManager();
        KirPlayerManager var3;

        if (var2 instanceof KirPlayerManager)
        {
            var3 = (KirPlayerManager)var2;
        }
        else
        {
            if (var2.getClass() != PlayerManager.class)
            {
                failed = true;
                KirUtil.log("The chunk sender is already changed by another mod (" + var2.getClass().getName() + "), OptiFine Render Fix is unavailable", (Throwable)null);
                return;
            }

            if (wantedRadius <= fieldViewDistanceOf(var2))
            {
                return;
            }

            try
            {
                var3 = new KirPlayerManager(world, var2);
                fieldWorldManager.set(world, var3);
            }
            catch (Exception var5)
            {
                failed = true;
                KirUtil.log("Could not replace the chunk sender, OptiFine Render Fix is unavailable", var5);
                return;
            }
        }

        var3.setRadius(wantedRadius);
        var3.fill();
    }

    private static int fieldViewDistanceOf(PlayerManager manager)
    {
        return KirUtil.getInt(fieldViewDistance, manager, 10);
    }

    /** The radius a world is sending now, for the tooltip and tests. */
    public static int getRadius(WorldServer world)
    {
        PlayerManager var1 = world.getPlayerManager();
        return var1 instanceof KirPlayerManager ? ((KirPlayerManager)var1).radius : fieldViewDistanceOf(var1);
    }

    private static long key(int x, int z)
    {
        return (long)x + 2147483647L | (long)z + 2147483647L << 32;
    }

    private static int keyX(long key)
    {
        return (int)((key & 4294967295L) - 2147483647L);
    }

    private static int keyZ(long key)
    {
        return (int)((key >>> 32) - 2147483647L);
    }

    private PlayerInstance getWatcher(int x, int z, boolean create)
    {
        long var4 = key(x, z);
        PlayerInstance var6 = (PlayerInstance)this.watchers.getValueByKey(var4);

        if (var6 == null && create)
        {
            var6 = new PlayerInstance(this, x, z);
            this.watchers.add(var4, var6);
        }

        return var6;
    }

    private void watch(EntityPlayerMP player, PlayerState state, int x, int z)
    {
        if (state.watched.add(Long.valueOf(key(x, z))))
        {
            PlayerInstance var5 = this.getWatcher(x, z, true);

            if (!PlayerInstance.getPlayersInChunk(var5).contains(player))
            {
                var5.addPlayerToChunkWatchingList(player);
            }
        }
    }

    private void unwatch(EntityPlayerMP player, long key)
    {
        PlayerInstance var4 = this.getWatcher(keyX(key), keyZ(key), false);

        if (var4 != null)
        {
            var4.sendThisChunkToPlayer(player);
        }
    }

    /** Stops watching everything outside the square of the radius around the player's center. */
    private void trim(EntityPlayerMP player, PlayerState state)
    {
        Iterator var3 = state.watched.iterator();

        while (var3.hasNext())
        {
            long var4 = ((Long)var3.next()).longValue();

            if (Math.abs(keyX(var4) - state.centerX) > this.radius || Math.abs(keyZ(var4) - state.centerZ) > this.radius)
            {
                var3.remove();
                this.unwatch(player, var4);
            }
        }
    }

    /** Watches the vanilla square around the player's center right away. */
    private void watchNear(EntityPlayerMP player, PlayerState state)
    {
        int var3 = Math.min(this.radius, this.baseRadius);

        for (int var4 = state.centerX - var3; var4 <= state.centerX + var3; ++var4)
        {
            for (int var5 = state.centerZ - var3; var5 <= state.centerZ + var3; ++var5)
            {
                this.watch(player, state, var4, var5);
            }
        }
    }

    private PlayerState getState(EntityPlayerMP player)
    {
        PlayerState var2 = (PlayerState)this.states.get(player);

        if (var2 == null)
        {
            var2 = new PlayerState();
            var2.centerX = (int)player.field_71131_d >> 4;
            var2.centerZ = (int)player.field_71132_e >> 4;
            this.states.put(player, var2);
        }

        return var2;
    }

    void setRadius(int wanted)
    {
        int var2 = Math.max(this.baseRadius, wanted);

        if (var2 == this.radius)
        {
            return;
        }

        boolean var3 = var2 < this.radius;
        this.radius = var2;

        // Over 15 is fine here: playerViewDistance only sizes the queue sort, which is overridden.
        KirUtil.setInt(fieldViewDistance, this, var2);
        Iterator var4 = this.states.entrySet().iterator();

        while (var4.hasNext())
        {
            Map.Entry var5 = (Map.Entry)var4.next();
            PlayerState var6 = (PlayerState)var5.getValue();

            if (var3)
            {
                this.trim((EntityPlayerMP)var5.getKey(), var6);
            }
            else
            {
                var6.fillRing = 0;
            }
        }
    }

    /** Adds a few missing chunks per player, nearest ring first, while their send queue is short. */
    void fill()
    {
        Iterator var1 = this.states.entrySet().iterator();

        while (var1.hasNext())
        {
            Map.Entry var2 = (Map.Entry)var1.next();
            EntityPlayerMP var3 = (EntityPlayerMP)var2.getKey();
            PlayerState var4 = (PlayerState)var2.getValue();

            if (var4.fillRing > this.radius || var3.chunksToLoad.size() >= QUEUE_LOW)
            {
                continue;
            }

            int var5 = 0;

            while (var4.fillRing <= this.radius && var5 < ADD_PER_TICK)
            {
                int var6 = var4.fillRing;
                boolean var7 = true;

                for (int var8 = -var6; var8 <= var6 && var5 < ADD_PER_TICK; ++var8)
                {
                    for (int var9 = -var6; var9 <= var6 && var5 < ADD_PER_TICK; ++var9)
                    {
                        if (Math.abs(var8) != var6 && Math.abs(var9) != var6)
                        {
                            continue;
                        }

                        int var10 = var4.centerX + var8;
                        int var11 = var4.centerZ + var9;

                        if (!var4.watched.contains(Long.valueOf(key(var10, var11))))
                        {
                            this.watch(var3, var4, var10, var11);
                            ++var5;
                        }
                    }
                }

                // The ring is complete only if the loops ran to the end without hitting the budget.
                if (var5 >= ADD_PER_TICK)
                {
                    var7 = this.isRingComplete(var4, var6);
                }

                if (!var7)
                {
                    break;
                }

                ++var4.fillRing;
            }
        }
    }

    private boolean isRingComplete(PlayerState state, int ring)
    {
        for (int var3 = -ring; var3 <= ring; ++var3)
        {
            for (int var4 = -ring; var4 <= ring; ++var4)
            {
                if ((Math.abs(var3) == ring || Math.abs(var4) == ring) && !state.watched.contains(Long.valueOf(key(state.centerX + var3, state.centerZ + var4))))
                {
                    return false;
                }
            }
        }

        return true;
    }

    /** A player joins this world. */
    public void func_72683_a(EntityPlayerMP par1EntityPlayerMP)
    {
        par1EntityPlayerMP.field_71131_d = par1EntityPlayerMP.posX;
        par1EntityPlayerMP.field_71132_e = par1EntityPlayerMP.posZ;
        PlayerState var2 = new PlayerState();
        var2.centerX = (int)par1EntityPlayerMP.posX >> 4;
        var2.centerZ = (int)par1EntityPlayerMP.posZ >> 4;
        this.states.put(par1EntityPlayerMP, var2);
        this.watchNear(par1EntityPlayerMP, var2);
        this.players.add(par1EntityPlayerMP);
        this.func_72691_b(par1EntityPlayerMP);
    }

    /** Sorts the player's send queue nearest first. */
    public void func_72691_b(EntityPlayerMP par1EntityPlayerMP)
    {
        final int var2 = (int)par1EntityPlayerMP.posX >> 4;
        final int var3 = (int)par1EntityPlayerMP.posZ >> 4;
        ArrayList var4 = new ArrayList(par1EntityPlayerMP.chunksToLoad);
        Collections.sort(var4, new Comparator()
        {
            public int compare(Object a, Object b)
            {
                return distance((ChunkCoordIntPair)a) - distance((ChunkCoordIntPair)b);
            }
            private int distance(ChunkCoordIntPair pos)
            {
                int var2a = pos.chunkXPos - var2;
                int var3a = pos.chunkZPos - var3;
                return var2a * var2a + var3a * var3a;
            }
        });
        par1EntityPlayerMP.chunksToLoad.clear();
        par1EntityPlayerMP.chunksToLoad.addAll(var4);
    }

    /** A player leaves this world. */
    public void func_72695_c(EntityPlayerMP par1EntityPlayerMP)
    {
        PlayerState var2 = (PlayerState)this.states.remove(par1EntityPlayerMP);

        if (var2 == null)
        {
            super.func_72695_c(par1EntityPlayerMP);
            return;
        }

        Iterator var3 = var2.watched.iterator();

        while (var3.hasNext())
        {
            this.unwatch(par1EntityPlayerMP, ((Long)var3.next()).longValue());
        }

        this.players.remove(par1EntityPlayerMP);
    }

    /** A player moved; same thresholds as vanilla (8 blocks, a new chunk). */
    public void func_72685_d(EntityPlayerMP par1EntityPlayerMP)
    {
        double var2 = par1EntityPlayerMP.field_71131_d - par1EntityPlayerMP.posX;
        double var4 = par1EntityPlayerMP.field_71132_e - par1EntityPlayerMP.posZ;

        if (var2 * var2 + var4 * var4 < 64.0D)
        {
            return;
        }

        int var6 = (int)par1EntityPlayerMP.posX >> 4;
        int var7 = (int)par1EntityPlayerMP.posZ >> 4;
        int var8 = (int)par1EntityPlayerMP.field_71131_d >> 4;
        int var9 = (int)par1EntityPlayerMP.field_71132_e >> 4;

        if (var6 == var8 && var7 == var9)
        {
            return;
        }

        PlayerState var10 = this.getState(par1EntityPlayerMP);
        var10.centerX = var6;
        var10.centerZ = var7;
        var10.fillRing = 0;
        this.trim(par1EntityPlayerMP, var10);
        this.watchNear(par1EntityPlayerMP, var10);
        this.func_72691_b(par1EntityPlayerMP);
        par1EntityPlayerMP.field_71131_d = par1EntityPlayerMP.posX;
        par1EntityPlayerMP.field_71132_e = par1EntityPlayerMP.posZ;
    }

    /** How many chunks a player watches (for tests). */
    public int getWatchedCount(EntityPlayerMP player)
    {
        PlayerState var2 = (PlayerState)this.states.get(player);
        return var2 == null ? -1 : var2.watched.size();
    }
}
