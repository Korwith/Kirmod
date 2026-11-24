package net.minecraft.src;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * /rename and /clearname (from InventoryManagerPlus), as CommandLib commands.
 *
 * The name is stored the way InventoryManagerPlus stored it, as "customName" in the held item's NBT, so
 * items it renamed keep their names and its item tooltips (if its jar is still installed) show Kirmod's.
 * The change is made to the integrated server's copy of the host player's item, on the server thread
 * (KirInteraction.runOnServer), and the slot is sent back to the client. A remote server would not keep
 * it, so the commands only work in singleplayer and on a LAN world you host.
 */
public class KirRename
{
    public static final String TAG = "customName";

    private static final Map COLORS = new HashMap();
    private static final Map STYLES = new HashMap();

    static
    {
        String[] var0 = new String[] {"black", "blue", "green", "cyan", "red", "purple", "orange", "grey", "darkgrey", "lightblue", "lime", "aqua", "lightred", "magenta", "yellow", "white"};

        for (int var1 = 0; var1 < var0.length; ++var1)
        {
            COLORS.put(var0[var1], "\u00a7" + Integer.toHexString(var1));
        }

        COLORS.put("gray", "\u00a77");
        COLORS.put("darkgray", "\u00a78");
        STYLES.put("bold", "\u00a7l");
        STYLES.put("italic", "\u00a7o");
        STYLES.put("strike", "\u00a7m");
        STYLES.put("underline", "\u00a7n");
    }

    /** The /rename name of a stack, or null. */
    public static String getCustomName(ItemStack stack)
    {
        if (stack == null || stack.stackTagCompound == null || !stack.stackTagCompound.hasKey(TAG))
        {
            return null;
        }

        String var1 = stack.stackTagCompound.getString(TAG);
        return var1.length() > 0 ? var1 : null;
    }

    static void registerCommands()
    {
        mod_CommandLib.registerCommand(new ClientCommand("rename", "[color] [bold|italic|strike|underline] <name>", "Renames the item in your hand (singleplayer / LAN host)")
        {
            public void execute(String[] args)
            {
                if (args.length == 0)
                {
                    this.failUsage();
                }

                int var2 = 0;
                String var3 = "";
                String var4 = "";

                if (COLORS.containsKey(args[0].toLowerCase()) && args.length > 1)
                {
                    var3 = (String)COLORS.get(args[var2++].toLowerCase());
                }

                if (var2 < args.length - 1 && STYLES.containsKey(args[var2].toLowerCase()))
                {
                    var4 = (String)STYLES.get(args[var2++].toLowerCase());
                }

                StringBuilder var5 = new StringBuilder();

                for (int var6 = var2; var6 < args.length; ++var6)
                {
                    var5.append(var6 > var2 ? " " : "").append(args[var6]);
                }

                // "&c" style codes work too.
                final String var7 = var3 + var4 + var5.toString().replaceAll("&([0-9a-fk-or])", "\u00a7$1");
                KirRename.apply(this, var7);
            }
        });
        mod_CommandLib.registerCommand(new ClientCommand("clearname", "", "Gives the item in your hand its normal name back (singleplayer / LAN host)")
        {
            public void execute(String[] args)
            {
                KirRename.apply(this, (String)null);
            }
        });
    }

    /** Renames (or with null, un-renames) the host player's held item on the integrated server. */
    private static void apply(final ClientCommand command, final String name)
    {
        Minecraft var2 = ModLoader.getMinecraftInstance();

        if (var2.thePlayer == null || !KirInteraction.isOnIntegratedServer(var2))
        {
            throw new ClientCommandException("Renaming only works in singleplayer or on a LAN world you host.");
        }

        if (var2.thePlayer.inventory.getCurrentItem() == null)
        {
            throw new ClientCommandException("Hold the item you want to " + (name == null ? "clear the name of." : "rename."));
        }

        KirInteraction.runOnServer(new KirInteraction.ServerTask()
        {
            public String run(EntityPlayer player)
            {
                ItemStack var2 = player.inventory.getCurrentItem();

                if (var2 == null)
                {
                    return "\u00a7cYou aren't holding anything.";
                }

                if (name == null)
                {
                    if (getCustomName(var2) == null)
                    {
                        return "\u00a77That item has its normal name.";
                    }

                    var2.stackTagCompound.setString(TAG, "");
                }
                else
                {
                    if (var2.stackTagCompound == null)
                    {
                        var2.setTagCompound(new NBTTagCompound());
                    }

                    var2.stackTagCompound.setString(TAG, name);
                }

                player.inventory.inventoryChanged = true;

                if (player instanceof EntityPlayerMP)
                {
                    ((EntityPlayerMP)player).serverForThisPlayer.sendPacketToPlayer(new Packet103SetSlot(0, 36 + player.inventory.currentItem, var2));
                }

                return name == null ? "\u00a7eItem name cleared." : "\u00a7aItem renamed to: \u00a7r" + name;
            }
        });
    }
}
