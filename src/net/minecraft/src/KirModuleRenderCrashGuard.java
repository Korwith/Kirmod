package net.minecraft.src;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * Render Crash Guard (from EndermanCrashFix): "On" stops an error while drawing one entity from crashing
 * the game. The entity is skipped for that frame, the error goes to the log (at most every 2 seconds),
 * and the chat says so once per kind of entity.
 *
 * Every renderer in RenderManager.entityRenderMap is wrapped in a SafeRender, whose doRender catches
 * exceptions and puts the OpenGL matrix stack and the Tessellator back the way they were. The map is
 * checked again now and then, so renderers added later (by mods, or the map caching a superclass's
 * renderer) are wrapped too; "Off" unwraps them. EndermanCrashFix replaced RenderManager, which took
 * Forge's copy out of the game.
 *
 * Player renderers are left unwrapped: ItemRenderer casts the player's renderer to RenderPlayer to draw
 * the first-person arm.
 */
public class KirModuleRenderCrashGuard extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    private static final Field fieldRenderMap = KirUtil.findField(RenderManager.class, "entityRenderMap", "o");
    private static final Field fieldIsDrawing = KirUtil.findField(Tessellator.class, "isDrawing", "z");

    private static long lastLog = 0L;
    private static final Set reported = new HashSet();

    private int frames = 0;
    private int lastSize = -1;
    private boolean wrapped = false;

    public KirModuleRenderCrashGuard()
    {
        super("renderCrashGuard", "Render Crash Guard", "Render Guard", 0, "rendercrashguard");
    }

    public String getCategory()
    {
        return CATEGORY_OTHER;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Keeps an error while drawing an entity (an enderman holding a modded block, say) from crashing the game.",
            describeValue("Off", "vanilla, such an error crashes the game."),
            describeValue("On", "the entity isn't drawn for that frame instead; the error is written to the log and mentioned once in chat."),
            note("Like EndermanCrashFix, without replacing RenderManager.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public boolean isAvailable()
    {
        return fieldRenderMap != null;
    }

    protected void onValueChanged()
    {
        this.lastSize = -1;
    }

    public void update(Minecraft mc)
    {
        boolean var2 = this.getValue() == 1;

        if (fieldRenderMap == null || !var2 && !this.wrapped || ++this.frames % 40 != 0 && this.lastSize >= 0)
        {
            return;
        }

        try
        {
            Map var3 = (Map)fieldRenderMap.get(RenderManager.instance);

            if (var2 && var3.size() == this.lastSize)
            {
                return;
            }

            Iterator var4 = var3.entrySet().iterator();

            while (var4.hasNext())
            {
                Map.Entry var5 = (Map.Entry)var4.next();
                Object var6 = var5.getValue();

                if (var2 && var6 instanceof Render && !(var6 instanceof SafeRender) && !(var6 instanceof RenderPlayer))
                {
                    var5.setValue(new SafeRender((Render)var6));
                }
                else if (!var2 && var6 instanceof SafeRender)
                {
                    var5.setValue(((SafeRender)var6).inner);
                }
            }

            this.lastSize = var3.size();
            this.wrapped = var2;
        }
        catch (Exception var7)
        {
            KirUtil.log("Could not wrap the entity renderers", var7);
            this.lastSize = Integer.MAX_VALUE;
        }
    }

    /** Puts the matrix stack and Tessellator back after a renderer threw, and reports it. */
    static void recover(Entity entity, int stackDepth, Exception error)
    {
        for (int var3 = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH); var3 > stackDepth; --var3)
        {
            GL11.glPopMatrix();
        }

        try
        {
            if (fieldIsDrawing != null && fieldIsDrawing.getBoolean(Tessellator.instance))
            {
                // Drop the half-built shape; draw() would put it on screen.
                fieldIsDrawing.setBoolean(Tessellator.instance, false);
                Tessellator.instance.startDrawingQuads();
                fieldIsDrawing.setBoolean(Tessellator.instance, false);
            }
        }
        catch (Exception var6)
        {
            ;
        }

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        long var4 = System.currentTimeMillis();

        if (var4 - lastLog > 2000L)
        {
            lastLog = var4;
            KirUtil.log("Render Crash Guard skipped drawing " + entity, error);
            error.printStackTrace();
        }

        String var7 = entity.getClass().getName();

        if (reported.add(var7))
        {
            Minecraft var8 = ModLoader.getMinecraftInstance();

            if (var8 != null && var8.ingameGUI != null)
            {
                var8.ingameGUI.getChatGUI().printChatMessage("\u00a7e[Kirmod] Render Crash Guard stopped a crash while drawing " + EntityList.getEntityString(entity) + " (" + error + "). It is skipped; see the log.");
            }
        }
    }

    /** A renderer that passes everything to the real one and survives its errors. */
    public static class SafeRender extends Render
    {
        final Render inner;

        public SafeRender(Render inner)
        {
            this.inner = inner;
            this.shadowSize = 0.0F;
            this.setRenderManager(RenderManager.instance);
        }

        public void setRenderManager(RenderManager par1RenderManager)
        {
            super.setRenderManager(par1RenderManager);

            if (this.inner != null)
            {
                this.inner.setRenderManager(par1RenderManager);
            }
        }

        public void doRender(Entity par1Entity, double par2, double par4, double par6, float par8, float par9)
        {
            int var10 = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);

            try
            {
                this.inner.doRender(par1Entity, par2, par4, par6, par8, par9);
            }
            catch (Exception var12)
            {
                recover(par1Entity, var10, var12);
            }
        }

        public void doRenderShadowAndFire(Entity par1Entity, double par2, double par4, double par6, float par8, float par9)
        {
            int var10 = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);

            try
            {
                this.inner.doRenderShadowAndFire(par1Entity, par2, par4, par6, par8, par9);
            }
            catch (Exception var12)
            {
                recover(par1Entity, var10, var12);
            }
        }
    }
}
