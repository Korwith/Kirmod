package net.minecraft.src;

/**
 * One of the Music screen's options (1.36): Order, Sound Test, 0x10c, Music Discs, Nether Soundtrack and
 * End Soundtrack. They are plain values read by KirMusic; the module only keeps them in Kirmod.cfg. Its
 * category, CATEGORY_MUSIC, keeps them out of the settings screen.
 */
public class KirModuleMusicOption extends KirModule
{
    public static final String[] OFF_ON = new String[] {"Off", "On"};

    private final String[] values;
    private final String[] tooltip;

    public KirModuleMusicOption(String key, String title, String[] values, int defaultValue, String[] tooltip)
    {
        super(key, title, title, defaultValue, (String)null);
        this.values = values;
        this.tooltip = tooltip;
    }

    public String[] getValueNames()
    {
        return this.values;
    }

    public String getCategory()
    {
        return CATEGORY_MUSIC;
    }

    public String[] getTooltip()
    {
        return this.tooltip;
    }

    public boolean isAvailable()
    {
        return KirMusic.isAvailable();
    }

    static KirModuleMusicOption order()
    {
        return new KirModuleMusicOption("musicOrder", "Order", new String[] {"Shuffle", "In Order"}, 0, new String[] {
            "How the next song is picked.",
            describeValue("Shuffle", "a random order, as the game does; every song plays once before any plays again."),
            describeValue("In Order", "the playlist from top to bottom, then from the top again.")
        });
    }

    static KirModuleMusicOption soundTest()
    {
        return new KirModuleMusicOption("musicSoundTest", "Sound Test", OFF_ON, 0, new String[] {
            "Adds the Sound Test soundtrack's songs to the playlist of the soundtrack you picked.",
            note("Needs a folder with \"Sound Test\" in its name in .minecraft/soundtracks.")
        });
    }

    static KirModuleMusicOption x10c()
    {
        return new KirModuleMusicOption("music0x10c", "0x10c", OFF_ON, 0, new String[] {
            "Adds C418's 0x10c music to the playlist of the soundtrack you picked.",
            note("Needs a folder with \"0x10c\" in its name in .minecraft/soundtracks.")
        });
    }

    static KirModuleMusicOption discs()
    {
        return new KirModuleMusicOption("musicDiscs", "Music Discs", OFF_ON, 0, new String[] {
            "Adds the soundtrack's music disc songs (13, cat, blocks, chirp...) to its playlist.",
            note("All Music always has them.")
        });
    }

    static KirModuleMusicOption nether()
    {
        return new KirModuleMusicOption("musicNether", "Nether Soundtrack", OFF_ON, 0, new String[] {
            "In the Nether, plays the Nether's own music instead: Concrete Halls, Dead Voxel, Warmth and Ballad of the Cats from Volume Beta, or a soundtrack folder's nether/ subfolder.",
            describeValue("Off", "the same music as everywhere else, as in 1.3.2."),
            note("Needs Volume Beta (or a nether/ folder) in .minecraft/soundtracks.")
        });
    }

    static KirModuleMusicOption end()
    {
        return new KirModuleMusicOption("musicEnd", "End Soundtrack", OFF_ON, 0, new String[] {
            "In the End, plays the End's own music instead: The End from Volume Beta, or a soundtrack folder's end/ subfolder.",
            describeValue("Off", "the same music as everywhere else, as in 1.3.2."),
            note("Needs Volume Beta (or an end/ folder) in .minecraft/soundtracks.")
        });
    }
}
