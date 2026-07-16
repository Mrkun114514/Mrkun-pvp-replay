# PvpReplay — 轻量级服务端 PvP 回放

为 Minecraft **服务端** 提供轻量化 PvP 回放能力，覆盖 **Fabric / NeoForge / LEAF（Paper 系）** 三种加载器，目标版本 **1.20.x ~ 1.21.x**。

回放产物为 **ReplayMod 兼容的 `.mcpr` 文件**，可直接用客户端 [ReplayMod](https://www.replaymod.com/) 打开观看。

---

## 设计目标：轻量、低内存

- **流式写盘，内存恒定**：抓到的每个数据包直接追加写入磁盘临时文件，堆内只保留一个 64KB 缓冲；回放时长无论多久，内存占用基本不变。
- **只在“有人的维度”录制**：ARENA 模式天然只录制在线玩家所在维度；DUEL 模式只录制指定竞技场维度，避免空维度浪费 IO。
- **磁盘/天数配额**：配置最大 GB 与最大保留天数，超出后自动删除**最旧**的回放；配额为周期性后台任务执行，几乎不占用主线程。
- **零额外模拟**：直接拦截并复用游戏自身出站数据包的编码结果，**不创建假玩家、不开新维度、不重放逻辑**，因此对 TPS 影响极小。

---

## 编译后目录结构

```
pvp-replay/
├── common/    纯 Java 核心：配置 / 存储限额 / .mcpr 写入 / 会话管理（无 MC 依赖）
├── capture/   统一抓包层：向玩家 Netty 通道注入处理器（仅依赖 netty，编译期）
├── fabric/    Fabric 模组（fabric.mod.json + ModInitializer）
├── neoforge/  NeoForge 模组（neoforge.mods.toml + @Mod）
└── leaf/      LEAF/Paper 插件（plugin.yml + JavaPlugin）
```

`common` 与 `capture` 被三端共享，平台差异仅在“事件注册 + 维度判定 + 通道获取”少量代码。

---

## 配置文件

首次运行后生成 `pvp-replay.properties`（Fabric/NeoForge 在 `config/pvp-replay/`，LEAF 在插件数据目录）。

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `replay.enabled` | `true` | 总开关 |
| `replay.mode` | `ARENA` | `ARENA` = 天坑/职业战争风格（大地图，所有人同场，录制所有在线玩家维度）；`DUEL` = 1v1 风格（小地图，单维度，仅录 `duel.dimension`） |
| `replay.perspective` | `EACH` | `EACH` = 每个玩家各自一份回放（永远连贯）；`SHARED` = 同维度只录一份（取首位加入玩家为镜头，适合 Arena） |
| `replay.duel.dimension` | `minecraft:overworld` | DUEL 模式下要录制的维度键（命名空间:路径） |
| `replay.arena.only-occupied` | `true` | ARENA 模式下仅录制有人的维度（默认开启，符合“只在有人的维度制作回放”的建议） |
| `replay.max-disk-gb` | `3` | 回放占用磁盘上限（GB），超过删除最旧回放。**默认 3GB，符合需求** |
| `replay.max-days` | `7` | 回放最大保留天数，超过删除（`0` = 不限制天数） |
| `replay.max-duration-min` | `0` | 单场回放最长分钟数（`0` = 直到触发上限） |
| `replay.flush-interval-ms` | `1000` | 后台统计/轮询节拍（不影响写盘） |
| `replay.output-dir` | `replays` | 回放输出目录（相对服务端根目录） |

> 你提到的需求「回放不超过任意设置的 GB 或任意设置的天数（默认 3GB）」已映射为 `max-disk-gb`（默认 3）与 `max-days`。

---

## 两种模式与维度

- **ARENA（天坑 / 职业战争）**：大地图、所有人同场。建议 `perspective=SHARED`，这样整场战斗只占一份回放（以某位玩家视角为镜头）。录制范围覆盖所有“当前有玩家”的维度。
- **DUEL（1v1）**：小地图、单一竞技场维度。设置 `mode=DUEL` 并指定 `duel.dimension=<你的竞技场维度>`，插件只会录制该维度的对战，其它维度完全不碰。

---

## 构建与部署

> 需要联网首次拉取 Minecraft / 加载器依赖。JDK 21。

```bash
# Fabric
./gradlew :fabric:build        # 产物 fabric/build/libs/*.jar → 放入 mods/

# NeoForge
./gradlew :neoforge:build      # 产物 neoforge/build/libs/*.jar → 放入 mods/

# LEAF / Paper / Folia
./gradlew :leaf:build          # 产物 leaf/build/libs/PvpReplay-*.jar → 放入 plugins/
```

版本切换：修改根目录 `gradle.properties` 中的 `minecraft_version` / `yarn_mappings` / `loader_version` / `fabric_api_version` / `neoforge_version` / `paper_version`。

---

## 查看回放

1. 客户端安装 ReplayMod。
2. 从服务器 `replays/` 目录取回 `.mcpr` 文件。
3. 在 ReplayMod 播放器中打开即可（视角、倍速、自由镜头均可用）。

- `EACH` 模式下，每名玩家一份回放，以该玩家第一视角呈现（`selfId` 已写入元数据）。
- `SHARED` 模式下，回放以镜头玩家视角录制，可在播放器中自由切换为旁观者视角。

---

## 已知限制 / 注意

- **协议版本**：`.mcpr` 内写入运行期探测到的协议号与 MC 版本，请用**同版本**客户端 ReplayMod 观看，跨大版本可能解码失败。
- **版本差异**：`1.20.x` 与 `1.21.x` 的出站数据包 ID 不同；代码通过反射调用游戏自带的 `encoder` 处理器编码，因此天然跟随当前版本，无需为不同小版本改代码。但若某版本 `encoder` 处理器名称或 `encode` 方法签名变更，需在 `capture/PacketCapture.java` 调整。
- **SHARED 镜头切换**：当前实现在镜头玩家退出时关闭该维度回放；后续加入的玩家会开启新的一份。如需“镜头自动交接”，可在 `onLeave` 中挑选同维度下一位在线玩家重新注入。
- **数据包量**：ARENA + EACH 会给每名玩家都生成回放文件，磁盘增长较快——配合 `max-disk-gb` / `max-days` 配额即可控。

---

## 内存与性能速览

| 项 | 行为 |
|----|------|
| 堆内存 | 仅一个 64KB 写缓冲 + 会话元数据，与回放时长无关 |
| 磁盘 | 流式追加；受 `max-disk-gb` 硬上限约束 |
| 主线程影响 | 仅“反射编码 + 写入缓冲”，纳秒级；配额清理在异步/独立线程 |
| 网络 | 不额外发包；复用既有出站数据 |
