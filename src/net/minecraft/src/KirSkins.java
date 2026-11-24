package net.minecraft.src;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;

/**
 * Skins: draws a skin of the player's choice on their own player. Like KirCapes it is client-side
 * only, so other players (and servers) see nothing of it.
 *
 * Skins come from three places:
 *   - owned skins: the skin of the logged-in Microsoft account, fetched by KirCapes together with the
 *     account's capes (one /minecraft/profile request serves both)
 *   - skin packs: PNGs bundled with the mod in kirmod/skins/PACK/, listed in kirmod/skins/index.txt
 *     (lib/kirmod/skins/ in MCP)
 *   - the skin folder: PNGs in .minecraft/skins/; importing a skin from anywhere on disk copies it there
 *
 * Only skins that fit the standard (Steve) model are bundled. 64x64 skins keep their own left arm and
 * leg and their outer layer (jacket, sleeves, trouser legs): PlayerRenderer, registered for the
 * player's own entity class, draws the player with Model64 while such a skin is worn. Other players and
 * 64x32 skins use the vanilla model. The choice is saved in Kirmod.cfg as "skin" ("default", "account:SKIN_ID",
 * "pack:PACK/NAME.png" or "owned:NAME.png"). Options > Kirmod... > Skins... (GuiKirmodSkins) or the
 * /skin command change it.
 *
 * Vanilla draws a player's skin from RenderEngine's downloaded-image map, keyed by skinUrl. As with
 * capes, the chosen skin goes into that map under a key of our own and the player's skinUrl is
 * pointed at it every frame.
 */
public class KirSkins
{
    public static final String CONFIG_KEY = "skin";
    public static final String DEFAULT = "default";
    public static final String PACK_DIR = "/kirmod/skins/";
    public static final String PACK_INDEX = PACK_DIR + "index.txt";
    public static final String OWNED_DIR_NAME = "skins";

    private static final String KEY_PREFIX = "kirmod-skin:";
    private static final Field fieldImageData = KirUtil.findField(RenderEngine.class, "urlToImageDataMap", "i");

    private static List packs;
    private static String selectedId = DEFAULT;
    private static volatile List accountSkins = Collections.EMPTY_LIST;

    /** The image-map key the player's skin is drawn from, or null for the default skin; set by update(). */
    private static String appliedKey;

    /** Whether the applied skin is a square (64x64-style) image, which needs Model64. */
    private static boolean appliedSquare;
    private static volatile boolean applyPending = true;

    /** The player whose skinUrl we point at our skin, and the skinUrl the game gave it. */
    private static EntityPlayer skinnedPlayer;
    private static String originalSkinUrl;

    /** Reads the saved choice; called from mod_Kirmod.load(). KirCapes fetches the account's skin. */
    static void load()
    {
        selectedId = mod_Kirmod.getSetting(CONFIG_KEY, DEFAULT);
    }

    /* ------------------------------------------------------------------ Skin lists */

    /** One skin the player can pick. */
    public static class Skin
    {
        public final String id;
        public final String name;

        /** The pack a bundled skin is in, or null. */
        public final String pack;
        private final String resource;
        private final File file;

        /** Already downloaded image of an account skin. */
        private final BufferedImage image;

        Skin(String id, String name, String pack, String resource, File file, BufferedImage image)
        {
            this.id = id;
            this.name = name;
            this.pack = pack;
            this.resource = resource;
            this.file = file;
            this.image = image;
        }

        public String getDisplayName()
        {
            return this.name;
        }

        public boolean isAccount()
        {
            return this.id.startsWith("account:");
        }

        /** Where the skin comes from, for the settings screen. */
        public String getSource()
        {
            return this.isAccount() ? "Your account" : (this.pack != null ? this.pack : "Your skin folder");
        }

        /** Loads the skin as a 64x32-style image, or returns null (and logs) if it can't be read. */
        public BufferedImage loadImage()
        {
            if (this.image != null)
            {
                return this.image;
            }

            try
            {
                InputStream var1 = this.resource != null ? KirSkins.class.getResourceAsStream(this.resource) : new FileInputStream(this.file);

                if (var1 == null)
                {
                    return null;
                }

                try
                {
                    return toSkinImage(ImageIO.read(var1));
                }
                finally
                {
                    var1.close();
                }
            }
            catch (IOException var6)
            {
                KirUtil.log("Could not read skin " + (this.resource != null ? this.resource : this.file.getPath()), var6);
                return null;
            }
        }
    }

    /** A bundled skin pack. */
    public static class Pack
    {
        public final String name;
        public final List skins = new ArrayList();

        Pack(String name)
        {
            this.name = name;
        }
    }

    /**
     * The bundled packs, in the order of index.txt. A "[Pack name]" line starts a pack, whose PNGs are in
     * the folder of that name; a "FILE.png=Name" line adds a skin to it.
     */
    public static List getPacks()
    {
        if (packs == null)
        {
            packs = new ArrayList();
            InputStream var0 = KirSkins.class.getResourceAsStream(PACK_INDEX);

            if (var0 == null)
            {
                KirUtil.log("No skin packs found at " + PACK_INDEX, (Throwable)null);
                return packs;
            }

            try
            {
                BufferedReader var1 = new BufferedReader(new InputStreamReader(var0, "UTF-8"));
                Pack var2 = null;
                String var3;

                while ((var3 = var1.readLine()) != null)
                {
                    var3 = var3.trim();

                    if (var3.startsWith("[") && var3.endsWith("]"))
                    {
                        var2 = new Pack(var3.substring(1, var3.length() - 1));
                        packs.add(var2);
                    }
                    else if (var2 != null && !var3.startsWith("#") && var3.length() > 0)
                    {
                        int var4 = var3.indexOf('=');
                        String var5 = var4 < 0 ? var3 : var3.substring(0, var4).trim();
                        String var6 = var4 < 0 ? stripExtension(var5) : var3.substring(var4 + 1).trim();
                        String var7 = var2.name + "/" + var5;
                        var2.skins.add(new Skin("pack:" + var7, var6, var2.name, PACK_DIR + var7, (File)null, (BufferedImage)null));
                    }
                }

                var1.close();
            }
            catch (IOException var8)
            {
                KirUtil.log("Could not read " + PACK_INDEX, var8);
            }
        }

        return packs;
    }

    /** .minecraft/skins/, created if missing. */
    public static File getOwnedDir()
    {
        File var0 = new File(Minecraft.getMinecraftDir(), OWNED_DIR_NAME);
        var0.mkdirs();
        return var0;
    }

    /** The PNGs in .minecraft/skins/, sorted by name. Read fresh each call, so new files show up. */
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
                var0.add(new Skin("owned:" + var1[var2].getName(), stripExtension(var1[var2].getName()), (String)null, (String)null, var1[var2], (BufferedImage)null));
            }
        }

        return var0;
    }

    /**
     * Copies a skin PNG into .minecraft/skins/ (as NAME 2.png and so on if the name is taken) and returns
     * it. Throws with a message for the player if the file is missing, not a skin, or can't be copied.
     */
    public static Skin importSkin(File file) throws IOException
    {
        if (!file.isFile())
        {
            throw new IOException("File not found: " + file.getName());
        }

        BufferedImage var1;

        try
        {
            var1 = toSkinImage(ImageIO.read(file));
        }
        catch (IOException var13)
        {
            var1 = null;
        }

        if (var1 == null)
        {
            throw new IOException(file.getName() + " is not a skin (needs a 64x32 or 64x64 PNG)");
        }

        File var2 = getOwnedDir();

        if (file.getAbsoluteFile().getParentFile().equals(var2.getAbsoluteFile()))
        {
            return findById("owned:" + file.getName());
        }

        String var3 = stripExtension(file.getName());
        File var4 = new File(var2, var3 + ".png");

        for (int var5 = 2; var4.exists(); ++var5)
        {
            var4 = new File(var2, var3 + " " + var5 + ".png");
        }

        InputStream var6 = new FileInputStream(file);

        try
        {
            OutputStream var7 = new FileOutputStream(var4);

            try
            {
                byte[] var8 = new byte[8192];
                int var9;

                while ((var9 = var6.read(var8)) > 0)
                {
                    var7.write(var8, 0, var9);
                }
            }
            finally
            {
                var7.close();
            }
        }
        finally
        {
            var6.close();
        }

        return new Skin("owned:" + var4.getName(), stripExtension(var4.getName()), (String)null, (String)null, var4, (BufferedImage)null);
    }

    /** Finds a skin by id, or returns null (for "default" or a skin that is gone). */
    public static Skin findById(String id)
    {
        if (id == null || id.equals(DEFAULT))
        {
            return null;
        }

        List var1 = getAllSkins(id.startsWith("account:"), id.startsWith("owned:"), id.startsWith("pack:"));

        for (int var2 = 0; var2 < var1.size(); ++var2)
        {
            if (((Skin)var1.get(var2)).id.equals(id))
            {
                return (Skin)var1.get(var2);
            }
        }

        return null;
    }

    /** Finds an account, folder or pack skin by name, ignoring case and punctuation. */
    public static Skin findByName(String name)
    {
        String var1 = KirUtil.normalize(name);
        List var2 = getAllSkins(true, true, true);

        for (int var3 = 0; var3 < var2.size(); ++var3)
        {
            Skin var4 = (Skin)var2.get(var3);

            if (KirUtil.normalize(var4.name).equals(var1))
            {
                return var4;
            }
        }

        return null;
    }

    private static List getAllSkins(boolean account, boolean owned, boolean pack)
    {
        ArrayList var3 = new ArrayList();

        if (account)
        {
            var3.addAll(getAccountSkins());
        }

        if (owned)
        {
            var3.addAll(getOwned());
        }

        if (pack)
        {
            List var4 = getPacks();

            for (int var5 = 0; var5 < var4.size(); ++var5)
            {
                var3.addAll(((Pack)var4.get(var5)).skins);
            }
        }

        return var3;
    }

    /* ------------------------------------------------------------------ Account skins */

    /** The account's skins (in practice the one it wears); empty until KirCapes has fetched them. */
    public static List getAccountSkins()
    {
        return accountSkins;
    }

    /** Downloads the skins of a profile, given as KirCapes.parseProfileList(json, "skins") arrays. */
    static List downloadAccountSkins(List profileSkins)
    {
        ArrayList var1 = new ArrayList();

        for (int var2 = 0; var2 < profileSkins.size(); ++var2)
        {
            String[] var3 = (String[])profileSkins.get(var2);
            BufferedImage var4 = toSkinImage(KirCapes.download(var3[1]));

            if (var4 != null)
            {
                String var5 = "SLIM".equalsIgnoreCase(var3[2]) ? " (slim arms)" : "";
                var1.add(new Skin("account:" + var3[0], "Account Skin" + var5, (String)null, (String)null, (File)null, var4));
            }
        }

        return Collections.unmodifiableList(var1);
    }

    static void setAccountSkins(List skins)
    {
        accountSkins = skins;

        if (selectedId.startsWith("account:"))
        {
            applyPending = true;
        }
    }

    /* ------------------------------------------------------------------ Selection */

    public static String getSelectedId()
    {
        return selectedId;
    }

    public static Skin getSelected()
    {
        return findById(selectedId);
    }

    /** Wears the skin (null for the default one), saves the choice and updates the player straight away. */
    public static void select(Skin skin)
    {
        selectedId = skin == null ? DEFAULT : skin.id;
        applyPending = true;
        mod_Kirmod.setSetting(CONFIG_KEY, selectedId);
    }

    /** Re-reads the chosen skin's file, e.g. after it was edited. */
    public static void reload()
    {
        applyPending = true;
    }

    /* ------------------------------------------------------------------ Drawing it on the player */

    /** Called every frame: loads the chosen skin if needed and points the player's skin at it. */
    static void update(Minecraft mc)
    {
        if (fieldImageData == null || mc.renderEngine == null)
        {
            return;
        }

        if (!applyPending && appliedKey != null)
        {
            try
            {
                // The world releases a player's skin when it unloads them, which drops our entry.
                applyPending = !((Map)fieldImageData.get(mc.renderEngine)).containsKey(appliedKey);
            }
            catch (IllegalAccessException var2)
            {
                return;
            }
        }

        if (applyPending)
        {
            applyPending = false;
            applySkin(mc.renderEngine);
        }

        EntityClientPlayerMP var1 = mc.thePlayer;

        if (var1 != skinnedPlayer)
        {
            skinnedPlayer = var1;
            originalSkinUrl = var1 != null ? var1.skinUrl : null;
        }

        if (var1 != null)
        {
            var1.skinUrl = appliedKey != null ? appliedKey : originalSkinUrl;
        }
    }

    /** Replaces the skin image in the render engine's image map with the chosen one. */
    private static void applySkin(RenderEngine renderEngine)
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
                appliedSquare = false;
            }

            Skin var3 = getSelected();
            BufferedImage var4 = var3 != null ? var3.loadImage() : null;

            if (var4 != null)
            {
                ThreadDownloadImageData var5 = newImageData(var4);
                // A new key each time, so nothing else holding the old one can draw a stale texture.
                appliedKey = KEY_PREFIX + var3.id + "#" + System.nanoTime();
                appliedSquare = isSquare(var4);
                var1.put(appliedKey, var5);
            }
            else if (var3 == null && !DEFAULT.equals(selectedId) && !selectedId.startsWith("account:"))
            {
                KirUtil.log("Skin '" + selectedId + "' is gone, showing the default skin", (Throwable)null);
            }
        }
        catch (Exception var6)
        {
            KirUtil.log("Could not apply the skin", var6);
        }
    }

    /** Whether the player wears a 64x64-style skin, so PlayerRenderer should draw them with Model64. */
    static boolean isWideSkinApplied()
    {
        return appliedSquare && appliedKey != null;
    }

    /** An image-map entry that is already loaded; see KirCapes.newImageData. */
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
     * Checks a skin image: 2:1 images (64x32) and square ones (64x64 skins with their outer layer), or HD
     * multiples of either, are accepted; anything else is refused (null). As vanilla does for downloaded
     * skins, a hat layer without any transparent pixel (an old skin that filled it in) is cleared, so it
     * doesn't cover the head.
     */
    public static BufferedImage toSkinImage(BufferedImage image)
    {
        if (image == null)
        {
            return null;
        }

        int var1 = image.getWidth();
        int var2 = image.getHeight();

        if (var1 < 64 || var1 % 64 != 0 || var2 != var1 / 2 && var2 != var1)
        {
            return null;
        }

        BufferedImage var3 = new BufferedImage(var1, var2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D var4 = var3.createGraphics();
        var4.drawImage(image, 0, 0, null);
        var4.dispose();
        int var5 = var1 / 64;
        boolean var6 = false;

        for (int var7 = 32 * var5; var7 < 64 * var5 && !var6; ++var7)
        {
            for (int var8 = 0; var8 < 16 * var5; ++var8)
            {
                if ((var3.getRGB(var7, var8) >>> 24) < 128)
                {
                    var6 = true;
                    break;
                }
            }
        }

        if (!var6)
        {
            for (int var9 = 32 * var5; var9 < 64 * var5; ++var9)
            {
                for (int var10 = 0; var10 < 16 * var5; ++var10)
                {
                    var3.setRGB(var9, var10, var3.getRGB(var9, var10) & 0xFFFFFF);
                }
            }
        }

        return var3;
    }

    /** Whether a skin image is square, i.e. a 64x64 skin with a separate left arm and leg and an outer layer. */
    public static boolean isSquare(BufferedImage image)
    {
        return image != null && image.getWidth() == image.getHeight();
    }

    private static String stripExtension(String fileName)
    {
        return fileName.toLowerCase().endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    /** Opens .minecraft/skins/ in the system file manager. */
    public static void openOwnedDir()
    {
        KirCapes.openDir(getOwnedDir());
    }

    /* ------------------------------------------------------------------ 64x64 skins */

    /**
     * The player model for 64x64 skins: the vanilla parts mapped onto a 64x64 texture, the left arm and leg
     * with their own texels instead of mirrored right ones, and the outer layer (jacket, sleeves, trouser
     * legs) as slightly larger boxes. The outer boxes are children of their limb, so they move with it.
     */
    public static class Model64 extends ModelBiped
    {
        public ModelRenderer bipedBodyWear;
        public ModelRenderer bipedRightArmWear;
        public ModelRenderer bipedLeftArmWear;
        public ModelRenderer bipedRightLegWear;
        public ModelRenderer bipedLeftLegWear;

        /** The model RenderPlayer poses (held item, sneaking, bow), copied before each render; may be null. */
        private final ModelBiped poseSource;

        public Model64(ModelBiped poseSource)
        {
            super(0.0F);
            this.poseSource = poseSource;
            // The vanilla parts were built for a 64x32 texture; build them again for 64x64. The cloak and
            // ears keep theirs, since they are drawn with the cape texture.
            this.boxList.clear();
            this.textureHeight = 64;
            this.bipedHead = new ModelRenderer(this, 0, 0);
            this.bipedHead.addBox(-4.0F, -8.0F, -4.0F, 8, 8, 8, 0.0F);
            this.bipedHeadwear = new ModelRenderer(this, 32, 0);
            this.bipedHeadwear.addBox(-4.0F, -8.0F, -4.0F, 8, 8, 8, 0.5F);
            this.bipedBody = new ModelRenderer(this, 16, 16);
            this.bipedBody.addBox(-4.0F, 0.0F, -2.0F, 8, 12, 4, 0.0F);
            this.bipedBodyWear = this.addWear(this.bipedBody, 16, 32, -4.0F, 0.0F, -2.0F, 8);
            this.bipedRightArm = new ModelRenderer(this, 40, 16);
            this.bipedRightArm.addBox(-3.0F, -2.0F, -2.0F, 4, 12, 4, 0.0F);
            this.bipedRightArm.setRotationPoint(-5.0F, 2.0F, 0.0F);
            this.bipedRightArmWear = this.addWear(this.bipedRightArm, 40, 32, -3.0F, -2.0F, -2.0F, 4);
            this.bipedLeftArm = new ModelRenderer(this, 32, 48);
            this.bipedLeftArm.addBox(-1.0F, -2.0F, -2.0F, 4, 12, 4, 0.0F);
            this.bipedLeftArm.setRotationPoint(5.0F, 2.0F, 0.0F);
            this.bipedLeftArmWear = this.addWear(this.bipedLeftArm, 48, 48, -1.0F, -2.0F, -2.0F, 4);
            this.bipedRightLeg = new ModelRenderer(this, 0, 16);
            this.bipedRightLeg.addBox(-2.0F, 0.0F, -2.0F, 4, 12, 4, 0.0F);
            this.bipedRightLeg.setRotationPoint(-2.0F, 12.0F, 0.0F);
            this.bipedRightLegWear = this.addWear(this.bipedRightLeg, 0, 32, -2.0F, 0.0F, -2.0F, 4);
            this.bipedLeftLeg = new ModelRenderer(this, 16, 48);
            this.bipedLeftLeg.addBox(-2.0F, 0.0F, -2.0F, 4, 12, 4, 0.0F);
            this.bipedLeftLeg.setRotationPoint(2.0F, 12.0F, 0.0F);
            this.bipedLeftLegWear = this.addWear(this.bipedLeftLeg, 0, 48, -2.0F, 0.0F, -2.0F, 4);
        }

        /** Adds an outer-layer box, a quarter texel larger all round, as a child of a part. */
        private ModelRenderer addWear(ModelRenderer parent, int u, int v, float x, float y, float z, int width)
        {
            ModelRenderer var8 = new ModelRenderer(this, u, v);
            var8.addBox(x, y, z, width, 12, 4, 0.25F);
            parent.addChild(var8);
            return var8;
        }

        /**
         * Sets the models various rotation angles then renders the model.
         */
        public void render(Entity par1Entity, float par2, float par3, float par4, float par5, float par6, float par7)
        {
            if (this.poseSource != null)
            {
                this.heldItemLeft = this.poseSource.heldItemLeft;
                this.heldItemRight = this.poseSource.heldItemRight;
                this.isSneak = this.poseSource.isSneak;
                this.aimedBow = this.poseSource.aimedBow;
            }

            super.render(par1Entity, par2, par3, par4, par5, par6, par7);
        }
    }

    /**
     * The renderer of the player's own entity: vanilla, except that while a 64x64 skin is worn the body
     * and the first-person hand are drawn with Model64. Registered by mod_Kirmod.addRenderer() for
     * EntityClientPlayerMP only, so other players keep the vanilla renderer.
     */
    public static class PlayerRenderer extends RenderPlayer
    {
        private final ModelBase vanillaModel;
        private final Model64 wideModel;

        public PlayerRenderer()
        {
            this.vanillaModel = this.mainModel;
            this.wideModel = new Model64((ModelBiped)this.mainModel);
        }

        private static boolean useWideModel(Entity entity)
        {
            Minecraft var1 = ModLoader.getMinecraftInstance();
            return isWideSkinApplied() && var1 != null && entity == var1.thePlayer;
        }

        /**
         * Actually renders the given argument. This is a synthetic bridge method, always casting down its argument and then
         * handing it off to a worker function which does the actual work.
         */
        public void doRenderShadowAndFire(Entity par1Entity, double par2, double par4, double par6, float par8, float par9)
        {
            if (!KirCamera.hidesPlayer(par1Entity))
            {
                super.doRenderShadowAndFire(par1Entity, par2, par4, par6, par8, par9);
            }
        }

        public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9)
        {
            if (KirCamera.hidesPlayer(par1Entity))
            {
                // A panorama is being taken from inside this player's head.
                return;
            }

            if (!useWideModel(par1Entity))
            {
                super.doRender(par1Entity, par2, par4, par6, par8, par9);
                return;
            }

            this.mainModel = this.wideModel;

            try
            {
                super.doRender(par1Entity, par2, par4, par6, par8, par9);
            }
            finally
            {
                this.mainModel = this.vanillaModel;
            }
        }

        public void drawFirstPersonHand()
        {
            Minecraft var1 = ModLoader.getMinecraftInstance();

            if (!useWideModel(var1 != null ? var1.thePlayer : null))
            {
                super.drawFirstPersonHand();
                return;
            }

            this.wideModel.onGround = 0.0F;
            this.wideModel.setRotationAngles(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0625F);
            this.wideModel.bipedRightArm.render(0.0625F);
        }
    }

    /* ------------------------------------------------------------------ Command */

    static void registerCommand()
    {
        mod_CommandLib.registerCommand(new ClientCommand("skin", "[gui | default | list [pack] | <name>]", "Picks the skin you wear (only you see it)")
        {
            public void execute(String[] args)
            {
                String var2 = KirCapes.joinArgs(args);

                if (var2.length() == 0 || var2.equalsIgnoreCase("gui"))
                {
                    mod_Kirmod.openScreenNextTick(new GuiKirmodSkins((GuiScreen)null));
                }
                else if (var2.equalsIgnoreCase(DEFAULT))
                {
                    KirSkins.select((Skin)null);
                    this.reply("Skin: default");
                }
                else if (var2.equalsIgnoreCase("list"))
                {
                    this.reply("§eYour account: §f" + (KirCapes.isLoggedIn() ? joinNames(KirSkins.getAccountSkins()) : "not logged in"));
                    this.reply("§eSkin folder: §f" + joinNames(KirSkins.getOwned()));
                    StringBuilder var3 = new StringBuilder();
                    List var4 = KirSkins.getPacks();

                    for (int var5 = 0; var5 < var4.size(); ++var5)
                    {
                        Pack var6 = (Pack)var4.get(var5);
                        var3.append(var5 > 0 ? ", " : "").append(var6.name).append(" (").append(var6.skins.size()).append(")");
                    }

                    this.reply("§eSkin packs: §f" + var3 + " §7- /skin list <pack> lists one");
                }
                else if (var2.toLowerCase().startsWith("list "))
                {
                    String var7 = KirUtil.normalize(var2.substring(5));
                    List var8 = KirSkins.getPacks();

                    for (int var9 = 0; var9 < var8.size(); ++var9)
                    {
                        Pack var10 = (Pack)var8.get(var9);

                        if (KirUtil.normalize(var10.name).equals(var7) || KirUtil.normalize(var10.name.replace(" Skin Pack", "")).equals(var7))
                        {
                            this.reply("§e" + var10.name + ": §f" + joinNames(var10.skins));
                            return;
                        }
                    }

                    throw new ClientCommandException("No skin pack called '" + var2.substring(5).trim() + "'. Try /skin list");
                }
                else
                {
                    Skin var11 = KirSkins.findByName(var2);

                    if (var11 == null)
                    {
                        throw new ClientCommandException("No skin called '" + var2 + "'. Try /skin list");
                    }

                    KirSkins.select(var11);
                    this.reply("Skin: " + var11.getDisplayName());
                }
            }
        });
    }

    private static String joinNames(List skins)
    {
        if (skins.isEmpty())
        {
            return "(none)";
        }

        StringBuilder var1 = new StringBuilder();

        for (int var2 = 0; var2 < skins.size(); ++var2)
        {
            var1.append(var2 > 0 ? ", " : "").append(((Skin)skins.get(var2)).name);
        }

        return var1.toString();
    }
}
