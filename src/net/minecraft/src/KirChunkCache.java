package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;

/**
 * The block access a Multi-Core Chunks worker builds a chunk from: a ChunkCache whose chunks are private
 * copies, taken on the render thread, so the worker never reads a chunk the game is changing.
 *
 * Only the 16-high sections the cache covers are copied (the chunk's own and the ones above and below it
 * that its one-block border reaches), plus the height map, biomes and tile entities. Biomes and tile
 * entities are looked up only in the copies: vanilla would create missing tile entities and generate
 * missing biomes in the live world, which isn't thread-safe. Biomes are filled in on the render thread
 * first, for the 5x5 chunks around (OptiFine's smooth biomes look past the cache).
 */
public class KirChunkCache extends ChunkCache
{
    private static final Field fieldChunkArray = KirUtil.findField(ChunkCache.class, "chunkArray", "c");
    private static final Field fieldChunkX = KirUtil.findField(ChunkCache.class, "chunkX", "a");
    private static final Field fieldChunkZ = KirUtil.findField(ChunkCache.class, "chunkZ", "b");

    private static final int BIOME_RADIUS = 2;

    private final World world;
    private final Chunk[][] chunks;
    private final int baseX;
    private final int baseZ;

    /** Biome arrays of the chunks around the center one, [x][z], null where not loaded. */
    private final byte[][][] biomes = new byte[BIOME_RADIUS * 2 + 1][BIOME_RADIUS * 2 + 1][];
    private final int biomeBaseX;
    private final int biomeBaseZ;

    static boolean isAvailable()
    {
        return fieldChunkArray != null && fieldChunkX != null && fieldChunkZ != null;
    }

    /** Copies the area; render thread. The bounds are vanilla's (the chunk plus one block around). */
    public KirChunkCache(World par1World, int x0, int y0, int z0, int x1, int y1, int z1) throws IllegalAccessException
    {
        super(par1World, x0, y0, z0, x1, y1, z1);
        this.world = par1World;
        this.chunks = (Chunk[][])fieldChunkArray.get(this);
        this.baseX = fieldChunkX.getInt(this);
        this.baseZ = fieldChunkZ.getInt(this);
        int var8 = Math.max(0, y0 >> 4);
        int var9 = Math.min(15, y1 >> 4);
        WorldChunkManager var10 = par1World.getWorldChunkManager();
        int var14 = ((x0 + x1) >> 1) >> 4;
        int var15 = ((z0 + z1) >> 1) >> 4;
        this.biomeBaseX = var14 - BIOME_RADIUS;
        this.biomeBaseZ = var15 - BIOME_RADIUS;

        if (this.extendedLevelsInChunkCache())
        {
            // Nothing but air: nothing will be built, so nothing is copied.
            return;
        }

        for (int var11 = 0; var11 < this.chunks.length; ++var11)
        {
            for (int var12 = 0; var12 < this.chunks[var11].length; ++var12)
            {
                Chunk var13 = this.chunks[var11][var12];

                if (var13 != null)
                {
                    this.chunks[var11][var12] = copyChunk(par1World, var13, var8, var9, var10);
                }
            }
        }

        for (int var16 = 0; var16 <= BIOME_RADIUS * 2; ++var16)
        {
            for (int var17 = 0; var17 <= BIOME_RADIUS * 2; ++var17)
            {
                int var18 = this.biomeBaseX + var16;
                int var19 = this.biomeBaseZ + var17;

                if (par1World.getChunkProvider().chunkExists(var18, var19))
                {
                    Chunk var20 = par1World.getChunkFromChunkCoords(var18, var19);

                    if (var20 != null && !(var20 instanceof EmptyChunk))
                    {
                        this.biomes[var16][var17] = filledBiomes(var20, var10);
                    }
                }
            }
        }
    }

    /** The chunk's biome array with every column filled in (render thread). */
    private static byte[] filledBiomes(Chunk chunk, WorldChunkManager manager)
    {
        byte[] var2 = chunk.getBiomeArray();

        for (int var3 = 0; var3 < 256; ++var3)
        {
            if ((var2[var3] & 255) == 255)
            {
                chunk.getBiomeGenForWorldCoords(var3 & 15, var3 >> 4, manager);
            }
        }

        return (byte[])chunk.getBiomeArray().clone();
    }

    private static Chunk copyChunk(World world, Chunk live, int minSection, int maxSection, WorldChunkManager manager)
    {
        Chunk var5 = new Chunk(world, live.xPosition, live.zPosition);
        ExtendedBlockStorage[] var6 = live.getBlockStorageArray();
        ExtendedBlockStorage[] var7 = var5.getBlockStorageArray();

        for (int var8 = minSection; var8 <= maxSection && var8 < var6.length; ++var8)
        {
            ExtendedBlockStorage var9 = var6[var8];

            if (var9 != null)
            {
                ExtendedBlockStorage var10 = new ExtendedBlockStorage(var9.getYLocation());
                var10.setBlockLSBArray((byte[])var9.getBlockLSBArray().clone());
                var10.setBlockMSBArray(copyNibbles(var9.getBlockMSBArray()));
                var10.setBlockMetadataArray(copyNibbles(var9.getMetadataArray()));
                var10.setBlocklightArray(copyNibbles(var9.getBlocklightArray()));
                var10.setSkylightArray(copyNibbles(var9.getSkylightArray()));
                var7[var8] = var10;
            }
        }

        System.arraycopy(live.heightMap, 0, var5.heightMap, 0, Math.min(live.heightMap.length, var5.heightMap.length));
        var5.setBiomeArray(filledBiomes(live, manager));
        Iterator var11 = live.chunkTileEntityMap.entrySet().iterator();

        while (var11.hasNext())
        {
            Map.Entry var12 = (Map.Entry)var11.next();
            TileEntity var13 = (TileEntity)var12.getValue();

            if (var13 != null && !var13.isInvalid())
            {
                var5.chunkTileEntityMap.put(var12.getKey(), var13);
            }
        }

        return var5;
    }

    private static NibbleArray copyNibbles(NibbleArray nibbles)
    {
        return nibbles == null ? null : new NibbleArray((byte[])nibbles.data.clone(), 4);
    }

    private Chunk chunkAt(int x, int z)
    {
        int var3 = (x >> 4) - this.baseX;
        int var4 = (z >> 4) - this.baseZ;
        return var3 >= 0 && var3 < this.chunks.length && var4 >= 0 && var4 < this.chunks[var3].length ? this.chunks[var3][var4] : null;
    }

    /** Only the copied tile entities; never creates one. */
    public TileEntity getBlockTileEntity(int par1, int par2, int par3)
    {
        Chunk var4 = this.chunkAt(par1, par3);
        return var4 == null ? null : (TileEntity)var4.chunkTileEntityMap.get(new ChunkPosition(par1 & 15, par2, par3 & 15));
    }

    /** From the copied biome arrays; never generates one. */
    public BiomeGenBase getBiomeGenForCoords(int par1, int par2)
    {
        int var3 = (par1 >> 4) - this.biomeBaseX;
        int var4 = (par2 >> 4) - this.biomeBaseZ;

        if (var3 >= 0 && var3 < this.biomes.length && var4 >= 0 && var4 < this.biomes.length && this.biomes[var3][var4] != null)
        {
            int var5 = this.biomes[var3][var4][(par2 & 15) << 4 | par1 & 15] & 255;
            BiomeGenBase var6 = var5 < BiomeGenBase.biomeList.length ? BiomeGenBase.biomeList[var5] : null;

            if (var6 != null)
            {
                return var6;
            }
        }

        return BiomeGenBase.plains;
    }
}
