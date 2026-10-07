package colorfullights;

import colorfullights.light.LightColors;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
            .defineInRange("saturation", 1.6, 0.0, 10.0);

    private static final ModConfigSpec.DoubleValue BRIGHTNESS = BUILDER
            .comment("Multiplies block light on terrain, so lights look brighter and reach further. 1 = vanilla.")
            .defineInRange("brightness", 1.2, 1.0, 4.0);

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

    private static final ModConfigSpec.BooleanValue TINT_ENTITIES = BUILDER
            .comment("Tint mobs, players, items in hand, chests and other block entities, and particles with the light color.")
            .define("tintEntities", true);

    private static final ModConfigSpec.BooleanValue TEXTURE_COLORS = BUILDER
            .comment("Derive each light's color from the glowing parts of its block textures.")
            .define("textureColors", true);

    private static final ModConfigSpec.ConfigValue<String> DEFAULT_COLOR = BUILDER
            .comment("Color (#RRGGBB) of light sources when textureColors is off or a block has no usable texture.")
            .define("defaultColor", "#FFFFFF", Config::isColor);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> COLORS = BUILDER
            .comment("Per-block color overrides, as \"namespace:block=#RRGGBB\". These win over texture colors.")
            .defineListAllowEmpty("colors", LightColors.DEFAULTS, () -> "minecraft:torch=#FFFFFF", Config::isEntry);

    // --- darkness ---

    static {
        BUILDER.comment("True darkness: places without block or sky light are (almost) black, whatever the gamma.",
                "Night vision and conduit power cancel it. Dynamic lights still light things up.").push("darkness");
    }

    private static final ModConfigSpec.BooleanValue DARKNESS_ENABLED = BUILDER
            .comment("Master switch for darkness.")
            .translation("colorful_lights.configuration.darkness.enabled")
            .define("enabled", true);

    private static final ModConfigSpec.DoubleValue DARKNESS_STRENGTH = BUILDER
            .comment("0 = vanilla lighting, 1 = light level 0 is pitch black.")
            .defineInRange("strength", 1.0, 0.0, 1.0);

    private static final ModConfigSpec.DoubleValue DARKNESS_MOONLIGHT = BUILDER
            .comment("How much the full moon lights the surface at night (the new moon is always dark).")
            .defineInRange("moonlight", 0.25, 0.0, 1.0);

    private static final ModConfigSpec.BooleanValue DARKNESS_OVERWORLD = BUILDER
            .comment("Darkness in the Overworld.")
            .define("overworld", true);

    private static final ModConfigSpec.BooleanValue DARKNESS_NETHER = BUILDER
            .comment("Darkness in the Nether. Its ambient light is set to 0 by this mod's data (needs the mod on the server).")
            .define("nether", true);

    private static final ModConfigSpec.BooleanValue DARKNESS_END = BUILDER
            .comment("Darkness in the End.")
            .define("end", true);

    private static final ModConfigSpec.BooleanValue DARKNESS_OTHER = BUILDER
            .comment("Darkness in every other (modded) dimension.")
            .define("otherDimensions", true);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> DARKNESS_EXCLUDED = BUILDER
            .comment("Dimension ids without darkness, e.g. \"twilightforest:twilight_forest\".")
            .defineListAllowEmpty("excludedDimensions", List.of(), () -> "twilightforest:twilight_forest",
                    o -> o instanceof String s && ResourceLocation.tryParse(s.trim()) != null);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    public static volatile boolean enabled = true;
    public static volatile float intensity = 1.0f;
    public static volatile float saturation = 1.6f;
    public static volatile float brightness = 1.2f;
    public static volatile float skyReduction = 0.5f;
    public static volatile boolean dynamicLights = true;
    public static volatile float objectLightRange = 2.0f;
    public static volatile boolean tintEntities = true;

    public static volatile boolean darkness = true;
    public static volatile float darknessStrength = 1.0f;
    public static volatile float darknessMoonlight = 0.25f;
    private static volatile boolean darknessOverworld = true, darknessNether = true, darknessEnd = true, darknessOther = true;
    private static volatile Set<String> darknessExcluded = Set.of();

    /** Whether darkness applies in a dimension (by id, e.g. "minecraft:overworld"). */
    public static boolean darknessIn(String dimension) {
        if (!darkness || darknessStrength <= 0f || darknessExcluded.contains(dimension)) return false;
        return switch (dimension) {
            case "minecraft:overworld" -> darknessOverworld;
            case "minecraft:the_nether" -> darknessNether;
            case "minecraft:the_end" -> darknessEnd;
            default -> darknessOther;
        };
    }

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
        // darkness only changes the lightmap, which is rebuilt every tick anyway
        darknessStrength = DARKNESS_STRENGTH.get().floatValue();
        darknessMoonlight = DARKNESS_MOONLIGHT.get().floatValue();
        darknessOverworld = DARKNESS_OVERWORLD.get();
        darknessNether = DARKNESS_NETHER.get();
        darknessEnd = DARKNESS_END.get();
        darknessOther = DARKNESS_OTHER.get();
        darknessExcluded = DARKNESS_EXCLUDED.get().stream().map(d -> d.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        darkness = DARKNESS_ENABLED.get();

        List<Object> values = List.of(ENABLED.get(), INTENSITY.get(), SATURATION.get(), BRIGHTNESS.get(), SKY_REDUCTION.get(),
                DYNAMIC_LIGHTS.get(), OBJECT_LIGHT_RANGE.get(), TINT_ENTITIES.get(), TEXTURE_COLORS.get(), DEFAULT_COLOR.get(), List.copyOf(COLORS.get()));
        if (values.equals(applied)) return;
        applied = values;

        enabled = ENABLED.get();
        intensity = INTENSITY.get().floatValue();
        saturation = SATURATION.get().floatValue();
        brightness = BRIGHTNESS.get().floatValue();
        skyReduction = SKY_REDUCTION.get().floatValue();
        dynamicLights = DYNAMIC_LIGHTS.get();
        objectLightRange = OBJECT_LIGHT_RANGE.get().floatValue();
        tintEntities = TINT_ENTITIES.get();
        LightColors.configure(COLORS.get(), LightColors.parseColor(DEFAULT_COLOR.get()), TEXTURE_COLORS.get());
    }
}
