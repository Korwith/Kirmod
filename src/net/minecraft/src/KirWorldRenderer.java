package net.minecraft.src;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import org.lwjgl.opengl.GL11;

/**
 * A chunk renderer that builds its chunk with KirRenderBlocks while Beta Lighting's smooth lighting is
 * on, and exactly like vanilla otherwise.
 *
 * updateRenderer() is a copy of vanilla's with `new KirRenderBlocks` in place of `new RenderBlocks`.
 * Forge patches this method (render pass and per-block texture hooks, Block.hasTileEntity(metadata) and
 * canRenderInPass), so when Forge is there the copy calls those through reflection, in the same places
 * Forge's version does.
 *
 * KirRenderGlobal.loadRenderers makes these from the vanilla renderers without a constructor, which
 * would allocate GL display lists, by copying every field.
 */
public class KirWorldRenderer extends WorldRenderer
{
    private static final Field fieldGlRenderList = KirUtil.findField(WorldRenderer.class, "glRenderList", "y");
    private static final Field fieldTileEntities = KirUtil.findField(WorldRenderer.class, "tileEntities", "B");
    private static final Field fieldBytesDrawn = KirUtil.findField(WorldRenderer.class, "bytesDrawn", "C");
    private static final Field fieldIsInitialized = KirUtil.findField(WorldRenderer.class, "isInitialized", "A");

    /** Forge's hooks, all null without Forge. */
    private static Method beforeRenderPass;
    private static Method afterRenderPass;
    private static Method beforeBlockRender;
    private static Method afterBlockRender;
    private static Method hasTileEntityMeta;
    private static Method canRenderInPass;

    static
    {
        try
        {
            Class var0 = Class.forName("net.minecraftforge.client.ForgeHooksClient");
            beforeRenderPass = var0.getMethod("beforeRenderPass", new Class[] {Integer.TYPE});
            afterRenderPass = var0.getMethod("afterRenderPass", new Class[] {Integer.TYPE});
            beforeBlockRender = var0.getMethod("beforeBlockRender", new Class[] {Block.class, RenderBlocks.class});
            afterBlockRender = var0.getMethod("afterBlockRender", new Class[] {Block.class, RenderBlocks.class});
            hasTileEntityMeta = Block.class.getMethod("hasTileEntity", new Class[] {Integer.TYPE});
            canRenderInPass = Block.class.getMethod("canRenderInPass", new Class[] {Integer.TYPE});
        }
        catch (ClassNotFoundException var1)
        {
            ;
        }
        catch (Exception var2)
        {
            beforeRenderPass = null;
            KirUtil.log("Forge's chunk render hooks not found, Beta Lighting's smooth lighting is unavailable", var2);
        }
    }

    private static boolean failed = fieldGlRenderList == null || fieldTileEntities == null || fieldBytesDrawn == null || fieldIsInitialized == null;

    /** Never called: wrap() makes the instance without a constructor. */
    private KirWorldRenderer(World par1World, List par2List, int par3, int par4, int par5, int par6)
    {
        super(par1World, par2List, par3, par4, par5, par6);
    }

    /** A KirWorldRenderer with renderer's state, which it replaces; renderer itself if that fails. */
    static WorldRenderer wrap(WorldRenderer renderer) throws Exception
    {
        if (renderer == null || renderer instanceof KirWorldRenderer)
        {
            return renderer;
        }

        KirWorldRenderer var1 = (KirWorldRenderer)KirRenderGlobal.allocateInstance(KirWorldRenderer.class);
        KirUtil.copyFields(renderer, var1);
        return var1;
    }

    private static boolean hasForge()
    {
        return beforeRenderPass != null;
    }

    public void updateRenderer()
    {
        if (failed || !this.needsUpdate || !mod_Kirmod.BETA_LIGHTING.isSmoothActive())
        {
            super.updateRenderer();
            return;
        }

        try
        {
            this.buildChunk();
        }
        catch (Exception var2)
        {
            failed = true;
            KirUtil.log("Could not build a chunk with Beta's smooth lighting, using vanilla's", var2);
            this.needsUpdate = true;
            super.updateRenderer();
        }
    }

    /** Vanilla's (or Forge's) updateRenderer with KirRenderBlocks. */
    private void buildChunk() throws Exception
    {
        Tessellator var1 = Tessellator.instance;
        int var2 = fieldGlRenderList.getInt(this);
        List var3 = (List)fieldTileEntities.get(this);
        this.needsUpdate = false;
        int var4 = this.posX;
        int var5 = this.posY;
        int var6 = this.posZ;
        int var7 = this.posX + 16;
        int var8 = this.posY + 16;
        int var9 = this.posZ + 16;

        for (int var10 = 0; var10 < 2; ++var10)
        {
            this.skipRenderPass[var10] = true;
        }

        Chunk.isLit = false;
        HashSet var11 = new HashSet();
        var11.addAll(this.tileEntityRenderers);
        this.tileEntityRenderers.clear();
        byte var12 = 1;
        ChunkCache var13 = new ChunkCache(this.worldObj, var4 - var12, var5 - var12, var6 - var12, var7 + var12, var8 + var12, var9 + var12);

        if (!var13.extendedLevelsInChunkCache())
        {
            ++chunksUpdated;
            KirRenderBlocks var14 = new KirRenderBlocks(var13);
            int var15 = 0;

            for (int var16 = 0; var16 < 2; ++var16)
            {
                boolean var17 = false;
                boolean var18 = false;
                boolean var19 = false;

                for (int var20 = var5; var20 < var8; ++var20)
                {
                    for (int var21 = var6; var21 < var9; ++var21)
                    {
                        for (int var22 = var4; var22 < var7; ++var22)
                        {
                            int var23 = var13.getBlockId(var22, var20, var21);

                            if (var23 > 0)
                            {
                                if (!var19)
                                {
                                    var19 = true;
                                    GL11.glNewList(var2 + var16, GL11.GL_COMPILE);
                                    GL11.glPushMatrix();
                                    GL11.glTranslatef((float)this.posXClip, (float)this.posYClip, (float)this.posZClip);
                                    float var24 = 1.000001F;
                                    GL11.glTranslatef(-8.0F, -8.0F, -8.0F);
                                    GL11.glScalef(var24, var24, var24);
                                    GL11.glTranslatef(8.0F, 8.0F, 8.0F);

                                    if (hasForge())
                                    {
                                        beforeRenderPass.invoke((Object)null, new Object[] {Integer.valueOf(var16)});
                                    }

                                    var1.startDrawingQuads();
                                    var1.setTranslation((double)(-this.posX), (double)(-this.posY), (double)(-this.posZ));
                                }

                                Block var25 = Block.blocksList[var23];

                                if (var25 != null)
                                {
                                    if (var16 == 0 && this.hasTileEntity(var25, var13, var22, var20, var21))
                                    {
                                        TileEntity var26 = var13.getBlockTileEntity(var22, var20, var21);

                                        if (TileEntityRenderer.instance.hasSpecialRenderer(var26))
                                        {
                                            this.tileEntityRenderers.add(var26);
                                        }
                                    }

                                    int var27 = var25.getRenderBlockPass();

                                    if (hasForge())
                                    {
                                        if (var27 > var16)
                                        {
                                            var17 = true;
                                        }

                                        if (((Boolean)canRenderInPass.invoke(var25, new Object[] {Integer.valueOf(var16)})).booleanValue())
                                        {
                                            beforeBlockRender.invoke((Object)null, new Object[] {var25, var14});
                                            var18 |= var14.renderBlockByRenderType(var25, var22, var20, var21);
                                            afterBlockRender.invoke((Object)null, new Object[] {var25, var14});
                                        }
                                    }
                                    else if (var27 != var16)
                                    {
                                        var17 = true;
                                    }
                                    else
                                    {
                                        var18 |= var14.renderBlockByRenderType(var25, var22, var20, var21);
                                    }
                                }
                            }
                        }
                    }
                }

                if (var19)
                {
                    if (hasForge())
                    {
                        afterRenderPass.invoke((Object)null, new Object[] {Integer.valueOf(var16)});
                    }

                    var15 += var1.draw();
                    GL11.glPopMatrix();
                    GL11.glEndList();
                    var1.setTranslation(0.0D, 0.0D, 0.0D);
                }
                else
                {
                    var18 = false;
                }

                if (var18)
                {
                    this.skipRenderPass[var16] = false;
                }

                if (!var17)
                {
                    break;
                }
            }

            fieldBytesDrawn.setInt(this, var15);
        }

        HashSet var28 = new HashSet();
        var28.addAll(this.tileEntityRenderers);
        var28.removeAll(var11);
        var3.addAll(var28);
        var11.removeAll(this.tileEntityRenderers);
        var3.removeAll(var11);
        this.isChunkLit = Chunk.isLit;
        fieldIsInitialized.setBoolean(this, true);
    }

    private boolean hasTileEntity(Block block, ChunkCache access, int x, int y, int z) throws Exception
    {
        if (hasForge())
        {
            return ((Boolean)hasTileEntityMeta.invoke(block, new Object[] {Integer.valueOf(access.getBlockMetadata(x, y, z))})).booleanValue();
        }

        return block.hasTileEntity();
    }
}
