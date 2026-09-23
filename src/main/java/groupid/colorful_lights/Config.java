package groupid.colorful_lights;

import groupid.colorful_lights.light.LightColors;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for colored lighting.")
            .define("enabled", true);

    private static final ModConfigSpec.DoubleValue INTENSITY = BUILDER
            .comment("How strongly light colors tint lit surfaces (0 = no tint, 1 = full color).")
            .defineInRange("intensity", 1.0, 0.0, 1.0);

    private static final ModConfigSpec.DoubleValue SATURATION = BUILDER
            .comment("Multiplies the saturation of light colors (hue is kept). 1 = colors as sampled; higher = more vivid.")
            .defineInRange("saturation", 4.0, 0.0, 10.0);

    private static final ModConfigSpec.DoubleValue BRIGHTNESS = BUILDER
            .comment("Multiplies block light on terrain, so lights look brighter and reach further. 1 = vanilla.")
            .defineInRange("brightness", 1.6, 1.0, 4.0);

    private static final ModConfigSpec.DoubleValue SKY_REDUCTION = BUILDER
            .comment("How much sky light washes out the tint (0 = ignore sky light, 1 = no tint in full daylight).")
            .defineInRange("skyReduction", 0.5, 0.0, 1.0);

    private static final ModConfigSpec.DoubleValue OBJECT_LIGHT_RANGE = BUILDER
            .comment("How much further light sources reach Create contraptions and Aeronautics ships than blocks",
                    "(1 = like vanilla, 2 = twice as far). Only lights open to the sky get the full extra reach.")
            .defineInRange("objectLightRange", 2.0, 1.0, 4.0);

    private static final ModConfigSpec.BooleanValue DYNAMIC_LIGHTS = BUILDER
            .comment("Held and dropped light items, burning entities, blazes and Create contraption lamps emit light.")
            .define("dynamicLights", true);

    private static final ModConfigSpec.BooleanValue TEXTURE_COLORS = BUILDER
            .comment("Derive each light's color from the glowing parts of its block textures.")
            .define("textureColors", true);

    private static final ModConfigSpec.ConfigValue<String> DEFAULT_COLOR = BUILDER
            .comment("Color (#RRGGBB) of light sources when textureColors is off or a block has no usable texture.")
            .define("defaultColor", "#FFFFFF", Config::isColor);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> COLORS = BUILDER
            .comment("Per-block color overrides, as \"namespace:block=#RRGGBB\". These win over texture colors.")
            .defineListAllowEmpty("colors", LightColors.DEFAULTS, () -> "minecraft:torch=#FFFFFF", Config::isEntry);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static volatile boolean enabled = true;
    public static volatile float intensity = 1.0f;
    public static volatile float saturation = 4.0f;
    public static volatile float brightness = 1.6f;
    public static volatile float skyReduction = 0.5f;
    public static volatile boolean dynamicLights = true;
    public static volatile float objectLightRange = 2.0f;

    private static boolean isColor(Object o) {
        return o instanceof String s && LightColors.parseColor(s) >= 0;
    }

    private static boolean isEntry(Object o) {
        if (!(o instanceof String s)) return false;
        int eq = s.indexOf('=');
        return eq > 0 && LightColors.parseColor(s.substring(eq + 1)) >= 0;
    }

    static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) apply();
    }

    static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) apply();
    }

    /** Values of the last applied config, to ignore reloads that change nothing (e.g. the file being re-saved). */
    private static List<Object> applied = List.of();

    /** Invalidates the color table; ClientEvents rebuilds it and re-meshes chunks on the next tick. */
    private static synchronized void apply() {
        List<Object> values = List.of(ENABLED.get(), INTENSITY.get(), SATURATION.get(), BRIGHTNESS.get(), SKY_REDUCTION.get(),
                DYNAMIC_LIGHTS.get(), OBJECT_LIGHT_RANGE.get(), TEXTURE_COLORS.get(), DEFAULT_COLOR.get(), List.copyOf(COLORS.get()));
        if (values.equals(applied)) return;
        applied = values;

        enabled = ENABLED.get();
        intensity = INTENSITY.get().floatValue();
        saturation = SATURATION.get().floatValue();
        brightness = BRIGHTNESS.get().floatValue();
        skyReduction = SKY_REDUCTION.get().floatValue();
        dynamicLights = DYNAMIC_LIGHTS.get();
        objectLightRange = OBJECT_LIGHT_RANGE.get().floatValue();
        LightColors.configure(COLORS.get(), LightColors.parseColor(DEFAULT_COLOR.get()), TEXTURE_COLORS.get());
    }
}
