package net.minecraft.src;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;

/**
 * A hook that runs every frame right before it is drawn (1.37), at the main menu too.
 *
 * ModLoader runs its tick hooks after the frame is drawn, so whatever a screen's first frame needs (a
 * screen swapped for a subclass, like the Recipe Book's inventory or Kirmod's Options; the Dark Theme's
 * textures) came one frame late, and that frame flickered. Minecraft.runGameLoop opens the profiler section
 * "display" after the game ticks (which is where keys open screens) and before EntityRenderer draws the
 * frame, so mc.mcProfiler is replaced by this subclass, which runs mod_Kirmod.beforeFrame there. It took
 * over the original's state, and every call still goes to Profiler, so the F3 profiler works as before.
 *
 * If the profiler can't be replaced, mod_Kirmod keeps doing its per-frame work from ModLoader's hooks.
 */
public class KirFrameHook extends Profiler
{
    private static final String DISPLAY = "display";

    private static boolean failed;
    private static boolean errorLogged;

    private Minecraft mc;

    /** Whether the hook is in place, so the per-frame work runs from it. */
    static boolean isActive(Minecraft mc)
    {
        return mc.mcProfiler instanceof KirFrameHook;
    }

    /** Replaces mc.mcProfiler (once). Main thread. */
    static void install(Minecraft mc)
    {
        if (failed || mc == null || mc.mcProfiler instanceof KirFrameHook)
        {
            return;
        }

        try
        {
            Field var1 = null;
            Field[] var2 = Minecraft.class.getDeclaredFields();

            for (int var3 = 0; var3 < var2.length; ++var3)
            {
                if (var2[var3].getType() == Profiler.class && !Modifier.isStatic(var2[var3].getModifiers()))
                {
                    var1 = var2[var3];
                    break;
                }
            }

            if (var1 == null || mc.mcProfiler.getClass() != Profiler.class)
            {
                // Not found, or another mod has replaced it already: leave it alone.
                failed = true;
                return;
            }

            KirFrameHook var4 = new KirFrameHook();
            KirUtil.copyFields(mc.mcProfiler, var4);
            var4.mc = mc;
            var1.setAccessible(true);
            var1.set(mc, var4);

            if (mc.mcProfiler != var4)
            {
                failed = true;
            }
        }
        catch (Throwable var5)
        {
            failed = true;
            KirUtil.log("Could not add the frame hook; menus may flicker for a frame", var5);
        }
    }

    public void startSection(String par1Str)
    {
        // Every caller passes a string literal, so they are all the one interned "display".
        if (par1Str == DISPLAY && this.mc != null)
        {
            try
            {
                mod_Kirmod.beforeFrame(this.mc);
            }
            catch (Throwable var3)
            {
                if (!errorLogged)
                {
                    errorLogged = true;
                    KirUtil.log("Frame hook failed", var3);
                }
            }
        }

        super.startSection(par1Str);
    }
}
