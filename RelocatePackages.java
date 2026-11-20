import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Relocates package prefixes throughout a jar.
 *
 * <p>This exists because the mod needs library versions newer than the ones Minecraft
 * 1.10.2 puts on the classpath, and classpath order means the game's older copies win.
 * Renaming the bundled copies to a private namespace makes them the only definition of
 * those names anywhere, so they load no matter what the launcher does with ordering.
 *
 * <p>The relocation must be complete to work. Renaming only class entries would leave
 * every caller pointing at the old names, and renaming only the usual type references
 * would miss the places where a class name hides inside a plain string constant (resource
 * paths, reflection targets, ASM internal names built at run time). This tool therefore
 * remaps:
 *
 * <ul>
 *   <li>type references everywhere in the constant pool, including descriptors and
 *       generic signatures, via ASM's ClassRemapper;</li>
 *   <li>string constants whose value looks like an internal name, a descriptor, or a
 *       resource path under one of the relocated prefixes;</li>
 *   <li>entry names in the jar itself, so the classes actually live at the new paths.</li>
 * </ul>
 *
 * <p>Usage: {@code RelocatePackages in.jar out.jar old/prefix/=new/prefix/ ...}
 *
 * <p>The {@code --lib=} arguments are accepted for symmetry with a validator that may
 * want them; this implementation does not need extra classpath context because the
 * mapping is purely textual.
 */
public final class RelocatePackages {

    private RelocatePackages() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                    "usage: RelocatePackages <in.jar> <out.jar> <old/prefix/=new/prefix/> ... [--lib=jar]");
            System.exit(2);
        }
        final Path input = Paths.get(args[0]);
        final Path output = Paths.get(args[1]);

        final List<String[]> pairs = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            final String arg = args[i];
            if (arg.startsWith("--lib=")) {
                continue;
            }
            final int eq = arg.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("bad mapping, expected old/=new/: " + arg);
            }
            pairs.add(new String[] { arg.substring(0, eq), arg.substring(eq + 1) });
        }
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("no mappings given");
        }
        // Longest prefix first, so a nested prefix is not shadowed by a shorter one.
        pairs.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));

        final Mapper mapper = new Mapper(pairs);

        final Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarInputStream in = new JarInputStream(Files.newInputStream(input))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), readAll(in));
            }
        }

        int relocated = 0;
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String name = e.getKey();
                byte[] data = e.getValue();

                if (name.endsWith(".class")) {
                    final byte[] rewritten = transformClass(data, mapper);
                    final String newName = mapper.mapPath(name);
                    if (!newName.equals(name) || rewritten != data) {
                        relocated++;
                    }
                    // Class entries always go through the rename path so the moving
                    // classes actually end up under the new prefix.
                    put(out, mapper.mapPath(name), rewritten);
                } else {
                    // Resource paths can also live under a relocated prefix, e.g. the
                    // service files and property bundles the libraries carry.
                    put(out, mapper.mapPath(name), data);
                }
            }
        }
        System.out.println("relocated " + relocated + " class entries into " + output);
    }

    private static void put(JarOutputStream out, String name, byte[] data) throws IOException {
        final JarEntry entry = new JarEntry(name);
        // Reproducible builds: fixed timestamp, since the content is what identifies it.
        entry.setTime(0L);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static byte[] transformClass(byte[] data, Mapper mapper) throws IOException {
        final ClassReader reader;
        try {
            reader = new ClassReader(data);
        } catch (RuntimeException e) {
            // Not something ASM can read (a stray non-class file with a .class suffix).
            // Leaving it untouched is safer than failing the whole build.
            return data;
        }
        final ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, mapper), 0);
        return writer.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
        final byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /**
     * Remaps internal names, and additionally rewrites string constants that name a class
     * or a resource under a relocated prefix.
     */
    private static final class Mapper extends Remapper {

        private final List<String[]> pairs;

        Mapper(List<String[]> pairs) {
            this.pairs = pairs;
        }

        @Override
        public String map(String internalName) {
            if (internalName == null) {
                return null;
            }
            for (String[] pair : pairs) {
                if (internalName.startsWith(pair[0])) {
                    return pair[1] + internalName.substring(pair[0].length());
                }
            }
            return internalName;
        }

        /** Renames a jar entry path, which uses the same separators as internal names. */
        String mapPath(String path) {
            for (String[] pair : pairs) {
                if (path.startsWith(pair[0])) {
                    return pair[1] + path.substring(pair[0].length());
                }
            }
            return path;
        }

        @Override
        public Object mapValue(Object value) {
            if (value instanceof String str) {
                return mapStringConstant(str);
            }
            return super.mapValue(value);
        }

        /**
         * Rewrites a string constant if it is a type reference or a resource path under a
         * relocated prefix.
         *
         * <p>Library code builds internal names as strings in a handful of places, and
         * service/resource paths are resolved relative to the package root. Rewriting
         * those keeps the relocated copy self-consistent. Anything that does not start
         * with a relocated prefix is returned unchanged, so ordinary message strings are
         * untouched.
         */
        private String mapStringConstant(String str) {
            for (String[] pair : pairs) {
                if (str.startsWith(pair[0])) {
                    return pair[1] + str.substring(pair[0].length());
                }
                // Descriptors such as "Lorg/objectweb/asm/ClassVisitor;".
                final String descPrefix = "L" + pair[0];
                if (str.startsWith(descPrefix)) {
                    return "L" + pair[1] + str.substring(descPrefix.length());
                }
                // Array descriptors.
                final String arrPrefix = "[L" + pair[0];
                if (str.startsWith(arrPrefix)) {
                    return "[L" + pair[1] + str.substring(arrPrefix.length());
                }
            }
            return str;
        }
    }
}
