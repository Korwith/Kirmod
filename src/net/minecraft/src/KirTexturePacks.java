package net.minecraft.src;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Texture packs: the built-in Console Edition packs, the texture pack screen (GuiKirmodTexturePacks,
 * which takes the place of vanilla's) and HD (32x, 64x) texture packs without Forge.
 *
 * Built-in packs are 1.3.2 texture packs bundled with the mod in kirmod/texturepacks/PACK/ and listed in
 * kirmod/texturepacks/index.txt (lib/kirmod/texturepacks/ in MCP), each under a category: the Console
 * Edition packs are "Built-in", and packs ported from other versions are "Other". Each is a BuiltinPack,
 * which reads its files from there and falls back to the default textures for any file the pack doesn't
 * have. Vanilla's
 * TexturePackList only knows the packs in .minecraft/texturepacks/, so it can't find a built-in pack when
 * it rescans the folder (at startup, or when vanilla's screen opens) and falls back to the default pack.
 * The chosen pack is saved as vanilla's "skin" option ("kirmod/PACK"; options.txt can't hold a colon there), and update() puts it back whenever
 * the list has lost it. Without Kirmod the game just starts with the default pack.
 *
 * Custom packs are vanilla's: zips and folders in .minecraft/texturepacks/. Importing one copies it there.
 *
 * HD packs: the terrain and item atlases are 16 tiles across at any size, so blocks and items draw
 * fine at 32x or 64x. The animated tiles (water, lava, fire, portal, compass, clock) are where vanilla
 * goes wrong: RenderEngine writes each animation as a 16x16 tile at 16-pixel offsets. Forge (FML) scales
 * them itself, so with Forge nothing is changed. Without it, the vanilla animations in RenderEngine's
 * list are swapped for HDTextureFX wrappers while an HD pack is on: the wrapper runs the animation as
 * usual, then scales it up and writes it at the pack's tile size itself.
 */
public class KirTexturePacks
{
    public static final String PACK_DIR = "/kirmod/texturepacks/";
    public static final String PACK_INDEX = PACK_DIR + "index.txt";
    public static final String ID_PREFIX = "kirmod/";
    public static final String CATEGORY_BUILTIN = "Built-in";
    public static final String CATEGORY_OTHER = "Other";

    private static final Field fieldTextureList = KirUtil.findField(RenderEngine.class, "textureList", "h");
    private static final boolean hasForge = classExists("cpw.mods.fml.client.TextureFXManager");

    private static List builtins;

    /** Terrain resolution (16, 32, 64...) per pack, read from its terrain.png once. */
    private static final Map resolutions = new HashMap();

    /** The tick hook; see TickFX. */
    private static final TickFX tickFX = new TickFX();

    /** The pack update() last saw selected, to notice changes. */
    private static TexturePackBase lastPack;

    /** The atlas sizes (in pixels) the HD wrappers are set up for; 256 = vanilla's. */
    private static int wrappedTerrainSize = 256;
    private static int wrappedItemsSize = 256;

    /* ------------------------------------------------------------------ Built-in packs */

    /** A texture pack bundled with the mod. */
    public static class BuiltinPack extends TexturePackImplementation
    {
        public final String name;
        public final int resolution;
        public final String category;
        private String description;

        BuiltinPack(String name, int resolution, String category)
        {
            // The id comes first: getResourceAsStream() needs it while the super constructor reads pack.png.
            super(ID_PREFIX + name, ID_PREFIX + name);
            this.name = name;
            this.resolution = resolution;
            this.category = category;
        }

        public InputStream getResourceAsStream(String par1Str)
        {
            InputStream var2 = KirTexturePacks.class.getResourceAsStream(PACK_DIR + this.func_77536_b().substring(ID_PREFIX.length()) + par1Str);
            return var2 != null ? var2 : super.getResourceAsStream(par1Str);
        }

        /** The whole second line of pack.txt; vanilla cuts both lines to 34 characters. */
        public String getDescription()
        {
            if (this.description == null)
            {
                this.description = readPackTxt(this, 1);
            }

            return this.description;
        }

        public int func_77534_f()
        {
            return this.resolution;
        }
    }

    /**
     * The built-in packs of every category, in the order of index.txt. A "FOLDER=RESOLUTION" line adds the
     * pack in kirmod/texturepacks/FOLDER/, whose terrain is RESOLUTION pixels per tile. A "[CATEGORY]" line
     * puts the packs below it in that category; the ones above the first are CATEGORY_BUILTIN.
     */
    public static List getBuiltins()
    {
        if (builtins == null)
        {
            builtins = new ArrayList();
            InputStream var0 = KirTexturePacks.class.getResourceAsStream(PACK_INDEX);

            if (var0 == null)
            {
                KirUtil.log("No built-in texture packs found at " + PACK_INDEX, (Throwable)null);
                return builtins;
            }

            try
            {
                BufferedReader var1 = new BufferedReader(new InputStreamReader(var0, "UTF-8"));
                String var2;
                String var9 = CATEGORY_BUILTIN;

                while ((var2 = var1.readLine()) != null)
                {
                    var2 = var2.trim();
                    int var3 = var2.lastIndexOf('=');

                    if (var2.startsWith("[") && var2.endsWith("]"))
                    {
                        var9 = var2.substring(1, var2.length() - 1).trim();
                    }
                    else if (var2.length() > 0 && !var2.startsWith("#") && var3 > 0)
                    {
                        int var4 = 16;

                        try
                        {
                            var4 = Integer.parseInt(var2.substring(var3 + 1).trim());
                        }
                        catch (NumberFormatException var7)
                        {
                            ;
                        }

                        BuiltinPack var5 = new BuiltinPack(var2.substring(0, var3).trim(), var4, var9);
                        builtins.add(var5);
                        resolutions.put(var5, Integer.valueOf(var4));
                    }
                }

                var1.close();
            }
            catch (IOException var8)
            {
                KirUtil.log("Could not read " + PACK_INDEX, var8);
            }
        }

        return builtins;
    }

    /** The built-in packs in one category, in the order of index.txt. */
    public static List getBuiltins(String category)
    {
        List var1 = getBuiltins();
        ArrayList var2 = new ArrayList();

        for (int var3 = 0; var3 < var1.size(); ++var3)
        {
            BuiltinPack var4 = (BuiltinPack)var1.get(var3);

            if (var4.category.equals(category))
            {
                var2.add(var4);
            }
        }

        return var2;
    }

    /** "Built-in", "Other" (a bundled pack's category) or "Custom". */
    public static String getCategory(TexturePackBase pack)
    {
        return pack instanceof BuiltinPack ? ((BuiltinPack)pack).category : (pack instanceof TexturePackDefault ? CATEGORY_BUILTIN : "Custom");
    }

    /** The default pack (always first in vanilla's list). */
    public static TexturePackBase getDefault(Minecraft mc)
    {
        return (TexturePackBase)mc.texturePackList.availableTexturePacks().get(0);
    }

    /** The packs in .minecraft/texturepacks/, as vanilla lists them (rescanned by refreshCustom()). */
    public static List getCustom(Minecraft mc)
    {
        List var1 = mc.texturePackList.availableTexturePacks();
        return var1.size() > 1 ? var1.subList(1, var1.size()) : Collections.EMPTY_LIST;
    }

    /** Rescans .minecraft/texturepacks/, keeping a built-in pack selected. */
    public static void refreshCustom(Minecraft mc)
    {
        mc.texturePackList.updateAvaliableTexturePacks();
        restoreBuiltin(mc);
    }

    public static File getCustomDir()
    {
        File var0 = new File(Minecraft.getMinecraftDir(), "texturepacks");
        var0.mkdirs();
        return var0;
    }

    public static void openCustomDir()
    {
        KirCapes.openDir(getCustomDir());
    }

    /** The display name of a pack: pack.txt's first line for built-in packs, the file name otherwise. */
    public static String getName(TexturePackBase pack)
    {
        if (pack instanceof BuiltinPack)
        {
            return ((BuiltinPack)pack).name;
        }

        String var1 = pack.func_77538_c();

        if (pack instanceof TexturePackDefault || var1 == null)
        {
            return "Default";
        }

        return var1.toLowerCase().endsWith(".zip") ? var1.substring(0, var1.length() - 4) : var1;
    }

    /** The pack's description, both pack.txt lines for a custom pack. */
    public static String getDescription(TexturePackBase pack)
    {
        if (pack instanceof BuiltinPack)
        {
            return ((BuiltinPack)pack).getDescription();
        }

        String var1 = pack.func_77531_d();
        String var2 = pack.func_77537_e();
        return ((var1 == null ? "" : var1) + " " + (var2 == null ? "" : var2)).trim();
    }

    /** Terrain pixels per tile: 16, 32, 64... (0 if the pack has no readable terrain.png). */
    public static int getResolution(TexturePackBase pack)
    {
        Integer var1 = (Integer)resolutions.get(pack);

        if (var1 == null)
        {
            int var2 = readImageWidth(pack, "/terrain.png");
            var1 = Integer.valueOf(var2 > 0 ? var2 / 16 : 0);
            resolutions.put(pack, var1);
        }

        return var1.intValue();
    }

    /** Forgets the cached resolution of custom packs, e.g. after a pack in the folder was replaced. */
    public static void forgetResolutions()
    {
        Iterator var0 = resolutions.keySet().iterator();

        while (var0.hasNext())
        {
            if (!(var0.next() instanceof BuiltinPack))
            {
                var0.remove();
            }
        }
    }

    /** Finds a built-in or custom pack by name, ignoring case and punctuation; null if there is none. */
    public static TexturePackBase findByName(Minecraft mc, String name)
    {
        String var2 = KirUtil.normalize(name);
        ArrayList var3 = new ArrayList();
        var3.add(getDefault(mc));
        var3.addAll(getBuiltins());
        var3.addAll(getCustom(mc));

        for (int var4 = 0; var4 < var3.size(); ++var4)
        {
            TexturePackBase var5 = (TexturePackBase)var3.get(var4);
            String var6 = KirUtil.normalize(getName(var5));

            if (var6.equals(var2) || var6.replace("texturepack", "").equals(var2) || var6.replace("mashup", "").equals(var2))
            {
                return var5;
            }
        }

        return null;
    }

    /* ------------------------------------------------------------------ Selection */

    /** Switches to a pack and reloads every texture; a no-op if it is already on. */
    public static void select(Minecraft mc, TexturePackBase pack)
    {
        if (mc.texturePackList.setTexturePack(pack))
        {
            lastPack = pack;
            syncHD(mc, pack);
            mc.renderEngine.refreshTextures();
            KirDarkMenus.reapply(mc);

            if (mc.theWorld != null)
            {
                // Chunks keep the old pack's grass and foliage colors until they are built again.
                KirUtil.reloadRenderers();
            }
        }
    }

    public static TexturePackBase getSelected(Minecraft mc)
    {
        return mc.texturePackList.getSelectedTexturePack();
    }

    /** If the saved choice is a built-in pack that the list lost, selects it again (without saving). */
    private static boolean restoreBuiltin(Minecraft mc)
    {
        String var1 = mc.gameSettings.skin;

        if (var1 == null || !var1.startsWith(ID_PREFIX) || getSelected(mc) instanceof BuiltinPack && var1.equals(getSelected(mc).func_77538_c()))
        {
            return false;
        }

        List var2 = getBuiltins();

        for (int var3 = 0; var3 < var2.size(); ++var3)
        {
            BuiltinPack var4 = (BuiltinPack)var2.get(var3);

            if (var4.func_77538_c().equals(var1))
            {
                TexturePackList.func_77303_a(mc.texturePackList, var4);
                return true;
            }
        }

        return false;
    }

    /* ------------------------------------------------------------------ Importing */

    /**
     * Copies a texture pack (a zip, or a folder with a pack.txt) into .minecraft/texturepacks/ (as
     * "NAME 2" and so on if the name is taken), rescans the folder and returns the new pack. Throws with a
     * message for the player if the file is not a texture pack or can't be copied.
     */
    public static TexturePackBase importPack(Minecraft mc, File file) throws IOException
    {
        boolean var2 = file.isDirectory();

        if (!file.exists())
        {
            throw new IOException("File not found: " + file.getName());
        }

        if (var2 ? !new File(file, "pack.txt").isFile() && !new File(file, "terrain.png").isFile() : !file.getName().toLowerCase().endsWith(".zip"))
        {
            throw new IOException(file.getName() + " is not a texture pack (needs a .zip, or a folder with a pack.txt)");
        }

        File var3 = getCustomDir();
        File var4 = file;

        if (!file.getAbsoluteFile().getParentFile().equals(var3.getAbsoluteFile()))
        {
            String var5 = file.getName();
            String var6 = var2 ? "" : ".zip";
            String var7 = var2 ? var5 : var5.substring(0, var5.length() - 4);
            var4 = new File(var3, var7 + var6);

            for (int var8 = 2; var4.exists(); ++var8)
            {
                var4 = new File(var3, var7 + " " + var8 + var6);
            }

            copy(file, var4);

            if (var2 && !new File(var4, "pack.txt").exists())
            {
                // Vanilla only lists folders that have a pack.txt.
                OutputStream var9 = new FileOutputStream(new File(var4, "pack.txt"));
                var9.write((var4.getName() + "\nImported texture pack\n").getBytes("UTF-8"));
                var9.close();
            }
        }

        refreshCustom(mc);
        List var10 = getCustom(mc);

        for (int var11 = 0; var11 < var10.size(); ++var11)
        {
            TexturePackBase var12 = (TexturePackBase)var10.get(var11);

            if (var12.func_77538_c().equals(var4.getName()))
            {
                return var12;
            }
        }

        throw new IOException("Copied " + file.getName() + ", but the game didn't list it as a texture pack");
    }

    private static void copy(File from, File to) throws IOException
    {
        if (from.isDirectory())
        {
            to.mkdirs();
            File[] var2 = from.listFiles();

            for (int var3 = 0; var2 != null && var3 < var2.length; ++var3)
            {
                copy(var2[var3], new File(to, var2[var3].getName()));
            }

            return;
        }

        InputStream var4 = new FileInputStream(from);

        try
        {
            OutputStream var5 = new FileOutputStream(to);

            try
            {
                byte[] var6 = new byte[65536];
                int var7;

                while ((var7 = var4.read(var6)) > 0)
                {
                    var5.write(var6, 0, var7);
                }
            }
            finally
            {
                var5.close();
            }
        }
        finally
        {
            var4.close();
        }
    }

    /* ------------------------------------------------------------------ Hooks */

    /**
     * Called every tick, from the GUI hook and from TickFX (which also runs at the main menu, where
     * ModLoader's hooks don't): swaps vanilla's texture pack screen for ours, keeps a built-in pack
     * selected, and sets up the HD wrappers when the pack changes.
     */
    static void update(Minecraft mc)
    {
        if (mc.texturePackList == null || mc.renderEngine == null)
        {
            return;
        }

        // Kirmod Lite has no texture pack screen of its own and keeps vanilla's.
        if (!KirEdition.LITE && mc.currentScreen != null && mc.currentScreen.getClass() == GuiTexturePacks.class)
        {
            mc.displayGuiScreen(new GuiKirmodTexturePacks(((GuiTexturePacks)mc.currentScreen).guiScreen));
        }

        boolean var1 = restoreBuiltin(mc);
        TexturePackBase var2 = getSelected(mc);

        if (!hasForge && (wrappedTerrainSize > 256 || wrappedItemsSize > 256) && wrapAnimations(mc, false))
        {
            // A 16x animation was added (and may already have been written at the wrong place).
            mc.renderEngine.refreshTextures();
            KirDarkMenus.reapply(mc);
        }

        if (var1 || var2 != lastPack)
        {
            lastPack = var2;

            if (syncHD(mc, var2) || var1)
            {
                // The textures on the card are the default pack's (lost built-in pack), or vanilla
                // animations have already been written at the wrong tile size.
                mc.renderEngine.refreshTextures();
                KirDarkMenus.reapply(mc);
            }
        }
    }

    /** The tick hook, added through ModLoader.addAnimation(); see mod_Kirmod.registerAnimation(). */
    static TextureFX getTickFX(Minecraft mc)
    {
        return readdAnimation(mc, tickFX);
    }

    /**
     * Takes an animation that is added through ModLoader.addAnimation() out of RenderEngine's list and
     * returns it. ModLoader adds its animations again on every texture pack change, so the old copy goes
     * first.
     */
    static TextureFX readdAnimation(Minecraft mc, TextureFX fx)
    {
        List var2 = getTextureList(mc.renderEngine);

        if (var2 != null)
        {
            ArrayList var3 = new ArrayList(var2);
            var3.removeAll(Collections.singleton(fx));
            setTextureList(mc.renderEngine, var3);
        }

        return fx;
    }

    /**
     * An animation that animates nothing: tileSize 0 makes RenderEngine (and Forge's) write no tile. It
     * binds terrain.png like a block animation would, since Forge looks up the bound texture's size.
     */
    static class TickFX extends TextureFX
    {
        TickFX()
        {
            super(0);
            this.tileSize = 0;
            this.tileImage = 0;
        }

        public void onTick()
        {
            try
            {
                KirTexturePacks.update(ModLoader.getMinecraftInstance());
                KirLocalGame.install(ModLoader.getMinecraftInstance());

                // Keeps the menus dark, also at the main menu where mod_Kirmod's hooks don't run (1.37).
                KirDarkMenus.tick(ModLoader.getMinecraftInstance());

                // In a world mod_Kirmod's tick runs the music player; at the main menu only this does (1.36).
                if (ModLoader.getMinecraftInstance().theWorld == null)
                {
                    KirMusic.update(ModLoader.getMinecraftInstance());
                }
            }
            catch (Throwable var2)
            {
                KirUtil.log("Texture pack hook failed", var2);
            }
        }
    }

    /* ------------------------------------------------------------------ HD packs without Forge */

    /**
     * Wraps (or unwraps) vanilla's animations to match the pack's atlas sizes. Returns whether the list
     * changed. Replaces RenderEngine's list instead of editing it, since this can run while
     * RenderEngine is going through it.
     */
    static boolean syncHD(Minecraft mc, TexturePackBase pack)
    {
        if (hasForge || fieldTextureList == null)
        {
            return false;
        }

        int var2 = Math.max(256, readImageWidth(pack, "/terrain.png"));
        int var3 = Math.max(256, readImageWidth(pack, "/gui/items.png"));

        if (var2 == wrappedTerrainSize && var3 == wrappedItemsSize)
        {
            return false;
        }

        wrappedTerrainSize = var2;
        wrappedItemsSize = var3;
        KirUtil.log("Texture pack atlases: terrain " + var2 + "px, items " + var3 + "px", (Throwable)null);
        return wrapAnimations(mc, true);
    }

    /**
     * Wraps the animations that need it for the current atlas sizes, and unwraps the rest. Unless all is
     * true, does nothing when no unwrapped animation needs wrapping: animations added later (ModLoader
     * adds its texture overrides again after a pack change) are caught that way. Returns whether the list
     * changed.
     */
    private static boolean wrapAnimations(Minecraft mc, boolean all)
    {
        List var2 = getTextureList(mc.renderEngine);

        if (var2 == null)
        {
            return false;
        }

        boolean var3 = all;

        for (int var4 = 0; !var3 && var4 < var2.size(); ++var4)
        {
            var3 = getHDTileSize((TextureFX)var2.get(var4)) > 16;
        }

        if (!var3)
        {
            return false;
        }

        ArrayList var5 = new ArrayList();
        Iterator var6 = var2.iterator();

        while (var6.hasNext())
        {
            TextureFX var7 = (TextureFX)var6.next();

            if (var7 instanceof HDTextureFX)
            {
                var7 = ((HDTextureFX)var7).inner;
            }

            int var8 = getHDTileSize(var7);
            var5.add(var8 > 16 ? new HDTextureFX(var7, var8) : var7);
        }

        setTextureList(mc.renderEngine, var5);
        return true;
    }

    /** The tile size an unwrapped 16x animation must be scaled to (16 if it needs no wrapper). */
    private static int getHDTileSize(TextureFX fx)
    {
        if (fx instanceof HDTextureFX || fx.imageData.length != 1024 || fx.tileSize <= 0)
        {
            return 16;
        }

        return (fx.tileImage == 0 ? wrappedTerrainSize : (fx.tileImage == 1 ? wrappedItemsSize : 256)) / 16;
    }

    /** A vanilla 16x animation drawn on an HD atlas: scaled up and written at the atlas' tile size. */
    static class HDTextureFX extends TextureFX
    {
        final TextureFX inner;
        private final int tile;
        private final ByteBuffer buffer;

        HDTextureFX(TextureFX inner, int tile)
        {
            super(inner.iconIndex);
            this.inner = inner;
            this.tile = tile;
            this.tileImage = inner.tileImage;
            this.textureId = inner.textureId;
            // RenderEngine writes nothing for us; onTick() does the writing.
            this.tileSize = 0;
            this.buffer = ByteBuffer.allocateDirect(tile * tile * 4);
        }

        public void onTick()
        {
            this.inner.anaglyphEnabled = this.anaglyphEnabled;
            this.inner.onTick();
            int var1 = this.tile / 16;
            byte[] var2 = this.inner.imageData;
            this.buffer.clear();

            for (int var3 = 0; var3 < this.tile; ++var3)
            {
                for (int var4 = 0; var4 < this.tile; ++var4)
                {
                    int var5 = (var3 / var1 * 16 + var4 / var1) * 4;
                    this.buffer.put(var2[var5]).put(var2[var5 + 1]).put(var2[var5 + 2]).put(var2[var5 + 3]);
                }
            }

            this.buffer.flip();
            Minecraft var6 = ModLoader.getMinecraftInstance();
            this.inner.bindImage(var6.renderEngine);

            for (int var7 = 0; var7 < this.inner.tileSize; ++var7)
            {
                for (int var8 = 0; var8 < this.inner.tileSize; ++var8)
                {
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (this.iconIndex % 16 + var7) * this.tile, (this.iconIndex / 16 + var8) * this.tile, this.tile, this.tile, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.buffer);
                }
            }
        }

        public void bindImage(RenderEngine par1RenderEngine)
        {
            this.inner.bindImage(par1RenderEngine);
        }
    }

    private static List getTextureList(RenderEngine renderEngine)
    {
        try
        {
            return fieldTextureList == null ? null : (List)fieldTextureList.get(renderEngine);
        }
        catch (IllegalAccessException var2)
        {
            return null;
        }
    }

    private static void setTextureList(RenderEngine renderEngine, List list)
    {
        try
        {
            fieldTextureList.set(renderEngine, list);
        }
        catch (Exception var3)
        {
            KirUtil.log("Could not update the texture animations", var3);
        }
    }

    /* ------------------------------------------------------------------ Helpers */

    /** The width of an image in a pack, read from its header only; 0 if it can't be read. */
    static int readImageWidth(TexturePackBase pack, String path)
    {
        try
        {
            InputStream var2 = pack.getResourceAsStream(path);

            if (var2 == null)
            {
                return 0;
            }

            try
            {
                ImageInputStream var3 = ImageIO.createImageInputStream(var2);
                Iterator var4 = ImageIO.getImageReaders(var3);

                if (!var4.hasNext())
                {
                    return 0;
                }

                ImageReader var5 = (ImageReader)var4.next();

                try
                {
                    var5.setInput(var3);
                    return var5.getWidth(0);
                }
                finally
                {
                    var5.dispose();
                    var3.close();
                }
            }
            finally
            {
                var2.close();
            }
        }
        catch (Exception var11)
        {
            return 0;
        }
    }

    /** A line of a pack's pack.txt (0 = name, 1 = description), or "". */
    private static String readPackTxt(TexturePackBase pack, int line)
    {
        try
        {
            InputStream var2 = pack.getResourceAsStream("/pack.txt");

            if (var2 != null)
            {
                BufferedReader var3 = new BufferedReader(new InputStreamReader(var2, "UTF-8"));

                try
                {
                    String var4 = null;

                    for (int var5 = 0; var5 <= line; ++var5)
                    {
                        var4 = var3.readLine();
                    }

                    return var4 == null ? "" : var4.trim();
                }
                finally
                {
                    var3.close();
                }
            }
        }
        catch (IOException var9)
        {
            ;
        }

        return "";
    }

    private static boolean classExists(String name)
    {
        try
        {
            Class.forName(name);
            return true;
        }
        catch (Throwable var2)
        {
            return false;
        }
    }

    /* ------------------------------------------------------------------ Command */

    static void registerCommand()
    {
        mod_CommandLib.registerCommand(new ClientCommand("texturepack", "[gui | default | list | <name>]", "Picks the texture pack", "tpack")
        {
            public void execute(String[] args)
            {
                String var2 = KirCapes.joinArgs(args);
                Minecraft var3 = ModLoader.getMinecraftInstance();

                if (var2.length() == 0 || var2.equalsIgnoreCase("gui"))
                {
                    mod_Kirmod.openScreenNextTick(new GuiKirmodTexturePacks((GuiScreen)null));
                }
                else if (var2.equalsIgnoreCase("list"))
                {
                    KirTexturePacks.refreshCustom(var3);
                    this.reply("§eBuilt-in: §f" + joinNames(KirTexturePacks.getBuiltins(KirTexturePacks.CATEGORY_BUILTIN)));
                    this.reply("§eOther: §f" + joinNames(KirTexturePacks.getBuiltins(KirTexturePacks.CATEGORY_OTHER)));
                    this.reply("§eCustom: §f" + joinNames(KirTexturePacks.getCustom(var3)));
                    this.reply("§7Selected: " + KirTexturePacks.getName(KirTexturePacks.getSelected(var3)));
                }
                else
                {
                    KirTexturePacks.refreshCustom(var3);
                    TexturePackBase var4 = var2.equalsIgnoreCase("default") ? KirTexturePacks.getDefault(var3) : KirTexturePacks.findByName(var3, var2);

                    if (var4 == null)
                    {
                        throw new ClientCommandException("No texture pack called '" + var2 + "'. Try /texturepack list");
                    }

                    KirTexturePacks.select(var3, var4);
                    this.reply("Texture pack: " + KirTexturePacks.getName(var4));
                }
            }
        });
    }

    private static String joinNames(List packs)
    {
        if (packs.isEmpty())
        {
            return "(none)";
        }

        StringBuilder var1 = new StringBuilder();

        for (int var2 = 0; var2 < packs.size(); ++var2)
        {
            TexturePackBase var3 = (TexturePackBase)packs.get(var2);
            int var4 = getResolution(var3);
            var1.append(var2 > 0 ? ", " : "").append(getName(var3)).append(var4 > 16 ? " (" + var4 + "x)" : "");
        }

        return var1.toString();
    }
}
