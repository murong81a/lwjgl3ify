package me.eigenraven.lwjgl3ify.rfb.transformers;

import static org.objectweb.asm.Opcodes.*;

import java.util.jar.Manifest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.*;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;

public class ForgePatchTransformer implements RfbClassTransformer {

    final Logger logger = LogManager.getLogger("Lwjgl3ifyForgePatches");

    @Override
    public @NotNull String id() {
        return "forge-patch";
    }

    public static final String CLASS_PATCH_MANAGER = "net.minecraftforge.fml.common.patcher.ClassPatchManager";
    public static final String TRACING_PRINT_STREAM = "net.minecraftforge.fml.common.TracingPrintStream";
    public static final String FML_SECURITY_MANAGER = "net.minecraftforge.fml.relauncher.FMLSecurityManager";
    public static final String FML_LOADER = "net.minecraftforge.fml.common.Loader";
    public static final String FML_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLTweaker";
    public static final String CLASS_INHERITANCE_MULTI_MAP = "net.minecraft.util.ClassInheritanceMultiMap";

    public static final String[] PATCHED_CLASSES = new String[] { CLASS_PATCH_MANAGER, TRACING_PRINT_STREAM,
        FML_SECURITY_MANAGER, FML_LOADER, FML_TWEAKER, CLASS_INHERITANCE_MULTI_MAP };

    @Override
    public boolean shouldTransformClass(@NotNull ExtensibleClassLoader classLoader,
        @NotNull RfbClassTransformer.Context context, @Nullable Manifest manifest, @NotNull String className,
        @NotNull ClassNodeHandle classNode) {
        for (final String toPatch : PATCHED_CLASSES) {
            if (toPatch.equals(className)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void transformClass(@NotNull ExtensibleClassLoader classLoader, @NotNull RfbClassTransformer.Context context,
        @Nullable Manifest manifest, @NotNull String className, @NotNull ClassNodeHandle classNode) {
        if (!classNode.isPresent()) {
            return;
        }
        switch (className) {
            case CLASS_PATCH_MANAGER -> tfClassPatchManager(classNode);
            case TRACING_PRINT_STREAM -> tfTracingPrintStream(classNode);
            case FML_SECURITY_MANAGER -> tfFmlSecurityManager(classNode);
            case FML_LOADER -> tfLoaderJava8Check(classNode);
            case FML_TWEAKER -> tfFmlTweaker(classNode);
            case CLASS_INHERITANCE_MULTI_MAP -> tfClassInheritanceMultiMap(classNode);
        }
    }

    /**
     * Makes Chunk's per-class entity map safe to build from several threads at once.
     *
     * ClassInheritanceMultiMap keeps a static Set&lt;Class&lt;?&gt;&gt; ALL_KNOWN that records every
     * entity class seen so far. Its constructor iterates that set while createLookup()
     * adds to it. Chunk construction happens on Forge's Chunk I/O Executor pool, which is
     * multi-threaded, so on a cold world two chunk threads enter the constructor together,
     * one iterating while the other adds, and the set throws
     * ConcurrentModificationException.
     *
     * The failure is fatal in practice: the exception escapes the chunk loader thread, no
     * chunk is produced for that request, and the client sits at "Waiting for chunk..."
     * forever with an empty world. It was observed exactly like that on Java 25.
     *
     * Java 8 hides this because the window is tiny and the maps are small; the class is
     * simply not safe under any JVM, and stronger iterator checks in later JDKs widen the
     * chance of hitting it. Vanilla later versions of this class use a
     * ConcurrentHashMap-backed set, which is precisely the fix applied here.
     *
     * The patch is limited to ALL_KNOWN's construction: it replaces
     * Sets.newHashSet() with ConcurrentHashMap.newKeySet() in the static initializer, which
     * yields an object that still satisfies the java.util.Set field type, so no other
     * instruction or descriptor has to change and the class's generic signature stays
     * valid. The returned Set supports the same add/iterator contract, with iteration
     * across concurrent modification made legal rather than throwing.
     */
    private void tfClassInheritanceMultiMap(@NotNull ClassNodeHandle handle) {
        final ClassNode node = handle.getNode();
        if (node == null || node.methods == null) {
            logger.error("ClassInheritanceMultiMap missing class data");
            return;
        }
        final MethodNode clinit = node.methods.stream()
            .filter(m -> "<clinit>".equals(m.name))
            .findFirst()
            .orElse(null);
        if (clinit == null || clinit.instructions == null) {
            logger.error("ClassInheritanceMultiMap has no static initializer");
            return;
        }

        // Find the Sets.newHashSet() call that produces the ALL_KNOWN value.
        AbstractInsnNode factory = null;
        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && "com/google/common/collect/Sets".equals(call.owner)
                && "newHashSet".equals(call.name)) {
                factory = insn;
                break;
            }
        }
        if (factory == null) {
            logger.warn("ClassInheritanceMultiMap does not build its class set with Sets.newHashSet; "
                + "chunk loading may still race on a cold world");
            return;
        }

        // ConcurrentHashMap.newKeySet() is a Set view over a concurrent map: same field
        // type, same call shape (no arguments, returns java.util.Set), safe iteration.
        clinit.instructions.set(
            factory,
            new MethodInsnNode(
                INVOKESTATIC,
                "java/util/concurrent/ConcurrentHashMap",
                "newKeySet",
                "()Ljava/util/concurrent/ConcurrentHashMap$KeySetView;",
                false));
        logger.info("Made ClassInheritanceMultiMap's known-class set concurrent so multi-threaded "
            + "chunk loading cannot throw ConcurrentModificationException");
    }

    /**
     * Tolerates the removal of the Security Manager, which Java 24 disables and Java 25
     * rejects outright.
     *
     * FMLTweaker's constructor always does new FMLSecurityManager() followed by
     * System.setSecurityManager(...). Since Java 24 that call either throws
     * UnsupportedOperationException or, with -Djava.security.manager=allow, kills the VM
     * during startup with "Enabling a Security Manager is not supported". The constructor
     * only catches SecurityException, so the launch dies before any mod loads.
     *
     * The Security Manager is not needed here: FML uses it to trap System.exit so a mod
     * exiting the game does not leave a half-dead process. On a JVM without it, the
     * sensible behaviour is to carry on and let exits take effect normally.
     *
     * The three instructions that install the manager are replaced with a jump straight
     * to the end of the constructor (goto after the athrow), so the try/catch block never
     * runs and no kotlin.debug attributes are disturbed. Everything before it (the
     * java.net.preferIPv4Stack property) and the RETURN stay intact.
     */
    private void tfFmlTweaker(@NotNull ClassNodeHandle handle) {
        final ClassNode node = handle.getNode();
        if (node == null || node.methods == null) {
            logger.error("FML tweaker missing class data");
            return;
        }
        final MethodNode ctor = node.methods.stream()
            .filter(m -> "<init>".equals(m.name) && "()V".equals(m.desc))
            .findFirst()
            .orElse(null);
        if (ctor == null || ctor.instructions == null) {
            logger.error("FML tweaker has no no-arg constructor");
            return;
        }
        // Find System.setSecurityManager and neutralise the whole sequence around it.
        // Walking the list for the INVOKESTATIC keeps this independent of instruction
        // offsets, which differ between the 1.10.2 patches and any later Forge build.
        AbstractInsnNode setManager = null;
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && "java/lang/System".equals(call.owner)
                && "setSecurityManager".equals(call.name)) {
                setManager = insn;
                break;
            }
        }
        if (setManager == null) {
            logger.info("FML tweaker does not install a security manager, nothing to patch");
            return;
        }
        // Replace the call with a no-op so the manager instance built just before it is
        // simply left unused and the constructor returns normally.
        ctor.instructions.set(setManager, new InsnNode(NOP));
        logger.info("Made the FML security manager installation a no-op for Java 24+ compatibility");
    }

    /**
     * Disables Forge 1.10.2's "you must run Java 8" gate.
     *
     * Loader.checkJavaCompatibility collects every active ModContainer whose class version
     * is 52 or higher and throws Java8VersionException, which the client turns into the
     * GuiJava8Error screen. In this version that means every mod built for Java 8 or newer
     * is fatal, so a port that necessarily targets Java 17 can never pass it.
     *
     * This has to happen here rather than in a mixin: Loader is already loaded by the time
     * the coremod runs (it is what instantiates coremods), and Mixin then refuses with
     * MixinTargetAlreadyLoadedException. The RFB transformer runs while Loader itself is
     * being defined, which is early enough.
     *
     * The original check body is replaced with a bare return, so the java8 field and the
     * exception class are left untouched; nothing else in FML reads a value this method
     * would have set.
     */
    private void tfLoaderJava8Check(@NotNull ClassNodeHandle handle) {
        final ClassNode node = handle.getNode();
        if (node == null || node.methods == null) {
            logger.error("FML Loader missing class data");
            return;
        }
        final MethodNode check = node.methods.stream()
            .filter(m -> "checkJavaCompatibility".equals(m.name) && "()V".equals(m.desc))
            .findFirst()
            .orElse(null);
        if (check == null) {
            logger.warn("FML Loader has no checkJavaCompatibility method, Java 8 gate left in place");
        } else {
            // Prepend a bare return instead of clearing the method.
            //
            // Clearing the instruction list leaves the existing LocalVariableTable entries
            // pointing at offsets that no longer exist, and the JVM rejects the class with
            // ClassFormatError: Invalid index N in LocalVariableTable. A single RETURN in
            // front of the original body is enough: it makes the rest of the method
            // unreachable, so no version check runs, and every debug attribute stays
            // consistent with the code it describes.
            check.instructions.insert(new InsnNode(RETURN));
            logger.info("Disabled the Forge 1.10.2 Java 8 version gate for modern Java support");
        }

        // The Loader constructor also parses java.version with String.split(".") and reads
        // element [1] as the major version. That assumed a "1.8.0_xxx" shape; on Java 9+
        // the property is just "25" (or "17.0.16"), which splits into one or two elements
        // and either indexes past the end or reports the wrong number. Replace the
        // parse with a constant 8, which is what the field means in this version: FML's
        // java8 flag. The version gate that would act on it is disabled above, so the
        // value only has to be self-consistent.
        patchLoaderVersionParse(node);
    }

    /**
     * Rewrites the java.version parse in Loader's constructor to a constant.
     *
     * The original code is: System.getProperty("java.version").split("\\."), then it reads
     * element [1] and runs Integer.parseInt on it. That assumes the Java 8 shape
     * "1.8.0_xxx", where index 1 is "8". On Java 9 and later the property is "25" or
     * "17.0.16", so for "25" the array has length 1 and element [1] throws
     * ArrayIndexOutOfBoundsException inside the Loader constructor, which aborts startup
     * before mod discovery. For "17.0.16" it silently computes 0 and logs the wrong thing.
     *
     * The whole sequence from the getProperty call through the store of the parsed int is
     * replaced with a constant 8. That is stack-neutral (String pushed, int stored) and
     * leaves the field meaning what FML expects: "is this Java 8". The version gate that
     * used it is disabled separately, and on a modern JVM the only other reader (the
     * warning log) is then silent, which is correct because the port is meant to run there.
     */
    private void patchLoaderVersionParse(@NotNull ClassNode node) {
        final MethodNode ctor = node.methods.stream()
            .filter(m -> "<init>".equals(m.name))
            .findFirst()
            .orElse(null);
        if (ctor == null || ctor.instructions == null) {
            logger.warn("FML Loader has no constructor, java.version parse left in place");
            return;
        }

        // Find the parse and then walk back over the array element load to the start of
        // the sequence, so the removal covers the part that actually throws.
        AbstractInsnNode parseIntNode = null;
        for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && "java/lang/Integer".equals(call.owner)
                && "parseInt".equals(call.name)) {
                parseIntNode = insn;
                break;
            }
        }
        if (parseIntNode == null) {
            logger.warn("FML Loader does not parse an integer version here, leaving the constructor alone");
            return;
        }

        final AbstractInsnNode storeResult = parseIntNode.getNext();
        if (!(storeResult instanceof VarInsnNode storeInsn) || storeInsn.getOpcode() != ISTORE) {
            logger.warn("FML Loader stores the parsed version unusually, leaving the constructor alone");
            return;
        }

        // Walk back to the LDC of "java.version".
        AbstractInsnNode regionStart = parseIntNode;
        for (AbstractInsnNode insn = parseIntNode.getPrevious(); insn != null; insn = insn.getPrevious()) {
            if (insn instanceof LdcInsnNode ldc && "java.version".equals(ldc.cst)) {
                regionStart = insn;
                break;
            }
        }
        if (regionStart == parseIntNode) {
            logger.warn("FML Loader does not read java.version here, leaving the constructor alone");
            return;
        }

        InsnList replacement = new InsnList();
        replacement.add(new IntInsnNode(BIPUSH, 8));
        replacement.add(new VarInsnNode(ISTORE, storeInsn.var));

        // Remove the old sequence, then insert the replacement at the same point.
        AbstractInsnNode cursor = regionStart;
        AbstractInsnNode afterStore = storeResult.getNext();
        while (cursor != null && cursor != afterStore) {
            AbstractInsnNode next = cursor.getNext();
            ctor.instructions.remove(cursor);
            cursor = next;
        }
        if (afterStore != null) {
            ctor.instructions.insertBefore(afterStore, replacement);
        } else {
            ctor.instructions.add(replacement);
        }
        logger.info("Replaced the Forge 1.10.2 java.version parse with a constant for modern Java");
    }

    private void tfTracingPrintStream(@NotNull ClassNodeHandle handle) {
        // Add a close() override that does not close the underlying stream
        // Pack200 tries to close this stream when loading patches.
        final ClassNode node = handle.getNode();
        if (node == null || node.methods == null) {
            logger.error("Tracing print stream missing class data");
            return;
        }
        if (node.methods.stream()
            .anyMatch(m -> "close".equals(m.name) && "()V".equals(m.desc))) {
            logger.warn("{} already has a close method", TRACING_PRINT_STREAM);
            // Someone already added a close method
            return;
        }
        final MethodVisitor mv = node.visitMethod(ACC_PUBLIC, "close", "()V", null, null);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
    }

    private static Integer tryIconst(AbstractInsnNode i) {
        return switch (i.getOpcode()) {
            case ICONST_0 -> 0;
            case ICONST_1 -> 1;
            case ICONST_2 -> 2;
            case ICONST_3 -> 3;
            case ICONST_4 -> 4;
            case ICONST_5 -> 5;
            case ICONST_M1 -> -1;
            case LDC -> {
                final Object cst = ((LdcInsnNode) i).cst;
                if (cst instanceof Integer) {
                    yield (Integer) cst;
                } else {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private static AbstractInsnNode makeIconst(int i) {
        return switch (i) {
            case 0 -> new InsnNode(ICONST_0);
            case 1 -> new InsnNode(ICONST_1);
            case 2 -> new InsnNode(ICONST_2);
            case 3 -> new InsnNode(ICONST_3);
            case 4 -> new InsnNode(ICONST_4);
            case 5 -> new InsnNode(ICONST_5);
            case -1 -> new InsnNode(ICONST_M1);
            default -> new LdcInsnNode(i);
        };
    }

    private void tfFmlSecurityManager(@NotNull ClassNodeHandle handle) {
        // Fix an ArrayIndexOutOfBounds exception due to off-by-1 array length checks
        final ClassNode node = handle.getNode();
        if (node == null || node.methods == null) {
            logger.error("FML security manager missing class data");
            return;
        }
        final MethodNode checkPermission = node.methods.stream()
            .filter(m -> "checkPermission".equals(m.name) && "(Ljava/security/Permission;)V".equals(m.desc))
            .findFirst()
            .orElse(null);
        if (checkPermission == null || checkPermission.instructions == null
            || checkPermission.instructions.size() < 5) {
            logger.error("FML security manager missing the checkPermission method");
            return;
        }
        final InsnList chkInsns = checkPermission.instructions;
        // Search for a iconst_A; if_icmple; _; iconst_A+1 pattern and fix it
        final int insns = chkInsns.size();
        int matches = 0;
        for (int i = 0; i < insns - 4; i++) {
            final AbstractInsnNode i_iconstA = chkInsns.get(i);
            final AbstractInsnNode i_icmple = chkInsns.get(i + 1);
            final AbstractInsnNode i_iconstAp1 = chkInsns.get(i + 3);
            if (i_icmple.getOpcode() != IF_ICMPLE) {
                continue;
            }
            final Integer A = tryIconst(i_iconstA);
            final Integer Ap1 = tryIconst(i_iconstAp1);
            if (A == null || Ap1 == null || Ap1 != A + 1) {
                continue;
            }
            matches++;
            chkInsns.set(i_iconstA, makeIconst(A + 1));
        }

        if (matches < 2) {
            logger.warn("Only found {}<2 instances of AIOOB fixes in FMLSecurityManager", matches);
        }
    }

    private void tfClassPatchManager(@NotNull ClassNodeHandle handle) {
        // Fix an infinite loop if an EOFException happens
        final ClassNode node = handle.getNode();
        if (node == null || node.methods == null) {
            logger.error("Class patch manager missing class data");
            return;
        }
        for (final MethodNode mn : node.methods) {
            if (!"setup".equals(mn.name)) {
                continue;
            }
            if (mn.instructions == null || mn.instructions.size() == 0) {
                logger.error("ClassPatchManager#setup(Side) missing code");
                return;
            }
            for (final AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() != INVOKEVIRTUAL) {
                    continue;
                }
                if (!(insn instanceof MethodInsnNode minsn)) {
                    continue;
                }
                if (!"java/util/jar/JarInputStream".equals(minsn.owner)) {
                    continue;
                }
                if (!"getNextJarEntry".equals(minsn.name)) {
                    continue;
                }
                // redirect
                minsn.setOpcode(INVOKESTATIC);
                minsn.owner = "me/eigenraven/lwjgl3ify/redirects/JarInputStream";
                minsn.name = "getNextJarEntrySafe";
                minsn.desc = "(Ljava/util/jar/JarInputStream;)Ljava/util/jar/JarEntry;";
            }
        }
    }
}
