package me.eigenraven.lwjgl3ify.mixins.early.fml;

import java.lang.reflect.Field;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.google.common.base.Throwables;

/**
 * Ported from lwjgl3ify 1.12.2.
 *
 * 1.12.2 keeps the two helpers in ObjectHolderRef$FinalFieldHelper; 1.10.2 has a
 * top-level net.minecraftforge.fml.common.registry.FinalFieldHelper instead, so
 * the target class name changes here.
 */
@Mixin(targets = { "net.minecraftforge.fml.common.registry.FinalFieldHelper" }, remap = false)
public class ObjectHolderRef {

    /**
     * @author eigenraven
     * @reason Simple helper function
     */
    @Overwrite(remap = false)
    static Field makeWritable(Field f) {
        try {
            f.setAccessible(true);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
        return f;
    }

    /**
     * @author TwilightFlower
     * @reason avoid reflection on the modifiers field, which no longer exists on
     *         Java 12 and later
     */
    @Overwrite(remap = false)
    static void setField(Field field, Object instance, Object thing) throws Exception {
        field.set(instance, thing);
    }
}
