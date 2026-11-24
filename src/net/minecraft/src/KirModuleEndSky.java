package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * End Overworld Sky: "On" lights the End the way the overworld is lit: a blue sky with sun, moon, stars,
 * clouds, sunsets, day and night, and weather.
 *
 * Only the client's copy of the End changes. The client world has its own WorldProvider, so it gets a
 * Provider in its place, which answers the sky questions as the overworld's provider does. The server's
 * End (mobs, spawning, what is saved) never sees any of this, so this works in multiplayer too.
 *
 * The time of day is only ever read: sun and moon come from the world time the server already sends the
 * client in every dimension, which in the End is the overworld's own time. Nothing here writes it.
 *
 * The server's End has no sky light, and the zeros it sends with each chunk have to be replaced by sky
 * light worked out on the client, as the overworld's server would do it. That's what SkyChunk is for; its
 * ChunkProvider creates them. Sky color comes from the biome, and the End's biome has a black sky, so
 * SkyChunk reports Plains as its biome (where rain can fall).
 *
 * The server tells the client when it rains in the overworld. WeatherInfo lets End Weather hide that from
 * the world's weather code without losing it, so turning End Weather back on brings the current weather.
 * It is also hidden from the client's entities while they tick: to the server nothing in the End ever gets
 * rained on, and an enderman that thought so would keep teleporting on the client.
 *
 * The End's own sky is chosen by provider.worldType == 1, so Provider has 0 there: anything else on the
 * client that reads that field sees the overworld too. It always has the real dimension in
 * thePlayer.dimension.
 */
public class KirModuleEndSky extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    private static final Field fieldProvider = KirUtil.findField(World.class, "provider", "w");
    private static final Field fieldWorldInfo = KirUtil.findField(World.class, "worldInfo", "A");
    private static final Field fieldWorldChunkProvider = KirUtil.findField(World.class, "chunkProvider", "y");
    private static final Field fieldClientChunkProvider = KirUtil.findField(WorldClient.class, "clientChunkProvider", "b");
    private static final Field fieldChunkMapping = KirUtil.findField(ChunkProviderClient.class, "chunkMapping", "b");

    /** How far around the player, in chunks, to look for chunks that are already loaded when it is switched on. */
    private static final int SCAN_RADIUS = 32;

    /** Time per frame for joining up the sky light of neighboring chunks, in nanoseconds. */
    private static final long LIGHT_BUDGET = 3000000L;

    /** Set if swapping failed once, so it isn't retried every frame. */
    private boolean failed = false;

    /** The world's rain strength while hideRain has it at 0. */
    private boolean rainHidden = false;
    private float savedPrevRain;
    private float savedRain;

    public KirModuleEndSky()
    {
        super("endSky", "End Overworld Sky", "Overworld Sky", 0, "endsky");
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public String getCategory()
    {
        return CATEGORY_END;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Lights the End like the overworld.",
            describeValue("Off", "the End's black sky and flat, dim light."),
            describeValue("On", "blue sky, sun, moon, stars, clouds, sunsets and sky light, following the overworld's time of day. Rain too, if Weather is on."),
            note("Only the look changes: the End's mobs and terrain, and the world's time, are the same either way. Works on servers too.")
        };
    }

    public boolean isAvailable()
    {
        return !this.failed && fieldProvider != null && fieldWorldInfo != null && fieldWorldChunkProvider != null && fieldClientChunkProvider != null && fieldChunkMapping != null;
    }

    public void update(Minecraft mc)
    {
        WorldClient var2 = mc.theWorld;

        if (var2 == null)
        {
            return;
        }

        boolean var3 = this.getValue() == 1 && this.isAvailable();

        if (var2.provider instanceof Provider)
        {
            if (var3)
            {
                KirWorldHook.installAll(mc);
                getChunkProvider(var2).updateLight();

                // The server also sets the strength itself when the weather changes.
                if (mod_Kirmod.END_WEATHER.getValue() == 0 && var2.getRainStrength(1.0F) > 0.0F)
                {
                    var2.setRainStrength(0.0F);
                }
            }
            else
            {
                this.setProvider(var2, ((Provider)var2.provider).original);
            }
        }
        else if (var3 && var2.provider.getClass() == WorldProviderEnd.class)
        {
            this.install(mc, var2);
        }
    }

    /** Called by KirWorldHook before the client world's entities tick. */
    void hideRain(World world)
    {
        if (world.provider instanceof Provider)
        {
            this.savedPrevRain = world.prevRainingStrength;
            this.savedRain = world.rainingStrength;
            world.prevRainingStrength = 0.0F;
            world.rainingStrength = 0.0F;
            this.rainHidden = true;
        }
    }

    /** Called by KirWorldHook after the client world's entities have ticked. */
    void showRain(World world)
    {
        if (this.rainHidden)
        {
            world.prevRainingStrength = this.savedPrevRain;
            world.rainingStrength = this.savedRain;
            this.rainHidden = false;
        }
    }

    private void install(Minecraft mc, WorldClient world)
    {
        try
        {
            Object var3 = fieldClientChunkProvider.get(world);
            ChunkProvider var4;

            if (var3 instanceof ChunkProvider)
            {
                // Switched on before in this world: the light was left alone while it was off.
                var4 = (ChunkProvider)var3;
                var4.queueAllForLight();
            }
            else if (var3.getClass() == ChunkProviderClient.class)
            {
                var4 = new ChunkProvider(world, (ChunkProviderClient)var3);
                fieldClientChunkProvider.set(world, var4);
                fieldWorldChunkProvider.set(world, var4);
            }
            else
            {
                throw new IllegalStateException("the chunk cache is already changed by another mod (" + var3.getClass().getName() + ")");
            }

            if (!(world.getWorldInfo() instanceof WeatherInfo))
            {
                fieldWorldInfo.set(world, new WeatherInfo(world.getWorldInfo()));
            }

            this.setProvider(world, new Provider(world.provider));

            if (mc.thePlayer != null)
            {
                var4.convertChunksAround(MathHelper.floor_double(mc.thePlayer.posX) >> 4, MathHelper.floor_double(mc.thePlayer.posZ) >> 4);
            }

            var4.updateLight();
        }
        catch (Exception var5)
        {
            this.failed = true;
            KirUtil.log("Could not change the End's sky", var5);
        }
    }

    private void setProvider(WorldClient world, WorldProvider provider)
    {
        try
        {
            fieldProvider.set(world, provider);
            world.calculateInitialSkylight();
            KirUtil.reloadRenderers();
        }
        catch (IllegalAccessException var4)
        {
            this.failed = true;
            KirUtil.log("Could not change the End's sky", var4);
        }
    }

    private static ChunkProvider getChunkProvider(WorldClient world)
    {
        try
        {
            return (ChunkProvider)fieldClientChunkProvider.get(world);
        }
        catch (IllegalAccessException var2)
        {
            throw new IllegalStateException(var2);
        }
    }

    /** The client End's provider while this is on; the sky questions are answered by an overworld provider. */
    public static class Provider extends WorldProviderEnd
    {
        final WorldProvider original;
        private final WorldProvider overworld = new WorldProviderSurface();

        Provider(WorldProvider original) throws IllegalAccessException
        {
            KirUtil.copyFields(original, this);
            this.original = original;
            this.worldType = 0;
            this.hasNoSky = false;
            this.worldChunkMgr = new WorldChunkManagerHell(BiomeGenBase.plains, BiomeGenBase.plains.temperature, BiomeGenBase.plains.rainfall);
            // Its own table: Fullbright changes the table of whichever provider is current, and puts it back.
            this.lightBrightnessTable = new float[16];
            this.generateLightBrightnessTable();
        }

        public float calculateCelestialAngle(long par1, float par3)
        {
            return this.overworld.calculateCelestialAngle(par1, par3);
        }

        public float[] calcSunriseSunsetColors(float par1, float par2)
        {
            return this.overworld.calcSunriseSunsetColors(par1, par2);
        }

        public Vec3 getFogColor(float par1, float par2)
        {
            return this.overworld.getFogColor(par1, par2);
        }

        public boolean isSkyColored()
        {
            return this.overworld.isSkyColored();
        }

        public boolean isSurfaceWorld()
        {
            return this.overworld.isSurfaceWorld();
        }

        public float getCloudHeight()
        {
            return this.overworld.getCloudHeight();
        }

        public boolean doesXZShowFog(int par1, int par2)
        {
            return this.overworld.doesXZShowFog(par1, par2);
        }

        /** Would otherwise turn on with hasNoSky off: the void stays free of the overworld's bedrock particles and fog. */
        public boolean getWorldHasVoidParticles()
        {
            return false;
        }
    }

    /** The client End's WorldInfo (never saved, and not the server's), which keeps the weather to itself with End Weather off. */
    public static class WeatherInfo extends WorldInfo
    {
        WeatherInfo(WorldInfo original)
        {
            super(original);
        }

        public boolean isRaining()
        {
            return super.isRaining() && mod_Kirmod.END_WEATHER.getValue() == 1;
        }

        public boolean isThundering()
        {
            return super.isThundering() && mod_Kirmod.END_WEATHER.getValue() == 1;
        }
    }

    /** The client's chunk cache in an End that has had this on; hands out SkyChunks. */
    public static class ChunkProvider extends ChunkProviderClient
    {
        private final World world;
        private final LongHashMap mapping;

        /** Every SkyChunk in the cache. */
        private final Set chunks = new HashSet();

        /** Chunks that need their sky light worked out. */
        private final Set unlit = new LinkedHashSet();

        /** Chunks whose sky light still has to be joined up with their neighbors'. */
        private final Set unjoined = new LinkedHashSet();

        ChunkProvider(World world, ChunkProviderClient original) throws IllegalAccessException
        {
            super(world);
            KirUtil.copyFields(original, this);
            this.world = world;
            this.mapping = (LongHashMap)fieldChunkMapping.get(this);
        }

        public Chunk loadChunk(int par1, int par2)
        {
            this.forget(super.provideChunk(par1, par2));
            SkyChunk var3 = new SkyChunk(this.world, par1, par2, this);
            this.mapping.add(ChunkCoordIntPair.chunkXZ2Int(par1, par2), var3);
            var3.isChunkLoaded = true;
            this.chunks.add(var3);
            return var3;
        }

        public void unloadChunk(int par1, int par2)
        {
            this.forget(this.provideChunk(par1, par2));
            super.unloadChunk(par1, par2);
        }

        /** A plain Chunk here was loaded before this provider took over; it becomes a SkyChunk on first use. */
        public Chunk provideChunk(int par1, int par2)
        {
            Chunk var3 = super.provideChunk(par1, par2);
            return var3.getClass() == Chunk.class ? this.convert(var3) : var3;
        }

        private Chunk convert(Chunk chunk)
        {
            SkyChunk var2 = new SkyChunk(this.world, chunk.xPosition, chunk.zPosition, this);

            try
            {
                KirUtil.copyFields(chunk, var2);
            }
            catch (IllegalAccessException var4)
            {
                KirUtil.log("Could not convert a chunk for End Overworld Sky", var4);
                return chunk;
            }

            this.mapping.add(ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition), var2);
            this.chunks.add(var2);
            // Not right away: this can be in the middle of building a chunk's mesh.
            this.unlit.add(var2);
            return var2;
        }

        private void forget(Chunk chunk)
        {
            this.chunks.remove(chunk);
            this.unlit.remove(chunk);
            this.unjoined.remove(chunk);
        }

        void convertChunksAround(int chunkX, int chunkZ)
        {
            for (int var3 = chunkX - SCAN_RADIUS; var3 <= chunkX + SCAN_RADIUS; ++var3)
            {
                for (int var4 = chunkZ - SCAN_RADIUS; var4 <= chunkZ + SCAN_RADIUS; ++var4)
                {
                    this.provideChunk(var3, var4);
                }
            }
        }

        void queueAllForLight()
        {
            this.unlit.addAll(this.chunks);
        }

        void queueForJoining(SkyChunk chunk)
        {
            this.unjoined.add(chunk);
        }

        /**
         * Lights the chunks waiting for it, then spends a bit of time on what WorldServer does for the
         * overworld's chunks every tick and the client normally has no need of: Chunk.updateSkylight.
         */
        void updateLight()
        {
            Iterator var1 = new ArrayList(this.unlit).iterator();
            this.unlit.clear();

            while (var1.hasNext())
            {
                ((SkyChunk)var1.next()).relight();
            }

            long var2 = System.nanoTime() + LIGHT_BUDGET;
            var1 = this.unjoined.iterator();

            while (var1.hasNext() && System.nanoTime() < var2)
            {
                Chunk var4 = (Chunk)var1.next();
                var1.remove();
                var4.updateSkylight();
            }
        }
    }

    /** A chunk of the client's End with sky light of its own. */
    public static class SkyChunk extends Chunk
    {
        private final ChunkProvider chunkProvider;

        SkyChunk(World world, int chunkX, int chunkZ, ChunkProvider chunkProvider)
        {
            super(world, chunkX, chunkZ);
            this.chunkProvider = chunkProvider;
        }

        /** The packet's sky light, always zero from the End's server, has just been copied in: replace it. */
        public void fillChunk(byte[] par1ArrayOfByte, int par2, int par3, boolean par4)
        {
            super.fillChunk(par1ArrayOfByte, par2, par3, par4);

            if (!this.worldObj.provider.hasNoSky)
            {
                this.relight();
            }
        }

        void relight()
        {
            this.generateSkylightMap();
            this.worldObj.markBlocksDirty(this.xPosition << 4, 0, this.zPosition << 4, (this.xPosition << 4) + 15, 256, (this.zPosition << 4) + 15);
            this.chunkProvider.queueForJoining(this);
        }

        /** While on, the provider's WorldChunkManager gives Plains; the chunk's own biome bytes stay as they are. */
        public BiomeGenBase getBiomeGenForWorldCoords(int par1, int par2, WorldChunkManager par3WorldChunkManager)
        {
            return this.worldObj.provider.hasNoSky ? super.getBiomeGenForWorldCoords(par1, par2, par3WorldChunkManager) : par3WorldChunkManager.getBiomeGenAt((this.xPosition << 4) + par1, (this.zPosition << 4) + par2);
        }
    }
}
