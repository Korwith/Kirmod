package net.minecraft.src;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import paulscode.sound.SoundSystem;

/**
 * Kirmod's music player (1.36): plays the background music in place of SoundManager, from a soundtrack
 * picked in the Music screen (GuiKirmodMusic).
 *
 * Soundtracks:
 * - "Minecraft 1.3.2": the game's own music (the music pool: calm, hal, nuance and piano, plus any a mod
 *   adds), with the jukebox records (the streaming pool) as its music discs;
 * - one per folder in .minecraft/soundtracks/. Folders whose name says Volume Alpha, Volume Beta, Sound Test
 *   or 0x10c are C418's albums: their tracks are sorted into music, music discs, Nether and End by title
 *   (Volume Beta's Concrete Halls, Dead Voxel, Warmth and Ballad of the Cats are the Nether's; The End is the
 *   End's). In any other folder, the subfolders nether/, end/ and discs/ (or records/) hold those; every other
 *   file is music. Loose files directly in soundtracks/ make "Other Music";
 * - "All Music": every track of every soundtrack, discs, Nether and End tracks included. The game's own
 *   tracks are left out where an album has the same song.
 *
 * The playlist is the chosen soundtrack's music, plus its discs (Music Discs), Sound Test's and 0x10c's
 * music (their options), and in the Nether or the End that dimension's tracks from any soundtrack instead
 * (Nether / End Soundtrack, when there are some). Order is Shuffle (a shuffled round of the whole playlist,
 * as a music player shuffles; never the same song twice in a row) or In Order.
 *
 * Playback: the song plays on SoundManager's own "BgMusic" source, so the music volume slider, Music Pitch
 * and jukeboxes (a record stops the music) work as in vanilla. SoundManager's own countdown is held up every
 * frame so it never starts a song itself; Kirmod counts down instead, with Music Frequency's delays, while a
 * world is open and not paused (as vanilla, whose countdown runs from the player controller's tick). The
 * first song after startup waits the vanilla 0 to 10 minutes, capped by the setting. Songs can be started,
 * paused, skipped and gone back to from the Music screen at any time, at the main menu too.
 *
 * The sound system plays Ogg Vorbis, WAV and the game's .mus files. Anything else (MP3, FLAC, M4A, Opus...)
 * is converted to Ogg Vorbis with ffmpeg, if one can be found (kirmod.ffmpeg system property, soundtracks/
 * ffmpeg[.exe], then the PATH), on a background thread, into soundtracks/.converted/; a file is converted
 * once and reused while it is unchanged.
 */
public class KirMusic
{
    public static final String DIR_NAME = "soundtracks";
    public static final String CONFIG_KEY = "musicSoundtrack";
    public static final String VANILLA_ID = "vanilla";
    public static final String ALL_ID = "all";

    public static final int ROLE_MUSIC = 0;
    public static final int ROLE_DISC = 1;
    public static final int ROLE_NETHER = 2;
    public static final int ROLE_END = 3;

    public static final int KIND_VANILLA = 0;
    public static final int KIND_ALL = 1;
    public static final int KIND_ALPHA = 2;
    public static final int KIND_BETA = 3;
    public static final int KIND_SOUND_TEST = 4;
    public static final int KIND_0X10C = 5;
    public static final int KIND_CUSTOM = 6;

    /** Track states. */
    public static final int READY = 0;
    public static final int CONVERTING = 1;
    public static final int WAITING = 2;
    public static final int NO_FFMPEG = 3;
    public static final int FAILED = 4;

    public static final String C418_CREDIT = "Music by C418 (Daniel Rosenfeld)";

    private static final String[] PLAYABLE = {"ogg", "wav", "mus"};
    private static final String[] CONVERTIBLE = {"mp3", "flac", "m4a", "aac", "opus", "wma", "webm", "mka", "aif", "aiff", "oga", "mp2", "alac", "ape", "wv"};

    /** The game's music file names and the album titles of the same songs. */
    private static final String[][] VANILLA_TITLES = {
        {"calm1", "Minecraft"}, {"calm2", "Clark"}, {"calm3", "Sweden"},
        {"hal1", "Subwoofer Lullaby"}, {"hal2", "Living Mice"}, {"hal3", "Haggstrom"}, {"hal4", "Danny"},
        {"nuance1", "Key"}, {"nuance2", "Oxyg\u00e8ne"},
        {"piano1", "Dry Hands"}, {"piano2", "Wet Hands"}, {"piano3", "Mice on Venus"},
        {"13", "Thirteen"}, {"cat", "Cat"}, {"blocks", "Blocks"}, {"chirp", "Chirp"}, {"far", "Far"},
        {"mall", "Mall"}, {"mellohi", "Mellohi"}, {"stal", "Stal"}, {"strad", "Strad"}, {"ward", "Ward"},
        {"11", "Eleven"}, {"wait", "Wait"}
    };

    private static final Set ALPHA_DISCS = names(new String[] {"Cat", "Thirteen", "13"});
    private static final Set BETA_DISCS = names(new String[] {"Chirp", "Wait", "Mellohi", "Stal", "Strad", "Eleven", "11", "Ward", "Mall", "Blocks", "Far"});
    private static final Set BETA_NETHER = names(new String[] {"Concrete Halls", "Dead Voxel", "Warmth", "Ballad of the Cats"});
    private static final Set BETA_END = names(new String[] {"The End"});

    /** Music Frequency's delay range in ticks [min, max) for each of its values. */
    private static final int[][] DELAYS = {{24000, 48000}, {12000, 24000}, {6000, 12000}, {0, 0}};

    private static final Field fieldSndSystem = KirUtil.findField(SoundManager.class, "sndSystem", "a");
    private static final Field fieldLoaded = KirUtil.findField(SoundManager.class, "loaded", "g");
    private static final Field fieldTicksBeforeMusic = KirUtil.findField(SoundManager.class, "ticksBeforeMusic", "i");
    private static final Field fieldPoolMusic = KirUtil.findField(SoundManager.class, "soundPoolMusic", "d");
    private static final Field fieldPoolStreaming = KirUtil.findField(SoundManager.class, "soundPoolStreaming", "c");
    private static final Field fieldPoolEntries = KirUtil.findField(SoundPool.class, "allSoundPoolEntries", "e");

    private static final Random rand = new Random();

    /** Every soundtrack, in tile order: Minecraft 1.3.2, the folders, All Music. */
    private static List soundtracks = new ArrayList();

    /** Tracks of folders, by source path, so their state survives rescans. */
    private static final Map tracksByPath = new HashMap();

    private static long dirStamp = Long.MIN_VALUE;
    private static int vanillaCount = -1;
    private static long lastScan;

    /* Playback state (main thread only). */
    private static Track current;
    private static boolean paused;
    private static boolean waitingStart;
    private static long startedAt;
    private static double elapsedMs;
    private static long lastFrame;
    private static double countdownMs = -1.0D;
    private static int lastDimension = Integer.MIN_VALUE;
    private static String lastPlaylistKey;
    private static final LinkedList history = new LinkedList();
    private static final LinkedList shuffleQueue = new LinkedList();
    private static String shuffleKey;
    private static String message;
    private static long messageUntil;

    /* Converter. */
    private static Thread converter;
    private static volatile Track[] convertQueue = new Track[0];
    private static volatile File ffmpeg;
    private static volatile boolean ffmpegSearched;
    private static final Object convertLock = new Object();

    /** One song. */
    public static class Track
    {
        public final String title;
        /** The folder's soundtrack (a track of All Music keeps its own). */
        public Soundtrack soundtrack;
        public final int role;
        /** The file in the soundtracks folder, or null for the game's own tracks. */
        public final File source;
        final URL vanillaUrl;
        final String vanillaName;
        /** The game's file name (calm1), for its own tracks. */
        public final String vanillaFile;
        volatile File playable;
        volatile int state;
        volatile long durationMs = -1L;
        volatile boolean durationRead;
        int number;

        Track(String title, Soundtrack soundtrack, int role, File source, int number)
        {
            this.title = title;
            this.soundtrack = soundtrack;
            this.role = role;
            this.source = source;
            this.vanillaUrl = null;
            this.vanillaName = null;
            this.vanillaFile = null;
            this.number = number;
        }

        Track(String title, Soundtrack soundtrack, int role, SoundPoolEntry entry, String file, int number)
        {
            this.title = title;
            this.soundtrack = soundtrack;
            this.role = role;
            this.source = null;
            this.vanillaUrl = entry.soundUrl;
            this.vanillaName = entry.soundName;
            this.vanillaFile = file;
            this.state = READY;
            this.number = number;
        }

        public int getState()
        {
            return this.state;
        }

        /** Length in milliseconds, or -1 if not known (yet). */
        public long getDuration()
        {
            return this.durationMs;
        }

        public boolean isReady()
        {
            return this.state == READY;
        }

        /** "Volume Beta", "Minecraft 1.3.2". */
        public String getAlbum()
        {
            return this.soundtrack.name;
        }

        String getKey()
        {
            return this.source != null ? this.source.getAbsolutePath() : "vanilla:" + this.vanillaName + ":" + this.role;
        }
    }

    /** One tile in the Music screen. */
    public static class Soundtrack
    {
        public final String id;
        public final String name;
        public final int kind;
        public final File dir;
        /** "Minecraft - Volume Alpha (2011)" etc., or the first line of a folder's credits.txt. */
        public final String fullName;
        public final List credits = new ArrayList();
        public final List tracks = new ArrayList();
        File cover;
        int coverTexture = -1;
        boolean coverLoaded;

        Soundtrack(String id, String name, int kind, File dir, String fullName)
        {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.dir = dir;
            this.fullName = fullName;
        }

        public int count(int role)
        {
            int var2 = 0;

            for (int var3 = 0; var3 < this.tracks.size(); ++var3)
            {
                if (((Track)this.tracks.get(var3)).role == role)
                {
                    ++var2;
                }
            }

            return var2;
        }

        public boolean isC418()
        {
            return this.kind == KIND_ALPHA || this.kind == KIND_BETA || this.kind == KIND_SOUND_TEST || this.kind == KIND_0X10C || this.kind == KIND_VANILLA;
        }

        /** The GL texture of cover.jpg/png (or folder.jpg...), loaded the first time; -1 for none. */
        public int getCoverTexture(RenderEngine engine)
        {
            if (!this.coverLoaded)
            {
                this.coverLoaded = true;

                if (this.cover != null)
                {
                    try
                    {
                        BufferedImage var2 = ImageIO.read(this.cover);

                        if (var2 != null)
                        {
                            BufferedImage var3 = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
                            Graphics2D var4 = var3.createGraphics();
                            var4.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                            var4.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                            var4.drawImage(var2, 0, 0, 128, 128, null);
                            var4.dispose();
                            this.coverTexture = engine.allocateAndSetupTexture(var3);
                        }
                    }
                    catch (Throwable var5)
                    {
                        KirUtil.log("Could not read " + this.cover, var5);
                    }
                }
            }

            return this.coverTexture;
        }
    }

    private static Set names(String[] titles)
    {
        HashSet var1 = new HashSet();

        for (int var2 = 0; var2 < titles.length; ++var2)
        {
            var1.add(KirUtil.normalize(titles[var2]));
        }

        return var1;
    }

    /** False if the sound manager could not be hooked; the vanilla music then plays as usual. */
    public static boolean isAvailable()
    {
        return fieldSndSystem != null && fieldLoaded != null && fieldTicksBeforeMusic != null;
    }

    public static File getDir()
    {
        File var0 = new File(Minecraft.getMinecraftDir(), DIR_NAME);

        if (!var0.isDirectory())
        {
            var0.mkdirs();
        }

        return var0;
    }

    public static void openDir()
    {
        KirCapes.openDir(getDir());
    }

    /* ------------------------------------------------------------------ Library */

    /** Rescans the folder (and the game's own music) if something changed; cheap otherwise. */
    public static void refresh(Minecraft mc, boolean force)
    {
        long var2 = System.currentTimeMillis();

        if (!force && var2 - lastScan < 2000L && !soundtracks.isEmpty())
        {
            return;
        }

        lastScan = var2;
        File var4 = getDir();
        long var5 = stamp(var4, 0);
        int var7 = countVanilla(mc);

        if (!force && var5 == dirStamp && var7 == vanillaCount && !soundtracks.isEmpty())
        {
            return;
        }

        dirStamp = var5;
        vanillaCount = var7;
        ArrayList var8 = new ArrayList();
        HashMap var9 = new HashMap();
        var8.add(buildVanilla(mc));
        File[] var10 = var4.listFiles();

        if (var10 != null)
        {
            Arrays.sort(var10, new NaturalOrder());
            ArrayList var11 = new ArrayList();

            for (int var12 = 0; var12 < var10.length; ++var12)
            {
                File var13 = var10[var12];

                if (var13.getName().startsWith("."))
                {
                    continue;
                }

                if (var13.isDirectory())
                {
                    Soundtrack var14 = buildFolder(var13, var9);

                    if (!var14.tracks.isEmpty())
                    {
                        var8.add(var14);
                    }
                }
                else if (isAudio(var13))
                {
                    var11.add(var13);
                }
            }

            if (!var11.isEmpty())
            {
                Soundtrack var15 = new Soundtrack("loose", "Other Music", KIND_CUSTOM, var4, "Loose files in the soundtracks folder");

                for (int var16 = 0; var16 < var11.size(); ++var16)
                {
                    addFile(var15, (File)var11.get(var16), ROLE_MUSIC, var16 + 1, var9);
                }

                var8.add(var15);
            }
        }

        var8.add(buildAll(var8));
        tracksByPath.clear();
        tracksByPath.putAll(var9);
        soundtracks = var8;
        shuffleQueue.clear();
        updateConvertQueue();
    }

    private static long stamp(File dir, int depth)
    {
        File[] var2 = dir.listFiles();
        long var3 = 17L;

        if (var2 != null)
        {
            for (int var5 = 0; var5 < var2.length; ++var5)
            {
                File var6 = var2[var5];

                if (var6.getName().startsWith("."))
                {
                    continue;
                }

                var3 = var3 * 31L + var6.getName().hashCode() + var6.lastModified() + var6.length();

                if (var6.isDirectory() && depth < 2)
                {
                    var3 = var3 * 31L + stamp(var6, depth + 1);
                }
            }
        }

        return var3;
    }

    private static List getPoolEntries(Minecraft mc, Field pool)
    {
        if (mc == null || mc.sndManager == null || pool == null || fieldPoolEntries == null)
        {
            return Collections.EMPTY_LIST;
        }

        try
        {
            Object var2 = pool.get(mc.sndManager);
            List var3 = var2 == null ? null : (List)fieldPoolEntries.get(var2);
            return var3 == null ? Collections.EMPTY_LIST : new ArrayList(var3);
        }
        catch (Exception var4)
        {
            return Collections.EMPTY_LIST;
        }
    }

    private static int countVanilla(Minecraft mc)
    {
        return getPoolEntries(mc, fieldPoolMusic).size() * 1000 + getPoolEntries(mc, fieldPoolStreaming).size();
    }

    private static String vanillaTitle(String file)
    {
        for (int var1 = 0; var1 < VANILLA_TITLES.length; ++var1)
        {
            if (VANILLA_TITLES[var1][0].equals(file))
            {
                return VANILLA_TITLES[var1][1];
            }
        }

        return file;
    }

    /** "newmusic/hal1.ogg" -> "hal1". */
    private static String baseName(String name)
    {
        String var1 = name.replace('\\', '/');
        var1 = var1.substring(var1.lastIndexOf('/') + 1);
        int var2 = var1.lastIndexOf('.');
        return var2 > 0 ? var1.substring(0, var2) : var1;
    }

    private static Soundtrack buildVanilla(Minecraft mc)
    {
        Soundtrack var1 = new Soundtrack(VANILLA_ID, "Minecraft 1.3.2", KIND_VANILLA, null, "The game's own music and music discs");
        var1.credits.add(C418_CREDIT);
        var1.credits.add("The music and records that come with Minecraft 1.3.2.");
        List var2 = getPoolEntries(mc, fieldPoolMusic);
        ArrayList var3 = new ArrayList();

        for (int var4 = 0; var4 < var2.size(); ++var4)
        {
            SoundPoolEntry var5 = (SoundPoolEntry)var2.get(var4);
            String var6 = baseName(var5.soundName);
            var3.add(new Track(vanillaTitle(var6), var1, ROLE_MUSIC, var5, var6, 0));
        }

        sortVanilla(var3);
        var1.tracks.addAll(var3);
        var3.clear();
        List var7 = getPoolEntries(mc, fieldPoolStreaming);
        HashMap var8 = new HashMap();

        // Records come as .mus and, for 13 and cat, .ogg too: one track each, the .ogg if there is one.
        for (int var9 = 0; var9 < var7.size(); ++var9)
        {
            SoundPoolEntry var10 = (SoundPoolEntry)var7.get(var9);
            String var11 = baseName(var10.soundName);
            SoundPoolEntry var12 = (SoundPoolEntry)var8.get(var11);

            if (var12 == null || !var12.soundName.toLowerCase().endsWith(".ogg"))
            {
                var8.put(var11, var10);
            }
        }

        Iterator var13 = var8.entrySet().iterator();

        while (var13.hasNext())
        {
            Map.Entry var14 = (Map.Entry)var13.next();
            String var15 = (String)var14.getKey();
            var3.add(new Track(vanillaTitle(var15), var1, ROLE_DISC, (SoundPoolEntry)var14.getValue(), var15, 0));
        }

        sortVanilla(var3);
        var1.tracks.addAll(var3);
        return var1;
    }

    /** In VANILLA_TITLES' order, others by name after them. */
    private static void sortVanilla(List tracks)
    {
        Collections.sort(tracks, new Comparator()
        {
            public int compare(Object a, Object b)
            {
                Track var3 = (Track)a;
                Track var4 = (Track)b;
                int var5 = vanillaIndex(var3.vanillaFile);
                int var6 = vanillaIndex(var4.vanillaFile);
                return var5 != var6 ? var5 - var6 : var3.vanillaFile.compareTo(var4.vanillaFile);
            }
        });
    }

    private static int vanillaIndex(String file)
    {
        for (int var1 = 0; var1 < VANILLA_TITLES.length; ++var1)
        {
            if (VANILLA_TITLES[var1][0].equals(file))
            {
                return var1;
            }
        }

        return VANILLA_TITLES.length;
    }

    private static int kindOf(String folderName)
    {
        String var1 = KirUtil.normalize(folderName);

        if (var1.indexOf("soundtest") >= 0)
        {
            return KIND_SOUND_TEST;
        }

        if (var1.indexOf("0x10c") >= 0)
        {
            return KIND_0X10C;
        }

        if (var1.indexOf("volumealpha") >= 0 || var1.equals("alpha") || var1.endsWith("minecraftalpha"))
        {
            return KIND_ALPHA;
        }

        if (var1.indexOf("volumebeta") >= 0 || var1.equals("beta") || var1.endsWith("minecraftbeta"))
        {
            return KIND_BETA;
        }

        return KIND_CUSTOM;
    }

    private static Soundtrack buildFolder(File dir, Map tracks)
    {
        int var2 = kindOf(dir.getName());
        String var3;
        String var4;

        switch (var2)
        {
            case KIND_ALPHA:
                var3 = "Volume Alpha";
                var4 = "Minecraft - Volume Alpha (2011)";
                break;

            case KIND_BETA:
                var3 = "Volume Beta";
                var4 = "Minecraft - Volume Beta (2013)";
                break;

            case KIND_SOUND_TEST:
                var3 = "Sound Test";
                var4 = "Sound Test";
                break;

            case KIND_0X10C:
                var3 = "0x10c";
                var4 = "0x10c (2012), for Notch's cancelled space game";
                break;

            default:
                var3 = dir.getName();
                var4 = dir.getName();
        }

        Soundtrack var5 = new Soundtrack("folder:" + dir.getName(), var3, var2, dir, var4);

        if (var5.isC418())
        {
            var5.credits.add(C418_CREDIT);
        }

        File var6 = new File(dir, "credits.txt");

        if (var6.isFile())
        {
            var5.credits.addAll(readLines(var6, 8));
        }

        var5.cover = findCover(dir);
        ArrayList var7 = new ArrayList();
        collectFiles(dir, var2 == KIND_CUSTOM ? ROLE_MUSIC : -1, var7, 0);
        int[] var8 = new int[4];

        for (int var9 = 0; var9 < var7.size(); var9 += 2)
        {
            File var10 = (File)var7.get(var9);
            int var11 = ((Integer)var7.get(var9 + 1)).intValue();

            if (var11 < 0)
            {
                var11 = roleByTitle(var2, cleanTitle(var10));
            }

            addFile(var5, var10, var11, ++var8[var11], tracks);
        }

        return var5;
    }

    /** Adds file, role pairs: the folder's audio files, with role -1 where the title decides. */
    private static void collectFiles(File dir, int role, List out, int depth)
    {
        File[] var4 = dir.listFiles();

        if (var4 == null)
        {
            return;
        }

        Arrays.sort(var4, new NaturalOrder());

        for (int var5 = 0; var5 < var4.length; ++var5)
        {
            if (!var4[var5].isDirectory() && isAudio(var4[var5]))
            {
                out.add(var4[var5]);
                out.add(Integer.valueOf(role));
            }
        }

        for (int var6 = 0; var6 < var4.length; ++var6)
        {
            File var7 = var4[var6];

            if (var7.isDirectory() && !var7.getName().startsWith(".") && depth < 3)
            {
                String var8 = KirUtil.normalize(var7.getName());
                int var9 = var8.equals("nether") ? ROLE_NETHER : (var8.equals("end") || var8.equals("theend") ? ROLE_END : (var8.equals("discs") || var8.equals("records") || var8.equals("musicdiscs") ? ROLE_DISC : role));
                collectFiles(var7, var9, out, depth + 1);
            }
        }
    }

    private static int roleByTitle(int kind, String title)
    {
        String var2 = KirUtil.normalize(title);

        if (kind == KIND_ALPHA && ALPHA_DISCS.contains(var2) || kind == KIND_BETA && BETA_DISCS.contains(var2))
        {
            return ROLE_DISC;
        }

        if (kind == KIND_BETA && BETA_NETHER.contains(var2))
        {
            return ROLE_NETHER;
        }

        return kind == KIND_BETA && BETA_END.contains(var2) ? ROLE_END : ROLE_MUSIC;
    }

    private static void addFile(Soundtrack soundtrack, File file, int role, int number, Map tracks)
    {
        String var5 = file.getAbsolutePath();
        Track var6 = (Track)tracksByPath.get(var5);

        if (var6 == null || var6.role != role || !var6.soundtrack.id.equals(soundtrack.id) || var6.source.length() != file.length() || var6.state == FAILED)
        {
            var6 = new Track(cleanTitle(file), soundtrack, role, file, number);
            var6.state = needsConversion(file) ? WAITING : READY;

            if (var6.state == READY)
            {
                var6.playable = file;
            }
            else
            {
                File var7 = getConvertedFile(file);

                if (var7.isFile() && var7.length() > 0L)
                {
                    var6.playable = var7;
                    var6.state = READY;
                }
            }
        }
        else
        {
            // Same file: the same track (the converter may still be working on it), under the rebuilt soundtrack.
            var6.soundtrack = soundtrack;
            var6.number = number;
        }

        tracks.put(var5, var6);
        soundtrack.tracks.add(var6);
    }

    private static Soundtrack buildAll(List built)
    {
        Soundtrack var1 = new Soundtrack(ALL_ID, "All Music", KIND_ALL, null, "Every soundtrack, music discs included");
        HashSet var2 = new HashSet();

        for (int var3 = 1; var3 < built.size(); ++var3)
        {
            Soundtrack var4 = (Soundtrack)built.get(var3);
            var1.tracks.addAll(var4.tracks);

            for (int var5 = 0; var5 < var4.tracks.size(); ++var5)
            {
                Track var6 = (Track)var4.tracks.get(var5);

                if (var4.isC418())
                {
                    var2.add(KirUtil.normalize(var6.title));
                }
            }

            if (var4.isC418() && !var1.credits.contains(C418_CREDIT))
            {
                var1.credits.add(C418_CREDIT);
            }
        }

        // The game's own songs, unless an album has the same one.
        Soundtrack var7 = (Soundtrack)built.get(0);

        for (int var8 = 0; var8 < var7.tracks.size(); ++var8)
        {
            Track var9 = (Track)var7.tracks.get(var8);

            if (!var2.contains(KirUtil.normalize(var9.title)))
            {
                var1.tracks.add(var9);
            }
        }

        if (!var1.credits.contains(C418_CREDIT))
        {
            var1.credits.add(C418_CREDIT);
        }

        return var1;
    }

    private static File findCover(File dir)
    {
        String[] var1 = {"cover.jpg", "cover.png", "cover.jpeg", "folder.jpg", "folder.png", "front.jpg", "front.png", "albumart.jpg"};

        for (int var2 = 0; var2 < var1.length; ++var2)
        {
            File var3 = new File(dir, var1[var2]);

            if (var3.isFile())
            {
                return var3;
            }
        }

        File[] var4 = dir.listFiles();

        if (var4 != null)
        {
            for (int var5 = 0; var5 < var4.length; ++var5)
            {
                String var6 = var4[var5].getName().toLowerCase();

                if (var6.endsWith(".jpg") || var6.endsWith(".png") || var6.endsWith(".jpeg"))
                {
                    return var4[var5];
                }
            }
        }

        return null;
    }

    private static List readLines(File file, int max)
    {
        ArrayList var2 = new ArrayList();

        try
        {
            java.io.BufferedReader var3 = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(file), "UTF-8"));

            try
            {
                String var4;

                while ((var4 = var3.readLine()) != null && var2.size() < max)
                {
                    if (var4.trim().length() > 0)
                    {
                        var2.add(var4.trim());
                    }
                }
            }
            finally
            {
                var3.close();
            }
        }
        catch (IOException var8)
        {
            ;
        }

        return var2;
    }

    private static String extension(File file)
    {
        String var1 = file.getName();
        int var2 = var1.lastIndexOf('.');
        return var2 < 0 ? "" : var1.substring(var2 + 1).toLowerCase();
    }

    static boolean isAudio(File file)
    {
        String var1 = extension(file);
        return Arrays.asList(PLAYABLE).contains(var1) || Arrays.asList(CONVERTIBLE).contains(var1);
    }

    /** True for files the sound system can't play: other formats, and Ogg files holding Opus or FLAC. */
    static boolean needsConversion(File file)
    {
        String var1 = extension(file);

        if (!Arrays.asList(PLAYABLE).contains(var1))
        {
            return true;
        }

        if (!var1.equals("ogg"))
        {
            return false;
        }

        byte[] var2 = new byte[128];
        int var3 = 0;

        try
        {
            InputStream var4 = new FileInputStream(file);

            try
            {
                var3 = var4.read(var2);
            }
            finally
            {
                var4.close();
            }
        }
        catch (IOException var8)
        {
            return false;
        }

        String var5 = new String(var2, 0, Math.max(0, var3), java.nio.charset.Charset.forName("ISO-8859-1"));
        return var5.indexOf("vorbis") < 0 && (var5.indexOf("OpusHead") >= 0 || var5.indexOf("FLAC") >= 0);
    }

    /** "07 Haggstrom.mp3" -> "Haggstrom"; "C418 - 01 - Key.ogg" -> "Key". */
    static String cleanTitle(File file)
    {
        String var1 = file.getName();
        int var2 = var1.lastIndexOf('.');
        String var3 = var2 > 0 ? var1.substring(0, var2) : var1;
        var3 = var3.replace('_', ' ').trim();
        var3 = var3.replaceFirst("(?i)^c418\\s*-\\s*", "");
        var3 = var3.replaceFirst("^\\d{1,3}\\s*([-.)]\\s*)?(?=\\S)", "");
        var3 = var3.replaceFirst("(?i)^c418\\s*-\\s*", "");
        return var3.trim().length() == 0 ? var1 : var3.trim();
    }

    public static List getSoundtracks(Minecraft mc)
    {
        refresh(mc, false);
        return Collections.unmodifiableList(soundtracks);
    }

    public static Soundtrack find(String id)
    {
        for (int var1 = 0; var1 < soundtracks.size(); ++var1)
        {
            Soundtrack var2 = (Soundtrack)soundtracks.get(var1);

            if (var2.id.equals(id))
            {
                return var2;
            }
        }

        return null;
    }

    public static Soundtrack findKind(int kind)
    {
        for (int var1 = 0; var1 < soundtracks.size(); ++var1)
        {
            Soundtrack var2 = (Soundtrack)soundtracks.get(var1);

            if (var2.kind == kind)
            {
                return var2;
            }
        }

        return null;
    }

    public static String getSelectedId()
    {
        return mod_Kirmod.getSetting(CONFIG_KEY, VANILLA_ID);
    }

    /** The chosen soundtrack; Minecraft 1.3.2 if it's gone from the folder. */
    public static Soundtrack getSelected()
    {
        Soundtrack var0 = find(getSelectedId());
        return var0 != null ? var0 : (soundtracks.isEmpty() ? null : (Soundtrack)soundtracks.get(0));
    }

    public static void select(Soundtrack soundtrack)
    {
        mod_Kirmod.setSetting(CONFIG_KEY, soundtrack.id);
        shuffleQueue.clear();
        updateConvertQueue();
    }

    /* ------------------------------------------------------------------ Playlists */

    private static boolean option(KirModule module)
    {
        return module != null && module.isEnabled() && module.getValue() == 1;
    }

    public static boolean isShuffle()
    {
        return !option(mod_Kirmod.MUSIC_ORDER);
    }

    /** Which list plays in a dimension: 0 the music, 1 the Nether's, 2 the End's. */
    public static int getPlaylistKind(int dimension)
    {
        if (dimension == -1 && option(mod_Kirmod.MUSIC_NETHER) && !getDimensionTracks(ROLE_NETHER).isEmpty())
        {
            return 1;
        }

        return dimension == 1 && option(mod_Kirmod.MUSIC_END) && !getDimensionTracks(ROLE_END).isEmpty() ? 2 : 0;
    }

    /** The Nether or End tracks: the chosen soundtrack's, or if it has none, every soundtrack's. */
    public static List getDimensionTracks(int role)
    {
        ArrayList var1 = new ArrayList();
        Soundtrack var2 = getSelected();

        if (var2 != null && var2.kind != KIND_ALL)
        {
            addRole(var1, var2, role);
        }

        if (var1.isEmpty())
        {
            for (int var3 = 0; var3 < soundtracks.size(); ++var3)
            {
                Soundtrack var4 = (Soundtrack)soundtracks.get(var3);

                if (var4.kind != KIND_ALL)
                {
                    addRole(var1, var4, role);
                }
            }
        }

        return var1;
    }

    private static void addRole(List out, Soundtrack soundtrack, int role)
    {
        for (int var3 = 0; var3 < soundtrack.tracks.size(); ++var3)
        {
            Track var4 = (Track)soundtrack.tracks.get(var3);

            if (var4.role == role && !out.contains(var4))
            {
                out.add(var4);
            }
        }
    }

    /** The music outside the Nether and End (or there, without their own soundtrack). */
    public static List getMusicPlaylist()
    {
        ArrayList var0 = new ArrayList();
        Soundtrack var1 = getSelected();

        if (var1 == null)
        {
            return var0;
        }

        if (var1.kind == KIND_ALL)
        {
            var0.addAll(var1.tracks);
            return var0;
        }

        addRole(var0, var1, ROLE_MUSIC);

        if (option(mod_Kirmod.MUSIC_DISCS))
        {
            addRole(var0, var1, ROLE_DISC);
        }

        Soundtrack var2 = findKind(KIND_SOUND_TEST);

        if (option(mod_Kirmod.MUSIC_SOUND_TEST) && var2 != null && var2 != var1)
        {
            addRole(var0, var2, ROLE_MUSIC);
        }

        Soundtrack var3 = findKind(KIND_0X10C);

        if (option(mod_Kirmod.MUSIC_0X10C) && var3 != null && var3 != var1)
        {
            addRole(var0, var3, ROLE_MUSIC);
        }

        return var0;
    }

    public static List getPlaylist(int dimension)
    {
        int var1 = getPlaylistKind(dimension);
        return var1 == 1 ? getDimensionTracks(ROLE_NETHER) : (var1 == 2 ? getDimensionTracks(ROLE_END) : getMusicPlaylist());
    }

    private static int getDimension(Minecraft mc)
    {
        return mc.thePlayer != null && mc.theWorld != null ? mc.thePlayer.dimension : 0;
    }

    /* ------------------------------------------------------------------ Playback */

    private static SoundSystem getSoundSystem(Minecraft mc)
    {
        try
        {
            if (mc.sndManager == null || !fieldLoaded.getBoolean((Object)null))
            {
                return null;
            }

            return (SoundSystem)fieldSndSystem.get((Object)null);
        }
        catch (Exception var2)
        {
            return null;
        }
    }

    /** Ticks to wait before the next song with Music Frequency's setting. */
    public static int getMusicDelayTicks()
    {
        int[] var0 = DELAYS[getFrequency()];
        return var0[1] > var0[0] ? var0[0] + rand.nextInt(var0[1] - var0[0]) : var0[0];
    }

    private static int getFrequency()
    {
        KirModule var0 = mod_Kirmod.MUSIC_FREQUENCY;
        return var0.isEnabled() ? Math.max(0, Math.min(DELAYS.length - 1, var0.getValue())) : 1;
    }

    /** Called when Music Frequency changes: a wait longer than the new setting allows is shortened. */
    public static void onFrequencyChanged()
    {
        if (current == null && countdownMs > (double)DELAYS[getFrequency()][1] * 50.0D)
        {
            countdownMs = (double)getMusicDelayTicks() * 50.0D;
        }
    }

    /** Called every frame, in game and in menus. */
    public static void update(Minecraft mc)
    {
        long var1 = System.currentTimeMillis();
        long var3 = lastFrame == 0L ? 0L : Math.min(1000L, Math.max(0L, var1 - lastFrame));
        lastFrame = var1;

        if (!isAvailable() || mc.sndManager == null)
        {
            return;
        }

        // Hold up SoundManager's own countdown, so only Kirmod starts songs.
        KirUtil.setInt(fieldTicksBeforeMusic, mc.sndManager, 1000000000);
        SoundSystem var5 = getSoundSystem(mc);

        if (var5 == null)
        {
            return;
        }

        if (mc.theWorld != null || mc.currentScreen instanceof GuiKirmodMusic)
        {
            refresh(mc, false);
        }

        boolean var6 = var5.playing("BgMusic");

        if (current != null && !paused)
        {
            if (waitingStart)
            {
                if (var6)
                {
                    waitingStart = false;
                }
                else if (var1 - startedAt > 5000L)
                {
                    KirUtil.log("Could not play " + current.title, (Throwable)null);
                    setMessage("Could not play " + current.title);
                    current = null;
                    waitingStart = false;
                    countdownMs = 1000.0D;
                }
            }
            else if (!var6)
            {
                // Ended, or stopped by a record, the volume slider or another mod.
                current = null;
                countdownMs = (double)getMusicDelayTicks() * 50.0D;
            }
            else
            {
                elapsedMs += (double)var3 * (mod_Kirmod.MUSIC_PITCH.isEnabled() ? (double)mod_Kirmod.MUSIC_PITCH.getPitch() : 1.0D);
            }
        }

        boolean var7 = mc.theWorld != null && mc.thePlayer != null && !mc.isGamePaused;

        if (var7)
        {
            if (countdownMs < 0.0D)
            {
                // The first song after startup: vanilla's 0 to 10 minutes, no longer than the setting allows.
                int var8 = Math.min(12000, DELAYS[getFrequency()][1]);
                countdownMs = (double)(var8 > 0 ? rand.nextInt(var8) : 0) * 50.0D;
            }

            int var9 = getDimension(mc);
            String var10 = String.valueOf(getPlaylistKind(var9));

            if (var9 != lastDimension)
            {
                lastDimension = var9;

                // Entering or leaving a dimension with its own soundtrack ends a song that doesn't belong there.
                if (lastPlaylistKey != null && !var10.equals(lastPlaylistKey) && current != null && !getPlaylist(var9).contains(current))
                {
                    var5.stop("BgMusic");
                    current = null;
                    paused = false;
                    countdownMs = Math.min(countdownMs, 3000.0D);
                }
                else if (lastPlaylistKey != null && !var10.equals(lastPlaylistKey) && current == null)
                {
                    countdownMs = Math.min(countdownMs, 3000.0D);
                }
            }

            lastPlaylistKey = var10;

            if (current == null && mc.gameSettings.musicVolume > 0.0F && !var5.playing("streaming"))
            {
                countdownMs -= (double)var3;

                if (countdownMs <= 0.0D)
                {
                    if (!playNext(mc, false))
                    {
                        countdownMs = 5000.0D;
                    }
                }
            }
        }
    }

    /** Starts the next song of the playlist now. False if none is ready. */
    public static boolean playNext(Minecraft mc, boolean manual)
    {
        List var2 = getPlaylist(getDimension(mc));
        ArrayList var3 = new ArrayList();

        for (int var4 = 0; var4 < var2.size(); ++var4)
        {
            if (((Track)var2.get(var4)).isReady())
            {
                var3.add(var2.get(var4));
            }
        }

        if (var3.isEmpty())
        {
            if (manual)
            {
                setMessage(var2.isEmpty() ? "The playlist is empty" : "No track is ready yet (still converting)");
            }

            return false;
        }

        Track var5 = current != null ? current : (history.isEmpty() ? null : (Track)history.getLast());
        Track var6;

        if (isShuffle())
        {
            String var7 = getSelectedId() + "/" + getPlaylistKind(getDimension(mc)) + "/" + var3.size();

            if (!var7.equals(shuffleKey))
            {
                shuffleQueue.clear();
                shuffleKey = var7;
            }

            var6 = null;

            while (var6 == null)
            {
                if (shuffleQueue.isEmpty())
                {
                    ArrayList var8 = new ArrayList(var3);
                    Collections.shuffle(var8, rand);

                    // A new round never starts with the song that just played.
                    if (var8.size() > 1 && var8.get(0) == var5)
                    {
                        var8.add(var8.remove(0));
                    }

                    shuffleQueue.addAll(var8);
                }

                Track var9 = (Track)shuffleQueue.removeFirst();

                if (var3.contains(var9) && (var9 != var5 || var3.size() == 1))
                {
                    var6 = var9;
                }
            }
        }
        else
        {
            int var10 = var5 == null ? -1 : var2.indexOf(var5);
            var6 = null;

            for (int var11 = 1; var11 <= var2.size() && var6 == null; ++var11)
            {
                Track var12 = (Track)var2.get(((var10 < 0 ? -1 : var10) + var11) % var2.size());

                if (var12.isReady())
                {
                    var6 = var12;
                }
            }
        }

        return var6 != null && play(mc, var6);
    }

    /** Goes back to the song before (or to the start of this one, after its first 3 seconds). */
    public static void playPrevious(Minecraft mc)
    {
        if (current != null && elapsedMs > 3000.0D || history.isEmpty())
        {
            if (current != null)
            {
                play(mc, current, false);
            }
            else if (!history.isEmpty())
            {
                play(mc, (Track)history.removeLast(), false);
            }

            return;
        }

        Track var1 = (Track)history.removeLast();

        if (var1 == current && !history.isEmpty())
        {
            var1 = (Track)history.removeLast();
        }

        play(mc, var1, false);
    }

    public static boolean play(Minecraft mc, Track track)
    {
        return play(mc, track, true);
    }

    private static boolean play(Minecraft mc, Track track, boolean remember)
    {
        SoundSystem var3 = getSoundSystem(mc);

        if (var3 == null)
        {
            setMessage("Sound is off (no sound system)");
            return false;
        }

        if (!track.isReady())
        {
            prioritize(track);
            setMessage(track.state == NO_FFMPEG ? "Needs ffmpeg to play " + extension(track.source).toUpperCase() + " files" : track.title + " is still converting");
            return false;
        }

        if (mc.gameSettings.musicVolume <= 0.0F)
        {
            setMessage("The music volume is off (Options > Music)");
            return false;
        }

        try
        {
            if (var3.playing("BgMusic") || paused)
            {
                var3.stop("BgMusic");
            }

            if (track.source != null)
            {
                File var4 = track.playable;
                var3.backgroundMusic("BgMusic", var4.toURI().toURL(), var4.getName(), false);
            }
            else
            {
                var3.backgroundMusic("BgMusic", track.vanillaUrl, track.vanillaName, false);
            }

            var3.setVolume("BgMusic", mc.gameSettings.musicVolume);
            var3.play("BgMusic");
        }
        catch (Exception var5)
        {
            KirUtil.log("Could not play " + track.title, var5);
            setMessage("Could not play " + track.title);
            return false;
        }

        if (remember)
        {
            history.remove(track);
            history.addLast(track);

            while (history.size() > 100)
            {
                history.removeFirst();
            }
        }

        current = track;
        paused = false;
        waitingStart = true;
        startedAt = System.currentTimeMillis();
        elapsedMs = 0.0D;
        readDuration(track);
        return true;
    }

    public static void togglePause(Minecraft mc)
    {
        SoundSystem var1 = getSoundSystem(mc);

        if (var1 == null)
        {
            return;
        }

        if (current == null)
        {
            playNext(mc, true);
        }
        else if (paused)
        {
            var1.play("BgMusic");
            paused = false;
            waitingStart = true;
            startedAt = System.currentTimeMillis();
        }
        else
        {
            var1.pause("BgMusic");
            paused = true;
        }
    }

    /** Stops the song; the next one comes after Music Frequency's wait. */
    public static void stop(Minecraft mc)
    {
        SoundSystem var1 = getSoundSystem(mc);

        if (var1 != null)
        {
            var1.stop("BgMusic");
        }

        current = null;
        paused = false;
        waitingStart = false;
        countdownMs = (double)getMusicDelayTicks() * 50.0D;
    }

    public static Track getCurrent()
    {
        return current;
    }

    public static boolean isPaused()
    {
        return paused;
    }

    public static long getElapsed()
    {
        return (long)elapsedMs;
    }

    /** Milliseconds until the next song starts by itself, or -1 while a song plays or nothing counts down. */
    public static long getCountdown()
    {
        return current != null || countdownMs < 0.0D ? -1L : (long)countdownMs;
    }

    public static String getMessage()
    {
        return message != null && System.currentTimeMillis() < messageUntil ? message : null;
    }

    private static void setMessage(String text)
    {
        message = text;
        messageUntil = System.currentTimeMillis() + 5000L;
    }

    /* ------------------------------------------------------------------ Durations */

    private static void readDuration(final Track track)
    {
        if (track.durationRead)
        {
            return;
        }

        track.durationRead = true;
        Thread var1 = new Thread("Kirmod music length")
        {
            public void run()
            {
                track.durationMs = KirMusic.readDuration(track.source != null ? track.playable : urlFile(track.vanillaUrl));
            }
        };
        var1.setDaemon(true);
        var1.start();
    }

    /** Reads the lengths of a soundtrack's tracks in the background, for the Music screen. */
    public static void readDurations(final List tracks)
    {
        Thread var1 = new Thread("Kirmod music lengths")
        {
            public void run()
            {
                for (int var1 = 0; var1 < tracks.size(); ++var1)
                {
                    Track var2 = (Track)tracks.get(var1);

                    if (!var2.durationRead && var2.isReady())
                    {
                        var2.durationRead = true;
                        var2.durationMs = KirMusic.readDuration(var2.source != null ? var2.playable : urlFile(var2.vanillaUrl));
                    }
                }
            }
        };
        var1.setDaemon(true);
        var1.start();
    }

    private static File urlFile(URL url)
    {
        try
        {
            return url != null && "file".equals(url.getProtocol()) ? new File(url.toURI()) : null;
        }
        catch (Exception var2)
        {
            return null;
        }
    }

    /** The length of an Ogg Vorbis file (last page's granule position / sample rate) or a WAV; -1 otherwise. */
    static long readDuration(File file)
    {
        if (file == null || !file.isFile())
        {
            return -1L;
        }

        try
        {
            RandomAccessFile var1 = new RandomAccessFile(file, "r");

            try
            {
                byte[] var2 = new byte[(int)Math.min(4096L, var1.length())];
                var1.readFully(var2);
                String var3 = extension(file);

                if (var3.equals("wav") && var2.length >= 44)
                {
                    long var4 = le(var2, 28, 4);
                    return var4 > 0L ? (var1.length() - 44L) * 1000L / var4 : -1L;
                }

                if (!var3.equals("ogg") || var2.length < 64 || var2[0] != 'O' || var2[1] != 'g' || var2[2] != 'g' || var2[3] != 'S')
                {
                    return -1L;
                }

                int var5 = 27 + (var2[26] & 255);

                if (var5 + 16 > var2.length || var2[var5] != 1)
                {
                    return -1L;
                }

                long var6 = le(var2, var5 + 12, 4);
                int var8 = (int)Math.min(65536L, var1.length());
                byte[] var9 = new byte[var8];
                var1.seek(var1.length() - (long)var8);
                var1.readFully(var9);

                for (int var10 = var8 - 14; var10 >= 0; --var10)
                {
                    if (var9[var10] == 'O' && var9[var10 + 1] == 'g' && var9[var10 + 2] == 'g' && var9[var10 + 3] == 'S')
                    {
                        long var11 = le(var9, var10 + 6, 8);
                        return var6 > 0L && var11 > 0L ? var11 * 1000L / var6 : -1L;
                    }
                }
            }
            finally
            {
                var1.close();
            }
        }
        catch (IOException var15)
        {
            ;
        }

        return -1L;
    }

    private static long le(byte[] b, int off, int n)
    {
        long var3 = 0L;

        for (int var5 = n - 1; var5 >= 0; --var5)
        {
            var3 = var3 << 8 | (long)(b[off + var5] & 255);
        }

        return var3;
    }

    /* ------------------------------------------------------------------ Converter */

    static File getConvertedFile(File source)
    {
        File var1 = new File(getDir(), ".converted");
        String var2 = source.getParentFile() == null ? "" : source.getParentFile().getName();
        String var3 = Long.toHexString((source.getAbsolutePath() + "|" + source.length() + "|" + source.lastModified()).hashCode() & 0xFFFFFFFFL);
        String var4 = (var2 + " - " + baseName(source.getName())).replaceAll("[^A-Za-z0-9 ._()-]", "_");

        if (var4.length() > 80)
        {
            var4 = var4.substring(0, 80);
        }

        return new File(var1, var4 + " " + var3 + ".ogg");
    }

    /** ffmpeg, or null if none was found. */
    public static File getFfmpeg()
    {
        if (!ffmpegSearched)
        {
            ffmpegSearched = true;
            ffmpeg = findFfmpeg();
            KirUtil.log(ffmpeg != null ? "Converting music with " + ffmpeg : "No ffmpeg found; only Ogg Vorbis and WAV music can play", (Throwable)null);
        }

        return ffmpeg;
    }

    public static boolean hasFfmpeg()
    {
        return getFfmpeg() != null;
    }

    private static File findFfmpeg()
    {
        boolean var0 = System.getProperty("os.name", "").toLowerCase().indexOf("win") >= 0;
        String var1 = var0 ? "ffmpeg.exe" : "ffmpeg";
        ArrayList var2 = new ArrayList();
        String var3 = System.getProperty("kirmod.ffmpeg");

        if (var3 != null && var3.length() > 0)
        {
            var2.add(new File(var3));
        }

        var2.add(new File(getDir(), var1));
        var2.add(new File(new File(getDir(), "ffmpeg"), var1));
        var2.add(new File(new File(new File(getDir(), "ffmpeg"), "bin"), var1));
        String var4 = System.getenv("PATH");

        if (var4 != null)
        {
            String[] var5 = var4.split(File.pathSeparator);

            for (int var6 = 0; var6 < var5.length; ++var6)
            {
                if (var5[var6].length() > 0)
                {
                    var2.add(new File(var5[var6], var1));
                }
            }
        }

        String[] var7 = {"/usr/bin", "/usr/local/bin", "/opt/homebrew/bin", "/opt/local/bin", "/snap/bin", "C:\\ffmpeg\\bin", "C:\\Program Files\\ffmpeg\\bin"};

        for (int var8 = 0; var8 < var7.length; ++var8)
        {
            var2.add(new File(var7[var8], var1));
        }

        for (int var9 = 0; var9 < var2.size(); ++var9)
        {
            File var10 = (File)var2.get(var9);

            if (var10.isFile())
            {
                return var10;
            }
        }

        return null;
    }

    /** How many tracks are waiting for or in conversion. */
    public static int getConvertingCount()
    {
        int var0 = 0;
        Track[] var1 = convertQueue;

        for (int var2 = 0; var2 < var1.length; ++var2)
        {
            if (var1[var2].state == WAITING || var1[var2].state == CONVERTING)
            {
                ++var0;
            }
        }

        return var0;
    }

    /** Converts the chosen soundtrack's tracks first, then the playlist's others, then everything else. */
    private static void updateConvertQueue()
    {
        ArrayList var0 = new ArrayList();
        Soundtrack var1 = getSelected();

        if (var1 != null)
        {
            var0.addAll(getMusicPlaylist());
            var0.addAll(var1.tracks);
            var0.addAll(getDimensionTracks(ROLE_NETHER));
            var0.addAll(getDimensionTracks(ROLE_END));
        }

        for (int var2 = 0; var2 < soundtracks.size(); ++var2)
        {
            var0.addAll(((Soundtrack)soundtracks.get(var2)).tracks);
        }

        ArrayList var3 = new ArrayList();

        for (int var4 = 0; var4 < var0.size(); ++var4)
        {
            Track var5 = (Track)var0.get(var4);

            if (var5.source != null && (var5.state == WAITING || var5.state == CONVERTING) && !var3.contains(var5))
            {
                var3.add(var5);
            }
        }

        synchronized (convertLock)
        {
            convertQueue = (Track[])var3.toArray(new Track[var3.size()]);
            convertLock.notifyAll();
        }

        if (var3.isEmpty())
        {
            return;
        }

        if (!hasFfmpeg())
        {
            for (int var6 = 0; var6 < var3.size(); ++var6)
            {
                ((Track)var3.get(var6)).state = NO_FFMPEG;
            }

            return;
        }

        if (converter == null || !converter.isAlive())
        {
            converter = new Thread("Kirmod music converter")
            {
                public void run()
                {
                    KirMusic.runConverter();
                }
            };
            converter.setDaemon(true);
            converter.setPriority(Thread.MIN_PRIORITY);
            converter.start();
        }
    }

    /** Moves a track to the front of the conversion queue. */
    static void prioritize(Track track)
    {
        if (track.state != WAITING)
        {
            return;
        }

        synchronized (convertLock)
        {
            ArrayList var1 = new ArrayList(Arrays.asList(convertQueue));
            var1.remove(track);
            var1.add(0, track);
            convertQueue = (Track[])var1.toArray(new Track[var1.size()]);
            convertLock.notifyAll();
        }
    }

    static void runConverter()
    {
        while (true)
        {
            Track var0 = null;

            synchronized (convertLock)
            {
                Track[] var1 = convertQueue;

                for (int var2 = 0; var2 < var1.length && var0 == null; ++var2)
                {
                    if (var1[var2].state == WAITING)
                    {
                        var0 = var1[var2];
                    }
                }

                if (var0 == null)
                {
                    try
                    {
                        convertLock.wait(30000L);
                    }
                    catch (InterruptedException var7)
                    {
                        return;
                    }

                    continue;
                }

                var0.state = CONVERTING;
            }

            convert(var0);
        }
    }

    private static void convert(Track track)
    {
        File var1 = getConvertedFile(track.source);
        File var2 = new File(var1.getParentFile(), var1.getName() + ".part.ogg");
        var1.getParentFile().mkdirs();
        long var3 = System.currentTimeMillis();

        try
        {
            ProcessBuilder var5 = new ProcessBuilder(new String[] {getFfmpeg().getAbsolutePath(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", track.source.getAbsolutePath(), "-vn", "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", "5", var2.getAbsolutePath()});
            var5.redirectErrorStream(true);
            Process var6 = var5.start();
            InputStream var7 = var6.getInputStream();
            StringBuilder var8 = new StringBuilder();
            byte[] var9 = new byte[1024];
            int var10;

            while ((var10 = var7.read(var9)) > 0)
            {
                if (var8.length() < 2000)
                {
                    var8.append(new String(var9, 0, var10));
                }
            }

            int var11 = var6.waitFor();

            if (var11 == 0 && var2.isFile() && var2.length() > 0L && (var1.delete() || !var1.exists()) && var2.renameTo(var1))
            {
                track.playable = var1;
                track.state = READY;
                KirUtil.log("Converted " + track.source.getName() + " in " + (System.currentTimeMillis() - var3) + " ms", (Throwable)null);
                return;
            }

            KirUtil.log("ffmpeg could not convert " + track.source + " (exit " + var11 + "): " + var8.toString().trim(), (Throwable)null);
        }
        catch (Exception var12)
        {
            KirUtil.log("Could not convert " + track.source, var12);
        }

        var2.delete();
        track.state = FAILED;
    }

    /** Sorts file names with their numbers in number order: "2 B" before "10 A". */
    static class NaturalOrder implements Comparator
    {
        public int compare(Object a, Object b)
        {
            String var3 = ((File)a).getName().toLowerCase();
            String var4 = ((File)b).getName().toLowerCase();
            int var5 = 0;
            int var6 = 0;

            while (var5 < var3.length() && var6 < var4.length())
            {
                char var7 = var3.charAt(var5);
                char var8 = var4.charAt(var6);

                if (Character.isDigit(var7) && Character.isDigit(var8))
                {
                    int var9 = var5;
                    int var10 = var6;

                    while (var5 < var3.length() && Character.isDigit(var3.charAt(var5)))
                    {
                        ++var5;
                    }

                    while (var6 < var4.length() && Character.isDigit(var4.charAt(var6)))
                    {
                        ++var6;
                    }

                    long var11 = Long.parseLong(var3.substring(var9, Math.min(var5, var9 + 18)));
                    long var13 = Long.parseLong(var4.substring(var10, Math.min(var6, var10 + 18)));

                    if (var11 != var13)
                    {
                        return var11 < var13 ? -1 : 1;
                    }
                }
                else
                {
                    if (var7 != var8)
                    {
                        return var7 - var8;
                    }

                    ++var5;
                    ++var6;
                }
            }

            return (var3.length() - var5) - (var4.length() - var6);
        }
    }
}
