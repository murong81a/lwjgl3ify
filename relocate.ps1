# Relocates third-party libraries into a private package namespace, so the mod jar can
# carry its own copies without colliding with what Minecraft already has on the classpath.
#
# Why this is necessary, measured rather than assumed:
#
#   Minecraft 1.10.2 ships asm-all-5.0.3, which provides the same org.objectweb.asm
#   package this mod needs at a much newer version. The game's copy sits ahead of the mod
#   on the classpath, so it always wins. An experiment that bundled ASM 9.8 inside the mod
#   jar and left the game's asm-all in place still crashed: Mixin reported
#   "ASM 9.0" because it resolved Opcodes from the game's asm-all, and it then rejected
#   the JAVA_17 compatibility level. The RFB transformers additionally need
#   ClassRemapper, which the game's asm-all does not contain at all.
#
#   Renaming the bundled library packages to me.eigenraven.lwjgl3ify.shadow.* removes the
#   collision entirely: no other jar on the classpath defines those names, so the copies
#   inside the mod jar are the ones that load, regardless of classpath order.
#
# What is relocated and what is not:
#   ASM and commons-* are relocated, because the game ships older versions of the same
#   packages. org.lwjgl (LWJGL 3) is deliberately NOT relocated: the game only carries
#   LWJGL 2, the two versions overlap in just BufferUtils and PointerBuffer, and LWJGL 3
#   loads its natives by looking up resources under its own package path. Renaming it
#   would break that lookup for no benefit.
#
# The relocation is a whole-jar transformation: every class in the bundled libraries is
# renamed, and every reference to them from the mod's own classes is rewritten, including
# descriptors, signatures, string constants that name classes, and resource paths.

param(
    [Parameter(Mandatory = $true)][string]$InputJar,
    [Parameter(Mandatory = $true)][string]$OutputJar,
    # package prefix mappings, e.g. @('org/objectweb/asm/=me/eigenraven/lwjgl3ify/shadow/asm/')
    [Parameter(Mandatory = $true)][string[]]$Mappings,
    [string]$JavaHome = 'D:\JAVA\Java17',
    [string[]]$LibraryJars = @()
)

$ErrorActionPreference = 'Stop'

$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $java)) { throw "java not found: $java" }

# The relocator itself is a small ASM program compiled against ASM 9.8. It is kept as a
# separate source file next to this script and compiled on demand into build\tools, so a
# clean checkout needs nothing beyond the JDK and the jars in libs\.
$relocatorSrc = Join-Path $PSScriptRoot 'RelocatePackages.java'
$relocatorDir = Join-Path $PSScriptRoot 'build\tools'
$relocatorOut = Join-Path $relocatorDir 'classes'
$relocatorMarker = Join-Path $relocatorOut 'RelocatePackages.class'

$libs = Get-ChildItem (Join-Path $PSScriptRoot 'libs') -File -Filter 'asm*.jar' |
    ForEach-Object { $_.FullName }
if (-not $libs) { throw "No ASM jars in libs\; the relocator needs them to compile" }

$asmCp = $libs -join ';'

if (-not (Test-Path $relocatorMarker)) {
    New-Item -ItemType Directory -Force -Path $relocatorOut | Out-Null
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    & $javac --release 17 -nowarn -encoding UTF-8 -classpath $asmCp -d $relocatorOut $relocatorSrc
    if ($LASTEXITCODE -ne 0) { throw "relocator compile failed with $LASTEXITCODE" }
}

# The relocator runs on the ASM jars from libs\, which is fine: it is a build-time tool,
# not part of the shipped mod.
$relocatorCp = (@($relocatorOut) + $libs) -join ';'

$argList = @('-cp', $relocatorCp, 'RelocatePackages', $InputJar, $OutputJar)
foreach ($m in $Mappings) { $argList += $m }
foreach ($l in $LibraryJars) { $argList += "--lib=$l" }

& $java @argList
if ($LASTEXITCODE -ne 0) { throw "relocation failed with $LASTEXITCODE" }
