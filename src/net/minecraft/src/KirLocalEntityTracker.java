package net.minecraft.src;

/**
 * The entity tracker of a local world (Integrated Server Off, see KirLocalGame). A server's tracker sends
 * entities to the players watching them; the local player sees the world's own entities, so there is
 * nothing to send. (The vanilla one would also treat the local player as a network player and crash.)
 */
public class KirLocalEntityTracker extends EntityTracker
{
    public KirLocalEntityTracker(WorldServer world)
    {
        super(world);
    }

    public void addEntityToTracker(Entity par1Entity) {}

    public void addEntityToTracker(Entity par1Entity, int par2, int par3) {}

    public void addEntityToTracker(Entity par1Entity, int par2, int par3, boolean par4) {}

    public void removeEntityFromAllTrackingPlayers(Entity par1Entity) {}

    public void processOutstandingEntries() {}

    public void sendPacketToAllPlayersTrackingEntity(Entity par1Entity, Packet par2Packet) {}

    public void sendPacketToAllAssociatedPlayers(Entity par1Entity, Packet par2Packet) {}

    public void removeAllTrackingPlayers(EntityPlayerMP par1EntityPlayerMP) {}
}
