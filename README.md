# 字幕拼接 独立 App — 开发记录

> 项目位置：`E:\Develop\dsh_anything\app\`
> 产物：`app\build\SubtitleStitch.apk`
> 包名：`com.dsh.subtitlestitch`

## 一、背景与结论

原本想把手机侧边栏的「字幕拼接」系统功能（`com.realme.movieshot`）改造成可独立运行的 APK。
经完整排查，**改造原包不可行**：

| 障碍 | 证据 |
|---|---|
| 侧边栏入口在 ROM 17+ 被硬编码禁用 | `SubtitleSpliceTool.isToolAvailable()` 直接 `return false` |
| 服务启动即崩溃 | `OplusPowerManager.registerScreenStatusListener` → 需要 signature 级 `SAFE_MANAGER` 权限 |
| 修掉一个还有下一个 | `NoSuchMethodError: OplusPhoneStateListener.<init>(Looper)` —— ROM API 签名不同 |
| 系统耦合极深 | 审计出 **145 处** ROM 私有调用 |
| 原生拼接库对不上 | dex 加载 `coloros_stitch`，APK 只带 `liboplus_stitch.so` |

**因此改为重写一个行为等价的独立 App**，只依赖标准 Android API。

## 二、工具链

本机原本没有 Android SDK。已下载安装到 `E:\Develop\dsh_anything\toolchain\sdk`：

| 组件 | 版本 |
|---|---|
| commandline-tools | 136MB 完整包 |
| platforms;android-36 | android.jar (API 36) |
| build-tools;36.0.0 | d8 8.10.9 / aapt2 2.20 / apksigner 0.9 / zipalign |
| platform-tools | adb |
| JDK | `D:\Program Files\JetBrains\IntelliJ IDEA 2025.3.2\jbr` (21.0.10) |

**注意**：`sdkmanager` 需要 Java 17+，openj9-11 不满足。

## 三、构建

```powershell
cd E:\Develop\dsh_anything\app
$py = "C:\Users\Equent\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
$env:PYTHONIOENCODING="utf-8"
& $py build.py            # 构建
& $py build.py --install  # 构建并安装
& $py run_tests.py        # 跑纯逻辑测试
```

`build.py` 是纯 Python 驱动的构建脚本（不用 Gradle）：
```
aapt2 compile → aapt2 link → javac → d8 → 打包 → zipalign → apksigner → verify
```

签名密钥：`app/keystore/keystore.p12`（口令 `dshpass`，别名 `subtitlestitch`）
> apksigner 读不了 PEM 私钥，必须用 PKCS#12。

## 四、功能实现状态

| 功能 | 状态 | 说明 |
|---|---|---|
| 多图纵向拼接 | ✅ 已验证 | `StitchEngine.render()` |
| 首张完整 + 后续联动裁剪 | ✅ 已验证 | 25 项单元测试通过 |
| 默认保留下半 50% | ✅ 已验证 | 手柄不在边缘，易于发现 |
| 截屏监听（MediaStore） | ✅ 已验证 | Device 上实测捕获成功 |
| 悬浮球 | ✅ 已验证 | 可拖动、点击进拼接 |
| 长按悬浮球永久隐藏 | ✅ 已实现 | 持久化，重启后仍隐藏 |
| 通知栏恢复悬浮窗 | ✅ 已实现 | 通知含 3 个按钮 |
| 清空已捕获 | ✅ 已实现 | 二次确认，不删相册原图 |
| 失效截图自动过滤 | ✅ 已实现 | 避免"加载失败" |
| 网格布局 | ✅ 已验证 | 竖屏 2 列 / 横屏 3-4 列 |
| 点击放大精细调节 | ✅ 已实现 | 联动同步 |
| 快捷开关 Tile | ✅ 已实现 | 控制中心可添加 |
| 悬浮球内自动截图 | ⚠️ 未实测 | MediaProjection，见下 |
| 无障碍截屏 | ⏳ 预留 | 未实现 |

## 五、悬浮球自动截图（MediaProjection）

**用户提出**：通过悬浮窗自带按钮触发截图，在截图瞬间完成 隐藏→截图→恢复，解决需要手动截图的问题。

**结论：可行，且不需要高级权限。**

- `MediaProjection` 不是 runtime 权限，走系统授权对话框（一次点击）
- 流程：点圆点 → 隐藏悬浮球 → 等 180ms 让合成器移除窗口 → 抓一帧 → 恢复悬浮球 → 加入列表
- **限制**：授权在进程存活期内有效，App 重启后需重新授权（Android 安全设计，无法绕过）

已完成的适配：
- 清单加 `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- 服务声明 `foregroundServiceType="dataSync|mediaProjection"`
- `startForeground()` 显式传 type（Android 14+ 强制要求，否则抛 SecurityException）

**待设备实测**：这部分代码已编译通过，但未在真机验证过。

## 六、已知问题 / 待办

1. **悬浮球计数**：已改为从 `ShotStore` 读持久化数据，需实测确认
2. **MediaProjection 截图**：未在真机跑通
3. **adb 无法直接授权**：该 ROM 拒绝 `pm grant` / `appops set`，权限必须用户手动点
4. **`uiautomator` 无法转储通知栏**：动态界面，自动点击不可靠，需人工测试

## 七、测试

纯 JVM 单元测试（`app/test/`），不依赖设备：

```
PASSED=25  FAILED=0
```

覆盖：clamp 边界、裁剪窗口归一化、keptHeight、srcTop/srcBottom 像素算术、
首张完整/后续联动、联动副本独立性、删除首张后的重新链接。

运行：`python run_tests.py`（退出码 0 表示全通过）

## 八、关键代码位置

| 文件 | 职责 |
|---|---|
| `StitchEngine.java` | 拼接渲染 + 裁剪窗口模型（纯 Java，可测） |
| `StitchActivity.java` | 拼接编辑界面、网格、联动裁剪、放大编辑 |
| `MainActivity.java` | 主界面、权限、MediaProjection 授权 |
| `ShotWatcherService.java` | MediaStore 监听、通知栏、捕获列表 |
| `BubbleService.java` | 悬浮球、长按隐藏、自动截图触发 |
| `ScreenCapture.java` | MediaProjection 抓帧 |
| `ShotStore.java` | 捕获列表持久化 |
| `MediaStoreUtil.java` | MediaStore 查询/保存/缓存 |
| `WatchTileService.java` | 控制中心快捷开关 |
