# 字幕拼接 (Subtitle Stitch)

把多张截图纵向拼成一张长图，保留台词字幕。为影视剧截图场景设计：一集看下来截十几张，用这个 App 一次拼成一条完整字幕长图。

不依赖任何 ROM 私有 API，纯标准 Android 实现，可在任意 Android 10+ 设备运行。

## 功能

**拼接**

- 多图纵向拼接成一张长图，输出到相册 `Pictures/SubtitleStitch/`
- **首张完整保留，后续共用同一裁剪窗口**：第一张保留原样（通常含片名/人物），之后每张裁出同一位置的字幕条，避免重复页眉
- 网格预览：竖屏 2 列、横屏 3–4 列，一屏可对比多张
- 点击任意图片进入**全屏放大精细调节**，联动同步到所有后续图片
- 默认预裁保留下半 50%（字幕常见位置），手柄明显可见，不会贴在边缘
- 每张可删除、重新排序

**截屏捕获**

- 监听 MediaStore，自动收集系统新截图
- 悬浮球显示已捕获数量，点击进入拼接
- **悬浮球自带截图按钮**：点一下自动完成「隐藏悬浮球 → 抓屏 → 恢复悬浮球 → 加入列表」，悬浮球不会出现在截图里
- 长按悬浮球永久隐藏，从通知栏「显示悬浮窗」恢复
- 通知栏常驻操作：进入拼接 / 显示悬浮窗 / 隐藏悬浮窗
- 控制中心快捷开关（TileService）

## 技术要点

### 悬浮球截图：MediaProjection

第三方 App 无法调用系统截屏键或接收截屏广播（特权操作），但 `MediaProjection`（录屏能力）经一次用户授权后可以读取屏幕帧缓冲。它不是运行时权限，不需要 root。

实现这个方案必须处理三个 Android 14+ 的硬性约束，否则会崩溃或授权失败：

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
   系统重新投递 `onStartCommand` 时会把前台服务类型重置回清单默认值。类型一丢，系统就**连带回收投影**——这就是“切换 App 后授权失效”的原因。
   → 每次 `onStartCommand` 都重新应用类型，投影存活期间持续保持。

3. **必须先注册回调**
   ```
   IllegalStateException: Must register a callback before starting capture
   ```
   → 创建投影后立即 `registerCallback`。

另外 `getMediaProjection()` 对同一 consent token **只能调用一次**，失败就要重新授权，不能重试。

### 拼接引擎

`StitchEngine` 是纯 Java（仅用 `Canvas` 绘制），裁剪模型是每张图一个 `CropWindow(top, bottom)` 比例窗口。首张强制全帧，其余共享一个窗口。核心算术与联动规则有单元测试覆盖。

## 构建

不需要 Gradle。`build.py` 直接调用 Android SDK 命令行工具：

```
aapt2 compile → aapt2 link → javac → d8 → 打包 → zipalign → apksigner
```

### 环境要求

- JDK 17 或更高
- Android SDK：`platforms;android-36`、`build-tools;36.0.0`

### 配置

创建 `local.properties`（已被 git 忽略）：

```properties
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
java.home=C:/Program Files/Java/jdk-21
```

也可以用环境变量：`ANDROID_SDK_ROOT`、`JAVA_HOME`。

### 构建

```bash
python build.py              # 构建（用自动生成的 debug 密钥签名）
python build.py --release    # 标记为 release 构建
python build.py --install    # 构建并安装到已连接设备
```

首次构建会自动生成 `build/debug.keystore`，因此克隆后可直接构建。

### 正式签名

用 PKCS#12 密钥库（apksigner 读不了 PEM 私钥）：

```properties
keystore.path=C:/keys/release.p12
keystore.pass=yourpassword
keystore.alias=release
```

或命令行：`python build.py --ks-pass pass --ks-alias release`

> 密钥库和 `local.properties` 都不应提交到仓库（已在 `.gitignore` 中排除）。

## 测试

```bash
python run_tests.py
```

纯桌面 JVM 单元测试，不需要设备，覆盖：

- 裁剪边界与归一化（含退化窗口）
- `srcTop`/`srcBottom` 像素算术
- 首张完整 / 后续联动的规则
- 联动副本的独立性（改一张不影响其他）
- 删除首张后的重新链接

当前状态：**25 项全部通过**。

运行测试只需 `android.jar` 作编译期 classpath，测试代码运行时不触碰任何 Android 类。

## 代码结构

| 文件 | 职责 |
|---|---|
| `StitchEngine.java` | 拼接渲染与裁剪窗口模型（纯 Java，可测） |
| `StitchActivity.java` | 拼接编辑界面：网格、联动裁剪、放大编辑 |
| `MainActivity.java` | 主界面、权限申请、授权入口 |
| `ShotWatcherService.java` | MediaStore 监听、通知栏、捕获列表、前台服务类型管理 |
| `BubbleService.java` | 悬浮球、长按隐藏、截图触发 |
| `ScreenCapture.java` | MediaProjection 抓帧、回调与生命期管理 |
| `ConsentProxyActivity.java` | 透明跳板：让悬浮球直接拉起授权对话框 |
| `ShotStore.java` | 捕获列表持久化（SharedPreferences + JSON） |
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

- **授权在 App 进程被终止后失效**，需重新授权一次。这是 Android 的安全设计，无法绕过。日常切换 App 不受影响。
- 最低支持 Android 10（API 29）。

## 来源说明

本项目最初是为替代手机 ROM 侧边栏自带的一个「字幕拼接」功能而重写的。原功能 `com.realme.movieshot` 深度耦合 ROM 私有 API（审计出 145 处），且启动即因签名级权限崩溃，无法独立运行，因此按相同交互重新实现。

## 许可

MIT
