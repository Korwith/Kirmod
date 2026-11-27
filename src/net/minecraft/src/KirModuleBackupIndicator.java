package net.minecraft.src;

/**
 * Backup Indicator (1.38), in the Backups tab: whether the HUD shows the backup icon and progress in the top
 * right corner while a backup runs (KirBackupHud.drawHud).
 */
public class KirModuleBackupIndicator extends KirModule
{
    private static final String[] VALUES = new String[] {"On", "Off"};

    public KirModuleBackupIndicator()
    {
        super("backupIndicator", "Backup Indicator", "Backup Indicator", 0, (String)null);
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
            "Shows an icon in the top right corner of the screen while a backup runs, with how far along it is, and says when it is done.",
            describeValue("On", "the icon shows while you play or are in other menus."),
            describeValue("Off", "backups run without it; the Backups tab still shows them.")
        };
    }
}
