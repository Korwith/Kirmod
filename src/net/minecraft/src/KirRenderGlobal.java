package net.minecraft.src;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * mc.renderGlobal swapped for a subclass, for Tile Entity Render Distance, Block Outline and Beta Lighting.
 *
 * renderEntities() renders the tile entities itself, with Kirmod's distance instead of vanilla's 64
 * blocks (TileEntityRenderer.renderTileEntity has the 64 built in, and its constructor is private, so it
 * can't be subclassed). drawSelectionBox() draws Block Outline's highlight instead of the black lines.
 * For Beta Lighting, loadRenderers() swaps the chunk renderers for KirWorldRenderers and
 * clipRenderersByFrustum() (the first call of a frame after EntityRenderer updates its lightmap) puts
 * Beta's lightmap in. updateRenderers() hands chunk rebuilds to KirChunkBuilder for Multi-Core Chunks. With all three settings at their defaults it does exactly what vanilla does.
 *
 * The subclass is made without running RenderGlobal's constructor, which would allocate a second set of
 * GL display lists, and then gets every field of the original (as KirInteraction does with the player
 * controller). It is only installed once one of those settings is changed from its default.
 */
public class KirRenderGlobal extends RenderGlobal
{
    private static final Field fieldStartupCounter = KirUtil.findField(RenderGlobal.class, "renderEntitiesStartupCounter", "G");
    private static final Field fieldWorldRenderers = KirUtil.findField(RenderGlobal.class, "worldRenderers", "l");
    private static final Field fieldSortedWorldRenderers = KirUtil.findField(RenderGlobal.class, "sortedWorldRenderers", "k");
    private static final Field fieldWorldRenderersToUpdate = KirUtil.findField(RenderGlobal.class, "worldRenderersToUpdate", "j");

    /** sun.misc.Unsafe and its allocateInstance(Class), which makes an object without a constructor. */
    private static Object unsafe;
    private static Method methodAllocateInstance;

    private static boolean failed = false;

    /** Set if swapping the chunk renderers failed once, so it isn't retried every frame. */
    private static boolean swapFailed = false;

    static
    {
        try
        {
            Class var0 = Class.forName("sun.misc.Unsafe");
            Field var1 = var0.getDeclaredField("theUnsafe");
            var1.setAccessible(true);
            unsafe = var1.get((Object)null);
            methodAllocateInstance = var0.getMethod("allocateInstance", new Class[] {Class.class});
        }
        catch (Exception var2)
        {
            KirUtil.log("Could not find sun.misc.Unsafe, Tile Entity Render Distance and Block Outline are unavailable", var2);
        }
    }

    /** Never called: install() makes the instance without a constructor. */
    private KirRenderGlobal(Minecraft par1Minecraft, RenderEngine par2RenderEngine)
    {
        super(par1Minecraft, par2RenderEngine);
    }

    public static boolean isAvailable()
    {
        return methodAllocateInstance != null && fieldStartupCounter != null && !failed;
    }

    /** Swaps mc.renderGlobal for a KirRenderGlobal. Cheap once done. */
    public static void install(Minecraft mc)
    {
        RenderGlobal var1 = mc.renderGlobal;

        if (var1 == null || var1 instanceof KirRenderGlobal || !isAvailable())
        {
            return;
        }

        if (var1.getClass() != RenderGlobal.class)
        {
            failed = true;
            KirUtil.log("The world renderer is already changed by another mod (" + var1.getClass().getName() + "), Tile Entity Render Distance and Block Outline are unavailable", (Throwable)null);
            return;
        }

        try
        {
            KirRenderGlobal var2 = (KirRenderGlobal)allocateInstance(KirRenderGlobal.class);
            KirUtil.copyFields(var1, var2);

            // The world reports block changes to its listeners, which include the renderer.
            if (mc.theWorld != null)
            {
                List var3 = mc.theWorld.worldAccesses;
                int var4 = var3.indexOf(var1);

                if (var4 >= 0)
                {
                    var3.set(var4, var2);
                }
            }

            mc.renderGlobal = var2;
        }
        catch (Exception var5)
        {
            failed = true;
            KirUtil.log("Could not hook into the world renderer", var5);
        }
    }

    /** Whether loadRenderers can swap in KirWorldRenderers (for Beta Lighting). */
    static boolean canSwapWorldRenderers()
    {
        return isAvailable() && !swapFailed && fieldWorldRenderers != null && fieldSortedWorldRenderers != null && fieldWorldRenderersToUpdate != null;
    }

    /** Makes an instance of cls without running a constructor; for swapping objects via copyFields. */
    static Object allocateInstance(Class cls) throws Exception
    {
        return methodAllocateInstance.invoke(unsafe, new Object[] {cls});
    }

    public void loadRenderers()
    {
        KirChunkBuilder.invalidate();
        super.loadRenderers();

        if (mod_Kirmod.BETA_LIGHTING.wantsWorldRenderers() && canSwapWorldRenderers())
        {
            this.swapWorldRenderers();
        }
    }

    /** Replaces every chunk renderer with a KirWorldRenderer, in all three lists that hold them. */
    private void swapWorldRenderers()
    {
        try
        {
            WorldRenderer[] var1 = (WorldRenderer[])fieldWorldRenderers.get(this);
            WorldRenderer[] var2 = (WorldRenderer[])fieldSortedWorldRenderers.get(this);
            List var3 = (List)fieldWorldRenderersToUpdate.get(this);

            if (var1 == null)
            {
                return;
            }

            IdentityHashMap var4 = new IdentityHashMap();

            for (int var5 = 0; var5 < var1.length; ++var5)
            {
                WorldRenderer var6 = KirWorldRenderer.wrap(var1[var5]);
                var4.put(var1[var5], var6);
                var1[var5] = var6;
            }

            for (int var5 = 0; var2 != null && var5 < var2.length; ++var5)
            {
                if (var4.containsKey(var2[var5]))
                {
                    var2[var5] = (WorldRenderer)var4.get(var2[var5]);
                }
            }

            for (int var5 = 0; var5 < var3.size(); ++var5)
            {
                if (var4.containsKey(var3.get(var5)))
                {
                    var3.set(var5, var4.get(var3.get(var5)));
                }
            }
        }
        catch (Exception var7)
        {
            swapFailed = true;
            KirUtil.log("Could not swap the chunk renderers, Beta Lighting's smooth lighting is unavailable", var7);
        }
    }

    /**
     * Whether the chunk renderers are KirWorldRenderers, or null if there are none yet (no world).
     */
    Boolean hasKirWorldRenderers()
    {
        if (!canSwapWorldRenderers())
        {
            return null;
        }

        try
        {
            WorldRenderer[] var1 = (WorldRenderer[])fieldWorldRenderers.get(this);
            return var1 != null && var1.length > 0 ? Boolean.valueOf(var1[0] instanceof KirWorldRenderer) : null;
        }
        catch (IllegalAccessException var2)
        {
            return null;
        }
    }

    /** Queues every chunk for a rebuild, as Beta's RenderGlobal.updateAllRenderers did. */
    void markAllRenderersForUpdate()
    {
        if (!canSwapWorldRenderers())
        {
            return;
        }

        try
        {
            WorldRenderer[] var1 = (WorldRenderer[])fieldWorldRenderers.get(this);
            List var2 = (List)fieldWorldRenderersToUpdate.get(this);

            for (int var3 = 0; var1 != null && var3 < var1.length; ++var3)
            {
                if (var1[var3] != null && !var1[var3].needsUpdate)
                {
                    var2.add(var1[var3]);
                    var1[var3].markDirty();
                }
            }
        }
        catch (IllegalAccessException var4)
        {
            ;
        }
    }

    /**
     * Multi-Core Chunks schedules the chunk rebuilds itself while it is on. Chunks built here on the render
     * thread pass their quads to KirRenderHook.onChunkQuad (via KirTessellator).
     */
    public boolean updateRenderers(EntityLiving par1EntityLiving, boolean par2)
    {
        KirRenderHook var3 = KirRenderHook.hook;
        IBlockAccess var4 = KirTessellator.renderThreadBlocks;

        if (var3 != null && var3.skip(KirRenderHook.SKIP_CHUNK_UPDATES))
        {
            return true;
        }

        if (var3 != null && var3.wantsChunkQuads())
        {
            KirTessellator.renderThreadBlocks = ModLoader.getMinecraftInstance().theWorld;
        }

        try
        {
            if (mod_Kirmod.MULTICORE_CHUNKS.isActive())
            {
                return KirChunkBuilder.update(this, par1EntityLiving);
            }

            return super.updateRenderers(par1EntityLiving, par2);
        }
        finally
        {
            KirTessellator.renderThreadBlocks = var4;
        }
    }

    /** How deep inside sortAndRender the render thread is (it calls renderAllRenderLists itself). */
    private int sortDepth;

    public int sortAndRender(EntityLiving par1EntityLiving, int par2, double par3)
    {
        KirRenderHook var5 = KirRenderHook.hook;

        if (var5 == null)
        {
            return super.sortAndRender(par1EntityLiving, par2, par3);
        }

        if (par2 == 1 && var5.skip(KirRenderHook.SKIP_TRANSLUCENT))
        {
            return 0;
        }

        var5.beforeTerrain(par2);
        ++this.sortDepth;

        try
        {
            return super.sortAndRender(par1EntityLiving, par2, par3);
        }
        finally
        {
            --this.sortDepth;
            var5.afterTerrain(par2);
        }
    }

    /** Fancy graphics draw the water a second time with this, straight from EntityRenderer. */
    public void renderAllRenderLists(int par1, double par2)
    {
        KirRenderHook var4 = KirRenderHook.hook;

        if (var4 == null || this.sortDepth > 0)
        {
            super.renderAllRenderLists(par1, par2);
            return;
        }

        if (par1 == 1 && var4.skip(KirRenderHook.SKIP_TRANSLUCENT))
        {
            return;
        }

        var4.beforeTerrain(par1);

        try
        {
            super.renderAllRenderLists(par1, par2);
        }
        finally
        {
            var4.afterTerrain(par1);
        }
    }

    public void renderSky(float par1)
    {
        KirRenderHook var2 = KirRenderHook.hook;

        if (var2 == null)
        {
            super.renderSky(par1);
            return;
        }

        if (var2.skip(KirRenderHook.SKIP_SKY))
        {
            return;
        }

        var2.beforeSky(par1);

        try
        {
            super.renderSky(par1);
        }
        finally
        {
            var2.afterSky(par1);
        }
    }

    public void renderClouds(float par1)
    {
        KirRenderHook var2 = KirRenderHook.hook;

        if (var2 == null)
        {
            super.renderClouds(par1);
            return;
        }

        if (var2.skip(KirRenderHook.SKIP_CLOUDS))
        {
            return;
        }

        var2.beforeClouds(par1);

        try
        {
            super.renderClouds(par1);
        }
        finally
        {
            var2.afterClouds(par1);
        }
    }

    public void clipRenderersByFrustum(ICamera par1ICamera, float par2)
    {
        if (mod_Kirmod.BETA_LIGHTING.isActive())
        {
            mod_Kirmod.BETA_LIGHTING.uploadLightmap(ModLoader.getMinecraftInstance());
        }

        super.clipRenderersByFrustum(par1ICamera, par2);
    }

    public void renderEntities(Vec3 par1Vec3, ICamera par2ICamera, float par3)
    {
        KirRenderHook var5 = KirRenderHook.hook;

        if (var5 == null)
        {
            this.renderEntitiesDistance(par1Vec3, par2ICamera, par3);
            return;
        }

        if (var5.skip(KirRenderHook.SKIP_ENTITIES))
        {
            return;
        }

        var5.beforeEntities(par3);

        try
        {
            this.renderEntitiesDistance(par1Vec3, par2ICamera, par3);
        }
        finally
        {
            var5.afterEntities(par3);
        }
    }

    private void renderEntitiesDistance(Vec3 par1Vec3, ICamera par2ICamera, float par3)
    {
        int var4 = mod_Kirmod.TILE_ENTITY_DISTANCE.getDistance();

        if (var4 <= 0)
        {
            super.renderEntities(par1Vec3, par2ICamera, par3);
            return;
        }

        // Vanilla renders nothing for a couple of frames after loading renderers.
        boolean var5 = this.getStartupCounter() > 0;
        List var6 = this.tileEntities;
        this.tileEntities = Collections.EMPTY_LIST;

        try
        {
            super.renderEntities(par1Vec3, par2ICamera, par3);
        }
        finally
        {
            this.tileEntities = var6;
        }

        if (!var5)
        {
            this.renderTileEntities(var6, (double)var4, par3);
        }
    }

    /** The tile entity part of renderEntities, with a distance limit of maxDistance blocks. */
    private void renderTileEntities(List tileEntities, double maxDistance, float partialTicks)
    {
        Minecraft var5 = ModLoader.getMinecraftInstance();
        TileEntityRenderer var6 = TileEntityRenderer.instance;
        World var7 = var6.worldObj;

        if (var7 == null)
        {
            return;
        }

        var7.theProfiler.startSection("tileentities");
        double var8 = maxDistance * maxDistance;
        // Standard item lighting is still on from the entities.
        var5.entityRenderer.enableLightmap((double)partialTicks);
        Iterator var10 = tileEntities.iterator();

        while (var10.hasNext())
        {
            TileEntity var11 = (TileEntity)var10.next();

            if (var11.getDistanceFrom(var6.playerX, var6.playerY, var6.playerZ) < var8)
            {
                int var12 = var7.getLightBrightnessForSkyBlocks(var11.xCoord, var11.yCoord, var11.zCoord, 0);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, (float)(var12 % 65536), (float)(var12 / 65536));
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                var6.renderTileEntityAt(var11, (double)var11.xCoord - TileEntityRenderer.staticPlayerX, (double)var11.yCoord - TileEntityRenderer.staticPlayerY, (double)var11.zCoord - TileEntityRenderer.staticPlayerZ, partialTicks);
            }
        }

        var5.entityRenderer.disableLightmap((double)partialTicks);
        var7.theProfiler.endSection();
    }

    private int getStartupCounter()
    {
        try
        {
            return fieldStartupCounter.getInt(this);
        }
        catch (IllegalAccessException var2)
        {
            return 0;
        }
    }

    public void drawSelectionBox(EntityPlayer par1EntityPlayer, MovingObjectPosition par2MovingObjectPosition, int par3, ItemStack par4ItemStack, float par5)
    {
        if (par3 == 0 && par2MovingObjectPosition.typeOfHit == EnumMovingObjectType.TILE && mod_Kirmod.BLOCK_OUTLINE.isHighlight())
        {
            mod_Kirmod.BLOCK_OUTLINE.drawHighlight(par1EntityPlayer, par2MovingObjectPosition, par5);
        }
        else
        {
            super.drawSelectionBox(par1EntityPlayer, par2MovingObjectPosition, par3, par4ItemStack, par5);
        }
    }
}
