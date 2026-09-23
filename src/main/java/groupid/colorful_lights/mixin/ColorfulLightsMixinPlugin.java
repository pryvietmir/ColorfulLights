package groupid.colorful_lights.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies the Sodium, Create, Flywheel and Sable mixins only when those mods are installed. */
public class ColorfulLightsMixinPlugin implements IMixinConfigPlugin {
    private static final String SODIUM_PACKAGE = "groupid.colorful_lights.mixin.sodium.";
    private static final String CREATE_PACKAGE = "groupid.colorful_lights.mixin.create.";
    private static final String FLYWHEEL_PACKAGE = "groupid.colorful_lights.mixin.flywheel.";
    private static final String SABLE_PACKAGE = "groupid.colorful_lights.mixin.sable.";

    private boolean sodiumLoaded;
    private boolean createLoaded;
    private boolean flywheelLoaded;
    private boolean sableLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumLoaded = LoadingModList.get().getModFileById("sodium") != null;
        createLoaded = LoadingModList.get().getModFileById("create") != null;
        flywheelLoaded = LoadingModList.get().getModFileById("flywheel") != null;
        sableLoaded = LoadingModList.get().getModFileById("sable") != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(SODIUM_PACKAGE)) return sodiumLoaded;
        if (mixinClassName.startsWith(CREATE_PACKAGE)) return createLoaded;
        if (mixinClassName.startsWith(FLYWHEEL_PACKAGE)) return flywheelLoaded;
        if (mixinClassName.startsWith(SABLE_PACKAGE)) return sableLoaded;
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
