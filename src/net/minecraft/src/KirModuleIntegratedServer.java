package net.minecraft.src;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Integrated Server (On/Off). "Off" runs singleplayer worlds on the client's own thread in one shared world,
 * as before 1.3 (see KirLocalGame). It only changes outside a world, since the world would have to be left
 * anyway; worlds already open keep running the way they started.
 *
 * Also tracks what the settings screen's sidebar shows about the integrated server: online or offline,
 * uptime and tick rate.
 */
public class KirModuleIntegratedServer extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    /** When each integrated server logged that it was starting (see KirLocalGame.ParkFilter). */
    private static final Map startTimes = new WeakHashMap();

    /** Tick rate sampling: the server sampled, its tick counter and the time then; the last rate. */
    private Object sampledServer;
    private int sampledTicks;
    private long sampledTime;
    private double ticksPerSecond = -1.0D;
    private boolean wasPaused;

    public KirModuleIntegratedServer()
    {
        super("integratedServer", "Integrated Server (Experimental)", "Int. Server (Exp.)", 1, "integratedserver");
    }

    public String getCategory()
    {
        return CATEGORY_OTHER;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Whether singleplayer worlds run on Minecraft's integrated server (a second, server copy of the world on its own thread, since 1.3) or on the client itself, as before 1.3.",
            describeValue("On", "vanilla: the integrated server runs the world and the client shows a copy of it."),
            describeValue("Off", "one world, ticked by the client: no server thread, no packets, no second copy. Blocks, mobs and items react without any lag and the game uses less memory."),
            note("\u00a7eExperimental:\u00a77 turning the integrated server off is still being tested; worlds may behave differently from vanilla. Back up worlds you care about."),
            note("Only changes outside a world. Off: Open to LAN is unavailable; /gamemode, /give, /xp, /tp, /kill and /seed only work on yourself. Mods that run on the server side may not see the player."),
            note("The sidebar shows the integrated server's status: online or offline, how long it has run, and its ticks per second.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return KirLocalGame.isAvailable();
    }

    public String getLockedMessage()
    {
        Minecraft var1 = ModLoader.getMinecraftInstance();
        return var1 != null && var1.theWorld != null ? "Leave the world to turn the integrated server on or off." : null;
    }

    public void install()
    {
        KirLocalGame.install(ModLoader.getMinecraftInstance());
    }

    public void update(Minecraft mc)
    {
        KirLocalGame.install(mc);
        this.sample(mc);

        // The integrated server saves when the game pauses; so does a local world.
        boolean var2 = mc.isGamePaused && mc.theWorld != null;
        KirLocalServer var3 = KirLocalGame.getServer(mc);

        if (var2 && !this.wasPaused && var3 != null)
        {
            var3.saveForPause();
        }

        this.wasPaused = var2;

        // Open to LAN needs the real integrated server.
        if (var3 != null && mc.currentScreen instanceof GuiIngameMenu)
        {
            for (int var4 = 0; var4 < mc.currentScreen.controlList.size(); ++var4)
            {
                GuiButton var5 = (GuiButton)mc.currentScreen.controlList.get(var4);

                if (var5.id == 7)
                {
                    var5.enabled = false;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ Status */

    /** Called from the log filter's thread when an integrated server starts. */
    static void noteServerStarting(MinecraftServer server)
    {
        synchronized (startTimes)
        {
            startTimes.put(server, Long.valueOf(System.currentTimeMillis()));
        }
    }

    private long getStartTime(IntegratedServer server)
    {
        if (server instanceof KirLocalServer)
        {
            return ((KirLocalServer)server).getStartTime();
        }

        synchronized (startTimes)
        {
            Long var2 = (Long)startTimes.get(server);

            if (var2 == null)
            {
                var2 = Long.valueOf(System.currentTimeMillis());
                startTimes.put(server, var2);
            }

            return var2.longValue();
        }
    }

    /** Measures the tick rate about once a second while the server is ticking. */
    private void sample(Minecraft mc)
    {
        IntegratedServer var2 = mc.getIntegratedServer();
        long var3 = System.currentTimeMillis();

        if (var2 != this.sampledServer)
        {
            this.sampledServer = var2;
            this.ticksPerSecond = -1.0D;
            this.sampledTime = var3;
            this.sampledTicks = var2 != null ? var2.getTickCounter() : 0;
            return;
        }

        if (var2 == null || var3 - this.sampledTime < 1000L)
        {
            return;
        }

        int var5 = var2.getTickCounter();

        // A paused server doesn't tick; keep the rate it had while running.
        if (var5 != this.sampledTicks && !mc.isGamePaused)
        {
            this.ticksPerSecond = (double)(var5 - this.sampledTicks) * 1000.0D / (double)(var3 - this.sampledTime);
        }

        this.sampledTicks = var5;
        this.sampledTime = var3;
    }

    /** Whether the vanilla integrated server is running this game. */
    public boolean isOnline(Minecraft mc)
    {
        IntegratedServer var2 = mc.getIntegratedServer();
        return var2 != null && !(var2 instanceof KirLocalServer) && mc.isIntegratedServerRunning();
    }

    /** The sidebar's status lines (colour codes included): Online/Offline, then two details. */
    public String[] getStatusLines(Minecraft mc)
    {
        this.sample(mc);
        IntegratedServer var2 = mc.getIntegratedServer();
        boolean var3 = var2 instanceof KirLocalServer;

        if (var2 == null || var3 || !mc.isIntegratedServerRunning())
        {
            String var4 = var3 ? "\u00a77Local world" : (mc.theWorld != null ? "\u00a77Remote server" : (this.getValue() == 0 ? "\u00a77Disabled" : "\u00a77Not running"));
            String var5 = var3 ? "\u00a77" + this.formatRate(var2) : "\u00a77-";
            return new String[] {"Offline", var4, var5};
        }

        long var6 = (System.currentTimeMillis() - this.getStartTime(var2)) / 1000L;
        return new String[] {"Online", "\u00a77Up \u00a7f" + formatDuration(var6), this.formatRate(var2)};
    }

    private String formatRate(IntegratedServer server)
    {
        double var2 = averageTickMillis(server);
        String var4 = var2 >= 0.0D ? " \u00a77" + String.format("%.1f", new Object[] {Double.valueOf(var2)}) + "ms" : "";

        if (this.ticksPerSecond < 0.0D)
        {
            return "\u00a77- TPS" + var4;
        }

        double var5 = Math.min(20.0D, this.ticksPerSecond);
        String var7 = var5 >= 19.5D ? "\u00a7a" : (var5 >= 15.0D ? "\u00a7e" : "\u00a7c");
        return var7 + String.format("%.1f", new Object[] {Double.valueOf(var5)}) + " TPS" + var4;
    }

    /** Mean time of the last 100 ticks, in milliseconds, or -1 if none ran. */
    private static double averageTickMillis(MinecraftServer server)
    {
        long var1 = 0L;
        int var3 = 0;

        for (int var4 = 0; var4 < server.tickTimeArray.length; ++var4)
        {
            if (server.tickTimeArray[var4] > 0L)
            {
                var1 += server.tickTimeArray[var4];
                ++var3;
            }
        }

        return var3 == 0 ? -1.0D : (double)var1 / (double)var3 / 1000000.0D;
    }

    private static String formatDuration(long seconds)
    {
        long var2 = seconds / 3600L;
        long var4 = seconds / 60L % 60L;
        long var6 = seconds % 60L;
        return var2 > 0L ? var2 + ":" + (var4 < 10L ? "0" : "") + var4 + ":" + (var6 < 10L ? "0" : "") + var6 : var4 + ":" + (var6 < 10L ? "0" : "") + var6;
    }
}
