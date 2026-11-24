package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * Multi-Core Chunks: builds chunk geometry on worker threads (see KirChunkBuilder). "Auto" uses one
 * thread fewer than the computer has cores (the render thread keeps one), at most 8.
 *
 * Needs KirRenderGlobal (it schedules the rebuilds) and KirTessellator in Tessellator.instance; both are
 * put in the first time it is turned on and stay (they act like vanilla while it is Off). With a vanilla
 * jar, whose Tessellator.instance is final, KirTessellator goes in at startup instead (see install()).
 */
public class KirModuleMultiCoreChunks extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "Auto", "1", "2", "3", "4", "6", "8"};
    private static final int[] THREADS = new int[] {0, -1, 1, 2, 3, 4, 6, 8};

    private boolean installFailed = false;
    private boolean active = false;

    public KirModuleMultiCoreChunks()
    {
        super("multiCoreChunks", "Multi-Core Chunks", "Chunk Threads", 0, "multicorechunks", "chunkthreads");
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
        return !this.installFailed && KirChunkBuilder.isAvailable();
    }

    /** Threads "Auto" means on this computer. */
    static int autoThreads()
    {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    }

    /** Worker threads wanted now, 0 for Off. */
    int getWantedThreads()
    {
        int var1 = THREADS[this.getValue()];
        return var1 < 0 ? autoThreads() : var1;
    }

    /** Whether KirRenderGlobal should hand chunk updates to KirChunkBuilder. */
    boolean isActive()
    {
        return this.active;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Builds chunks (turning blocks into what the graphics card draws) on extra CPU threads. The game and OptiFine build them one at a time on the thread that also draws the frames.",
            describeValue("Off", "vanilla, chunks are built on the render thread, a few per frame."),
            describeValue("Auto", "one thread fewer than your CPU has cores (" + autoThreads() + " here)."),
            describeValue("1-8", "that many threads. Chunks load in much faster and moving around stutters less."),
            note("Works with and without OptiFine. Chunks with modded blocks that draw themselves are still built the normal way. Built now: " + KirChunkBuilder.builtOnWorkers + " on threads, " + KirChunkBuilder.builtOnRenderThread + " normally.")
        };
    }

    public void install()
    {
        if (KirTessellator.isInstanceFinal() && KirTessellator.isAvailable())
        {
            KirTessellator.installProxy();
        }
    }

    protected void onValueChanged()
    {
        this.installFailed = false;
    }

    public void update(Minecraft mc)
    {
        int var2 = this.isAvailable() ? this.getWantedThreads() : 0;

        if (var2 > 0)
        {
            if (!KirTessellator.installProxy())
            {
                this.installFailed = true;
                var2 = 0;
            }
            else
            {
                KirRenderGlobal.install(mc);

                if (!(mc.renderGlobal instanceof KirRenderGlobal))
                {
                    var2 = 0;
                }
            }
        }

        if (var2 > 0)
        {
            KirChunkBuilder.setWorkerCount(var2);
            this.active = KirChunkBuilder.getWorkerCount() == var2 && KirChunkBuilder.isAvailable();
        }
        else if (this.active || KirChunkBuilder.getWorkerCount() > 0)
        {
            this.active = false;
            KirChunkBuilder.stop(mc.renderGlobal);
        }
    }
}
