# Build the 1.10.2 port of lwjgl3ify.
#
# Toolchain notes, because this build does not use Gradle:
#
#   ForgeGradle 2.3 is the only build tool that resolves Forge 1.10.2, and it only runs
#   on Gradle 4.x, which only runs on JDK 8. These sources use switch expressions, pattern
#   matching and records, so javac must be 17 or newer. Jabel (upstream's answer, which
#   lowers modern syntax to class file 52) does not work here: the plugin crashes on JDK
#   17 with a BasicJavacTask/JavacTaskImpl cast error, and on JDK 21/25 its bundled
#   ByteBuddy 1.11.21 rejects class files 65/69.
#
#   That constraint does not limit this mod, because the mod exists to run Minecraft on
#   Java 17 or newer. Shipping class file 61 is the point, and it matches the requirement
#   that the launcher use Java 17+ anyway.
#
# What this produces, and what it deliberately does not:
#
#   build\libs\lwjgl3ify-1.10.2-port-0.0.1.jar is the MOD: only this port's own classes
#   plus its resources. It does NOT contain ASM, commons, LWJGL 3 or RFB. Those belong to
#   the launch profile and are built by build-harness.ps1; see that script for the
#   measured reasons (RFB needs ASM before mods load; RFB's API exposes ASM types so ASM
#   cannot be relocated; commons-compress's Pack200 cannot be relocated because its path
#   contains java/util/jar).
#
#   This split mirrors both upstream lwjgl3ify (a separate "forgePatches" library listed
#   in the launcher metadata) and Cleanroom (dependencies installed into the profile).

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$base = $PSScriptRoot
$outDir = Join-Path $base 'build\classes'
$refmap = Join-Path $outDir 'mixins.lwjgl3ify.refmap.json'

$javaHome = 'D:\JAVA\Java17'
$javac = Join-Path $javaHome 'bin\javac.exe'
$jar = Join-Path $javaHome 'bin\jar.exe'
$javaExe = Join-Path $javaHome 'bin\java.exe'

if (-not (Test-Path $javac)) { throw "JDK 17 not found at $javaHome" }

# Deobfuscated Forge 1.10.2 + Minecraft, MCP stable_29 names.
$forgeJar = Join-Path $root '_tmp\ysm\port\.gradle\minecraft\forgeBin-1.10.2-12.18.3.2511-PROJECT(port).jar'
if (-not (Test-Path $forgeJar)) { throw "1.10.2 Forge jar not found: $forgeJar" }

# MCP stable_29 SRG, used by the Mixin annotation processor so the refmap holds SRG names
# that resolve in a production (obfuscated) client.
$srg = Join-Path $root '_tmp\ysm\gradle-home\caches\minecraft\de\oceanlabs\mcp\mcp_stable\29\1.10.2\srgs\mcp-srg.srg'
if (-not (Test-Path $srg)) { throw "MCP SRG not found: $srg" }

$libs = Get-ChildItem (Join-Path $base 'libs') -File -Filter '*.jar' | ForEach-Object { $_.FullName }
if (-not $libs) { throw "No jars in $base\libs" }

# Library versions Minecraft 1.10.2 itself puts on the classpath must come FIRST so javac
# links against the overloads the game will resolve at runtime. This is not politeness:
# compiling against log4j 2.17 selected a warn(String, Object) overload that does not
# exist in the game's log4j 2.0-beta9, and the jar died at the first transform with
# NoSuchMethodError.
$runtimeLibs = @()
$runtimeDir = Join-Path $base 'libs-runtime'
if (Test-Path $runtimeDir) {
    $runtimeLibs = Get-ChildItem $runtimeDir -File -Filter '*.jar' | ForEach-Object { $_.FullName }
}
$cp = ($runtimeLibs + $libs + $forgeJar) -join ';'

# The annotation processor lives outside libs: it must be on the processor path only, or
# its shaded ASM and Guava shadow the versions the game resolves at runtime.
$processorPath = (Get-ChildItem (Join-Path $base 'tools') -File -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'

# Sources. src\util is excluded: it is a build-time tool upstream (runComparisonTool) that
# needs commons-io 2.15 and lang3 3.14 APIs and has no place in a mod jar.
$sources = @()
foreach ($set in @('generated', 'main', 'hotswap', 'relauncherStub')) {
    $dir = Join-Path $base "src\$set\java"
    if (Test-Path $dir) {
        $sources += Get-ChildItem $dir -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName }
    }
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$argFile = Join-Path $base 'build\sources.txt'
$sources -join "`n" | Set-Content -Encoding ASCII $argFile

Write-Host "Compiling $($sources.Count) sources against 1.10.2 ..."

# Each -A option must be one argv element, hence the interpolation: writing
# '-Akey=' + $value inside an array literal yields two separate arguments.
$apArgs = @(
    "-AoutRefMapFile=$refmap",
    "-AreobfSrgFile=$srg",
    '-AmappingTypes=searge',
    '-AdefaultObfuscationEnv=searge',
    '-AMSG_NO_OBFDATA_FOR_SHADOW=warning',
    '-AMSG_TARGET_ELEMENT_NOT_FOUND=warning'
)

& $javac `
    '--release', '17' `
    '-Xlint:-options' `
    '-encoding', 'UTF-8' `
    '-nowarn' `
    '-classpath', $cp `
    '-processorpath', $processorPath `
    '-d', $outDir `
    $apArgs `
    "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed with $LASTEXITCODE" }

$classCount = (Get-ChildItem $outDir -Recurse -File -Filter '*.class').Count
Write-Host "Compiled $classCount classes"
if (-not (Test-Path $refmap)) {
    throw "Mixin refmap was not generated; mixins targeting obfuscated members would not apply"
}

# Reobfuscate to SRG names.
#
# Minecraft 1.10.2 runs from its obfuscated jar, so a mod keeping MCP member names cannot
# link against it: the game calls func_71410_x while the mod asks for getMinecraft, and the
# launch dies with NoSuchMethodError inside the first frame that touches Minecraft.
# ForgeGradle normally does this with reobfJar; with no Gradle, ForgeAutoRenamingTool and
# the same MCP stable_29 SRG do the job. mcp-srg.srg maps MCP to SRG, which is the
# direction needed.
$fart = 'D:\pcl\libraries\net\minecraftforge\ForgeAutoRenamingTool\0.1.22\ForgeAutoRenamingTool-0.1.22-all.jar'
if (-not (Test-Path $fart)) { throw "ForgeAutoRenamingTool not found: $fart" }

$manifest = @'
Manifest-Version: 1.0
FMLCorePlugin: me.eigenraven.lwjgl3ify.core.Lwjgl3ifyCoremod
FMLCorePluginContainsFMLMod: true
ForceLoadAsMod: true
Implementation-Title: Lwjgl3ify
Implementation-Version: 1.10.2-port-0.0.1
Multi-Release: true

'@
$manifestPath = Join-Path $base 'build\MANIFEST.MF'
# The jar manifest format requires CRLF line endings and a trailing blank line.
$manifest -replace "`r?`n", "`r`n" | Set-Content -Encoding ASCII -NoNewline $manifestPath

$stage = Join-Path $base 'build\stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item (Join-Path $outDir '*') $stage -Recurse -Force
Copy-Item (Join-Path $base 'src\main\resources\*') $stage -Recurse -Force

$preJar = Join-Path $base 'build\libs\lwjgl3ify-1.10.2-port-0.0.1-pre.jar'
if (Test-Path $preJar) { Remove-Item $preJar -Force }
& $jar --create --file $preJar --manifest $manifestPath -C $stage .
if ($LASTEXITCODE -ne 0) { throw "staging jar failed with $LASTEXITCODE" }

$jarPath = Join-Path $base 'build\libs\lwjgl3ify-1.10.2-port-0.0.1.jar'
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }

$fartArgs = @('-jar', $fart, '--input', $preJar, '--output', $jarPath, '--map', $srg,
              '--ann-fix', '--ids-fix', '--record-fix', '--threads', '4')
foreach ($lib in ($libs + $runtimeLibs + $forgeJar)) {
    $fartArgs += @('-e', $lib)
}

Write-Host "Reobfuscating to SRG names ..."
& $javaExe @fartArgs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "reobfuscation failed with $LASTEXITCODE" }
Remove-Item $preJar -Force

$size = [math]::Round((Get-Item $jarPath).Length / 1MB, 2)
Write-Host "Built $jarPath ($size MB)"
Write-Host "Run build-harness.ps1 to produce the companion launch-profile library."
