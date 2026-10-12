package colorfullights.debug;

import colorfullights.ColorfulLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Development aid: with {@code -Dcolorful_lights.screenshot=<name>}, saves screenshots (without the GUI) into
 * {@code screenshots/} 15 and 25 seconds after entering a world, then closes the game if
 * {@code -Dcolorful_lights.screenshotQuit=true}. Lets the look of the mod be checked without the window in front.
 */
public final class AutoScreenshot {
    private static final String NAME = System.getProperty("colorful_lights.screenshot");
    private static final boolean QUIT = Boolean.getBoolean("colorful_lights.screenshotQuit");
    private static final int[] AT_TICKS = {300, 500};

    private static int ticks;
    private static int shots;

    private AutoScreenshot() {
    }

    public static boolean enabled() {
        return NAME != null && !NAME.isBlank();
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ticks++;
        if (ticks == AT_TICKS[0] - 40) {
            // the frames before the shots are drawn without the GUI and menus
            if (mc.screen != null) mc.setScreen(null);
            mc.options.hideGui = true;
        }
        if (shots < AT_TICKS.length && ticks == AT_TICKS[shots]) {
            String file = NAME + "_" + (shots + 1) + ".png";
            Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(),
                    message -> ColorfulLights.LOGGER.info("Auto screenshot: {}", message.getString()));
            shots++;
        } else if (shots == AT_TICKS.length && QUIT && ticks == AT_TICKS[AT_TICKS.length - 1] + 40) {
            mc.stop();
        }
    }
}
