package net.minecraft.src;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;

/**
 * The block renderer KirWorldRenderer builds chunks with: Beta 1.7.3's smooth lighting.
 *
 * renderStandardBlockWithAmbientOcclusion is Beta's: the light at each corner of a face is the average
 * of the brightness of the four blocks touching it in front of the face (the corner block only counts
 * if light can get there past one of the edge blocks). Solid blocks hold no light, so they count as the
 * darkest level, which is what makes Beta's corner shadows. Like in Beta it also smooths blocks that
 * give off light.
 *
 * The brightness is baked into the vertex colors and every vertex gets lightmap coordinates that Beta
 * Lighting's lightmap draws at full brightness, so the vertex colors are exactly Beta's.
 *
 * The per-vertex colors are private fields of RenderBlocks (public under Forge), read by the
 * render*Face methods, so they are set through reflection.
 */
public class KirRenderBlocks extends RenderBlocks
{
    private static final Field fieldEnableAO = KirUtil.findField(RenderBlocks.class, "enableAO", "m");
    private static final Field fieldOverrideBlockTexture = KirUtil.findField(RenderBlocks.class, "overrideBlockTexture", "d");
    private static final Field fieldRenderAllFaces = KirUtil.findField(RenderBlocks.class, "renderAllFaces", "f");

    /** Red, green and blue of the top left, bottom left, bottom right and top right vertex. */
    private static final Field[] fieldColors = new Field[] {
        KirUtil.findField(RenderBlocks.class, "colorRedTopLeft", "ar"),
        KirUtil.findField(RenderBlocks.class, "colorGreenTopLeft", "av"),
        KirUtil.findField(RenderBlocks.class, "colorBlueTopLeft", "az"),
        KirUtil.findField(RenderBlocks.class, "colorRedBottomLeft", "as"),
        KirUtil.findField(RenderBlocks.class, "colorGreenBottomLeft", "aw"),
        KirUtil.findField(RenderBlocks.class, "colorBlueBottomLeft", "aA"),
        KirUtil.findField(RenderBlocks.class, "colorRedBottomRight", "at"),
        KirUtil.findField(RenderBlocks.class, "colorGreenBottomRight", "ax"),
        KirUtil.findField(RenderBlocks.class, "colorBlueBottomRight", "aB"),
        KirUtil.findField(RenderBlocks.class, "colorRedTopRight", "au"),
        KirUtil.findField(RenderBlocks.class, "colorGreenTopRight", "ay"),
        KirUtil.findField(RenderBlocks.class, "colorBlueTopRight", "aC")
    };

    /** Lightmap coordinates of the same four vertices. */
    private static final Field[] fieldBrightness = new Field[] {
        KirUtil.findField(RenderBlocks.class, "brightnessTopLeft", "an"),
        KirUtil.findField(RenderBlocks.class, "brightnessBottomLeft", "ao"),
        KirUtil.findField(RenderBlocks.class, "brightnessBottomRight", "ap"),
        KirUtil.findField(RenderBlocks.class, "brightnessTopRight", "aq")
    };

    private static boolean failed = !allFound();

    /** Light level of the block being drawn; Beta's Block.getBlockBrightness never goes below it. */
    private int ownLight;

    /** The colors of the face being drawn, in fieldColors order. */
    private final float[] colors = new float[12];

    public KirRenderBlocks(IBlockAccess par1IBlockAccess)
    {
        super(par1IBlockAccess);
    }

    private static boolean allFound()
    {
        if (fieldEnableAO == null || fieldOverrideBlockTexture == null || fieldRenderAllFaces == null)
        {
            return false;
        }

        for (int var0 = 0; var0 < fieldColors.length; ++var0)
        {
            if (fieldColors[var0] == null)
            {
                return false;
            }
        }

        for (int var1 = 0; var1 < fieldBrightness.length; ++var1)
        {
            if (fieldBrightness[var1] == null)
            {
                return false;
            }
        }

        return true;
    }

    private static boolean isBetaSmooth()
    {
        return !failed && mod_Kirmod.BETA_LIGHTING.isSmoothActive();
    }

    /** Beta smooths blocks that give off light too; 1.3.2 draws those flat. */
    public boolean renderStandardBlock(Block par1Block, int par2, int par3, int par4)
    {
        if (Block.lightValue[par1Block.blockID] == 0 || !Minecraft.isAmbientOcclusionEnabled() || !isBetaSmooth())
        {
            return super.renderStandardBlock(par1Block, par2, par3, par4);
        }

        int var5 = par1Block.colorMultiplier(this.blockAccess, par2, par3, par4);
        float var6 = (float)(var5 >> 16 & 255) / 255.0F;
        float var7 = (float)(var5 >> 8 & 255) / 255.0F;
        float var8 = (float)(var5 & 255) / 255.0F;

        if (EntityRenderer.anaglyphEnable)
        {
            float var9 = (var6 * 30.0F + var7 * 59.0F + var8 * 11.0F) / 100.0F;
            float var10 = (var6 * 30.0F + var7 * 70.0F) / 100.0F;
            float var11 = (var6 * 30.0F + var8 * 70.0F) / 100.0F;
            var6 = var9;
            var7 = var10;
            var8 = var11;
        }

        return this.renderStandardBlockWithAmbientOcclusion(par1Block, par2, par3, par4, var6, var7, var8);
    }

    public boolean renderStandardBlockWithAmbientOcclusion(Block par1Block, int par2, int par3, int par4, float par5, float par6, float par7)
    {
        if (!isBetaSmooth())
        {
            return super.renderStandardBlockWithAmbientOcclusion(par1Block, par2, par3, par4, par5, par6, par7);
        }

        try
        {
            return this.renderBetaAmbientOcclusion(par1Block, par2, par3, par4, par5, par6, par7);
        }
        catch (IllegalAccessException var9)
        {
            failed = true;
            KirUtil.log("Could not draw Beta's smooth lighting", var9);
            return super.renderStandardBlockWithAmbientOcclusion(par1Block, par2, par3, par4, par5, par6, par7);
        }
    }

    /** Beta's Block.getBlockBrightness for the block being drawn. */
    private float light(int x, int y, int z)
    {
        return mod_Kirmod.BETA_LIGHTING.getBrightness(this.blockAccess.getLightBrightnessForSkyBlocks(x, y, z, 0), this.ownLight);
    }

    /** Beta's Block.canBlockGrass for a neighbor: whether light gets through it to a corner. */
    private boolean clear(int x, int y, int z)
    {
        return Block.canBlockGrass[this.blockAccess.getBlockId(x, y, z)];
    }

    /**
     * Sets the four vertex colors of a face: shade (Beta's 0.5 bottom, 1 top, 0.8 north/south, 0.6
     * east/west) times the tint (if tinted) times each corner's light.
     */
    private void setFaceColors(float shade, boolean tinted, float red, float green, float blue, float topLeft, float bottomLeft, float bottomRight, float topRight) throws IllegalAccessException
    {
        float var10 = (tinted ? red : 1.0F) * shade;
        float var11 = (tinted ? green : 1.0F) * shade;
        float var12 = (tinted ? blue : 1.0F) * shade;
        float[] var13 = new float[] {topLeft, bottomLeft, bottomRight, topRight};

        for (int var14 = 0; var14 < 4; ++var14)
        {
            this.colors[var14 * 3] = var10 * var13[var14];
            this.colors[var14 * 3 + 1] = var11 * var13[var14];
            this.colors[var14 * 3 + 2] = var12 * var13[var14];
        }

        this.applyColors();
    }

    /** Multiplies the face colors by the tint, for the grass side overlay. */
    private void tintFaceColors(float red, float green, float blue) throws IllegalAccessException
    {
        for (int var4 = 0; var4 < 4; ++var4)
        {
            this.colors[var4 * 3] *= red;
            this.colors[var4 * 3 + 1] *= green;
            this.colors[var4 * 3 + 2] *= blue;
        }

        this.applyColors();
    }

    private void applyColors() throws IllegalAccessException
    {
        for (int var1 = 0; var1 < 12; ++var1)
        {
            fieldColors[var1].setFloat(this, this.colors[var1]);
        }
    }

    /** Beta 1.7.3's RenderBlocks.renderStandardBlockWithAmbientOcclusion. */
    private boolean renderBetaAmbientOcclusion(Block block, int x, int y, int z, float red, float green, float blue) throws IllegalAccessException
    {
        boolean var8 = false;
        boolean var9 = fieldRenderAllFaces.getBoolean(this);
        int var10 = fieldOverrideBlockTexture.getInt(this);
        boolean var11 = block.blockIndexInTexture != 3 && var10 < 0;
        this.ownLight = Block.lightValue[block.blockID];

        for (int var12 = 0; var12 < 4; ++var12)
        {
            fieldBrightness[var12].setInt(this, KirModuleBetaLighting.FULL_BRIGHTNESS);
        }

        fieldEnableAO.setBoolean(this, true);
        Tessellator.instance.setBrightness(KirModuleBetaLighting.FULL_BRIGHTNESS);

        float var13 = this.light(x - 1, y, z);
        float var14 = this.light(x, y - 1, z);
        float var15 = this.light(x, y, z - 1);
        float var16 = this.light(x + 1, y, z);
        float var17 = this.light(x, y + 1, z);
        float var18 = this.light(x, y, z + 1);
        boolean var19 = this.clear(x + 1, y + 1, z);
        boolean var20 = this.clear(x + 1, y - 1, z);
        boolean var21 = this.clear(x + 1, y, z + 1);
        boolean var22 = this.clear(x + 1, y, z - 1);
        boolean var23 = this.clear(x - 1, y + 1, z);
        boolean var24 = this.clear(x - 1, y - 1, z);
        boolean var25 = this.clear(x - 1, y, z - 1);
        boolean var26 = this.clear(x - 1, y, z + 1);
        boolean var27 = this.clear(x, y + 1, z + 1);
        boolean var28 = this.clear(x, y + 1, z - 1);
        boolean var29 = this.clear(x, y - 1, z + 1);
        boolean var30 = this.clear(x, y - 1, z - 1);
        int var31;

        if (var9 || block.shouldSideBeRendered(this.blockAccess, x, y - 1, z, 0))
        {
            int var32 = y - 1;
            float var33 = this.light(x - 1, var32, z);
            float var34 = this.light(x, var32, z - 1);
            float var35 = this.light(x, var32, z + 1);
            float var36 = this.light(x + 1, var32, z);
            float var37 = !var30 && !var24 ? var33 : this.light(x - 1, var32, z - 1);
            float var38 = !var29 && !var24 ? var33 : this.light(x - 1, var32, z + 1);
            float var39 = !var30 && !var20 ? var36 : this.light(x + 1, var32, z - 1);
            float var40 = !var29 && !var20 ? var36 : this.light(x + 1, var32, z + 1);
            this.setFaceColors(0.5F, var11, red, green, blue, (var38 + var33 + var35 + var14) / 4.0F, (var33 + var37 + var14 + var34) / 4.0F, (var14 + var34 + var36 + var39) / 4.0F, (var35 + var14 + var40 + var36) / 4.0F);
            this.renderBottomFace(block, (double)x, (double)y, (double)z, block.getBlockTexture(this.blockAccess, x, y, z, 0));
            var8 = true;
        }

        if (var9 || block.shouldSideBeRendered(this.blockAccess, x, y + 1, z, 1))
        {
            int var32 = y + 1;
            float var33 = this.light(x - 1, var32, z);
            float var34 = this.light(x + 1, var32, z);
            float var35 = this.light(x, var32, z - 1);
            float var36 = this.light(x, var32, z + 1);
            float var37 = !var28 && !var23 ? var33 : this.light(x - 1, var32, z - 1);
            float var38 = !var28 && !var19 ? var34 : this.light(x + 1, var32, z - 1);
            float var39 = !var27 && !var23 ? var33 : this.light(x - 1, var32, z + 1);
            float var40 = !var27 && !var19 ? var34 : this.light(x + 1, var32, z + 1);
            this.setFaceColors(1.0F, true, red, green, blue, (var36 + var17 + var40 + var34) / 4.0F, (var17 + var35 + var34 + var38) / 4.0F, (var33 + var37 + var17 + var35) / 4.0F, (var39 + var33 + var36 + var17) / 4.0F);
            this.renderTopFace(block, (double)x, (double)y, (double)z, block.getBlockTexture(this.blockAccess, x, y, z, 1));
            var8 = true;
        }

        if (var9 || block.shouldSideBeRendered(this.blockAccess, x, y, z - 1, 2))
        {
            int var32 = z - 1;
            float var33 = this.light(x - 1, y, var32);
            float var34 = this.light(x, y - 1, var32);
            float var35 = this.light(x, y + 1, var32);
            float var36 = this.light(x + 1, y, var32);
            float var37 = !var25 && !var30 ? var33 : this.light(x - 1, y - 1, var32);
            float var38 = !var25 && !var28 ? var33 : this.light(x - 1, y + 1, var32);
            float var39 = !var22 && !var30 ? var36 : this.light(x + 1, y - 1, var32);
            float var40 = !var22 && !var28 ? var36 : this.light(x + 1, y + 1, var32);
            this.setFaceColors(0.8F, var11, red, green, blue, (var33 + var38 + var15 + var35) / 4.0F, (var15 + var35 + var36 + var40) / 4.0F, (var34 + var15 + var39 + var36) / 4.0F, (var37 + var33 + var34 + var15) / 4.0F);
            var31 = block.getBlockTexture(this.blockAccess, x, y, z, 2);
            this.renderEastFace(block, (double)x, (double)y, (double)z, var31);

            if (fancyGrass && var31 == 3 && var10 < 0)
            {
                this.tintFaceColors(red, green, blue);
                this.renderEastFace(block, (double)x, (double)y, (double)z, 38);
            }

            var8 = true;
        }

        if (var9 || block.shouldSideBeRendered(this.blockAccess, x, y, z + 1, 3))
        {
            int var32 = z + 1;
            float var33 = this.light(x - 1, y, var32);
            float var34 = this.light(x + 1, y, var32);
            float var35 = this.light(x, y - 1, var32);
            float var36 = this.light(x, y + 1, var32);
            float var37 = !var26 && !var29 ? var33 : this.light(x - 1, y - 1, var32);
            float var38 = !var26 && !var27 ? var33 : this.light(x - 1, y + 1, var32);
            float var39 = !var21 && !var29 ? var34 : this.light(x + 1, y - 1, var32);
            float var40 = !var21 && !var27 ? var34 : this.light(x + 1, y + 1, var32);
            this.setFaceColors(0.8F, var11, red, green, blue, (var33 + var38 + var18 + var36) / 4.0F, (var37 + var33 + var35 + var18) / 4.0F, (var35 + var18 + var39 + var34) / 4.0F, (var18 + var36 + var34 + var40) / 4.0F);
            var31 = block.getBlockTexture(this.blockAccess, x, y, z, 3);
            this.renderWestFace(block, (double)x, (double)y, (double)z, var31);

            if (fancyGrass && var31 == 3 && var10 < 0)
            {
                this.tintFaceColors(red, green, blue);
                this.renderWestFace(block, (double)x, (double)y, (double)z, 38);
            }

            var8 = true;
        }

        if (var9 || block.shouldSideBeRendered(this.blockAccess, x - 1, y, z, 4))
        {
            int var32 = x - 1;
            float var33 = this.light(var32, y - 1, z);
            float var34 = this.light(var32, y, z - 1);
            float var35 = this.light(var32, y, z + 1);
            float var36 = this.light(var32, y + 1, z);
            float var37 = !var25 && !var24 ? var34 : this.light(var32, y - 1, z - 1);
            float var38 = !var26 && !var24 ? var35 : this.light(var32, y - 1, z + 1);
            float var39 = !var25 && !var23 ? var34 : this.light(var32, y + 1, z - 1);
            float var40 = !var26 && !var23 ? var35 : this.light(var32, y + 1, z + 1);
            this.setFaceColors(0.6F, var11, red, green, blue, (var13 + var35 + var36 + var40) / 4.0F, (var34 + var13 + var39 + var36) / 4.0F, (var37 + var33 + var34 + var13) / 4.0F, (var33 + var38 + var13 + var35) / 4.0F);
            var31 = block.getBlockTexture(this.blockAccess, x, y, z, 4);
            this.renderNorthFace(block, (double)x, (double)y, (double)z, var31);

            if (fancyGrass && var31 == 3 && var10 < 0)
            {
                this.tintFaceColors(red, green, blue);
                this.renderNorthFace(block, (double)x, (double)y, (double)z, 38);
            }

            var8 = true;
        }

        if (var9 || block.shouldSideBeRendered(this.blockAccess, x + 1, y, z, 5))
        {
            int var32 = x + 1;
            float var33 = this.light(var32, y - 1, z);
            float var34 = this.light(var32, y, z - 1);
            float var35 = this.light(var32, y, z + 1);
            float var36 = this.light(var32, y + 1, z);
            float var37 = !var20 && !var22 ? var34 : this.light(var32, y - 1, z - 1);
            float var38 = !var20 && !var21 ? var35 : this.light(var32, y - 1, z + 1);
            float var39 = !var19 && !var22 ? var34 : this.light(var32, y + 1, z - 1);
            float var40 = !var19 && !var21 ? var35 : this.light(var32, y + 1, z + 1);
            this.setFaceColors(0.6F, var11, red, green, blue, (var33 + var38 + var16 + var35) / 4.0F, (var37 + var33 + var34 + var16) / 4.0F, (var34 + var16 + var39 + var36) / 4.0F, (var16 + var35 + var36 + var40) / 4.0F);
            var31 = block.getBlockTexture(this.blockAccess, x, y, z, 5);
            this.renderSouthFace(block, (double)x, (double)y, (double)z, var31);

            if (fancyGrass && var31 == 3 && var10 < 0)
            {
                this.tintFaceColors(red, green, blue);
                this.renderSouthFace(block, (double)x, (double)y, (double)z, 38);
            }

            var8 = true;
        }

        fieldEnableAO.setBoolean(this, false);
        return var8;
    }
}
