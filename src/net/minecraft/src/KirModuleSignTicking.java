package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * Sign Ticking: "Off" keeps signs out of the world's tile entity tick (see KirWorldHook). Signs do
 * nothing when ticked, but servers with thousands of them still make every client look each one up
 * every tick.
 */
public class KirModuleSignTicking extends KirModule
{
    private static final String[] VALUES = new String[] {"On", "Off"};

    public KirModuleSignTicking()
    {
        super("signTicking", "Sign Ticking", "Sign Ticking", 0, "signticking", "signs");
    }

    public String getCategory()
    {
        return CATEGORY_PERFORMANCE;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Signs do nothing when ticked, but the game still looks up every sign every tick. On servers with thousands of signs, turning this off saves that time.",
            describeValue("On", "vanilla, signs are ticked."),
            describeValue("Off", "signs are skipped. They still show their text and can be edited.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return KirWorldHook.isAvailable();
    }

    public boolean suppressesAny()
    {
        return this.enabled && this.getValue() == 1;
    }

    public boolean shouldTickTileEntity(TileEntity tileEntity)
    {
        return !this.suppressesAny() || !(tileEntity instanceof TileEntitySign);
    }

    public void update(Minecraft mc)
    {
        KirWorldHook.installAll(mc);
    }
}
