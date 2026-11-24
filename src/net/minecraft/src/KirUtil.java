package net.minecraft.src;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;

/**
 * Helpers shared by Kirmod modules: reflection that works both in MCP (deobfuscated names) and in a
 * release (obfuscated names, also with Forge), and swapping a vanilla block for a subclass at runtime.
 */
public class KirUtil
{
    /**
     * Finds a declared field by trying each name in turn, normally the MCP name and then the obfuscated
     * one from conf/fields.csv. Returns null (and logs) if none exists, so the caller can turn its
     * feature off instead of crashing.
     */
    public static Field findField(Class owner, String ... names)
    {
        for (int var2 = 0; var2 < names.length; ++var2)
        {
            try
            {
                Field var3 = owner.getDeclaredField(names[var2]);
                var3.setAccessible(true);
                return var3;
            }
            catch (NoSuchFieldException var4)
            {
                ;
            }
        }

        log("Field " + owner.getName() + "." + names[0] + " not found", (Throwable)null);
        return null;
    }

    /** Like findField, for a method without parameters. */
    public static Method findMethod(Class owner, String ... names)
    {
        for (int var2 = 0; var2 < names.length; ++var2)
        {
            try
            {
                Method var3 = owner.getDeclaredMethod(names[var2], new Class[0]);
                var3.setAccessible(true);
                return var3;
            }
            catch (NoSuchMethodException var4)
            {
                ;
            }
        }

        log("Method " + owner.getName() + "." + names[0] + " not found", (Throwable)null);
        return null;
    }

    /**
     * Copies every instance field declared by from's class and its superclasses into to, which must be
     * an instance of the same class or a subclass. Used to swap a vanilla object for a Kirmod subclass
     * without losing its state (or fields Forge and other mods added).
     */
    public static void copyFields(Object from, Object to) throws IllegalAccessException
    {
        for (Class var2 = from.getClass(); var2 != Object.class; var2 = var2.getSuperclass())
        {
            Field[] var3 = var2.getDeclaredFields();

            for (int var4 = 0; var4 < var3.length; ++var4)
            {
                if (!Modifier.isStatic(var3[var4].getModifiers()))
                {
                    var3[var4].setAccessible(true);
                    var3[var4].set(to, var3[var4].get(from));
                }
            }
        }
    }

    public static void log(String message, Throwable error)
    {
        System.out.println("[Kirmod] " + message + (error != null ? ": " + error : ""));
    }

    /** Lowercase with everything but letters and digits removed, for forgiving name matching. */
    public static String normalize(String s)
    {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Rebuilds the world's chunk meshes, e.g. after a change to how blocks look. */
    public static void reloadRenderers()
    {
        Minecraft var0 = ModLoader.getMinecraftInstance();

        if (var0 != null && var0.theWorld != null && var0.renderGlobal != null)
        {
            var0.renderGlobal.loadRenderers();
        }
    }

    /**
     * Frees a block ID so a replacement block can be constructed in its slot. Use it as the argument of
     * the replacement's super constructor, which would otherwise refuse an occupied slot:
     * super(KirUtil.constructSlot(id), ...).
     */
    public static int constructSlot(int id)
    {
        // Block's constructor overwrites these tables for its ID; replaceBlock puts them back.
        savedSlot = id;
        savedOpaque = Block.opaqueCubeLookup[id];
        savedOpacity = Block.lightOpacity[id];
        savedCanBlockGrass = Block.canBlockGrass[id];
        Block.blocksList[id] = null;
        return id;
    }

    private static int savedSlot = -1;
    private static boolean savedOpaque;
    private static int savedOpacity;
    private static boolean savedCanBlockGrass;

    /**
     * Frees an item ID so a replacement item can be constructed in its slot without vanilla's
     * "CONFLICT @" warning: super(KirUtil.constructItemSlot(original.shiftedIndex - 256), ...).
     */
    public static int constructItemSlot(int id)
    {
        Item.itemsList[256 + id] = null;
        return id;
    }

    /**
     * Finishes swapping in a replacement item, like replaceBlock: copies every field of the original
     * (icon, name, durability, and anything Forge or other mods added) and points the static Item field
     * at the replacement. If the original was already replaced by another mod it is put back untouched
     * and false is returned.
     */
    public static boolean replaceItem(Item original, Item replacement, String mcpName, String obfName) throws IllegalAccessException
    {
        int var4 = original.shiftedIndex;

        if (!replacement.getClass().getSuperclass().equals(original.getClass()))
        {
            Item.itemsList[var4] = original;
            log("Item " + var4 + " is already changed by another mod (" + original.getClass().getName() + "), leaving it alone", (Throwable)null);
            return false;
        }

        copyFields(original, replacement);
        Item.itemsList[var4] = replacement;
        setStaticField(Item.class, replacement, mcpName, obfName);
        return true;
    }

    /** Sets a static (even final) field; logs and returns false if it can't. */
    public static boolean setStaticField(Class owner, Object value, String ... names)
    {
        try
        {
            Field var3 = findField(owner, names);
            Field var4 = Field.class.getDeclaredField("modifiers");
            var4.setAccessible(true);
            var4.setInt(var3, var3.getModifiers() & ~Modifier.FINAL);
            var3.set((Object)null, value);
            return true;
        }
        catch (Exception var5)
        {
            // Not fatal: the game uses Item.itemsList / Block.blocksList, the static field is a shortcut.
            log("Could not update " + owner.getName() + "." + names[0], var5);
            return false;
        }
    }

    /** Reads an int field found with findField, or returns fallback. */
    public static int getInt(Field field, Object owner, int fallback)
    {
        try
        {
            return field != null ? field.getInt(owner) : fallback;
        }
        catch (IllegalAccessException var4)
        {
            return fallback;
        }
    }

    /** Writes an int field found with findField; does nothing if it is missing. */
    public static void setInt(Field field, Object owner, int value)
    {
        try
        {
            if (field != null)
            {
                field.setInt(owner, value);
            }
        }
        catch (IllegalAccessException var4)
        {
            ;
        }
    }

    /**
     * Finishes swapping in a replacement block: copies every field of the original block (hardness,
     * sound, name, light, and anything Forge or other mods added) and points the static Block field at
     * the replacement. If the original was already replaced by another mod it is put back untouched and
     * false is returned.
     *
     * @param mcpName the Block field holding the original, e.g. "chest"
     * @param obfName the same field's obfuscated name, e.g. "au"
     */
    public static boolean replaceBlock(Block original, Block replacement, String mcpName, String obfName) throws IllegalAccessException
    {
        int var4 = original.blockID;

        if (!replacement.getClass().getSuperclass().equals(original.getClass()))
        {
            Block.blocksList[var4] = original;

            if (savedSlot == var4)
            {
                Block.opaqueCubeLookup[var4] = savedOpaque;
                Block.lightOpacity[var4] = savedOpacity;
                Block.canBlockGrass[var4] = savedCanBlockGrass;
                savedSlot = -1;
            }

            log("Block " + var4 + " is already changed by another mod (" + original.getClass().getName() + "), leaving it alone", (Throwable)null);
            return false;
        }

        copyFields(original, replacement);
        Block.blocksList[var4] = replacement;

        if (savedSlot == var4)
        {
            Block.opaqueCubeLookup[var4] = savedOpaque;
            Block.lightOpacity[var4] = savedOpacity;
            Block.canBlockGrass[var4] = savedCanBlockGrass;
            savedSlot = -1;
        }

        try
        {
            Field var9 = findField(Block.class, mcpName, obfName);
            Field var10 = Field.class.getDeclaredField("modifiers");
            var10.setAccessible(true);
            var10.setInt(var9, var9.getModifiers() & ~Modifier.FINAL);
            var9.set((Object)null, replacement);
        }
        catch (Exception var8)
        {
            // Not fatal: the world uses Block.blocksList, the static field is only a shortcut.
            log("Could not update Block." + mcpName, var8);
        }

        return true;
    }
}
