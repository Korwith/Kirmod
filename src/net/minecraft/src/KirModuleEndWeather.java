package net.minecraft.src;

/**
 * End Weather: whether the overworld's rain reaches the End while End Overworld Sky is on. This module is
 * only the switch; KirModuleEndSky reads it.
 */
public class KirModuleEndWeather extends KirModule
{
    private static final String[] VALUES = new String[] {"Off", "On"};

    public KirModuleEndWeather()
    {
        super("endWeather", "End Weather", "Weather", 1, "endweather");
    }

    public String[] getValueNames()
    {
        return VALUES;
    }

    public String getCategory()
    {
        return CATEGORY_END;
    }

    public String[] getTooltip()
    {
        return new String[] {
            "Whether it rains in the End when it rains in the overworld.",
            describeValue("Off", "always clear skies in the End."),
            describeValue("On", "rain, its sound and its darker sky follow the overworld's weather."),
            note("Only has an effect while Overworld Sky is on. Only the look changes: the overworld's weather carries on either way.")
        };
    }
}
