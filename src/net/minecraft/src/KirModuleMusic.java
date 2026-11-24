package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.Random;
import net.minecraft.client.Minecraft;

/**
 * Music Frequency: changes the wait between songs.
 *
 * SoundManager counts its private ticksBeforeMusic down and sets a new vanilla delay whenever a song
 * starts. Whenever the countdown goes up (a new song started) or is longer than the setting allows, it
 * is replaced with a delay from the current setting. The short wait for the first song after startup
 * is left alone.
 */
public class KirModuleMusic extends KirModule
{
    private static final String[] VALUES = new String[] {"Rarer", "Default", "Often", "Constant"};

    /** Delay range in ticks [min, max) for each value. */
    private static final int[][] DELAYS = new int[][] {{24000, 48000}, {12000, 24000}, {6000, 12000}, {0, 0}};

    private static final Field fieldTicksBeforeMusic = KirUtil.findField(SoundManager.class, "ticksBeforeMusic", "i");

    private final Random rand = new Random();
    private int lastMusicTicks = -1;

    public KirModuleMusic()
    {
        super("musicFrequency", "Music Frequency", "Music Frequency", 1, "music");
    }

    public String getCategory()
    {
        return CATEGORY_OTHER;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Changes how long the game waits between songs.",
            describeValue("Rarer", "20 to 40 minutes between songs."),
            describeValue("Default", "vanilla, 10 to 20 minutes."),
            describeValue("Often", "5 to 10 minutes."),
            describeValue("Constant", "the next song starts as soon as one ends.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return fieldTicksBeforeMusic != null;
    }

    /** Ticks to wait before the next song with the current setting. */
    public int getMusicDelay(Random rand)
    {
        int[] var2 = DELAYS[this.getValue()];
        return var2[1] > var2[0] ? var2[0] + rand.nextInt(var2[1] - var2[0]) : var2[0];
    }

    public void update(Minecraft mc)
    {
        if (fieldTicksBeforeMusic == null || mc.sndManager == null)
        {
            return;
        }

        try
        {
            int var2 = fieldTicksBeforeMusic.getInt(mc.sndManager);

            if (this.lastMusicTicks >= 0 && var2 > this.lastMusicTicks || var2 > DELAYS[this.getValue()][1])
            {
                var2 = this.getMusicDelay(this.rand);
                fieldTicksBeforeMusic.setInt(mc.sndManager, var2);
            }

            this.lastMusicTicks = var2;
        }
        catch (IllegalAccessException var3)
        {
            KirUtil.log("Could not change the music delay", var3);
        }
    }
}
