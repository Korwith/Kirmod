package net.minecraft.src;

/**
 * Isometric Direction: the side the isometric screenshot camera looks from (NE, SE, NW or SW of you). It
 * looks back across you, so NE shows the north and east faces of things. See KirCamera.
 */
public class KirModuleIsometricDirection extends KirModule
{
    private static final String[] VALUES = new String[] {"NE", "SE", "NW", "SW"};

    /** Each value's direction from you to the camera as (x, z); north is -z, east is +x. */
    private static final int[][] OFFSETS = new int[][] {{1, -1}, {1, 1}, {-1, -1}, {-1, 1}};

    public KirModuleIsometricDirection()
    {
        super("isometricDirection", "Isometric Direction", "Iso Direction", 0, "isodirection", "isodir");
    }

    public String getCategory()
    {
        return KirModuleIsometric.CATEGORY_CAMERA;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Which side the isometric screenshot is taken from. The camera is up in the sky on that side of you, looking back at you.",
            describeValue("NE", "from the northeast, looking southwest."),
            describeValue("SE", "from the southeast, looking northwest."),
            describeValue("NW", "from the northwest, looking southeast."),
            describeValue("SW", "from the southwest, looking northeast.")
        };
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    /** The direction from the player to the camera as (x, z), each -1 or 1. */
    public int[] getOffset()
    {
        return OFFSETS[this.getValue()];
    }
}
