package colorfullights.lamp;

import colorfullights.ColorfulLights;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Lamp settings for the client: all of a level's lamps ({@code replace}, on login and dimension change) or just the
 * ones that changed. A value of 0 means the lamp is back to its defaults.
 */
public record LampSettingsPayload(boolean replace, long[] keys, long[] values) implements CustomPacketPayload {
    public static final Type<LampSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ColorfulLights.MODID, "lamp_settings"));

    public static final StreamCodec<ByteBuf, LampSettingsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.BOOL.encode(buf, p.replace);
                ByteBufCodecs.VAR_INT.encode(buf, p.keys.length);
                for (int i = 0; i < p.keys.length; i++) {
                    ByteBufCodecs.VAR_LONG.encode(buf, p.keys[i]);
                    ByteBufCodecs.VAR_LONG.encode(buf, p.values[i]);
                }
            },
            buf -> {
                boolean replace = ByteBufCodecs.BOOL.decode(buf);
                int n = ByteBufCodecs.VAR_INT.decode(buf);
                long[] keys = new long[n], values = new long[n];
                for (int i = 0; i < n; i++) {
                    keys[i] = ByteBufCodecs.VAR_LONG.decode(buf);
                    values[i] = ByteBufCodecs.VAR_LONG.decode(buf);
                }
                return new LampSettingsPayload(replace, keys, values);
            });

    public static LampSettingsPayload one(long key, long value) {
        return new LampSettingsPayload(false, new long[]{key}, new long[]{value});
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
