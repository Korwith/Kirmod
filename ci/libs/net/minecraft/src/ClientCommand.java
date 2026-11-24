package net.minecraft.src;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A client-side chat command, registered through mod_CommandLib.registerCommand().
 * The name passed to the constructor is the command's config key; whether it is enabled
 * and which aliases it answers to are read from config/CommandLib.cfg.
 */
public abstract class ClientCommand
{
    private final String name;
    private final String usage;
    private final String description;
    private final List defaultAliases;

    /** Aliases currently in effect (filled in by mod_CommandLib from the config). */
    List activeAliases = new ArrayList();
    boolean enabled = true;

    public ClientCommand(String name, String usage, String description, String ... defaultAliases)
    {
        this.name = name.toLowerCase();
        this.usage = usage;
        this.description = description;
        this.defaultAliases = Collections.unmodifiableList(Arrays.asList(defaultAliases));
    }

    /**
     * Runs the command. args holds the words after the command name. Throw a ClientCommandException
     * to print an error to the player.
     */
    public abstract void execute(String[] args);

    public String getName()
    {
        return this.name;
    }

    /** Usage text shown in help, without the prefix or command name, e.g. "<option> <value>". */
    public String getUsage()
    {
        return this.usage;
    }

    public String getDescription()
    {
        return this.description;
    }

    public List getDefaultAliases()
    {
        return this.defaultAliases;
    }

    public List getAliases()
    {
        return Collections.unmodifiableList(this.activeAliases);
    }

    public boolean isEnabled()
    {
        return this.enabled;
    }

    /** Full usage line including the configured prefix, e.g. "/kirmod set <option> <value>". */
    public String getFullUsage()
    {
        return mod_CommandLib.getPrefix() + this.name + (this.usage.length() > 0 ? " " + this.usage : "");
    }

    /** Prints a normal message to the local chat. */
    protected void reply(String message)
    {
        mod_CommandLib.sendMessage(message);
    }

    /** Aborts the command with an error message. */
    protected void fail(String message)
    {
        throw new ClientCommandException(message);
    }

    /** Aborts the command and shows its usage. */
    protected void failUsage()
    {
        throw new ClientCommandException("Usage: " + this.getFullUsage());
    }
}
