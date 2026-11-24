package net.minecraft.src;

import net.minecraft.server.MinecraftServer;

/**
 * A world of a local game (Integrated Server Off, see KirLocalGame). The same as the integrated server's,
 * except for what it would have sent to the client: entity effects and explosions happen right here, and
 * whatever the local player rides is moved by the world, not by the player's packets.
 */
public class KirLocalWorldServer extends WorldServer
{
    private EntityTracker tracker;

    public KirLocalWorldServer(MinecraftServer server, ISaveHandler saveHandler, String name, int dimension, WorldSettings settings, Profiler profiler)
    {
        super(server, saveHandler, name, dimension, settings, profiler);
    }

    public EntityTracker getEntityTracker()
    {
        if (this.tracker == null)
        {
            this.tracker = new KirLocalEntityTracker(this);
        }

        return this.tracker;
    }

    public void setEntityState(Entity par1Entity, byte par2)
    {
        super.setEntityState(par1Entity, par2);
        KirLocalGame.playEntityState(par1Entity, par2);
    }

    public void updateEntityWithOptionalForce(Entity par1Entity, boolean par2)
    {
        if (!KirLocalGame.updateRiddenEntity(this, par1Entity, par2))
        {
            super.updateEntityWithOptionalForce(par1Entity, par2);
        }
    }

    public boolean spawnEntityInWorld(Entity par1Entity)
    {
        return super.spawnEntityInWorld(KirLocalGame.replaceSpawned(this, par1Entity));
    }

    /** World.newExplosion: with the particles, which the server leaves to its clients. */
    public Explosion newExplosion(Entity par1Entity, double par2, double par4, double par6, float par8, boolean par9)
    {
        Explosion var10 = new Explosion(this, par1Entity, par2, par4, par6, par8);
        var10.isFlaming = par9;
        var10.doExplosionA();
        var10.doExplosionB(true);
        return var10;
    }
}
