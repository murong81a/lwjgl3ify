package me.eigenraven.lwjgl3ify.mixins.early.fml;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ported from lwjgl3ify 1.12.2 with the ASM package changed.
 *
 * Forge 1.10.2's ASMEventHandler uses the standalone org.objectweb.asm rather
 * than the scala.tools.asm repackage that later Forge versions bundle, so the
 * Opcodes constants come from the same package as Minecraft's own ASM.
 *
 * The class version constant is 50 in the 1.10.2 bytecode (confirmed with
 * javap: a bipush 50 before ClassWriter.visit), which is Java 6. Under modern
 * Java a generated class must be at least version 52, or the verifier rejects
 * it before any mod code runs.
 */
@Mixin(value = net.minecraftforge.fml.common.eventhandler.ASMEventHandler.class, remap = false)
public class ASMEventHandler {

    @ModifyConstant(
        method = "createWrapper(Ljava/lang/reflect/Method;)Ljava/lang/Class;",
        constant = @Constant(intValue = 50),
        remap = false)
    private int useClassVersion52(int originalValue) {
        return Opcodes.V1_8;
    }

    @ModifyConstant(
        method = "createWrapper(Ljava/lang/reflect/Method;)Ljava/lang/Class;",
        slice = @Slice(
            from = @At(
                value = "INVOKE:LAST",
                target = "org/objectweb/asm/MethodVisitor.visitTypeInsn(ILjava/lang/String;)V")),
        constant = @Constant(intValue = 0),
        remap = false)
    private int fixMethodRefType(int originalValue, Method callback) {
        return callback.getDeclaringClass()
            .isInterface() ? 1 : 0;
    }
}
