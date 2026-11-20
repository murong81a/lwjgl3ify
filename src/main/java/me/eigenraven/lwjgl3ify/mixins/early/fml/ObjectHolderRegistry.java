package me.eigenraven.lwjgl3ify.mixins.early.fml;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.eigenraven.lwjgl3ify.WasFinalObjectHolder;

/**
 * Ported from lwjgl3ify 1.12.2.
 *
 * The class moved from net.minecraftforge.registries (1.12.2) to
 * net.minecraftforge.fml.common.registry (1.10.2), and the bytecode still calls
 * Field.getModifiers() from within scanClassForFields in both versions, so the
 * redirect target is unchanged apart from the owner name.
 */
@Mixin(value = { net.minecraftforge.fml.common.registry.ObjectHolderRegistry.class }, remap = false)
public abstract class ObjectHolderRegistry {

    @Redirect(
        method = { "Lnet/minecraftforge/fml/common/registry/ObjectHolderRegistry;scanClassForFields(Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;Z)V" },
        at = @At(value = "INVOKE", target = "Ljava/lang/reflect/Field;getModifiers()I"),
        remap = false,
        require = 1)
    public int getFieldModifiersProxy(Field f) {
        int mods = f.getModifiers();
        if (f.isAnnotationPresent(WasFinalObjectHolder.class)) {
            mods |= Modifier.FINAL;
        }
        return mods;
    }
}
