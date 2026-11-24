package net.minecraft.src;

/**
 * An ender pearl thrown by the local player (Integrated Server Off). Vanilla only teleports a thrower that
 * is a network player (EntityPlayerMP); this one teleports the local player the same way.
 */
public class KirLocalEnderPearl extends EntityEnderPearl
{
    public KirLocalEnderPearl(World world, EntityLiving thrower)
    {
        super(world, thrower);
    }

    protected void onImpact(MovingObjectPosition par1MovingObjectPosition)
    {
        super.onImpact(par1MovingObjectPosition);

        if (!this.worldObj.isRemote && this.thrower instanceof KirLocalPlayer && this.thrower.worldObj == this.worldObj && !this.thrower.isDead)
        {
            this.thrower.setPositionAndUpdate(this.posX, this.posY, this.posZ);
            this.thrower.fallDistance = 0.0F;
            this.thrower.attackEntityFrom(DamageSource.fall, 5);
        }
    }
}
