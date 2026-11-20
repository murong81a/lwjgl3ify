package me.eigenraven.lwjgl3ify.mixins.early.fml;

import java.util.zip.ZipEntry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Ported from lwjgl3ify 1.12.2.
 *
 * 1.12.2 splits jar discovery into findClassesASM and findClassesJSON; 1.10.2
 * keeps everything in a single discover(ModCandidate, ASMDataTable) method, so
 * only one injector is needed here instead of two.
 *
 * The redirect still hangs off the "__MACOSX" string constant rather than an
 * explicit instruction index: both versions use that constant as the
 * "skip this entry" marker inside the entry loop, and the 1.10.2 bytecode has
 * exactly one occurrence (verified with javap).
 *
 * Skipping module-info.class and META-INF/versions/ matters because 1.10.2's
 * JarDiscoverer predates the module system and would otherwise try to parse
 * multi-release entries as mod classes.
 */
@Mixin(value = { net.minecraftforge.fml.common.discovery.JarDiscoverer.class }, remap = false)
public class JarDiscoverer {

    @Redirect(
        method = "Lnet/minecraftforge/fml/common/discovery/JarDiscoverer;discover(Lnet/minecraftforge/fml/common/discovery/ModCandidate;Lnet/minecraftforge/fml/common/discovery/ASMDataTable;)Ljava/util/List;",
        // target based on the string constant to ensure we are hitting the entry loop
        at = @At(ordinal = 0, value = "CONSTANT", args = { "stringValue=__MACOSX" }, shift = At.Shift.BEFORE),
        remap = false,
        require = 1)
    public String getZipEntryName(ZipEntry ze) {
        String name = ze.getName();
        if (name == null) {
            return null;
        }
        if (name.contains("module-info.class") || name.startsWith("META-INF/versions/")
            || name.contains("org/openjdk/nashorn")
            || name.contains("jakarta/servlet/")) {
            // Triggers the continue in the loop
            return "__MACOSX_ignoreme";
        }
        return name;
    }
}
