# Synaxis: Connect beyond the 4th Wall

> 内部 modId 保持 `cc_virtual_coms`，存档与脚本兼容；旧显示名「CC Synaxis Virtual Coms」仅作文档历史名。

把 Minecraft 电路、CC:Tweaked 电脑和游戏外的 Python 程序,通过本地 TCP 套接字连在一起。

> **English documentation: [README.en.md](README.en.md)**

- **作者**:Axolotl114
- **版本**:0.0.1
- **MC / 加载器**:1.21.1 / NeoForge 21.1.236

### 注意：本模组大部分代码由Deepseek v4.1flash和GPT-6.1sol生成，仅作抛砖引玉，提出一种游戏外与游戏内通信的思路

## 前置模组

| 模组 | 用途 |
| --- | --- |
| Synaxis | Cimulink 电路 |
| CC:Tweaked | 电脑与外设 |
| Create / Sable / LDLib2 | Synaxis 的依赖链 |

## 方块与物品

### 虚拟串口电路板 `virtual_coms_board`

唯一添加的方块(同时是物品),创造模式物品栏「Synaxis: Connect beyond the 4th Wall」标签页中获取。

- **放置**:像 Synaxis 其他电路板一样,贴附在你点击的那个面上(`facing` 六个方向)。
- **配置界面**:**空手右键**打开(手持任何物品右键不会打开,交互会交给物品本身,因此可以正常用 Synaxis 工具枪连线)。
- **界面**:设置串口号(`COM1` 等);增删端子;切换端子类型 `real` / `boolean` / `vec3` / `quad`;切换方向 `in` / `out`;
  查看每个端子的当前值与最后写入者(`python` / `cc` / `synaxis`);开启「保持最后值」。
- **接线**:注册为 Cimulink 组件 `cc_virtual_coms:serial_board`。`in` 端子是电路输入,`out` 端子是电路输出。

### 端子方向

| 方向 | 含义 |
| --- | --- |
| `out` | 电路**读取**该端子 —— 从 Python / 电脑写入它来驱动电路 |
| `in` | 电路**驱动**该端子 —— 从 Python / 电脑读取它来观测电路 |

读写权限不受方向限制。但若某端子正被电路驱动(值列显示 `[synaxis]`),
写入的值会在下一个游戏刻被电路覆盖。

### 图传摄像头 `camera_transmitter`

把方块视角的游戏画面输出给游戏外的程序(Zoom / Discord / OBS / 浏览器等任何枚举摄像头的应用)。

- **放置与界面**:六向 `facing`,使用原版侦测器模型;空手右键打开配置界面,手持物品时交互交给物品。
- **视频输出**:分辨率(4:3 / 16:9 预设 + 自定义宽高)、帧率 15–60、彩色 / 黑白、夜视(黑白 + 夜视为热成像风格伪彩色)。
- **触发**:电路/红石控制开关、保持开启 / 保持关闭、红石模式(保持 / 切换)、阈值 0–15、Create 无线红石双频率槽;完整优先级见界面 tooltip 与 `CameraConfig` / `CameraTransmitterBlockEntity` 的 JavaDoc。
- **相机**:yaw / pitch / roll 各自可设为「世界稳定」或「跟随 sublevel」;可调局部位置偏移。方块放在 Sable 子维度上时,相机使用 `renderPose` 跟随船体的位移与旋转。
- **渲染**:光影渲染「开 / 关 / 跟随玩家」。没有生效中的光影包时走离屏渲染,不影响玩家画面;光影包生效时捕获走主渲染目标(每秒最多 10 次,并在同帧重绘玩家视角,避免画面闪烁)。
- **输出设备**:Windows 上每个摄像头绑定一个 DirectShow 设备 `Synaxis Camera 1..4`(最多 4 路,可在配置中扩展)。首次使用在游戏内运行 `/ccvc_cameras install`,会请求一次 UAC 授权(无需重启系统,外部程序重启后即可在设备列表看到)。驱动未注册/不可用时自动回退到本地 MJPEG,并在界面、日志与 `/api/cameras` 中明确显示 `driver_unavailable`:
  - 流:`http://127.0.0.1:25586/camera/<位置hex>/stream.mjpeg`
  - 快照:`http://127.0.0.1:25586/camera/<位置hex>/snapshot.jpg`
  - 状态:`http://127.0.0.1:25586/api/cameras`(摄像头列表、分辨率、帧率、设备名、状态)
  端口可用 `-Dcc_virtual_coms.camera.httpPort=<port>` 覆盖;只绑定 `127.0.0.1`。
- **offline 行为**:未开启、区块未加载或流未启动时,虚拟设备保持存在并持续输出黑底白字 `offline` 帧;方块被破坏时注销对应设备实例。
- **Synaxis 端子**:`enable`(boolean,输入)、`streaming`(boolean,输出)、`euler_angle_in` / `euler_angle_out`(vec3,`yaw, pitch, roll`;Synaxis 要求端口名唯一,因此拆成一对匹配的输入/输出)。
- **命令**:`/ccvc_cameras status|install|uninstall|cleanup`、`/ccvc_camera_debug status|pose|dump`。
- **限制**:DirectShow 设备为 x64 / RGB24,32 位外部程序不可见;光影包生效时的捕获会额外渲染一帧玩家画面(每秒最多 10 次),低配机器可降低分辨率 / 帧率。

## Python API

文件 `python/cc_virtual_coms.py`,纯标准库,Python 3.8+。

```python
from cc_virtual_coms import Coms

coms = Coms()                        # 连上 127.0.0.1:25585

coms.send("drive", 0.25)             # 打包 + 发送
coms.send("gear", True)              # 类型自动推断
coms.send("target", [1.0, 64.0, 2.0])   # vec3

port, ptype, value = coms.recv()     # 阻塞接收并解包
print(coms.get("sense"))             # 读本地缓存的最新值

for port, ptype, value in coms:      # 持续接收推送
    print(port, ptype, value)
```

`Coms` 主要方法:

| 分类 | 方法 |
| --- | --- |
| 打包 | `pack(port, value, port_type=None)` / `pack_payload(...)` |
| 发送 | `send(frame)` / `send(port, value)` / `write(...)` / `heartbeat()` |
| 接收 | `recv(timeout=None)` / `poll(timeout=0)` / `recv_all(...)` / `for ... in coms` |
| 读取 | `get(port, default=None)` / `coms[port]` / `read(port, default=None, wait=None)` |
| 状态 | `connected` / `ports` / `list_ports()` / `out_ports()` / `in_ports()` / `port_com(p)` / `port_direction(p)` / `last_error` / `close()` |

要点:

- `Coms()` 自动连接并启动后台接收线程,所以 `recv()` 阻塞时 `send()` 仍可用;不需要时用 `Coms(background=False)` + `poll()`。
- 端子类型由游戏决定,优先用上报的类型;不符时会报错,可用 `port_type=` 覆盖。
- 每次推送都带串口号,因此 `send("spark", 1.0)` 会自动发到对应板子,多板子无需手工指定。
- **只创建一条连接并复用**,不要在循环里反复 `Coms()`(会撞上连接数上限,默认 64)。

底层函数(需要自己控制 socket 时):`pack_message` / `unpack_message` / `read_message` /
`pack_port_value` / `unpack_port_value` / `infer_port_type` /
`pack_binary_port_value` / `unpack_binary_port_value`(紧凑二进制编码),
以及常量 `MAGIC` / `VERSION` / `HEADER_SIZE` / `MSG_*` / `PORT_TYPES`。
更底层的 `VirtualComsClient` 类提供 `write_port` / `pump` / `listen` / `on_data` 等接口。

### Python 相机 API

```python
from cc_virtual_coms import CameraStream, Coms

coms = Coms()                                 # 连上 127.0.0.1:25585
for camera in coms.cameras():                 # 图传摄像头列表(由模组经 TCP 0x10 推送)
    print(camera["name"], camera["status"], camera["stream_url"])
print(coms.camera_status("Synaxis Camera 1")) # streaming / offline / driver_unavailable

stream = CameraStream.from_camera(coms.cameras()[0])
stream.open()
frame = stream.read_frame()                   # 原始 JPEG bytes,超时返回 None
if frame:
    stream.save("camera_frame.jpg")           # 或 save_frames("dir", count=10)
stream.close()
```

`CameraStream` 也支持直接给 `stream_url`、`host/port + pos` 或 `name`;`show()` 在安装了
Pillow + Tk 时显示实时窗口,否则退化为写临时 JPEG 交给系统查看器(标准库主路径是 `save()`)。

## Lua API

在 CC:Tweaked 电脑上:

```lua
local coms = peripheral.find("cc_virtual_coms")   -- 别名 "virtual_coms"
```

```lua
-- 读写
local value, ptype = coms.readPort("altitude")
coms.writePort("throttle", 42.5)
coms.writePort("gear", true)
coms.writePort("target", {1.0, 2.0, 3.0})         -- vec3,也接受 {x=,y=,z=}
coms.writePort("attitude", {0.0, 0.0, 0.0, 1.0})  -- quad

-- 端口列表与连接状态
for _, p in ipairs(coms.listPorts()) do
  print(p.name, p.type, p.direction, p.value)
end
print(coms.isConnected())
```

| 分类 | 方法 |
| --- | --- |
| 核心 | `readPort(name)` / `writePort(name, value)` / `listPorts()` / `isConnected()` |
| 配置 | `getComPort()` / `setComPort(id)` / `addPort([name], type, direction)` / `removePort(name)` / `setPortType(name, type)` / `setPortDirection(name, dir)` / `clearPorts()` |
| 诊断 | `getPortInfo(name)` / `getStatus()` / `getConnectionCount()` / `getServerAddress()` / `getLastError()` / `getDirectionLegend()` |

`type` 取 `"real"` / `"boolean"` / `"vec3"` / `"quad"`,`direction` 取 `"in"` / `"out"`。

事件:某个 `in` 端子收到新值时,已连接的电脑会收到事件。

```lua
local event, port, value = os.pullEvent("cc_virtual_coms")
```

## 从源码构建

需要 JDK 21。依赖由 `libs/` 目录中的本地 jar 提供,无需访问模组仓库,也不会被打包进产物。

```bash
./gradlew build          # 产物在 build/libs/
./gradlew runClient      # 带模组的客户端
./gradlew runServer      # 服务端
```

## 致谢

本模组的显示名是 **Synaxis: Connect beyond the 4th Wall**，其中「Synaxis」是在致敬上游的 **Synaxis** 模组。

- **GUI 贴图**复用自 **Rew1nd** 的 **Synaxis** 模组（modId `synaxis`，许可 "All Rights Reserved"），**已获原作者授权**；版权归原作者，不受本模组 MIT 许可覆盖。
- **方块模型与方块贴图**（`base` / `pins` / `chip`）是以 Synaxis 原作为参考**重新绘制/重新上色**的衍生作品，与原件存在实质差异（对 `synaxis-1.4.3.jar` 内原版逐像素比对：`pins` 256/256 = **100%**（完全重画）、`base` 251/256 = **98.05%**、`chip` 1055/4096 = **25.76%**），不是原文件。
- **图传摄像头**（`camera_transmitter`）等新增方块**不复用** Synaxis 的方块模型/方块贴图：方块模型只以资源位置引用原版 `minecraft:block/observer`，不额外打包任何方块贴图。
- 基于 **Synaxis**、**CC:Tweaked** 与 **Create** 构建。
- 依赖链：**Sable** / **LDLib2** / **Create Aeronautics**。

> 第三方美术素材的完整法律声明见 [LICENSE](LICENSE)。

## 许可

MIT —— 详见 [LICENSE](LICENSE)。

注意：第三方美术素材（Synaxis GUI 贴图与由其衍生的方块贴图）**不在** MIT 许可范围内，详见 [LICENSE](LICENSE) 的「第三方美术素材」一节。

