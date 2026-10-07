package colorfullights.beam;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible light where a directional lamp's beam hits (see {@link BeamLights}): real block light for mob spawning
 * and for everything that reads the light engine. Can't be seen, touched or picked; anything can be placed into it.
 * A random tick removes it when no lamp claims it any more (a lamp broken while its beam's chunk was unloaded).
 */
public class BeamLightBlock extends Block {
    public static final MapCodec<BeamLightBlock> CODEC = simpleCodec(BeamLightBlock::new);
    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 1, 15);

    public BeamLightBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LEVEL, 15));
    }

    public static Properties defaultProperties() {
        return Properties.of().replaceable().noCollission().noLootTable().noOcclusion().noTerrainParticles()
                .pushReaction(PushReaction.DESTROY).randomTicks().strength(-1f, 3600000f)
                .lightLevel(state -> state.getValue(LEVEL));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LEVEL);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!BeamLights.isClaimed(level, pos)) level.removeBlock(pos, false);
    }
}
