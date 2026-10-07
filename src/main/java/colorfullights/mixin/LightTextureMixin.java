package colorfullights.mixin;

import colorfullights.Config;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Darkness: darkens the finished lightmap where both sky and block light are low, so that light level 0 at night
 * and in caves becomes (almost) black regardless of gamma. Runs right before the lightmap is uploaded, i.e. after
 * all vanilla and dimension-effect adjustments of the same frame.
 * <p>
 * Dynamic lights need nothing extra: the Sodium shader looks their light up in this same lightmap (at the raised
 * block light level), and baked dynamic light raises the vertices' block light coordinate.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @Shadow @Final private NativeImage lightPixels;
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V"))
    private void colorful_lights$darken(float partialTicks, CallbackInfo ci) {
        ClientLevel level = this.minecraft.level;
        LocalPlayer player = this.minecraft.player;
        if (level == null || player == null) return;
        if (level.effects().forceBrightLightmap()) return;
        if (!Config.darknessIn(level.dimension().location().toString())) return;

        // Same rules as vanilla: night vision fully, conduit power under water partially.
        float vision = 0.0F;
        if (player.hasEffect(MobEffects.NIGHT_VISION)) {
            vision = GameRenderer.getNightVisionScale(player, partialTicks);
        } else if (player.getWaterVision() > 0.0F && player.hasEffect(MobEffects.CONDUIT_POWER)) {
            vision = player.getWaterVision();
        }
        float strength = Config.darknessStrength * (1.0F - vision);
        if (strength <= 0.0F) return;

        // How much a sky light level of 15 actually lights things: 1 at day,
        // moonlight (scaled by moon phase) at night, full during lightning.
        float skyFactor;
        if (!level.dimensionType().hasSkyLight()) {
            skyFactor = 0.0F;
        } else if (level.getSkyFlashTime() > 0) {
            skyFactor = 1.0F;
        } else {
            float day = Math.max(0.0F, Math.min(1.0F, (level.getSkyDarken(1.0F) - 0.2F) / 0.8F));
            float night = Config.darknessMoonlight * level.getMoonBrightness();
            skyFactor = night + (1.0F - night) * day;
        }

        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                float light = Math.max(sky / 15.0F * skyFactor, block / 15.0F);
                float dark = 1.0F - light;
                float mul = 1.0F - strength * dark * dark * dark * dark;
                if (mul >= 1.0F) continue;

                int c = this.lightPixels.getPixelRGBA(block, sky);
                int r = (int) ((c & 0xFF) * mul);
                int g = (int) ((c >> 8 & 0xFF) * mul);
                int b = (int) ((c >> 16 & 0xFF) * mul);
                this.lightPixels.setPixelRGBA(block, sky, 0xFF000000 | b << 16 | g << 8 | r);
            }
        }
    }
}
