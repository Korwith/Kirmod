package net.minecraft.src;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Block Outline: how the block under the crosshair is marked. "Default" is vanilla's thin black outline;
 * "Highlight" draws the block's own visible faces again, added on top of themselves, so its texture
 * lights up instead. Blocks drawn by a tile entity renderer (chests, signs) have no block model, so
 * their selection box is lit up instead.
 *
 * KirRenderGlobal.drawSelectionBox calls drawHighlight in place of the outline.
 */
public class KirModuleBlockOutline extends KirModule
{
    private static final String[] VALUES = new String[] {"Default", "Highlight"};

    /** How much of the texture is added on top of itself; 0.4 makes it 40% brighter. */
    private static final float HIGHLIGHT_STRENGTH = 0.4F;

    /** The same for the plain box around blocks without a model. */
    private static final float BOX_STRENGTH = 0.3F;

    private RenderBlocks renderBlocks;
    private World renderBlocksWorld;

    public KirModuleBlockOutline()
    {
        super("blockOutline", "Block Outline", "Block Outline", 0, "outline", "blockoutline");
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public String getCategory()
    {
        return CATEGORY_VISUALS;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "How the block you are looking at is marked.",
            describeValue("Default", "vanilla's thin black outline."),
            describeValue("Highlight", "the block's texture lights up instead, like on the console editions.")
        };
    }

    public boolean isAvailable()
    {
        return KirRenderGlobal.isAvailable();
    }

    public boolean isHighlight()
    {
        return this.enabled && this.getValue() == 1;
    }

    public void update(Minecraft mc)
    {
        if (this.isHighlight())
        {
            KirRenderGlobal.install(mc);
        }
    }

    /** Draws the highlight of the block hit points at; called where vanilla draws the outline. */
    void drawHighlight(EntityPlayer player, MovingObjectPosition hit, float partialTicks)
    {
        Minecraft var4 = ModLoader.getMinecraftInstance();
        WorldClient var5 = var4.theWorld;
        int var6 = hit.blockX;
        int var7 = hit.blockY;
        int var8 = hit.blockZ;
        int var9 = var5 == null ? 0 : var5.getBlockId(var6, var7, var8);
        Block var10 = var9 > 0 ? Block.blocksList[var9] : null;

        if (var10 == null)
        {
            return;
        }

        if (this.renderBlocks == null || this.renderBlocksWorld != var5)
        {
            this.renderBlocks = new RenderBlocks(var5);
            this.renderBlocksWorld = var5;
        }

        double var11 = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double)partialTicks;
        double var13 = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double)partialTicks;
        double var15 = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double)partialTicks;

        // Added on top of what is already drawn, pulled slightly towards the camera so it wins the depth test.
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        GL11.glPolygonOffset(-3.0F, -3.0F);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, HIGHLIGHT_STRENGTH);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, var4.renderEngine.getTexture("/terrain.png"));
        Tessellator var17 = Tessellator.instance;
        var17.startDrawingQuads();
        var17.setTranslation(-var11, -var13, -var15);
        // The block's own shading would be added too; the highlight is the same brightness on every face.
        var17.disableColor();
        boolean var18 = var10.getRenderType() != -1 && this.renderBlocks.renderBlockByRenderType(var10, var6, var7, var8);
        var17.draw();
        var17.setTranslation(0.0D, 0.0D, 0.0D);

        if (!var18)
        {
            var10.setBlockBoundsBasedOnState(var5, var6, var7, var8);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, BOX_STRENGTH);
            drawBox(var10.getSelectedBoundingBoxFromPool(var5, var6, var7, var8).expand(0.002D, 0.002D, 0.002D).getOffsetBoundingBox(-var11, -var13, -var15));
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }

        GL11.glPolygonOffset(0.0F, 0.0F);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** The six faces of box, wound like RenderBlocks' faces so only the ones facing the camera are drawn. */
    private static void drawBox(AxisAlignedBB box)
    {
        Tessellator var1 = Tessellator.instance;
        var1.startDrawingQuads();
        var1.addVertex(box.minX, box.minY, box.maxZ);
        var1.addVertex(box.minX, box.minY, box.minZ);
        var1.addVertex(box.maxX, box.minY, box.minZ);
        var1.addVertex(box.maxX, box.minY, box.maxZ);
        var1.addVertex(box.maxX, box.maxY, box.maxZ);
        var1.addVertex(box.maxX, box.maxY, box.minZ);
        var1.addVertex(box.minX, box.maxY, box.minZ);
        var1.addVertex(box.minX, box.maxY, box.maxZ);
        var1.addVertex(box.minX, box.maxY, box.minZ);
        var1.addVertex(box.maxX, box.maxY, box.minZ);
        var1.addVertex(box.maxX, box.minY, box.minZ);
        var1.addVertex(box.minX, box.minY, box.minZ);
        var1.addVertex(box.minX, box.maxY, box.maxZ);
        var1.addVertex(box.minX, box.minY, box.maxZ);
        var1.addVertex(box.maxX, box.minY, box.maxZ);
        var1.addVertex(box.maxX, box.maxY, box.maxZ);
        var1.addVertex(box.minX, box.maxY, box.maxZ);
        var1.addVertex(box.minX, box.maxY, box.minZ);
        var1.addVertex(box.minX, box.minY, box.minZ);
        var1.addVertex(box.minX, box.minY, box.maxZ);
        var1.addVertex(box.maxX, box.minY, box.maxZ);
        var1.addVertex(box.maxX, box.minY, box.minZ);
        var1.addVertex(box.maxX, box.maxY, box.minZ);
        var1.addVertex(box.maxX, box.maxY, box.maxZ);
        var1.draw();
    }
}
