package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * mc.thePlayer in a local game (Integrated Server Off, see KirLocalGame). It lives in the server's world,
 * so the game logic that ran for the integrated server's copy of the player (damage, healing, item drops,
 * portals, all statistics) runs for this one instead. EntityClientPlayerMP turned those off for the
 * client copy; they are called past its overrides here.
 */
public class KirLocalPlayer extends EntityClientPlayerMP
{
    /** Set after the End's exit portal; the next respawn keeps everything (EntityPlayerMP.playerConqueredTheEnd). */
    boolean conqueredTheEnd;

    /** The game type saved with the player ("playerGameType", as EntityPlayerMP saves it), or null. */
    EnumGameType savedGameType;

    public KirLocalPlayer(Minecraft mc, World world, Session session, NetClientHandler handler)
    {
        super(mc, world, session, handler);
    }

    public boolean attackEntityFrom(DamageSource par1DamageSource, int par2)
    {
        return ((Boolean)KirLocalGame.invoke(KirLocalGame.superAttackEntityFrom, new Object[] {this, par1DamageSource, Integer.valueOf(par2)})).booleanValue();
    }

    public void heal(int par1)
    {
        KirLocalGame.invoke(KirLocalGame.superHeal, new Object[] {this, Integer.valueOf(par1)});
    }

    protected void damageEntity(DamageSource par1DamageSource, int par2)
    {
        KirLocalGame.invoke(KirLocalGame.superDamageEntity, new Object[] {this, par1DamageSource, Integer.valueOf(par2)});
    }

    public EntityItem dropOneItem()
    {
        return this.dropPlayerItemWithRandomChoice(this.inventory.decrStackSize(this.inventory.currentItem, 1), false);
    }

    /** EntityPlayer's, which spawns the item (or, under Forge, holds it back while a toss is captured). */
    protected void joinEntityItemWithWorld(EntityItem par1EntityItem)
    {
        KirLocalGame.invoke(KirLocalGame.superJoinEntityItem, new Object[] {this, par1EntityItem});
    }

    public void sendChatMessage(String par1Str)
    {
        KirLocalGame.handleChat(this.mc, this, par1Str);
    }

    public void respawnPlayer()
    {
        KirLocalGame.respawn(this.mc);
    }

    /** EntityPlayerSP's: the cursor's item stays held, so the container drops it as on a server. */
    public void closeScreen()
    {
        this.craftingInventory = this.inventorySlots;
        this.mc.displayGuiScreen((GuiScreen)null);
    }

    /** Every statistic counts here, not just the ones the client counts itself. */
    public void addStat(StatBase par1StatBase, int par2)
    {
        if (par1StatBase != null)
        {
            if (par1StatBase.isIndependent)
            {
                super.addStat(par1StatBase, par2);
            }
            else
            {
                super.incrementStat(par1StatBase, par2);
            }
        }
    }

    public void writeEntityToNBT(NBTTagCompound par1NBTTagCompound)
    {
        super.writeEntityToNBT(par1NBTTagCompound);

        if (this.mc.playerController instanceof KirLocalController)
        {
            par1NBTTagCompound.setInteger("playerGameType", ((KirLocalController)this.mc.playerController).getGameType().getID());
        }
    }

    public void readEntityFromNBT(NBTTagCompound par1NBTTagCompound)
    {
        super.readEntityFromNBT(par1NBTTagCompound);

        if (par1NBTTagCompound.hasKey("playerGameType"))
        {
            EnumGameType var2 = EnumGameType.getByID(par1NBTTagCompound.getInteger("playerGameType"));
            this.savedGameType = var2 == EnumGameType.NOT_SET ? null : var2;
        }
    }

    public void sendMotionUpdates() {}

    public void sendPlayerAbilities() {}

    public void onUpdate()
    {
        super.onUpdate();

        if (!this.isDead && this.worldObj instanceof WorldServer)
        {
            // Item Magnet (Kirmod Extras), say.
            mod_Kirmod.tickServerPlayer(this);
        }
    }

    /** EntityPlayerSP counts the time in a portal; going through is EntityPlayerMP's part. */
    public void onLivingUpdate()
    {
        boolean var1 = this.inPortal;
        super.onLivingUpdate();

        if (var1 && this.timeInPortal >= 1.0F && this.ridingEntity == null && this.timeUntilPortal == 0 && this.worldObj instanceof WorldServer)
        {
            this.timeUntilPortal = 10;
            this.triggerAchievement(AchievementList.portal);
            KirLocalGame.requestDimension(this.dimension == -1 ? 0 : -1);
        }
    }

    /** EntityPlayerMP.travelToTheEnd: into the End, or out of it through the exit portal. */
    public void travelToTheEnd(int par1)
    {
        if (this.dimension == 1 && par1 == 1)
        {
            if (!this.conqueredTheEnd)
            {
                this.triggerAchievement(AchievementList.theEnd2);
                this.conqueredTheEnd = true;
                KirLocalGame.requestCredits();
            }
        }
        else if (this.dimension != par1)
        {
            this.triggerAchievement(AchievementList.theEnd);
            KirLocalGame.requestDimension(par1);
        }
    }
}
