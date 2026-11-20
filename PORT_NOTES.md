# lwjgl3ify 移植到 Minecraft 1.10.2 交接文档

本文档记录移植产物、运行环境、以及一路上踩过的坑。写作目的是让接手的人不必重走
已经走过的死路。所有"实测"字样都对应真实运行日志，不是推断。

---

## 一、当前状态

| 项目 | 状态 |
|---|---|
| 上游源码编译到 1.10.2 | 完成，639 个源文件，706 个类 |
| Mixin 配置迁移 | 完成，7 个 mixin 应用成功，1 个（STB 缝合）已知失败 |
| 独立可运行（Java 25 + LWJGL 3.3.3） | 完成，可进存档，地形正常 |
| 依赖打包 | 完成，两个 harness jar |
| 放进 `mods/` 就能用 | **不行**，见第五节 |
| 全自动安装 | 未完成 |

最后一次成功运行的证据：`run\launch.log`，1309 行，无 `Exception in thread`，
Mixin 兼容级别 `JAVA_17`，显卡 NVIDIA RTX 5070，Protactinium 间接渲染激活。

---

## 二、源码位置

```
D:\MuRong\cwsgi12313\MRPlus\lwjgl3ify-1102\
├── src\
│   ├── generated\java\        443 个  org.lwjglx 与 org.lwjgl 的 shim 层（上游生成）
│   ├── main\java\             192 个  移植主体：RFB 插件、转换器、mixin、relauncher
│   ├── util\java\             4 个    上游构建工具，不打包（见坑 9）
│   ├── hotswap\java\          2 个    热重载支持
│   └── relauncherStub\java\   2 个    重启器图形控制台
├── src\main\resources\
│   ├── mixins.lwjgl3ify.early.json         7 个 mixin 的注册表
│   ├── mixins.lwjgl3ify.early.stb_stitch.json   STB 缝合（required=false）
│   ├── mixins.lwjgl3ify.early.stb_tex.json      STB 纹理加载（required=false）
│   ├── META-INF\rfb-plugin\lwjgl3ify.properties  RFB 插件声明
│   └── mcmod.info
├── libs\       编译期依赖 + 打包源（31 个 jar）
├── libs-runtime\  Minecraft 自带的库版本，编译时优先（见坑 3）
├── tools\      Mixin 注解处理器
├── run\        测试实例：启动脚本、LWJGL3 jar、natives、日志
├── build\libs\ 三个交付产物
├── build.ps1           构建 mod jar（0.79 MB）
├── build-harness.ps1   构建两个 harness jar
├── run-client.ps1      组装类路径并启动客户端
├── relocate.ps1        包名重定位驱动（当前未用于最终产物）
└── RelocatePackages.java  重定位工具本体
```

上游源码副本：`D:\MuRong\cwsgi12313\MRPlus\_tmp\lwjgl3ify-src\`
（来自 `https://github.com/murong81a/lwjgl3ify`，master 分支）

RetroFuturaBootstrap 源码：`D:\MuRong\cwsgi12313\MRPlus\_tmp\rfb-src\`

---

## 三、运行环境

### JDK

| 用途 | 路径 | 说明 |
|---|---|---|
| 编译 + 运行 | `D:\JAVA\Java17` | `--release 17`，产物 class 61 |
| 运行验证 | `D:\JAVA\java25` | OpenJDK 25+37，验证现代 Java 可用 |

### 游戏与依赖

```
游戏实例   D:\pcl\versions\1.10.2-Forge_12.18.3.2511-OptiFine_I5\
游戏库     D:\pcl\libraries\
资源       D:\pcl\assets\
测试游戏目录 D:\MuRong\cwsgi12313\MRPlus\lwjgl3ify-1102\run\game\
```

### 构建所需的离线资源

```
反编译 Forge 1.10.2
  D:\MuRong\cwsgi12313\MRPlus\_tmp\ysm\port\.gradle\minecraft\
    forgeBin-1.10.2-12.18.3.2511-PROJECT(port).jar

MCP stable_29 SRG 映射
  D:\MuRong\cwsgi12313\MRPlus\_tmp\ysm\gradle-home\caches\minecraft\
    de\oceanlabs\mcp\mcp_stable\29\1.10.2\srgs\mcp-srg.srg

ForgeAutoRenamingTool
  D:\pcl\libraries\net\minecraftforge\ForgeAutoRenamingTool\0.1.22\
    ForgeAutoRenamingTool-0.1.22-all.jar
```

### 构建与运行命令

```
# 构建 mod jar
powershell -NoProfile -ExecutionPolicy Bypass -File lwjgl3ify-1102\build.ps1

# 构建两个 harness jar（改了依赖后需要重跑）
powershell -NoProfile -ExecutionPolicy Bypass -File lwjgl3ify-1102\build-harness.ps1

# 启动客户端（-JavaHome 可换 JDK）
powershell -NoProfile -ExecutionPolicy Bypass -File lwjgl3ify-1102\run-client.ps1 -JavaHome D:\JAVA\java25
```

### 显卡设置（本机特有，重要）

```
HKCU\Software\Microsoft\DirectX\UserGpuPreferences
  D:\JAVA\Java17\bin\java.exe = GpuPreference=2   (高性能)
  D:\JAVA\java25\bin\java.exe = GpuPreference=2   (高性能，本移植手动加的)

含义  2 = 高性能(独显)  1 = 省电(核显)  0 = 让 Windows 决定
```

本机内屏接在 AMD Radeon 610M 上，独显是 NVIDIA RTX 5070 Laptop。
未设 `GpuPreference=2` 时 Java 25 会走核显，帧数从 600+ 掉到 60-80。

---

## 四、交付产物

| 文件 | 大小 | 内容 | 放哪 |
|---|---|---|---|
| `lwjgl3ify-1.10.2-port-0.0.1.jar` | 0.79 MB | 只有移植代码和资源 | `mods/` |
| `lwjgl3ify-1.10.2-harness-pre.jar` | 2.85 MB | ASM 9.8 + commons 全套 | **启动器库列表最前** |
| `lwjgl3ify-1.10.2-harness-post.jar` | 3.61 MB | LWJGL 3.3.3 + 6 个 dll + RetroFuturaBootstrap | **启动器库列表最后** |

两个 harness 没有 `mcmod.info`，也没有 `FMLCorePlugin`，它们是库不是模组。

### 实测可工作的类路径顺序（66 条，`run\launch.log` 实录）

```
[0]  harness-pre.jar                        <- 必须最前
[15] commons-lang3-3.3.2.jar                <- 游戏自带，保留
[16] commons-io-2.4.jar                     <- 游戏自带，保留（Forge 自己要用）
[17] commons-codec-1.9.jar                  <- 游戏自带，保留
[22] commons-compress-1.8.1.jar             <- 游戏自带，保留
[24] commons-logging-1.1.3.jar              <- 游戏自带，保留
[46] mixinbooter-11.18.jar
[47] lwjgl-3.3.3-natives-windows.jar
[48] lwjgl-3.3.3.jar
[59] asm-9.8.jar                            <- 独立 jar，见坑 12
[60] asm-analysis-9.8.jar
[61] asm-commons-9.8.jar
[62] asm-tree-9.8.jar
[63] asm-util-9.8.jar
[64] lwjgl3ify-1.10.2-port-0.0.1.jar
[65] harness-post.jar                       <- 必须最后
```

---

## 五、为什么还没做到"丢进 mods 就能玩"

两个 harness 的位置要求互相冲突，而 `mods/` 里的 jar 顺序由 FML 决定，无法控制：

- **commons 必须在游戏库之前**。否则游戏的 commons-io 2.4 抢占，
  commons-compress 1.26 的 Pack200 适配器调用 `BoundedInputStream.builder()`
  （2.16+ 才有的 API）落到 2.4 上，启动即 `NoSuchMethodError`。
- **ASM 必须在 mod jar 之后**（实测：见坑 12）。ASM 太靠前时 Mixin 只认
  `JAVA_8`，拒绝本移植 `JAVA_17` 的 mixin 配置。

所以两个 harness 必须写进启动器的 `libraries` 数组，而不是 `mods/`。
上游 lwjgl3ify 用 `forgePatches` 这个 classifier 做同样的事，Cleanroom 则是
安装时把依赖铺进 profile。下一步工作是生成一份可直接用的版本 JSON。

---

## 六、踩过的坑

按"踩到 -> 现象 -> 根因 -> 结论"记录。每条都是实际发生的。

### 坑 1：Jabel 在本机三个 JDK 上都跑不通

**现象**：上游用 Jabel 把 Java 17 语法降级成 class 52。本机尝试全部失败。

```
JDK 17 + Jabel 1.0.0  -> ClassCastException:
                         BasicJavacTask cannot be cast to JavacTaskImpl
JDK 21 + Jabel 1.0.0  -> Unsupported class file major version 65
                         (Jabel 内嵌 ByteBuddy 1.11.21 太旧)
JDK 25 + Jabel 1.0.0  -> Unsupported class file major version 69
```

**结论**：放弃 Jabel，直接 `--release 17` 编译出 class 61。代价是 mixin 的
`compatibilityLevel` 必须写 `JAVA_17`，引出了坑 12。这是整条路线的分岔点。

### 坑 2：PowerShell `-Encoding UTF8` 写入 BOM

**现象**：`javac` 报 `illegal character: '\ufeff'`。

**根因**：PowerShell 5 的 `-Encoding UTF8` 会写 UTF-8 BOM，javac 不认。

**结论**：写源文件用 `[System.IO.File]::WriteAllText()` 或本会话的 write 工具，
不用 `Set-Content -Encoding UTF8`。Mixin 配置带 BOM 也会让 Mixin 整体加载失败，
而症状只是"mixin 全部不生效"，很难查。

### 坑 3：编译期用了比游戏新的库版本，运行期炸

**现象**：启动即 `NoSuchMethodError: Logger.warn(String, Object)`。

**根因**：编译类路径上是 log4j 2.17.1，它有个非 varargs 的
`warn(String, Object)` 重载，javac 选了它。游戏实际是 log4j 2.0-beta9，
只有 `warn(String, Object...)`。

**结论**：`libs-runtime/` 放游戏自带版本（log4j 2.0-beta9、gson 2.2.4、
guava 17.0、commons 各旧版），编译时排在 `libs/` 之前。这类不匹配靠运行时
逐个追是不可接受的。

### 坑 4：RFB 必须在系统类路径上，且排在 launchwrapper 之前

**现象**：`ClassNotFoundException: com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader`
以及后续 `NoClassDefFoundError: java/util/jar/Pack200`。

**根因**：`-Djava.system.class.loader` 在任何 jar manifest 被读之前就要解析。
且游戏自带 `launchwrapper-1.12.jar` 里的 `Launch` 会抢在 RFB 的 `Launch` 之前加载，
导致 RFB 插件阶段根本不运行，重定向转换器一个都没注册。

**结论**：RFB jar 放类路径第一位，并从配置文件里**删掉**
`net.minecraft:launchwrapper:1.12`。

### 坑 5：直接调 `GsonBuilder.disableJdkUnsafe()` 在游戏 gson 2.2.4 上崩

**现象**：`NoSuchMethodError: GsonBuilder.disableJdkUnsafe()`。

**根因**：该方法 2.9 才有，游戏是 2.2.4。

**结论**：`rfb/EarlyConfig.java` 里改成反射调用，找不到就跳过。

### 坑 6：Log4j 2 未配置时只输出 ERROR，看不到 RFB 早期日志

**现象**：启动失败时日志只有几行，完全看不出 RFB 插件是否加载。

**结论**：加一份 `run\early-log4j2.xml`，用 `-Dlog4j.configurationFile` 指过去，
根级别 DEBUG。排查早期失败必开。

### 坑 7：MixinBooter 与 RFB 的类加载器不兼容

**现象**：`RuntimeException: Unable to add MixinBooter into the parent ClassLoader`。

**根因**：MixinBooter 反射调 `URLClassLoader.addURL`，Java 9+ 是 protected，
需要 `--add-opens java.base/java.net`。

**结论**：完整照抄上游 `java9args.txt` 的 24 条 `--add-opens`，一条都不能少。

### 坑 8：`--add-opens` 一条都不能省

**现象**：补齐后 Mixin 才真正开始工作。

**结论**：上游那份清单是实测产物，不是随手写的。缺 `java.base/java.net`
会让 MixinBooter 自我注入失败，进而整个启动死在任何 mixin 应用之前。

### 坑 9：`src\util` 不能打进 jar

**现象**：编译报 `cannot find symbol: FileUtils.forceMkdirParent` 等。

**根因**：那 4 个文件是上游的构建期工具（`runComparisonTool`），依赖
commons-io 2.15 / lang3 3.14 的新 API，且用了 class 61 的语法。

**结论**：构建时排除 `src\util`。上游也是把它当独立 sourceSet。

### 坑 10：1.10.2 的 Forge 有两个 Java 版本门

**现象**：先是弹 `GuiJava8Error` 画面，绕过之后又崩在
`ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1`。

**根因**：
- `Loader.checkJavaCompatibility()` 收集所有 `getClassVersion() >= 52` 的模组，
  非空就抛 `Java8VersionException`。本移植是 class 61，必中。
- `Loader` 构造器解析 `java.version` 时按 `1.8.0_xxx` 假设取 `split(".")[1]`，
  Java 25 的 `"25"` 分开后只有 1 个元素，直接越界。

**结论**：在 `ForgePatchTransformer` 里处理。
- `checkJavaCompatibility` 开头插入 `RETURN`（不能清空方法，见坑 11）
- 构造器里 `java.version` 解析整段替换成常量 8

### 坑 11：清空指令会让 LocalVariableTable 失效

**现象**：`ClassFormatError: Invalid index 3 in LocalVariableTable in class file Loader`。

**根因**：`instructions.clear()` 之后原有的 LocalVariableTable 偏移全部悬空。

**结论**：改成在方法开头 `insert(new InsnNode(RETURN))`，让后续代码不可达。
不要清空指令表。

### 坑 12：ASM 位置是整件事最诡异的一处

**现象**。同一份 mixin 配置，ASM 在类路径不同位置时结果完全不同：

```
ASM 在 mod jar 之前 ([59] vs [64])  -> Compatibility level set to JAVA_17  正常
ASM 只在 harness-post 里 ([65])     -> Compatibility level set to JAVA_8   失败
```

失败时报：

```
The requested compatibility level JAVA_17 could not be set.
Level is not supported by the active JRE or ASM version
(Java 25.0, ASM 9.0 (ASM10_EXPERIMENTAL))
```

**已排除的可能**：
- `asm-all-5.0.3` 已从类路径移除（确认不在）
- MixinBooter 不自带 `org/objectweb/asm`（确认 0 个条目）
- 严格模式下 harness-pre 是唯一的 ASM 提供者，里面是 9.8

**未查清的部分**：那句 `ASM 9.0` 里的版本号到底从哪读出来的，没有定位到。
它不可能是从 9.8 的 jar 读的，怀疑是 Mixin 内部的固定文案，也可能是
`CompatibilityLevel.MAX_SUPPORTED` 的编译期常量（日志里另一处说
`maximum level supported by this version of mixin (JAVA_13)`，但枚举里
明明有 JAVA_17 到 JAVA_25，所以更像是 MixinBooter 打包的 Mixin 0.8.7
在编译时把上限钉死在某个值）。

**当前结论（经验性，非解释性）**：ASM 必须同时满足
1. 在类路径上，供应 RFB 插件阶段使用
2. 位置在 mod jar **之前**

实测 `[59] 独立 asm-*.jar` + `[65] harness-post` 这个组合能跑通。
把独立 asm jar 去掉就失败。所以最终产物里 ASM 既在 harness-pre，
启动脚本又保留 `run\asm-*.jar`（见坑 13 的反面：这两者不能互相替代）。

### 坑 13：重定位 ASM 会破坏 RFB 接口契约

**现象**：`NoSuchMethodError: 'shadow.asm.tree.ClassNode ...ClassNodeHandle.getNode()'`

**根因**：RFB 的公开 API 直接把 ASM 类型写在方法签名里：

```
RfbClassTransformer.transformClass(..., ClassNodeHandle handle)
ClassNodeHandle.getNode() -> org.objectweb.asm.tree.ClassNode
```

本移植实现这个接口，方法签名必须逐字一致。把 ASM 重定位到
`me.eigenraven.lwjgl3ify.shadow.asm`，签名就变成 `shadow.asm.tree.ClassNode`，
与 RFB 期望的 `org.objectweb.asm.tree.ClassNode` 不匹配。

**结论**：ASM 绝对不能重定位。

### 坑 14：重定位 commons-compress 会破坏 Pack200

**现象**：`NoClassDefFoundError: me/eigenraven/lwjgl3ify/shadow/commons/compress/java/util/jar/Pack200`，
而该类明明在 jar 里。

**根因**：commons-compress 的 Pack200 替代实现放在
`org.apache.commons.compress.java.util.jar.Pack200`，重定位后路径里含
`java/util/jar`，游戏类加载器拒绝定义。

**结论**：commons-compress 也不能重定位。

### 坑 15：commons-lang3 重定位后静态初始化拿不到版本

**现象**：`NullPointerException: SystemUtils.JAVA_SPECIFICATION_VERSION_AS_ENUM is null`

**根因**：commons-lang3 通过资源文件路径推断自身版本，路径被重定位后读不到。

**结论**：同上，不要重定位 commons。

### 坑 16：Forge 自己要吃游戏自带的 commons

**现象**：把游戏自带的 commons 从类路径移除后，
`NoClassDefFoundError: org/apache/commons/io/IOUtils`，
堆栈在 `net.minecraftforge.common.config.Configuration`。

**根因**：Forge 1.10.2 自己调用 `IOUtils`。游戏自带的 commons 是给 Forge 用的。

**结论**：游戏自带的 commons 必须保留。本移植用自己的副本时只能靠
位置或被重定位来隔离，不能删掉游戏那份。

### 坑 17：RFB 是 multi-release jar，合并时不能丢 `META-INF/versions/`

**现象**：`IllegalStateException: Java newer than 8 is used, while the
URLClassLoaderBase for Java 8 was loaded by jdk.internal.loader.ClassLoaders$AppClassLoader`

**根因**：合并 jar 时把 `META-INF/` 整个排除了，连带 `META-INF/versions/9/`
里的 Java 9+ 专用实现一起丢掉。且合并后的 manifest 没有声明 `Multi-Release: true`。

**结论**：合并 RFB 时要保留 `META-INF/versions/`，且 manifest 必须写
`Multi-Release: true`。只影响 RFB 这一个 jar。

### 坑 18：1.10.2 的 `ClassInheritanceMultiMap` 有并发缺陷，Java 25 上必现

**现象**：进世界后一直 `Waiting for chunk...`，画面虚空，
F3 显示 `Indirect OFF`。日志里：

```
java.util.ConcurrentModificationException
  at java.base/java.util.HashMap$HashIterator.nextNode
  at net.minecraft.util.ClassInheritanceMultiMap.<init>
  at net.minecraft.world.chunk.Chunk.<init>
  at net.minecraft.world.chunk.storage.AnvilChunkLoader...
  at net.minecraftforge.common.chunkio.ChunkIOProvider.run
```

**根因**：`ClassInheritanceMultiMap.ALL_KNOWN` 是静态 `HashSet`。
构造器里遍历它，`createLookup()` 里往它加元素。Chunk 构建跑在 Forge 的
Chunk I/O 线程池上，多线程同时进入构造器就撞车。Java 8 窗口小没撞上，
Java 25 迭代器检查更严，冷启动必现。

**修复**（已实现，`ForgePatchTransformer.tfClassInheritanceMultiMap`）：
把 `<clinit>` 里的 `Sets.newHashSet()` 换成
`ConcurrentHashMap.newKeySet()`。返回类型仍是 `java.util.Set`，
字段描述符不变，不破坏泛型签名。这正是原版后期版本对该类的做法。

**验证**：修复后 `java.util.ConcurrentModificationException` 实际抛出次数为 0，
`Exception in thread` 为 0，区块正常加载，地形可见。

### 坑 19：Mixin 兼容级别与 MixinBooter 的冲突

**现象**：
```
Compatibility level set to JAVA_8
Compatibility level JAVA_17 specified by mixins.lwjgl3ify.early.json
  is higher than the maximum level supported by this version of mixin (JAVA_13)
WARN: The requested compatibility level JAVA_17 is higher than the level supported
  by the active subsystem 'MixinBooter' which supports JAVA_8.
```

**注意**：这条 **WARN 出现时程序仍能继续**，最终 `Compatibility level set to JAVA_17`
成功。不要被这条警告误导成失败。

**根因**：Mixin 的兼容级别是全局单例。MixinBooter 的
`mixin.mixinbooter.init.json` 声明 `JAVA_8` 并先被处理。

**结论**：目前是"报警告但能跑"。要彻底干净，需要把 mixin 类降到 class 52
（即回到 Jabel，但本机跑不通，见坑 1）。

### 坑 20：MLP 玩家存档里的初始世界是空的，容易误判

**现象**：截图看着是虚空，以为渲染坏了。

**根因**：新建世界第一次进入时区块还没生成，`Waiting for chunk...` 是正常的。

**结论**：判断渲染是否正常，看日志里有没有
`[Protactinium] indirect terrain active: one call per layer`，
而不是看第一帧画面。

### 坑 21：`renderer inactive` 不代表坏

**现象**：F3 显示 `Indirect OFF`，日志 `renderer inactive: vanilla renderer,
indirect path not enabled yet`。

**根因**：`ProtactiniumStatus.setRendererActive(true, ...)` 只在
**第一次成功的 indirect draw 之后**才调用。没有区块可画就永远是 inactive。
那条 "not enabled yet" 是 `BuilderThreadProbeMixin` 的兜底文案，
真正的原因由 `IndirectChunkRenderer.unavailableReason()` 给出
（OptiFine / 设置里关了 / GL 能力不足）。

**结论**：这个状态值不能当天花板看。要有地形绘制才谈得上状态。

### 坑 22：本机 java25 默认走核显

**现象**：同一份 jar，Java 17 跑 600+ fps，Java 25 只有 60-80 fps。

**根因**：注册表 `UserGpuPreferences` 里
`D:\JAVA\Java17\bin\java.exe` 是 `GpuPreference=2`（高性能），
`D:\JAVA\java25\bin\java.exe` 是 `GpuPreference=0`（让 Windows 决定）。
同 jar 同代码，Java 17 拿到 RTX 5070，Java 25 拿到 AMD 610M 核显。

**结论**：与 mod 和 Java 版本都无关。设 `GpuPreference=2` 即恢复。

### 坑 23：MixinBooter jar 有两个不同构建

`D:\pcl\versions\1.10.2-Forge_12.18.3.2511-OptiFine_I5\mods\!mixinbooter-11.18.jar`
（2451781 字节，SHA256 `AC6779...`）与 maven 上同名版本（2453839 字节，
SHA256 `04AE27...`）不是同一个文件，内部实现类全部不同。

**用前者**。maven 那份在 RFB 类加载器下 `Unable to add MixinBooter into the
parent ClassLoader` 或干脆起不来。用户提供的这份才能正常 boot。

---

## 七、已知问题清单

| 编号 | 问题 | 影响 | 状态 |
|---|---|---|---|
| 1 | 两个 harness 必须进启动器库列表，不能放 `mods/` | 无法直接分发 | 未解决，需生成版本 JSON |
| 2 | `MixinStitcher` 的 `@Overwrite doStitch()` 应用失败 | STB 纹理缝合优化不可用，已改为 `required=false` 默认关闭 | 接受，非功能性 |
| 3 | Mixin 兼容级别报 JAVA_8 vs JAVA_17 警告 | 有警告但能跑 | 可接受，彻底修需 Jabel |
| 4 | `JAVA_13` 上限的来源未查清 | 目前靠类路径位置绕过 | 未查清，见坑 12 |
| 5 | 只提供 windows/x64 natives | macOS / Linux 不可用 | 已知限制 |
| 6 | 内置 relanucher 的 DarkLaf 主题被移除 | 重启器界面是原生 Swing 外观 | 接受，仅外观 |

---

## 八、下一步建议

按优先级：

1. **生成可直接导入的版本 JSON**
   把 `harness-pre` 写到 `libraries[0]`，`harness-post` 写到 `libraries[-1]`，
   中间保留游戏原有库，删掉 `net.minecraft:launchwrapper:1.12` 和
   `org.lwjgl.lwjgl:*`，加上 24 条 `--add-opens`。
   这是让普通人能用起来的唯一缺口。

2. **查清坑 12 的 `JAVA_13` 上限来源**
   如果它是 MixinBooter 打包的 Mixin 0.8.7 的编译期常量，那换个更高版本的
   MixinBooter 可能就不需要"ASM 必须在 mod 之前"这个位置约束了，
   两个 harness 也许能合并成一个，安装步骤能从两步减到一步。

3. **补全 STB 缝合 mixin 的 refmap**
   缺的是注解处理器对裸 `@Overwrite` 不生成映射。手写 `mixins.lwjgl3ify.refmap.json`
   补上 `MixinStitcher` 的 `doStitch` / `getStichSlots` 两条 SRG 映射即可，
   然后可以把 `required` 改回 `true`。

4. **验证 macOS / Linux**
   需要各平台 natives。`build-harness.ps1` 里的 `$nativeJars` 按平台加规则即可。

---

## 九、不要在哪些地方浪费时间

- **不要试图重定位 ASM**（坑 13），会破坏 RFB 接口。
- **不要试图重定位 commons-compress**（坑 14），Pack200 路径含 `java/util/jar`。
- **不要再试 Jabel**（坑 1），三个 JDK 都试过了。
- **不要删掉游戏自带的 commons**（坑 16），Forge 自己要用。
- **不要用 `instructions.clear()` 打方法补丁**（坑 11），会破坏调试表。
- **不要被 `renderer inactive` 误导**（坑 21），要看有没有实际绘制。
- **不要相信 `ASM 9.0 (ASM10_EXPERIMENTAL)` 这个版本号**（坑 12），
  它不是从实际加载的 jar 读出来的。
