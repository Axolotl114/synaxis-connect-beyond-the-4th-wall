# 可选原生构建：DirectShow 虚拟摄像头 DLL

本文件**不是** `build.gradle`，而是给 Lead 粘贴到 `build.gradle` 的片段。按本阶段约定，我（camera-native）不直接修改 `build.gradle`。

## 目标

- `gradlew build` 在**没有 Visual Studio 的机器上仍然成功**（原生任务自动 skip 并 warning）；
- 有 VS2022 + Windows SDK 的发布机上：构建 `synaxis_cam_filter.dll`，并把它作为 `/native/synaxis_cam_filter.dll` 资源打进 mod jar；
- **DLL 产物不进 git**（本片段把产物写到 `build/` 下，`native/` 只放源码/脚本，符合"原生源码可进仓库、.dll 不进仓库"）。

## 建议追加到 build.gradle（Groovy）

```groovy
// ---------------------------------------------------------------------------
// Optional native DirectShow virtual camera filter.
//
// Only built when native/virtual_camera/build.ps1 can find Visual Studio 2022
// + the Windows SDK; otherwise the script exits 0 and the jar simply ships
// without the DLL (the MJPEG fallback remains the only output).
// ---------------------------------------------------------------------------
def nativeFilterDir = layout.projectDirectory.dir('native/virtual_camera')
def nativeFilterBuildDir = layout.buildDirectory.dir('native')
def nativeFilterStagingDir = layout.buildDirectory.dir('generated/native-resources')

def buildNativeFilter = tasks.register('buildNativeFilter', Exec) {
    group = 'build'
    description = 'Builds the optional x64 DirectShow virtual camera DLL.'
    onlyIf { org.gradle.internal.os.OperatingSystem.current().windows }
    workingDir rootDir
    commandLine 'powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
            '-File', nativeFilterDir.file('build.ps1').asFile.absolutePath
}

def stageNativeFilter = tasks.register('stageNativeFilter', Copy) {
    description = 'Stages the built native filter DLL as a mod resource.'
    dependsOn buildNativeFilter
    from nativeFilterBuildDir.map { it.dir('') } {
        include 'synaxis_cam_filter.dll'
    }
    into nativeFilterStagingDir.map { it.dir('native') }
    // A source-only build (no Visual Studio) simply stages nothing.
    onlyIf { nativeFilterBuildDir.map { it.file('synaxis_cam_filter.dll').asFile.exists() }.get() }
}

sourceSets.main.resources.srcDir(nativeFilterStagingDir)
tasks.named('processResources') { dependsOn stageNativeFilter }
```

## 说明 / 注意事项

1. **Gradle 版本**：本仓库 wrapper 是 Gradle 9.4.1（`.gradle-home/wrapper/dists/gradle-9.4.1-*`），片段使用 `layout.buildDirectory`/`layout.projectDirectory` provider 写法，避免 Gradle 9 丢弃的 `buildDir`。
2. `build.ps1` 在找不到 VS / Windows SDK / cl.exe 时**exit 0**（只打 warning），所以 `Exec` 任务不会让 `gradlew build` 失败；`stageNativeFilter` 的 `onlyIf` 保证没有 DLL 时也不做任何事。
3. 产物路径：`build/native/synaxis_cam_filter.dll`；staging 后进入 `build/generated/native-resources/native/synaxis_cam_filter.dll`，最终在 jar 里是 `/native/synaxis_cam_filter.dll`。Java 侧 `NativeLibraryLocator` 从该资源解压到 `%LOCALAPPDATA%\cc_virtual_coms\native\`（必须用稳定路径，否则注册表里的 InprocServer32 会失效）。
4. **不要把 DLL 放进 `src/main/resources/native/` 并提交**；本片段全部走 `build/` 目录，天然不会进 git。
5. `.gitignore` 需要补一行 `/.build/`（当前 `.gitignore` 只有 `build/`，不覆盖 `.build/`；`.build/native/` 是本阶段的本地产物目录）。另外 `native/virtual_camera/` 下的源码/脚本应当提交。
6. 发布 jar 里带不带 DLL 是构建机决定的：CI/发布机需要 Visual Studio；只装了 JDK 的开发机构建出的 jar 没有设备，但 MJPEG fallback 仍可用，且 `NativeLibraryLocator` 会给出清晰 reason。
7. `processResources` 已有 `expandProps` 配置，追加 `dependsOn` 不影响它。
8. 32 位支持不在 MVP 范围内（x64 DLL 只对 x64 外部程序可见）；如果以后要支持 32 位应用，需要另编 x86 DLL + 另一个 CLSID，并同样注册 Wow6432Node 视图。
