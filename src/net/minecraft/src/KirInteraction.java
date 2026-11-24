package net.minecraft.src;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Right-click and pickup handling for Tile Suppressor and the modules' hooks (Kirmod Extras: Sneak Place, Painting Picker, Item Magnet, XP Pickup), on both
 * sides, and the queue of work for the integrated server's thread (runOnServer: /rename, Painting Picker).
 *
 * Client: mc.playerController is swapped for a KirPlayerController, which shows Tile Suppressor's
 * message instead of opening a frozen tile entity, and does Sneak Place's placement.
 *
 * Server: the integrated server's host player gets a KirItemInWorldManager, which does Sneak Place's
 * placement, pulls items in for Item Magnet and absorbs experience orbs for XP Pickup. The server
 * decides on its own, from its own copy of the player (sneaking, held item), so it validates everything
 * the client does. A remote server has no such handler, so Sneak Place, Item Magnet and XP Pickup stay
 * vanilla there: the client only places instead
 * of opening when serverHandlesSneakPlace() says the server will do the same. Other LAN players are
 * left vanilla too, as the settings are the host's.
 */
public class KirInteraction
{
    private static final Method methodSyncCurrentPlayItem = KirUtil.findMethod(PlayerControllerMP.class, "syncCurrentPlayItem", "j");

    /** Tile Suppressor's message is shown at most this often while right-click is held. */
    private static final long MESSAGE_INTERVAL_MS = 1000L;

    private static boolean warnedController = false;
    private static long lastMessageTime = 0L;

    /** Work for the integrated server's thread, for the host player (see runOnServer). */
    private static final List serverTasks = new ArrayList();

    /** Chat lines from server tasks, shown on the client thread. */
    private static final List clientMessages = new ArrayList();

    /** Something to do to the host player on the integrated server's thread. */
    public interface ServerTask
    {
        /** Runs on the server thread; returns a chat line for the client, or null. */
        String run(EntityPlayer host);
    }

    /**
     * Queues a task for the integrated server's thread, where it runs with the host player during the
     * next tick of the world they are in (from KirWorldHook). Changing server objects from the client
     * thread could break the server's tick.
     */
    public static void runOnServer(ServerTask task)
    {
        Minecraft var1 = ModLoader.getMinecraftInstance();

        // Integrated Server Off: the client's player is the world's player, and this is its thread.
        if (KirLocalGame.isLocal(var1) && var1.thePlayer != null)
        {
            String var2;

            try
            {
                var2 = task.run(var1.thePlayer);
            }
            catch (RuntimeException var4)
            {
                KirUtil.log("A Kirmod server task failed", var4);
                var2 = "\u00a7cKirmod: " + var4;
            }

            if (var2 != null)
            {
                var1.ingameGUI.getChatGUI().printChatMessage(var2);
            }

            return;
        }

        synchronized (serverTasks)
        {
            serverTasks.add(task);
        }

        KirWorldHook.installAll(ModLoader.getMinecraftInstance());
    }

    /** Runs the queued tasks if the host player is in this server world. Server thread. */
    static void runServerTasks(World world)
    {
        ArrayList var1;

        synchronized (serverTasks)
        {
            if (serverTasks.isEmpty())
            {
                return;
            }

            var1 = null;
        }

        EntityPlayerMP var2 = null;

        for (int var3 = 0; var3 < world.playerEntities.size(); ++var3)
        {
            Object var4 = world.playerEntities.get(var3);

            if (var4 instanceof EntityPlayerMP && isHost((EntityPlayerMP)var4))
            {
                var2 = (EntityPlayerMP)var4;
            }
        }

        if (var2 == null)
        {
            return;
        }

        synchronized (serverTasks)
        {
            var1 = new ArrayList(serverTasks);
            serverTasks.clear();
        }

        for (int var5 = 0; var5 < var1.size(); ++var5)
        {
            String var6;

            try
            {
                var6 = ((ServerTask)var1.get(var5)).run(var2);
            }
            catch (RuntimeException var8)
            {
                KirUtil.log("A Kirmod server task failed", var8);
                var6 = "\u00a7cKirmod: " + var8;
            }

            if (var6 != null)
            {
                synchronized (clientMessages)
                {
                    clientMessages.add(var6);
                }
            }
        }
    }

    /** Prints the server tasks' chat lines. Client thread, every frame. */
    static void showClientMessages(Minecraft mc)
    {
        synchronized (clientMessages)
        {
            if (clientMessages.isEmpty() || mc.ingameGUI == null)
            {
                return;
            }

            for (int var1 = 0; var1 < clientMessages.size(); ++var1)
            {
                mc.ingameGUI.getChatGUI().printChatMessage((String)clientMessages.get(var1));
            }

            clientMessages.clear();
        }
    }

    /** Swaps mc.playerController for a KirPlayerController. Called every frame; cheap once done. */
    public static void installClient(Minecraft mc)
    {
        PlayerControllerMP var1 = mc.playerController;

        if (var1 == null || var1 instanceof KirPlayerController || methodSyncCurrentPlayItem == null)
        {
            return;
        }

        if (var1.getClass() != PlayerControllerMP.class)
        {
            if (!warnedController)
            {
                warnedController = true;
                KirUtil.log("The player controller is already changed by another mod (" + var1.getClass().getName() + "), Sneak Place and the tile suppressor message are unavailable", (Throwable)null);
            }

            return;
        }

        try
        {
            KirPlayerController var2 = new KirPlayerController(mc, (NetClientHandler)null);
            KirUtil.copyFields(var1, var2);
            mc.playerController = var2;
        }
        catch (Exception var3)
        {
            KirUtil.log("Could not hook into block clicks", var3);
        }
    }

    public static boolean isClientAvailable()
    {
        return methodSyncCurrentPlayItem != null && !warnedController;
    }

    /**
     * Gives the integrated server's host player a KirItemInWorldManager. Called from KirWorldHook on the
     * server thread, every tick, for each of the integrated server's worlds; the handler is replaced
     * whenever the server makes a new player (respawn, changing dimension).
     */
    static void updateServerWorld(World world)
    {
        if (!mod_Kirmod.hasServerPlayerTicks())
        {
            return;
        }

        installPlayerFileData(world);

        List var1 = world.playerEntities;

        for (int var2 = 0; var2 < var1.size(); ++var2)
        {
            Object var3 = var1.get(var2);

            if (var3 instanceof EntityPlayerMP && isHost((EntityPlayerMP)var3))
            {
                EntityPlayerMP var4 = (EntityPlayerMP)var3;
                ItemInWorldManager var5 = var4.theItemInWorldManager;

                if (var5 != null && var5.getClass() == ItemInWorldManager.class)
                {
                    try
                    {
                        KirItemInWorldManager var6 = new KirItemInWorldManager(var5.theWorld);
                        KirUtil.copyFields(var5, var6);
                        var4.theItemInWorldManager = var6;
                    }
                    catch (Exception var7)
                    {
                        KirUtil.log("Could not hook into the server's block clicks", var7);
                    }
                }
            }
        }
    }

    /** ServerConfigurationManager.playerNBTManagerObj, which saves the players. */
    private static final java.lang.reflect.Field fieldPlayerFileData = KirUtil.findField(ServerConfigurationManager.class, "field_72412_k", "k");

    /** IntegratedPlayerList's copy of the host player's data, which goes into level.dat. */
    private static final java.lang.reflect.Field fieldHostPlayerData = KirUtil.findField(IntegratedPlayerList.class, "tagsForLastWrittenPlayer", "e");

    /** Wraps the integrated server's player saver, so modules can add to the host's saved data (onSaveHostPlayer). */
    private static void installPlayerFileData(World world)
    {
        MinecraftServer var1 = MinecraftServer.getServer();

        if (fieldPlayerFileData == null || var1 == null || !(var1.getConfigurationManager() instanceof IntegratedPlayerList))
        {
            return;
        }

        try
        {
            Object var2 = fieldPlayerFileData.get(var1.getConfigurationManager());

            if (var2 != null && !(var2 instanceof KirPlayerFileData))
            {
                fieldPlayerFileData.set(var1.getConfigurationManager(), new KirPlayerFileData((IPlayerFileData)var2));
            }
        }
        catch (Exception var3)
        {
            KirUtil.log("Could not hook into player saving", var3);
        }
    }

    /**
     * Passes everything on to the server's own player saver. When the host is saved, IntegratedPlayerList has just
     * put their data where it goes into level.dat; the modules can add to it (KirModule.onSaveHostPlayer).
     */
    public static class KirPlayerFileData implements IPlayerFileData
    {
        private final IPlayerFileData parent;

        public KirPlayerFileData(IPlayerFileData parent)
        {
            this.parent = parent;
        }

        public void writePlayerData(EntityPlayer par1EntityPlayer)
        {
            this.parent.writePlayerData(par1EntityPlayer);

            if (isHost(par1EntityPlayer) && fieldHostPlayerData != null)
            {
                try
                {
                    Object var2 = fieldHostPlayerData.get(MinecraftServer.getServer().getConfigurationManager());

                    if (var2 instanceof NBTTagCompound)
                    {
                        mod_Kirmod.saveHostPlayer(par1EntityPlayer, (NBTTagCompound)var2);
                    }
                }
                catch (Exception var3)
                {
                    KirUtil.log("Could not add to the host player's saved data", var3);
                }
            }
        }

        public void readPlayerData(EntityPlayer par1EntityPlayer)
        {
            this.parent.readPlayerData(par1EntityPlayer);
        }

        public String[] getAvailablePlayerDat()
        {
            return this.parent.getAvailablePlayerDat();
        }
    }

    /** Whether player is the integrated server's owner, the one playing on this client. */
    public static boolean isHost(EntityPlayer player)
    {
        IntegratedServer var1 = ModLoader.getMinecraftInstance().getIntegratedServer();
        return var1 != null && player.username != null && player.username.equals(var1.getServerOwner());
    }

    /** Whether this client is playing on its own integrated server, where Kirmod runs the server side too. */
    public static boolean isOnIntegratedServer(Minecraft mc)
    {
        return mc.isIntegratedServerRunning() && mc.getIntegratedServer() != null;
    }

    /** Whether the server will place instead of opening when this client sneak-places. */
    public static boolean serverHandlesSneakPlace(Minecraft mc)
    {
        if (KirLocalGame.isLocal(mc))
        {
            return true;
        }

        if (!isOnIntegratedServer(mc))
        {
            return false;
        }

        try
        {
            IntegratedServer var1 = mc.getIntegratedServer();
            EntityPlayerMP var2 = var1.getConfigurationManager().getPlayerForUsername(var1.getServerOwner());
            return var2 != null && var2.theItemInWorldManager instanceof KirItemInWorldManager;
        }
        catch (RuntimeException var3)
        {
            // The player list is the server thread's; if it changed under us, just place vanilla-style.
            return false;
        }
    }

    private static void showMessage(Minecraft mc, String message)
    {
        long var2 = System.currentTimeMillis();

        if (var2 - lastMessageTime >= MESSAGE_INTERVAL_MS && mc.ingameGUI != null)
        {
            lastMessageTime = var2;
            mc.ingameGUI.getChatGUI().printChatMessage("§c" + message);
        }
    }

    /** Uses an item on a block without activating the block; the end of a vanilla right-click. */
    static boolean useItemOnBlock(EntityPlayer player, World world, ItemStack stack, int x, int y, int z, int side, float hitX, float hitY, float hitZ, boolean creative)
    {
        if (creative)
        {
            int var11 = stack.getItemDamage();
            int var12 = stack.stackSize;
            boolean var13 = stack.tryPlaceItemIntoWorld(player, world, x, y, z, side, hitX, hitY, hitZ);
            stack.setItemDamage(var11);
            stack.stackSize = var12;
            return var13;
        }
        else
        {
            return stack.tryPlaceItemIntoWorld(player, world, x, y, z, side, hitX, hitY, hitZ);
        }
    }

    public static class KirPlayerController extends PlayerControllerMP
    {
        private final Minecraft mc;

        public KirPlayerController(Minecraft par1Minecraft, NetClientHandler par2NetClientHandler)
        {
            super(par1Minecraft, par2NetClientHandler);
            this.mc = par1Minecraft;
        }

        public boolean onPlayerRightClick(EntityPlayer par1EntityPlayer, World par2World, ItemStack par3ItemStack, int par4, int par5, int par6, int par7, Vec3 par8Vec3)
        {
            if (mod_Kirmod.usesItemInsteadOfActivating(par1EntityPlayer, par3ItemStack) && serverHandlesSneakPlace(this.mc))
            {
                return this.sneakPlace(par1EntityPlayer, par2World, par3ItemStack, par4, par5, par6, par7, par8Vec3);
            }

            if (mod_Kirmod.handleRightClickBlock(this.mc, par1EntityPlayer, par2World, par3ItemStack, par4, par5, par6, par7))
            {
                return true;
            }

            String var9 = mod_Kirmod.TILE_SUPPRESSOR.enabled ? mod_Kirmod.TILE_SUPPRESSOR.getBlockedMessage(par2World, par4, par5, par6) : null;

            if (var9 != null)
            {
                showMessage(this.mc, var9);
                // Handled: nothing is sent, and the held item isn't used (no eating, no bow) either.
                return true;
            }

            return super.onPlayerRightClick(par1EntityPlayer, par2World, par3ItemStack, par4, par5, par6, par7, par8Vec3);
        }

        /** Inventory clicks go past the modules first (KirModule.onWindowClick, Kirmod 1.29). */
        public ItemStack windowClick(int par1, int par2, int par3, boolean par4, EntityPlayer par5EntityPlayer)
        {
            if (mod_Kirmod.handleWindowClick(this.mc, par1, par2, par3, par4, par5EntityPlayer))
            {
                return null;
            }

            return super.windowClick(par1, par2, par3, par4, par5EntityPlayer);
        }

        /** The vanilla right-click without block activation. The server does the same (KirItemInWorldManager). */
        private boolean sneakPlace(EntityPlayer player, World world, ItemStack stack, int x, int y, int z, int side, Vec3 hit)
        {
            try
            {
                methodSyncCurrentPlayItem.invoke(this, new Object[0]);
            }
            catch (Exception var13)
            {
                throw new RuntimeException("Kirmod could not call PlayerControllerMP.syncCurrentPlayItem", var13);
            }

            float var9 = (float)hit.xCoord - (float)x;
            float var10 = (float)hit.yCoord - (float)y;
            float var11 = (float)hit.zCoord - (float)z;

            if (stack.getItem() instanceof ItemBlock && !((ItemBlock)stack.getItem()).canPlaceItemBlockOnSide(world, x, y, z, side, player, stack))
            {
                return false;
            }

            ((EntityClientPlayerMP)player).sendQueue.addToSendQueue(new Packet15Place(x, y, z, side, player.inventory.getCurrentItem(), var9, var10, var11));
            return useItemOnBlock(player, world, stack, x, y, z, side, var9, var10, var11, this.isInCreativeMode());
        }
    }

    public static class KirItemInWorldManager extends ItemInWorldManager
    {
        public KirItemInWorldManager(World par1World)
        {
            super(par1World);
        }

        public boolean activateBlockOrUseItem(EntityPlayer par1EntityPlayer, World par2World, ItemStack par3ItemStack, int par4, int par5, int par6, int par7, float par8, float par9, float par10)
        {
            if (mod_Kirmod.usesItemInsteadOfActivating(par1EntityPlayer, par3ItemStack))
            {
                return useItemOnBlock(par1EntityPlayer, par2World, par3ItemStack, par4, par5, par6, par7, par8, par9, par10, this.isCreative());
            }

            return super.activateBlockOrUseItem(par1EntityPlayer, par2World, par3ItemStack, par4, par5, par6, par7, par8, par9, par10);
        }

        /** Called every server tick from EntityPlayerMP.onUpdate. */
        public void func_73075_a()
        {
            super.func_73075_a();
            mod_Kirmod.tickServerPlayer(this.field_73090_b);
        }
    }
}
