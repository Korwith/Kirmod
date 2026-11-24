package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * The vanilla commands that only work on a network player (EntityPlayerMP), redone for the local player
 * (Integrated Server Off, see KirLocalGame): /gamemode, /give, /xp, /tp, /kill and /seed. The others
 * (/time, /toggledownfall, /help...) go to the server's own command manager.
 */
public class KirLocalCommands
{
    /** Runs the command if it is one of these; returns false to pass it on. */
    static boolean handle(Minecraft mc, KirLocalPlayer player, String line)
    {
        String[] var3 = line.trim().split(" +");
        String var4 = var3[0].toLowerCase();
        String[] var5 = new String[var3.length - 1];
        System.arraycopy(var3, 1, var5, 0, var5.length);

        if (!"gamemode".equals(var4) && !"give".equals(var4) && !"xp".equals(var4) && !"tp".equals(var4) && !"kill".equals(var4) && !"seed".equals(var4))
        {
            return false;
        }

        if (!"seed".equals(var4) && !player.canCommandSenderUseCommand(var4))
        {
            player.sendChatToPlayer("\u00a7cYou do not have permission to use this command.");
            return true;
        }

        try
        {
            if ("gamemode".equals(var4))
            {
                gameMode(mc, player, var5);
            }
            else if ("give".equals(var4))
            {
                give(player, var5);
            }
            else if ("xp".equals(var4))
            {
                xp(player, var5);
            }
            else if ("tp".equals(var4))
            {
                teleport(player, var5);
            }
            else if ("kill".equals(var4))
            {
                player.attackEntityFrom(DamageSource.outOfWorld, 1000);
                player.sendChatToPlayer("Ouch. That look like it hurt.");
            }
            else
            {
                player.sendChatToPlayer("Seed: " + player.worldObj.getSeed());
            }
        }
        catch (CommandException var7)
        {
            player.sendChatToPlayer("\u00a7c" + player.translateString(var7.getMessage(), var7.getErrorOjbects()));
        }

        return true;
    }

    private static void checkPlayer(KirLocalPlayer player, String name)
    {
        if (!name.equalsIgnoreCase(player.username))
        {
            throw new PlayerNotFoundException();
        }
    }

    private static int parseInt(String text, int min, int max)
    {
        try
        {
            int var3 = Integer.parseInt(text);

            if (var3 < min)
            {
                throw new NumberInvalidException("commands.generic.num.tooSmall", new Object[] {Integer.valueOf(var3), Integer.valueOf(min)});
            }

            if (var3 > max)
            {
                throw new NumberInvalidException("commands.generic.num.tooBig", new Object[] {Integer.valueOf(var3), Integer.valueOf(max)});
            }

            return var3;
        }
        catch (NumberFormatException var4)
        {
            throw new NumberInvalidException("commands.generic.num.invalid", new Object[] {text});
        }
    }

    private static void gameMode(Minecraft mc, KirLocalPlayer player, String[] args)
    {
        if (args.length < 1)
        {
            throw new WrongUsageException("commands.gamemode.usage", new Object[0]);
        }

        String var3 = args[0];
        EnumGameType var4;

        if (var3.equalsIgnoreCase("survival") || var3.equalsIgnoreCase("s"))
        {
            var4 = EnumGameType.SURVIVAL;
        }
        else if (var3.equalsIgnoreCase("creative") || var3.equalsIgnoreCase("c"))
        {
            var4 = EnumGameType.CREATIVE;
        }
        else if (var3.equalsIgnoreCase("adventure") || var3.equalsIgnoreCase("a"))
        {
            var4 = EnumGameType.ADVENTURE;
        }
        else
        {
            var4 = WorldSettings.getGameTypeById(parseInt(var3, 0, EnumGameType.values().length - 2));
        }

        if (args.length >= 2)
        {
            checkPlayer(player, args[1]);
        }

        mc.playerController.setGameType(var4);
        player.sendChatToPlayer(player.translateString("commands.gamemode.success.self", new Object[] {StatCollector.translateToLocal("gameMode." + var4.getName())}));
    }

    private static void give(KirLocalPlayer player, String[] args)
    {
        if (args.length < 2)
        {
            throw new WrongUsageException("commands.give.usage", new Object[0]);
        }

        checkPlayer(player, args[0]);
        int var3 = parseInt(args[1], 1, Item.itemsList.length - 1);

        if (Item.itemsList[var3] == null)
        {
            throw new NumberInvalidException("commands.give.notFound", new Object[] {Integer.valueOf(var3)});
        }

        int var4 = args.length >= 3 ? parseInt(args[2], 1, 64) : 1;
        int var5 = args.length >= 4 ? parseInt(args[3], Integer.MIN_VALUE, Integer.MAX_VALUE) : 0;
        ItemStack var6 = new ItemStack(var3, var4, var5);
        player.dropPlayerItem(var6);
        player.sendChatToPlayer(player.translateString("commands.give.success", new Object[] {Item.itemsList[var3].func_77653_i(var6), Integer.valueOf(var3), Integer.valueOf(var4), player.username}));
    }

    private static void xp(KirLocalPlayer player, String[] args)
    {
        if (args.length < 1)
        {
            throw new WrongUsageException("commands.xp.usage", new Object[0]);
        }

        int var2 = parseInt(args[0], 0, 5000);

        if (args.length >= 2)
        {
            checkPlayer(player, args[1]);
        }

        player.addExperience(var2);
        player.sendChatToPlayer(player.translateString("commands.xp.success", new Object[] {Integer.valueOf(var2), player.username}));
    }

    /** /tp [player] x y z; teleporting to a player is to yourself here. */
    private static void teleport(KirLocalPlayer player, String[] args)
    {
        if (args.length != 3 && args.length != 4)
        {
            if (args.length == 1 || args.length == 2)
            {
                checkPlayer(player, args[args.length - 1]);
                return;
            }

            throw new WrongUsageException("commands.tp.usage", new Object[0]);
        }

        if (args.length == 4)
        {
            checkPlayer(player, args[0]);
        }

        int var2 = args.length - 3;
        int var3 = parseInt(args[var2], -30000000, 30000000);
        int var4 = parseInt(args[var2 + 1], 0, 256);
        int var5 = parseInt(args[var2 + 2], -30000000, 30000000);

        if (player.ridingEntity != null)
        {
            player.mountEntity((Entity)null);
        }

        player.setPositionAndUpdate((double)((float)var3 + 0.5F), (double)var4, (double)((float)var5 + 0.5F));

        if (player.worldObj instanceof WorldServer)
        {
            KirLocalGame.loadChunksAround((WorldServer)player.worldObj, player, 1);
        }
        player.sendChatToPlayer(player.translateString("commands.tp.coordinates", new Object[] {player.username, Integer.valueOf(var3), Integer.valueOf(var4), Integer.valueOf(var5)}));
    }
}
