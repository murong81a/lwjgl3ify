# Builds the two companion jars this port needs at launch: a "pre" library and a "post"
# library. Together they carry every dependency the mod itself cannot hold.
#
# Why the dependencies cannot simply live in the mod jar:
#
#   RetroFuturaBootstrap runs its plugin phase before any mod jar is on the classpath, and
#   its plugins construct ASM ClassWriter objects there. An ASM jar supplied only by the
#   mod is invisible at that moment and the launch dies with
#   NoClassDefFoundError: org/objectweb/asm/ClassWriter.
#
#   Relocating the dependencies into the mod was tried and does not work for two of them.
#   ASM cannot move because RFB's public API names org.objectweb.asm.tree.ClassNode in its
#   method signatures, so a relocated mixin/transformer class stops matching the interface
#   it implements (observed: NoSuchMethodError 'shadow.asm.tree.ClassNode ...
#   ClassNodeHandle.getNode()'). commons-compress cannot move either, because its Pack200
#   replacement lives at org.apache.commons.compress.java.util.jar.Pack200 and relocating
#   yields a path containing java/util/jar, which the game's class loader will not define.
#
# Why there are two jars and not one:
#
#   The classpath position requirements genuinely conflict, and this was measured both ways:
#
#     * The commons set must come BEFORE the game's libraries. Otherwise the game's
#       commons-io 2.4 wins, commons-compress 1.26's Pack200 adapter calls
#       BoundedInputStream.builder() (a 2.16+ API) on the 2.4 class, and the launch dies
#       with NoSuchMethodError inside the patch loader.
#
#     * ASM must come AFTER the mod and its plain dependency jars. With ASM first, Mixin
#       rejects this port's mixin configs:
#       "Compatibility level JAVA_17 ... higher than the maximum level supported by this
#        version of mixin (JAVA_13)". With ASM last, the same configs resolve to JAVA_17
#       and apply.
#
#   Splitting along that line satisfies both: the pre jar holds only the commons packages
#   (which Mixin never probes), the post jar holds ASM, LWJGL 3 and RFB.
#
# This mirrors upstream lwjgl3ify, which ships a separate "forgePatches" library in the
# launcher metadata, and Cleanroom, which installs its dependencies into the profile.

$ErrorActionPreference = 'Stop'

$base = $PSScriptRoot
$javaHome = 'D:\JAVA\Java17'
$jar = Join-Path $javaHome 'bin\jar.exe'

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Merge-JarInto {
    param([string]$JarPath, [string]$Into, [string[]]$ExcludePrefixes = @(),
          [switch]$KeepVersionedEntries)
    if (-not (Test-Path $JarPath)) { throw "missing: $JarPath" }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $name = $entry.FullName
            # Each merged jar has its own manifest and signatures. Those cannot be
            # combined, and a stale signature makes the JVM reject the class.
            if ($name -match '^META-INF/(MANIFEST\.MF|[^/]*\.SF|[^/]*\.RSA|[^/]*\.DSA)$') { continue }
            # META-INF/versions/<n>/ holds per-Java-version variants. Dropping them
            # silently downgrades a multi-release jar to its Java 8 classes; RFB needs
            # versions/9 or it loads the Java 8 URLClassLoaderBase and aborts with
            # "Java newer than 8 is used".
            if (-not $KeepVersionedEntries -and $name.StartsWith('META-INF/versions/')) { continue }
            $skip = $false
            foreach ($p in $ExcludePrefixes) {
                if ($name.StartsWith($p)) { $skip = $true; break }
            }
            if ($skip) { continue }
            $dest = Join-Path $Into ($name -replace '/', '\')
            New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
        }
    } finally {
        $zip.Dispose()
    }
}

function New-HarnessJar {
    param([string]$OutJar, [string]$Title)
    if (Test-Path $OutJar) { Remove-Item $OutJar -Force }
    New-Item -ItemType Directory -Force -Path (Split-Path $OutJar) | Out-Null
    $mf = Join-Path $base "build\$Title.MF"
    # Multi-Release must be declared, or the JVM ignores META-INF/versions/ entirely.
    @"
Manifest-Version: 1.0
Implementation-Title: $Title
Implementation-Version: 1.10.2-port-0.0.1
Multi-Release: true

"@ -replace "`r?`n", "`r`n" | Set-Content -Encoding ASCII -NoNewline $mf
    & $jar --create --file $OutJar --manifest $mf -C (Join-Path $base "build\$Title") .
    if ($LASTEXITCODE -ne 0) { throw "$Title jar failed with $LASTEXITCODE" }
    $size = [math]::Round((Get-Item $OutJar).Length / 1MB, 2)
    Write-Host "Built $OutJar ($size MB)"
}

# --- bootstrap: RetroFuturaBootstrap only, goes very first ---------------------------
#
# RFB has its own position requirement, separate from ASM and commons, and this is the
# reason there are three jars rather than two.
#
# The JVM resolves -Djava.system.class.loader before it opens a single jar. The named class
# therefore has to be reachable on the raw system classpath, ahead of everything else, or
# the launch dies with
#   java.lang.Error: com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader
#   Caused by: java.lang.ClassNotFoundException
# before Minecraft starts.
#
# ASM, by contrast, has to sit AFTER the game's libraries (see the post section below).
# Keeping RFB and ASM in one jar cannot satisfy both, which is what a single "harness"
# jar got wrong for several iterations.
$bootStage = Join-Path $base 'build\harness-boot'
if (Test-Path $bootStage) { Remove-Item $bootStage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $bootStage | Out-Null
# KeepVersionedEntries is required: RFB is a multi-release jar whose META-INF/versions/9/
# classes are the ones used on Java 9+. Without them it loads its Java 8 URLClassLoaderBase
# and aborts with "Java newer than 8 is used".
Merge-JarInto -JarPath (Join-Path $base 'libs\retrofuturabootstrap-1.1.2.jar') -Into $bootStage -KeepVersionedEntries
New-HarnessJar -OutJar (Join-Path $base 'build\libs\lwjgl3ify-1.10.2-harness-boot.jar') -Title 'harness-boot'

# --- pre: commons only, goes ahead of the game's libraries ---------------------------
#
# ASM is deliberately NOT in this jar. Putting it here was tried and breaks the launch:
# Mixin then resolves a compatibility level of JAVA_8 and rejects the port's JAVA_17
# configs. See PORT_NOTES.md pitfall 12.
$preStage = Join-Path $base 'build\harness-pre'
if (Test-Path $preStage) { Remove-Item $preStage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $preStage | Out-Null
foreach ($name in @('commons-io-2.17.0', 'commons-lang3-3.14.0', 'commons-codec-1.17.1',
                    'commons-compress-1.26.2')) {
    Merge-JarInto -JarPath (Join-Path $base "libs\$name.jar") -Into $preStage
}
New-HarnessJar -OutJar (Join-Path $base 'build\libs\lwjgl3ify-1.10.2-harness-pre.jar') -Title 'harness-pre'

# --- post: ASM and LWJGL 3 + natives, comes after the mod ---------------------------
$postStage = Join-Path $base 'build\harness-post'
if (Test-Path $postStage) { Remove-Item $postStage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $postStage | Out-Null
foreach ($name in @('asm-9.8', 'asm-tree-9.8', 'asm-commons-9.8', 'asm-analysis-9.8', 'asm-util-9.8')) {
    Merge-JarInto -JarPath (Join-Path $base "libs\$name.jar") -Into $postStage
}
foreach ($name in @('lwjgl-3.3.3', 'lwjgl-glfw-3.3.3', 'lwjgl-opengl-3.3.3',
                    'lwjgl-openal-3.3.3', 'lwjgl-stb-3.3.3', 'lwjgl-tinyfd-3.3.3')) {
    Merge-JarInto -JarPath (Join-Path $base "run\$name.jar") -Into $postStage
}
foreach ($name in @('lwjgl-3.3.3-natives-windows', 'lwjgl-glfw-3.3.3-natives-windows',
                    'lwjgl-opengl-3.3.3-natives-windows', 'lwjgl-openal-3.3.3-natives-windows',
                    'lwjgl-stb-3.3.3-natives-windows', 'lwjgl-tinyfd-3.3.3-natives-windows')) {
    # Only the windows/ tree is what LWJGL's SharedLibraryLoader looks up.
    Merge-JarInto -JarPath (Join-Path $base "run\$name.jar") -Into $postStage -ExcludePrefixes @('META-INF/')
}
# RFB is NOT merged here. It lives in harness-boot, which goes first, because the JVM
# resolves -Djava.system.class.loader before opening any jar. Duplicating it here would
# also put a second copy of net.minecraft.launchwrapper.Launch on the classpath.
New-HarnessJar -OutJar (Join-Path $base 'build\libs\lwjgl3ify-1.10.2-harness-post.jar') -Title 'harness-post'
