package net.minecraft.src;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;

/**
 * mc.playerController in a local game (Integrated Server Off, see KirLocalGame), like pre-1.3's
 * PlayerControllerSP. Breaking blocks, using items and clicking blocks act on the player's own world, with
 * the server's rules (ItemInWorldManager): drops, tool wear, creative mode. Everything else is
 * PlayerControllerMP's, with the world it is handed swapped for the player's (the client passes
 * mc.theWorld, which only mirrors it). It extends Kirmod's KirPlayerController, so Sneak Place, Painting
 * Picker and Tile Suppressor's message work too.
 *
 * The digging state stays in PlayerControllerMP's own fields, which Mining Delay reads.
 */
public class KirLocalController extends KirInteraction.KirPlayerController
{
    private static final Field fieldX = KirUtil.findField(PlayerControllerMP.class, "currentBlockX", "c");
    private static final Field fieldY = KirUtil.findField(PlayerControllerMP.class, "currentBlockY", "d");
    private static final Field fieldZ = KirUtil.findField(PlayerControllerMP.class, "currentblockZ", "e");
    private static final Field fieldDamage = KirUtil.findField(PlayerControllerMP.class, "curBlockDamageMP", "f");
    private static final Field fieldPrevDamage = KirUtil.findField(PlayerControllerMP.class, "prevBlockDamageMP", "g");
    private static final Field fieldStepSound = KirUtil.findField(PlayerControllerMP.class, "stepSoundTickCounter", "h");
    private static final Field fieldHitDelay = KirUtil.findField(PlayerControllerMP.class, "blockHitDelay", "i");
    private static final Field fieldHitting = KirUtil.findField(PlayerControllerMP.class, "isHittingBlock", "j");
    private static final Field fieldGameType = KirUtil.findField(PlayerControllerMP.class, "currentGameType", "k");

    private final Minecraft mc;

    /** For mc.loadWorld's player: the saved player data, or the player being respawned. */
    NBTTagCompound spawnData;
    EntityPlayer respawnFrom;
    boolean keepEverything;

    public KirLocalController(Minecraft mc, NetClientHandler handler)
    {
        super(mc, handler);
        this.mc = mc;
    }

    static boolean isAvailable()
    {
        return fieldX != null && fieldY != null && fieldZ != null && fieldDamage != null && fieldPrevDamage != null && fieldStepSound != null && fieldHitDelay != null && fieldHitting != null && fieldGameType != null;
    }

    public EntityClientPlayerMP func_78754_a(World par1World)
    {
        return KirLocalGame.createPlayer(this.mc, this, par1World, this.spawnData, this.respawnFrom, this.keepEverything);
    }

    /** Sets the game type before there is a player (setGameType also configures mc.thePlayer). */
    void setGameTypeQuietly(EnumGameType type)
    {
        this.set(fieldGameType, type);
    }

    EnumGameType getGameType()
    {
        return (EnumGameType)this.get(fieldGameType);
    }

    boolean isAdventure()
    {
        return this.getGameType().isAdventure();
    }

    private EntityPlayer player()
    {
        return this.mc.thePlayer;
    }

    private World world()
    {
        return this.mc.thePlayer.worldObj;
    }

    /* ------------------------------------------------------------------ Breaking blocks */

    public void clickBlock(int par1, int par2, int par3, int par4)
    {
        EnumGameType var5 = this.getGameType();

        if (var5.isAdventure())
        {
            return;
        }

        World var6 = this.world();

        if (var5.isCreative())
        {
            this.destroyInCreative(par1, par2, par3, par4);
            this.setInt(fieldHitDelay, 5);
        }
        else if (!this.getBool(fieldHitting) || par1 != this.getInt(fieldX) || par2 != this.getInt(fieldY) || par3 != this.getInt(fieldZ))
        {
            var6.extinguishFire(this.player(), par1, par2, par3, par4);
            int var7 = var6.getBlockId(par1, par2, par3);
            float var8 = 1.0F;

            if (var7 > 0 && this.getFloat(fieldDamage) == 0.0F)
            {
                Block.blocksList[var7].onBlockClicked(var6, par1, par2, par3, this.player());
            }

            if (var7 > 0)
            {
                var8 = Block.blocksList[var7].getPlayerRelativeBlockHardness(this.player(), var6, par1, par2, par3);
            }

            if (var7 > 0 && var8 >= 1.0F)
            {
                this.onPlayerDestroyBlock(par1, par2, par3, par4);
            }
            else
            {
                this.setBool(fieldHitting, true);
                this.setInt(fieldX, par1);
                this.setInt(fieldY, par2);
                this.setInt(fieldZ, par3);
                this.setFloat(fieldDamage, 0.0F);
                this.setFloat(fieldPrevDamage, 0.0F);
                this.setFloat(fieldStepSound, 0.0F);
                var6.destroyBlockInWorldPartially(this.player().entityId, par1, par2, par3, -1);
            }
        }
    }

    public void resetBlockRemoving()
    {
        this.setBool(fieldHitting, false);
        this.setFloat(fieldDamage, 0.0F);

        if (this.mc.thePlayer != null)
        {
            this.world().destroyBlockInWorldPartially(this.player().entityId, this.getInt(fieldX), this.getInt(fieldY), this.getInt(fieldZ), -1);
        }
    }

    public void onPlayerDamageBlock(int par1, int par2, int par3, int par4)
    {
        int var5 = this.getInt(fieldHitDelay);

        if (var5 > 0)
        {
            this.setInt(fieldHitDelay, var5 - 1);
            return;
        }

        if (this.getGameType().isCreative())
        {
            this.setInt(fieldHitDelay, 5);
            this.destroyInCreative(par1, par2, par3, par4);
            return;
        }

        if (par1 != this.getInt(fieldX) || par2 != this.getInt(fieldY) || par3 != this.getInt(fieldZ))
        {
            this.clickBlock(par1, par2, par3, par4);
            return;
        }

        World var6 = this.world();
        int var7 = var6.getBlockId(par1, par2, par3);

        if (var7 == 0)
        {
            this.setBool(fieldHitting, false);
            return;
        }

        Block var8 = Block.blocksList[var7];
        float var9 = this.getFloat(fieldDamage) + var8.getPlayerRelativeBlockHardness(this.player(), var6, par1, par2, par3);
        float var10 = this.getFloat(fieldStepSound);

        if (var10 % 4.0F == 0.0F)
        {
            this.mc.sndManager.playSound(var8.stepSound.getStepSound(), (float)par1 + 0.5F, (float)par2 + 0.5F, (float)par3 + 0.5F, (var8.stepSound.getVolume() + 1.0F) / 8.0F, var8.stepSound.getPitch() * 0.5F);
        }

        this.setFloat(fieldStepSound, var10 + 1.0F);

        if (var9 >= 1.0F)
        {
            this.setBool(fieldHitting, false);
            this.onPlayerDestroyBlock(par1, par2, par3, par4);
            var9 = 0.0F;
            this.setFloat(fieldPrevDamage, 0.0F);
            this.setFloat(fieldStepSound, 0.0F);
            this.setInt(fieldHitDelay, 5);
        }

        this.setFloat(fieldDamage, var9);
        var6.destroyBlockInWorldPartially(this.player().entityId, this.getInt(fieldX), this.getInt(fieldY), this.getInt(fieldZ), (int)(var9 * 10.0F) - 1);
    }

    private void destroyInCreative(int x, int y, int z, int side)
    {
        if (!this.world().extinguishFire(this.player(), x, y, z, side))
        {
            this.onPlayerDestroyBlock(x, y, z, side);
        }
    }

    /** ItemInWorldManager.tryHarvestBlock: the block breaks, drops what it drops and wears the tool. */
    public boolean onPlayerDestroyBlock(int par1, int par2, int par3, int par4)
    {
        EnumGameType var5 = this.getGameType();

        if (var5.isAdventure())
        {
            return false;
        }

        World var6 = this.world();
        EntityPlayer var7 = this.player();
        int var8 = var6.getBlockId(par1, par2, par3);
        int var9 = var6.getBlockMetadata(par1, par2, par3);
        Block var10 = Block.blocksList[var8];

        if (var10 == null)
        {
            return false;
        }

        var6.playAuxSFXAtEntity(var7, 2001, par1, par2, par3, var8 + (var9 << 12));
        var10.onBlockHarvested(var6, par1, par2, par3, var9, var7);
        boolean var11 = var6.setBlockWithNotify(par1, par2, par3, 0);

        if (var11)
        {
            var10.onBlockDestroyedByPlayer(var6, par1, par2, par3, var9);
        }

        if (!var5.isCreative())
        {
            ItemStack var12 = var7.getCurrentEquippedItem();
            boolean var13 = var7.canHarvestBlock(var10);

            if (var12 != null)
            {
                var12.func_77941_a(var6, var8, par1, par2, par3, var7);

                if (var12.stackSize == 0)
                {
                    var7.destroyCurrentEquippedItem();
                }
            }

            if (var11 && var13)
            {
                var10.harvestBlock(var6, var7, par1, par2, par3, var9);
            }
        }

        return var11;
    }

    /* ------------------------------------------------------------------ Using items */

    public boolean onPlayerRightClick(EntityPlayer par1EntityPlayer, World par2World, ItemStack par3ItemStack, int par4, int par5, int par6, int par7, Vec3 par8Vec3)
    {
        return super.onPlayerRightClick(par1EntityPlayer, par1EntityPlayer.worldObj, par3ItemStack, par4, par5, par6, par7, par8Vec3);
    }

    /** ItemInWorldManager.tryUseItem: creative mode doesn't use the item up. */
    public boolean sendUseItem(EntityPlayer par1EntityPlayer, World par2World, ItemStack par3ItemStack)
    {
        int var4 = par3ItemStack.stackSize;
        int var5 = par3ItemStack.getItemDamage();
        ItemStack var6 = par3ItemStack.useItemRightClick(par1EntityPlayer.worldObj, par1EntityPlayer);

        if (var6 == par3ItemStack && (var6 == null || var6.stackSize == var4) && (var6 == null || var6.getMaxItemUseDuration() <= 0))
        {
            return false;
        }

        par1EntityPlayer.inventory.mainInventory[par1EntityPlayer.inventory.currentItem] = var6;

        if (var6 != null && this.getGameType().isCreative())
        {
            var6.stackSize = var4;
            var6.setItemDamage(var5);
        }

        if (var6 == null || var6.stackSize == 0)
        {
            par1EntityPlayer.inventory.mainInventory[par1EntityPlayer.inventory.currentItem] = null;
        }

        return true;
    }

    public void sendEnchantPacket(int par1, int par2)
    {
        EntityPlayer var3 = this.player();
        Container var4 = var3.craftingInventory;

        if (var4 != null && var4.windowId == par1 && var4.enchantItem(var3, par2))
        {
            var4.updateCraftingResults();
        }
    }

    /* ------------------------------------------------------------------ Field access */

    private Object get(Field field)
    {
        try
        {
            return field.get(this);
        }
        catch (IllegalAccessException var3)
        {
            throw new RuntimeException(var3);
        }
    }

    private void set(Field field, Object value)
    {
        try
        {
            field.set(this, value);
        }
        catch (IllegalAccessException var4)
        {
            throw new RuntimeException(var4);
        }
    }

    private int getInt(Field field)
    {
        return ((Integer)this.get(field)).intValue();
    }

    private void setInt(Field field, int value)
    {
        this.set(field, Integer.valueOf(value));
    }

    private float getFloat(Field field)
    {
        return ((Float)this.get(field)).floatValue();
    }

    private void setFloat(Field field, float value)
    {
        this.set(field, Float.valueOf(value));
    }

    private boolean getBool(Field field)
    {
        return ((Boolean)this.get(field)).booleanValue();
    }

    private void setBool(Field field, boolean value)
    {
        this.set(field, Boolean.valueOf(value));
    }
}
