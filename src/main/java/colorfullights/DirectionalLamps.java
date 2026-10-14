package colorfullights;

import colorfullights.compat.BitsNBobsHeadlamps;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * @param color   color of the light (0xRRGGBB), or -1 for the one derived from the block's textures
     * @param litAbove the lamp only shines when the block's own light level (as declared) is above this
     * @param heads   for blocks holding several lamps in their block entity: where they are; null for one lamp
     */
    public record Lamp(ResourceLocation block, Mount mount, int level, float inner, float outer, float range, int ambient,
                       int color, int litAbove, Heads heads) {
        Lamp(ResourceLocation block, Mount mount, int level, float inner, float outer, float range, int ambient, int color) {
            this(block, mount, level, inner, outer, range, ambient, color, 0, null);
        }
    }

    /**
     * One lamp of a block holding several.
     *
     * @param offset where its beam starts, from the block's center (in the block's own axes)
     * @param color  its own color (0xRRGGBB, e.g. dyed), or -1 for the lamp's
     */
    public record Head(Vec3 offset, int color) {
    }

    /** The lamps of a block that holds several of them in its block entity (all shine along the block's direction). */
    public interface Heads {
        /** The lamps of a placed block (empty if its block entity isn't there or holds none). */
        List<Head> fromBlockEntity(BlockState state, BlockEntity blockEntity);

        /** The lamps of a block from its saved block entity data (e.g. a block on a contraption); may be null. */
        List<Head> fromTag(BlockState state, CompoundTag tag);
    }

    private static final List<Lamp> TABLE = List.of(
            // Create: Bells & Whistles headlight: lantern light, on any face (lens away from the support block)
            new Lamp(ResourceLocation.fromNamespaceAndPath("bellsandwhistles", "headlight"), Mount.FACE_ATTACHED,
                    15, 14f, 32f, 40f, 6, -1),
            // Design n' Decor frontlights: lens along the facing (the base sits on the opposite side); only the
            // lit ones emit light, so unlit ones get no beam
            frontlight("andesite_frontlight"), frontlight("brass_frontlight"), frontlight("copper_frontlight"),
            frontlight("gold_frontlight"), frontlight("industrial_frontlight"), frontlight("iron_frontlight"),
            // Create: Bits 'n' Bobs headlamp: up to 9 small lamps on one face (in the block entity), each dyeable;
            // lit when powered above 6 or switched on by hand
            new Lamp(ResourceLocation.fromNamespaceAndPath("bits_n_bobs", "headlamp"), Mount.FACING,
                    15, 12f, 28f, 36f, 6, 0xFFF0DC, 6, BitsNBobsHeadlamps.INSTANCE));

    /** A slightly warm white. */
    private static final int WARM_WHITE = 0xFFF0DC;

    private static Lamp frontlight(String name) {
        return new Lamp(ResourceLocation.fromNamespaceAndPath("dndecor", name), Mount.FACING, 15, 14f, 32f, 40f, 6, WARM_WHITE);
    }

    /** The color a lamp's light has: the one painted over all lamps in the config, or its own (-1: from textures). */
    public static int colorOf(Lamp lamp) {
        int painted = Config.spotColor;
        return painted >= 0 ? painted : lamp.color();
    }

    private static volatile Map<Block, Lamp> byBlock;
    /** States of lamps in which they shine (taken before {@link #applyAmbientLight} lowers their light). */
    private static final Set<BlockState> LIT = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

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
        java.util.List<Lamp> lamps = new java.util.ArrayList<>(TABLE);
        // every Design n' Decor frontlight, also kinds this table doesn't name (netherite, zinc, newer ones)
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getNamespace().equals("dndecor") && id.getPath().endsWith("_frontlight")
                    && lamps.stream().noneMatch(l -> l.block().equals(id))) {
                lamps.add(frontlight(id.getPath()));
            }
        }
        for (Lamp lamp : lamps) {
            BuiltInRegistries.BLOCK.getOptional(lamp.block()).ifPresent(block -> {
                map.put(block, lamp);
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    if (state.getLightEmission() > lamp.litAbove()) LIT.add(state);
                }
            });
        }
        return byBlock = map;
    }

    /** Whether a lamp in this state shines (e.g. switched on). */
    public static boolean isLit(BlockState state) {
        if (byBlock == null) build();
        return LIT.contains(state);
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
