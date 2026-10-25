# 字幕拼接 · Subtitle Stitch

把多张截图纵向拼成一张长图，保留台词字幕。

为影视剧截图场景设计：一集看下来截十几张，用这个 App 一次拼成一条完整字幕长图。

纯标准 Android 实现，不依赖任何 ROM 私有 API，可在任意 Android 10+ 设备运行。

## 功能

### 拼接

- 多图纵向拼接成一张长图，输出到相册 `Pictures/SubtitleStitch/`
- **首张完整保留，后续共用同一裁剪窗口**：第一张保留原样（通常含片名、人物），之后每张裁出同一位置的字幕条，避免重复页眉
- 首张也可**单独裁剪**，不影响后续图片的联动窗口
- 网格预览：竖屏 2 列、横屏 3–4 列，一屏对比多张
- 点击任意图片进入**全屏放大精细调节**
- 默认预裁保留下半 50%（字幕常见位置），手柄明显可见
- 每张可删除、重新排序
- **先预览再保存**，预览弹窗内可保存、清空已截取、关闭监听

### 截屏捕获

- 监听 MediaStore，自动收集系统新截图
- **悬浮球自带截图按钮**：点一下自动完成「隐藏悬浮球 → 抓屏 → 恢复悬浮球」，悬浮球不会出现在截图里
- 悬浮球仅在 App 外显示，不遮挡自己的界面；主界面提供手动开关
- 长按悬浮球可永久隐藏，从通知栏恢复
- 通知栏常驻操作：进入拼接 / 显示悬浮窗 / 隐藏悬浮窗
- 控制中心快捷开关（TileService）

### 外观

- 亮色 / 暗色跟随系统设置
- 启用预见式返回动画

## 技术要点

### 悬浮球截图：MediaProjection

第三方 App 无法调用系统截屏键或接收截屏广播（特权操作），但 `MediaProjection`（录屏能力）经一次用户授权后可以读取屏幕帧缓冲。它不是运行时权限，不需要 root。

实现这个方案必须处理四个 Android 14+ 的硬性约束，否则会崩溃或授权失败：

1. **前台服务类型**
   `MediaProjection` 只能由声明了 `mediaProjection` 类型的前台服务持有，否则：
   ```
   SecurityException: Media projections require a foreground service of type
   FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
   ```
   但反过来，服务启动时就无条件声明该类型，又会因为没有授权而崩溃：
   ```
   SecurityException: Starting FGS with type mediaProjection ...
   requires permissions [FOREGROUND_SERVICE_MEDIA_PROJECTION]
   any of [CAPTURE_VIDEO_OUTPUT, project_media]
   ```
   → 默认只用 `dataSync`，需要截图时先提升类型，再创建投影。

2. **服务类型会在重提升时被重置**
   系统重新投递 `onStartCommand` 时会把前台服务类型重置回清单默认值。类型一丢，系统就**连带回收投影**——表现为「切换 App 后授权失效」。
   → 每次 `onStartCommand` 都重新应用类型，投影存活期间持续保持。

3. **必须先注册回调**
   ```
   IllegalStateException: Must register a callback before starting capture
   ```
   → 创建投影后立即 `registerCallback`。

4. **一个投影只能创建一个 VirtualDisplay**
   ```
   SecurityException: Don't take multiple captures by invoking
   MediaProjection#createVirtualDisplay multiple times on the same instance.
   ```
   每张截图都新建 display 会抛异常并**撤销整个投影**，看起来就是「每截一张都要重新授权」。
   → 授权时创建**唯一一个长期驻留的 VirtualDisplay**，每次截图只从 ImageReader 取最新帧。

另外 `getMediaProjection()` 对同一 consent token **只能调用一次**，失败就要重新授权，不能重试。

### 截图性能

时间戳埋点显示 PNG 编码占了整个耗时的 92%：

```
acquire=68ms  bitmap=39ms  trim=16ms  save=1386ms  total=1509ms
```

PNG 是无损格式，需要对 350 万像素重新做 deflate 压缩。截图没有透明通道，因此改用 **JPEG quality 95**，并把流程拆成两个阶段：

- **快阶段**：取帧（悬浮球保持隐藏）
- **慢阶段**：编码 + 登记（悬浮球已恢复）

用户感知的等待只剩取帧时间。

### 捕获尺寸与黑边

- 每次授权只创建一个 `VirtualDisplay`；捕获尺寸变化时通过 `resize()` 和 `setSurface()` 更换匹配尺寸的 `ImageReader`，避免把横屏画面放进旧的竖屏表面。
- Android 14+ 使用 `onCapturedContentResize` 同步捕获尺寸，并明确请求整个屏幕；较早版本通过显示尺寸变化监听同步。切换期间等待匹配尺寸的新帧，不保存过期表面的图像。
- 截图保存、图片导入、网格/放大预览和最终拼接均保留完整原图，不再按颜色自动删除黑边。电影黑边、暗场和用户有意保留的边缘内容不会被自动裁掉。
- 仅按图像宽度移除行缓冲区的对齐填充；这与按黑色像素裁边无关。
- 字幕区域仍由手动裁剪决定：首张独立，后续图片共用裁剪窗口。已被旧版本裁掉并保存的像素无法恢复。

### 拼接引擎

`StitchEngine` 是纯 Java（仅用 `Canvas` 绘制），裁剪模型是每张图一个 `CropWindow(top, bottom)` 比例窗口。首张独立，其余共享一个窗口。核心算术与联动规则有单元测试覆盖。

## 构建

不需要 Gradle，也不需要 Python。构建脚本直接调用 Android SDK 命令行工具：

```
aapt2 compile -> aapt2 link -> javac -> d8 -> 打包 -> zipalign -> apksigner
```

### 环境要求

- JDK 17 或更高
- Android SDK：`platforms;android-36`、`build-tools;36.0.0`
- Linux / macOS 另需 `zip` 与 `unzip`

### 配置

创建 `local.properties`（已被 git 忽略）：

```properties
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
java.home=C:/Program Files/Java/jdk-21
```

也可以直接用环境变量：`ANDROID_SDK_ROOT`、`JAVA_HOME`。

### 构建

**Windows**

```bat
build.bat
build.bat --release
build.bat --install
build.bat --out D:\build\stitch
```

**Linux / macOS**

```bash
chmod +x build.sh
./build.sh
./build.sh --release
./build.sh --install
./build.sh --out /tmp/stitch
```

首次构建会自动生成 `build/debug.keystore`，因此克隆后可直接构建。

> **Windows 注意**：个别目录下 aapt2 无法创建中间文件（报 `failed to open file`，即使普通写入正常）。如果遇到，用 `--out` 指定其他输出目录。

### 正式签名

用 PKCS#12 密钥库（apksigner 读不了 PEM 私钥）：

```properties
keystore.path=C:/keys/release.p12
keystore.pass=yourpassword
keystore.alias=release
```

或使用环境变量：`STITCH_KEYSTORE`、`STITCH_KS_PASS`、`STITCH_KS_ALIAS`。

> 密钥库和 `local.properties` 都不应提交到仓库（已在 `.gitignore` 中排除）。

## 测试

```bash
test.bat        # Windows
./test.sh       # Linux / macOS
```

纯桌面 JVM 单元测试，不需要设备，覆盖：

- 裁剪边界与归一化（含退化窗口）
- `srcTop`/`srcBottom` 像素算术
- 首张完整 / 后续联动的规则
- 联动副本的独立性
- 删除首张后的重新链接

当前状态：**25 项全部通过**。

真机捕获回归还覆盖了同一次授权下的「竖屏 → 横屏 → 竖屏」：三次原始帧、JPEG 和最终渲染尺寸均与显示尺寸一致，测试图案中的有效黑边保留，且始终复用同一个 `VirtualDisplay`。旋转过渡期间会等待合成器稳定后再取帧。

运行测试只需 `android.jar` 作编译期 classpath，测试代码运行时不触碰任何 Android 类。

连接已开启 USB 调试的 Android 设备后，还可以在 PowerShell 中运行真实图像回归测试：

```powershell
./test-device.ps1 -Sdk $env:ANDROID_SDK_ROOT -JavaHome $env:JAVA_HOME
# 多设备时加 -Serial <设备序列号>；可用 -OutDir 指定本地测试输出目录。
```

该测试无需安装 APK，通过 `app_process` 执行真实 Android `Bitmap` 和拼接引擎，验证完整黑边、暗场、手动裁剪、首张/后续联动及缩放后的尺寸和像素。生成的设备端测试图片和 dex 会在运行后清理；当前 **19 项全部通过**。

## 持续集成与发布

### CI（`.github/workflows/build.yml`）

每次 push 和 PR 自动运行：单元测试 → 构建 APK → 验证签名 → 上传构件。
产物在 Actions 页面对应的 run 里下载。

### 发布（`.github/workflows/release.yml`）

推送一个 `vX.Y.Z` 形式的 tag 即可发布：

```bash
git tag v1.2.0
git push origin v1.2.0
```

工作流会：

1. 从 tag 推导版本号
   - `versionName` = tag 去掉前缀 `v`（`v1.2.0` → `1.2.0`）
   - `versionCode` = `major*10000 + minor*100 + patch`（`1.2.0` → `10200`）
2. 运行单元测试
3. 构建 `--release` APK
4. 校验 tag 确实写入了清单（防止版本号错位）
5. 创建 GitHub Release，附带 `SubtitleStitch-<版本>.apk` 和 `.sha256` 校验文件

也可以在 Actions 页面手动触发并指定 tag。

#### 关于发布签名

默认使用**每次运行临时生成的 debug 密钥**签名，便于先跑通流程。这有两个后果：

- 每次构建签名不同，用户**无法覆盖升级**
- 不适合正式分发

要发布可长期升级的正式版本，添加三个仓库 Secrets：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | PKCS#12 密钥库的 base64 |
| `KEYSTORE_PASS` | 密钥库口令 |
| `KEYSTORE_ALIAS` | 密钥别名 |

生成并上传：

```bash
keytool -genkeypair -keystore release.p12 -storetype PKCS12 \
  -alias release -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <口令> -keypass <口令> -dname "CN=Your Name"

base64 -w0 release.p12 > release.b64     # Windows: certutil -encode release.p12 out.txt

gh secret set KEYSTORE_BASE64 < release.b64
gh secret set KEYSTORE_PASS
gh secret set KEYSTORE_ALIAS
```

配置后工作流会自动优先使用该密钥库；`release.p12` 本身不要提交到仓库。

#### 版本号也可以在本地覆盖

```bash
STITCH_VERSION_NAME=1.2.0 STITCH_VERSION_CODE=10200 ./build.sh --release
```

## 代码结构

| 文件 | 职责 |
|---|---|
| `StitchEngine.java` | 拼接渲染、裁剪窗口模型、黑边裁剪（纯 Java，可测） |
| `StitchActivity.java` | 拼接编辑界面：网格、联动裁剪、放大编辑、预览保存 |
| `MainActivity.java` | 主界面、权限申请、悬浮窗开关、授权入口 |
| `StitchApp.java` | 跟踪 Activity 生命周期，控制悬浮球显隐 |
| `ShotWatcherService.java` | MediaStore 监听、通知栏、捕获列表、前台服务类型管理 |
| `BubbleService.java` | 悬浮球、长按隐藏、两阶段截图 |
| `ScreenCapture.java` | MediaProjection 会话、单 VirtualDisplay、取帧与编码 |
| `ConsentProxyActivity.java` | 透明跳板：让悬浮球直接拉起授权对话框 |
| `ShotStore.java` | 捕获列表与设置持久化 |
| `MediaStoreUtil.java` | MediaStore 查询、保存、缓存 |
| `WatchTileService.java` | 控制中心快捷开关 |

## 权限

| 权限 | 用途 |
|---|---|
| `READ_MEDIA_IMAGES` | 读取截图（必需） |
| `SYSTEM_ALERT_WINDOW` | 悬浮球 |
| `POST_NOTIFICATIONS` | 通知栏操作 |
| `FOREGROUND_SERVICE` / `_DATA_SYNC` / `_MEDIA_PROJECTION` | 后台监听与截图 |

MediaProjection 无需在清单声明，通过系统对话框授权。

## 已知限制

- **授权在 App 进程被终止后失效**，需重新授权一次。这是 Android 的安全设计，无法绕过；日常切换 App 不受影响。
- 最低支持 Android 10（API 29）。

## 来源

本项目最初是为替代手机 ROM 侧边栏自带的一个「字幕拼接」功能而重写的。原功能 `com.realme.movieshot` 深度耦合 ROM 私有 API（审计出 145 处），且启动即因签名级权限崩溃，无法独立运行，因此按相同交互重新实现。

## 许可

[MIT](LICENSE)
