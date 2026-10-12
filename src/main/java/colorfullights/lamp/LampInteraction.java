package colorfullights.lamp;

import colorfullights.DirectionalLamps;
import colorfullights.beam.BeamLights;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * Settings of placed directional lamps: a dye paints the beam (white dye removes the paint), a wrench steps through
 * the beam distance, and a wrench used while sneaking through the focus. Common side; the same decision is taken on
 * the client (to cancel the click) and on the server (to apply it).
 */
public final class LampInteraction {
    private static final ResourceLocation CREATE_WRENCH = ResourceLocation.fromNamespaceAndPath("create", "wrench");
    private static final TagKey<Item> WRENCH_TAG = ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

    private LampInteraction() {
    }

    private static boolean isWrench(ItemStack stack) {
        return stack.is(WRENCH_TAG) || BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(CREATE_WRENCH);
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || DirectionalLamps.isEmpty()) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        DirectionalLamps.Lamp lamp = DirectionalLamps.of(level.getBlockState(pos));
        if (lamp == null) return;
        boolean dye = stack.getItem() instanceof DyeItem;
        if (!dye && !isWrench(stack)) return;
        // lamps that hold several heads dye each head themselves (the beam takes the head's color)
        if (dye && lamp.heads() != null) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(level instanceof ServerLevel serverLevel) || !(event.getEntity() instanceof ServerPlayer player)) return;

        LampSavedData data = LampSavedData.get(serverLevel);
        long key = pos.asLong();
        long value = data.get(key);
        Component message;
        if (dye) {
            DyeColor color = ((DyeItem) stack.getItem()).getDyeColor();
            if (color == DyeColor.WHITE) {
                value = LampSettings.withoutColor(value);
                message = Component.translatable("colorful_lights.lamp.unpainted");
            } else {
                value = LampSettings.withColor(value, color.getFireworkColor());
                message = Component.translatable("colorful_lights.lamp.painted", Component.translatable("color.minecraft." + color.getName()));
            }
            if (!player.getAbilities().instabuild) stack.shrink(1);
            level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1f, 1f);
        } else if (player.isShiftKeyDown()) {
            value = LampSettings.nextFocus(value);
            message = Component.translatable("colorful_lights.lamp.focus", percent(LampSettings.focus(value)));
            level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 1.2f);
        } else {
            value = LampSettings.nextRange(value);
            message = Component.translatable("colorful_lights.lamp.range", percent(LampSettings.range(value)));
            level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 0.9f);
        }
        player.displayClientMessage(message, true);
        if (data.set(key, value)) {
            PacketDistributor.sendToPlayersInDimension(serverLevel, LampSettingsPayload.one(key, value));
            BeamLights.refresh(serverLevel, pos);
        }
    }

    private static String percent(float multiplier) {
        return String.format(Locale.ROOT, "%d%%", Math.round(multiplier * 100));
    }

    /** A lamp block was removed or replaced by something else: its settings go. Server thread. */
    public static void onLampRemoved(ServerLevel level, BlockPos pos) {
        LampSavedData data = LampSavedData.get(level);
        long key = pos.asLong();
        if (data.set(key, 0L)) PacketDistributor.sendToPlayersInDimension(level, LampSettingsPayload.one(key, 0L));
    }

    public static void sendAll(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        PacketDistributor.sendToPlayer(player, LampSavedData.get(level).all());
    }

    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player);
    }
}
