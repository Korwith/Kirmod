package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.HashMap;
import net.minecraft.client.Minecraft;
import paulscode.sound.Library;
import paulscode.sound.SoundSystem;
import paulscode.sound.SoundSystemConfig;
import paulscode.sound.Source;

/**
 * Music Pitch: a slider from -5 to +5 semitones that changes the pitch of the background music only.
 *
 * SoundManager plays every song on its own "BgMusic" source, created anew for each song with pitch 1.
 * Every frame, if that source's pitch differs from the setting, it is set through the sound system, so
 * each new song is shifted as soon as it starts and a change applies to the song already playing. Game
 * sounds and music discs use other sources and are left alone. Like a record played faster or slower,
 * the music's speed changes with its pitch.
 */
public class KirModuleMusicPitch extends KirModule
{
    public static final int MAX_SEMITONES = 5;

    /** "-5" to "+5": value i is i - MAX_SEMITONES semitones. */
    private static final String[] VALUES = new String[MAX_SEMITONES * 2 + 1];

    private static final Field fieldSndSystem = KirUtil.findField(SoundManager.class, "sndSystem", "a");
    private static final Field fieldSoundLibrary = KirUtil.findField(SoundSystem.class, "soundLibrary");

    static
    {
        for (int var0 = 0; var0 < VALUES.length; ++var0)
        {
            int var1 = var0 - MAX_SEMITONES;
            VALUES[var0] = var1 > 0 ? "+" + var1 : String.valueOf(var1);
        }
    }

    public KirModuleMusicPitch()
    {
        super("musicPitch", "Music Pitch", "Music Pitch", MAX_SEMITONES, "musicpitch", "pitch");
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isSlider()
    {
        return true;
    }

    public String getCategory()
    {
        return CATEGORY_OTHER;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Raises or lowers the pitch of the background music. Game sounds and music discs are not changed.",
            describeValue("0", "vanilla pitch."),
            describeValue("-5 to +5", "that many semitones lower or higher; the music also plays slower or faster.")
        };
    }

    public boolean isAvailable()
    {
        return fieldSndSystem != null && fieldSoundLibrary != null;
    }

    /** Accepts a number of semitones such as "+3", "3" or "-2" (clamped to the range) as well as the value names. */
    public int findValue(String name)
    {
        String var2 = name.trim();

        try
        {
            int var3 = Integer.parseInt(var2.startsWith("+") ? var2.substring(1) : var2);
            return Math.max(-MAX_SEMITONES, Math.min(MAX_SEMITONES, var3)) + MAX_SEMITONES;
        }
        catch (NumberFormatException var4)
        {
            return super.findValue(name);
        }
    }

    /** The music's pitch multiplier, 2^(semitones / 12). */
    public float getPitch()
    {
        return (float)Math.pow(2.0D, (double)(this.getValue() - MAX_SEMITONES) / 12.0D);
    }

    public void update(Minecraft mc)
    {
        if (!this.isAvailable())
        {
            return;
        }

        try
        {
            SoundSystem var2 = (SoundSystem)fieldSndSystem.get((Object)null);

            if (var2 == null)
            {
                return;
            }

            float var3 = this.getPitch();
            boolean var4;

            synchronized (SoundSystemConfig.THREAD_SYNC)
            {
                Library var6 = (Library)fieldSoundLibrary.get(var2);
                HashMap var7 = var6 == null ? null : var6.getSources();
                Source var8 = var7 == null ? null : (Source)var7.get("BgMusic");
                var4 = var8 != null && Math.abs(var8.getPitch() - var3) > 1.0E-4F;
            }

            if (var4)
            {
                var2.setPitch("BgMusic", var3);
            }
        }
        catch (IllegalAccessException var10)
        {
            KirUtil.log("Could not change the music pitch", var10);
        }
    }
}
