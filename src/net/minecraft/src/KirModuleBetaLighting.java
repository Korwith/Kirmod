package net.minecraft.src;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;

/**
 * Beta Lighting: "On" lights the world the way Beta 1.7.3 did.
 *
 * Light levels (how far light spreads, what blocks it) are the same in both versions; only the way a
 * level becomes a brightness on screen changed in 1.0-1.3:
 *
 *   Beta 1.7.3                                     1.3.2
 *   brightness = table[max(sky - darkness, block)] sky and torch light are added up, then tinted
 *   grey, the same for sky light and torch light   sky light blue-ish at night, torch light orange
 *   no flicker                                     torch light flickers
 *   darkness (skylightSubtracted) 0-11 in steps,   the sun fades smoothly
 *     so dusk and dawn get darker in 11 jumps
 *   darkest level 5% (Nether 10%)                  darkest level 3%, Brightness slider on top
 *   lightning only flashes the sky                 lightning also lights up the ground
 *   smooth lighting averages the brightness of the smooth lighting averages the light levels and
 *     4 blocks at each corner, solid ones count      multiplies by a separate 0.2 shadow per solid
 *     as the darkest level                           block
 *
 * 1.3.2 draws everything through a 16x16 lightmap texture indexed by (sky level, block level), so the
 * first five rows are emulated exactly by writing Beta's table into that texture every frame
 * (KirRenderGlobal.clipRenderersByFrustum runs after EntityRenderer has written its own). That covers
 * blocks, entities, particles, tile entities and the held item.
 *
 * Beta's smooth lighting can't be expressed through the lightmap (an average of four brightnesses is
 * not a brightness of averaged levels), so with Smooth Lighting on, chunks are built by
 * KirWorldRenderer with KirRenderBlocks, which runs Beta's algorithm and bakes the result into the
 * vertex colors, as Beta did. Those chunks are rebuilt whenever the darkness changes a step, like in
 * Beta (it is the brief wave of chunk updates at dusk Beta players knew).
 *
 * The End is left vanilla (Beta had no End, and without sky light it would be pitch black) unless End
 * Overworld Sky gives it sky light, and everything is left vanilla while Fullbright is on.
 */
public class KirModuleBetaLighting extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    /** Lightmap coordinates that Beta's lightmap maps to full brightness (sky 15, block 15). */
    static final int FULL_BRIGHTNESS = 15 << 20 | 15 << 4;

    private static final Field fieldLightmapTexture = KirUtil.findField(EntityRenderer.class, "lightmapTexture", "d");

    /** Brightness of each light level in the current dimension, by Beta's formula. */
    private final float[] table = new float[16];
    private WorldProvider tableProvider;

    /** Beta's darkness of sky light (World.skylightSubtracted), 0 at noon to 11 at night. */
    private int skyDarkness;

    private boolean active;
    private boolean smoothActive;
    private World lastWorld;
    private final int[] lightmapColors = new int[256];

    public KirModuleBetaLighting()
    {
        super("betaLighting", "Beta Lighting", "Beta Lighting", 0, "betalighting", "betalight");
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public String getCategory()
    {
        return CATEGORY_OVERWORLD;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Lights the world the way Beta 1.7.3 did.",
            describeValue("Off", "1.3.2 lighting: orange flickering torch light, blue nights, a smooth sunset."),
            describeValue("On", "Beta lighting: grey light with no flicker, nights darken in steps, darker smooth lighting corners."),
            note("Only the look changes: light levels, mob spawning and crop growth are the same either way. The Brightness slider has no effect while it is on (Beta had none). The End (unless End Overworld Sky is on) and Fullbright stay as they are.")
        };
    }

    public boolean isAvailable()
    {
        return KirRenderGlobal.canSwapWorldRenderers() && fieldLightmapTexture != null;
    }

    /** Whether the chunk renderers should be KirWorldRenderers (see KirRenderGlobal.loadRenderers). */
    boolean wantsWorldRenderers()
    {
        return this.enabled && this.getValue() == 1 && this.isAvailable();
    }

    /** Whether Beta's lightmap is in use right now. */
    boolean isActive()
    {
        return this.active;
    }

    /** Whether chunks are built with Beta's smooth lighting right now. */
    boolean isSmoothActive()
    {
        return this.smoothActive;
    }

    /** Whether Fullbright (a Kirmod Extras module, so it may be missing) is on. */
    private static boolean isFullbright()
    {
        KirModule var0 = mod_Kirmod.getModule("fullbright");
        return var0 != null && var0.isEnabled() && var0.getValue() == 1;
    }

    public void update(Minecraft mc)
    {
        boolean var2 = this.wantsWorldRenderers();
        WorldClient var3 = mc.theWorld;

        if (var2)
        {
            KirRenderGlobal.install(mc);
        }

        boolean var4 = var2 && var3 != null && mc.renderGlobal instanceof KirRenderGlobal && var3.provider.worldType != 1 && !isFullbright();
        int var5 = this.skyDarkness;

        if (var4)
        {
            if (this.tableProvider != var3.provider)
            {
                this.tableProvider = var3.provider;
                generateTable(this.table, var3.provider.isHellWorld ? 0.1F : 0.05F);
            }

            this.skyDarkness = var3.calculateSkylightSubtracted(1.0F);
        }

        boolean var6 = this.smoothActive;
        this.active = var4;
        this.smoothActive = var4 && mc.gameSettings.ambientOcclusion;

        if (mc.renderGlobal instanceof KirRenderGlobal)
        {
            KirRenderGlobal var7 = (KirRenderGlobal)mc.renderGlobal;

            Boolean var8 = var7.hasKirWorldRenderers();

            if (var3 != null && var8 != null && var8.booleanValue() != var2)
            {
                // Switched on or off, or the renderer was only just installed.
                KirUtil.reloadRenderers();
            }
            else if (var3 == this.lastWorld && (var6 != this.smoothActive || this.smoothActive && var5 != this.skyDarkness))
            {
                // The baked smooth lighting is out of date.
                var7.markAllRenderersForUpdate();
            }
        }

        this.lastWorld = var3;
    }

    /** Beta's WorldProvider.generateLightBrightnessTable; 1.3.2 uses 0 instead of minimum. */
    private static void generateTable(float[] table, float minimum)
    {
        for (int var2 = 0; var2 <= 15; ++var2)
        {
            float var3 = 1.0F - (float)var2 / 15.0F;
            table[var2] = (1.0F - var3) / (var3 * 3.0F + 1.0F) * (1.0F - minimum) + minimum;
        }
    }

    /** Beta's brightness for the packed light levels of IBlockAccess.getLightBrightnessForSkyBlocks. */
    float getBrightness(int packedLight, int minLevel)
    {
        int var3 = (packedLight >> 20 & 15) - this.skyDarkness;
        int var4 = packedLight >> 4 & 15;

        if (var4 > var3)
        {
            var3 = var4;
        }

        if (minLevel > var3)
        {
            var3 = minLevel;
        }

        return this.table[var3 < 0 ? 0 : var3];
    }

    /** Overwrites the lightmap EntityRenderer made this frame with Beta's; called before the world is drawn. */
    void uploadLightmap(Minecraft mc)
    {
        for (int var2 = 0; var2 < 256; ++var2)
        {
            int var3 = (int)(this.getBrightness((var2 >> 4) << 20 | (var2 & 15) << 4, 0) * 255.0F);
            this.lightmapColors[var2] = 0xFF000000 | var3 << 16 | var3 << 8 | var3;
        }

        try
        {
            mc.renderEngine.createTextureFromBytes(this.lightmapColors, 16, 16, fieldLightmapTexture.getInt(mc.entityRenderer));
        }
        catch (IllegalAccessException var4)
        {
            ;
        }
    }
}
