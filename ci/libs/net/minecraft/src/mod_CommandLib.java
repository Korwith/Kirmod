package net.minecraft.src;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Client-side chat command library.
 *
 * Other mods register commands with registerCommand(new ClientCommand(...) {...}). While the chat
 * screen is open it is swapped for GuiCommandLibChat, which hands typed lines to dispatch() before
 * sending them; anything this library does not claim is sent to the server as usual. No base class
 * is modified, so the library also runs with Forge.
 *
 * Everything is configurable in .minecraft/config/CommandLib.cfg:
 *   enabled                 - master switch for all client commands
 *   prefix                  - prefix that marks a chat line as a command (default "/")
 *   passUnknownToServer     - send unknown prefixed commands to the server (default true)
 *   messagePrefix           - text put in front of every message the library prints
 *   command.NAME.enabled    - turn a single command on or off
 *   command.NAME.aliases    - comma separated alternative names for a command
 */
public class mod_CommandLib extends BaseMod
{
    public static final String CONFIG_FILE_NAME = "CommandLib.cfg";

    private static final Properties config = new Properties();
    private static boolean configLoaded = false;

    /** Registered commands by their primary name, in registration order. */
    private static final Map commands = new LinkedHashMap();

    /** Every name and alias that currently resolves to a command. */
    private static final Map lookup = new LinkedHashMap();

    private static boolean enabled = true;
    private static String prefix = "/";
    private static boolean passUnknownToServer = true;
    private static String messagePrefix = "§7[§bCmd§7]§r ";

    public String getVersion()
    {
        return "1.0 for 1.3.2";
    }

    public void load()
    {
        ensureConfigLoaded();
        ModLoader.setInGUIHook(this, true, false);
        registerCommand(new ClientCommand("commands", "[reload]", "Lists client commands, or reloads CommandLib.cfg", "chelp")
        {
            public void execute(String[] args)
            {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload"))
                {
                    reloadConfig();
                    this.reply("Reloaded " + CONFIG_FILE_NAME + ".");
                    return;
                }

                if (args.length != 0)
                {
                    this.failUsage();
                }

                this.reply("§eClient commands:");
                Iterator var2 = mod_CommandLib.getCommands().iterator();

                while (var2.hasNext())
                {
                    ClientCommand var3 = (ClientCommand)var2.next();

                    if (var3.isEnabled())
                    {
                        String var4 = var3.getAliases().isEmpty() ? "" : " §8(" + joinAliases(var3) + ")";
                        this.reply("§f" + var3.getFullUsage() + var4 + " §7- " + var3.getDescription());
                    }
                }
            }
        });
    }

    public boolean onTickInGUI(float partialTicks, Minecraft mc, GuiScreen screen)
    {
        // Runs every frame, so the swap happens before the player can press enter. The text typed so
        // far (for example the "/" that opened the chat) is carried over.
        if (screen != null && screen.getClass() == GuiChat.class)
        {
            mc.displayGuiScreen(new GuiCommandLibChat(((GuiChat)screen).inputField.getText()));
        }

        return true;
    }

    /** The chat screen, with client commands run on enter instead of being sent to the server. */
    public static class GuiCommandLibChat extends GuiChat
    {
        public GuiCommandLibChat(String par1Str)
        {
            super(par1Str);
        }

        protected void keyTyped(char par1, int par2)
        {
            if (par2 == 28)
            {
                String var3 = this.inputField.getText().trim();

                if (var3.length() > 0 && dispatch(var3))
                {
                    this.mc.ingameGUI.getChatGUI().func_73767_b(var3);
                    this.mc.displayGuiScreen((GuiScreen)null);
                    return;
                }
            }

            super.keyTyped(par1, par2);
        }
    }

    /**
     * Registers a command. Its enabled state and aliases come from the config; if the command has no
     * entries there yet, they are written with the command's defaults.
     */
    public static void registerCommand(ClientCommand command)
    {
        ensureConfigLoaded();

        if (commands.containsKey(command.getName()))
        {
            ModLoader.getLogger().warning("[CommandLib] Command '" + command.getName() + "' registered twice, ignoring the second one.");
            return;
        }

        commands.put(command.getName(), command);
        String var1 = "command." + command.getName() + ".enabled";
        String var2 = "command." + command.getName() + ".aliases";
        boolean var3 = false;

        if (!config.containsKey(var1))
        {
            config.setProperty(var1, "true");
            var3 = true;
        }

        if (!config.containsKey(var2))
        {
            StringBuilder var4 = new StringBuilder();
            Iterator var5 = command.getDefaultAliases().iterator();

            while (var5.hasNext())
            {
                var4.append(var4.length() > 0 ? "," : "").append(var5.next());
            }

            config.setProperty(var2, var4.toString());
            var3 = true;
        }

        if (var3)
        {
            saveConfig();
        }

        rebuildLookup();
    }

    public static ClientCommand getCommand(String name)
    {
        return (ClientCommand)lookup.get(name.toLowerCase());
    }

    public static List getCommands()
    {
        return new ArrayList(commands.values());
    }

    public static String getPrefix()
    {
        return prefix;
    }

    /**
     * Called by Minecraft.handleClientCommand() for every chat line the player sends.
     * Returns true when the line was handled here and must not be sent to the server.
     */
    public static boolean dispatch(String line)
    {
        ensureConfigLoaded();

        if (!enabled || prefix.length() == 0 || !line.startsWith(prefix))
        {
            return false;
        }

        String[] var1 = line.substring(prefix.length()).trim().split("\\s+");

        if (var1.length == 0 || var1[0].length() == 0)
        {
            return false;
        }

        ClientCommand var2 = getCommand(var1[0]);

        if (var2 == null)
        {
            if (passUnknownToServer)
            {
                return false;
            }

            sendError("Unknown command. Type " + prefix + "commands for a list.");
            return true;
        }

        String[] var3 = new String[var1.length - 1];
        System.arraycopy(var1, 1, var3, 0, var3.length);

        try
        {
            var2.execute(var3);
        }
        catch (ClientCommandException var5)
        {
            sendError(var5.getMessage());
        }
        catch (Throwable var6)
        {
            sendError("An error occurred running that command, see the log.");
            ModLoader.getLogger().log(java.util.logging.Level.WARNING, "[CommandLib] Command '" + var2.getName() + "' failed", var6);
        }

        return true;
    }

    public static void sendMessage(String message)
    {
        Minecraft var1 = ModLoader.getMinecraftInstance();

        if (var1 != null && var1.ingameGUI != null)
        {
            var1.ingameGUI.getChatGUI().printChatMessage(messagePrefix + message);
        }
    }

    public static void sendError(String message)
    {
        sendMessage("§c" + message);
    }

    public static void reloadConfig()
    {
        configLoaded = false;
        ensureConfigLoaded();
        rebuildLookup();
    }

    private static String joinAliases(ClientCommand command)
    {
        StringBuilder var1 = new StringBuilder();
        Iterator var2 = command.getAliases().iterator();

        while (var2.hasNext())
        {
            var1.append(var1.length() > 0 ? ", " : "").append(prefix).append(var2.next());
        }

        return var1.toString();
    }

    private static void rebuildLookup()
    {
        lookup.clear();
        Iterator var0 = commands.values().iterator();

        while (var0.hasNext())
        {
            ClientCommand var1 = (ClientCommand)var0.next();
            var1.enabled = !"false".equalsIgnoreCase(config.getProperty("command." + var1.getName() + ".enabled", "true").trim());
            var1.activeAliases.clear();

            if (var1.enabled)
            {
                lookup.put(var1.getName(), var1);
            }
        }

        var0 = commands.values().iterator();

        while (var0.hasNext())
        {
            ClientCommand var1 = (ClientCommand)var0.next();

            if (var1.enabled)
            {
                String[] var2 = config.getProperty("command." + var1.getName() + ".aliases", "").split(",");

                for (int var3 = 0; var3 < var2.length; ++var3)
                {
                    String var4 = var2[var3].trim().toLowerCase();

                    if (var4.length() > 0)
                    {
                        if (lookup.containsKey(var4))
                        {
                            ModLoader.getLogger().warning("[CommandLib] Alias '" + var4 + "' of '" + var1.getName() + "' is already taken, skipping it.");
                        }
                        else
                        {
                            lookup.put(var4, var1);
                            var1.activeAliases.add(var4);
                        }
                    }
                }
            }
        }
    }

    private static File getConfigFile()
    {
        return new File(Minecraft.getMinecraftDir(), "config/" + CONFIG_FILE_NAME);
    }

    private static void ensureConfigLoaded()
    {
        if (configLoaded)
        {
            return;
        }

        configLoaded = true;
        config.clear();
        File var0 = getConfigFile();

        if (var0.exists())
        {
            try
            {
                FileInputStream var1 = new FileInputStream(var0);

                try
                {
                    config.load(var1);
                }
                finally
                {
                    var1.close();
                }
            }
            catch (IOException var5)
            {
                ModLoader.getLogger().warning("[CommandLib] Could not read " + var0 + ": " + var5);
            }
        }

        enabled = !"false".equalsIgnoreCase(getOrDefault("enabled", "true"));
        prefix = getOrDefault("prefix", "/");
        passUnknownToServer = !"false".equalsIgnoreCase(getOrDefault("passUnknownToServer", "true"));
        messagePrefix = getOrDefault("messagePrefix", messagePrefix);

        if (!var0.exists())
        {
            saveConfig();
        }
    }

    private static String getOrDefault(String key, String def)
    {
        if (!config.containsKey(key))
        {
            config.setProperty(key, def);
        }

        return config.getProperty(key);
    }

    private static void saveConfig()
    {
        File var0 = getConfigFile();

        try
        {
            var0.getParentFile().mkdirs();
            FileOutputStream var1 = new FileOutputStream(var0);

            try
            {
                config.store(var1, "CommandLib client command settings. command.NAME.aliases is a comma separated list.");
            }
            finally
            {
                var1.close();
            }
        }
        catch (IOException var5)
        {
            ModLoader.getLogger().warning("[CommandLib] Could not write " + var0 + ": " + var5);
        }
    }
}
