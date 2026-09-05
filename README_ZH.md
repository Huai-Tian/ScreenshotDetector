# 截图检测 (ScreenshotDetector)

简体中文 | [English](README.md)

---

## 📖 项目简介

**截图检测 (ScreenshotDetector)** 是一个用于检测设备截屏、录屏和屏幕共享行为的工具。

它从应用层出发，通过系统 API 对截录屏行为进行检测，与 [ScreenshotFaker](https://github.com/Huai-Tian/ScreenshotFaker) 形成攻防对照研究的视角。

**注**：本工具基于应用层检测手段，对于系统框架层的行为检测能力有限，属于辅助性检测参考。

---

## ✨ 特点

- **按键截屏检测**  
  通过 `ScreenCaptureCallback` 检测系统按键截屏事件（Android 14+）；Android 11-13 开启本应用无障碍服务后经全局按键流识别截屏键（`KEYCODE_SCREENSHOT`，仅观测不消费按键；本应用在后台时按键截屏不归本应用、不上报）

- **录屏检测**  
  通过 `ScreenRecordingCallback` 检测录屏行为

- **设备录音检测**  
  通过 `AudioManager` 检测设备级录音活动（带麦克风音轨的录屏亦被覆盖，详情含录音音源；AOSP 剥离录音者身份无法精确归因，以"最近使用 × 麦克风权限 × 前台服务启动事件"交叉推测来源应用，并动态排除系统设置/桌面/权限对话框等系统流程性应用，推测值可能不准。开启本应用无障碍服务后，实时窗口事件流作为额外候选源，推测不再依赖"使用情况访问权"。麦克风被系统静音时详情附加旁证提示）

- **录屏/投屏服务运行中检测**（需 通知使用权）  
  两个独立事实交叉判定："存在常驻（前台服务）通知的应用" × "持有虚拟显示器（`DisplayInfo.ownerPackageName` 归属）或免询问投屏持久授权（`project_media`）"。Android 14+ 强制 MediaProjection 前台服务保持常驻通知，进行中的录屏/投屏应用两事实同时成立。不做命名特征推断，卡片显示交叉命中的应用包名

- **投屏检测**  
  通过 `DisplayManager` 检测屏幕镜像或投屏状态

- **显示器归因**  
  通过反射 `DisplayInfo`（隐藏 API）读取显示器类型、标志与虚拟显示器创建者包名，将新增显示器归因到创建应用（录屏/投屏应用），卡片显示其包名。不依赖特定软件的命名特征——所有可见虚拟显示器统一上报并归因，是否为录屏由用户按包名甄别。有线外接显示器详情附加 HDMI 音频路由旁证（媒体声音正在向外接设备传输）

- **Miracast 状态检测**  
  通过 `WifiDisplayStatus`（隐藏 API）获取 Miracast 投屏连接状态与对端设备名；无线显示开关开启时详情附扫描到的可用对端（反射 `getDisplayList`）

- **MediaProjection 监听**  
  监听 `MediaProjection` 服务的运行状态

- **免询问投屏授权检测**  
  通过 AppOps 隐藏 op 字符串 `android:project_media` / `android:project_audio` 检测设备上持有免询问投屏（视频/音频）持久授权的应用（能力面信号，卡片显示完整数量与最多 5 个已授权包名）

- **媒体库监听**  
  通过 `ContentObserver` 监听媒体库变化：图片库识别新增截图文件，视频库识别录屏特征命名（screenrecord / 屏幕录制等，覆盖 AOSP 与中文 ROM 命名）的新增录屏视频；注册时回查 15 秒窗口，覆盖打开应用前刚发生的行为，卡片附写入者归因（`owner_package_name` 隐藏列，跨应用可见性依 ROM 而定可降级）

- **设备环境安全检测**  
  检测开发者选项、USB 调试、无线调试（隐藏键 `adb_wifi_enabled`）、模拟辅助显示（隐藏键 `overlay_display_devices`）、无线显示开关（隐藏键 `wifi_display_on`）、无障碍模式（`getEnabledAccessibilityServiceList` 可跨应用枚举已启用的无障碍服务，卡片显示完整数量与最多 5 个应用包名，排除本应用自身的增强服务）等风险项。无障碍状态以 200ms 轮询实时刷新（覆盖后台开关与服务集合变化，卡片随当前状态出现/更新/移除）

- **确定性/潜在风险双通道展示**  
  检测项按语义分两类：**确定性事件**（截屏、录屏、投屏、切屏、小窗等可观测事实，"检测到 = 行为发生"）展示在主异常列表；**潜在风险**（环境开关、读屏者通道、免询问授权、伪造工具特征等能力面/环境条件，"检测到 = 存在可被利用的通道"，不证明行为发生）不再混入主列表，改由顶栏权限按钮左侧的眼睛图标单独入口弹层展示——无任何潜在风险命中时该图标不出现

- **三方能力面检测**（潜在风险类）  
  检测已启用通知监听的三方应用（可读取全部通知内容）、具备截屏通道的三方应用（清单声明 `FOREGROUND_SERVICE_MEDIA_PROJECTION`，targetSdk 34+ 的录屏/投屏应用必然声明）、可绘制悬浮窗的三方应用（持有 `SYSTEM_ALERT_WINDOW` 特殊授权，AppOps `MODE_ALLOWED` 判定）。卡片显示完整数量与最多 5 个包名；仅表能力面，不代表行为正在发生

- **读屏者通道检测**  
  检测使用中的三方输入法（可读取全部按键输入）、三方自动填充服务（隐藏键 `autofill_service`，可读取全部表单内容）、三方语音交互服务、三方助手应用（`RoleManager` 查询默认助手，`QUERY_ROLE_HOLDERS` 权限——assist 通道是合法的整屏截图入口）。均仅报三方应用（预装系统服务不报防噪音），卡片显示当前服务包名

- **底座/桌面模式检测**  
  通过 `UiModeManager` 检测底座/桌面模式接入（dock 是桌面窗口模式/外接显示的强前置信号，与外接显示器卡片互补）

- **文件监听**  
  通过 `FileObserver` 监听截图/录屏目录（`Pictures/Screenshots`、`Movies/ScreenRecords`、`Movies`）文件变化；注册时回扫目录，覆盖打开应用前落盘的文件（录屏目录是先于媒体库扫描落库数秒的即时文件信号）

- **权限状态面板**  
  所有权限均为可选项，全部未授权应用也能正常运行，各项仅启用对应的检测能力，未授权项静默降级、不弹任何提示。顶栏安全等级图标实时反映状态：警告=三项常规权限（照片和视频/使用情况/应用列表）未齐，锁=常规权限已齐但两项增强服务（本应用无障碍服务/通知使用权）未全部启用，盾牌=全部就绪；点击展开详情（面板展开时 500ms 轮询）。未授权项点击获取：照片和视频为运行时权限一次弹窗合并申请（图片+视频，仅勾选"不再询问"后才跳应用详情页），使用情况/应用列表/无障碍/通知使用权为特殊访问授权跳对应设置页；应用列表项与消费路径同款调用全量枚举、按返回规模判定（ColorOS 开关拦截全量枚举而不拦截单包查询）

- **检测增强服务**  
  两项系统级增强服务，均未开启时所有检测自动退回原有路径：
  - **无障碍服务**（实时窗口归因）：`TYPE_WINDOW_STATE_CHANGED` 事件流供录音来源推测（不再依赖使用情况访问权）；`getWindows()` 跨应用窗口枚举（普通应用无任何窗口枚举路径，平台限制）将小窗归因从用量统计"推测"升级为窗口列表"事实"，并为窗口显示不完整卡片新增遮挡来源归因（"遮挡来源：包名"）；`TYPE_ACCESSIBILITY_OVERLAY` 窗口检出无障碍悬浮窗；全局按键流识别截屏键（Android 11-13，仅观测不消费按键）
  - **通知使用权**（常驻通知可见性）：为录屏/投屏服务运行中检测提供常驻通知数据，与虚拟显示器归属、投屏持久授权交叉

- **无障碍悬浮窗检测**（需 无障碍服务）  
  通过无障碍窗口快照检测 `TYPE_ACCESSIBILITY_OVERLAY` 类型窗口的存在（该窗口类型仅无障碍服务可创建——读屏软件、无障碍劫持木马的典型载体），卡片显示归属包名（排除本应用自身服务）

- **辅助检测**  
  通过 `MediaRouter` 辅助检测投屏行为

- **可疑行为检测**  
  检测切屏、分屏、画中画等可疑操作；窗口化形态（`windowingMode` 反射）不随 `multiWindow` 标志置位的 ROM 小窗盲区由反射读取补齐，反射不可用时退回 `WindowMetrics` 面积比较兜底（键盘弹出时跳过防误报）

- **悬浮窗检测**  
  通过 `FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` 触摸遮挡信号，以及"焦点被抢占但前台应用仍是自己"（`UsageStatsManager`）判定悬浮窗存在

- **自由小窗检测**  
  通过焦点归因检测自由小窗（ROM 小窗/自由窗口）：应用保持 RESUMED 而焦点与顶层应用归属其他应用——正常应用切换会先使本应用暂停。检测卡片会显示小窗内应用的包名。开启无障碍服务后归因升级为事实：跨应用窗口列表中当前持有焦点的窗口即覆盖窗口（无需使用情况访问权）

- **焦点被抢占检测**  
  检测应用处于前台但窗口焦点被其他窗口持续抢占的行为

- **窗口显示不完整检测**（Android 15+）  
  通过 `TrustedPresentationListener` 检测本应用窗口跌出可信呈现态（实际被渲染的像素比例跌破阈值）的行为——外部窗口遮挡、系统浮层、手势导航离场动画等均会触发。服务端仅在状态迁移时回调（窗口从注册首帧起就被遮挡时无迁移边沿、永不回调），故注册后超时未收到任何回调即判"启动即呈现不完整"兜底。开启无障碍服务后卡片附遮挡来源归因：与本应用顶层窗口相交的最高层外部窗口（"遮挡来源：包名"）

- **ScreenshotFaker 特征检测**  
  检测设备中是否存在 ScreenshotFaker（检测安装包 `fake.screenshot`），卡片附特征来源；旧版的 `Pictures/ScreenshotFaker` 目录检测已移除（新版 Faker 不再使用该目录）

- **更多特性持续开发中...**

---

## ⚙️ 技术细节

### 与 ScreenshotFaker 的技术对照

- **ScreenshotFaker**：
  - LSPosed 模式：工作于**系统框架层**，通过 Hook 系统 API 拦截并伪造截屏事件
  - Shizuku/Root 模式：工作于**特权层**，通过系统 API 调用实现截屏替换与绕过检测

- **ScreenshotDetector**：  
  工作于**应用层**，通过系统 API 检测截屏状态。对系统框架层（LSPosed）的行为检测能力有限，对特权层（Shizuku/Root）的行为具有一定检测能力。

### 检测原理

基于 Android 系统公开 API 实现：
- `ScreenCaptureCallback`：检测按键截屏（Android 14+）；Android 11-13 经无障碍全局按键流识别 `KEYCODE_SCREENSHOT`（仅观测不消费，本应用后台时不上报）
- `ScreenRecordingCallback`：检测录屏行为（Android 15+）
- `DisplayManager`：检测投屏状态与虚拟显示器（外接显示器检测按 `DisplayInfo.type` 仅计入有线外接，虚拟/Miracast/模拟显示器分别归入对应检测项）
- `MediaProjection`：检测屏幕投影服务状态
- `MediaRouter`：检测外部显示路由
- `ContentObserver` + `MediaStore`：检测媒体库新增截图（图片）/录屏视频（视频库按录屏特征命名匹配），查询 Bundle 携带 `MATCH_PENDING` 纳入写入瞬间的 pending 行使检出提前数秒，投影含 `owner_package_name` 隐藏列做写入者归因
- `FileObserver`：检测截图/录屏目录文件创建/移动（`Pictures/Screenshots`、`Movies/ScreenRecords`、`Movies` 多目录）
- `Settings.Global` / `AccessibilityManager`：检测 ADB、开发者选项、无障碍服务（`getEnabledAccessibilityServiceList` 无需权限、跨应用可见且为实时 Binder 查询，卡片显示已启用服务的包名；无障碍状态经 200ms 轮询前后台即时同步，该项为实时状态——服务全部停用后卡片自动移除，其余环境项保持粘性以防关闭即抹除痕迹）
- `Settings.Secure` / `RoleManager`：读屏者通道检测——三方输入法（`DEFAULT_INPUT_METHOD`）、三方自动填充（`autofill_service` 隐藏键）、三方语音交互（`voice_interaction_service`，SDK 37.1 起移出公开 stub 改硬编码值）、三方默认助手（`getRoleHolders`，SDK 37.1 起移出公开 stub 改反射，`QUERY_ROLE_HOLDERS` 权限）
- `NotificationManagerCompat`：已启用通知监听的三方应用（读取 Settings.Secure 已启用列表，与自查同源跨应用枚举）
- `PackageInfo.requestedPermissions`：具备截屏通道的三方应用（扫描清单声明 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 的原始数组而非 checkPermission——权限在旧系统未定义时 checkPermission 恒拒，清单读取跨版本稳定）
- `AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW`：可绘制悬浮窗的三方应用（`unsafeCheckOpNoThrow` 为 `MODE_ALLOWED` 即设置中显式开启；`Settings.canDrawOverlays` 仅支持自查自身无法查他者）
- `UiModeManager`：底座/桌面模式检测（`currentModeType` 非 NORMAL 即 dock 接入）
- `AudioDeviceInfo`：HDMI 音频旁证（`getDevices` 输出设备含 `TYPE_HDMI` 即媒体声音正向 HDMI 外接设备传输，附于外接显示器卡片详情）
- `AudioManager.isMicrophoneMute`：录音详情的麦克风静音旁证（录音配置存在但系统级静音生效，实际无有效采集）
- `WindowMetrics`（Android 11+）：`windowingMode` 反射不可用时的窗口化形态兜底——current 持续明显小于 maximum（面积 < 90%，连续 3 次命中）即处于分屏/小窗，键盘可见时跳过防误报
- `AudioManager.getActiveRecordingConfigurations`：检测设备级录音活动（跨应用；AOSP 对普通应用返回匿名化副本，仅含音源不含来源身份；`getOpsForPackage` 的 op 运行状态查询被 `GET_APP_OPS_STATS` 拦截，精确归因无应用层路径）。来源推测：回看窗口（5 分钟）内按最近使用排序的候选应用（`UsageEvents` 事件流 + `UsageStats` 聚合值双源，覆盖悬浮球/前台服务等无前台化路径，事件流计入前台服务启动与停止、聚合值取 `lastTimeUsed`/`lastTimeVisible` 较大者）中，先动态排除系统流程性应用（桌面/系统设置/权限对话框宿主——系统设置也可能持有麦克风权限，不排除会在从设置返回后误中），再优先取窗口内有前台服务启动事件（`FOREGROUND_SERVICE_START`，后台录音必然由前台服务承载）的候选，其余取首个具备麦克风能力的应用（跳过无麦克风能力的夹层应用）——麦克风能力 = 持有 `RECORD_AUDIO` 权限且 `record_audio` AppOps 原始模式（`unsafeCheckOpRawNoThrow`，未评估、可区分"仅前台允许"与"已拒绝"）非拒绝态
- `FLAG_WINDOW_IS_OBSCURED` 和 `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`（Android 12+）：检测悬浮窗的触摸遮挡信号
- `TrustedPresentationListener`（Android 15+）：检测本应用窗口跌出可信呈现态，上报窗口显示不完整；服务端仅在状态迁移时回调，"先遮挡再打开应用"的场景以注册后超时未回调（bootstrap 超时）兜底
- `UsageStatsManager`（需"使用情况访问权"）：查询前台应用进行窗口检测归因——焦点被抢占但顶层应用仍是自己判定为悬浮窗；应用保持 RESUMED 而焦点与顶层应用归属他人（正常应用切换会先暂停本应用）判定为自由小窗，并对 `lastTimeUsed` 做新鲜度过滤排除陈旧的顶层应用记录
- `AccessibilityService`（需开启本应用无障碍服务）：`TYPE_WINDOW_STATE_CHANGED` 事件流供录音来源推测（不依赖使用情况访问权的候选源）；全局按键流识别截屏键（`KEYCODE_SCREENSHOT`，需 `flagRequestFilterKeyEvents`，仅观测不消费）；`getWindows()` 是应用层唯一的跨应用窗口枚举路径——当前持焦点的外部窗口即小窗归因"事实"（优先于用量统计推测），与本应用顶层窗口相交的最高层外部窗口即显示不完整卡片的遮挡来源，`TYPE_ACCESSIBILITY_OVERLAY` 类型窗口即无障碍悬浮窗检出。仅读取窗口包名与窗口属性，不读取任何窗口内容
- `NotificationListenerService`（需"通知使用权"）：为录屏/投屏服务运行中检测提供数据——存在常驻通知且同时持有虚拟显示器或投屏持久授权的应用。仅读取包名与常驻标记，不读取任何通知内容

基于 Android 系统隐藏 API 实现：
- `WindowManagerGlobal.mViews`：枚举本进程窗口列表，排除自有对话框持有焦点造成的误报
- `DisplayManagerGlobal.getDisplayInfo`：读取 `DisplayInfo.type/flags/name` 与虚拟显示器创建者 `ownerPackageName`，实现显示器归因。不做录屏特征词推断（各软件命名方式不一，特征词必然漏报），统一上报"存在虚拟显示器 + 创建者包名"的事实
- `DisplayManager.getWifiDisplayStatus` 与 WFD 状态变化广播：Miracast 投屏连接状态与对端设备名（服务端明示无需权限）；`getDisplayList` 读取扫描到的可用对端
- `AppOpsManager.checkOpNoThrow` + 隐藏 op 字符串 `android:project_media` / `android:project_audio`：查询各应用投屏持久授权状态（视频/音频，`checkOperation` 服务端无越包校验，全量枚举需 `QUERY_ALL_PACKAGES`）
- `Settings.Global` 隐藏键：`adb_wifi_enabled`（无线调试）、`overlay_display_devices`（模拟辅助显示）、`wifi_display_on`（无线显示开关）

### 已知限制（实测 ColorOS 16 / Android 16）

- **录屏检测失效**：`ScreenRecordingCallback` 注册与状态语义均正常（未录屏时正确返回 `NOT_VISIBLE`，`dumpsys window` 中回调登记可见），但无论系统录屏还是三方录屏，WMS 录屏状态均不更新，状态翻转的边沿回调永不触发——系统录屏走 ROM 私有通道不经公共 MediaProjection，三方录屏的虚拟显示器也不被计入录屏状态。三方录屏仍可通过 MediaProjection 检测与媒体库/文件检测兜底覆盖
- **小窗检测失效**：系统对小窗内应用实施隐私屏蔽，不写入用量统计、不设置窗口化标志，应用层无法归因小窗与悬浮窗（焦点被抢占检测仍然有效）。开启本应用无障碍服务后归因恢复：跨应用窗口列表直接识别覆盖窗口，不依赖用量统计
- **显示器归因的边界**：`MediaProjection` 默认路径创建的虚拟显示器未设置 `VIRTUAL_DISPLAY_FLAG_PUBLIC` 时为私有显示器，对其他应用不可见（`getDisplayIds` 按 `FLAG_PRIVATE` 过滤），显示器归因仅覆盖可见（公开）显示器——录屏/投屏服务检测的虚拟显示器一臂继承此边界（投屏持久授权一臂仍可命中授权持有者）
- **录音来源为推测值**：AOSP 对普通应用的 `getActiveRecordingConfigurations` 返回匿名化副本（来源 uid/包名被剥离，`getClientUid` 等接口被 `MODIFY_AUDIO_ROUTING` 签名权限拦截，op 运行状态查询被 `GET_APP_OPS_STATS` 拦截），无法精确归因。当前推测会动态排除系统流程性应用（桌面/系统设置/权限对话框宿主）并优先采用窗口内有前台服务启动事件的候选，但后台偷录场景仍可能指向错误对象（如期间使用过其他持有麦克风权限且运行前台服务的应用）；未开启无障碍增强时依赖"使用情况访问权"
- **Miracast 检测依赖 WFD 通道**：`WifiDisplayStatus` 仅覆盖 Miracast/WFD 投屏；Google Cast 及 ROM 私有投屏协议仍依赖 MediaRouter/显示器检测
- **录屏视频落库依赖文件命名**：视频媒体库检测按录屏特征词（screenrecord / 屏幕录制等）匹配，非常规命名的录屏文件会漏报；虚拟显示器归属（进行中）与 FileObserver（落盘瞬间）双路兜底
- **无障碍按键流的前台限制**：Android 11-13 的按键截屏检测依赖无障碍全局按键流，本应用在后台时按键截屏不归本应用、不上报；Android 14+ 无此限制（`ScreenCaptureCallback` 仅本应用可见窗口被截时回调）
- **截屏通道检测的版本边界**：以清单声明 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 为判定依据，仅覆盖 targetSdk 34+ 的应用；旧 targetSdk 应用不经此权限也能启动 MediaProjection 前台服务，不在统计内
- **悬浮窗授权检测的边界**：仅统计 AppOps 为 `MODE_ALLOWED`（设置中显式开启）的应用；targetSdk < 23 的旧应用默认持有悬浮窗但 op 为 `MODE_DEFAULT`，不在统计内（现网已罕见）

---

## 🚫 非商业声明

本项目由开发者出于个人兴趣和技术研究目的发起，**非商业性质**，特此声明：

- **永久免费**：  
  本项目完全免费，**不设任何付费功能、会员制度、订阅服务或内购项目**，所有用户均可无障碍使用全部功能。

- **无赞助渠道**：  
  本项目的作者**从未开放任何形式的赞助渠道**，也**不接受任何个人或机构的资金捐赠**，以确保项目的中立性与纯粹性。

- **非盈利目的**：  
  本项目不涉及任何商业运营行为，作者不依托本项目获取任何直接或间接的经济利益。

- **技术研究导向**：  
  本项目始终定位于“**安全研究、隐私保护、软件测试**”，旨在为技术社区提供研究工具，而非商业产品。任何使用本项目的商业行为均属用户个人行为，与本项目无关。

- **禁止倒卖**：  
  本项目**严禁倒卖、转售或商业牟利**。请仅从本仓库（GitHub）及官方指定渠道获取，非官方渠道获取造成的问题，开发者概不负责。

---

## ⚖️ 免责声明

- **用途限制**：  
  本项目仅供**安全研究、软件测试和教育目的**使用。
  请勿将本项目用于任何非法用途。

- **后果自负警告**：
  本软件的检测结果**仅供安全研究参考，不能作为绝对的安全依据**。您应自行评估使用风险，开发者及贡献者**不对因依赖检测结果而导致的任何损失或后果承担责任**。

- **无担保声明**：  
  本软件根据其许可证条款提供，**不附带任何形式的明示或暗示的担保**，包括但不限于对适销性、特定用途适用性及非侵权性的担保。

- **兼容性免责**：
  本软件**不保证与所有操作系统版本、设备型号或第三方应用完全兼容**。因系统差异、应用更新或其他不可控因素导致的功能异常或损失，开发者不承担责任。

- **责任限制**：  
  在适用法律允许的最大范围内，作者及贡献者**在任何情况下均不对**因使用或无法使用本软件而导致的任何直接、间接、偶然、特殊或后果性损害承担责任，即使已被告知可能发生此类损害。

- **用户责任**：  
  使用者需自行承担因使用本项目而产生的一切法律责任。

- **最终解释**：  
  本免责声明的最终解释权归本项目作者所有。

---

## 🙏 致谢

- LSPosed（HiddenApiBypass）

---

## 💬 联系方式

- QQ: https://qm.qq.com/q/j2NM49cd8c
- Telegram: https://t.me/ScreenshotFaker

欢迎通过 GitHub Issue 提交问题、建议或反馈。

---

## ⭐ 支持项目

如果你觉得这个项目对你有帮助，或者认同它在技术研究方面的价值，欢迎点一个 ⭐ 支持一下。

你的支持能让更多人发现这个项目，也能让作者感受到持续维护的意义。感谢你的认可！