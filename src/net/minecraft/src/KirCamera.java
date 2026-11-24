package net.minecraft.src;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/**
 * The camera of Freecam and of isometric screenshots (Kirmod > Camera...).
 *
 * Both work by pointing mc.renderViewEntity, the entity the world is drawn from, at a Camera that is not
 * in the world. The player stays where it is, is drawn like any other player, and the server never
 * hears of the camera. EntityRenderer casts the view entity to EntityPlayerSP, so Camera is one.
 *
 * Freecam: the player's movement input is swapped for a still one, and the camera moves itself once a
 * frame from the movement keys, at a constant speed, without collisions. Vanilla turns the player with
 * the mouse; the camera turns by the same amount and the player is turned back, so it keeps looking
 * where it was. The frame's update runs from Camera.rayTrace(), which EntityRenderer calls for the
 * block under the crosshair just before it draws the world; it returns nothing, so the player can't
 * click on what the camera sees.
 *
 * Isometric screenshots: F2 (while Isometric Screenshots is on) moves the view to a camera high up on
 * one side of the player, looking down at the isometric angle, hides the HUD, waits for the chunks
 * there to be built, draws the world once more on its own (so other mods' HUDs aren't in it) and saves
 * that. Vanilla's own F2 screenshot is held off through Minecraft.isTakingScreenshot.
 *
 * Panorama screenshots (from PanoramaAssist): F2 (while Panorama Screenshots is on) or /panorama puts a
 * fixed camera at the eyes of whoever has the view, snaps it to the nearest quarter turn, and takes six
 * square 90-degree shots (four around, then up and down) the same way, saved as panorama0.png to
 * panorama5.png: the names and order of the title screen's panorama in a texture pack (title/bg/).
 *
 * Every camera also needs the first-person hand and the overlays (fire, water, inside a block) hidden,
 * as they belong to the player: mc.entityRenderer.itemRenderer is swapped for a KirItemRenderer the
 * first time a camera is used.
 */
public class KirCamera
{
    private static final Field fieldTakingScreenshot = KirUtil.findField(Minecraft.class, "isTakingScreenshot", "F");
    private static final Field fieldRenderersToUpdate = KirUtil.findField(RenderGlobal.class, "worldRenderersToUpdate", "j");

    /** Isometric view: the camera looks down at atan(1 / sqrt 2), about 35.26 degrees. */
    public static final float ISOMETRIC_PITCH = (float)Math.toDegrees(Math.atan(1.0D / Math.sqrt(2.0D)));

    /** An isometric screenshot waits at most this long for the chunks around its camera, in ms. */
    private static final long ISOMETRIC_TIMEOUT = 5000L;

    /** Freecam's camera, or null while Freecam is off. */
    private static Camera freecam;

    /** How fast Freecam's camera flies, in blocks per second (Freecam Speed, in Kirmod Extras). */
    private static int freecamSpeed = 10;
    private static EntityPlayerSP freecamPlayer;
    private static MovementInput playerInput;

    /** The isometric screenshot being taken, or null. */
    private static Camera isoCamera;
    private static long isoStart;
    private static int isoFrames;
    private static EntityLiving isoPreviousView;
    private static boolean isoPreviousHideGUI;
    private static float isoPreviousFov;
    private static int isoPreviousThirdPerson;

    /** The panorama being taken, or null. */
    private static Camera panoCamera;
    private static int panoFace;
    private static long panoFaceStart;
    private static int panoFrames;
    private static float panoYaw;
    private static File panoDir;
    private static EntityLiving panoPreviousView;
    private static boolean panoPreviousHideGUI;
    private static float panoPreviousFov;
    private static int panoPreviousThirdPerson;

    /** Whether F2 was down last frame for panoramas. */
    private static boolean panoramaKeyDown = false;

    /** Size of each panorama face, in pixels. */
    private static final int PANORAMA_SIZE = 1024;

    /** Whether F2 was down last frame, so one press takes one screenshot. */
    private static boolean screenshotKeyDown = false;

    /** True while a Kirmod camera is drawing the world. */
    public static boolean isActive(Minecraft mc)
    {
        return mc.renderViewEntity instanceof Camera;
    }

    public static boolean isFreecamOn()
    {
        return freecam != null;
    }

    /* ------------------------------------------------------------------ Freecam */

    /**
     * Starts or stops Freecam to match its setting (Freecam, in Kirmod Extras). Called every frame, after
     * the world is drawn. Returns true when a new world or a respawn stopped it, so the setting goes Off.
     */
    public static boolean updateFreecam(Minecraft mc, boolean on)
    {
        boolean var3 = false;

        if (isoCamera != null || panoCamera != null)
        {
            // The isometric camera has the view for now; it gives it back when it is done.
            return false;
        }

        if (freecam != null && (mc.thePlayer != freecamPlayer || mc.renderViewEntity != freecam))
        {
            // A new world or a respawn gave the view back to the (new) player.
            stopFreecam(mc);

            if (on)
            {
                var3 = true;
                on = false;
            }
        }

        on = on && mc.theWorld != null && mc.thePlayer != null;

        if (on && freecam == null)
        {
            startFreecam(mc);
        }
        else if (!on && freecam != null)
        {
            stopFreecam(mc);
        }

        if (freecam != null)
        {
            // Third person would orbit the camera.
            mc.gameSettings.thirdPersonView = 0;
        }

        return var3;
    }

    /** Sets how fast Freecam's camera flies, in blocks per second. */
    public static void setFreecamSpeed(int blocksPerSecond)
    {
        freecamSpeed = blocksPerSecond;
    }

    private static void startFreecam(Minecraft mc)
    {
        KirItemRenderer.install(mc);
        EntityPlayerSP var1 = mc.thePlayer;
        freecam = new Camera(mc, true);
        freecam.moveTo(var1.posX, var1.posY, var1.posZ, var1.rotationYaw, var1.rotationPitch);
        freecamPlayer = var1;
        playerInput = var1.movementInput;
        var1.movementInput = new MovementInput();
        freecam.playerYaw = var1.rotationYaw;
        freecam.playerPitch = var1.rotationPitch;
        mc.renderViewEntity = freecam;
    }

    private static void stopFreecam(Minecraft mc)
    {
        if (freecamPlayer != null && freecamPlayer == mc.thePlayer)
        {
            freecamPlayer.movementInput = playerInput;
        }

        if (mc.renderViewEntity == freecam)
        {
            mc.renderViewEntity = mc.thePlayer;
        }

        freecam = null;
        freecamPlayer = null;
        playerInput = null;
    }

    /* ------------------------------------------------------------------ Isometric screenshots */

    /**
     * Takes an isometric screenshot when F2 is pressed, and steps one that is being taken. Called every
     * frame, after the world is drawn and before vanilla checks F2.
     */
    static void updateIsometric(Minecraft mc, boolean on)
    {
        boolean var2 = Keyboard.isKeyDown(Keyboard.KEY_F2) && on;

        if (var2 && fieldTakingScreenshot != null)
        {
            try
            {
                // Vanilla thinks F2 is still held from its last screenshot and takes none.
                fieldTakingScreenshot.setBoolean(mc, true);
            }
            catch (IllegalAccessException var4)
            {
                ;
            }
        }

        if (var2 && !screenshotKeyDown && isoCamera == null && panoCamera == null && mc.theWorld != null && mc.renderViewEntity != null && mc.currentScreen == null)
        {
            startIsometric(mc);
        }

        screenshotKeyDown = var2;

        if (isoCamera != null)
        {
            stepIsometric(mc);
        }
    }

    private static void startIsometric(Minecraft mc)
    {
        KirItemRenderer.install(mc);
        EntityLiving var1 = mc.renderViewEntity;
        isoPreviousView = var1;
        isoPreviousHideGUI = mc.gameSettings.hideGUI;
        isoPreviousFov = mc.gameSettings.fovSetting;
        isoPreviousThirdPerson = mc.gameSettings.thirdPersonView;

        // Looking at the eyes of whoever has the view (the player, or Freecam's camera).
        double var2 = var1.posX;
        double var4 = var1.posY;
        double var6 = var1.posZ;
        int[] var8 = mod_Kirmod.ISOMETRIC_DIRECTION.getOffset();
        double var9 = (double)mod_Kirmod.ISOMETRIC_DISTANCE.getDistance();
        double var11 = Math.toRadians((double)ISOMETRIC_PITCH);
        double var13 = Math.cos(var11) * var9 / Math.sqrt(2.0D);
        float var15 = (float)Math.toDegrees(Math.atan2((double)var8[0], (double)(-var8[1])));
        isoCamera = new Camera(mc, false);
        isoCamera.moveTo(var2 + (double)var8[0] * var13, var4 + Math.sin(var11) * var9,var6 + (double)var8[1] * var13, var15, ISOMETRIC_PITCH);
        isoStart = System.currentTimeMillis();
        isoFrames = 0;
        mc.renderViewEntity = isoCamera;
        mc.gameSettings.hideGUI = true;
        mc.gameSettings.fovSetting = 0.0F;
        mc.gameSettings.thirdPersonView = 0;
    }

    /** Waits for the chunks to be built, then saves the shot and gives the view back. */
    private static void stepIsometric(Minecraft mc)
    {
        if (mc.theWorld == null || mc.renderViewEntity != isoCamera)
        {
            // Left the world, or something else took the view: give up quietly.
            endIsometric(mc, false);
            return;
        }

        ++isoFrames;

        if (isoFrames < 4 || !renderersBuilt(mc) && System.currentTimeMillis() - isoStart < ISOMETRIC_TIMEOUT)
        {
            return;
        }

        String var1;

        try
        {
            // Drawn again on its own, so the shot has no HUD from another mod drawn after the world.
            mc.entityRenderer.renderWorld(1.0F, 0L);
            var1 = ScreenShotHelper.func_74292_a(Minecraft.getMinecraftDir(), getScreenshotName(), mc.displayWidth, mc.displayHeight);
            mc.entityRenderer.setupOverlayRendering();
        }
        catch (Throwable var3)
        {
            KirUtil.log("Could not take an isometric screenshot", var3);
            var1 = "Could not take an isometric screenshot: " + var3;
        }

        endIsometric(mc, true);

        if (mc.ingameGUI != null)
        {
            mc.ingameGUI.getChatGUI().printChatMessage(var1);
        }
    }

    private static void endIsometric(Minecraft mc, boolean restoreView)
    {
        if (restoreView || mc.renderViewEntity == isoCamera)
        {
            mc.renderViewEntity = isoPreviousView != null && isoPreviousView.worldObj == mc.theWorld ? isoPreviousView : mc.thePlayer;
        }

        mc.gameSettings.hideGUI = isoPreviousHideGUI;
        mc.gameSettings.fovSetting = isoPreviousFov;
        mc.gameSettings.thirdPersonView = isoPreviousThirdPerson;
        isoCamera = null;
        isoPreviousView = null;
    }

    /* ------------------------------------------------------------------ Panorama screenshots */

    /**
     * Whether an entity must not be drawn: the player, while a panorama is taken from their eyes (it would
     * be taken from inside their head). KirSkins' player renderer asks.
     */
    public static boolean hidesPlayer(Entity entity)
    {
        return panoCamera != null && entity == panoPreviousView;
    }

    /**
     * Takes a panorama when F2 is pressed (while on), and steps one that is being taken. Called every
     * frame, after the world is drawn and before vanilla checks F2.
     */
    static void updatePanorama(Minecraft mc, boolean on)
    {
        boolean var2 = Keyboard.isKeyDown(Keyboard.KEY_F2) && on;

        if (var2 && fieldTakingScreenshot != null)
        {
            try
            {
                fieldTakingScreenshot.setBoolean(mc, true);
            }
            catch (IllegalAccessException var4)
            {
                ;
            }
        }

        if (var2 && !panoramaKeyDown && mc.currentScreen == null)
        {
            startPanorama(mc);
        }

        panoramaKeyDown = var2;

        if (panoCamera != null)
        {
            stepPanorama(mc);
        }
    }

    /** Starts a panorama, from F2 or /panorama. Returns false if the view is busy or there's no world. */
    static boolean startPanorama(Minecraft mc)
    {
        if (panoCamera != null || isoCamera != null || mc.theWorld == null || mc.renderViewEntity == null)
        {
            return false;
        }

        KirItemRenderer.install(mc);
        EntityLiving var1 = mc.renderViewEntity;
        panoPreviousView = var1;
        panoPreviousHideGUI = mc.gameSettings.hideGUI;
        panoPreviousFov = mc.gameSettings.fovSetting;
        panoPreviousThirdPerson = mc.gameSettings.thirdPersonView;
        float var2 = var1.rotationYaw % 360.0F;
        panoYaw = (float)Math.round((var2 < 0.0F ? var2 + 360.0F : var2) / 90.0F) * 90.0F % 360.0F;
        panoCamera = new Camera(mc, false);
        panoCamera.moveTo(var1.posX, var1.posY, var1.posZ, panoYaw, 0.0F);
        panoFace = 0;
        panoFrames = 0;
        panoFaceStart = System.currentTimeMillis();
        String var3 = (new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss")).format(new Date()) + "_panorama";
        File var4 = new File(Minecraft.getMinecraftDir(), "screenshots");
        panoDir = new File(var4, var3);

        for (int var5 = 2; panoDir.exists(); ++var5)
        {
            panoDir = new File(var4, var3 + "_" + var5);
        }

        mc.renderViewEntity = panoCamera;
        mc.gameSettings.hideGUI = true;
        mc.gameSettings.thirdPersonView = 0;
        setPanoramaFov(mc);

        if (mc.ingameGUI != null)
        {
            mc.ingameGUI.getChatGUI().printChatMessage("Taking a panorama facing " + new String[] {"south", "west", "north", "east"}[(int)(panoYaw / 90.0F) & 3] + "...");
        }

        return true;
    }

    /** A vertical field of view that makes the middle square of the screen exactly 90 degrees wide. */
    private static void setPanoramaFov(Minecraft mc)
    {
        float var1 = mc.displayWidth >= mc.displayHeight ? 90.0F : (float)Math.toDegrees(2.0D * Math.atan((double)mc.displayHeight / (double)mc.displayWidth));
        mc.gameSettings.fovSetting = (var1 - 70.0F) / 40.0F;
    }

    private static void stepPanorama(Minecraft mc)
    {
        if (mc.theWorld == null || mc.renderViewEntity != panoCamera)
        {
            endPanorama(mc, false);
            return;
        }

        // Faces 0-3 turn a quarter each to the right; 4 looks up and 5 down (PanoramaAssist's order).
        float var1 = (panoYaw + (panoFace < 4 ? (float)panoFace * 90.0F : 0.0F)) % 360.0F;
        float var2 = panoFace == 4 ? -90.0F : (panoFace == 5 ? 90.0F : 0.0F);
        panoCamera.moveTo(panoCamera.posX, panoCamera.posY, panoCamera.posZ, var1, var2);
        setPanoramaFov(mc);
        ++panoFrames;

        // A few frames for the view to settle (the FOV eases in), then wait for the chunks in view.
        if (panoFrames < 6 || System.currentTimeMillis() - panoFaceStart < 250L || !renderersBuilt(mc) && System.currentTimeMillis() - panoFaceStart < ISOMETRIC_TIMEOUT)
        {
            return;
        }

        try
        {
            mc.entityRenderer.renderWorld(1.0F, 0L);
            savePanoramaFace(mc, panoFace);
            mc.entityRenderer.setupOverlayRendering();
        }
        catch (Throwable var4)
        {
            KirUtil.log("Could not take a panorama", var4);
            endPanorama(mc, true);

            if (mc.ingameGUI != null)
            {
                mc.ingameGUI.getChatGUI().printChatMessage("Could not take a panorama: " + var4);
            }

            return;
        }

        ++panoFace;
        panoFrames = 0;
        panoFaceStart = System.currentTimeMillis();

        if (panoFace >= 6)
        {
            File var3 = panoDir;
            endPanorama(mc, true);

            if (mc.ingameGUI != null)
            {
                mc.ingameGUI.getChatGUI().printChatMessage("Saved panorama in screenshots/" + var3.getName());
            }
        }
    }

    /** Reads the middle square of the screen and saves it as panoramaN.png, PANORAMA_SIZE pixels wide. */
    private static void savePanoramaFace(Minecraft mc, int face) throws Exception
    {
        int var2 = mc.displayWidth;
        int var3 = mc.displayHeight;
        GL11.glFinish();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        ByteBuffer var4 = BufferUtils.createByteBuffer(var2 * var3 * 3);
        GL11.glReadPixels(0, 0, var2, var3, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, var4);
        int var5 = Math.min(var2, var3);
        int var6 = (var2 - var5) / 2;
        int var7 = (var3 - var5) / 2;
        BufferedImage var8 = new BufferedImage(var5, var5, BufferedImage.TYPE_INT_RGB);

        for (int var9 = 0; var9 < var5; ++var9)
        {
            // OpenGL rows go bottom to top.
            int var10 = var3 - 1 - (var7 + var9);

            for (int var11 = 0; var11 < var5; ++var11)
            {
                int var12 = (var10 * var2 + var6 + var11) * 3;
                var8.setRGB(var11, var9, (var4.get(var12) & 255) << 16 | (var4.get(var12 + 1) & 255) << 8 | var4.get(var12 + 2) & 255);
            }
        }

        BufferedImage var13 = new BufferedImage(PANORAMA_SIZE, PANORAMA_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D var14 = var13.createGraphics();
        var14.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        var14.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        var14.drawImage(var8, 0, 0, PANORAMA_SIZE, PANORAMA_SIZE, null);
        var14.dispose();
        panoDir.mkdirs();
        ImageIO.write(var13, "png", new File(panoDir, "panorama" + face + ".png"));
    }

    private static void endPanorama(Minecraft mc, boolean restoreView)
    {
        if (restoreView || mc.renderViewEntity == panoCamera)
        {
            mc.renderViewEntity = panoPreviousView != null && panoPreviousView.worldObj == mc.theWorld ? panoPreviousView : mc.thePlayer;
        }

        mc.gameSettings.hideGUI = panoPreviousHideGUI;
        mc.gameSettings.fovSetting = panoPreviousFov;
        mc.gameSettings.thirdPersonView = panoPreviousThirdPerson;
        panoCamera = null;
        panoPreviousView = null;
    }

    /** True once no chunk around the camera is waiting to be built. */
    private static boolean renderersBuilt(Minecraft mc)
    {
        if (fieldRenderersToUpdate == null || mc.renderGlobal == null)
        {
            return true;
        }

        try
        {
            return ((List)fieldRenderersToUpdate.get(mc.renderGlobal)).isEmpty();
        }
        catch (IllegalAccessException var2)
        {
            return true;
        }
    }

    /** DATE_isometric.png, like vanilla's names, with _2, _3... if taken. */
    private static String getScreenshotName()
    {
        String var0 = (new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss")).format(new Date()) + "_isometric";
        File var1 = new File(Minecraft.getMinecraftDir(), "screenshots");
        String var2 = var0 + ".png";

        for (int var3 = 2; (new File(var1, var2)).exists(); ++var3)
        {
            var2 = var0 + "_" + var3 + ".png";
        }

        return var2;
    }

    /* ------------------------------------------------------------------ The camera */

    /**
     * A view entity that is never added to the world. A free camera moves itself every frame from the
     * movement keys and the mouse; a fixed one (isometric) stays where it was put.
     */
    static class Camera extends EntityPlayerSP
    {
        private final boolean free;

        /** The player's look when Freecam started; the player is turned back to it every frame. */
        float playerYaw;
        float playerPitch;

        private long lastFrame = System.nanoTime();

        Camera(Minecraft mc, boolean free)
        {
            super(mc, mc.theWorld, mc.session, mc.thePlayer != null ? mc.thePlayer.dimension : 0);
            this.free = free;
            this.noClip = true;
        }

        void moveTo(double x, double y, double z, float yaw, float pitch)
        {
            this.setPositionAndRotation(x, y, z, yaw, pitch);
            this.prevPosX = this.lastTickPosX = x;
            this.prevPosY = this.lastTickPosY = y;
            this.prevPosZ = this.lastTickPosZ = z;
            this.prevRotationYaw = yaw;
            this.prevRotationPitch = pitch;
        }

        /**
         * Called by EntityRenderer.getMouseOver(): every frame before the world is drawn (with the frame's
         * partial tick, below 1), and every tick before clicks are handled (with 1). Moves a free camera
         * on the frame's call, and never points at a block.
         */
        public MovingObjectPosition rayTrace(double par1, float par3)
        {
            if (this.free && par3 < 1.0F)
            {
                this.updateFrame();
            }

            return null;
        }

        /** No direction, so EntityRenderer finds no entity under the crosshair either. */
        public Vec3 getLook(float par1)
        {
            return Vec3.getVec3Pool().getVecFromPool(0.0D, 0.0D, 0.0D);
        }

        private void updateFrame()
        {
            long var1 = System.nanoTime();
            double var3 = Math.min(0.1D, (double)(var1 - this.lastFrame) / 1.0E9D);
            this.lastFrame = var1;
            EntityPlayerSP var5 = this.mc.thePlayer;
            float var6 = this.rotationYaw;
            float var7 = this.rotationPitch;

            if (this.mc.inGameHasFocus && var5 != null)
            {
                // The turn vanilla just gave the player (EntityRenderer.updateCameraAndRender and
                // Entity.setAngles), taken by the camera instead.
                float var8 = this.mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
                float var9 = var8 * var8 * var8 * 8.0F;
                var6 += (float)this.mc.mouseHelper.deltaX * var9 * 0.15F;
                var7 -= (float)this.mc.mouseHelper.deltaY * var9 * (this.mc.gameSettings.invertMouse ? -1.0F : 1.0F) * 0.15F;
                var7 = Math.max(-90.0F, Math.min(90.0F, var7));
                var5.rotationYaw = var5.prevRotationYaw = this.playerYaw;
                var5.rotationPitch = var5.prevRotationPitch = this.playerPitch;
            }

            double var16 = 0.0D;
            double var18 = 0.0D;
            double var20 = 0.0D;

            if (this.mc.currentScreen == null)
            {
                GameSettings var10 = this.mc.gameSettings;
                double var11 = (double)((var10.keyBindForward.pressed ? 1 : 0) - (var10.keyBindBack.pressed ? 1 : 0));
                double var13 = (double)((var10.keyBindLeft.pressed ? 1 : 0) - (var10.keyBindRight.pressed ? 1 : 0));
                double var22 = Math.toRadians((double)var6);
                var16 = -Math.sin(var22) * var11 + Math.cos(var22) * var13;
                var20 = Math.cos(var22) * var11 + Math.sin(var22) * var13;
                var18 = (double)((var10.keyBindJump.pressed ? 1 : 0) - (var10.keyBindSneak.pressed ? 1 : 0));
            }

            double var24 = Math.sqrt(var16 * var16 + var18 * var18 + var20 * var20);

            if (var24 > 0.0D)
            {
                // The same speed in every direction, diagonals included.
                double var26 = (double)freecamSpeed * var3 / var24;
                var16 *= var26;
                var18 *= var26;
                var20 *= var26;
            }

            this.moveTo(this.posX + var16, this.posY + var18, this.posZ + var20, var6, var7);
        }
    }

    /* ------------------------------------------------------------------ Hand and overlays */

    /**
     * EntityRenderer's first-person item renderer, which draws nothing of the player's (hand, held item,
     * fire, water and in-block overlays) while a Kirmod camera has the view.
     */
    public static class KirItemRenderer extends ItemRenderer
    {
        private static boolean failed = false;

        public KirItemRenderer(Minecraft par1Minecraft)
        {
            super(par1Minecraft);
        }

        static void install(Minecraft mc)
        {
            ItemRenderer var1 = mc.entityRenderer.itemRenderer;

            if (failed || var1 instanceof KirItemRenderer)
            {
                return;
            }

            if (var1.getClass() != ItemRenderer.class)
            {
                failed = true;
                KirUtil.log("The item renderer is already changed by another mod (" + var1.getClass().getName() + "), the hand shows in Freecam", (Throwable)null);
                return;
            }

            try
            {
                KirItemRenderer var2 = new KirItemRenderer(mc);
                KirUtil.copyFields(var1, var2);
                mc.entityRenderer.itemRenderer = var2;
            }
            catch (Exception var3)
            {
                failed = true;
                KirUtil.log("Could not hook into the item renderer", var3);
            }
        }

        public void renderItemInFirstPerson(float par1)
        {
            if (!KirCamera.isActive(ModLoader.getMinecraftInstance()))
            {
                super.renderItemInFirstPerson(par1);
            }
        }

        public void renderOverlays(float par1)
        {
            if (!KirCamera.isActive(ModLoader.getMinecraftInstance()))
            {
                super.renderOverlays(par1);
            }
        }
    }
}
