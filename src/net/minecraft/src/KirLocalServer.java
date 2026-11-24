package net.minecraft.src;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * The server object of a local world (Integrated Server Off, see KirLocalGame). It is never started: no
 * thread, no network. It holds the worlds and saves them like the integrated server would, and
 * KirLocalWorld ticks it from the client's own world tick. MinecraftServer.getServer() returns it, so
 * server code (commands, dimensions, the player list) finds what it expects.
 */
public class KirLocalServer extends IntegratedServer
{
    private static final Field fieldLoadedChunks = KirUtil.findField(ChunkProviderServer.class, "loadedChunks", "g");

    /** MinecraftServer.serverStopped, which Forge's Minecraft.loadWorld(null) waits for. */
    private static final Field fieldServerStopped = KirUtil.findField(MinecraftServer.class, "serverStopped", "v");

    /** MinecraftServer.timeOfLastDimenstionTick; Forge replaced it with a map it keeps itself. */
    private static final Field fieldDimensionTickTimes = findOptionalField(MinecraftServer.class, "timeOfLastDimenstionTick", "k");

    /** Forge and FML, when present. */
    private static final Class classDimensionManager = findClass("net.minecraftforge.common.DimensionManager");
    private static final Class classFMLCommonHandler = findClass("cpw.mods.fml.common.FMLCommonHandler");
    private static Object eventBus;
    private static Method methodEventPost;
    private static Constructor constructorWorldLoad;

    static
    {
        try
        {
            Class var0 = findClass("net.minecraftforge.common.MinecraftForge");
            Class var1 = findClass("net.minecraftforge.event.world.WorldEvent$Load");

            if (var0 != null && var1 != null)
            {
                eventBus = var0.getField("EVENT_BUS").get((Object)null);
                constructorWorldLoad = var1.getConstructor(new Class[] {World.class});
                Method[] var2 = eventBus.getClass().getMethods();

                for (int var3 = 0; var3 < var2.length; ++var3)
                {
                    if ("post".equals(var2[var3].getName()) && var2[var3].getParameterTypes().length == 1)
                    {
                        methodEventPost = var2[var3];
                    }
                }
            }
        }
        catch (Exception var4)
        {
            KirUtil.log("Could not find Forge's world events", var4);
        }
    }

    private static Class findClass(String name)
    {
        try
        {
            return Class.forName(name);
        }
        catch (Throwable var2)
        {
            return null;
        }
    }

    private static Field findOptionalField(Class owner, String ... names)
    {
        for (int var2 = 0; var2 < names.length; ++var2)
        {
            try
            {
                Field var3 = owner.getDeclaredField(names[var2]);

                if (var3.getType() == long[][].class)
                {
                    var3.setAccessible(true);
                    return var3;
                }
            }
            catch (NoSuchFieldException var4)
            {
                ;
            }
        }

        return null;
    }

    private final Minecraft mc;
    private final WorldSettings settings;
    private final DummyListenThread listenThread;
    private ISaveHandler saveHandler;
    private String worldName;
    private final long startTime = System.currentTimeMillis();
    private boolean stopped;
    private int ticks;

    /** Chunk offsets within the current radius, nearest first. */
    private int[][] offsets = new int[0][];
    private int offsetsRadius = -1;

    /** Real time of the last 40 ticks, for the tick rate shown in the settings screen. */
    private final long[] tickTimes = new long[40];
    private int tickTimeCount;

    public KirLocalServer(Minecraft mc, String folder, String name, WorldSettings settings) throws IOException
    {
        super(mc, folder, name, settings);
        this.mc = mc;
        this.settings = settings;
        this.listenThread = new DummyListenThread(this);
    }

    /** The connection point NetClientHandler and the tick use: one that never lets anyone in. */
    public IntegratedServerListenThread func_71343_a()
    {
        return this.listenThread;
    }

    /** Open to LAN needs a real server. */
    public String shareToLAN(EnumGameType par1EnumGameType, boolean par2)
    {
        return null;
    }

    /** IntegratedServer.startServer, on the client thread and without the key pair (there is no network). */
    void startLocal()
    {
        this.setOnlineMode(false);
        this.setSpawnAnimals(true);
        this.setSpawnNpcs(true);
        this.setAllowPvp(true);
        this.setAllowFlight(true);
        this.loadAllDimensions(this.getFolderName(), this.getWorldName(), this.settings.getSeed(), this.settings.getTerrainType());
        this.setMOTD(this.getServerOwner() + " - " + this.worldServerForDimension(0).getWorldInfo().getWorldName());
        this.setLoadOnRequest(false);
        this.fmlEvent("handleServerStarting");
        this.fmlEvent("handleServerStarted");
    }

    /**
     * IntegratedServer.loadAllDimensions with Kirmod's world classes. Under Forge, also the other dimensions
     * Forge knows about, and Forge's world load event; Forge's WorldServer registers itself with its
     * DimensionManager, which then keeps theWorldServer up to date.
     */
    protected void loadAllDimensions(String par1Str, String par2Str, long par3, WorldType par5WorldType)
    {
        this.convertMapIfNeeded(par1Str);
        this.saveHandler = this.getActiveAnvilConverter().getSaveLoader(par1Str, true);
        this.worldName = par2Str;
        int[] var6 = getStaticDimensions();

        if (classDimensionManager == null)
        {
            this.theWorldServer = new WorldServer[var6.length];
        }

        if (fieldDimensionTickTimes != null)
        {
            try
            {
                fieldDimensionTickTimes.set(this, new long[var6.length][100]);
            }
            catch (IllegalAccessException var9)
            {
                ;
            }
        }

        // Under Forge, each WorldServer's constructor rebuilds theWorldServer from DimensionManager.
        boolean var10 = classDimensionManager == null;
        WorldServer var7 = new KirLocalWorldServer(this, this.saveHandler, par2Str, 0, this.settings, this.theProfiler);

        if (var10)
        {
            this.theWorldServer[0] = var7;
        }

        this.addListeners(var7);

        for (int var8 = 1; var8 < var6.length; ++var8)
        {
            WorldServer var11 = this.createDimension(var6[var8], var7);

            if (var10)
            {
                this.theWorldServer[var8] = var11;
            }
        }

        this.getConfigurationManager().func_72364_a(new WorldServer[] {var7});
        this.setDifficultyForAllDimensions(this.getDifficulty());
        this.mc.loadingScreen.resetProgresAndWorkingMessage(StatCollector.translateToLocal("menu.generatingTerrain"));
        this.initialWorldChunkLoad();
    }

    private WorldServer createDimension(int dimension, WorldServer overworld)
    {
        WorldServer var3 = new KirLocalWorldServerMulti(this, this.saveHandler, this.worldName, dimension, this.settings, overworld, this.theProfiler);
        this.addListeners(var3);
        return var3;
    }

    /** The server's WorldManager, and Forge's world load event. */
    private void addListeners(WorldServer world)
    {
        world.addWorldAccess(new WorldManager(this, world));

        if (methodEventPost != null)
        {
            try
            {
                methodEventPost.invoke(eventBus, new Object[] {constructorWorldLoad.newInstance(new Object[] {world})});
            }
            catch (Exception var3)
            {
                KirUtil.log("Forge's world load event failed", var3);
            }
        }
    }

    /**
     * Forge unloads a dimension once it has no chunks loaded and makes a plain WorldServerMulti when it is
     * needed again; the local game needs its own class there, so it makes that one instead.
     */
    public WorldServer worldServerForDimension(int par1)
    {
        WorldServer var2 = super.worldServerForDimension(par1);

        if (var2 != null && !(var2 instanceof KirLocalWorldServer) && !(var2 instanceof KirLocalWorldServerMulti) && this.saveHandler != null && par1 != 0)
        {
            var2 = this.createDimension(par1, super.worldServerForDimension(0));
            var2.difficultySetting = this.getDifficulty();
        }

        return var2;
    }

    /** 0, -1 and 1, or Forge's list of dimensions (overworld first). */
    private static int[] getStaticDimensions()
    {
        if (classDimensionManager != null)
        {
            try
            {
                Integer[] var0 = (Integer[])classDimensionManager.getMethod("getStaticDimensionIDs", new Class[0]).invoke((Object)null, new Object[0]);
                int[] var1 = new int[var0.length];
                int var2 = 1;

                for (int var3 = 0; var3 < var0.length; ++var3)
                {
                    if (var0[var3].intValue() != 0 && var2 < var1.length)
                    {
                        var1[var2++] = var0[var3].intValue();
                    }
                }

                if (var2 == var1.length)
                {
                    return var1;
                }
            }
            catch (Exception var4)
            {
                KirUtil.log("Could not ask Forge for its dimensions", var4);
            }
        }

        return new int[] {0, -1, 1};
    }

    /** FML's server starting/started/stopping events, which mods register commands and the like in. */
    private void fmlEvent(String name)
    {
        if (classFMLCommonHandler != null)
        {
            try
            {
                Object var2 = classFMLCommonHandler.getMethod("instance", new Class[0]).invoke((Object)null, new Object[0]);

                if ("handleServerStarting".equals(name))
                {
                    classFMLCommonHandler.getMethod(name, new Class[] {MinecraftServer.class}).invoke(var2, new Object[] {this});
                }
                else
                {
                    classFMLCommonHandler.getMethod(name, new Class[0]).invoke(var2, new Object[0]);
                }
            }
            catch (Exception var3)
            {
                KirUtil.log("FML's " + name + " failed", var3);
            }
        }
    }

    /** initialWorldChunkLoad's progress, on the loading screen. */
    protected void outputPercentRemaining(String par1Str, int par2)
    {
        super.outputPercentRemaining(par1Str, par2);
        this.mc.loadingScreen.setLoadingProgress(par2);
    }

    /** Whether asking a world for a chunk that isn't loaded loads it (true while ticking, like a server). */
    void setLoadOnRequest(boolean load)
    {
        if (this.theWorldServer != null)
        {
            for (int var2 = 0; var2 < this.theWorldServer.length; ++var2)
            {
                if (this.theWorldServer[var2] != null)
                {
                    this.theWorldServer[var2].theChunkProviderServer.loadChunkOnProvideRequest = load;
                }
            }
        }
    }

    /** One game tick of every dimension, on the client thread. Called from KirLocalWorld.tick. */
    void tickLocal()
    {
        long var1 = System.nanoTime();
        this.setLoadOnRequest(true);

        try
        {
            EntityPlayer var3 = this.mc.thePlayer;

            if (var3 != null && var3.worldObj instanceof WorldServer)
            {
                this.manageChunks((WorldServer)var3.worldObj, var3);
            }

            this.tick();
        }
        finally
        {
            this.setLoadOnRequest(false);
        }

        this.tickTimes[this.tickTimeCount++ % this.tickTimes.length] = var1;
        KirLocalGame.afterTick(this.mc);
    }

    /**
     * Keeps the chunks around the player loaded, nearest first, as many as fit in about 8 ms a tick, and lets
     * the others go. The integrated server's player list did this for its players; the local player isn't on
     * it. Pre-1.3 singleplayer loaded chunks whenever the renderer asked, which is slower to look at but
     * the same idea.
     */
    private void manageChunks(WorldServer world, EntityPlayer player)
    {
        int var3 = this.getChunkRadius();
        int var4 = MathHelper.floor_double(player.posX) >> 4;
        int var5 = MathHelper.floor_double(player.posZ) >> 4;
        long var6 = System.nanoTime() + 8000000L;
        int var8 = 0;
        int[][] var9 = this.getOffsets(var3);

        for (int var10 = 0; var10 < var9.length; ++var10)
        {
            int var11 = var4 + var9[var10][0];
            int var12 = var5 + var9[var10][1];

            if (!world.theChunkProviderServer.chunkExists(var11, var12))
            {
                if (var8 > 0 && System.nanoTime() > var6)
                {
                    break;
                }

                world.theChunkProviderServer.loadChunk(var11, var12);
                world.markBlocksDirty(var11 * 16 - 1, 0, var12 * 16 - 1, var11 * 16 + 16, 255, var12 * 16 + 16);
                ++var8;
            }
        }

        if (++this.ticks % 20 != 0 || fieldLoadedChunks == null)
        {
            return;
        }

        for (int var13 = 0; var13 < this.theWorldServer.length; ++var13)
        {
            WorldServer var14 = this.theWorldServer[var13];

            if (var14 == null)
            {
                continue;
            }

            List var15;

            try
            {
                var15 = new ArrayList((List)fieldLoadedChunks.get(var14.theChunkProviderServer));
            }
            catch (IllegalAccessException var19)
            {
                return;
            }

            for (int var16 = 0; var16 < var15.size(); ++var16)
            {
                Chunk var17 = (Chunk)var15.get(var16);

                if ((var14 != world || Math.abs(var17.xPosition - var4) > var3 + 2 || Math.abs(var17.zPosition - var5) > var3 + 2) && !isSpawnChunk(var14, var17))
                {
                    var14.theChunkProviderServer.unloadChunksIfNotNearSpawn(var17.xPosition, var17.zPosition);
                }
            }
        }

        // Takes the chunks in range back off the unload queue (loadChunk does that for a loaded chunk).
        for (int var18 = 0; var18 < var9.length; ++var18)
        {
            if (world.theChunkProviderServer.chunkExists(var4 + var9[var18][0], var5 + var9[var18][1]))
            {
                world.theChunkProviderServer.loadChunk(var4 + var9[var18][0], var5 + var9[var18][1]);
            }
        }
    }

    /**
     * The overworld's spawn area stays loaded, as on a vanilla server. Forge's unloadChunksIfNotNearSpawn
     * doesn't check that (DimensionManager.shouldLoadSpawn is false), and once a dimension has no chunks
     * Forge unloads it; without the overworld it can't load any other dimension again.
     */
    private static boolean isSpawnChunk(WorldServer world, Chunk chunk)
    {
        if (!world.provider.canRespawnHere())
        {
            return false;
        }

        ChunkCoordinates var2 = world.getSpawnPoint();
        int var3 = chunk.xPosition * 16 + 8 - var2.posX;
        int var4 = chunk.zPosition * 16 + 8 - var2.posZ;
        return var3 >= -128 && var3 <= 128 && var4 >= -128 && var4 <= 128;
    }

    /** The renderer's reach in chunks, from the render distance setting (RenderGlobal's arithmetic). */
    private int getChunkRadius()
    {
        int var1 = Math.min(400, 64 << 3 - Math.max(0, Math.min(3, this.mc.gameSettings.renderDistance)));
        return (var1 / 16 + 1) / 2 + 1;
    }

    private int[][] getOffsets(int radius)
    {
        if (radius != this.offsetsRadius)
        {
            int var2 = radius * 2 + 1;
            int[][] var3 = new int[var2 * var2][];
            int var4 = 0;

            for (int var5 = -radius; var5 <= radius; ++var5)
            {
                for (int var6 = -radius; var6 <= radius; ++var6)
                {
                    var3[var4++] = new int[] {var5, var6};
                }
            }

            Arrays.sort(var3, new Comparator()
            {
                public int compare(Object a, Object b)
                {
                    int[] var3 = (int[])a;
                    int[] var4 = (int[])b;
                    return (var3[0] * var3[0] + var3[1] * var3[1]) - (var4[0] * var4[0] + var4[1] * var4[1]);
                }
            });
            this.offsets = var3;
            this.offsetsRadius = radius;
        }

        return this.offsets;
    }

    /* ------------------------------------------------------------------ Saving and stopping */

    /** Puts the local player's data where IntegratedPlayerList keeps the host's, which goes into level.dat. */
    private void writeHostData()
    {
        if (this.mc.thePlayer instanceof KirLocalPlayer && KirLocalGame.fieldHostPlayerData != null)
        {
            try
            {
                NBTTagCompound var1 = new NBTTagCompound();
                this.mc.thePlayer.writeToNBT(var1);
                mod_Kirmod.saveHostPlayer(this.mc.thePlayer, var1);
                KirLocalGame.fieldHostPlayerData.set(this.getConfigurationManager(), var1);
            }
            catch (IllegalAccessException var2)
            {
                KirUtil.log("Could not save the player", var2);
            }
        }
    }

    protected void saveAllDimensions(boolean par1)
    {
        this.writeHostData();
        super.saveAllDimensions(par1);
    }

    /** Saves everything, like the integrated server does when the game pauses. */
    void saveForPause()
    {
        if (!this.stopped)
        {
            logger.info("Saving and pausing game...");
            this.getConfigurationManager().saveAllPlayerData();
            this.saveAllDimensions(false);
        }
    }

    /** Called by mc.loadWorld(null) when the world is left: saves and closes the world right away. */
    public void setServerStopping()
    {
        super.setServerStopping();

        if (this.stopped)
        {
            return;
        }

        this.stopped = true;

        if (this.isWorldBeingDeleted())
        {
            this.flushAll();
        }
        else
        {
            this.mc.loadingScreen.resetProgresAndWorkingMessage("Saving world");
            this.fmlEvent("handleServerStopping");

            try
            {
                this.stopServer();
            }
            catch (RuntimeException var3)
            {
                KirUtil.log("Could not save the world", var3);
            }
        }

        // What the server thread sets once it has stopped. Forge's Minecraft.loadWorld(null) waits for it.
        if (fieldServerStopped != null)
        {
            try
            {
                fieldServerStopped.setBoolean(this, true);
            }
            catch (IllegalAccessException var2)
            {
                ;
            }
        }
    }

    boolean isWorldBeingDeleted()
    {
        try
        {
            return KirLocalGame.fieldWorldIsBeingDeleted.getBoolean(this);
        }
        catch (IllegalAccessException var2)
        {
            return false;
        }
    }

    /** Closes the region files without saving. */
    void flushAll()
    {
        if (this.theWorldServer != null)
        {
            for (int var1 = 0; var1 < this.theWorldServer.length; ++var1)
            {
                if (this.theWorldServer[var1] != null)
                {
                    this.theWorldServer[var1].flush();
                }
            }
        }
    }

    public boolean isStopped()
    {
        return this.stopped;
    }

    /* ------------------------------------------------------------------ Status */

    public long getStartTime()
    {
        return this.startTime;
    }

    /** Ticks per second over the last 40 ticks (20 when it keeps up), or -1 if too few ran yet. */
    public double getTicksPerSecond()
    {
        int var1 = Math.min(this.tickTimeCount, this.tickTimes.length);

        if (var1 < 2)
        {
            return -1.0D;
        }

        long var2 = this.tickTimes[(this.tickTimeCount - 1) % this.tickTimes.length];
        long var4 = this.tickTimes[(this.tickTimeCount - var1) % this.tickTimes.length];

        // Paused for a while: the last ticks are old news.
        if (System.nanoTime() - var2 > 1000000000L)
        {
            return -1.0D;
        }

        return var2 > var4 ? (double)(var1 - 1) * 1.0E9D / (double)(var2 - var4) : -1.0D;
    }

    /** A connection point that never accepts anyone: the local player isn't a network player. */
    static class DummyListenThread extends IntegratedServerListenThread
    {
        DummyListenThread(IntegratedServer server) throws IOException
        {
            super(server);
        }

        public void func_71754_a(MemoryConnection par1MemoryConnection, String par2Str) {}

        public String func_71755_c() throws IOException
        {
            throw new IOException("This world runs without the integrated server");
        }

        public void networkTick() {}

        public boolean func_71752_f()
        {
            return false;
        }
    }
}
