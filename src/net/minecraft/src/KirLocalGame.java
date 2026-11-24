package net.minecraft.src;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Integrated Server Off: singleplayer worlds run on the client's own thread, in one shared world, the way
 * Minecraft did before 1.3. There is no server thread, no network connection and no second copy of the
 * world or the player.
 *
 * How a world gets started that way, without changing Minecraft.launchIntegratedServer (Forge patches it):
 * vanilla makes its IntegratedServer and starts its thread. The first thing that thread does is log
 * "Starting integrated minecraft server"; a Filter on the server logger parks the thread right there, before
 * it touches the save. The client thread is meanwhile polling the server in a loop that calls
 * mc.loadingScreen every 200 ms; mc.loadingScreen is a KirLoadingScreen, which then builds the local game
 * (KirLocalServer, KirLocalWorld, KirLocalPlayer), puts the KirLocalServer in mc.theIntegratedServer and
 * marks it running, so the loop ends. The parked vanilla server never loads anything and stays parked. If
 * building the local game fails, the parked server is let go and the world opens the normal way.
 *
 * The pieces:
 * - KirLocalServer: an IntegratedServer that is never started. It owns the worlds (KirLocalWorldServer),
 *   saves them, and is ticked from the client's world tick, so the game still pauses with the menu.
 * - KirLocalWorld: the client's mc.theWorld. It shares the server world's chunks, entity lists and world
 *   info, so the renderer draws the real world; anything that would change it is passed to the server world.
 * - KirLocalPlayer: mc.thePlayer, living in the server world (no EntityPlayerMP).
 * - KirLocalController: block breaking, placing and using items, done directly on the world.
 * - KirLocalNetHandler: the send queue the player and screens send packets to; it answers the few that
 *   matter (chat, leaving a bed, respawning) and drops the rest.
 */
public class KirLocalGame
{
    static final Field fieldTheIntegratedServer = KirUtil.findField(Minecraft.class, "theIntegratedServer", "Z");
    static final Field fieldIntegratedServerIsRunning = KirUtil.findField(Minecraft.class, "integratedServerIsRunning", "al");
    static final Field fieldServerIsRunning = KirUtil.findField(MinecraftServer.class, "serverIsRunning", "Q");
    static final Field fieldTickCounter = KirUtil.findField(MinecraftServer.class, "tickCounter", "w");
    static final Field fieldWorldIsBeingDeleted = KirUtil.findField(MinecraftServer.class, "worldIsBeingDeleted", "O");
    static final Field fieldServerSettings = KirUtil.findField(IntegratedServer.class, "field_71350_m", "m");
    static final Field fieldHostPlayerData = KirUtil.findField(IntegratedPlayerList.class, "tagsForLastWrittenPlayer", "e");

    /** EntityPlayer.attackEntityFrom, EntityLiving.heal etc., called past EntityClientPlayerMP's overrides. */
    static final MethodHandle superAttackEntityFrom = findSuper(EntityPlayer.class, new Class[] {DamageSource.class, Integer.TYPE}, "attackEntityFrom", "a");
    static final MethodHandle superHeal = findSuper(EntityLiving.class, new Class[] {Integer.TYPE}, "heal", "i");
    static final MethodHandle superDamageEntity = findSuper(EntityPlayer.class, new Class[] {DamageSource.class, Integer.TYPE}, "damageEntity", "d");
    static final MethodHandle superJoinEntityItem = findSuper(EntityPlayer.class, new Class[] {EntityItem.class}, "joinEntityItemWithWorld", "a");
    static final MethodHandle superUpdateEntity = findSuper(World.class, new Class[] {Entity.class, Boolean.TYPE}, "updateEntityWithOptionalForce", "a");

    /** The vanilla server parked by the log filter, waiting for the client thread to decide. */
    private static volatile Parked parked;

    private static boolean filterInstalled;
    private static boolean launching;
    private static boolean warnedUnavailable;

    /** A dimension change, respawn or the End credits asked for during the tick, done right after it. */
    private static int pendingDimension = Integer.MIN_VALUE;
    private static boolean pendingCredits;

    public static boolean isAvailable()
    {
        return fieldTheIntegratedServer != null && fieldIntegratedServerIsRunning != null && fieldServerIsRunning != null && fieldTickCounter != null && fieldWorldIsBeingDeleted != null && fieldServerSettings != null && fieldHostPlayerData != null && superAttackEntityFrom != null && superHeal != null && superDamageEntity != null && superUpdateEntity != null && superJoinEntityItem != null && KirLocalNetHandler.isAvailable() && KirLocalController.isAvailable();
    }

    /** Whether new singleplayer worlds should run locally (Integrated Server Off). */
    static boolean wantsLocal()
    {
        KirModuleIntegratedServer var0 = mod_Kirmod.INTEGRATED_SERVER;
        return var0.isEnabled() && var0.getValue() == 0 && isAvailable();
    }

    /** Whether the world being played is a local one. */
    public static boolean isLocal(Minecraft mc)
    {
        return mc != null && mc.getIntegratedServer() instanceof KirLocalServer;
    }

    public static KirLocalServer getServer(Minecraft mc)
    {
        return mc != null && mc.getIntegratedServer() instanceof KirLocalServer ? (KirLocalServer)mc.getIntegratedServer() : null;
    }

    /** Puts the log filter and the loading screen hook in. Cheap once done; called every frame. */
    public static void install(Minecraft mc)
    {
        if (!filterInstalled)
        {
            filterInstalled = true;
            Logger var1 = MinecraftServer.logger;
            var1.setFilter(new ParkFilter(var1.getFilter()));
        }

        if (mc != null && mc.loadingScreen != null && mc.loadingScreen.getClass() == LoadingScreenRenderer.class)
        {
            mc.loadingScreen = new KirLoadingScreen(mc);
        }
    }

    /* ------------------------------------------------------------------ Starting */

    /**
     * Server thread: parks the vanilla integrated server as it starts, if the world should run locally.
     * Returns only if it should start after all.
     */
    static void maybePark()
    {
        MinecraftServer var0 = MinecraftServer.getServer();
        Minecraft var1 = ModLoader.getMinecraftInstance();

        if (!(var0 instanceof IntegratedServer) || var0 instanceof KirLocalServer || var1 == null || var1.isDemo() || !wantsLocal() || var1.getIntegratedServer() != var0)
        {
            return;
        }

        Parked var2 = new Parked((IntegratedServer)var0);

        synchronized (var2)
        {
            parked = var2;

            // Parked for good once the world runs locally; only a failed local start lets it go on.
            while (!var2.release)
            {
                try
                {
                    var2.wait();
                }
                catch (InterruptedException var5)
                {
                    ;
                }
            }
        }
    }

    /** Client thread, from the loading screen while launchIntegratedServer waits: starts the local game. */
    static void onLoadingScreen(Minecraft mc)
    {
        Parked var10 = parked;

        if (launching || var10 == null || mc.getIntegratedServer() != var10.server)
        {
            return;
        }

        IntegratedServer var1 = var10.server;
        parked = null;
        launching = true;

        try
        {
            KirLocalServer var2 = null;

            try
            {
                var2 = start(mc, var1.getFolderName(), var1.getWorldName(), (WorldSettings)fieldServerSettings.get(var1));
            }
            catch (Throwable var8)
            {
                KirUtil.log("Could not start the world without the integrated server, starting it normally", var8);

                if (var2 != null || mc.theWorld != null)
                {
                    abandon(mc);
                }

                fieldTheIntegratedServer.set(mc, var1);
                fieldIntegratedServerIsRunning.setBoolean(mc, true);

                synchronized (var10)
                {
                    var10.release = true;
                    var10.notifyAll();
                }
            }
        }
        catch (IllegalAccessException var9)
        {
            KirUtil.log("Kirmod could not hand over the world", var9);
        }
        finally
        {
            launching = false;
        }
    }

    /** Builds and enters the local game. */
    private static KirLocalServer start(Minecraft mc, String folder, String name, WorldSettings settings) throws Exception
    {
        KirUtil.log("Starting \"" + folder + "\" without the integrated server", (Throwable)null);
        KirLocalServer var4 = new KirLocalServer(mc, folder, name, settings);
        fieldTheIntegratedServer.set(mc, var4);
        fieldIntegratedServerIsRunning.setBoolean(mc, true);
        var4.startLocal();
        KirLocalNetHandler var5 = KirLocalNetHandler.create(mc);
        KirLocalController var6 = new KirLocalController(mc, var5);
        WorldInfo var7 = var4.worldServerForDimension(0).getWorldInfo();
        NBTTagCompound var8 = var7.getPlayerNBTTagCompound();
        int var9 = var8 != null && var8.hasKey("Dimension") ? var8.getInteger("Dimension") : 0;
        WorldServer var10 = var4.worldServerForDimension(var9);
        var6.setGameTypeQuietly(var7.getGameType());
        var6.spawnData = var8;
        mc.playerController = var6;
        mc.loadWorld(new KirLocalWorld(var5, var4, var10));
        var6.spawnData = null;
        EnumGameType var11 = mc.thePlayer instanceof KirLocalPlayer ? ((KirLocalPlayer)mc.thePlayer).savedGameType : null;
        var6.setGameType(var11 != null ? var11 : var7.getGameType());
        // Lets launchIntegratedServer's wait loop end; it reads the field again each time round.
        fieldServerIsRunning.setBoolean(var4, true);
        return var4;
    }

    /** Takes a half-built local game down again without saving. */
    private static void abandon(Minecraft mc)
    {
        try
        {
            KirLocalServer var1 = getServer(mc);

            if (var1 != null)
            {
                fieldWorldIsBeingDeleted.setBoolean(var1, true);
            }

            mc.loadWorld((WorldClient)null);
        }
        catch (Throwable var2)
        {
            KirUtil.log("Could not clean up", var2);
        }
    }

    /* ------------------------------------------------------------------ Players */

    /** Makes the local player for mc.loadWorld: from the saved data, or at the world spawn. */
    static KirLocalPlayer createPlayer(Minecraft mc, KirLocalController controller, World facade, NBTTagCompound data, EntityPlayer respawnFrom, boolean keepEverything)
    {
        KirLocalWorld var6 = (KirLocalWorld)facade;
        WorldServer var7 = var6.target;
        KirLocalPlayer var8 = new KirLocalPlayer(mc, var7, mc.session, var6.netHandler);
        var8.dimension = var7.provider.worldType;

        if (data != null)
        {
            var8.readFromNBT(data);
            var8.setWorld(var7);
            var8.dimension = var7.provider.worldType;
        }
        else
        {
            if (respawnFrom != null)
            {
                var8.clonePlayer(respawnFrom, keepEverything);
                var8.entityId = respawnFrom.entityId;
                var8.dimension = var7.provider.worldType;
            }

            ChunkCoordinates var9 = respawnFrom != null ? respawnFrom.getSpawnChunk() : null;
            ChunkCoordinates var10 = var9 != null ? EntityPlayer.verifyRespawnCoordinates(var7, var9) : null;

            if (var10 != null)
            {
                var8.setLocationAndAngles((double)((float)var10.posX + 0.5F), (double)((float)var10.posY + 0.1F), (double)((float)var10.posZ + 0.5F), 0.0F, 0.0F);
                var8.setSpawnChunk(var9);
            }
            else
            {
                if (var9 != null)
                {
                    var8.addChatMessage("tile.bed.notValid");
                }

                placeAtSpawn(var8, var7, controller);
            }
        }

        loadChunksAround(var7, var8, 2);

        while (!var7.getCollidingBoundingBoxes(var8, var8.boundingBox).isEmpty() && var8.posY < 256.0D)
        {
            var8.setPosition(var8.posX, var8.posY + 1.0D, var8.posZ);
        }

        return var8;
    }

    /** Where a new player starts, like EntityPlayerMP's constructor. */
    private static void placeAtSpawn(EntityPlayer player, WorldServer world, KirLocalController controller)
    {
        ChunkCoordinates var3 = world.getSpawnPoint();
        int var4 = var3.posX;
        int var5 = var3.posZ;
        int var6 = var3.posY;

        if (!world.provider.hasNoSky && !controller.isAdventure())
        {
            var4 += world.rand.nextInt(20) - 10;
            var5 += world.rand.nextInt(20) - 10;
            world.theChunkProviderServer.loadChunk(var4 >> 4, var5 >> 4);
            var6 = world.getTopSolidOrLiquidBlock(var4, var5);
        }

        player.setLocationAndAngles((double)var4 + 0.5D, (double)var6, (double)var5 + 0.5D, 0.0F, 0.0F);
    }

    /** Loads the chunks within radius of the entity right now, so it has ground under it. */
    static void loadChunksAround(WorldServer world, Entity entity, int radius)
    {
        int var3 = MathHelper.floor_double(entity.posX) >> 4;
        int var4 = MathHelper.floor_double(entity.posZ) >> 4;

        for (int var5 = -radius; var5 <= radius; ++var5)
        {
            for (int var6 = -radius; var6 <= radius; ++var6)
            {
                world.theChunkProviderServer.loadChunk(var3 + var5, var4 + var6);
            }
        }
    }

    /** Asked for by the player during the tick: change dimension once it is over. */
    static void requestDimension(int dimension)
    {
        if (pendingDimension == Integer.MIN_VALUE && !pendingCredits)
        {
            pendingDimension = dimension;
        }
    }

    static void requestCredits()
    {
        pendingCredits = true;
        pendingDimension = Integer.MIN_VALUE;
    }

    /** Right after each local tick: does what the player asked for during it. */
    static void afterTick(Minecraft mc)
    {
        if (pendingCredits)
        {
            pendingCredits = false;

            if (mc.thePlayer instanceof KirLocalPlayer)
            {
                // Like the server: out of the End's world now, the credits, then a respawn keeping everything.
                mc.thePlayer.worldObj.setEntityDead(mc.thePlayer);
                mc.displayGuiScreen(new GuiWinGame());
            }
        }

        if (pendingDimension != Integer.MIN_VALUE)
        {
            int var1 = pendingDimension;
            pendingDimension = Integer.MIN_VALUE;

            try
            {
                changeDimension(mc, var1);
            }
            catch (RuntimeException var3)
            {
                KirUtil.log("Could not change dimension", var3);
                mc.ingameGUI.getChatGUI().printChatMessage("\u00a7cKirmod: could not go to dimension " + var1 + ": " + var3);
            }
        }
    }

    /** ServerConfigurationManager.transferPlayerToDimension for the local player, plus the client's side. */
    private static void changeDimension(Minecraft mc, int dimension)
    {
        KirLocalServer var2 = getServer(mc);

        if (var2 == null || !(mc.thePlayer instanceof KirLocalPlayer) || mc.thePlayer.dimension == dimension)
        {
            return;
        }

        KirLocalPlayer var3 = (KirLocalPlayer)mc.thePlayer;
        int var4 = var3.dimension;
        WorldServer var5 = var2.worldServerForDimension(var4);
        WorldServer var6 = var2.worldServerForDimension(dimension);

        if (var5 == null || var6 == null)
        {
            throw new IllegalStateException("dimension " + (var5 == null ? var4 : dimension) + " isn't loaded");
        }

        var3.dimension = dimension;
        var5.removeEntity(var3);
        var3.isDead = false;
        double var7 = var3.posX;
        double var9 = var3.posZ;

        if (dimension == -1)
        {
            var7 /= 8.0D;
            var9 /= 8.0D;
        }
        else if (dimension == 0)
        {
            var7 *= 8.0D;
            var9 *= 8.0D;
        }
        else
        {
            ChunkCoordinates var11 = var6.getEntrancePortalLocation();
            var7 = (double)var11.posX;
            var3.posY = (double)var11.posY;
            var9 = (double)var11.posZ;
            var3.rotationYaw = 90.0F;
            var3.rotationPitch = 0.0F;
        }

        var3.setWorld(var6);

        if (var4 != 1)
        {
            var7 = (double)MathHelper.clamp_int((int)var7, -29999872, 29999872);
            var9 = (double)MathHelper.clamp_int((int)var9, -29999872, 29999872);
        }

        var3.setLocationAndAngles(var7, var3.posY, var9, var3.rotationYaw, var3.rotationPitch);
        loadChunksAround(var6, var3, 2);

        if (var4 != 1)
        {
            (new Teleporter()).placeInPortal(var6, var3);
        }

        int var12 = var3.getHealth();
        float var13 = var3.timeInPortal;
        mc.loadWorld(new KirLocalWorld(((KirLocalWorld)mc.theWorld).netHandler, var2, var6));
        var3.setEntityHealth(var12);
        var3.timeInPortal = var13;
        var3.prevTimeInPortal = var13;
        var3.timeUntilPortal = 10;
    }

    /** The respawn button (dead, or after the End credits). */
    static void respawn(Minecraft mc)
    {
        KirLocalServer var1 = getServer(mc);

        if (var1 == null || !(mc.thePlayer instanceof KirLocalPlayer) || !(mc.playerController instanceof KirLocalController))
        {
            return;
        }

        KirLocalPlayer var2 = (KirLocalPlayer)mc.thePlayer;
        boolean var3 = var2.conqueredTheEnd;

        if (!var3 && var1.isHardcore())
        {
            deleteWorld(mc, var1);
            return;
        }

        KirLocalController var4 = (KirLocalController)mc.playerController;
        WorldServer var5 = var2.worldObj instanceof WorldServer ? (WorldServer)var2.worldObj : var1.worldServerForDimension(var2.dimension);
        var5.removeEntity(var2);
        int var6 = var3 ? 0 : (var5.provider.canRespawnHere() ? var5.provider.worldType : 0);
        WorldServer var7 = var1.worldServerForDimension(var6);

        if (var7 == null)
        {
            KirUtil.log("Could not respawn: dimension " + var6 + " isn't loaded", (Throwable)null);
            return;
        }
        var4.respawnFrom = var2;
        var4.keepEverything = var3;

        try
        {
            if (mc.theWorld instanceof KirLocalWorld && ((KirLocalWorld)mc.theWorld).target == var7)
            {
                // Same dimension: a new player in the same world, like Minecraft.setDimensionAndSpawnPlayer.
                mc.renderViewEntity = null;
                EntityClientPlayerMP var8 = var4.func_78754_a(mc.theWorld);
                mc.thePlayer = var8;
                mc.renderViewEntity = var8;
                var8.preparePlayerToSpawn();
                mc.theWorld.spawnEntityInWorld(var8);
                var4.flipPlayer(var8);
                var8.movementInput = new MovementInputFromOptions(mc.gameSettings);
                var4.func_78748_a(var8);
            }
            else
            {
                mc.thePlayer = null;
                mc.loadWorld(new KirLocalWorld(((KirLocalWorld)mc.theWorld).netHandler, var1, var7));
            }
        }
        finally
        {
            var4.respawnFrom = null;
            var4.keepEverything = false;
        }

        if (mc.currentScreen instanceof GuiGameOver || mc.currentScreen instanceof GuiWinGame)
        {
            mc.displayGuiScreen((GuiScreen)null);
        }
    }

    /** Hardcore death: the world is deleted, like the integrated server does for its owner. */
    private static void deleteWorld(Minecraft mc, KirLocalServer server)
    {
        String var2 = server.getFolderName();

        try
        {
            fieldWorldIsBeingDeleted.setBoolean(server, true);
        }
        catch (IllegalAccessException var4)
        {
            ;
        }

        server.flushAll();
        mc.loadWorld((WorldClient)null);
        mc.getSaveLoader().flushCache();
        mc.getSaveLoader().deleteWorldDirectory(var2);
        mc.displayGuiScreen(new GuiMainMenu());
    }

    /* ------------------------------------------------------------------ Chat */

    /** What the integrated server would do with a chat line: commands, or the line itself. */
    static void handleChat(Minecraft mc, KirLocalPlayer player, String message)
    {
        message = message.trim();

        if (message.length() == 0)
        {
            return;
        }

        if (!message.startsWith("/"))
        {
            mc.ingameGUI.getChatGUI().printChatMessage("<" + player.username + "> " + message);
            return;
        }

        if (KirLocalCommands.handle(mc, player, message.substring(1)))
        {
            return;
        }

        KirLocalServer var3 = getServer(mc);

        if (var3 != null)
        {
            var3.getCommandManager().executeCommand(player, message);
        }
    }

    /* ------------------------------------------------------------------ World helpers (both world classes) */

    /** WorldServer.setEntityState sends a packet for the client's copy; here the effect is played directly. */
    static void playEntityState(Entity entity, byte state)
    {
        // 2 (hurt) and 3 (death) already happened in this world; 9 is the player finishing eating.
        if (state != 2 && state != 3 && (state != 9 || !(entity instanceof EntityPlayer)))
        {
            try
            {
                entity.handleHealthUpdate(state);
            }
            catch (RuntimeException var3)
            {
                ;
            }
        }
    }

    /**
     * World.updateEntityWithOptionalForce for an entity the local player rides: WorldServer skips those and
     * leaves them to the rider's packets, which there aren't here.
     */
    static boolean updateRiddenEntity(World world, Entity entity, boolean force)
    {
        if (!(entity.riddenByEntity instanceof KirLocalPlayer))
        {
            return false;
        }

        invoke(superUpdateEntity, new Object[] {world, entity, Boolean.valueOf(force)});
        return true;
    }

    /** Swaps entities spawned into a local world for ones that work without the network (ender pearls). */
    static Entity replaceSpawned(World world, Entity entity)
    {
        if (entity != null && entity.getClass() == EntityEnderPearl.class && ((EntityEnderPearl)entity).thrower instanceof KirLocalPlayer)
        {
            return new KirLocalEnderPearl(world, ((EntityEnderPearl)entity).thrower);
        }

        return entity;
    }

    /* ------------------------------------------------------------------ Reflection */

    /** A handle that calls owner's own version of a method on any instance, past subclass overrides. */
    private static MethodHandle findSuper(Class owner, Class[] params, String ... names)
    {
        try
        {
            Field var3 = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            var3.setAccessible(true);
            MethodHandles.Lookup var4 = (MethodHandles.Lookup)var3.get((Object)null);

            for (int var5 = 0; var5 < names.length; ++var5)
            {
                try
                {
                    Method var6 = owner.getDeclaredMethod(names[var5], params);
                    return var4.unreflectSpecial(var6, owner);
                }
                catch (NoSuchMethodException var7)
                {
                    ;
                }
            }

            KirUtil.log("Method " + owner.getName() + "." + names[0] + " not found", (Throwable)null);
        }
        catch (Throwable var8)
        {
            KirUtil.log("Could not look up " + owner.getName() + "." + names[0], var8);
        }

        return null;
    }

    static Object invoke(MethodHandle handle, Object ... args)
    {
        try
        {
            return handle.invokeWithArguments(args);
        }
        catch (RuntimeException var3)
        {
            throw var3;
        }
        catch (Error var4)
        {
            throw var4;
        }
        catch (Throwable var5)
        {
            throw new RuntimeException(var5);
        }
    }

    /* ------------------------------------------------------------------ Hooks */

    private static class Parked
    {
        final IntegratedServer server;
        boolean release;

        Parked(IntegratedServer server)
        {
            this.server = server;
        }
    }

    /** Parks the vanilla server thread at its first log line (see the class comment). */
    private static class ParkFilter implements Filter
    {
        private final Filter parent;

        ParkFilter(Filter parent)
        {
            this.parent = parent;
        }

        public boolean isLoggable(LogRecord record)
        {
            if (Thread.currentThread() instanceof ThreadServerApplication && record.getMessage() != null && record.getMessage().startsWith("Starting integrated minecraft server"))
            {
                try
                {
                    KirModuleIntegratedServer.noteServerStarting(MinecraftServer.getServer());
                    maybePark();
                }
                catch (RuntimeException var3)
                {
                    KirUtil.log("Kirmod's integrated server hook failed", var3);
                }
            }

            return this.parent == null || this.parent.isLoggable(record);
        }
    }

    /** mc.loadingScreen; starts the local game from launchIntegratedServer's wait loop. */
    static class KirLoadingScreen extends LoadingScreenRenderer
    {
        private final Minecraft mc;

        KirLoadingScreen(Minecraft mc)
        {
            super(mc);
            this.mc = mc;
        }

        public void resetProgresAndWorkingMessage(String par1Str)
        {
            super.resetProgresAndWorkingMessage(par1Str);
            onLoadingScreen(this.mc);
        }
    }
}
