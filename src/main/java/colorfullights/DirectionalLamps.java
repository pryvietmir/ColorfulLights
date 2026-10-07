package colorfullights;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocks of other mods that are lamps shining in one direction (headlights, floodlights). They get a long-range spot
 * light along their facing on the client (also on Create contraptions and Sable ships, turning with them), real light
 * where the beam hits on the server, and a weaker all-around glow than the block itself declares.
 * <p>
 * The table is built in and known to both sides; blocks of mods that aren't installed are skipped.
 */
public final class DirectionalLamps {
    /** How the direction a lamp shines in follows from its block state. */
    public enum Mount {
        /** Lever-like blocks (face + facing): on a wall along facing, on the floor up, on the ceiling down. */
        FACE_ATTACHED,
        /** Along the facing property. */
        FACING,
        /** Against the facing property. */
        FACING_OPPOSITE
    }

    /**
     * A directional lamp.
     *
     * @param level   light level of the beam at the lamp (the beam fades linearly to 0 at {@code range})
     * @param inner   half angle (degrees) of the fully lit core of the beam
     * @param outer   half angle (degrees) where the beam fades out
     * @param range   reach of the beam in blocks
     * @param ambient the block's own all-around light level instead of the declared one (-1: keep it)
     */
    public record Lamp(ResourceLocation block, Mount mount, int level, float inner, float outer, float range, int ambient) {
    }

    private static final List<Lamp> TABLE = List.of(
            // Create: Bells & Whistles headlight: lantern light, on any face (lens away from the support block)
            new Lamp(ResourceLocation.fromNamespaceAndPath("bellsandwhistles", "headlight"), Mount.FACE_ATTACHED,
                    15, 14f, 32f, 40f, 6));

    private static volatile Map<Block, Lamp> byBlock;

    private DirectionalLamps() {
    }

    /** The lamp a block state is, or null. Valid once the block registry is complete. */
    public static Lamp of(BlockState state) {
        Map<Block, Lamp> map = byBlock;
        if (map == null) map = build();
        return map.isEmpty() ? null : map.get(state.getBlock());
    }

    public static boolean isEmpty() {
        Map<Block, Lamp> map = byBlock;
        if (map == null) map = build();
        return map.isEmpty();
    }

    private static synchronized Map<Block, Lamp> build() {
        if (byBlock != null) return byBlock;
        Map<Block, Lamp> map = new IdentityHashMap<>();
        for (Lamp lamp : TABLE) {
            BuiltInRegistries.BLOCK.getOptional(lamp.block()).ifPresent(block -> map.put(block, lamp));
        }
        return byBlock = map;
    }

    /** The direction a lamp in this state shines in. */
    public static Direction direction(Lamp lamp, BlockState state) {
        return switch (lamp.mount()) {
            case FACE_ATTACHED -> {
                AttachFace face = state.getOptionalValue(BlockStateProperties.ATTACH_FACE).orElse(AttachFace.WALL);
                yield face == AttachFace.FLOOR ? Direction.UP : face == AttachFace.CEILING ? Direction.DOWN : facing(state);
            }
            case FACING -> facing(state);
            case FACING_OPPOSITE -> facing(state).getOpposite();
        };
    }

    private static Direction facing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) return state.getValue(BlockStateProperties.FACING);
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return Direction.NORTH;
    }

    /** Lowers the all-around light of the lamps that ask for it. Run once the registries are frozen (both sides). */
    public static void applyAmbientLight() {
        build();
        for (Map.Entry<Block, Lamp> e : byBlock.entrySet()) {
            int ambient = e.getValue().ambient();
            if (ambient < 0) continue;
            for (BlockState state : e.getKey().getStateDefinition().getPossibleStates()) {
                if (state.getLightEmission() > ambient) {
                    ((colorfullights.mixin.BlockStateBaseAccessor) state).colorful_lights$setLightEmission(ambient);
                }
            }
            ColorfulLights.LOGGER.info("Directional lamp {}: beam {} blocks, own light {}", e.getValue().block(),
                    e.getValue().range(), ambient);
        }
    }
}
