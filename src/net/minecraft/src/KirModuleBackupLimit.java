package net.minecraft.src;

/**
 * Keep Backups (1.38), in the Backups tab: how many automatic backups of each world are kept. After each
 * backup the oldest automatic ones past that are deleted (KirBackups); ones made with "Backup Now" are kept.
 */
public class KirModuleBackupLimit extends KirModule
{
    private static final String[] VALUES = new String[] {"3", "5", "10", "20", "50", "All"};
    private static final int[] LIMITS = new int[] {3, 5, 10, 20, 50, 0};

    public KirModuleBackupLimit()
    {
        super("backupLimit", "Keep Backups", "Keep Backups", 2, "backuplimit", "keepbackups");
    }

    public String getCategory()
    {
        return CATEGORY_BACKUPS;
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "How many automatic backups of each world are kept. After each backup, the oldest automatic ones past this are deleted.",
            describeValue("3 to 50", "that many per world."),
            describeValue("All", "never deletes any; watch your disk space."),
            note("Backups made with \"Backup Now\" are never deleted by this.")
        };
    }

    /** How many automatic backups to keep per world, or 0 for all. */
    public int getLimit()
    {
        return LIMITS[this.getValue()];
    }
}
