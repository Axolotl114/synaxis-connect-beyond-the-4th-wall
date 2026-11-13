# native/virtual_camera - DirectShow 虚拟摄像头原生层

CC Synaxis Virtual Coms 的"图传摄像头"在 Windows 上通过一个 **DirectShow 视频输入设备**暴露给
外部程序（OBS / Zoom / 浏览器 / 任何能枚举摄像头的程序）。本目录只放**源码与构建脚本**；
构建产物 `synaxis_cam_filter.dll` 属于本地产物，**不进 git**（默认输出到 `.build/native/`，
发布构建时由 Gradle 打包进 jar 的 `/native/` 资源）。

Java 侧对应代码：

| Java 类 | 作用 |
| --- | --- |
| `com.ccvirtualcoms.client.camera.dshow.NativeLibraryLocator` | 从 jar 资源/开发目录定位 DLL，解压到 `%LOCALAPPDATA%\cc_virtual_coms\native\` |
| `...camera.dshow.DeviceRegistration` | 只读探测注册状态（`reg query`）、UAC 提权安装/卸载/清理（`regsvr32`） |
| `...camera.dshow.VirtualCameraTransport` | 127.0.0.1:25587 帧服务器，向每个 filter 实例推 RGB24 帧 |
| `...camera.dshow.CameraNativeClientEvents` | 客户端 tick 懒启动探测与帧桥 |
| `...camera.dshow.CameraNativeCommand` | 客户端命令 `/ccvc_cameras status|install|uninstall|cleanup` |
| `...camera.stream.*` | MJPEG HTTP fallback（driver 不可用时的唯一输出，始终可用） |

## 构建

```powershell
# 需要 VS2022（Desktop C++）+ Windows 10/11 SDK；两者缺失时脚本 exit 0 只打 warning
powershell -NoProfile -ExecutionPolicy Bypass -File native/virtual_camera/build.ps1
# 产物：
#   .build/native/synaxis_cam_filter.dll      （x64）
#   .build/native/filter_smoke_test.exe       （开发自测工具，不进发布包）
```

`build.ps1` 也负责编译 `filter_smoke_test.cpp`；这个自测工具**不写注册表**，它加载 DLL、用
模拟的 device moniker 属性包（`CameraIndex=2`）创建 filter、检查 `IBaseFilter / IPin /
IMemInputPin / IAMStreamConfig`，然后搭一个真实的 `Synaxis Camera -> Null Renderer` 滤镜图跑
2 秒。在 Java 帧服务器没启动时应能正常跑完（设备显示 "no signal"），不会卡死：

```powershell
.build\native\filter_smoke_test.exe .build\native\synaxis_cam_filter.dll
```

## 注册 / 卸载（需要一次 UAC）

```powershell
# 安装（写 HKCR\CLSID 与 VideoInputDeviceCategory 实例，需要管理员）
regsvr32 /s  "C:\Users\<you>\AppData\Local\cc_virtual_coms\native\synaxis_cam_filter.dll"
# 卸载 / 清理所有 Synaxis Camera 注册
regsvr32 /u /s "C:\Users\<you>\AppData\Local\cc_virtual_coms\native\synaxis_cam_filter.dll"
```

游戏内更推荐用客户端命令（会自动 UAC 提权，并解析退出码给出清晰原因）：

```
/ccvc_cameras status      # 驱动状态、DLL 路径、设备数、fallback URL、每个相机的状态
/ccvc_cameras install     # 请求管理员权限并注册 4 个设备
/ccvc_cameras uninstall   # 注销
/ccvc_cameras cleanup     # 清理所有残留注册
```

安装后**通常不需要重启系统**，但 OBS/Zoom/浏览器需要重启或重新打开设备列表才能重新枚举；
DLL 更新时如果旧 DLL 正被外部程序占用，需要先关闭这些程序（`NativeLibraryLocator` 会给出
"close OBS/Zoom/browser and retry" 的明确错误）。

### 注册表布局（单 CLSID + 4 个实例）

```
HKCR\CLSID\{6A1F0B7E-3C62-4C61-9E11-5A4D1A7C0001}\InprocServer32   -> DLL 路径, ThreadingModel=Both
HKCR\CLSID\{860BB310-5D01-11D0-BD3B-00A0C911CE86}\Instance\{...}
    FriendlyName = "Synaxis Camera 1" .. "Synaxis Camera 4"
    CLSID        = {6A1F0B7E-...}
    CameraIndex  = 0..3        (filter 在 IPersistPropertyBag::Load 里读回自己的槽位)
    DataPort     = 25587       (Java 帧服务器端口；MVP 固定值，改动需同时更新
                                kDefaultDataPort / VirtualCameraTransport.DEFAULT_PORT 并重新注册)
```

## 帧协议（127.0.0.1:25587，little-endian，无第三方库）

```
filter -> server  hello (16B):
  "SCMH" | version u16=1 | slot u16 | width u16 | height u16 | fps u16 | format u16=0(RGB24)

server -> filter  frame (30B header + payload):
  "SCAM" | version u16=1 | slot u16 | sequence u32 | timestampUs u64
         | width u16 | height u16 | format u16=0 | length u32
  payload = width*height*3 字节 RGB24，按行从上到下
```

- 每个 filter 实例一条 TCP 连接；`VirtualCameraTransport` 按 hello 里的尺寸把 RGBA 相机帧缩放成
  RGB24 推过去；没有相机/没有帧时持续推 "no signal" 占位帧，外部程序永远不会卡住；
- 帧服务器每 250ms 重扫 `CameraStreamRegistry`，新放的相机会自动接上，拆掉/离开世界后自动回落到
  "no signal"；
- 断线重连：filter 每 500ms 重试连接；连接上但超过 1 秒没有帧时，filter 自己也会输出 GDI 画的
  "no signal" 画面（双保险）；
- 只绑 loopback，不暴露到局域网。

## 设备与槽位

- 固定 4 个槽位（`kDeviceCount` / `CameraTransmitterBlockEntity.MAX_DEVICES`），设备名固定为
  `Synaxis Camera 1..4`；方块与服务端分配的逻辑槽位对应 `CameraIndex`；
- 未放置相机的槽位也会出现在"摄像头"列表里，显示 "no signal" 占位帧（不是黑屏/挂起）；
- 4 个实例是同一个 CLSID 的多个 device moniker，因此注册/注销次数与方块数量无关，
  不会每放一个方块就弹一次 UAC。

## 格式限制（MVP）

- 仅 **x64**；外部程序也必须是 x64（现代 OBS/Zoom/Discord/浏览器均满足；32 位应用需要另编 x86 DLL）；
- 输出格式仅 `MEDIASUBTYPE_RGB24`，`IAMStreamConfig` 提供 640x480 / 1280x720 两档分辨率、15/30fps
  共 4 个 cap；Java 侧会按协商结果缩放；
- 没有 `IAMVideoProcAmp` / `IAMCameraControl`（大多数消费端可选，OBS 不需要）；
- 未实现 Media Foundation 虚拟摄像头（Win11 的 `MFCreateVirtualCamera` 是后续可选路线）。

## 已知风险 / 排错

| 现象 | 原因 / 处理 |
| --- | --- |
| `/ccvc_cameras status` 显示 `driver_unavailable` | 没构建 DLL / 没注册 / 注册指向旧路径；按提示 `install`，或看 `NativeLibraryLocator.lastFailure()` |
| 设备列表里看不到 Synaxis Camera | 应用需要重启；`reg query` 检查实例数；Windows 相机隐私设置（Settings > Privacy > Camera）是否允许桌面应用访问 |
| UAC 被拒绝 | `DeviceRegistration` 返回 "the UAC prompt was cancelled"；fallback 的 MJPEG 仍可用 |
| DLL 更新失败 | 关闭占用 DLL 的外部程序后重试；`%LOCALAPPDATA%\cc_virtual_coms\native\` 下是稳定路径 |
| 画面卡在 no signal | 游戏进程没在跑 / 帧桥端口不一致 / 没有活跃相机；`/ccvc_cameras status` 看 `native-frame-bridge` 与相机列表 |

## 与 Java 帧服务的区别

- `VirtualCameraTransport`（本目录相关）：**RGB24 原始帧**，仅给 DirectShow filter 用，端口 25587；
- `CameraStreamService` + `MjpegHttpServer`（`...camera.stream`）：**MJPEG over HTTP**，端口 25586，
  给任何外部程序/浏览器/Python 用，driver 不可用时是唯一输出。
两者都从 `CameraStreamRegistry` 读同一份最新帧，互不阻塞。
