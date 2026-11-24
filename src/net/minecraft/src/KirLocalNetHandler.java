package net.minecraft.src;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * The "connection" of a local game (Integrated Server Off, see KirLocalGame): mc.thePlayer.sendQueue.
 * Screens and the player hand it the packets they would send to a server. Most of what those packets ask
 * for has already happened, since client and server share one world, so they are dropped. The rest is
 * done here: chat and commands, tab completion, leaving a bed, respawning, signs and dropping an item.
 *
 * It is made without a constructor (NetClientHandler's open a connection), so its fields are set here.
 */
public class KirLocalNetHandler extends NetClientHandler
{
    private static final Field fieldMc = KirUtil.findField(NetClientHandler.class, "mc", "h");
    private static final Field fieldWorldClient = KirUtil.findField(NetClientHandler.class, "worldClient", "i");
    private static final Field fieldNetManager = KirUtil.findField(NetClientHandler.class, "netManager", "g");
    private static final Field fieldPlayerInfoMap = KirUtil.findField(NetClientHandler.class, "playerInfoMap", "k");

    private static Object unsafe;
    private static Method methodAllocateInstance;

    static
    {
        try
        {
            Class var0 = Class.forName("sun.misc.Unsafe");
            Field var1 = var0.getDeclaredField("theUnsafe");
            var1.setAccessible(true);
            unsafe = var1.get((Object)null);
            methodAllocateInstance = var0.getMethod("allocateInstance", new Class[] {Class.class});
        }
        catch (Exception var2)
        {
            KirUtil.log("Could not find sun.misc.Unsafe, Integrated Server Off is unavailable", var2);
        }
    }

    private Minecraft minecraft;

    /** A paused-or-not flag Minecraft sets on the connection each frame; a real one, so the cast works. */
    private MemoryConnection connection;

    /** Never called: create() makes the instance without a constructor. */
    private KirLocalNetHandler(Minecraft mc) throws java.io.IOException
    {
        super(mc, (String)null, 0);
    }

    static boolean isAvailable()
    {
        return methodAllocateInstance != null && fieldMc != null && fieldWorldClient != null && fieldNetManager != null && fieldPlayerInfoMap != null;
    }

    static KirLocalNetHandler create(Minecraft mc) throws Exception
    {
        KirLocalNetHandler var1 = (KirLocalNetHandler)methodAllocateInstance.invoke(unsafe, new Object[] {KirLocalNetHandler.class});
        var1.minecraft = mc;
        var1.connection = new MemoryConnection(var1);
        fieldMc.set(var1, mc);
        fieldNetManager.set(var1, var1.connection);
        fieldPlayerInfoMap.set(var1, new HashMap());
        var1.playerInfoList = new ArrayList();
        var1.currentServerMaxPlayers = 1;
        var1.mapStorage = null;
        return var1;
    }

    void setWorld(WorldClient world)
    {
        try
        {
            fieldWorldClient.set(this, world);
        }
        catch (IllegalAccessException var3)
        {
            ;
        }
    }

    public NetworkManager getNetManager()
    {
        return this.connection;
    }

    public void processReadPackets() {}

    public void cleanup()
    {
        this.setWorld((WorldClient)null);
    }

    public void disconnect() {}

    public void quitWithPacket(Packet par1Packet) {}

    public boolean canProcessPackets()
    {
        return true;
    }

    /** What the server would have done with the packet, for the few that still need doing. */
    public void addToSendQueue(Packet par1Packet)
    {
        Minecraft var2 = this.minecraft;
        EntityClientPlayerMP var3 = var2.thePlayer;

        if (!(var3 instanceof KirLocalPlayer))
        {
            return;
        }

        KirLocalPlayer var4 = (KirLocalPlayer)var3;

        if (par1Packet instanceof Packet3Chat)
        {
            KirLocalGame.handleChat(var2, var4, ((Packet3Chat)par1Packet).message);
        }
        else if (par1Packet instanceof Packet203AutoComplete)
        {
            this.complete(var4, ((Packet203AutoComplete)par1Packet).func_73473_d());
        }
        else if (par1Packet instanceof Packet19EntityAction)
        {
            if (((Packet19EntityAction)par1Packet).state == 3 && var4.isPlayerSleeping())
            {
                var4.wakeUpPlayer(false, true, true);
            }
        }
        else if (par1Packet instanceof Packet205ClientCommand)
        {
            if (((Packet205ClientCommand)par1Packet).forceRespawn == 1)
            {
                KirLocalGame.respawn(var2);
            }
        }
        else if (par1Packet instanceof Packet130UpdateSign)
        {
            this.updateSign(var4, (Packet130UpdateSign)par1Packet);
        }
        else if (par1Packet instanceof Packet14BlockDig && ((Packet14BlockDig)par1Packet).status == 4)
        {
            var4.dropOneItem();
        }
    }

    private void complete(EntityPlayer player, String text)
    {
        KirLocalServer var3 = KirLocalGame.getServer(this.minecraft);

        if (var3 == null)
        {
            return;
        }

        StringBuilder var4 = new StringBuilder();
        Iterator var5 = var3.getPossibleCompletions(player, text).iterator();

        while (var5.hasNext())
        {
            if (var4.length() > 0)
            {
                var4.append("\u0000");
            }

            var4.append((String)var5.next());
        }

        this.handleAutoComplete(new Packet203AutoComplete(var4.toString()));
    }

    /** The sign screen edited the tile entity itself; it only has to be saved and redrawn. */
    private void updateSign(EntityPlayer player, Packet130UpdateSign packet)
    {
        World var3 = player.worldObj;

        if (var3.blockExists(packet.xPosition, packet.yPosition, packet.zPosition))
        {
            TileEntity var4 = var3.getBlockTileEntity(packet.xPosition, packet.yPosition, packet.zPosition);

            if (var4 instanceof TileEntitySign)
            {
                TileEntitySign var5 = (TileEntitySign)var4;

                for (int var6 = 0; var6 < 4; ++var6)
                {
                    String var7 = packet.signLines[var6];
                    var5.signText[var6] = var7.length() > 15 ? var7.substring(0, 15) : var7;
                }

                var5.onInventoryChanged();
                var3.markBlockNeedsUpdate(packet.xPosition, packet.yPosition, packet.zPosition);
            }
        }
    }
}
