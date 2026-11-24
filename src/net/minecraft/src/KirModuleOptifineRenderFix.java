package net.minecraft.src;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;

/**
 * OptiFine Render Fix: "On" makes the integrated server send chunks out to the client's render distance,
 * so OptiFine's distances past Far (up to 512 blocks) and vanilla Far itself actually show terrain.
 *
 * The client was never the problem: OptiFine sizes its chunk renderers from ofRenderDistanceFine (up to
 * 1024 blocks across) and draws whatever chunks the client has. But the integrated server always sends
 * a square of 10 chunks (160 blocks) around the player, so the renderers past that stay empty while
 * OptiFine keeps checking them, which looks like loading that never finishes.
 *
 * The client thread works out the wanted radius each frame (ofRenderDistanceFine / 16 with OptiFine,
 * 256 >> renderDistance without, never below vanilla's 10, at most 32). The server thread, from
 * KirWorldHook's "chunkMap" section, gives each world a KirPlayerManager and passes the radius on.
 * Only for worlds this game hosts; a remote server sends what its own view-distance says.
 */
public class KirModuleOptifineRenderFix extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    /** OptiFine's highest distance, 512 blocks. */
    public static final int MAX_RADIUS = 32;

    /** OptiFine's GameSettings.ofRenderDistanceFine, or null without OptiFine. */
    private static final Field fieldRenderDistanceFine = findOptifineField();

    /** The radius the server should send, set by the client thread; 0 for vanilla. */
    private static volatile int wantedRadius = 0;

    /** The radius each integrated server world (Nether, Overworld, End) sent at its last tick, for the tooltip. */
    private static final int[] sentRadius = new int[] {-1, -1, -1};

    public KirModuleOptifineRenderFix()
    {
        super("optifineRenderFix", "OptiFine Render Fix", "OptiFine Fix", 0, "optifinerenderfix", "renderfix");
    }

    private static Field findOptifineField()
    {
        try
        {
            Field var0 = GameSettings.class.getDeclaredField("ofRenderDistanceFine");
            var0.setAccessible(true);
            return var0;
        }
        catch (Exception var1)
        {
            return null;
        }
    }

    public static boolean hasOptifine()
    {
        return fieldRenderDistanceFine != null;
    }

    public String getCategory()
    {
        return CATEGORY_OPTIFINE;
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return KirPlayerManager.isAvailable() && KirWorldHook.isAvailable();
    }

    public String[] getTooltip()
    {
        Minecraft var1 = ModLoader.getMinecraftInstance();
        int var2 = var1 != null && var1.gameSettings != null ? getRenderBlocks(var1.gameSettings) : 256;
        int var4 = var1 != null && var1.thePlayer != null && var1.getIntegratedServer() != null ? getSentRadius(var1.thePlayer.dimension) : -1;
        String var3 = var4 > 0 ? var4 * 16 + " blocks (" + var4 + " chunks)" : "nothing here (no singleplayer world)";

        return new String[] {
            "Singleplayer only sends the chunks within 160 blocks, whatever the render distance, so OptiFine's distances past Far (and vanilla Far) show empty space past that while the game keeps trying.",
            describeValue("Off", "vanilla, 10 chunks are sent."),
            describeValue("On", "the chunks out to your render distance are sent, up to 512 blocks. The extra chunks come in over a minute or so, nearest first, without freezing the game."),
            note("Render distance: " + var2 + " blocks" + (hasOptifine() ? " (OptiFine)" : "") + ". Being sent: " + var3 + "."),
            note("Worlds you host (singleplayer, Open to LAN) only. More chunks use more memory and CPU; at 512 blocks give the game 2 GB or more.")
        };
    }

    /** The client's render distance in blocks. */
    static int getRenderBlocks(GameSettings settings)
    {
        if (fieldRenderDistanceFine != null)
        {
            try
            {
                return fieldRenderDistanceFine.getInt(settings);
            }
            catch (Exception var2)
            {
                ;
            }
        }

        return 256 >> (settings.renderDistance & 3);
    }

    /** The radius in chunks that covers the client's render distance. */
    static int getRadiusFor(int blocks)
    {
        return Math.max(3, Math.min(MAX_RADIUS, (blocks + 15) / 16));
    }

    public void update(Minecraft mc)
    {
        wantedRadius = this.getValue() == 1 && mc.gameSettings != null ? getRadiusFor(getRenderBlocks(mc.gameSettings)) : 0;

        if (wantedRadius > 0 && mc.getIntegratedServer() != null)
        {
            KirWorldHook.installAll(mc);
        }
    }

    /** Server thread, once per tick of each integrated server world (KirWorldHook, "chunkMap"). */
    void tickServerWorld(World world)
    {
        if (!(world instanceof WorldServer))
        {
            return;
        }

        WorldServer var2 = (WorldServer)world;
        KirPlayerManager.update(var2, wantedRadius);

        if (world.provider != null && world.provider.worldType >= -1 && world.provider.worldType <= 1)
        {
            sentRadius[world.provider.worldType + 1] = KirPlayerManager.getRadius(var2);
        }
    }

    /** The radius the integrated server world of a dimension sends, or -1 if unknown. */
    public static int getSentRadius(int dimension)
    {
        return dimension >= -1 && dimension <= 1 ? sentRadius[dimension + 1] : -1;
    }

    /** The wanted radius (for tests). */
    public static int getWantedRadius()
    {
        return wantedRadius;
    }
}
