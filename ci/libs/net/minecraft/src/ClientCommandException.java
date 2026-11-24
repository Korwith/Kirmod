package net.minecraft.src;

/** Thrown from ClientCommand.execute() to abort with an error shown to the player. */
public class ClientCommandException extends RuntimeException
{
    public ClientCommandException(String message)
    {
        super(message);
    }
}
