package colorfullights.debug;

import colorfullights.ColorfulLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Development aid: with {@code -Dcolorful_lights.screenshot=<name>}, saves screenshots (without the GUI) into
 * {@code screenshots/} 15 and 25 seconds (or at {@code screenshotTicks}) after entering a world, and half a second
 * after any chat message containing {@code CLSHOT} (so a test scene can place the camera, then ask for a shot).
 * With {@code -Dcolorful_lights.screenshotQuit=true} the game closes after the last timed shot (or after
 * {@code screenshotQuitAfter} seconds). Lets the look of the mod be checked without the window in front.
 */
public final class AutoScreenshot {
    private static final String NAME = System.getProperty("colorful_lights.screenshot");
    private static final boolean QUIT = Boolean.getBoolean("colorful_lights.screenshotQuit");
    /** Ticks in the world at which to take the screenshots ({@code -Dcolorful_lights.screenshotTicks=300,500}). */
    private static final int[] AT_TICKS = java.util.Arrays.stream(
            System.getProperty("colorful_lights.screenshotTicks", "300,500").split(","))
            .mapToInt(t -> Integer.parseInt(t.trim())).toArray();
    /** With screenshotQuit: seconds in the world before closing (at least until the last screenshot). */
    private static final int QUIT_TICKS = Math.max(AT_TICKS[AT_TICKS.length - 1] + 40,
            Integer.getInteger("colorful_lights.screenshotQuitAfter", 0) * 20);

    private static int ticks;
    /** Timed shots taken, and shots taken in all (for the file names). */
    private static int timed, shots;
    /** Tick of a screenshot asked for in chat, or -1. */
    private static int requested = -1;

    private AutoScreenshot() {
    }

    public static boolean enabled() {
        return NAME != null && !NAME.isBlank();
    }

    public static void onChat(ClientChatReceivedEvent event) {
        if (!event.getMessage().getString().contains("CLSHOT")) return;
        requested = ticks + 10;
        event.setCanceled(true); // not in the picture
        Minecraft.getInstance().options.hideGui = true;
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
        if (ticks == requested) {
            requested = -1;
            take(mc);
        }
        if (timed < AT_TICKS.length && ticks == AT_TICKS[timed]) {
            timed++;
            take(mc);
        } else if (QUIT && ticks == QUIT_TICKS) {
            mc.stop();
        }
    }

    private static void take(Minecraft mc) {
        if (mc.screen != null) mc.setScreen(null);
        mc.options.hideGui = true;
        String file = NAME + "_" + (++shots) + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(),
                message -> ColorfulLights.LOGGER.info("Auto screenshot: {}", message.getString()));
        ColorfulLights.LOGGER.info("Auto screenshot {} camera at {} yaw {} pitch {}", file,
                mc.gameRenderer.getMainCamera().getPosition(), mc.gameRenderer.getMainCamera().getYRot(),
                mc.gameRenderer.getMainCamera().getXRot());
    }
}
