# PvpReplay 代码审查（仅审查，未改动源码）

**审查人：** 程基岩（Cheng Jiyan），引擎/技术审查
**范围：** `common/`、`capture/`、`fabric/`、`neoforge/`、`leaf/` 源码 + `README.md`
**目标：** Fabric / NeoForge / Leaf(Paper/Folia)，MC 1.20.x–1.21.x
**方法：** 逐文件阅读；对主理人提出的每个疑点都用 `文件:行号` 证据核实；并对照**权威的 ReplayStudio 源码**（`io/ReplayOutputStream.java`、`util/Utils.java`、`ReplayMetaData.java`）交叉验证了 ReplayMod 的磁盘格式。

---

## 0. 总体结论

**架构：概念上合理。** 流式写盘（堆内存恒定、落盘刷新）、`ReplayManager` 会话注册表、跨加载器无关的 `PacketCapture` 注入，以及各平台薄封装，对服务端录制来说是合理的设计。

**实现风险：致命 —— 当前状态不能上真实服务器运行。**

存在两类相互独立的"拦路虎"：

1. **产出的每个 `.mcpr` 都无法被 ReplayMod 打开**（5 处截然不同的格式缺陷 —— 主理人"确认格式是否匹配"的假设被**推翻**，实际并不匹配）。
2. **两处逻辑级正确性 bug**，会损坏/截断真实回放：
   - SHARED 模式下，*任意*玩家离开都会结束整维度的回放（而不只是镜头玩家）。
   - 在 Fabric（Yarn 映射）上，维度键永远返回 `minecraft:overworld`，导致非主世界的 DUEL 模式永远不录制，ARENA+SHARED 则把所有维度合并成一个回放。

**上线前必须修复：** C1（全部 5 个子缺陷）、C2、C3。**C0 已实施（注入提前到 login 阶段 + 缓冲刷盘保留原始时间戳）+ L1 单元测试 CI 验证通过**，但 L2 真机 ReplayMod 播放实测（尤其 NeoForge 部分修复）仍待用户侧确认。I1（并发）已由 C2 的 `putIfAbsent` 一并解决，I2（空文件）已修复。次要/加固项可随后处理。

严重级别图例：**Critical（致命）**（文件打不开 / 数据损坏）· **Important（重要）**（数据竞争 / 产出浪费）· **Minor（次要）**（死配置 / 范围漂移）· **Hardening（加固）**（健壮性、性能、可维护性）。

---

## 1. 致命问题（CRITICAL）

### C1. 产出的 `.mcpr` 不兼容 ReplayMod —— 5 处缺陷 [推翻主理人"确认格式匹配"的假设]

主理人要求"确认 `ReplayWriter` 存的是 `VarInt packetId + body`，与 ReplayMod 的 `.tmcpr`（每包 id+body）一致"。实际**并不一致**。真实格式（已对照 ReplayStudio 的 `io/ReplayOutputStream.java#doWrite` 与 `util/Utils.writeInt` 核实）为：

```
recording.tmcpr，每个数据包（一直重复到文件尾）：
  [ 4 字节 大端  时间戳 ]   // 自回放开始的毫秒数（累计值，不是 delta）
  [ 4 字节 大端  长度   ]   // 紧随其后的包数据字节长度
  [ 包数据 = VarInt packetId + payload ]   // 恰好 `长度` 字节
```

`metaData.json`（注意：驼峰，**没有下划线**）至少必须包含：
`fileFormat="MCPR"`、`fileFormatVersion=14`、`protocol`（v≥13 时必填）、`players` = **UUID 字符串数组**（`String[]`）、`selfId`、`date`、`duration`、`mcversion`、`serverName`、`generator`。

| 编号 | 缺陷 | 证据（当前代码） | 正确取值 |
|---|------|------------------|----------|
| C1.1 | 时间戳写成 **VarInt**，且**完全缺失 4 字节长度前缀** → 读取方在第 1 字节就失步 | `ReplayWriter.java:58,61-62`（`writeVarInt(out,(int)delta); out.write(encoded,...)`） | `writeIntBE(ts); writeIntBE(encoded.length); out.write(encoded)` |
| C1.2 | 元数据文件名 `meta_data.json`（带下划线） | `ReplayWriter.java:85`（`new ZipEntry("meta_data.json")`） | `metaData.json` |
| C1.3 | `fileFormat:"MCRF"` | `ReplayWriter.java:119` | `"MCPR"` |
| C1.4 | `fileFormatVersion:1` | `ReplayWriter.java:120` | `14`（`ReplayMetaData.CURRENT_FILE_FORMAT_VERSION`） |
| C1.5 | `players` 写成 `[{name,uuid}]` 对象 | `ReplayWriter.java:127-133` | `["<uuid>","<uuid>"]`（UUID 字符串数组） |

**为什么每一项都是致命的：**
- **C1.1** —— ReplayMod 读取一个固定的 4 字节 int，再读一个 4 字节 int 长度，然后读 `长度` 字节。写成 VarInt 时间戳（1–5 字节）+ 没有长度，会让读取方把包字节当成"长度"消费，立刻失步 / 撞到 EOF。**没有任何文件能打开。**
- **C1.2** —— ReplayMod 加载的是 `metaData.json`（`ReplayOutputStream.close()` 写的正是这个名字）。`meta_data.json` 永远找不到 → 元数据解析失败 → 文件被拒。
- **C1.3 / C1.4** —— `ReplayMetaData` 要求 `fileFormat="MCPR"`，且 `fileFormatVersion>=13` 时要有 `protocol`。"MCRF"/1 是一对过时的组合，与读取方不匹配。
- **C1.5** —— `ReplayMetaData.players` 的类型是 `String[]`（UUID）。Gson 无法把 `{name,uuid}` 对象强制转成 `String[]` → 元数据反序列化抛异常 → 文件被拒。

**附带说明（细化主理人对 Javadoc 的质疑）：** `ReplayWriter.java:22` 的注释*"The first frame's delta is treated by ReplayMod as the absolute start time"*（首帧 delta 被 ReplayMod 当作绝对起始时间）是误导性的。根据 `ReplayOutputStream.doWrite(time, packet)`，写入的值是**累计**的 `time`（自回放开始的毫秒数）；ReplayMod 把它加上 `meta.date` 得到绝对时刻。而当前代码还额外把累计值又改成了 **delta**（`absTs - lastTs`，第 58 行），无论哪种理解都是错的。`clock` 返回的已经是"自开始的累计毫秒数"，所以修复方式就是把这个值**直接**写成大端 int（不要做 delta 运算）。

#### C1 的修复 —— `ReplayWriter.java`

```java
// 修改前（ReplayWriter.java:56-65）
public void writePacket(long absTs, byte[] encoded) throws IOException {
    if (!started || closed || encoded == null || encoded.length == 0) return;
    long delta = (lastTs < 0) ? Math.max(0, absTs) : Math.max(0, absTs - lastTs);
    if (lastTs < 0) firstAbs = absTs;
    lastAbs = absTs;
    writeVarInt(out, (int) delta);          // 错误：VarInt，且没有长度前缀
    out.write(encoded, 0, encoded.length);
    lastTs = absTs;
    frames++;
}

// 修改后
public void writePacket(long tsMs, byte[] encoded) throws IOException {
    if (!started || closed || encoded == null || encoded.length == 0) return;
    if (lastTs < 0) firstAbs = tsMs;
    lastAbs = tsMs;
    writeIntBE(out, (int) tsMs);     // 4 字节大端时间戳（自开始的累计毫秒数）
    writeIntBE(out, encoded.length);   // 4 字节大端长度前缀（与 ReplayStudio 的 Utils.writeInt 一致）
    out.write(encoded, 0, encoded.length);
    lastTs = tsMs;
    frames++;
}

// 新增（与 ReplayStudio util/Utils.writeInt 一致 —— 大端）
private static void writeIntBE(OutputStream out, int v) throws IOException {
    out.write((v >>> 24) & 0xFF);
    out.write((v >>> 16) & 0xFF);
    out.write((v >>>  8) & 0xFF);
    out.write( v        & 0xFF);
}
```

```java
// 修改前（ReplayWriter.java:82-99 buildZip / metaJson）
ZipEntry metaEntry = new ZipEntry("meta_data.json");     // 第 85 行  -> 名字错误
...
sb.append("\"fileFormat\":\"MCRF\",");                  // 第 119 行 -> 错误
sb.append("\"fileFormatVersion\":1,");                 // 第 120 行 -> 错误
...
for (...) { sb.append("{\"name\":...,\"uuid\":...}"); } // 第 131 行 -> 形状错误

// 修改后
ZipEntry metaEntry = new ZipEntry("metaData.json");    // 驼峰，无下划线
...
sb.append("\"fileFormat\":\"MCPR\",");
sb.append("\"fileFormatVersion\":14,");                 // CURRENT_FILE_FORMAT_VERSION（请对照你的 ReplayMod 构建版本确认）
...
sb.append("\"players\":[");
for (int i = 0; i < meta.getPlayers().size(); i++) {
    if (i > 0) sb.append(',');
    sb.append(quote(meta.getPlayers().get(i).uuid));   // UUID 字符串数组，对应 ReplayMetaData.players : String[]
}
sb.append("],");
// 可选但建议：与 ReplayStudio 默认值保持一致：
sb.append("\"singleplayer\":false,");
```

> `protocol` 在 `fileFormatVersion>=13` 时**必填**；当前代码已经设置了它（`meta.setProtocol(config.getProtocol())`），保留即可。`selfId=-1` 在 schema 里是被允许的（"可不设置"）—— 改进见 M4。

---

### C0. 在 JOIN 才注入捕获 → 缺失 login / play 阶段包 [已确认 + 实施 + L1 单测 + CI 验证]

**原始风险（已确认）：** `PacketCapture.inject` 在三个加载器里原本都是从 **JOIN** 事件调用的。到那时玩家的连接已经完全进入 PLAY 状态，因此**客户端方向的 Login(play) 包**（维度注册表 + 出生点）以及配置阶段的包在注入之前就已经发出、**从未被捕获**。ReplayMod 的回放播放器需要那个首个 Login(play) / Respawn 包来初始化 `ClientWorld` 并安置镜头——这就是"修完 C1 格式仍可能播不出来 / 镜头丢失"的剩余根因。

**修复 —— 把注入时机提前到 login 阶段（缓冲 + 刷盘模式）：**
- 注入点提前到连接进 LOGIN 状态：`PacketCapture.inject` 现在从 login 事件调用——
  - Fabric：`ServerLoginConnectionEvents.INIT`（连接进入 LOGIN 状态即触发，比 JOIN 更早；Fabric API 0.102 只有 `INIT`/`QUERY_START`/`DISCONNECT`，**没有** `LOGIN_SUCCESS`）
  - Leaf：`PlayerLoginEvent`（Paper API）
  - NeoForge：`PlayerLoggedInEvent`（见下方限制——NeoForge 无更早的 login 事件）
- 注入后进入**缓冲态**：login/配置阶段的包被写入 `ConcurrentLinkedQueue<Buf>`，每个 `Buf` 带着**捕获时刻的原始 `tsMs`**（netty 事件循环线程写入，由 `synchronized` 守卫）。
- 到 **JOIN** 时调用 `PacketCapture.beginSession(key, mgr)`，由服务端主线程把缓冲队列刷入会话——关键是每个包用 `ReplayManager.writePacketAt(key, b.tsMs, b.data)` 以**原始捕获时间戳**落盘，而不是 `writePacket`（后者会按"当前时钟 − 会话起点"重算，把缓冲包全部折叠到刷盘瞬间，丢失 Login/Respawn 时序）。
- 异常连接：`discard()` 清空缓冲并置 `discarded`，之后无论 `beginSession` 还是 `write` 都不再产出任何包 → 不会产生回放文件（与 I2 的"零帧不落盘"配合）。

**验证（L1 纯单元测试 + CI，无需服务端）：** 新增 `capture/src/test/java/com/pvpreplay/capture/PacketCaptureC0Test.java`（JUnit5 + Netty `EmbeddedChannel`，零 MC 依赖），已提交 `72b60e1` 并推送。CI run `29625287872`（`Build with Gradle (all modules)` = success）触发 `:capture:test`，**2 个用例全部通过**：
1. `c0_loginPhaseBufferKeepsCaptureTimeTimestamps` —— login 阶段写 3 个包（中间各 sleep 50ms），JOIN 刷盘后解析 `.mcpr`，断言 3 帧时间戳**严格递增且跨度 > 50ms**。直接证明 `writePacketAt` 保留了各包原始采集时间戳、未被折叠到刷盘瞬间。
2. `c0_discardDropsBufferedPackets` —— 缓冲阶段 `discard()` 后不产生回放文件（0 帧），证明异常连接不污染磁盘。

> 这坐实了 C0 的**核心逻辑**（login 缓冲 / 保留原始时间戳 / discard）正确；但"回放真的能在 ReplayMod 里播放、世界渲染完整、镜头能跟随"仍需用户本机起服做 L2 真机实测（见下方 NeoForge 限制）。

**NeoForge 限制（重要，未完全修复）：** NeoForge 21.1 **没有**早于 `PlayerLoggedInEvent` 的干净 login/连接事件，因此 C0 在 NeoForge 上只是**部分修复**——login 握手包仍可能漏掉。Fabric（Yarn）与 Leaf（Paper）路径是完整的（从 `INIT` / `PlayerLoginEvent` 起注入）。此外，即便 Fabric/Leaf，若 login 握手包在 `INIT` 事件触发**之前**就已发出，仍可能漏（极端边界）。这些边界都只能通过 L2 真机 ReplayMod 播放来确认世界渲染是否完整。

> 仍要把"文件能在 ReplayMod 里打开"（C1）和"回放真的能播放"（C0 + L2 实测）当作**两个独立的检查**。

---

### C2. SHARED 模式下任意玩家离开都会结束整维度回放 [已确认 + 修复]

**现象：** 在 SHARED 模式下，一个维度的回放就是镜头玩家的流。当*非镜头*玩家离开时，镜头玩家的整段回放被关闭并写入磁盘。

**设计意图确认（用户反馈）：** 本 mod 用于鉴别外挂 / 矿透（cheat / X-ray detection），因此"维度空了就停止录制"是**正确且预期**的行为——录空维度毫无意义。C2 的缺陷**不在于"停止"本身**，而在于**触发条件错了**：当前任一玩家（含旁观者）离开都会结束录制，哪怕镜头玩家仍在场内、维度仍有人。修复后只在*镜头玩家*离开（即被录制的视角消失）时才停止，正好契合"只录有人的维度"的意图。

**证据：**
- `PvpReplayFabric.java:103-112` —— `onLeave` 算出 `key="dim_"+sanitize(dim)` 并**无条件**调用 `PacketCapture.remove(...)` + `mgr.endSession(key)`。
- `PvpReplayNeoForge.java:95-104` —— 完全一样。
- `PvpReplayLeaf.java:96-105` —— 完全一样。

主理人指出的 `onJoin` 是没问题的：`if (mgr.hasSession(key)) return;`（Fabric:91-93 / NeoForge:85 / Leaf:86）运行在 `inject` **之前**，所以只有第一个（镜头）玩家被注入。bug 纯粹在 `onLeave`。

**修复 —— 按维度跟踪镜头玩家。** 给每个平台类加一个 `Map<UUID或UUID字符串, String 会话键>`；只有当离开的玩家*就是镜头*时，才结束 SHARED 会话。用镜头玩家的 UUID 作键（而不是维度字符串），这样会话中途换维度（M3）也不会让键错配。使用 `putIfAbsent` 还能让 join 变成原子操作，顺带解决 I1 的竞态。

```java
// 新增字段（Fabric/NeoForge/Leaf）
private final java.util.Map<String,String> cameraKeyByUuid = new java.util.concurrent.ConcurrentHashMap<>();

// onJoin —— SHARED 分支（替换原来的 hasSession 守卫）
String cameraUuid = <playerUuid(player)>;
key = "dim_" + sanitize(dim);
if (cameraKeyByUuid.putIfAbsent(cameraUuid, key) != null) {
    return; // 该维度已有镜头（或本玩家已是镜头）-> 非镜头：不注入、不结束
}
mgr.startSession(key, meta);
// ... PacketCapture.inject(...) 照旧

// onLeave —— SHARED 分支
String cameraUuid = <playerUuid(player)>;
String key = cameraKeyByUuid.remove(cameraUuid); // 为 null 表示本玩家从未是镜头
if (key == null) return;                         // 非镜头离开 -> 什么都不做
PacketCapture.remove(<channelOf(player)>);
mgr.endSession(key);
// EACH 分支不变：key = "p_"+uuid; remove + endSession
```

---

### C3. Fabric 的 `dimKey()` 永远返回 `"minecraft:overworld"`（Yarn 反射断裂）[已确认 + 细化]

**现象：** 在 Fabric（Yarn 映射）上，每个玩家上报的维度都是 `minecraft:overworld`。后果正如主理人所预测：(a) `duel.dimension != minecraft:overworld` 的 DUEL 模式**永远不录制**（Fabric 上）；(b) ARENA+SHARED 把所有维度合并成一个 `dim_minecraft_overworld` 回放。

**证据 —— `PvpReplayFabric.java:151-162`：**
```java
Object level = invoke(player, "level");          // Yarn：没有 "level()" 方法 -> 抛异常
if (level == null) level = invoke(player, "getWorld");
...
Object dim = invoke(level, "dimension");         // 在 Yarn 上也会抛（Yarn 用的是 getDimensionKey()）
Object loc = invoke(dim, "location");
if (loc == null) loc = invoke(dim, "getValue");
...
return loc != null ? loc.toString() : "minecraft:overworld";
} catch (Exception e) { return "minecraft:overworld"; }
```

**对主理人诊断的细化：** 这条链抛异常的时间点**比** `invoke(level,"dimension")` **更早**。在 Yarn 上，`ServerPlayerEntity` 没有 `level()` 方法（它是 `getWorld()`），所以 `invoke(player,"level")` 抛出 `NoSuchMethodException`，被*外层* try 捕获并返回默认值。那些 Yarn 专用的回退分支（`getWorld`、`getDimensionKey`、`getValue`）是**死代码**，因为第一个反射 `invoke` 就已经让整个方法中止了。

NeoForge（`p.level().dimension().location()`，`PvpReplayNeoForge.java:152-155`）和 Leaf（`player.getWorld().getKey()`，`PvpReplayLeaf.java:141-145`）是**正确的**。

**修复 —— 让每一步反射都 null 安全，这样 Yarn 路径才真正走得到。** 把会抛异常的 `invoke`/`field` 换成返回 `null` 的 `tryInvoke`/`tryField`，并补上 Yarn 的 `getWorld()`→`getDimensionKey()`→`getValue()` 分支：

```java
private static String dimKey(Object player) {
    try {
        Object level = tryInvoke(player, "getWorld");        // Yarn
        if (level == null) level = tryInvoke(player, "level"); // Mojang
        if (level == null) level = tryField(player, "world");
        if (level == null) return "minecraft:overworld";
        Object dim = tryInvoke(level, "getDimensionKey");    // Yarn
        if (dim == null) dim = tryInvoke(level, "dimension"); // Mojang
        if (dim == null) return "minecraft:overworld";
        Object loc = tryInvoke(dim, "getValue");            // Yarn ResourceKey#getValue
        if (loc == null) loc = tryInvoke(dim, "location"); // Mojang ResourceKey#location
        if (loc == null) loc = tryField(dim, "location");
        return loc != null ? loc.toString() : "minecraft:overworld";
    } catch (Exception e) { return "minecraft:overworld"; }
}
private static Object tryInvoke(Object o, String name) {
    if (o == null) return null;
    try { Method m = o.getClass().getMethod(name); m.setAccessible(true); return m.invoke(o); }
    catch (Exception e) { return null; }
}
private static Object tryField(Object o, String name) {
    if (o == null) return null;
    try { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    catch (Exception e) { return null; }
}
```

---

## 2. 重要问题（IMPORTANT）

### I1. Folia 上并发 SHARED 加入 → 共享 writer 数据竞争 [新增]

**风险：** 在 Folia 上，join 事件可能在不同区域线程上并发触发。`onJoin` 里的 `if (hasSession(key)) return;` 守卫（Fabric:91 / NeoForge:85 / Leaf:86）与 `mgr.startSession(key, meta)`（其中做 `active.put`）**不是原子的**。两个玩家几乎同时加入同一维度时，可能都通过守卫、都调用 `startSession`（第二次返回 `false` 但被**忽略**），并且都被 `PacketCapture.inject` 注入。于是两个 Netty 事件循环线程会同时写入**同一个** `ReplayWriter`（同一个键）—— `BufferedOutputStream` 不是线程安全的，`frames++` 也不是原子的 → 字节交错/损坏。

**修复：** C2 的镜头映射改用 `putIfAbsent` 后，每个维度只会有一个镜头，所以没有任何两个通道会共享一个 writer。这一处改动同时解决 C2 和 I1。（EACH 模式下每个玩家有唯一键 → 各自的 writer → 不会共享。）

---

### I2. 注入失败时产出空的 `.mcpr` [已确认]

**证据：** 三个加载器里 `startSession` 都调用在 `PacketCapture.inject` **之前**：
- Fabric `PvpReplayFabric.java:86-94` 然后 `:100`
- NeoForge `PvpReplayNeoForge.java:80-87` 然后 `:92`
- Leaf `PvpReplayLeaf.java:81-88` 然后 `:93`

如果 `inject` 提前返回（没找到 `"encoder"` 处理器 —— `PacketCapture.java:56-60`），会话已经存在但**零包**。在 `onLeave`/`closeAll` 时，`endSession`（`ReplayManager.java:76-86`）仍然会写一个零帧的 `.mcpr`。

**修复（两层）：**
1. **先注入，注入成功后才开会话。** 让 `PacketCapture.inject` 返回 `boolean`（它在 `:64`/`catch` 处本就知道成败）；只有返回 `true` 时才调用 `mgr.startSession`。
2. **在 `ReplayWriter.close()` 里加防御性守卫**，让零帧会话绝不变成文件：
```java
@Override public void close() throws IOException {
    if (closed) return;
    closed = true;
    if (out != null) { try { out.flush(); } finally { out.close(); }
    if (frames == 0) {                       // 什么都没捕获到
        if (tmpFile != null) Files.deleteIfExists(tmpFile);
        return;                             // 不要生成空的 .mcpr
    }
    long duration = Math.max(0, lastAbs - firstAbs);
    meta.setDuration(duration);
    buildZip();
    if (tmpFile != null) Files.deleteIfExists(tmpFile);
}
```

---

## 3. 次要问题（MINOR）

### M1. 死配置 `replay.flush-interval-ms` [已确认]
`ReplayConfig.java:35,66-67` 声明了 `flushIntervalMs`；`ConfigLoader.java:48,63` 加载并保存它。但它**从未被读取**。三个清理器都硬编码了周期：Fabric `PvpReplayFabric.java:73-75`（`60,60`）、NeoForge `:108-110`（`60,60`）、Leaf `:62-64`（`1200L,1200L`）。**修复：** 要么把 `config.getFlushIntervalMs()` 接到调度周期上，要么注明它未使用并从 `README.md` 删掉（目前 `README.md:47` 声称它是"后台统计/轮询节拍"）。

### M2. 死配置 `replay.arena.only-occupied` [新增]
`ReplayConfig.java:31,54-55` 声明了 `arenaOnlyOccupied`；`ConfigLoader.java:44,59` 加载并保存它。三个加载器的 `shouldRecord(dim)` 在 ARENA 下都**无条件返回 `true`**（Fabric:114-119 / NeoForge:118-123 / Leaf:107-112），所以这个开关**没有任何效果**。**修复：** 删掉它，或真正实现它（不过基于 join 的录制本来就只为"有人的维度"开会话，所以它设计上就是多余的 —— 最安全是删除它和 `README.md` 第 43 行）。

### M3. 会话中途换维度未处理 [已确认]
没有任何加载器注册维度变更监听器；`dimKey` 只在 join 时计算一次。一个玩家传送换维度后，*同一个*通道和*同一个*会话键保留，于是传送后属于新维度的包会被追加进一份"本应为 join 时维度"的回放里 → 范围损坏（尤其 DUEL：玩家在 `duel.dimension` 加入后又离开它，仍录着错误维度；以及 SHARED：镜头回放悄悄换了世界）。**修复：** 按加载器注册维度变更事件 —— NeoForge 用 `PlayerEvent.PlayerChangedDimensionEvent`；Leaf 用 `PlayerChangedWorldEvent`（Paper API）；Fabric 没有直接的事件，可在每次 `ServerTickEvents.END_SERVER_TICK` 检查 `player.getWorld()`/`level()`（或用维度变更的 mixin/hook）。变化时：`cameraKeyByUuid.remove(uuid)` + `PacketCapture.remove(channel)` + `mgr.endSession(oldKey)`；若该维度应当录制，可选再 `startSession` 新维度。

### M4. SHARED 的 `players` 只含镜头；`selfId=-1` [已确认 + 细化]
`buildMeta` 只加了*当前*玩家（Fabric:126 / NeoForge:130 / Leaf:119）；SHARED 下用的是第一个玩家的 meta，后来的加入 `return` 提前退出，所以 `players` = `[camera]`。**细化：** `selfId=-1` *不是*硬 bug —— `ReplayMetaData` 明确说它"可不设置"。但把它设成**镜头玩家的实体 id** 能让 ReplayMod 自动跟随镜头（否则以自由飞行模式打开）。**修复（与 C1.5 合并）：** 把参与者 UUID 累积进会话的 `players` 列表（每次 SHARED 加入、在提前 return 之前就加），SHARED 下 `meta.setSelfId(镜头实体id)`，并按 C1.5 以 UUID 字符串数组形式输出。

---

## 4. 加固项（HARDENING）

### H1. 把时间戳时钟收归 `ReplayManager` [已确认 + 升级]
每个 `onJoin` 都构造 `LongSupplier clock = () -> (System.nanoTime() - injectNanos)/1_000_000L`（Fabric:98-100 / NeoForge:90-92 / Leaf:91-93）并传给 `PacketCapture.inject`；`CaptureHandler.write` 调用 `mgr.writePacket(key, clock.getAsLong(), encoded)`（`PacketCapture.java:111`）。但 `Session` 已经存了 `startNanos`（`ReplayManager.java:48,102`）。**把时钟移进 `ReplayManager.writePacket` 的好处：** (a) 单一真相源；(b) 首个包的时间戳变为 ≈0（当前有个小偏移，因为 `injectNanos` 是在 `startSession` 的 `startNanos` *之后*才取的）；(c) 防止未来任何"一个会话多 writer"的时间戳漂移。
**修复：** 去掉 `PacketCapture.inject`/`CaptureHandler` 的 `clock` 参数；在 `ReplayManager.writePacket(key, encoded)` 里算 `long tsMs = (System.nanoTime() - s.startNanos)/1_000_000L;`；并在 `startSession` 里 `meta.setDate(s.startWall)`，使 `meta.date` 与时钟原点完全对齐。

### H2. pipeline 变更不在事件循环上（Folia / Netty）[新增]
`PacketCapture.inject`/`remove` 从 join/leave 的**事件线程**调用 `pipe.addBefore`/`pipe.remove`（`PacketCapture.java:54-75`），而不是连接的 Netty 事件循环。Netty 会同步 pipeline 变更，但在 Folia 区域线程上，最稳妥的做法是在 `channel.eventLoop()` 上执行。**修复：** 把两处变更都包进 `channel.eventLoop().execute(() -> { ... });`（用 `channel.eventLoop().inEventLoop()` 守卫，避免已经在循环上时重入）。

### H3. `findEncode` 用了 `getDeclaredMethod`（找不到继承来的 `encode`）[新增]
`PacketCapture.java:78-85`：`encoder.getClass().getDeclaredMethod("encode", ...)`。`getDeclaredMethod` **只搜当前声明类**，不搜父类。`PacketEncoder` 目前重写了 `encode`，所以今天能工作，但任何"编码器不再重新声明 `encode`"的 MC 版本都会抛异常 → `inject` 对每个玩家都失败 → 彻底没有回放。**修复：** 沿类层级向上找（`Class c = encoder.getClass(); while (c != null) { try return c.getDeclaredMethod(...); } c = c.getSuperclass(); }`），或按编码器类把方法缓存进 `ConcurrentHashMap<Class,Method>`。

### H4. 每包分配 / GC 压力 [新增]
`CaptureHandler.encodePacket` 每包都分配 `Unpooled.buffer()` 和 `new byte[buf.readableBytes()]`（`PacketCapture.java:120-133`）；高包速率下这是 Netty 事件循环上持续的 GC 抖动。**修复：** 每个处理器复用 `ThreadLocal<ByteBuf>`（或一个带上限的自有 `byte[]`）；编码后释放。优先级低，但在繁忙服务器上值得做。

### H5. `closeAll` 每个会话都调一次 `storage.enforce` [新增]
`ReplayManager.closeAll`（`:94-96`）循环 `endSession`，而每个 `endSession` 都调用 `storage.enforce(...)`（`:85`）→ 在有关闭时 *N* 个活跃会话的情况下，目录被完整列出 *N* 次。**修复：** 先把所有会话关完，再统一调一次 `enforceLimits()`。

### H6. 版本敏感 API 需按 MC 构建验证 [背景]
`README.md`（`:93-94`）已经标了 `"encoder"` 处理器名的隐患。另外 `detectProtocol`/`detectMcVersion`（Fabric:196-210、NeoForge:159-173、Leaf:149-163）依赖 `SharedConstants.getCurrentVersion()` + `getProtocolVersion()`/`getName()` —— 请确认这些方法的名字在 1.20.x–1.21.x 各版本间是否成立。只要对每个目标版本做过验证，这两个风险都可接受。

---

## 5. 附录 —— 修正后的 ReplayMod 写入器（可直接套用的参考）

最小且格式正确的核心（合并 C1 + H1）。在 `ReplayWriter` / `ReplayManager` 里应用，并由 `ReplayManager.writePacket` 驱动（不要 `clock` 参数）：

```java
// ReplayManager.writePacket —— 集中计算时间戳（H1）
public void writePacket(String key, byte[] encoded) {
    Session s = active.get(key);
    if (s == null) return;
    try {
        long tsMs = (System.nanoTime() - s.startNanos) / 1_000_000L;
        s.writer.writePacket(tsMs, encoded);
        if (config.getMaxDurationMin() > 0 &&
            (System.nanoTime() - s.startNanos) / 60_000_000_000L >= config.getMaxDurationMin()) {
            endSession(key);
        }
    } catch (IOException e) { log.error("写入回放包失败 " + key, e); }
}

// ReplayWriter.writePacket —— 大端 int32 时间戳 + int32 长度 + [VarInt id+body]（C1.1）
public void writePacket(long tsMs, byte[] encoded) throws IOException {
    if (!started || closed || encoded == null || encoded.length == 0) return;
    if (lastTs < 0) firstAbs = tsMs;
    lastAbs = tsMs;
    writeIntBE(out, (int) tsMs);
    writeIntBE(out, encoded.length);
    out.write(encoded, 0, encoded.length);
    lastTs = tsMs;
    frames++;
}
private static void writeIntBE(OutputStream out, int v) throws IOException {
    out.write((v >>> 24) & 0xFF); out.write((v >>> 16) & 0xFF);
    out.write((v >>>  8) & 0xFF); out.write( v        & 0xFF);
}
```

**修复后的验证步骤：**
1. 录一段约 10 秒的回放；用**同版本**的 ReplayMod 客户端打开。必须能加载并播放。
2. 对 `recording.tmcpr` 的前 8 字节做十六进制 dump：字节 0–3 = 大端时间戳（首个包约为 0，即 `00 00 00 00` 或很小的值），字节 4–7 = 首个包字节数的大端长度。如果你看到的是 1 字节 VarInt（例如 `01 ...` 且后面没有 4 字节长度），说明编码仍然错。
3. 确认 `metaData.json`（无下划线）能解析，且 `fileFormat=="MCPR"`、`fileFormatVersion==14`、`players` 是扁平的 UUID 数组。
