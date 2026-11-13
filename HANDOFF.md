# CC Synaxis Virtual Coms — 交接文档

> 用途：把本模组的后续开发交接给新的 agent team。
> 本文档只包含两部分：**一、项目架构**；**二、交接提示词（可直接粘贴使用）**。
>
> 仓库：`E:\minecraft_aimods\virtual_coms` ｜ GitHub：`Axolotl114/cc_virtual_coms`（master = `d47b4cc`，47 个受控文件）

---

# 一、项目架构

## 1.1 项目定位

一个**桥接层**模组，本身不提供玩法内容。它添加一块方块，把三方数据打通：

| 通道 | 载体 | 实现 |
| --- | --- | --- |
| 游戏内电路 | Synaxis Cimulink 组件 | `synaxis/VirtualComsComponent` |
| 游戏内编程 | CC:Tweaked 外设 | `content/VirtualComsPeripheral` |
| 游戏外程序 | 本地 TCP（`127.0.0.1:25585`） | `net/VirtualComsServer` |

三者共享同一份端子表（`SerialPort` 列表），方块实体是唯一的真相源。

**元数据**

| 项 | 值 |
| --- | --- |
| modId | `cc_virtual_coms` |
| 版本 | `0.0.1` |
| MC / 加载器 | 1.21.1 / NeoForge 21.1.236 |
| Java | 21 |
| 作者 | Axolotl114 |
| 许可 | 代码 MIT；方块模型与贴图为 Synaxis 衍生品，**不受 MIT 覆盖**（见 `LICENSE`） |

**硬依赖**（`neoforge.mods.toml` 全部 `required`）：`computercraft [1.118.0,)`、`synaxis [1.3.4,)`、`sable [1.1.3,)`、`sable_schematic_api [0.2.6,)`、`ldlib2 [2.2.17,)`、`create [6.0.10,)`、`aeronautics [1.3.0,)`。

## 1.2 技术栈与构建

- 构建：**ModDevGradle 2.0.78**（`build.gradle`），`java.toolchain 21`，`base.archivesName = mod_id`
- 依赖：`implementation fileTree(dir: 'libs', include: ['*.jar'])` —— 全靠本地 `libs/`，**离线可构建**，依赖不打包进产物
- 资源：`sourceSets.main.resources { srcDir 'src/generated/resources' }`
- 模板展开：`processResources` 把 `gradle.properties` 的值注入 `neoforge.mods.toml`

```bash
./gradlew build          # 产物 build/libs/cc_virtual_coms-0.0.1.jar
./gradlew runClient      # 开发客户端（run/ 下）
./gradlew runServer
./gradlew runData
```

> ⚠️ `gradle.properties` 里写死了代理 `127.0.0.1:7897`（`systemProp.https.proxyHost` 等）。换环境必须改，否则依赖解析会卡住。
> ⚠️ 当前 `build/libs/` 还残留 `1.3.1` / `1.3.2` 两个旧 jar，发布前只留 `0.0.1`。

## 1.3 目录结构

```
├── build.gradle / settings.gradle / gradle.properties
├── LICENSE                     MIT + 第三方素材声明
├── README.md                   中文（含作者与 AI 生成说明）
├── README.en.md                英文（CurseForge 用）
├── src/main/java/com/ccvirtualcoms/    19 个类，见 1.4
├── src/main/resources/
│   ├── META-INF/neoforge.mods.toml
│   ├── logo.png / icon.png     128×128 RGBA 模组图标
│   └── assets/cc_virtual_coms/
│       ├── blockstates/virtual_coms_board.json
│       ├── models/block/virtual_coms_board/{block,item}.json   (16 element)
│       ├── models/item/virtual_coms_board.json
│       ├── textures/block/virtual_coms_board/{base,pins,chip}.png
│       ├── lang/{en_us,zh_cn}.json
│       └── cover.png, icon.png
│   └── data/cc_virtual_coms/loot_table/blocks/virtual_coms_board.json
├── python/cc_virtual_coms.py   Python API（stdlib only）
├── python/example.py           最小示例
├── libs/                      依赖 jar（不进仓库）
├── pre_mods/                  实际加载用的依赖 jar（不进仓库）
├── assets/                    工作素材：Synaxis 提取件、bbmodel、CF 描述（不进仓库）
└── tools/                     构建/校验/渲染脚本（不进仓库）
```

## 1.4 代码分层

**入口 `CCVirtualComs`**（135 行）：在构造函数里注册 4 个 DeferredRegister、3 个 mod 事件（`commonSetup` / `registerCapabilities` / `registerPayloads`）、3 个游戏事件（`ServerStarted` / `ServerStopped` / `ServerTick`）；`ServerTickEvent` 每刻调用 `VirtualComsServer.tickDevices()` 推进所有已注册设备。

**`content/` —— 游戏内容**

| 类 | 职责 |
| --- | --- |
| `CCVirtualComsRegistry` | 方块 / 物品 / BlockEntity 类型 / 创造标签页 |
| `VirtualComsBlock` | `BaseEntityBlock`，`FACING` 六向，按朝向的 `VoxelShape`；空手右键开界面，手持物品则放行给物品（保 Synaxis 工具枪可用） |
| `VirtualComsBlockEntity` **(34 KB，最大)** | 唯一真相源：端子表、NBT 持久化、客户端同步、三通道的入口 |
| `VirtualComsPeripheral` | CC 外设，17 个方法；事件名 `cc_virtual_coms` |
| `PortType` / `PortDirection` | 枚举：`real`/`boolean`/`vec3`/`quad`；`in`/`out` |
| `SerialPort` | record `(name, type, direction, saveLastValue)` |
| `PortValues` **(12 KB)** | Synaxis `SignalValue` ↔ JSON / Lua / NBT 三向转换 |

**`net/` —— 外设通信**

| 类 | 职责 |
| --- | --- |
| `Protocol` | 帧格式与编解码（见 1.6） |
| `VirtualComsServer` **(30 KB)** | TCP 监听、连接会话、后台线程、跨线程任务队列 |
| `VirtualComsDevice` | 接口，BlockEntity 实现它，服务器按 BlockPos 注册/注销 |
| `PortMessage` | record：`com / port / type / direction / value` |
| `ConfigPayload` | C2S：`(pos, comId, ports)`，`MAX_PORTS = 256`，编辑距离上限 64 格 |
| `StatusPayload` | S2C：`(pos, status, revision, listenerRunning, connectionCount)` |

**`synaxis/` —— 电路集成**

- `VirtualComsComponent`：`ComponentType`，ID `cc_virtual_coms:serial_board`。方向语义（与 CC: Synaxis Connector 对齐）：
  - `PortDirection.IN` → Cimulink **输入**口，图驱动它，值在 `step()` 中落库
  - `PortDirection.OUT` → Cimulink **输出**口，图读取它，值在 `evaluate()` 中取出
- `VirtualComsPlantPort`：`GameThreadPlantPort`，负责端口读写落到方块实体

**`client/`**

- `VirtualComsConfigScreen` **(21 KB)**：配置界面，含分页、类型/方向循环切换、实时值列
- `VirtualComsClientHooks`：打开界面的入口

## 1.5 三通道数据流

```
                  ┌──────────────────┐
   Synaxis 电路 ──▶│ VirtualComs     │──▶ Synaxis 电路
   (Component)     │   BlockEntity   │    (Component)
                   │  （端子表为真相源）│
   CC 电脑 ───────▶│                 │──▶ CC 电脑
   (Peripheral)    └────────┬────────┘    (Peripheral)
                            │
                    VirtualComsServer
                            │ TCP 127.0.0.1:25585
                    外部 Python / 任意程序
```

**方向语义（用户可见、最容易搞错）**

| 方向 | 含义 |
| --- | --- |
| `out` | 电路**读取**该端子 → 从 Python/CC 写入它来驱动电路 |
| `in` | 电路**驱动**该端子 → 从 Python/CC 读取它来观测电路 |

读写权限不受方向限制；但若端子正被电路驱动（值列显示 `[synaxis]`），外部写入会在下一游戏刻被覆盖。

## 1.6 协议（TCP）

**帧格式**（大端）：

```
+--------+---------+----------+----------+------------------+
| magic  | version | msg type | payload  |     payload      |
| 4B     | 2B      | 2B       | length   |  N B JSON UTF-8  |
| "CCVC" | uint16  | uint16   | uint32   |                  |
+--------+---------+----------+----------+------------------+
```

`HEADER_SIZE = 12`；`VERSION = 1`；`MAX_PAYLOAD_SIZE = 8 MiB`。

| 消息 | 值 | 方向 |
| --- | --- | --- |
| `MSG_DATA_PUSH` | `0x01` | 游戏 → 外部（值变化发布） |
| `MSG_DATA_WRITE` | `0x02` | 外部 → 游戏（写入值） |
| `MSG_HEARTBEAT` | `0x03` | 双向 |
| `MSG_ERROR` | `0x04` | 双向 |

**值编码**：`real` → float64，`boolean` → uint8，`vec3` → float64[3]，`quad` → float64[4]。另有可选的紧凑二进制编码（`pack_binary_port_value`）。负载恒为 UTF-8 JSON 对象。

**服务器参数**（均可用 `-D` 覆盖）：默认 `127.0.0.1:25585`；`cc_virtual_coms.host` / `.port` / `.maxClients`；默认上限 64 会话（可设 1–1024）；`MAX_PENDING_TASKS = 4096`；每会话出站积压超限丢最旧帧。

## 1.7 线程与并发模型

- 所有 socket 线程都是 **daemon**，不会阻止 JVM 退出
- socket 线程**不碰任何游戏状态**，只把任务投递到队列，由服务器线程在 `ServerTickEvent` 中消费
- 每个连接占 1 socket + 2 线程；库侧对重复创建有防护，但**正确用法是一条连接复用**

## 1.8 Python / Lua API 面

- **Python** `python/cc_virtual_coms.py`（52 KB，仅标准库，3.8+）
  - 门面 `Coms`：`pack` / `send` / `write` / `recv` / `poll` / `recv_all` / `get` / `read` / `ports` / `list_ports` / `out_ports` / `in_ports` / `port_com` / `port_direction` / `close`
  - 底层 `VirtualComsClient`：`write_port` / `pump` / `listen` / `on_data` / 后台线程
  - 自由函数：`pack_message` / `unpack_message` / `pack_port_value` / `infer_port_type` / 二进制编解码
- **Lua**：`peripheral.find("cc_virtual_coms")`（别名 `virtual_coms`），17 个方法，事件 `cc_virtual_coms`

## 1.9 可用脚本（`tools/`，不进仓库）

| 脚本 | 用途 |
| --- | --- |
| `validate_virtual_coms_assets.py` | 校验 blockstate / 模型 parent / 贴图引用全部可解析 |
| `install_virtual_coms_model.py` | 把模型与贴图装进 `src/main/resources`（幂等） |
| `render_model_preview.py` | 纯 Python 软件渲染器，把模型渲成 3D 图 |
| `make_cover.py` | 用渲染结果生成 `logo.png`(128×128 RGBA) 与 `cover.png`(1280×720) |
| `test_coms_facade.py` | 用假服务端验证 `Coms` 门面，无需启动游戏 |

## 1.10 当前状态

- ✅ 代码、资源、构建可用；`./gradlew build` 成功
- ✅ GitHub 公开仓库已推送，本地与远端同步
- ✅ `LICENSE`（MIT + 第三方素材声明）、中英文 README
- ✅ 模组图标为 RGBA，`logoFile = "logo.png"` 已配置
- ✅ 磁盘已腾出（C: 4.3 GB / E: 48.2 GB 可用）
- ❌ **CurseForge 尚未发布**：项目未创建，jar 未上传
- ✅ CF 描述已备好：`assets/cf/description.html`（WYSIWYG 用）与 `description.txt`（纯文本备用）

## 1.11 风险与待办（新团队必读）

| 优先级 | 事项 | 说明 |
| --- | --- | --- |
| 🔴 高 | **`pins.png` 与 Synaxis 原版仅差 2/256 像素（99.2% 相同）** | 已确认 `base.png` 差 98%、`chip.png` 差 26%，属实质改动；`pins.png` 只把两组橙色像素改成灰色，其余原样 |
| 🔴 高 | **Synaxis 为 All Rights Reserved，本模组打包了衍生美术** | 需取得作者 Rew1nd 书面许可并署名，或重画 |
| 🟠 中 | CF 项目创建只能网页操作，API 无创建端点 | 上传文件端点被 Cloudflare 人机验证拦截，成功率未知 |
| 🟠 中 | 多数依赖不在 CurseForge | `synaxis` 未声明任何 URL 且为 ARR；只能写进描述 |
| 🟠 中 | 模组名含 "Synaxis" | CF 不建议项目名含他人模组名 |
| 🟡 低 | 构建无自动化测试 | `test` 任务 `NO-SOURCE`，唯一协议测试是 `tools/test_coms_facade.py` |
| 🟡 低 | `gradle.properties` 写死代理 | 换环境需修改 |

**关键陷阱：不要把 `assets/synaxis/` 当作 Synaxis 原版基准。**
该目录已被覆盖过，是**改过的版本**。唯一可靠的原版是 `pre_mods/synaxis-1.4.3.jar` 内的 `assets/synaxis/textures/block/cimulink/dc/*.png`。任何"对比原版"的操作都必须从 jar 里取字节。

---

# 二、交接提示词

以下提示词可直接粘贴给新的 agent team。建议用 **A** 作为总控，按需再补 **B / C / D** 子任务。

## A. 总控提示词（交给 team lead）

```
你接手一个 Minecraft 模组项目,继续它的开发与发布。请先读完仓库里的 HANDOFF.md 再动手。

项目:CC Synaxis Virtual Coms(v0.0.1)
路径:E:\minecraft_aimods\virtual_coms
远程:https://github.com/Axolotl114/cc_virtual_coms(master 最新)
作用:一块 Synaxis Cimulink 电路板,把游戏电路、CC:Tweaked 电脑、游戏外 Python 程序
      通过本地 TCP(127.0.0.1:25585)桥接起来。三方共享同一份端子表。

技术栈:MC 1.21.1 / NeoForge 21.1.236 / Java 21 / ModDevGradle 2.0.78,
       依赖走本地 libs/,离线构建。
构建:./gradlew build   产物 build/libs/cc_virtual_coms-0.0.1.jar
校验:python tools/validate_virtual_coms_assets.py

当前目标(按优先级):
1. 解决美术素材的授权风险(最高优先级,详见 HANDOFF.md 1.11)
2. 把 0.0.1 发布到 CurseForge
3. 保持代码与构建可用,补充最低限度的自动化验证

硬性约束:
- 不要修改 gradle.properties 里写死的代理,除非确认新环境不需要
- 不要把 libs/ pre_mods/ assets/ tools/ esp32/ python 研究脚本 提交进仓库
  (.gitignore 已处理;绝不要用 git add -f 绕过)
- 不要执行 git add -A 后再推:先 git status 确认没有意外文件
- 不要把任何 token / 密钥写进文件或提交历史
- 任何"与原版对比"的贴图操作,基准必须是 pre_mods/synaxis-1.4.3.jar 内的字节,
  不能用 assets/synaxis/(已被覆盖,不是原版)
- 版本号变更要同步 gradle.properties 与文档

交付要求:每个任务给出可验证的证据(命令输出、哈希比对、截图),不要只声称完成。
先给我一份你打算怎么做的计划,再开始执行。
```

## B. 子任务提示词：美术素材授权合规

```
任务:消除本模组的第三方美术授权风险。

背景:本模组打包的方块模型与三张贴图衍生于 Synaxis 模组(作者 Rew1nd),
      该模组为 "All Rights Reserved"。原版字节只能从 pre_mods/synaxis-1.4.3.jar 取。

已测差异(与 jar 内原版对比):
  base.png   16x16   251/256 像素不同(98%)   ← 属实质改动
  pins.png   16x16     2/256 像素不同(0.8%)  ← 几乎原样,风险最高
  chip.png   64x64   1055/4096 像素不同(26%) ← 属实质改动

要求:
1. 把 pins.png 重画成与原版显著不同(不要描图,不要在原文件上改几个像素)。
   尺寸必须仍是 16x16,沿用模组自己 base.png 的配色风格,保持 PCB 观感。
2. 重画后必须做哈希+逐像素比对,证明与原版存在实质差异,并给出数字。
3. 把新贴图装回 src/main/resources/assets/cc_virtual_coms/textures/block/virtual_coms_board/
4. 重新构建,并确认方块在游戏内观感正常(渲染预览图)。
5. 在 LICENSE 与 README(中英)的 Credits 里如实更新来源说明。

验收:提供比对脚本输出 + 新渲染图 + 重新构建的 jar。
```

## C. 子任务提示词：CurseForge 发布

```
任务:把 v0.0.1 发布到 CurseForge。

已有材料:
- jar:build/libs/cc_virtual_coms-0.0.1.jar
- 图标:src/main/resources/logo.png(128x128 RGBA)
- 描述:assets/cf/description.html(CF 的 WYSIWYG 编辑器用,粘贴 HTML)
         assets/cf/description.txt(纯文本备用)
- 依赖清单与版本要求:见 HANDOFF.md 与 assets/cf/description.html

已知限制:
- CF API 没有"创建项目"端点,项目必须在网页上建:只能由人类操作
- 上传端点被 Cloudflare 人机验证拦截过,自动化成功率未知
- 多数依赖不在 CF 上(Synaxis 是 ARR 且未声明任何 URL),无法设为自动下载依赖

要求:
1. 先列出必须由人类完成的步骤清单(网页操作),逐条写清字段值。
2. 确认每个依赖在 CF 上的存在性与 slug;找不到的在描述里给出人工安装说明。
3. 文件上传:Release 类型、游戏版本 1.21.1、加载器 NeoForge。
4. 若尝试 API 自动化,先做只读连通性测试,不要把写操作和排错混在一起。
5. 发布前必须确认 B 任务的贴图合规问题已解决。

验收:给出人类操作清单 + 依赖核实结果 + 最终项目 URL。
```

## D. 子任务提示词：代码与测试维护

```
任务:提升本模组的可维护性,不改动现有对外行为。

范围:
- src/main/java/com/ccvirtualcoms/net/        协议与 TCP 服务器
- src/main/java/com/ccvirtualcoms/content/    方块实体、外设、值转换
- python/cc_virtual_coms.py                   Python 客户端
- tools/test_coms_facade.py                   现有协议测试

要求:
1. 现状:构建 test 任务为 NO-SOURCE,没有任何 Java 侧自动化测试。
   补充最低限度的测试:协议编解码往返(Protocol)、值转换(PortValues 的
   JSON/Lua/NBT 三向)、帧长度与上限防护。
2. 不要改变线缆协议版本(VERSION=1)与帧格式;不要重命名已有的
   modId、组件 ID(cc_virtual_coms:serial_board)、外设类型、事件名、
   方块/物品注册名。这些都是存档与用户脚本依赖的稳定契约。
3. 线程模型不要动:socket 线程不得直接碰游戏状态,必须经任务队列。
4. 每次改动后运行 ./gradlew build 与 python tools/test_coms_facade.py,
   两者都必须通过。

验收:新测试能覆盖上述三项且通过;给出测试运行输出。
```

---

## 附：环境说明（可能只属于原机器，新环境请自行核对）

- 该机器曾出现工作区 Windows 文件权限错误（无法列目录），已用修复脚本处理；备份与回滚脚本在 `E:\minecraft_aimods\dsh-acl-recovery\`。
- Gradle 需要写 `C:\Users\<user>\.gradle`（工作区之外）。
- Git 走 `schannel` 会报 `SEC_E_NO_CREDENTIALS`，改用 `-c http.sslBackend=openssl` + 代理可用。
- PowerShell 自带 TLS 在此环境不可用，HTTP 请求改由 Python `urllib`（走代理）完成。
