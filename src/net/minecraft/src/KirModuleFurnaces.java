package net.minecraft.src;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Efficient Furnaces: furnaces tick every 2 or 4 ticks and do 2 or 4 ticks' worth of work each time.
 *
 * The furnace blocks are swapped for subclasses whose tile entity is a KirFurnace, and the "Furnace"
 * tile entity name is pointed at KirFurnace so furnaces already in a world load as one too. The result
 * of a batch is exactly the same as running the normal furnace update that many times, fuel included.
 */
public class KirModuleFurnaces extends KirModule
{
    private static final String[] VALUES = new String[] {"Normal", "Optimized", "Extra Optimized"};

    /** Tick interval (and progress multiplier) for each value. */
    private static final int[] INTERVALS = new int[] {1, 2, 4};

    private static final Field fieldTileNameToClass = KirUtil.findField(TileEntity.class, "nameToClassMap", "a");
    private static final Field fieldTileClassToName = KirUtil.findField(TileEntity.class, "classToNameMap", "b");
    private static final Method methodCanSmelt = KirUtil.findMethod(TileEntityFurnace.class, "canSmelt", "r");

    private boolean available = false;

    public KirModuleFurnaces()
    {
        super("furnaces", "Efficient Furnaces", "Furnaces", 0, "furnaces");
    }

    public String getCategory()
    {
        return CATEGORY_PERFORMANCE;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Makes furnaces cheaper to run: they update less often but do several ticks' worth of smelting each time. Smelting time, fuel use and output stay exactly the same.",
            describeValue("Normal", "vanilla, furnaces update every tick."),
            describeValue("Optimized", "furnaces update every 2 ticks."),
            describeValue("Extra Optimized", "furnaces update every 4 ticks. The progress arrow moves in bigger steps.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return this.available;
    }

    /** Ticks between batched furnace updates with the current setting; 1 means vanilla. */
    public int getInterval()
    {
        return this.available && this.enabled ? INTERVALS[this.getValue()] : 1;
    }

    public void install()
    {
        if (methodCanSmelt == null || fieldTileNameToClass == null || fieldTileClassToName == null)
        {
            KirUtil.log("Efficient furnaces are unavailable (furnace internals not found)", (Throwable)null);
            return;
        }

        try
        {
            boolean var1 = KirUtil.replaceBlock(Block.stoneOvenIdle, new KirBlockFurnace(Block.stoneOvenIdle.blockID, false), "stoneOvenIdle", "aB");
            boolean var2 = KirUtil.replaceBlock(Block.stoneOvenActive, new KirBlockFurnace(Block.stoneOvenActive.blockID, true), "stoneOvenActive", "aC");

            if (var1 && var2)
            {
                // Furnaces saved in the world load as KirFurnace, and KirFurnace saves as "Furnace".
                ((Map)fieldTileNameToClass.get((Object)null)).put("Furnace", KirFurnace.class);
                ((Map)fieldTileClassToName.get((Object)null)).put(KirFurnace.class, "Furnace");
                this.available = true;
            }
        }
        catch (Exception var3)
        {
            KirUtil.log("Could not install efficient furnaces", var3);
        }
    }

    public static class KirBlockFurnace extends BlockFurnace
    {
        public KirBlockFurnace(int par1, boolean par2)
        {
            super(KirUtil.constructSlot(par1), par2);
        }

        public TileEntity createNewTileEntity(World par1World)
        {
            return new KirFurnace();
        }
    }

    public static class KirFurnace extends TileEntityFurnace
    {
        /** Ticks counted towards the next batched update; -1 until first ticked. */
        private int tickCounter = -1;

        public void updateEntity()
        {
            int var1 = mod_Kirmod.FURNACES.getInterval();

            if (var1 <= 1)
            {
                super.updateEntity();
                return;
            }

            if (this.tickCounter < 0)
            {
                // Spread furnaces over the interval so they do not all do their work on the same tick.
                this.tickCounter = ((this.xCoord * 31 + this.zCoord * 17 + this.yCoord) & Integer.MAX_VALUE) % var1;
            }

            if (++this.tickCounter >= var1)
            {
                this.tickCounter = 0;
                runFurnaceTicks(this, var1);
            }
        }

        /** One normal (vanilla, or Forge's) furnace tick. */
        void tickNormally()
        {
            super.updateEntity();
        }
    }

    /**
     * Advances a furnace by the given number of ticks with exactly the same result as running its
     * normal update that many times. Stretches where only the counters change (fuel stays lit, no item
     * finishes) are done in one step; the ticks where fuel runs out or gets lit are run through the
     * furnace's own update so fuel handling stays identical, Forge's included.
     */
    private static void runFurnaceTicks(KirFurnace furnace, int ticks)
    {
        if (furnace.worldObj.isRemote)
        {
            // The client only counts burn time down; the server sends everything else.
            furnace.furnaceBurnTime = Math.max(0, furnace.furnaceBurnTime - ticks);
            return;
        }

        boolean var2 = false;

        while (ticks > 0)
        {
            boolean var3 = canSmelt(furnace);

            if (var3 && furnace.furnaceBurnTime > 1)
            {
                int var4 = Math.min(ticks, Math.min(furnace.furnaceBurnTime - 1, 200 - furnace.furnaceCookTime));
                furnace.furnaceBurnTime -= var4;
                furnace.furnaceCookTime += var4;
                ticks -= var4;

                if (furnace.furnaceCookTime >= 200)
                {
                    furnace.furnaceCookTime = 0;
                    furnace.smeltItem();
                    var2 = true;
                }
            }
            else if (!var3 && furnace.furnaceBurnTime > ticks)
            {
                // Nothing to smelt and the fuel outlasts the batch: it just burns down.
                furnace.furnaceBurnTime -= ticks;
                furnace.furnaceCookTime = 0;
                break;
            }
            else if (!var3 && furnace.furnaceBurnTime == 0)
            {
                // Idle furnace: nothing would change for the rest of the batch.
                furnace.furnaceCookTime = 0;
                break;
            }
            else
            {
                furnace.tickNormally();
                --ticks;
            }
        }

        if (var2)
        {
            furnace.onInventoryChanged();
        }
    }

    private static boolean canSmelt(TileEntityFurnace furnace)
    {
        try
        {
            return ((Boolean)methodCanSmelt.invoke(furnace, new Object[0])).booleanValue();
        }
        catch (Exception var2)
        {
            throw new RuntimeException("Kirmod could not call TileEntityFurnace.canSmelt", var2);
        }
    }
}
