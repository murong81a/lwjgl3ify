# LWJGL3ify for Minecraft 1.10.2

Brings LWJGL 3 and modern Java versions to Minecraft 1.10.2.

A port of [LWJGL3ify](https://github.com/murong81a/lwjgl3ify) from 1.12.2 to 1.10.2
(Forge 12.18.3.2511). The 1.12.2 branch is the original work by the GTNewHorizons team.

## What it does

Replaces LWJGL 2 with LWJGL 3.3.3 and runs the game on Java 17 or newer, by way of
[RetroFuturaBootstrap](https://github.com/GTNewHorizons/RetroFuturaBootstrap), which
replaces LaunchWrapper.

The LWJGL 2 API surface (`org.lwjgl.*`) is served by a shim layer under `org.lwjglx.*`
and rewritten at class load time, so the game and its mods keep calling the API they were
compiled against.

## Requirements

- Minecraft 1.10.2 with Forge 12.18.3.2511
- Java 17 or newer
- [MixinBooter](https://github.com/CleanroomMC/MixinBooter) 11.18

## Building

No Gradle. ForgeGradle 2.3 only runs on JDK 8, which cannot compile against a Java 17
Mixin target, so the build is a plain `javac` invocation plus a reobfuscation pass.

```
powershell -File build.ps1
```

Produces the mod jar. The launch profile also needs three companion jars, built separately:

```
powershell -File build-harness.ps1
```

`harness-boot` carries RetroFuturaBootstrap and must come first on the classpath,
`harness-pre` carries the commons set and must precede the game's own copies, and
`harness-post` carries ASM and LWJGL 3 and must come last. They cannot be one jar; see
`PORT_NOTES.md` for why.

## Layout

| Path | What it is |
|---|---|
| `src/main` | the port itself: RFB transformers, the LWJGL 2 to 3 shim, the relauncher |
| `src/generated` | the generated `org.lwjgl.*` compatibility surface |
| `src/util` | build time helpers, not shipped |
| `src/hotswap` | hotswap agent support |
| `src/relauncherStub` | the stub that hands control to the relauncher |
| `PORT_NOTES.md` | every failure this port hit, and the rule that prevents a repeat |

## Status

Works on Java 25: the client starts, loads a world and renders. Verified with OptiFine H5
and several large mods.

Known gaps are recorded in `PORT_NOTES.md`, including the mixin that does not apply.
