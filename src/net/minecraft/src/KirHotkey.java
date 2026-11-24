package net.minecraft.src;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * One of the keys in the Hotkeys panel of Kirmod's settings screen (new in Kirmod 1.28): Kirmod's own keys
 * and the ones plugins add. There are two kinds:
 *
 *   - module keys (mod_Kirmod.registerModuleKey): switch a module to its next value and say so in chat.
 *     Kirmod watches these keys itself every frame (poll()), so they work whichever mod ModLoader or
 *     Forge hands the key event to. Under Forge, ModLoader.registerKey gives a key to the mod that is
 *     loading at the time, not to the mod passed in, so a key a plugin registered for Kirmod used to go
 *     to the plugin, which ignored it (the dead Freecam and Fullbright keys of Kirmod Extras 1.0).
 *   - plugin keys (mod_Kirmod.registerPluginKey): keys a plugin's own mod registers and handles (Ghost
 *     Shapes' G, Storage Spaces' I...). Kirmod only lists them, so they can be seen and changed in one
 *     place.
 *
 * Every key is still a normal KeyBinding: it is listed in Options > Controls and saved in options.txt, and
 * changing it in either screen changes it in both.
 */
public class KirHotkey
{
    private static final List ALL = new ArrayList();

    public final KeyBinding key;

    /** The key it had when it was registered (before options.txt was read), for Reset. */
    public final int defaultCode;

    /** The module it switches, or null for a plugin key. */
    public final KirModule module;

    /** Whose key it is, for a plugin key (module keys use their module's plugin). */
    private final KirPlugin plugin;

    /** A group under the plugin's name ("Ghost Shapes"), or null. */
    public final String group;

    /** What it does, for the tooltip. */
    public final String description;

    /**
     * A key that must be held with this one (1.31), or 0. A combo key such as Debug Overlays' F3 + G is
     * shown as "F3 + G", only clashes with other combos on the same keys, and while its modifier is held no
     * module key on the same key fires (F3 + L does not also switch Item Magnet).
     */
    public final int modifier;

    /** Whether the key was down the last frame (module keys). */
    private boolean wasDown;

    private KirHotkey(KeyBinding key, KirModule module, KirPlugin plugin, String group, String description, int modifier)
    {
        this.key = key;
        this.defaultCode = key.keyCode;
        this.module = module;
        this.plugin = plugin;
        this.group = group;
        this.description = description;
        this.modifier = modifier;
    }

    static KirHotkey addModuleKey(KeyBinding key, KirModule module)
    {
        KirHotkey var2 = find(key);

        if (var2 == null)
        {
            var2 = new KirHotkey(key, module, (KirPlugin)null, (String)null, (String)null, 0);
            ALL.add(var2);
        }

        return var2;
    }

    static KirHotkey addPluginKey(KirPlugin plugin, String group, KeyBinding key, String description, int modifier)
    {
        KirHotkey var4 = find(key);

        if (var4 == null)
        {
            var4 = new KirHotkey(key, (KirModule)null, plugin, group, description, modifier);
            ALL.add(var4);
        }

        return var4;
    }

    private static KirHotkey find(KeyBinding key)
    {
        Iterator var1 = ALL.iterator();

        while (var1.hasNext())
        {
            KirHotkey var2 = (KirHotkey)var1.next();

            if (var2.key == key)
            {
                return var2;
            }
        }

        return null;
    }

    public KirPlugin getPlugin()
    {
        return this.module != null ? this.module.plugin : this.plugin;
    }

    public String getTitle()
    {
        return this.key.keyDescription;
    }

    /** Shown in the panel: a module key whose module is switched off in Kirmod.cfg is not. */
    public boolean isShown()
    {
        return this.module == null || this.module.enabled && mod_Kirmod.getModules().contains(this.module);
    }

    public boolean isChanged()
    {
        return this.key.keyCode != this.defaultCode;
    }

    /** Every shown hotkey, in sidebar order of their plugins (Kirmod first), then registration order. */
    public static List getShown()
    {
        ArrayList var0 = new ArrayList();
        Iterator var1 = mod_Kirmod.getPlugins().iterator();

        while (var1.hasNext())
        {
            KirPlugin var2 = (KirPlugin)var1.next();
            Iterator var3 = ALL.iterator();

            while (var3.hasNext())
            {
                KirHotkey var4 = (KirHotkey)var3.next();

                if (var4.getPlugin() == var2 && var4.isShown())
                {
                    var0.add(var4);
                }
            }
        }

        return var0;
    }

    public static List getAll()
    {
        return Collections.unmodifiableList(ALL);
    }

    /** The key's name: "Y", "Button 3", "None". */
    public static String keyName(int code)
    {
        if (code == 0)
        {
            return "None";
        }

        if (code < 0)
        {
            return "Button " + (code + 101);
        }

        String var1 = Keyboard.getKeyName(code);

        if (var1 == null)
        {
            return "Key " + code;
        }

        for (int var2 = 0; var2 < FRIENDLY.length; var2 += 2)
        {
            if (FRIENDLY[var2].equals(var1))
            {
                return FRIENDLY[var2 + 1];
            }
        }

        if (var1.startsWith("NUMPAD") && var1.length() == 7)
        {
            return "Num " + var1.charAt(6);
        }

        // "SCROLL" -> "Scroll", "F5" stays.
        return var1.length() > 2 ? var1.charAt(0) + var1.substring(1).toLowerCase() : var1;
    }

    /** LWJGL's key names and the ones shown instead. */
    private static final String[] FRIENDLY = new String[] {
        "LBRACKET", "[", "RBRACKET", "]", "APOSTROPHE", "'", "SEMICOLON", ";", "PERIOD", ".", "COMMA", ",",
        "SLASH", "/", "BACKSLASH", "\\", "MINUS", "-", "EQUALS", "=", "GRAVE", "`",
        "LMENU", "Left Alt", "RMENU", "Right Alt", "LCONTROL", "Left Ctrl", "RCONTROL", "Right Ctrl",
        "LSHIFT", "Left Shift", "RSHIFT", "Right Shift", "LMETA", "Left Meta", "RMETA", "Right Meta",
        "PRIOR", "Page Up", "NEXT", "Page Down", "RETURN", "Enter", "BACK", "Backspace", "CAPITAL", "Caps Lock",
        "NUMPADENTER", "Num Enter", "ADD", "Num +", "SUBTRACT", "Num -", "MULTIPLY", "Num *", "DIVIDE", "Num /",
        "DECIMAL", "Num .", "NUMLOCK", "Num Lock", "SCROLL", "Scroll Lock", "UP", "Up", "DOWN", "Down",
        "LEFT", "Left", "RIGHT", "Right", "SPACE", "Space", "TAB", "Tab", "ESCAPE", "Esc", "INSERT", "Insert",
        "DELETE", "Delete", "HOME", "Home", "END", "End", "SYSRQ", "Print Screen", "PAUSE", "Pause"
    };

    public String getKeyName()
    {
        return this.comboName(this.key.keyCode);
    }

    /** The key's name with its modifier, if it has one: "F3 + G". */
    public String comboName(int code)
    {
        return this.modifier != 0 && code != 0 ? keyName(this.modifier) + " + " + keyName(code) : keyName(code);
    }

    /** The other key bindings (the game's, any mod's) on the same key; empty when it has none. */
    public List getConflicts(Minecraft mc)
    {
        ArrayList var2 = new ArrayList();

        if (this.key.keyCode == 0 || mc == null || mc.gameSettings == null)
        {
            return var2;
        }

        if (this.modifier != 0)
        {
            // A combo only clashes with another combo on the same two keys.
            for (int var5 = 0; var5 < ALL.size(); ++var5)
            {
                KirHotkey var6 = (KirHotkey)ALL.get(var5);

                if (var6 != this && var6.modifier == this.modifier && var6.key.keyCode == this.key.keyCode && var6.isShown())
                {
                    var2.add(var6.getTitle());
                }
            }

            return var2;
        }

        KeyBinding[] var3 = mc.gameSettings.keyBindings;

        for (int var4 = 0; var4 < var3.length; ++var4)
        {
            if (var3[var4] != this.key && var3[var4].keyCode == this.key.keyCode)
            {
                var2.add(var3[var4].keyDescription);
            }
        }

        return var2;
    }

    /** Rebinds the key, like Options > Controls does, and saves options.txt. */
    public void setKeyCode(Minecraft mc, int code)
    {
        this.key.keyCode = code;
        KeyBinding.resetKeyBindingArrayAndHash();

        if (mc != null && mc.gameSettings != null)
        {
            mc.gameSettings.saveOptions();
        }
    }

    /** Puts every hotkey back on its default key. */
    public static void resetAll(Minecraft mc)
    {
        Iterator var1 = ALL.iterator();

        while (var1.hasNext())
        {
            KirHotkey var2 = (KirHotkey)var1.next();
            var2.key.keyCode = var2.defaultCode;
        }

        KeyBinding.resetKeyBindingArrayAndHash();

        if (mc != null && mc.gameSettings != null)
        {
            mc.gameSettings.saveOptions();
        }
    }

    /**
     * Switches the modules of module keys pressed since the last frame. Called every frame; only acts in a
     * world with no screen open (chat, inventory), like the game's own keys.
     */
    static void poll(Minecraft mc)
    {
        boolean var1 = mc.currentScreen == null && mc.theWorld != null && mc.thePlayer != null;

        for (int var2 = 0; var2 < ALL.size(); ++var2)
        {
            KirHotkey var3 = (KirHotkey)ALL.get(var2);

            if (var3.module == null)
            {
                continue;
            }

            boolean var4 = isDown(var3.key.keyCode);

            if (var4 && !var3.wasDown && var1 && var3.isShown() && !isComboHeld(var3.key.keyCode))
            {
                var3.module.cycleValue(1);

                if (mc.ingameGUI != null)
                {
                    mc.ingameGUI.getChatGUI().printChatMessage("\u00a7e" + var3.module.getDisplayString());
                }
            }

            var3.wasDown = var4;
        }
    }

    /** Whether a combo hotkey on this key has its modifier held: then the key belongs to the combo. */
    static boolean isComboHeld(int code)
    {
        for (int var1 = 0; var1 < ALL.size(); ++var1)
        {
            KirHotkey var2 = (KirHotkey)ALL.get(var1);

            if (var2.modifier != 0 && var2.key.keyCode == code && isDown(var2.modifier))
            {
                return true;
            }
        }

        return false;
    }

    static boolean isDown(int code)
    {
        try
        {
            if (code < 0)
            {
                return Mouse.isCreated() && code + 100 < Mouse.getButtonCount() && Mouse.isButtonDown(code + 100);
            }

            return code > 0 && Keyboard.isCreated() && Keyboard.isKeyDown(code);
        }
        catch (RuntimeException var2)
        {
            return false;
        }
    }
}
