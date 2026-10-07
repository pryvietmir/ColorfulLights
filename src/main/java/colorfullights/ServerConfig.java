package colorfullights;

import colorfullights.network.DarknessSettings;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings decided by the server (config/colorful_lights-server.toml of the server, or of the game for
 * singleplayer worlds). Sent to every player on login, so players can't turn darkness off on their side.
 */
public final class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("True darkness: places without block or sky light are (almost) black, whatever the gamma.",
                "Night vision and conduit power cancel it. Dynamic lights still light things up.",
                "Decided by the server for all players.").push("darkness");
    }

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for darkness.")
            .translation("colorful_lights.configuration.darkness.enabled")
            .define("enabled", true);

    private static final ModConfigSpec.DoubleValue STRENGTH = BUILDER
            .comment("0 = vanilla lighting, 1 = light level 0 is pitch black.")
            .defineInRange("strength", 1.0, 0.0, 1.0);

    private static final ModConfigSpec.DoubleValue MOONLIGHT = BUILDER
            .comment("How much the full moon lights the surface at night (the new moon is always dark).")
            .defineInRange("moonlight", 0.25, 0.0, 1.0);

    private static final ModConfigSpec.BooleanValue OVERWORLD = BUILDER
            .comment("Darkness in the Overworld.")
            .define("overworld", true);

    private static final ModConfigSpec.BooleanValue NETHER = BUILDER
            .comment("Darkness in the Nether (its ambient light is also set to 0 by this mod's data).")
            .define("nether", true);

    private static final ModConfigSpec.BooleanValue END = BUILDER
            .comment("Darkness in the End.")
            .define("end", true);

    private static final ModConfigSpec.BooleanValue OTHER = BUILDER
            .comment("Darkness in every other (modded) dimension.")
            .define("otherDimensions", true);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED = BUILDER
            .comment("Dimension ids without darkness, e.g. \"twilightforest:twilight_forest\".")
            .defineListAllowEmpty("excludedDimensions", List.of(), () -> "twilightforest:twilight_forest",
                    o -> o instanceof String s && ResourceLocation.tryParse(s.trim()) != null);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private ServerConfig() {
    }

    /** The darkness settings players get. Only valid while a server (dedicated or integrated) is running. */
    public static DarknessSettings darkness() {
        Set<String> excluded = EXCLUDED.get().stream().map(d -> d.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return new DarknessSettings(ENABLED.get(), STRENGTH.get().floatValue(), MOONLIGHT.get().floatValue(),
                OVERWORLD.get(), NETHER.get(), END.get(), OTHER.get(), excluded);
    }
}
