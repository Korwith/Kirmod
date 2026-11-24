package net.minecraft.src;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.lwjgl.opengl.GL11;

/**
 * Multi-Core Chunks: builds chunk geometry on worker threads instead of the render thread.
 *
 * Vanilla and OptiFine rebuild a dirty chunk renderer (16x16x16 blocks) on the render thread, inside the
 * frame: block by block through RenderBlocks into the Tessellator, compiled straight into a GL display
 * list. OptiFine limits it to 1-5 a frame (Chunk Updates), so far chunks fill in slowly and every
 * rebuild costs frame time.
 *
 * With this on, KirRenderGlobal.updateRenderers hands the work to update() here, once a frame:
 * 1. Finished chunks are compiled into their display lists (render thread, the only GL part), within a
 *    time budget.
 * 2. Dirty renderers, nearest first (out-of-view ones count 4x farther, like OptiFine), are snapshotted
 *    into a KirChunkCache (copies of the chunk sections, render thread, a fraction of a millisecond) and
 *    queued for the workers, as many as keep every worker busy.
 * 3. A worker runs the same loop as WorldRenderer.updateRenderer over the copy, drawing into its own
 *    recording KirTessellator (Tessellator.instance hands worker calls to it).
 *
 * The old display list stays on screen until the new one is compiled, so nothing flickers. A result
 * is dropped if the renderer was moved or marked dirty again meanwhile, or the renderers were reloaded.
 *
 * Kept on the render thread, built the vanilla way (the renderer's own updateRenderer): chunks with a
 * block whose render type isn't vanilla's (a mod's own renderer may use OpenGL), or, with Forge, a
 * block with its own texture file (Forge switches Tessellator.instance for those). With Forge such a
 * build waits for the workers (a write lock), since they read Tessellator.instance too.
 *
 * Blocks that change their bounds while rendering (stairs, slabs, fences...) are drawn by one worker
 * at a time per block type, since those bounds are fields of the shared Block object.
 */
public class KirChunkBuilder
{
    private static final Field fieldWorldRenderers = KirUtil.findField(RenderGlobal.class, "worldRenderers", "l");
    private static final Field fieldToUpdate = KirUtil.findField(RenderGlobal.class, "worldRenderersToUpdate", "j");
    private static final Field fieldTheWorld = KirUtil.findField(RenderGlobal.class, "theWorld", "h");
    private static final Field fieldTileEntities = KirUtil.findField(RenderGlobal.class, "tileEntities", "a");
    private static final Field fieldGlRenderList = KirUtil.findField(WorldRenderer.class, "glRenderList", "y");
    private static final Field fieldIsInitialized = KirUtil.findField(WorldRenderer.class, "isInitialized", "A");
    private static final Field fieldBytesDrawn = KirUtil.findField(WorldRenderer.class, "bytesDrawn", "C");

    /** OptiFine's chunk renderer: absolute coordinates offset by these, box list built on update. */
    private static final Field fieldGlobalOffsetX = quietField(WorldRenderer.class, "globalChunkOffsetX");
    private static final Field fieldGlobalOffsetZ = quietField(WorldRenderer.class, "globalChunkOffsetZ");
    private static final Field fieldNeedsBoxUpdate = quietField(WorldRenderer.class, "needsBoxUpdate");
    private static final Field fieldVisibleFromPosition = quietField(WorldRenderer.class, "isVisibleFromPosition");

    /** Forge's hooks, null without Forge. */
    private static Method canRenderInPass;
    private static Method hasTileEntityMeta;
    private static Method getTextureFile;

    static
    {
        try
        {
            Class.forName("net.minecraftforge.client.ForgeHooksClient");
            canRenderInPass = Block.class.getMethod("canRenderInPass", new Class[] {Integer.TYPE});
            hasTileEntityMeta = Block.class.getMethod("hasTileEntity", new Class[] {Integer.TYPE});
            getTextureFile = Block.class.getMethod("getTextureFile", new Class[0]);
        }
        catch (ClassNotFoundException var1)
        {
            ;
        }
        catch (Exception var2)
        {
            KirUtil.log("Forge's block hooks not found, Multi-Core Chunks keeps modded blocks on the render thread", var2);
            canRenderInPass = null;
            hasTileEntityMeta = null;
            getTextureFile = null;
        }
    }

    /** Render-thread time per frame for compiling finished chunks and for snapshotting new ones. */
    private static final long UPLOAD_BUDGET_NS = 3000000L;
    private static final long SUBMIT_BUDGET_NS = 2000000L;

    /** Chunks queued or being built, per worker. */
    private static final int JOBS_PER_WORKER = 3;

    /** Workers take this for each chunk; the render thread takes it for a Forge build. */
    static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static final LinkedBlockingQueue jobs = new LinkedBlockingQueue();
    private static final ConcurrentLinkedQueue results = new ConcurrentLinkedQueue();
    private static final List workers = new ArrayList();

    /** Latest job number per renderer; a result whose number differs is stale. */
    private static final Map generations = new IdentityHashMap();
    private static int nextGeneration = 1;
    private static int inFlight = 0;

    /** Bumped when the renderers are reloaded; older results are dropped. */
    private static int epoch = 0;
    private static Object lastRenderers;
    private static Object lastWorld;

    /** Per block id: 0 not checked, 1 fine on a worker, 2 render thread only; for blockChecked. */
    private static final byte[] blockSafety = new byte[4096];
    private static final Block[] blockChecked = new Block[4096];

    private static boolean failed = false;
    private static boolean loggedWorkerError = false;

    /** Stats for the tooltip and tests. */
    static volatile int builtOnWorkers = 0;
    static volatile int builtOnRenderThread = 0;

    /** Time spent (ns) and counts, for tests: snapshots and uploads on the render thread, builds on workers. */
    static long snapshotNs = 0L;
    static int snapshots = 0;
    static long uploadNs = 0L;
    static int uploads = 0;
    static final java.util.concurrent.atomic.AtomicLong buildNs = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();

    /** Average times in microseconds: snapshot, worker build, upload. */
    static String timings()
    {
        return "snapshot=" + (snapshots == 0 ? 0L : snapshotNs / snapshots / 1000L) + "us build=" + (builds.get() == 0 ? 0L : buildNs.get() / builds.get() / 1000L) + "us upload=" + (uploads == 0 ? 0L : uploadNs / uploads / 1000L) + "us";
    }

    private static ByteBuffer uploadBytes;
    private static IntBuffer uploadInts;
    private static FloatBuffer uploadFloats;
    private static ShortBuffer uploadShorts;

    /** One chunk renderer's rebuild. */
    static class Job
    {
        WorldRenderer renderer;
        int generation;
        int epoch;
        int posX;
        int posY;
        int posZ;
        boolean optifine;
        int offsetX;
        int offsetZ;
        boolean beta;
        KirChunkCache cache;
        List tileEntityRenderers = new ArrayList();

        /** Results: the recorded draws of each pass (null if the pass drew nothing), and which to skip. */
        List[] passes = new List[2];
        boolean[] skip = new boolean[] {true, true};
        Throwable error;
    }

    /** A thread that builds chunks; KirTessellator hands its Tessellator calls to its own recorder. */
    static class Worker extends Thread
    {
        final KirTessellator tessellator;
        volatile boolean running = true;

        Worker(int index) throws Exception
        {
            super("Kirmod chunk builder " + index);
            this.tessellator = KirTessellator.newRecorder();
            this.setDaemon(true);
            this.setPriority(Thread.NORM_PRIORITY - 1);
        }

        public void run()
        {
            while (this.running)
            {
                Job var1;

                try
                {
                    var1 = (Job)jobs.take();
                }
                catch (InterruptedException var3)
                {
                    continue;
                }

                if (var1.renderer == null)
                {
                    continue;
                }

                lock.readLock().lock();
                long var5 = System.nanoTime();

                try
                {
                    build(var1, this.tessellator);
                    buildNs.addAndGet(System.nanoTime() - var5);
                    builds.incrementAndGet();
                }
                catch (Throwable var4)
                {
                    this.tessellator.discard();
                    var1.error = var4;
                }
                finally
                {
                    lock.readLock().unlock();
                }

                var1.cache = null;
                results.add(var1);
            }
        }
    }

    private static Field quietField(Class owner, String name)
    {
        try
        {
            Field var2 = owner.getDeclaredField(name);
            var2.setAccessible(true);
            return var2;
        }
        catch (Exception var3)
        {
            return null;
        }
    }

    static boolean isAvailable()
    {
        return !failed && fieldWorldRenderers != null && fieldToUpdate != null && fieldTheWorld != null && fieldTileEntities != null && fieldGlRenderList != null && fieldIsInitialized != null && fieldBytesDrawn != null && KirChunkCache.isAvailable() && KirTessellator.isAvailable();
    }

    static boolean hasOptifineRenderer()
    {
        return fieldGlobalOffsetX != null && fieldGlobalOffsetZ != null;
    }

    static int getWorkerCount()
    {
        return workers.size();
    }

    /** Starts or stops workers so there are count of them. Render thread. */
    static void setWorkerCount(int count)
    {
        while (workers.size() > count)
        {
            Worker var1 = (Worker)workers.remove(workers.size() - 1);
            var1.running = false;
            var1.interrupt();
        }

        while (workers.size() < count)
        {
            try
            {
                Worker var2 = new Worker(workers.size() + 1);
                workers.add(var2);
                var2.start();
            }
            catch (Exception var3)
            {
                failed = true;
                KirUtil.log("Could not start a chunk builder thread, Multi-Core Chunks is unavailable", var3);
                return;
            }
        }
    }

    /**
     * Turning it off: every chunk still queued or being built is marked dirty again, so the game's own
     * updates rebuild it. Render thread.
     */
    static void stop(RenderGlobal renderGlobal)
    {
        setWorkerCount(0);
        jobs.clear();

        if (renderGlobal != null && !generations.isEmpty() && fieldToUpdate != null)
        {
            try
            {
                List var1 = (List)fieldToUpdate.get(renderGlobal);
                Iterator var2 = generations.keySet().iterator();

                while (var2.hasNext())
                {
                    WorldRenderer var3 = (WorldRenderer)var2.next();

                    if (var3.worldObj != null && !var3.needsUpdate)
                    {
                        var3.needsUpdate = true;
                        var1.add(var3);
                    }
                }
            }
            catch (Exception var4)
            {
                ;
            }
        }

        generations.clear();
        results.clear();
        inFlight = 0;
        ++epoch;
    }

    /** The renderers were reloaded (KirRenderGlobal.loadRenderers): everything pending is stale. */
    static void invalidate()
    {
        jobs.clear();
        generations.clear();
        inFlight = 0;
        ++epoch;
    }

    /** One frame's chunk updates, in place of RenderGlobal.updateRenderers. Render thread. */
    static boolean update(RenderGlobal renderGlobal, EntityLiving viewer)
    {
        try
        {
            Object var2 = fieldWorldRenderers.get(renderGlobal);
            Object var3 = fieldTheWorld.get(renderGlobal);

            if (var2 != lastRenderers || var3 != lastWorld)
            {
                lastRenderers = var2;
                lastWorld = var3;
                invalidate();
            }

            upload(renderGlobal);

            if (viewer != null && var3 != null)
            {
                submit(renderGlobal, viewer);
            }
        }
        catch (Exception var4)
        {
            failed = true;
            KirUtil.log("Multi-Core Chunks failed, chunks are built the normal way again", var4);
            stop(renderGlobal);
        }

        return true;
    }

    /**
     * Snapshots and queues the nearest dirty renderers. One pass over the update list drops renderers that
     * are no longer dirty and keeps the few best in a small sorted array, since after a reload the list can
     * hold every renderer (67600 at OptiFine's 512 blocks) and sorting it each frame would cost more than
     * building.
     */
    private static void submit(RenderGlobal renderGlobal, EntityLiving viewer) throws Exception
    {
        List var2 = (List)fieldToUpdate.get(renderGlobal);
        int var3 = workers.size() * JOBS_PER_WORKER - inFlight;

        if (var2.isEmpty())
        {
            return;
        }

        int var4 = Math.max(0, var3);
        WorldRenderer[] var5 = new WorldRenderer[var4];
        float[] var6 = new float[var4];
        int var7 = 0;
        int var8 = 0;

        for (int var9 = 0; var9 < var2.size(); ++var9)
        {
            WorldRenderer var10 = (WorldRenderer)var2.get(var9);

            if (var10 == null || !var10.needsUpdate || var10.worldObj == null)
            {
                continue;
            }

            var2.set(var8++, var10);

            if (var4 == 0)
            {
                continue;
            }

            float var11 = var10.distanceToEntitySquared(viewer);

            if (!var10.isInFrustum)
            {
                var11 *= 4.0F;
            }

            if (var7 < var4 || var11 < var6[var7 - 1])
            {
                int var12 = var7 < var4 ? var7++ : var7 - 1;

                while (var12 > 0 && var6[var12 - 1] > var11)
                {
                    var6[var12] = var6[var12 - 1];
                    var5[var12] = var5[var12 - 1];
                    --var12;
                }

                var6[var12] = var11;
                var5[var12] = var10;
            }
        }

        if (var8 < var2.size())
        {
            var2.subList(var8, var2.size()).clear();
        }

        long var13 = System.nanoTime();

        for (int var15 = 0; var15 < var7; ++var15)
        {
            if (var15 > 0 && System.nanoTime() - var13 > SUBMIT_BUDGET_NS)
            {
                break;
            }

            // Submitted renderers are no longer dirty, so the next pass drops them from the list.
            submitOne(renderGlobal, var5[var15]);
        }
    }

    /** Queues one renderer, or builds it right away; returns whether a job was queued. */
    private static boolean submitOne(RenderGlobal renderGlobal, WorldRenderer renderer) throws Exception
    {
        renderer.needsUpdate = false;
        int var2 = nextGeneration++;
        Integer var3 = (Integer)generations.put(renderer, Integer.valueOf(var2));

        if (var3 != null)
        {
            --inFlight;
        }

        Job var4 = new Job();
        var4.renderer = renderer;
        var4.generation = var2;
        var4.epoch = epoch;
        var4.posX = renderer.posX;
        var4.posY = renderer.posY;
        var4.posZ = renderer.posZ;
        var4.optifine = hasOptifineRenderer();

        if (var4.optifine)
        {
            var4.offsetX = fieldGlobalOffsetX.getInt((Object)null);
            var4.offsetZ = fieldGlobalOffsetZ.getInt((Object)null);
        }

        var4.beta = mod_Kirmod.BETA_LIGHTING.isSmoothActive() && renderer instanceof KirWorldRenderer;
        World var5 = renderer.worldObj;
        long var13 = System.nanoTime();
        KirChunkCache var6 = new KirChunkCache(var5, var4.posX - 1, var4.posY - 1, var4.posZ - 1, var4.posX + 17, var4.posY + 17, var4.posZ + 17);
        snapshotNs += System.nanoTime() - var13;
        ++snapshots;

        if (!var6.extendedLevelsInChunkCache())
        {
            // Tile entities are looked up (and made) here, as vanilla does; workers can't touch the world.
            for (int var7 = var4.posY; var7 < var4.posY + 16; ++var7)
            {
                for (int var8 = var4.posZ; var8 < var4.posZ + 16; ++var8)
                {
                    for (int var9 = var4.posX; var9 < var4.posX + 16; ++var9)
                    {
                        int var10 = var6.getBlockId(var9, var7, var8);

                        if (var10 > 0)
                        {
                            Block var11 = Block.blocksList[var10];

                            if (var11 == null)
                            {
                                continue;
                            }

                            if (!isSafe(var10, var11))
                            {
                                generations.remove(renderer);
                                buildOnRenderThread(renderer);
                                return false;
                            }

                            if (hasTileEntity(var11, var6, var9, var7, var8))
                            {
                                TileEntity var12 = var5.getBlockTileEntity(var9, var7, var8);

                                if (TileEntityRenderer.instance.hasSpecialRenderer(var12))
                                {
                                    var4.tileEntityRenderers.add(var12);
                                }
                            }
                        }
                    }
                }
            }

            var4.cache = var6;
            ++inFlight;
            jobs.add(var4);
            return true;
        }

        // Nothing but air: done.
        apply(renderGlobal, var4);
        generations.remove(renderer);
        return false;
    }

    private static boolean hasTileEntity(Block block, IBlockAccess access, int x, int y, int z) throws Exception
    {
        if (hasTileEntityMeta != null)
        {
            return ((Boolean)hasTileEntityMeta.invoke(block, new Object[] {Integer.valueOf(access.getBlockMetadata(x, y, z))})).booleanValue();
        }

        return block.hasTileEntity();
    }

    /** Whether a worker may draw this block (cached per id). */
    private static boolean isSafe(int id, Block block)
    {
        if (blockChecked[id] != block)
        {
            blockChecked[id] = block;
            boolean var2 = true;
            int var3 = block.getRenderType();

            // Vanilla's render types; 22 (chests) and -1 draw nothing here. Others are mods' own renderers.
            if (var3 < -1 || var3 > 31)
            {
                var2 = false;
            }

            if (var2 && getTextureFile != null)
            {
                try
                {
                    Object var4 = getTextureFile.invoke(block, new Object[0]);
                    var2 = var4 == null || "/terrain.png".equals(var4);
                }
                catch (Exception var5)
                {
                    var2 = false;
                }
            }

            blockSafety[id] = (byte)(var2 ? 1 : 2);
        }

        return blockSafety[id] == 1;
    }

    /** The renderer's own updateRenderer, on the render thread; with Forge, while no worker runs. */
    private static void buildOnRenderThread(WorldRenderer renderer)
    {
        renderer.needsUpdate = true;
        boolean var1 = getTextureFile != null;

        if (var1)
        {
            lock.writeLock().lock();
        }

        try
        {
            renderer.updateRenderer();
        }
        finally
        {
            if (var1)
            {
                lock.writeLock().unlock();
            }
        }

        renderer.needsUpdate = false;
        ++builtOnRenderThread;
    }

    /** WorldRenderer.updateRenderer's loop over the copy, into the worker's tessellator. Worker thread. */
    static void build(Job job, KirTessellator tess) throws Exception
    {
        KirChunkCache var2 = job.cache;
        tess.chunkBlocks = var2;
        RenderBlocks var3 = job.beta ? new KirRenderBlocks(var2) : new RenderBlocks(var2);
        int var4 = job.posX;
        int var5 = job.posY;
        int var6 = job.posZ;

        for (int var7 = 0; var7 < 2; ++var7)
        {
            boolean var8 = false;
            boolean var9 = false;
            boolean var10 = false;

            for (int var11 = var5; var11 < var5 + 16; ++var11)
            {
                for (int var12 = var6; var12 < var6 + 16; ++var12)
                {
                    for (int var13 = var4; var13 < var4 + 16; ++var13)
                    {
                        int var14 = var2.getBlockId(var13, var11, var12);

                        if (var14 <= 0)
                        {
                            continue;
                        }

                        if (!var10)
                        {
                            var10 = true;
                            tess.startDrawingQuads();

                            if (job.optifine)
                            {
                                tess.setTranslation((double)(-job.offsetX), 0.0D, (double)(-job.offsetZ));
                            }
                            else
                            {
                                tess.setTranslation((double)(-var4), (double)(-var5), (double)(-var6));
                            }
                        }

                        Block var15 = Block.blocksList[var14];

                        if (var15 == null)
                        {
                            continue;
                        }

                        int var16 = var15.getRenderBlockPass();
                        boolean var17;

                        if (var16 != var7)
                        {
                            var8 = true;
                        }

                        if (canRenderInPass != null)
                        {
                            var17 = ((Boolean)canRenderInPass.invoke(var15, new Object[] {Integer.valueOf(var7)})).booleanValue();
                        }
                        else
                        {
                            var17 = var16 == var7;
                        }

                        if (var17)
                        {
                            if (!var15.renderAsNormalBlock() || var15.getRenderType() != 0)
                            {
                                synchronized (var15)
                                {
                                    var9 |= var3.renderBlockByRenderType(var15, var13, var11, var12);
                                }
                            }
                            else
                            {
                                var9 |= var3.renderBlockByRenderType(var15, var13, var11, var12);
                            }
                        }
                    }
                }
            }

            if (var10)
            {
                tess.draw();
                job.passes[var7] = tess.takeCommands();
                tess.setTranslation(0.0D, 0.0D, 0.0D);
            }
            else
            {
                var9 = false;
            }

            job.skip[var7] = !var9;

            if (!var8)
            {
                break;
            }
        }
    }

    /** Compiles finished chunks into their display lists, within the time budget. */
    private static void upload(RenderGlobal renderGlobal) throws Exception
    {
        long var1 = System.nanoTime();
        boolean var3 = true;

        while (var3 || System.nanoTime() - var1 < UPLOAD_BUDGET_NS)
        {
            Job var4 = (Job)results.poll();

            if (var4 == null)
            {
                return;
            }

            if (var4.epoch != epoch)
            {
                continue;
            }

            Integer var5 = (Integer)generations.get(var4.renderer);

            if (var5 == null || var5.intValue() != var4.generation)
            {
                continue;
            }

            generations.remove(var4.renderer);
            --inFlight;
            WorldRenderer var6 = var4.renderer;

            if (var6.worldObj == null || var6.posX != var4.posX || var6.posY != var4.posY || var6.posZ != var4.posZ || var4.optifine && (fieldGlobalOffsetX.getInt((Object)null) != var4.offsetX || fieldGlobalOffsetZ.getInt((Object)null) != var4.offsetZ))
            {
                continue;
            }

            if (var4.error != null)
            {
                if (!loggedWorkerError)
                {
                    loggedWorkerError = true;
                    KirUtil.log("A chunk failed to build on a worker thread, building it the normal way (logged once)", var4.error);
                    var4.error.printStackTrace();
                }

                buildOnRenderThread(var6);
                var3 = false;
                continue;
            }

            long var7 = System.nanoTime();
            apply(renderGlobal, var4);
            uploadNs += System.nanoTime() - var7;
            ++uploads;
            ++builtOnWorkers;
            var3 = false;
        }
    }

    /** The end of WorldRenderer.updateRenderer, with the recorded draws. Render thread. */
    private static void apply(RenderGlobal renderGlobal, Job job) throws Exception
    {
        WorldRenderer var2 = job.renderer;
        int var3 = fieldGlRenderList.getInt(var2);

        if (job.optifine)
        {
            if (fieldNeedsBoxUpdate != null && fieldNeedsBoxUpdate.getBoolean(var2))
            {
                GL11.glNewList(var3 + 2, GL11.GL_COMPILE);
                RenderItem.renderAABB(AxisAlignedBB.getAABBPool().addOrModifyAABBInPool((double)var2.posXClip, (double)var2.posYClip, (double)var2.posZClip, (double)(var2.posXClip + 16), (double)(var2.posYClip + 16), (double)(var2.posZClip + 16)));
                GL11.glEndList();
                fieldNeedsBoxUpdate.setBoolean(var2, false);
            }

            var2.isVisible = true;

            if (fieldVisibleFromPosition != null)
            {
                fieldVisibleFromPosition.setBoolean(var2, false);
            }
        }

        int var4 = 0;

        for (int var5 = 0; var5 < 2; ++var5)
        {
            List var6 = job.passes[var5];

            if (var6 != null)
            {
                GL11.glNewList(var3 + var5, GL11.GL_COMPILE);

                if (!job.optifine)
                {
                    GL11.glPushMatrix();
                    GL11.glTranslatef((float)var2.posXClip, (float)var2.posYClip, (float)var2.posZClip);
                    float var7 = 1.000001F;
                    GL11.glTranslatef(-8.0F, -8.0F, -8.0F);
                    GL11.glScalef(var7, var7, var7);
                    GL11.glTranslatef(8.0F, 8.0F, 8.0F);
                }

                var4 += drawCommands(var6);

                if (!job.optifine)
                {
                    GL11.glPopMatrix();
                }

                GL11.glEndList();
            }

            var2.skipRenderPass[var5] = job.skip[var5];
        }

        HashSet var8 = new HashSet(var2.tileEntityRenderers);
        var2.tileEntityRenderers.clear();
        var2.tileEntityRenderers.addAll(job.tileEntityRenderers);
        List var9 = (List)fieldTileEntities.get(renderGlobal);
        HashSet var10 = new HashSet(job.tileEntityRenderers);
        var10.removeAll(var8);
        var9.addAll(var10);
        var8.removeAll(job.tileEntityRenderers);
        var9.removeAll(var8);
        var2.isChunkLit = true;
        fieldBytesDrawn.setInt(var2, var4);
        fieldIsInitialized.setBoolean(var2, true);
        ++WorldRenderer.chunksUpdated;
    }

    /** Draws recorded commands the way Tessellator.draw does (OptiFine's sub-textures first). */
    private static int drawCommands(List commands)
    {
        int var1 = 0;
        boolean var2 = false;

        for (int var3 = 0; var3 < commands.size(); ++var3)
        {
            KirTessellator.Command var4 = (KirTessellator.Command)commands.get(var3);

            if (var4.texture > 0)
            {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, var4.texture);
                var2 = true;
            }
            else if (var2)
            {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, ModLoader.getMinecraftInstance().renderEngine.getTexture("/terrain.png"));
                var2 = false;
            }

            var1 += drawCommand(var4);
        }

        if (var2)
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, ModLoader.getMinecraftInstance().renderEngine.getTexture("/terrain.png"));
        }

        return var1;
    }

    private static int drawCommand(KirTessellator.Command cmd)
    {
        int var1 = cmd.data.length;

        if (uploadBytes == null || uploadBytes.capacity() < var1 * 4)
        {
            int var2 = Math.max(var1 * 4, 1 << 20);
            uploadBytes = GLAllocation.createDirectByteBuffer(var2);
            uploadInts = uploadBytes.asIntBuffer();
            uploadFloats = uploadBytes.asFloatBuffer();
            uploadShorts = uploadBytes.asShortBuffer();
        }

        uploadInts.clear();
        uploadInts.put(cmd.data, 0, var1);
        uploadBytes.position(0);
        uploadBytes.limit(var1 * 4);

        if (cmd.hasTexture)
        {
            uploadFloats.position(3);
            GL11.glTexCoordPointer(2, 32, uploadFloats);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }

        if (cmd.hasBrightness)
        {
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            uploadShorts.position(14);
            GL11.glTexCoordPointer(2, 32, uploadShorts);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        }

        if (cmd.hasColor)
        {
            uploadBytes.position(20);
            GL11.glColorPointer(4, true, 32, uploadBytes);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        }

        if (cmd.hasNormals)
        {
            uploadBytes.position(24);
            GL11.glNormalPointer(32, uploadBytes);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        }

        uploadFloats.position(0);
        GL11.glVertexPointer(3, 32, uploadFloats);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        KirRenderHook var3 = KirRenderHook.hook;

        if (var3 != null)
        {
            var3.beforeDraw(uploadBytes, true);
        }

        GL11.glDrawArrays(cmd.drawMode, 0, cmd.vertices);

        if (var3 != null)
        {
            var3.afterDraw(uploadBytes, true);
        }

        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);

        if (cmd.hasTexture)
        {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }

        if (cmd.hasBrightness)
        {
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        }

        if (cmd.hasColor)
        {
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        }

        if (cmd.hasNormals)
        {
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        }

        return var1 * 4;
    }

    /** Chunks queued or being built now. */
    static int getPending()
    {
        return inFlight;
    }
}
