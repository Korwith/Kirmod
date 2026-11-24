package net.minecraft.src;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The Tessellator of Multi-Core Chunks. Block rendering code always draws through the one static
 * Tessellator.instance, so worker threads can't each get their own by passing it around.
 *
 * Tessellator.instance is replaced by a KirTessellator in proxy mode. On any thread but a chunk worker it
 * is the game's Tessellator (it took over the original's state, and every call goes to super). On a
 * KirChunkBuilder.Worker thread every call is passed on to that worker's own KirTessellator, which is in
 * recording mode: draw() doesn't touch OpenGL (workers have no GL context) but copies the vertices into
 * a Command, which the render thread later compiles into the chunk's display list.
 *
 * Works with vanilla's Tessellator, Forge's rewrite (static buffers, growing rawBuffer) and OptiFine's
 * (growing buffers, sub-tessellators for Connected Textures); they keep vanilla's obfuscated field names.
 * OptiFine's getSubTessellator is overridden by name, so Connected Textures on a worker get the worker's
 * sub-tessellators.
 */
public class KirTessellator extends Tessellator
{
    static final Field fieldRawBuffer = KirUtil.findField(Tessellator.class, "rawBuffer", "h");
    static final Field fieldVertexCount = KirUtil.findField(Tessellator.class, "vertexCount", "i");
    static final Field fieldRawBufferIndex = KirUtil.findField(Tessellator.class, "rawBufferIndex", "r");
    static final Field fieldAddedVertices = KirUtil.findField(Tessellator.class, "addedVertices", "s");
    static final Field fieldHasColor = KirUtil.findField(Tessellator.class, "hasColor", "n");
    static final Field fieldHasTexture = KirUtil.findField(Tessellator.class, "hasTexture", "o");
    static final Field fieldHasBrightness = KirUtil.findField(Tessellator.class, "hasBrightness", "p");
    static final Field fieldHasNormals = KirUtil.findField(Tessellator.class, "hasNormals", "q");
    static final Field fieldDrawMode = KirUtil.findField(Tessellator.class, "drawMode", "u");
    static final Field fieldIsDrawing = KirUtil.findField(Tessellator.class, "isDrawing", "z");
    static final Field fieldXOffset = KirUtil.findField(Tessellator.class, "xOffset", "v");
    static final Field fieldYOffset = KirUtil.findField(Tessellator.class, "yOffset", "w");
    static final Field fieldZOffset = KirUtil.findField(Tessellator.class, "zOffset", "x");

    /** Forge's growing buffer size; null without Forge. */
    private static final Field fieldRawBufferSize = quietField("rawBufferSize");

    /** OptiFine's sub-tessellators (one per extra texture); null without OptiFine. */
    private static final Field fieldSubTessellators = quietField("subTessellators");
    private static final Field fieldSubTextures = quietField("subTextures");

    /** OptiFine's Tessellator.getSubTessellator(int), called without virtual dispatch; null without OptiFine. */
    private static MethodHandle superGetSubTessellator;

    static
    {
        try
        {
            Tessellator.class.getMethod("getSubTessellator", new Class[] {Integer.TYPE});
            superGetSubTessellator = MethodHandles.lookup().findSpecial(Tessellator.class, "getSubTessellator", MethodType.methodType(Tessellator.class, Integer.TYPE), KirTessellator.class);
        }
        catch (NoSuchMethodException var1)
        {
            ;
        }
        catch (Exception var2)
        {
            KirUtil.log("Could not find OptiFine's getSubTessellator", var2);
        }
    }

    /** One draw() of a worker: the vertices and how to draw them. */
    static class Command
    {
        /** GL texture to bind first (an OptiFine sub-tessellator's), or 0 for the chunk's own texture. */
        int texture;
        int[] data;
        int vertices;
        int drawMode;
        boolean hasTexture;
        boolean hasBrightness;
        boolean hasColor;
        boolean hasNormals;
    }

    /** True for the instance in Tessellator.instance, which hands calls from workers to their own. */
    private boolean proxy;

    /** True for a worker's instance: draw() records Commands instead of drawing. */
    private boolean recording;

    /** Commands recorded since takeCommands(). */
    private List commands;

    /**
     * The world a chunk is being built from on the render thread (set by KirRenderGlobal.updateRenderers
     * while KirRenderHook wants chunk quads), or null.
     */
    static IBlockAccess renderThreadBlocks;

    /** A recorder's: the chunk copy its worker is building from (set by KirChunkBuilder.build), or null. */
    IBlockAccess chunkBlocks;

    /** The direct buffer Tessellator.draw copies the vertices into (static under Forge). */
    private static final Field fieldByteBuffer = findFieldOfType(java.nio.ByteBuffer.class);

    private static Field findFieldOfType(Class type)
    {
        Field[] var1 = Tessellator.class.getDeclaredFields();

        for (int var2 = 0; var2 < var1.length; ++var2)
        {
            if (var1[var2].getType() == type)
            {
                var1[var2].setAccessible(true);
                return var1[var2];
            }
        }

        return null;
    }

    /** The tessellator's direct vertex buffer, or null. */
    static java.nio.ByteBuffer getByteBuffer(Tessellator tess)
    {
        try
        {
            return fieldByteBuffer == null ? null : (java.nio.ByteBuffer)fieldByteBuffer.get(tess);
        }
        catch (Exception var2)
        {
            return null;
        }
    }

    /** After a vertex was added: hands a finished chunk quad to the render hook, which may change it. */
    private void afterVertex()
    {
        KirRenderHook var1 = KirRenderHook.hook;

        if (var1 == null)
        {
            return;
        }

        IBlockAccess var2 = this.recording ? this.chunkBlocks : (this.proxy ? renderThreadBlocks : null);

        if (var2 == null || fieldXOffset == null || fieldYOffset == null || fieldZOffset == null || !var1.wantsChunkQuads())
        {
            return;
        }

        try
        {
            int var3 = fieldRawBufferIndex.getInt(this);

            if (fieldDrawMode.getInt(this) != 7 || fieldAddedVertices.getInt(this) % 4 != 0 || var3 < 32)
            {
                return;
            }

            var1.onChunkQuad((int[])fieldRawBuffer.get(this), var3 - 32, fieldXOffset.getDouble(this), fieldYOffset.getDouble(this), fieldZOffset.getDouble(this), var2);
            fieldHasNormals.setBoolean(this, true);
        }
        catch (IllegalAccessException var4)
        {
            ;
        }
    }

    private static Field quietField(String name)
    {
        try
        {
            Field var1 = Tessellator.class.getDeclaredField(name);
            var1.setAccessible(true);
            return var1;
        }
        catch (Exception var2)
        {
            return null;
        }
    }

    static boolean isAvailable()
    {
        return fieldRawBuffer != null && fieldVertexCount != null && fieldRawBufferIndex != null && fieldAddedVertices != null && fieldHasColor != null && fieldHasTexture != null && fieldHasBrightness != null && fieldHasNormals != null && fieldDrawMode != null && fieldIsDrawing != null && KirRenderGlobal.isAvailable();
    }

    /**
     * Never called: instances are made with KirRenderGlobal.allocateInstance. (Compiling needs MCP's
     * Tessellator(int) to be protected instead of private; the shipped zip has no Tessellator.)
     */
    private KirTessellator()
    {
        super(0);
    }

    /**
     * Whether Tessellator.instance is final (a vanilla jar; Forge and OptiFine make it changeable). The JIT
     * may build a final static into compiled code, so it is then replaced at startup, before any block
     * rendering is compiled, rather than when Multi-Core Chunks is first turned on.
     */
    static boolean isInstanceFinal()
    {
        try
        {
            Field var0 = KirUtil.findField(Tessellator.class, "instance", "a");
            return var0 != null && java.lang.reflect.Modifier.isFinal(var0.getModifiers());
        }
        catch (Exception var1)
        {
            return false;
        }
    }

    /** Puts a proxy KirTessellator into Tessellator.instance (once). Render thread. */
    static boolean installProxy()
    {
        if (Tessellator.instance instanceof KirTessellator)
        {
            return true;
        }

        try
        {
            Tessellator var0 = Tessellator.instance;
            KirTessellator var1 = (KirTessellator)KirRenderGlobal.allocateInstance(KirTessellator.class);
            KirUtil.copyFields(var0, var1);
            var1.proxy = true;
            var1.recording = false;

            if (!KirUtil.setStaticField(Tessellator.class, var1, "instance", "a"))
            {
                return false;
            }

            // Vanilla's WorldRenderer keeps its own copy of the reference.
            try
            {
                WorldRenderer.class.getDeclaredField("tessellator");
                KirUtil.setStaticField(WorldRenderer.class, var1, "tessellator");
            }
            catch (NoSuchFieldException var3)
            {
                try
                {
                    Field var4 = WorldRenderer.class.getDeclaredField("z");

                    if (var4.getType() == Tessellator.class)
                    {
                        KirUtil.setStaticField(WorldRenderer.class, var1, "z");
                    }
                }
                catch (NoSuchFieldException var5)
                {
                    ;
                }
            }

            return Tessellator.instance == var1;
        }
        catch (Exception var6)
        {
            KirUtil.log("Could not replace the Tessellator, Multi-Core Chunks is unavailable", var6);
            return false;
        }
    }

    /** A recording KirTessellator for one worker thread. */
    static KirTessellator newRecorder() throws Exception
    {
        Tessellator var0 = Tessellator.instance;
        KirTessellator var1 = (KirTessellator)KirRenderGlobal.allocateInstance(KirTessellator.class);
        KirUtil.copyFields(var0, var1);
        var1.proxy = false;
        var1.recording = true;
        var1.commands = new ArrayList();
        fieldIsDrawing.setBoolean(var1, false);
        fieldVertexCount.setInt(var1, 0);
        fieldRawBufferIndex.setInt(var1, 0);
        fieldAddedVertices.setInt(var1, 0);

        if (fieldRawBufferSize != null)
        {
            // Forge's buffer grows on demand from nothing.
            fieldRawBufferSize.setInt(var1, 0);
            fieldRawBuffer.set(var1, (Object)null);
        }
        else
        {
            int[] var2 = (int[])fieldRawBuffer.get(var0);
            fieldRawBuffer.set(var1, new int[var2.length]);
        }

        if (fieldSubTessellators != null && fieldSubTextures != null)
        {
            fieldSubTessellators.set(var1, new Tessellator[0]);
            fieldSubTextures.set(var1, new int[0]);
        }

        return var1;
    }

    /** The recording tessellator of the current thread, or null if this isn't a proxy or not on a worker. */
    private Tessellator worker()
    {
        if (!this.proxy)
        {
            return null;
        }

        Thread var1 = Thread.currentThread();
        return var1 instanceof KirChunkBuilder.Worker ? ((KirChunkBuilder.Worker)var1).tessellator : null;
    }

    /** The commands recorded since the last call. */
    List takeCommands()
    {
        List var1 = this.commands;
        this.commands = new ArrayList();
        return var1;
    }

    /** Drops everything drawn so far (after an error). */
    void discard()
    {
        try
        {
            fieldIsDrawing.setBoolean(this, false);
            resetCounts(this);
        }
        catch (Exception var2)
        {
            ;
        }

        this.commands = new ArrayList();
    }

    private static void resetCounts(Tessellator tess) throws IllegalAccessException
    {
        fieldVertexCount.setInt(tess, 0);
        fieldRawBufferIndex.setInt(tess, 0);
        fieldAddedVertices.setInt(tess, 0);
    }

    private static Command capture(Tessellator tess, int texture) throws IllegalAccessException
    {
        int var2 = fieldRawBufferIndex.getInt(tess);

        if (var2 <= 0)
        {
            return null;
        }

        Command var3 = new Command();
        var3.texture = texture;
        int[] var4 = new int[var2];
        System.arraycopy((int[])fieldRawBuffer.get(tess), 0, var4, 0, var2);
        var3.data = var4;
        var3.vertices = var2 / 8;
        var3.drawMode = fieldDrawMode.getInt(tess);
        var3.hasTexture = fieldHasTexture.getBoolean(tess);
        var3.hasBrightness = fieldHasBrightness.getBoolean(tess);
        var3.hasColor = fieldHasColor.getBoolean(tess);
        var3.hasNormals = fieldHasNormals.getBoolean(tess);
        return var3;
    }

    /** draw() of a recording tessellator: OptiFine's sub-tessellators first, then its own vertices. */
    private int record()
    {
        try
        {
            if (!fieldIsDrawing.getBoolean(this))
            {
                throw new IllegalStateException("Not tesselating!");
            }

            if (fieldSubTessellators != null && fieldSubTextures != null)
            {
                Tessellator[] var1 = (Tessellator[])fieldSubTessellators.get(this);
                int[] var2 = (int[])fieldSubTextures.get(this);

                for (int var3 = 0; var3 < var2.length && var3 < var1.length; ++var3)
                {
                    if (var2[var3] <= 0)
                    {
                        break;
                    }

                    if (fieldIsDrawing.getBoolean(var1[var3]))
                    {
                        Command var4 = capture(var1[var3], var2[var3]);

                        if (var4 != null)
                        {
                            this.commands.add(var4);
                        }

                        fieldIsDrawing.setBoolean(var1[var3], false);
                        resetCounts(var1[var3]);
                    }

                    var2[var3] = 0;
                }
            }

            Command var5 = capture(this, 0);
            int var6 = fieldRawBufferIndex.getInt(this) * 4;

            if (var5 != null)
            {
                this.commands.add(var5);
            }

            fieldIsDrawing.setBoolean(this, false);
            resetCounts(this);
            return var6;
        }
        catch (IllegalAccessException var7)
        {
            throw new RuntimeException(var7);
        }
    }

    public int draw()
    {
        Tessellator var1 = this.worker();

        if (var1 != null)
        {
            return var1.draw();
        }

        if (this.recording)
        {
            return this.record();
        }

        KirRenderHook var2 = KirRenderHook.hook;

        if (var2 == null)
        {
            return super.draw();
        }

        boolean var3 = renderThreadBlocks != null;
        java.nio.ByteBuffer var4 = getByteBuffer(this);
        var2.beforeDraw(var4, var3);

        try
        {
            return super.draw();
        }
        finally
        {
            var2.afterDraw(var4, var3);
        }
    }

    public void startDrawingQuads()
    {
        Tessellator var1 = this.worker();

        if (var1 != null)
        {
            var1.startDrawingQuads();
            return;
        }

        super.startDrawingQuads();
    }

    public void startDrawing(int par1)
    {
        Tessellator var2 = this.worker();

        if (var2 != null)
        {
            var2.startDrawing(par1);
            return;
        }

        super.startDrawing(par1);
    }

    public void setTextureUV(double par1, double par3)
    {
        Tessellator var5 = this.worker();

        if (var5 != null)
        {
            var5.setTextureUV(par1, par3);
            return;
        }

        super.setTextureUV(par1, par3);
    }

    public void setBrightness(int par1)
    {
        Tessellator var2 = this.worker();

        if (var2 != null)
        {
            var2.setBrightness(par1);
            return;
        }

        super.setBrightness(par1);
    }

    public void setColorOpaque_F(float par1, float par2, float par3)
    {
        Tessellator var4 = this.worker();

        if (var4 != null)
        {
            var4.setColorOpaque_F(par1, par2, par3);
            return;
        }

        super.setColorOpaque_F(par1, par2, par3);
    }

    public void setColorRGBA_F(float par1, float par2, float par3, float par4)
    {
        Tessellator var5 = this.worker();

        if (var5 != null)
        {
            var5.setColorRGBA_F(par1, par2, par3, par4);
            return;
        }

        super.setColorRGBA_F(par1, par2, par3, par4);
    }

    public void setColorOpaque(int par1, int par2, int par3)
    {
        Tessellator var4 = this.worker();

        if (var4 != null)
        {
            var4.setColorOpaque(par1, par2, par3);
            return;
        }

        super.setColorOpaque(par1, par2, par3);
    }

    public void setColorRGBA(int par1, int par2, int par3, int par4)
    {
        Tessellator var5 = this.worker();

        if (var5 != null)
        {
            var5.setColorRGBA(par1, par2, par3, par4);
            return;
        }

        super.setColorRGBA(par1, par2, par3, par4);
    }

    public void addVertexWithUV(double par1, double par3, double par5, double par7, double par9)
    {
        Tessellator var11 = this.worker();

        if (var11 != null)
        {
            var11.addVertexWithUV(par1, par3, par5, par7, par9);
            return;
        }

        super.addVertexWithUV(par1, par3, par5, par7, par9);
    }

    public void addVertex(double par1, double par3, double par5)
    {
        Tessellator var7 = this.worker();

        if (var7 != null)
        {
            var7.addVertex(par1, par3, par5);
            return;
        }

        super.addVertex(par1, par3, par5);
        this.afterVertex();
    }

    public void setColorOpaque_I(int par1)
    {
        Tessellator var2 = this.worker();

        if (var2 != null)
        {
            var2.setColorOpaque_I(par1);
            return;
        }

        super.setColorOpaque_I(par1);
    }

    public void setColorRGBA_I(int par1, int par2)
    {
        Tessellator var3 = this.worker();

        if (var3 != null)
        {
            var3.setColorRGBA_I(par1, par2);
            return;
        }

        super.setColorRGBA_I(par1, par2);
    }

    public void disableColor()
    {
        Tessellator var1 = this.worker();

        if (var1 != null)
        {
            var1.disableColor();
            return;
        }

        super.disableColor();
    }

    public void setNormal(float par1, float par2, float par3)
    {
        Tessellator var4 = this.worker();

        if (var4 != null)
        {
            var4.setNormal(par1, par2, par3);
            return;
        }

        super.setNormal(par1, par2, par3);
    }

    public void setTranslation(double par1, double par3, double par5)
    {
        Tessellator var7 = this.worker();

        if (var7 != null)
        {
            var7.setTranslation(par1, par3, par5);
            return;
        }

        super.setTranslation(par1, par3, par5);
    }

    public void addTranslation(float par1, float par2, float par3)
    {
        Tessellator var4 = this.worker();

        if (var4 != null)
        {
            var4.addTranslation(par1, par2, par3);
            return;
        }

        super.addTranslation(par1, par2, par3);
    }

    /**
     * OptiFine's Connected Textures draw a face with another texture's sub-tessellator. Not obfuscated, so
     * this overrides OptiFine's method; without OptiFine nothing calls it.
     */
    public Tessellator getSubTessellator(int par1)
    {
        Tessellator var2 = this.worker();

        if (var2 != null)
        {
            return ((KirTessellator)var2).getSubTessellator(par1);
        }

        try
        {
            return (Tessellator)superGetSubTessellator.invokeWithArguments(new Object[] {this, Integer.valueOf(par1)});
        }
        catch (Throwable var4)
        {
            throw new RuntimeException(var4);
        }
    }
}
