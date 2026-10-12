package colorfullights.compat;

import colorfullights.DirectionalLamps;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Create: Bits 'n' Bobs headlamps ({@code bits_n_bobs:headlamp}): one block holds up to 9 small lamps on a 3×3 grid
 * of its face, all shining along the block's facing. The block entity keeps an int per grid cell
 * ({@code activePlacements}): 0 no lamp, 1 a plain lamp, 2 + n a lamp dyed with dye color n.
 * <p>
 * Read reflectively from the block entity ({@code getActivePlacements()}) or from its saved data, so there is no
 * compile dependency and nothing happens without the mod.
 */
public final class BitsNBobsHeadlamps implements DirectionalLamps.Heads {
    public static final BitsNBobsHeadlamps INSTANCE = new BitsNBobsHeadlamps();

    private static final String TAG = "activePlacements";
    private static final int CELLS = 9;
    /** Grid offsets (4 pixels) of the alignments RIGHT_OR_BOTTOM, MIDDLE, LEFT_OR_TOP. */
    private static final double[] OFFSETS = {-0.25, 0, 0.25};
    /** The lamps sit on the block's back face and are 7 pixels deep; beams start just in front of the lens. */
    private static final double LENS = -0.5 + 8 / 16.0;

    private static Class<?> blockEntityClass;
    private static Method getter;
    private static boolean broken;

    private BitsNBobsHeadlamps() {
    }

    @Override
    public List<DirectionalLamps.Head> fromBlockEntity(BlockState state, BlockEntity blockEntity) {
        int[] placements = placements(blockEntity);
        return placements == null ? List.of() : heads(state, placements);
    }

    @Override
    public List<DirectionalLamps.Head> fromTag(BlockState state, CompoundTag tag) {
        if (tag == null || !tag.contains(TAG, Tag.TAG_INT_ARRAY)) return null;
        return heads(state, tag.getIntArray(TAG));
    }

    private static int[] placements(BlockEntity be) {
        if (be == null || broken) return null;
        try {
            if (be.getClass() != blockEntityClass) {
                Method m = be.getClass().getMethod("getActivePlacements");
                if (m.getReturnType() != int[].class) return null;
                getter = m;
                blockEntityClass = be.getClass();
            }
            return (int[]) getter.invoke(be);
        } catch (NoSuchMethodException e) {
            return null; // some other block entity
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            colorfullights.ColorfulLights.LOGGER.warn("Bits 'n' Bobs headlamps can't be read; they get no beams", e);
            return null;
        }
    }

    /** The lamps of a block from its placements: the lamp layout of the block facing up, turned to its facing. */
    private static List<DirectionalLamps.Head> heads(BlockState state, int[] placements) {
        Direction facing = state.getOptionalValue(BlockStateProperties.FACING).orElse(Direction.UP);
        List<DirectionalLamps.Head> out = new ArrayList<>(4);
        for (int i = 0; i < Math.min(CELLS, placements.length); i++) {
            int value = placements[i];
            if (value == 0) continue;
            // cells are ordered vertical-major; facing up, horizontal runs along x and vertical along z
            Vector3f v = new Vector3f((float) OFFSETS[i % 3], (float) LENS, (float) OFFSETS[i / 3]);
            facing.getRotation().transform(v);
            int color = value >= 2 ? DyeColor.byId(value - 2).getFireworkColor() : -1;
            out.add(new DirectionalLamps.Head(new Vec3(v.x, v.y, v.z), color));
        }
        return out;
    }
}
