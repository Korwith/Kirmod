package net.minecraft.src;

import net.minecraft.client.Minecraft;

/**
 * mc.theWorld in a local game (Integrated Server Off, see KirLocalGame). It is a WorldClient because the
 * client's code expects one, but it holds no world of its own: it shares the server world's chunk
 * provider, entity and tile entity lists, world info, map storage and listeners. The renderer therefore
 * draws the real world, and a block the server world changes is redrawn through the same listener list.
 * Anything client code might change through it is passed to the server world, and ticking it ticks the
 * local server.
 */
public class KirLocalWorld extends WorldClient
{
    final KirLocalServer server;
    final WorldServer target;
    final KirLocalNetHandler netHandler;
    private final Minecraft mc = Minecraft.getMinecraft();

    public KirLocalWorld(KirLocalNetHandler handler, KirLocalServer server, WorldServer target)
    {
        super(handler, new WorldSettings(target.getWorldInfo()), target.provider.worldType, target.difficultySetting, Minecraft.getMinecraft().mcProfiler);
        this.server = server;
        this.target = target;
        this.netHandler = handler;
        this.isRemote = true;
        this.chunkProvider = target.chunkProvider;
        this.worldInfo = target.getWorldInfo();
        this.mapStorage = target.mapStorage;
        this.loadedEntityList = target.loadedEntityList;
        this.unloadedEntityList = target.unloadedEntityList;
        this.loadedTileEntityList = target.loadedTileEntityList;
        this.playerEntities = target.playerEntities;
        this.weatherEffects = target.weatherEffects;
        this.worldAccesses = target.worldAccesses;
        handler.setWorld(this);
        this.copyState();
    }

    public WorldServer getTarget()
    {
        return this.target;
    }

    /** The bits of state the renderer reads from fields: weather, sky light, difficulty. */
    private void copyState()
    {
        this.prevRainingStrength = this.target.prevRainingStrength;
        this.rainingStrength = this.target.rainingStrength;
        this.prevThunderingStrength = this.target.prevThunderingStrength;
        this.thunderingStrength = this.target.thunderingStrength;
        this.skylightSubtracted = this.target.skylightSubtracted;
        this.difficultySetting = this.target.difficultySetting;
        this.lastLightningBolt = this.target.lastLightningBolt;

        for (int var1 = 0; var1 < this.weatherEffects.size(); ++var1)
        {
            Entity var2 = (Entity)this.weatherEffects.get(var1);

            if (var2 instanceof EntityLightningBolt && var2.ticksExisted <= 1)
            {
                this.lightningFlash = 2;
            }
        }
    }

    /** One game tick: the local server ticks every dimension (and so the player). */
    public void tick()
    {
        if (this.server.isStopped())
        {
            return;
        }

        this.server.tickLocal();
        this.copyState();
    }

    /** Done by the server's tick. */
    public void updateEntities() {}

    public boolean spawnEntityInWorld(Entity par1Entity)
    {
        if (par1Entity.worldObj != this.target)
        {
            par1Entity.setWorld(this.target);
        }

        if (par1Entity instanceof EntityPlayer && this.target.loadedEntityList.contains(par1Entity))
        {
            return true;
        }

        return this.target.spawnEntityInWorld(par1Entity);
    }

    public void setEntityDead(Entity par1Entity)
    {
        this.target.setEntityDead(par1Entity);
    }

    public void removeEntity(Entity par1Entity)
    {
        this.target.removeEntity(par1Entity);
    }

    public Entity getEntityByID(int par1)
    {
        return this.target.getEntityByID(par1);
    }

    public Entity removeEntityFromWorld(int par1)
    {
        return null;
    }

    public void addEntityToWorld(int par1, Entity par2Entity) {}

    public void joinEntityInSurroundings(Entity par1Entity)
    {
        this.target.joinEntityInSurroundings(par1Entity);
    }

    /** WorldClient's would clear the shared entity list. */
    public void removeAllEntities() {}

    public void doPreChunk(int par1, int par2, boolean par3) {}

    public void invalidateBlockReceiveRegion(int par1, int par2, int par3, int par4, int par5, int par6) {}

    /** Leaving saves through KirLocalServer.setServerStopping instead. */
    public void sendQuittingDisconnectingPacket() {}

    public void setWorldTime(long par1)
    {
        this.target.setWorldTime(par1);
    }

    public String getDebugLoadedEntities()
    {
        return "All: " + this.loadedEntityList.size();
    }

    /* Block changes go to the server world, which notifies the renderer through the shared listeners. */

    public boolean setBlockAndMetadata(int par1, int par2, int par3, int par4, int par5)
    {
        return this.target.setBlockAndMetadata(par1, par2, par3, par4, par5);
    }

    public boolean setBlockAndMetadataWithUpdate(int par1, int par2, int par3, int par4, int par5, boolean par6)
    {
        return this.target.setBlockAndMetadataWithUpdate(par1, par2, par3, par4, par5, par6);
    }

    public boolean setBlock(int par1, int par2, int par3, int par4)
    {
        return this.target.setBlock(par1, par2, par3, par4);
    }

    public void setBlockMetadataWithNotify(int par1, int par2, int par3, int par4)
    {
        this.target.setBlockMetadataWithNotify(par1, par2, par3, par4);
    }

    public boolean setBlockMetadata(int par1, int par2, int par3, int par4)
    {
        return this.target.setBlockMetadata(par1, par2, par3, par4);
    }

    public boolean setBlockWithNotify(int par1, int par2, int par3, int par4)
    {
        return this.target.setBlockWithNotify(par1, par2, par3, par4);
    }

    public boolean setBlockAndMetadataWithNotify(int par1, int par2, int par3, int par4, int par5)
    {
        return this.target.setBlockAndMetadataWithNotify(par1, par2, par3, par4, par5);
    }

    public boolean setBlockAndMetadataAndInvalidate(int par1, int par2, int par3, int par4, int par5)
    {
        return this.target.setBlockAndMetadataWithNotify(par1, par2, par3, par4, par5);
    }

    public void notifyBlockChange(int par1, int par2, int par3, int par4)
    {
        this.target.notifyBlockChange(par1, par2, par3, par4);
    }

    public void notifyBlocksOfNeighborChange(int par1, int par2, int par3, int par4)
    {
        this.target.notifyBlocksOfNeighborChange(par1, par2, par3, par4);
    }

    public void scheduleBlockUpdate(int par1, int par2, int par3, int par4, int par5)
    {
        this.target.scheduleBlockUpdate(par1, par2, par3, par4, par5);
    }

    public void addBlockEvent(int par1, int par2, int par3, int par4, int par5, int par6)
    {
        this.target.addBlockEvent(par1, par2, par3, par4, par5, par6);
    }

    public TileEntity getBlockTileEntity(int par1, int par2, int par3)
    {
        return this.target.getBlockTileEntity(par1, par2, par3);
    }

    public void setBlockTileEntity(int par1, int par2, int par3, TileEntity par4TileEntity)
    {
        this.target.setBlockTileEntity(par1, par2, par3, par4TileEntity);
    }

    public void removeBlockTileEntity(int par1, int par2, int par3)
    {
        this.target.removeBlockTileEntity(par1, par2, par3);
    }

    public Explosion newExplosion(Entity par1Entity, double par2, double par4, double par6, float par8, boolean par9)
    {
        return this.target.newExplosion(par1Entity, par2, par4, par6, par8, par9);
    }

    public boolean extinguishFire(EntityPlayer par1EntityPlayer, int par2, int par3, int par4, int par5)
    {
        return this.target.extinguishFire(par1EntityPlayer, par2, par3, par4, par5);
    }
}
