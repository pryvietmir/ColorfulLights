package colorfullights.network;

import colorfullights.ColorfulLights;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * Darkness settings, decided by the server and sent to each player on login and when the server config changes.
 * The client darkens its lightmap by the last settings received; without any (main menu) there is no darkness.
 */
public record DarknessSettings(boolean enabled, float strength, float moonlight, boolean overworld, boolean nether,
                               boolean end, boolean otherDimensions, Set<String> excludedDimensions)
        implements CustomPacketPayload {
    public static final Type<DarknessSettings> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ColorfulLights.MODID, "darkness"));

    public static final StreamCodec<ByteBuf, DarknessSettings> STREAM_CODEC = StreamCodec.of(
            (buf, s) -> {
                ByteBufCodecs.BOOL.encode(buf, s.enabled);
                ByteBufCodecs.FLOAT.encode(buf, s.strength);
                ByteBufCodecs.FLOAT.encode(buf, s.moonlight);
                ByteBufCodecs.BOOL.encode(buf, s.overworld);
                ByteBufCodecs.BOOL.encode(buf, s.nether);
                ByteBufCodecs.BOOL.encode(buf, s.end);
                ByteBufCodecs.BOOL.encode(buf, s.otherDimensions);
                ByteBufCodecs.VAR_INT.encode(buf, s.excludedDimensions.size());
                for (String d : s.excludedDimensions) ByteBufCodecs.STRING_UTF8.encode(buf, d);
            },
            buf -> {
                boolean enabled = ByteBufCodecs.BOOL.decode(buf);
                float strength = ByteBufCodecs.FLOAT.decode(buf);
                float moonlight = ByteBufCodecs.FLOAT.decode(buf);
                boolean overworld = ByteBufCodecs.BOOL.decode(buf);
                boolean nether = ByteBufCodecs.BOOL.decode(buf);
                boolean end = ByteBufCodecs.BOOL.decode(buf);
                boolean other = ByteBufCodecs.BOOL.decode(buf);
                int n = ByteBufCodecs.VAR_INT.decode(buf);
                Set<String> excluded = new HashSet<>();
                for (int i = 0; i < n; i++) excluded.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                return new DarknessSettings(enabled, clamp01(strength), clamp01(moonlight), overworld, nether, end,
                        other, Set.copyOf(excluded));
            });

    /** The settings from the server the client is connected to, or null when not connected. */
    private static volatile DarknessSettings current;

    public static DarknessSettings current() {
        return current;
    }

    static void receive(DarknessSettings settings) {
        ColorfulLights.LOGGER.debug("Darkness settings from the server: {}", settings);
        current = settings;
    }

    /** Client side, on leaving a world or server. */
    public static void reset() {
        current = null;
    }

    /** Whether darkness applies in a dimension (by id, e.g. "minecraft:overworld"). */
    public boolean isEnabledIn(String dimension) {
        if (!enabled || strength <= 0f || excludedDimensions.contains(dimension)) return false;
        return switch (dimension) {
            case "minecraft:overworld" -> overworld;
            case "minecraft:the_nether" -> nether;
            case "minecraft:the_end" -> end;
            default -> otherDimensions;
        };
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
