package net.minecraft.src;

import java.nio.ByteBuffer;

/**
 * A hook into world rendering for plugins (new in Kirmod 1.34; the SEUS Shaders plugin uses it). At most one
 * hook is active, in KirRenderHook.hook; every method does nothing by default.
 *
 * Called from KirRenderGlobal (so the plugin must call KirRenderGlobal.install(mc)), from KirTessellator (so it
 * must call KirTessellator.installProxy()) and from KirChunkBuilder (Multi-Core Chunks). All on the render
 * thread except onChunkQuad, which also runs on chunk worker threads.
 */
public abstract class KirRenderHook
{
    /** The active hook, or null. */
    public static volatile KirRenderHook hook;

    /** What skip() can leave out (Kirmod 1.35). */
    public static final int SKIP_SKY = 0;
    public static final int SKIP_CLOUDS = 1;
    /** RenderGlobal.sortAndRender / renderAllRenderLists pass 1 (water, ice, portals). */
    public static final int SKIP_TRANSLUCENT = 2;
    /** RenderGlobal.updateRenderers (chunk rebuilds) for this frame. */
    public static final int SKIP_CHUNK_UPDATES = 3;
    /** RenderGlobal.renderEntities (entities and tile entities). */
    public static final int SKIP_ENTITIES = 4;

    /** True to leave a part of the world out of the current render (the SEUS shadow pass, say). */
    public boolean skip(int what)
    {
        return false;
    }

    /** Around RenderGlobal.renderSky. */
    public void beforeSky(float partialTicks) {}
    public void afterSky(float partialTicks) {}

    /** Around RenderGlobal.sortAndRender (pass 0 = solid terrain, 1 = water and other translucent blocks). */
    public void beforeTerrain(int pass) {}
    public void afterTerrain(int pass) {}

    /** Around RenderGlobal.renderEntities (entities and tile entities). */
    public void beforeEntities(float partialTicks) {}
    public void afterEntities(float partialTicks) {}

    /** Around RenderGlobal.renderClouds. */
    public void beforeClouds(float partialTicks) {}
    public void afterClouds(float partialTicks) {}

    /** Whether onChunkQuad should be called for each quad of a chunk while it is built. */
    public boolean wantsChunkQuads()
    {
        return false;
    }

    /**
     * A quad of a chunk was added to a Tessellator: its 4 vertices are rawBuffer[start .. start + 31], 8 ints
     * each (x, y, z, u, v, color, normal, brightness); x, y, z are world coordinates plus the tessellator's
     * translation (offsetX/Y/Z). blocks is the world (or Multi-Core Chunks' copy of it) the chunk is built
     * from. The hook may change the ints in place; the tessellator then draws them with normals.
     */
    public void onChunkQuad(int[] rawBuffer, int start, double offsetX, double offsetY, double offsetZ, IBlockAccess blocks) {}

    /**
     * Around a Tessellator.draw() on the render thread (chunk = it is drawn into a chunk's display list).
     * vertices is the tessellator's direct buffer the vertices are copied into before drawing (8 ints per
     * vertex), or null if it could not be found.
     */
    public void beforeDraw(ByteBuffer vertices, boolean chunk) {}
    public void afterDraw(ByteBuffer vertices, boolean chunk) {}
}
