package net.minecraft.src;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import org.lwjgl.Sys;

/**
 * Capes: draws a cape of the player's choice on their own player. It is client-side only, so other
 * players (and servers) see nothing of it.
 *
 * Capes come from four places:
 *   - owned capes: the capes of the logged-in Microsoft account, fetched from the Minecraft services
 *     API with the launcher's access token (see fetchAccountCapes())
 *   - the gallery: PNGs bundled with the mod in kirmod/capes/, listed by category in
 *     kirmod/capes/index.txt (lib/kirmod/capes/ in MCP)
 *   - the cape folder: PNGs the player puts in .minecraft/capes/, like texture packs
 *   - a custom file anywhere on disk, chosen by path
 *
 * The choice is saved in Kirmod.cfg as "cape" ("default", "none", "account:CAPE_ID", "gallery:NAME.png",
 * "owned:NAME.png" for the cape folder, or "file:PATH"). Options > Kirmod... > Capes... (GuiKirmodCapes) or the /cape command change it.
 * "default", used until the player picks something, overrides nothing: the player wears the cape that
 * is active on their Microsoft account, or, before that is fetched (or when offline), whatever cape
 * vanilla would draw. "none" hides the cape.
 *
 * Vanilla draws a player's cape from RenderEngine's downloaded-image map, keyed by playerCloakUrl. The
 * chosen cape goes into that map under a key of our own, with an image that is already loaded, and the
 * player's playerCloakUrl is pointed at it every frame.
 */
public class KirCapes
{
    public static final String CONFIG_KEY = "cape";
    public static final String NONE = "none";
    public static final String DEFAULT = "default";
    public static final String GALLERY_DIR = "/kirmod/capes/";
    public static final String GALLERY_INDEX = GALLERY_DIR + "index.txt";
    public static final String OWNED_DIR_NAME = "capes";

    private static final String KEY_PREFIX = "kirmod-cape:";
    private static final Field fieldImageData = KirUtil.findField(RenderEngine.class, "urlToImageDataMap", "i");

    private static List gallery;
    private static List categories;
    private static String selectedId = DEFAULT;

    /** The image-map key the player's cape is drawn from, or null for no cape; set lazily by update(). */
    private static String appliedKey;
    private static volatile boolean applyPending = true;

    /** Where the account's capes are: not logged in, being fetched, fetched, or the fetch failed. */
    public static final int ACCOUNT_NOT_LOGGED_IN = 0;
    public static final int ACCOUNT_LOADING = 1;
    public static final int ACCOUNT_LOADED = 2;
    public static final int ACCOUNT_FAILED = 3;

    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static volatile int accountState = ACCOUNT_NOT_LOGGED_IN;
    private static volatile String accountError;
    private static volatile List accountCapes = Collections.EMPTY_LIST;

    /** The id of the cape that is active on the account (the one it wears), or null for none. */
    private static volatile String activeAccountCapeId;

    /** Goes up whenever the account state or capes change, so screens know to redraw their lists. */
    private static volatile int accountVersion;

    /** Gallery capes whose file was renamed, as {old id, new id}, so a saved choice keeps working. */
    private static final String[][] RENAMED = new String[][] {{"gallery:Crown.png", "gallery:Hero.png"}, {"gallery:Warden.png", "gallery:Twisted.png"}, {"gallery:Pastel.png", "gallery:Aurora.png"}, {"gallery:Japanese Translator.png", "gallery:Cheapsh0t's.png"}, {"gallery:dannyBstyle's.png", "gallery:dB.png"}, {"gallery:MrMessiah's.png", "gallery:Spade.png"}, {"gallery:Follower's Cape.png", "gallery:Follower's.png"}};

    /** Reads the saved choice and starts fetching the account's capes; called from mod_Kirmod.load(). */
    static void load()
    {
        selectedId = mod_Kirmod.getSetting(CONFIG_KEY, DEFAULT);

        for (int var0 = 0; var0 < RENAMED.length; ++var0)
        {
            if (selectedId.equals(RENAMED[var0][0]))
            {
                selectedId = RENAMED[var0][1];
                mod_Kirmod.setSetting(CONFIG_KEY, selectedId);
            }
        }

        fetchAccountCapes();
    }

    /* ------------------------------------------------------------------ Cape lists */

    /** One cape the player can pick. */
    public static class Cape
    {
        public final String id;

        /** File name without ".png"; the cape is shown as "NAME Cape". */
        public final String name;
        private final String resource;
        private final File file;

        /** Already downloaded image of an account cape. */
        private final BufferedImage image;

        /** The gallery category the cape is listed under, or null for capes outside the gallery. */
        public String category;

        /** The full name from index.txt ("FILE.png=Name"), or null to show "NAME Cape". */
        private String displayName;

        Cape(String id, String name, String resource, File file)
        {
            this(id, name, resource, file, (BufferedImage)null);
        }

        Cape(String id, String name, String resource, File file, BufferedImage image)
        {
            this.id = id;
            this.name = name;
            this.resource = resource;
            this.file = file;
            this.image = image;
        }

        public String getDisplayName()
        {
            return this.displayName != null ? this.displayName : this.name + " Cape";
        }

        public boolean isGallery()
        {
            return this.resource != null;
        }

        public boolean isCustomFile()
        {
            return this.id.startsWith("file:");
        }

        public boolean isAccount()
        {
            return this.id.startsWith("account:");
        }

        /** Where the cape comes from, for the settings screen. */
        public String getSource()
        {
            return this.isAccount() ? "Your account" : (this.isGallery() ? (this.category != null ? this.category : "Cape Gallery") : (this.isCustomFile() ? "Custom file" : "Your cape folder"));
        }

        /** Loads the cape as a 64x32-style image, or returns null (and logs) if it can't be read. */
        public BufferedImage loadImage()
        {
            if (this.image != null)
            {
                return this.image;
            }

            try
            {
                InputStream var1 = this.resource != null ? KirCapes.class.getResourceAsStream(this.resource) : new FileInputStream(this.file);

                if (var1 == null)
                {
                    return null;
                }

                try
                {
                    return toCapeImage(ImageIO.read(var1));
                }
                finally
                {
                    var1.close();
                }
            }
            catch (IOException var6)
            {
                KirUtil.log("Could not read cape " + (this.resource != null ? this.resource : this.file.getPath()), var6);
                return null;
            }
        }
    }

    /** A heading of the cape gallery and the capes under it. */
    public static class Category
    {
        public final String name;
        public final List capes = new ArrayList();

        Category(String name)
        {
            this.name = name;
        }
    }

    /** The capes bundled with the mod, in the order of index.txt. */
    public static List getGallery()
    {
        if (gallery == null)
        {
            gallery = new ArrayList();
            categories = new ArrayList();
            InputStream var0 = KirCapes.class.getResourceAsStream(GALLERY_INDEX);

            if (var0 == null)
            {
                KirUtil.log("No cape gallery found at " + GALLERY_INDEX, (Throwable)null);
                return gallery;
            }

            try
            {
                BufferedReader var1 = new BufferedReader(new InputStreamReader(var0, "UTF-8"));
                Category var2 = null;
                String var3;

                while ((var3 = var1.readLine()) != null)
                {
                    var3 = var3.trim();

                    if (var3.startsWith("[") && var3.endsWith("]"))
                    {
                        var2 = new Category(var3.substring(1, var3.length() - 1));
                        categories.add(var2);
                        continue;
                    }

                    int var4 = var3.indexOf('=');
                    String var5 = var4 >= 0 ? var3.substring(0, var4).trim() : var3;

                    if (var5.toLowerCase().endsWith(".png"))
                    {
                        if (var2 == null)
                        {
                            var2 = new Category("Cape Gallery");
                            categories.add(var2);
                        }

                        Cape var6 = new Cape("gallery:" + var5, stripExtension(var5), GALLERY_DIR + var5, (File)null);
                        var6.category = var2.name;
                        var6.displayName = var4 >= 0 ? var3.substring(var4 + 1).trim() : null;
                        var2.capes.add(var6);
                        gallery.add(var6);
                    }
                }

                var1.close();
            }
            catch (IOException var7)
            {
                KirUtil.log("Could not read " + GALLERY_INDEX, var7);
            }
        }

        return gallery;
    }

    /**
     * The gallery's categories, in the order of index.txt. A "[Name]" line starts a category; a
     * "FILE.png" line adds a cape to it, shown as "FILE Cape", and "FILE.png=Name" gives it its own name.
     */
    public static List getCategories()
    {
        getGallery();
        return categories;
    }

    /** .minecraft/capes/, created if missing. */
    public static File getOwnedDir()
    {
        File var0 = new File(Minecraft.getMinecraftDir(), OWNED_DIR_NAME);
        var0.mkdirs();
        return var0;
    }

    /** The PNGs in .minecraft/capes/, sorted by name. Read fresh each call, so new files show up. */
    public static List getOwned()
    {
        ArrayList var0 = new ArrayList();
        File[] var1 = getOwnedDir().listFiles();

        if (var1 == null)
        {
            return var0;
        }

        Arrays.sort(var1, new Comparator()
        {
            public int compare(Object a, Object b)
            {
                return ((File)a).getName().compareToIgnoreCase(((File)b).getName());
            }
        });

        for (int var2 = 0; var2 < var1.length; ++var2)
        {
            if (var1[var2].isFile() && var1[var2].getName().toLowerCase().endsWith(".png"))
            {
                var0.add(new Cape("owned:" + var1[var2].getName(), stripExtension(var1[var2].getName()), (String)null, var1[var2]));
            }
        }

        return var0;
    }

    /** A cape from any file on disk. */
    public static Cape getCustomCape(File file)
    {
        File var1 = file.getAbsoluteFile();
        return new Cape("file:" + var1.getPath(), stripExtension(var1.getName()), (String)null, var1);
    }

    /** Finds a cape by id, or returns null (for "none" or a cape that is gone). */
    public static Cape findById(String id)
    {
        if (id == null || id.equals(NONE))
        {
            return null;
        }

        if (id.startsWith("file:"))
        {
            File var1 = new File(id.substring(5));
            return var1.isFile() ? getCustomCape(var1) : null;
        }

        List var2 = id.startsWith("account:") ? getAccountCapes() : (id.startsWith("owned:") ? getOwned() : getGallery());

        for (int var3 = 0; var3 < var2.size(); ++var3)
        {
            if (((Cape)var2.get(var3)).id.equals(id))
            {
                return (Cape)var2.get(var3);
            }
        }

        return null;
    }

    /** Finds an owned or gallery cape by name, with or without " Cape", ignoring case and punctuation. */
    public static Cape findByName(String name)
    {
        String var1 = KirUtil.normalize(name);
        ArrayList var2 = new ArrayList(getAccountCapes());
        var2.addAll(getOwned());
        var2.addAll(getGallery());

        for (int var3 = 0; var3 < var2.size(); ++var3)
        {
            Cape var4 = (Cape)var2.get(var3);

            if (KirUtil.normalize(var4.name).equals(var1) || KirUtil.normalize(var4.getDisplayName()).equals(var1))
            {
                return var4;
            }
        }

        return null;
    }

    /* ------------------------------------------------------------------ Account capes */

    /** The capes the logged-in account owns, in the API's order; empty until fetched. */
    public static List getAccountCapes()
    {
        return accountCapes;
    }

    public static int getAccountState()
    {
        return accountState;
    }

    /** Why the last fetch failed, for the settings screen. */
    public static String getAccountError()
    {
        return accountError;
    }

    public static int getAccountVersion()
    {
        return accountVersion;
    }

    /**
     * The Minecraft access token the launcher gave the game, or null when playing offline. Launchers
     * that log in with a Microsoft account (MultiMC, Prism) pass the legacy session id as
     * "token:ACCESS_TOKEN:UUID"; offline sessions get "-" or a plain id instead.
     */
    private static String getAccessToken()
    {
        Minecraft var0 = ModLoader.getMinecraftInstance();
        String var1 = var0 != null && var0.session != null ? var0.session.sessionId : null;

        if (var1 == null || !var1.startsWith("token:"))
        {
            return null;
        }

        String[] var2 = var1.split(":");
        return var2.length >= 2 && var2[1].length() > 0 ? var2[1] : null;
    }

    public static boolean isLoggedIn()
    {
        return getAccessToken() != null;
    }

    /**
     * Fetches the account's capes on a background thread: the profile from the Minecraft services API,
     * then each cape's image. When not logged in, nothing is sent and the state says so. The token only
     * goes to api.minecraftservices.com; the cape images are public.
     */
    public static void fetchAccountCapes()
    {
        final String var0 = getAccessToken();

        if (var0 == null)
        {
            KirSkins.setAccountSkins(Collections.EMPTY_LIST);
            setAccountState(ACCOUNT_NOT_LOGGED_IN, (String)null, Collections.EMPTY_LIST);
            return;
        }

        if (accountState == ACCOUNT_LOADING)
        {
            return;
        }

        setAccountState(ACCOUNT_LOADING, (String)null, accountCapes);
        Thread var1 = new Thread("Kirmod cape fetch")
        {
            public void run()
            {
                try
                {
                    String var7 = httpGet(PROFILE_URL, var0);
                    List var1 = parseProfileList(var7, "capes");
                    ArrayList var2 = new ArrayList();
                    String var8 = null;

                    for (int var3 = 0; var3 < var1.size(); ++var3)
                    {
                        String[] var4 = (String[])var1.get(var3);
                        BufferedImage var5 = downloadImage(var4[1]);

                        if (var5 != null)
                        {
                            var2.add(new Cape("account:" + var4[0], var4[2].length() > 0 ? var4[2] : "Account", (String)null, (File)null, var5));

                            if (var4[3].equalsIgnoreCase("ACTIVE"))
                            {
                                var8 = "account:" + var4[0];
                            }
                        }
                    }

                    activeAccountCapeId = var8;

                    KirSkins.setAccountSkins(KirSkins.downloadAccountSkins(parseProfileList(var7, "skins")));
                    KirCapes.setAccountState(ACCOUNT_LOADED, (String)null, Collections.unmodifiableList(var2));
                }
                catch (IOException var6)
                {
                    KirUtil.log("Could not fetch the account's capes", var6);
                    KirSkins.setAccountSkins(Collections.EMPTY_LIST);
                    KirCapes.setAccountState(ACCOUNT_FAILED, var6.getMessage(), Collections.EMPTY_LIST);
                }
            }
        };
        var1.setDaemon(true);
        var1.start();
    }

    private static void setAccountState(int state, String error, List capes)
    {
        if (state != ACCOUNT_LOADED && state != ACCOUNT_LOADING)
        {
            activeAccountCapeId = null;
        }

        accountCapes = capes;
        accountError = error;
        accountState = state;
        ++accountVersion;

        if (selectedId.startsWith("account:") || selectedId.equals(DEFAULT))
        {
            applyPending = true;
        }
    }

    /** The cape that is active on the logged-in account, or null if it wears none or isn't fetched yet. */
    public static Cape getAccountActiveCape()
    {
        return activeAccountCapeId == null ? null : findById(activeAccountCapeId);
    }

    /** GETs a URL with the access token and returns the body; HTTP errors become IOExceptions. */
    private static String httpGet(String url, String token) throws IOException
    {
        HttpURLConnection var2 = (HttpURLConnection)(new URL(url)).openConnection();

        try
        {
            var2.setConnectTimeout(10000);
            var2.setReadTimeout(10000);
            var2.setRequestProperty("Authorization", "Bearer " + token);
            var2.setRequestProperty("Accept", "application/json");
            int var3 = var2.getResponseCode();

            if (var3 == 401)
            {
                throw new IOException("Login expired, restart the launcher");
            }

            if (var3 == 404)
            {
                throw new IOException("This account doesn't own Minecraft");
            }

            if (var3 / 100 != 2)
            {
                throw new IOException("Minecraft services answered " + var3);
            }

            BufferedReader var4 = new BufferedReader(new InputStreamReader(var2.getInputStream(), "UTF-8"));
            StringBuilder var5 = new StringBuilder();
            String var6;

            while ((var6 = var4.readLine()) != null)
            {
                var5.append(var6).append('\n');
            }

            var4.close();
            return var5.toString();
        }
        finally
        {
            var2.disconnect();
        }
    }

    private static BufferedImage downloadImage(String url)
    {
        return toCapeImage(download(url));
    }

    /** Downloads a texture from textures.minecraft.net, or returns null (and logs) if that fails. */
    static BufferedImage download(String url)
    {
        try
        {
            // textures.minecraft.net serves the same files over https.
            URL var1 = new URL(url.startsWith("http://") ? "https://" + url.substring(7) : url);
            return ImageIO.read(var1);
        }
        catch (IOException var2)
        {
            KirUtil.log("Could not download " + url, var2);
            return null;
        }
    }

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{([^{}]*)\\}");

    /**
     * The capes or skins (key "capes" or "skins") in a /minecraft/profile response, as {id, url, alias or
     * variant, state} arrays. The game has no JSON library, and both arrays are flat lists of string
     * fields, so a small pattern match does.
     */
    static List parseProfileList(String json, String key)
    {
        ArrayList var1 = new ArrayList();
        Matcher var2 = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);

        if (var2.find())
        {
            Matcher var3 = JSON_OBJECT.matcher(var2.group(1));

            while (var3.find())
            {
                String var4 = jsonString(var3.group(1), "id");
                String var5 = jsonString(var3.group(1), "url");

                if (var4 != null && var5 != null)
                {
                    String var6 = jsonString(var3.group(1), key.equals("skins") ? "variant" : "alias");
                    String var7 = jsonString(var3.group(1), "state");
                    var1.add(new String[] {var4, var5, var6 != null ? var6 : "", var7 != null ? var7 : ""});
                }
            }
        }

        return var1;
    }

    /** A string field of a flat JSON object, unescaped, or null if missing. */
    private static String jsonString(String object, String key)
    {
        Matcher var2 = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(object);

        if (!var2.find())
        {
            return null;
        }

        String var3 = var2.group(1);
        StringBuilder var4 = new StringBuilder();

        for (int var5 = 0; var5 < var3.length(); ++var5)
        {
            char var6 = var3.charAt(var5);

            if (var6 == '\\' && var5 + 1 < var3.length())
            {
                char var7 = var3.charAt(++var5);

                if (var7 == 'u' && var5 + 4 < var3.length())
                {
                    var4.append((char)Integer.parseInt(var3.substring(var5 + 1, var5 + 5), 16));
                    var5 += 4;
                }
                else
                {
                    var4.append(var7 == 'n' ? '\n' : (var7 == 't' ? '\t' : var7));
                }
            }
            else
            {
                var4.append(var6);
            }
        }

        return var4.toString();
    }

    /* ------------------------------------------------------------------ Selection */

    public static String getSelectedId()
    {
        return selectedId;
    }

    /** The cape worn: the chosen one, or for "default" the account's active cape; null for none. */
    public static Cape getSelected()
    {
        return isDefault() ? getAccountActiveCape() : findById(selectedId);
    }

    /** Whether no cape is chosen, so the account's (or vanilla's) cape is worn. */
    public static boolean isDefault()
    {
        return DEFAULT.equals(selectedId);
    }

    /** Stops overriding the cape: the account's active cape (or vanilla's) is worn again. */
    public static void selectDefault()
    {
        selectedId = DEFAULT;
        applyPending = true;
        mod_Kirmod.setSetting(CONFIG_KEY, selectedId);
    }

    /** Wears the cape (null for none), saves the choice and updates the player straight away. */
    public static void select(Cape cape)
    {
        selectedId = cape == null ? NONE : cape.id;
        applyPending = true;
        mod_Kirmod.setSetting(CONFIG_KEY, selectedId);
    }

    /** Re-reads the chosen cape's file, e.g. after it was edited. */
    public static void reload()
    {
        applyPending = true;
    }

    /* ------------------------------------------------------------------ Drawing it on the player */

    /** Called every frame: loads the chosen cape if needed and points the player's cape at it. */
    static void update(Minecraft mc)
    {
        if (fieldImageData == null || mc.renderEngine == null)
        {
            return;
        }

        if (applyPending)
        {
            applyPending = false;
            applyCape(mc.renderEngine);
        }

        if (mc.thePlayer != null)
        {
            // Nothing applied on "default" leaves vanilla's cape (cloakUrl keeps vanilla's own URL).
            mc.thePlayer.playerCloakUrl = appliedKey != null ? appliedKey : (isDefault() ? mc.thePlayer.cloakUrl : null);
        }
    }

    /** Replaces the cape image in the render engine's image map with the chosen one. */
    private static void applyCape(RenderEngine renderEngine)
    {
        try
        {
            Map var1 = (Map)fieldImageData.get(renderEngine);

            if (appliedKey != null)
            {
                ThreadDownloadImageData var2 = (ThreadDownloadImageData)var1.remove(appliedKey);

                if (var2 != null && var2.textureName >= 0)
                {
                    renderEngine.deleteTexture(var2.textureName);
                }

                appliedKey = null;
            }

            Cape var3 = getSelected();
            BufferedImage var4 = var3 != null ? var3.loadImage() : null;

            if (var4 != null)
            {
                ThreadDownloadImageData var5 = newImageData(var4);
                // A new key each time, so nothing else holding the old one can draw a stale texture.
                appliedKey = KEY_PREFIX + var3.id + "#" + System.nanoTime();
                var1.put(appliedKey, var5);
            }
            else if (var3 == null && !NONE.equals(selectedId) && !isDefault())
            {
                KirUtil.log("Cape '" + selectedId + "' is gone, showing none", (Throwable)null);
            }
        }
        catch (Exception var6)
        {
            KirUtil.log("Could not apply the cape", var6);
        }
    }

    /**
     * An image-map entry that is already loaded. ThreadDownloadImageData's constructor starts a download
     * thread, so the object is allocated without running it and its fields are set by hand.
     */
    private static ThreadDownloadImageData newImageData(BufferedImage image) throws Exception
    {
        Class var1 = Class.forName("sun.misc.Unsafe");
        Field var2 = var1.getDeclaredField("theUnsafe");
        var2.setAccessible(true);
        Method var3 = var1.getMethod("allocateInstance", new Class[] {Class.class});
        ThreadDownloadImageData var4 = (ThreadDownloadImageData)var3.invoke(var2.get((Object)null), new Object[] {ThreadDownloadImageData.class});
        var4.image = image;
        var4.referenceCount = 1;
        var4.textureName = -1;
        var4.textureSetupComplete = false;
        return var4;
    }

    /* ------------------------------------------------------------------ Helpers */

    /**
     * Checks a cape image: 2:1 images (64x32, or HD multiples) are used as they are; smaller ones, like
     * the old 22x17 capes, are placed in the corner of a 64x32 image. Anything else is refused (null).
     */
    public static BufferedImage toCapeImage(BufferedImage image)
    {
        if (image == null)
        {
            return null;
        }

        int var1 = image.getWidth();
        int var2 = image.getHeight();

        if (var1 == var2 * 2 && var1 >= 64)
        {
            return image;
        }

        if (var1 <= 64 && var2 <= 32)
        {
            BufferedImage var3 = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D var4 = var3.createGraphics();
            var4.drawImage(image, 0, 0, null);
            var4.dispose();
            return var3;
        }

        return null;
    }

    private static String stripExtension(String fileName)
    {
        return fileName.toLowerCase().endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    /** Opens .minecraft/capes/ in the system file manager, the way the texture pack screen does. */
    public static void openOwnedDir()
    {
        openDir(getOwnedDir());
    }

    /** Opens a folder in the system file manager. */
    static void openDir(File dir)
    {
        String var0 = dir.getAbsolutePath();

        try
        {
            if (Minecraft.getOs() == EnumOS.MACOS)
            {
                Runtime.getRuntime().exec(new String[] {"/usr/bin/open", var0});
                return;
            }

            if (Minecraft.getOs() == EnumOS.WINDOWS)
            {
                Runtime.getRuntime().exec(new String[] {"cmd.exe", "/C", "start", "\"Open file\"", var0});
                return;
            }
        }
        catch (IOException var3)
        {
            KirUtil.log("Could not open " + var0, var3);
        }

        try
        {
            Class var1 = Class.forName("java.awt.Desktop");
            Object var2 = var1.getMethod("getDesktop", new Class[0]).invoke((Object)null, new Object[0]);
            var1.getMethod("browse", new Class[] {URI.class}).invoke(var2, new Object[] {dir.toURI()});
        }
        catch (Throwable var4)
        {
            Sys.openURL("file://" + var0);
        }
    }

    /* ------------------------------------------------------------------ Command */

    static void registerCommand()
    {
        mod_CommandLib.registerCommand(new ClientCommand("cape", "[gui | default | none | list | <name>]", "Picks the cape you wear (only you see it)")
        {
            public void execute(String[] args)
            {
                String var2 = joinArgs(args);

                if (var2.length() == 0 || var2.equalsIgnoreCase("gui"))
                {
                    mod_Kirmod.openScreenNextTick(new GuiKirmodCapes((GuiScreen)null));
                }
                else if (var2.equalsIgnoreCase(DEFAULT))
                {
                    KirCapes.selectDefault();
                    this.reply("Cape: your account's");
                }
                else if (var2.equalsIgnoreCase(NONE))
                {
                    KirCapes.select((Cape)null);
                    this.reply("Cape: none");
                }
                else if (var2.equalsIgnoreCase("list"))
                {
                    this.reply("§eYour account: §f" + (KirCapes.isLoggedIn() ? joinNames(KirCapes.getAccountCapes()) : "not logged in"));
                    this.reply("§eCape folder: §f" + joinNames(KirCapes.getOwned()));
                    List var4 = KirCapes.getCategories();

                    for (int var5 = 0; var5 < var4.size(); ++var5)
                    {
                        Category var6 = (Category)var4.get(var5);
                        this.reply("§e" + var6.name + ": §f" + joinNames(var6.capes));
                    }
                }
                else
                {
                    Cape var3 = KirCapes.findByName(var2);

                    if (var3 == null)
                    {
                        throw new ClientCommandException("No cape called '" + var2 + "'. Try /cape list");
                    }

                    KirCapes.select(var3);
                    this.reply("Cape: " + var3.getDisplayName());
                }
            }
        });
    }

    static String joinArgs(String[] args)
    {
        StringBuilder var1 = new StringBuilder();

        for (int var2 = 0; var2 < args.length; ++var2)
        {
            var1.append(var2 > 0 ? " " : "").append(args[var2]);
        }

        return var1.toString().trim();
    }

    private static String joinNames(List capes)
    {
        if (capes.isEmpty())
        {
            return "(none)";
        }

        StringBuilder var1 = new StringBuilder();

        for (int var2 = 0; var2 < capes.size(); ++var2)
        {
            Cape var3 = (Cape)capes.get(var2);
            var1.append(var2 > 0 ? ", " : "").append(var3.displayName != null ? var3.displayName : var3.name);
        }

        return var1.toString();
    }
}
