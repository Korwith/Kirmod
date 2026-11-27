package net.minecraft.src;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * World Backups (1.38): zips the singleplayer world into .minecraft/backups/WORLD/, on a timer (Auto Backup,
 * KirModuleBackups) or from the Backups tab's "Backup Now", and restores a backup as a new world.
 *
 * A backup is made the way a server admin would with /save-off and /save-all:
 * 1. On the integrated server's thread (from KirModuleBackups.onServerWorldTickEnd, through KirWorldHook) the
 *    players and every dimension are saved, then each WorldServer gets canNotSave, so neither the autosave
 *    nor chunk unloading writes to the region files while they are read.
 * 2. On a background thread, once the File IO thread has written what was queued, the world folder is copied
 *    to a staging folder next to the backups. That is only a file copy, so saving is off for as short as can be.
 *    Saving goes back on as soon as the copy is done, or straight away if the server starts to stop meanwhile
 *    (and if it stopped while saving was off, its worlds are saved again here, since its own save was skipped).
 * 3. The staged copy is zipped into WORLD/yyyy-MM-dd_HH-mm-ss_auto.zip (or _manual) and deleted. Then the
 *    oldest automatic backups past Keep Backups (KirModuleBackupLimit) are deleted; manual ones are kept.
 *
 * Progress is in the volatile fields below, read by the Backups tab (KirModuleBackups.drawPreview), the
 * backup browser (GuiKirmodBackups) and the HUD icon (KirBackupHud).
 */
public class KirBackups
{
    public static final int IDLE = 0;
    public static final int SAVING = 1;
    public static final int COPYING = 2;
    public static final int ZIPPING = 3;
    public static final int RESTORING = 4;
    public static final int DONE = 5;
    public static final int FAILED = 6;

    public static final String BACKUPS_DIR_NAME = "backups";
    private static final String AUTO_SUFFIX = "_auto";
    private static final String MANUAL_SUFFIX = "_manual";
    private static final String STAGING_PREFIX = ".staging-";
    private static final String PART_SUFFIX = ".part";

    /** Shares of the bar each step gets: saving is quick, copying quicker than zipping. */
    private static final float SAVE_SHARE = 0.05F;
    private static final float COPY_SHARE = 0.30F;

    private static final Object lock = new Object();

    private static volatile int state = IDLE;
    private static volatile float progress;
    private static volatile String detail = "";
    private static volatile String result = "";
    private static volatile boolean automatic;
    private static volatile long startedAt;
    private static volatile long finishedAt;
    private static volatile long bytesDone;
    private static volatile long bytesTotal;
    private static volatile String worldName = "";
    private static volatile String worldFolder = "";

    /** The server whose save is waiting for its thread (step 1); set while state is SAVING. */
    private static IntegratedServer pendingServer;

    /** canNotSave of each of the server's worlds before the backup, to put back. */
    private static boolean[] savedFlags;

    /** The world played now, and since when (the auto backup timer counts from then or the last backup). */
    private static String joinedFolder;
    private static long joinedAt;

    /* ------------------------------------------------------------------ State, for the screens */

    public static int getState()
    {
        return state;
    }

    /** Whether a backup or restore is under way. */
    public static boolean isBusy()
    {
        int var0 = state;
        return var0 == SAVING || var0 == COPYING || var0 == ZIPPING || var0 == RESTORING;
    }

    /** 0 to 1 over the whole backup. */
    public static float getProgress()
    {
        return progress;
    }

    /** What is being done now: "Saving the world...", "Copying files (12/80)"... */
    public static String getDetail()
    {
        return detail;
    }

    /** How the last backup went: "Saved 12.4 MB in 3.2 s", or why it failed. */
    public static String getResult()
    {
        return result;
    }

    public static boolean isAutomatic()
    {
        return automatic;
    }

    public static long getStartedAt()
    {
        return startedAt;
    }

    public static long getFinishedAt()
    {
        return finishedAt;
    }

    public static long getBytesDone()
    {
        return bytesDone;
    }

    public static long getBytesTotal()
    {
        return bytesTotal;
    }

    /** The name of the world being (or last) backed up. */
    public static String getWorldName()
    {
        return worldName;
    }

    public static String getWorldFolder()
    {
        return worldFolder;
    }

    /** Bytes per second of the current step, or 0 while unknown. */
    public static long getSpeed()
    {
        long var0 = System.currentTimeMillis() - stepStartedAt;
        return var0 < 300L ? 0L : (bytesDone - stepStartBytes) * 1000L / var0;
    }

    private static volatile long stepStartedAt;
    private static volatile long stepStartBytes;

    /* ------------------------------------------------------------------ Files */

    /** .minecraft/backups */
    public static File getBackupsDir()
    {
        return new File(Minecraft.getMinecraftDir(), BACKUPS_DIR_NAME);
    }

    /** .minecraft/backups/FOLDER */
    public static File getWorldBackupDir(String folder)
    {
        return new File(getBackupsDir(), folder);
    }

    public static File getSavesDir()
    {
        return new File(Minecraft.getMinecraftDir(), "saves");
    }

    /** The world folders that have backups, by name. */
    public static List getBackedUpWorlds()
    {
        ArrayList var0 = new ArrayList();
        File[] var1 = getBackupsDir().listFiles();

        if (var1 != null)
        {
            for (int var2 = 0; var2 < var1.length; ++var2)
            {
                if (var1[var2].isDirectory() && !getBackups(var1[var2].getName()).isEmpty())
                {
                    var0.add(var1[var2].getName());
                }
            }
        }

        Collections.sort(var0, String.CASE_INSENSITIVE_ORDER);
        return var0;
    }

    /** A world's backups, newest first. */
    public static List getBackups(String folder)
    {
        ArrayList var1 = new ArrayList();
        File[] var2 = getWorldBackupDir(folder).listFiles();

        if (var2 != null)
        {
            for (int var3 = 0; var3 < var2.length; ++var3)
            {
                Backup var4 = Backup.of(folder, var2[var3]);

                if (var4 != null)
                {
                    var1.add(var4);
                }
            }
        }

        Collections.sort(var1, new Comparator()
        {
            public int compare(Object a, Object b)
            {
                long var3 = ((Backup)a).date - ((Backup)b).date;
                return var3 > 0L ? -1 : (var3 < 0L ? 1 : 0);
            }
        });
        return var1;
    }

    /** When a world was last backed up, or 0 if never. */
    public static long getLastBackupTime(String folder)
    {
        List var1 = getBackups(folder);
        return var1.isEmpty() ? 0L : ((Backup)var1.get(0)).date;
    }

    /** The name a world shows in the world list (from its level.dat), or its folder's name. */
    public static String getDisplayName(String folder)
    {
        File var1 = new File(new File(getSavesDir(), folder), "level.dat");

        if (var1.isFile())
        {
            try
            {
                FileInputStream var2 = new FileInputStream(var1);

                try
                {
                    String var3 = CompressedStreamTools.readCompressed(var2).getCompoundTag("Data").getString("LevelName");

                    if (var3 != null && var3.length() > 0)
                    {
                        return var3;
                    }
                }
                finally
                {
                    var2.close();
                }
            }
            catch (Throwable var7)
            {
                ;
            }
        }

        return folder;
    }

    /* ------------------------------------------------------------------ The world being played */

    /** The integrated server, if a singleplayer world is open; else null. */
    public static IntegratedServer getServer(Minecraft mc)
    {
        IntegratedServer var1 = mc.getIntegratedServer();
        return var1 != null && mc.theWorld != null && var1.theWorldServer != null && var1.isServerRunning() ? var1 : null;
    }

    /** Why "Backup Now" can't be used right now, or null if it can. */
    public static String whyNot(Minecraft mc)
    {
        if (isBusy())
        {
            return "A backup is already running.";
        }

        if (getServer(mc) == null)
        {
            return mc.theWorld != null ? "Only singleplayer worlds can be backed up." : "Open a singleplayer world to back it up.";
        }

        return !KirWorldHook.isAvailable() ? "Kirmod could not hook into the world tick." : null;
    }

    /** Keeps track of which world is played, for the auto backup timer. Every frame, main thread. */
    private static void trackWorld(Minecraft mc)
    {
        IntegratedServer var1 = getServer(mc);
        String var2 = var1 == null ? null : var1.getFolderName();

        if (var2 == null ? joinedFolder != null : !var2.equals(joinedFolder))
        {
            joinedFolder = var2;
            joinedAt = System.currentTimeMillis();
            lastBackupCache = var2 == null ? 0L : getLastBackupTime(var2);
        }
    }

    /** The last backup of the world played, cached (looked up when joining and after each backup). */
    private static long lastBackupCache;

    /**
     * When the next automatic backup is due, for an interval in milliseconds: an interval after joining the
     * world or after its last backup, whichever is later. 0 outside a world.
     */
    public static long getNextAutoBackup(long interval)
    {
        return joinedFolder == null ? 0L : Math.max(joinedAt, lastBackupCache) + interval;
    }

    /** The last backup of the world being played, or 0. */
    public static long getLastBackupOfCurrentWorld()
    {
        return joinedFolder == null ? 0L : lastBackupCache;
    }

    /* ------------------------------------------------------------------ Backing up */

    /**
     * Starts a backup of the singleplayer world. Returns false (and says why in getResult) if it can't.
     * Main thread.
     */
    public static boolean start(Minecraft mc, boolean auto)
    {
        synchronized (lock)
        {
            String var2 = whyNot(mc);

            if (var2 != null)
            {
                if (!isBusy())
                {
                    result = var2;
                }

                return false;
            }

            IntegratedServer var3 = getServer(mc);
            KirWorldHook.installAll(mc);
            pendingServer = var3;
            automatic = auto;
            worldFolder = var3.getFolderName();
            worldName = var3.getWorldName();
            startedAt = System.currentTimeMillis();
            finishedAt = 0L;
            bytesDone = 0L;
            bytesTotal = 0L;
            progress = 0.0F;
            detail = "Saving the world...";
            result = "";
            state = SAVING;
            return true;
        }
    }

    /**
     * Step 1, on the server thread, from each world's tick: saves the players and the worlds, turns saving off
     * and hands over to the backup thread.
     */
    static void onServerTick(World world)
    {
        if (state == SAVING && world instanceof WorldServer && pendingServer != null && MinecraftServer.getServer() == pendingServer)
        {
            beginSave(pendingServer);
        }
    }

    /** When the game was paused, or 0 while it isn't. */
    private static long pausedSince;

    /**
     * Every frame, main thread: keeps track of the world played, and starts a backup's first step here while
     * the game is paused (the pause menu, or Kirmod's screen opened from it). A paused integrated server
     * doesn't tick its worlds, so the server-thread hook would wait until the game goes on; it saved everything
     * when it paused and stays idle until the main thread unpauses it, so it is safe to go on from here once it
     * has been paused for a few of its ticks.
     */
    static void tick(Minecraft mc)
    {
        trackWorld(mc);
        long var1 = System.currentTimeMillis();

        if (!mc.isGamePaused)
        {
            pausedSince = 0L;
        }
        else if (pausedSince == 0L)
        {
            pausedSince = var1;
        }

        IntegratedServer var3 = pendingServer;

        if (state != SAVING || var3 == null)
        {
            return;
        }

        if (!var3.isServerRunning() || var3 != mc.getIntegratedServer())
        {
            synchronized (lock)
            {
                if (pendingServer == var3)
                {
                    pendingServer = null;
                    fail("Backup failed: the world was closed before it started", (Throwable)null);
                }
            }
        }
        else if (pausedSince != 0L && var1 - pausedSince > 250L)
        {
            beginSave(var3);
        }
    }

    /** Step 1: saves the players and the worlds, turns saving off and starts the backup thread. */
    private static void beginSave(IntegratedServer server)
    {
        IntegratedServer var1;
        WorldServer[] var2;

        synchronized (lock)
        {
            var1 = pendingServer;

            if (state != SAVING || var1 == null || var1 != server)
            {
                return;
            }

            pendingServer = null;
            var2 = var1.theWorldServer;
            savedFlags = new boolean[var2.length];

            try
            {
                if (var1.getConfigurationManager() != null)
                {
                    var1.getConfigurationManager().saveAllPlayerData();
                }

                for (int var3 = 0; var3 < var2.length; ++var3)
                {
                    if (var2[var3] != null)
                    {
                        savedFlags[var3] = var2[var3].canNotSave;
                        var2[var3].canNotSave = false;
                        var2[var3].saveAllChunks(true, (IProgressUpdate)null);
                        var2[var3].canNotSave = true;
                    }
                }
            }
            catch (Throwable var6)
            {
                releaseSaving(var1, var2);
                fail("Could not save the world: " + var6, var6);
                return;
            }

            progress = SAVE_SHARE;
            state = COPYING;
            detail = "Copying files...";
        }

        final IntegratedServer var7 = var1;
        final WorldServer[] var8 = var2;
        Thread var9 = new Thread("Kirmod Backup")
        {
            public void run()
            {
                runBackup(var7, var8);
            }
        };
        var9.setDaemon(false);
        var9.setPriority(Thread.MIN_PRIORITY + 1);
        var9.start();
    }

    /** Steps 2 and 3, on the backup thread. */
    private static void runBackup(IntegratedServer server, WorldServer[] worlds)
    {
        String var2 = worldFolder;
        File var3 = new File(getSavesDir(), var2);
        File var4 = getWorldBackupDir(var2);
        String var5 = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date(startedAt));
        File var6 = new File(var4, STAGING_PREFIX + var5);
        File var7 = new File(var4, var5 + (automatic ? AUTO_SUFFIX : MANUAL_SUFFIX) + ".zip");
        File var8 = new File(var4, var7.getName() + PART_SUFFIX);
        boolean var9 = false;

        try
        {
            try
            {
                ThreadedFileIOBase.threadedIOInstance.waitForFinish();

                if (!var3.isDirectory())
                {
                    throw new IOException("the world folder " + var3 + " is missing");
                }

                // Step 2: copy the world while saving is off.
                ArrayList var10 = new ArrayList();
                listFiles(var3, "", var10);
                long var11 = 0L;

                for (int var13 = 0; var13 < var10.size(); ++var13)
                {
                    var11 += new File(var3, (String)var10.get(var13)).length();
                }

                bytesTotal = var11;
                beginStep();
                deleteTree(var6);

                for (int var14 = 0; var14 < var10.size(); ++var14)
                {
                    if (!server.isServerRunning())
                    {
                        // The world is closing: let it save now, and back up what was copied so far no more.
                        throw new IOException("the world was closed during the backup");
                    }

                    String var15 = (String)var10.get(var14);
                    detail = "Copying files (" + (var14 + 1) + "/" + var10.size() + ")";
                    copyFile(new File(var3, var15), new File(var6, var15), COPY_SHARE);
                }
            }
            finally
            {
                var9 = releaseSaving(server, worlds);
            }

            // Step 3: zip the copy, with saving back on.
            state = ZIPPING;
            progress = SAVE_SHARE + COPY_SHARE;
            bytesDone = 0L;
            beginStep();
            ArrayList var16 = new ArrayList();
            listFiles(var6, "", var16);
            ZipOutputStream var17 = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(var8), 65536));

            try
            {
                // Region files are compressed already; the fastest level loses little and keeps the CPU free.
                var17.setLevel(Deflater.BEST_SPEED);
                var17.setComment("Kirmod backup of \"" + worldName + "\" (" + (automatic ? "automatic" : "manual") + ")");

                for (int var18 = 0; var18 < var16.size(); ++var18)
                {
                    String var19 = (String)var16.get(var18);
                    detail = "Compressing (" + (var18 + 1) + "/" + var16.size() + ")";
                    File var20 = new File(var6, var19);
                    ZipEntry var21 = new ZipEntry(var2 + "/" + var19);
                    var21.setTime(var20.lastModified());
                    var17.putNextEntry(var21);
                    pump(var20, var17, 1.0F - SAVE_SHARE - COPY_SHARE);
                    var17.closeEntry();
                }
            }
            finally
            {
                var17.close();
            }

            if (!var8.renameTo(var7))
            {
                throw new IOException("could not rename " + var8.getName());
            }

            deleteTree(var6);
            int var22 = pruneAutoBackups(var2);
            long var23 = System.currentTimeMillis() - startedAt;
            result = "Saved " + formatSize(var7.length()) + " in " + formatDuration(var23) + (var22 > 0 ? ", removed " + var22 + " old" : "");
            progress = 1.0F;
            detail = "Backup complete";
            finishedAt = System.currentTimeMillis();

            if (var2.equals(joinedFolder))
            {
                lastBackupCache = startedAt;
            }

            state = DONE;
            KirUtil.log("Backed up \"" + worldName + "\" to " + var7, (Throwable)null);
        }
        catch (Throwable var27)
        {
            var8.delete();
            deleteTree(var6);
            fail("Backup failed: " + (var27.getMessage() != null ? var27.getMessage() : var27.toString()), var27);
        }
        finally
        {
            if (var9)
            {
                saveStoppedServer(server, worlds);
            }
        }
    }

    /**
     * Puts each world's canNotSave back. Returns true if the server stopped while saving was off: its own
     * save then skipped the chunks, so they have to be saved once it has finished stopping.
     */
    private static boolean releaseSaving(IntegratedServer server, WorldServer[] worlds)
    {
        boolean[] var2 = savedFlags;

        for (int var3 = 0; var3 < worlds.length; ++var3)
        {
            if (worlds[var3] != null)
            {
                worlds[var3].canNotSave = var2 != null && var3 < var2.length && var2[var3];
            }
        }

        savedFlags = null;
        return !server.isServerRunning();
    }

    /** Saves a server's worlds after it has stopped (see releaseSaving); its thread is gone by then. */
    private static void saveStoppedServer(IntegratedServer server, WorldServer[] worlds)
    {
        long var2 = System.currentTimeMillis() + 30000L;

        while (!server.isServerStopped() && System.currentTimeMillis() < var2)
        {
            try
            {
                Thread.sleep(20L);
            }
            catch (InterruptedException var7)
            {
                return;
            }
        }

        if (!server.isServerStopped())
        {
            return;
        }

        for (int var4 = 0; var4 < worlds.length; ++var4)
        {
            if (worlds[var4] != null)
            {
                try
                {
                    worlds[var4].saveAllChunks(true, (IProgressUpdate)null);
                    worlds[var4].flush();
                }
                catch (Throwable var6)
                {
                    KirUtil.log("Could not save " + worlds[var4].getWorldInfo().getWorldName() + " after the backup", var6);
                }
            }
        }
    }

    private static void fail(String message, Throwable error)
    {
        result = message;
        detail = "Backup failed";
        finishedAt = System.currentTimeMillis();
        state = FAILED;
        KirUtil.log(message, error);
    }

    /** Deletes the oldest automatic backups of a world past Keep Backups; returns how many. */
    private static int pruneAutoBackups(String folder)
    {
        int var1 = mod_Kirmod.BACKUP_LIMIT.getLimit();

        if (var1 <= 0)
        {
            return 0;
        }

        List var2 = getBackups(folder);
        int var3 = 0;
        int var4 = 0;

        for (int var5 = 0; var5 < var2.size(); ++var5)
        {
            Backup var6 = (Backup)var2.get(var5);

            if (var6.automatic && ++var3 > var1 && var6.file.delete())
            {
                ++var4;
            }
        }

        return var4;
    }

    /* ------------------------------------------------------------------ Restoring */

    /**
     * Unzips a backup into a new world folder next to the others ("FOLDER-restored", numbered if taken),
     * named "NAME (Backup DATE)", so nothing is overwritten. On a background thread; progress as for a backup.
     */
    public static boolean restore(final Backup backup)
    {
        synchronized (lock)
        {
            if (isBusy())
            {
                return false;
            }

            automatic = false;
            worldFolder = backup.folder;
            worldName = getDisplayName(backup.folder);
            startedAt = System.currentTimeMillis();
            finishedAt = 0L;
            progress = 0.0F;
            bytesDone = 0L;
            bytesTotal = backup.size;
            detail = "Restoring...";
            result = "";
            state = RESTORING;
        }

        Thread var1 = new Thread("Kirmod Restore")
        {
            public void run()
            {
                runRestore(backup);
            }
        };
        var1.setPriority(Thread.MIN_PRIORITY + 1);
        var1.start();
        return true;
    }

    private static void runRestore(Backup backup)
    {
        File var1 = getSavesDir();
        String var2 = backup.folder + "-restored";
        File var3 = new File(var1, var2);

        for (int var4 = 2; var3.exists(); ++var4)
        {
            var3 = new File(var1, var2 + "-" + var4);
        }

        try
        {
            ZipFile var5 = new ZipFile(backup.file);

            try
            {
                long var6 = 0L;
                ArrayList var8 = Collections.list(var5.entries());

                for (int var9 = 0; var9 < var8.size(); ++var9)
                {
                    var6 += Math.max(0L, ((ZipEntry)var8.get(var9)).getSize());
                }

                bytesTotal = var6;
                beginStep();
                String var10 = var3.getCanonicalPath() + File.separator;

                for (int var11 = 0; var11 < var8.size(); ++var11)
                {
                    ZipEntry var12 = (ZipEntry)var8.get(var11);
                    String var13 = var12.getName();
                    // Entries are FOLDER/...: drop the first part, so the copy goes in its own folder.
                    int var14 = var13.indexOf('/');
                    String var15 = var14 >= 0 ? var13.substring(var14 + 1) : var13;

                    if (var15.length() == 0 || var12.isDirectory())
                    {
                        continue;
                    }

                    File var16 = new File(var3, var15);

                    if (!var16.getCanonicalPath().startsWith(var10))
                    {
                        throw new IOException("the backup has a file outside its world: " + var13);
                    }

                    detail = "Restoring (" + (var11 + 1) + "/" + var8.size() + ")";
                    var16.getParentFile().mkdirs();
                    InputStream var17 = var5.getInputStream(var12);

                    try
                    {
                        OutputStream var18 = new BufferedOutputStream(new FileOutputStream(var16), 65536);

                        try
                        {
                            pump(var17, var18, 0.95F);
                        }
                        finally
                        {
                            var18.close();
                        }
                    }
                    finally
                    {
                        var17.close();
                    }
                }
            }
            finally
            {
                var5.close();
            }

            // A name of its own in the world list.
            File var19 = new File(var3, "level.dat");
            String var20 = worldName + " (Backup " + new SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(new Date(backup.date)) + ")";

            if (var19.isFile())
            {
                FileInputStream var21 = new FileInputStream(var19);
                NBTTagCompound var22;

                try
                {
                    var22 = CompressedStreamTools.readCompressed(var21);
                }
                finally
                {
                    var21.close();
                }

                var22.getCompoundTag("Data").setString("LevelName", var20);
                FileOutputStream var23 = new FileOutputStream(var19);

                try
                {
                    CompressedStreamTools.writeCompressed(var22, var23);
                }
                finally
                {
                    var23.close();
                }
            }

            progress = 1.0F;
            detail = "Restore complete";
            result = "Restored as \"" + var20 + "\" in your world list";
            finishedAt = System.currentTimeMillis();
            state = DONE;
        }
        catch (Throwable var27)
        {
            deleteTree(var3);
            fail("Restore failed: " + (var27.getMessage() != null ? var27.getMessage() : var27.toString()), var27);
        }
    }

    /* ------------------------------------------------------------------ Helpers */

    private static void beginStep()
    {
        stepStartedAt = System.currentTimeMillis();
        stepStartBytes = bytesDone;
    }

    /** Every file under dir (session.lock left out), as paths relative to it with '/'. */
    private static void listFiles(File dir, String prefix, List out)
    {
        File[] var3 = dir.listFiles();

        if (var3 == null)
        {
            return;
        }

        Arrays.sort(var3);

        for (int var4 = 0; var4 < var3.length; ++var4)
        {
            String var5 = prefix + var3[var4].getName();

            if (var3[var4].isDirectory())
            {
                listFiles(var3[var4], var5 + "/", out);
            }
            else if (!var5.equals("session.lock"))
            {
                out.add(var5);
            }
        }
    }

    private static void copyFile(File from, File to, float share) throws IOException
    {
        to.getParentFile().mkdirs();
        OutputStream var3 = new BufferedOutputStream(new FileOutputStream(to), 65536);

        try
        {
            pump(from, var3, share);
        }
        finally
        {
            var3.close();
        }

        to.setLastModified(from.lastModified());
    }

    private static void pump(File from, OutputStream to, float share) throws IOException
    {
        InputStream var3 = new BufferedInputStream(new FileInputStream(from), 65536);

        try
        {
            pump(var3, to, share);
        }
        finally
        {
            var3.close();
        }
    }

    /** Copies a stream, moving bytesDone and the progress (this step's share of the bar) along. */
    private static void pump(InputStream from, OutputStream to, float share) throws IOException
    {
        byte[] var3 = new byte[65536];
        float var4 = state == RESTORING ? 0.0F : (state == ZIPPING ? SAVE_SHARE + COPY_SHARE : SAVE_SHARE);
        int var5;

        while ((var5 = from.read(var3)) > 0)
        {
            to.write(var3, 0, var5);
            bytesDone += (long)var5;

            if (bytesTotal > 0L)
            {
                progress = var4 + share * Math.min(1.0F, (float)((double)bytesDone / (double)bytesTotal));
            }
        }
    }

    static void deleteTree(File file)
    {
        File[] var1 = file.listFiles();

        if (var1 != null)
        {
            for (int var2 = 0; var2 < var1.length; ++var2)
            {
                deleteTree(var1[var2]);
            }
        }

        file.delete();
    }

    /** "12.4 MB" */
    public static String formatSize(long bytes)
    {
        if (bytes < 1024L)
        {
            return bytes + " B";
        }

        if (bytes < 1048576L)
        {
            return String.format(Locale.ROOT, "%.1f KB", new Object[] {Double.valueOf((double)bytes / 1024.0D)});
        }

        if (bytes < 1073741824L)
        {
            return String.format(Locale.ROOT, "%.1f MB", new Object[] {Double.valueOf((double)bytes / 1048576.0D)});
        }

        return String.format(Locale.ROOT, "%.2f GB", new Object[] {Double.valueOf((double)bytes / 1073741824.0D)});
    }

    /** "3.2 s", "1 min 5 s" */
    public static String formatDuration(long ms)
    {
        if (ms < 60000L)
        {
            return String.format(Locale.ROOT, "%.1f s", new Object[] {Double.valueOf((double)ms / 1000.0D)});
        }

        return ms / 60000L + " min " + ms / 1000L % 60L + " s";
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago" */
    public static String formatAgo(long time)
    {
        long var2 = Math.max(0L, System.currentTimeMillis() - time) / 1000L;

        if (var2 < 60L)
        {
            return "just now";
        }

        if (var2 < 3600L)
        {
            return var2 / 60L + " min ago";
        }

        if (var2 < 86400L)
        {
            return var2 / 3600L + " h ago";
        }

        long var4 = var2 / 86400L;
        return var4 == 1L ? "yesterday" : var4 + " days ago";
    }

    /** "4:05" or "1:02:03" for a countdown. */
    public static String formatCountdown(long ms)
    {
        long var2 = Math.max(0L, (ms + 999L) / 1000L);
        long var4 = var2 / 3600L;
        long var6 = var2 / 60L % 60L;
        long var8 = var2 % 60L;
        return var4 > 0L ? var4 + ":" + (var6 < 10L ? "0" : "") + var6 + ":" + (var8 < 10L ? "0" : "") + var8 : var6 + ":" + (var8 < 10L ? "0" : "") + var8;
    }

    /** One backup zip: .minecraft/backups/FOLDER/yyyy-MM-dd_HH-mm-ss_auto.zip (or _manual). */
    public static class Backup
    {
        public final String folder;
        public final File file;
        public final long date;
        public final long size;
        public final boolean automatic;

        private Backup(String folder, File file, long date, long size, boolean automatic)
        {
            this.folder = folder;
            this.file = file;
            this.date = date;
            this.size = size;
            this.automatic = automatic;
        }

        static Backup of(String folder, File file)
        {
            String var2 = file.getName();

            if (!file.isFile() || !var2.endsWith(".zip"))
            {
                return null;
            }

            String var3 = var2.substring(0, var2.length() - 4);
            boolean var4 = var3.endsWith(AUTO_SUFFIX);

            if (var4)
            {
                var3 = var3.substring(0, var3.length() - AUTO_SUFFIX.length());
            }
            else if (var3.endsWith(MANUAL_SUFFIX))
            {
                var3 = var3.substring(0, var3.length() - MANUAL_SUFFIX.length());
            }

            long var5;

            try
            {
                var5 = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).parse(var3).getTime();
            }
            catch (Exception var8)
            {
                // A zip put there by hand: its file date.
                var5 = file.lastModified();
            }

            return new Backup(folder, file, var5, file.length(), var4);
        }
    }
}
