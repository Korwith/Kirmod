package net.minecraft.src;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Held Item Name (from InventoryManagerPlus): "On" shows the name of the item you switch to above the
 * hotbar for two seconds, fading out, like later versions. An item renamed with /rename shows its name
 * in italics.
 *
 * Drawn from Kirmod's in-game hook, after the HUD. InventoryManagerPlus did it in its own GuiIngame,
 * which replaced Forge's; if that GuiIngame is the one in the game (it has an ItemStack field, which
 * 1.3.2's doesn't), it already draws the name and this does nothing.
 */
public class KirModuleHeldItemName extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    /** How long the name shows, and how long of that it fades out, in ms (40 and 10 ticks). */
    private static final long SHOW_MS = 2000L;
    private static final long FADE_MS = 500L;

    private static Boolean otherModDrawsIt;

    private ItemStack shown;
    private long shownSince;

    public KirModuleHeldItemName()
    {
        super("heldItemName", "Held Item Name", "Held Item Name", 0, "helditemname");
    }

    public String getCategory()
    {
        return CATEGORY_VISUALS;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Shows the name of the item you switch to above the hotbar, like later versions.",
            describeValue("Off", "vanilla, no name."),
            describeValue("On", "the name shows for two seconds and fades. Items renamed with /rename show their new name in italics.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    /** The name an item shows: its /rename name (in italics) or its normal one. */
    static String getDisplayName(ItemStack stack)
    {
        String var1 = KirRename.getCustomName(stack);

        if (var1 != null)
        {
            // A color code resets the style, so the italics go after each one too.
            return "\u00a7o" + var1.replaceAll("(\u00a7[0-9a-fA-F])", "$1\u00a7o");
        }

        return (String)stack.getItemNameandInformation().get(0);
    }

    public void update(Minecraft mc)
    {
        if (this.getValue() != 1 || mc.thePlayer == null || mc.theWorld == null)
        {
            this.shown = null;
            return;
        }

        if (otherModDrawsIt == null)
        {
            otherModDrawsIt = Boolean.FALSE;
            Field[] var2 = GuiIngame.class.getDeclaredFields();

            for (int var3 = 0; var3 < var2.length; ++var3)
            {
                if (var2[var3].getType() == ItemStack.class)
                {
                    otherModDrawsIt = Boolean.TRUE;
                    KirUtil.log("GuiIngame already shows the held item's name (InventoryManagerPlus?); Held Item Name leaves it to it", (Throwable)null);
                }
            }
        }

        if (otherModDrawsIt.booleanValue())
        {
            return;
        }

        ItemStack var4 = mc.thePlayer.inventory.getCurrentItem();
        long var5 = System.currentTimeMillis();

        if (var4 != this.shown)
        {
            // A new stack of the same item (one used up, say) doesn't show the name again.
            boolean var7 = var4 != null && this.shown != null && var4.itemID == this.shown.itemID && ItemStack.func_77970_a(var4, this.shown) && (var4.isItemStackDamageable() || var4.getItemDamage() == this.shown.getItemDamage());

            if (!var7)
            {
                this.shownSince = var4 != null ? var5 : 0L;
            }

            this.shown = var4;
        }

        long var8 = this.shownSince + SHOW_MS - var5;

        if (this.shown == null || var8 <= 0L || mc.gameSettings.hideGUI || mc.currentScreen != null && !(mc.currentScreen instanceof GuiChat))
        {
            return;
        }

        int var10 = (int)Math.min(255L, var8 * 255L / FADE_MS);

        if (var10 < 8)
        {
            return;
        }

        ScaledResolution var11 = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        int var12 = var11.getScaledWidth();
        int var13 = var11.getScaledHeight();
        int var14 = 48;

        if (mc.playerController.shouldDrawHUD() && (mc.thePlayer.getTotalArmorValue() > 0 || mc.thePlayer.isInsideOfMaterial(Material.water) || mc.thePlayer.getAir() < 300))
        {
            var14 += 10;
        }

        String var15 = getDisplayName(this.shown);
        FontRenderer var16 = mc.fontRenderer;
        mc.entityRenderer.setupOverlayRendering();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        var16.drawStringWithShadow(var15, (var12 - var16.getStringWidth(var15)) / 2, var13 - var14 - 4, 0xFFFFFF | var10 << 24);
        GL11.glDisable(GL11.GL_BLEND);
    }
}
