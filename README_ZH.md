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
  通过 `ScreenCaptureCallback` 检测系统按键截屏事件

- **录屏检测**  
  通过 `ScreenRecordingCallback` 检测录屏行为

- **设备录音检测**  
  通过 `AudioManager` 检测设备级录音活动（带麦克风音轨的录屏亦被覆盖，详情含录音音源；AOSP 剥离录音者身份无法精确归因，以"最近使用 × 麦克风权限 × 前台服务启动事件"交叉推测来源应用，并动态排除系统设置/桌面/权限对话框等系统流程性应用，推测值可能不准）

- **投屏检测**  
  通过 `DisplayManager` 检测屏幕镜像或投屏状态

- **显示器归因**  
  通过反射 `DisplayInfo`（隐藏 API）读取显示器类型、标志与虚拟显示器创建者包名，将新增显示器归因到创建应用（录屏/投屏应用），卡片显示其包名。不依赖特定软件的命名特征——所有可见虚拟显示器统一上报并归因，是否为录屏由用户按包名甄别

- **Miracast 状态检测**  
  通过 `WifiDisplayStatus`（隐藏 API）获取 Miracast 投屏连接状态与对端设备名

- **MediaProjection 监听**  
  监听 `MediaProjection` 服务的运行状态

- **免询问投屏授权检测**  
  通过 AppOps 隐藏 op 字符串 `android:project_media` 检测设备上持有免询问投屏持久授权的应用（能力面信号，卡片显示完整数量与最多 5 个已授权包名）

- **媒体库监听**  
  通过 `ContentObserver` 监听媒体库变化，识别新增的截图文件；注册时回查 15 秒窗口，覆盖打开应用前刚发生的截图

- **设备环境安全检测**  
  检测开发者选项、USB 调试、无线调试（隐藏键 `adb_wifi_enabled`）、模拟辅助显示（隐藏键 `overlay_display_devices`）、无线显示开关（隐藏键 `wifi_display_on`）、无障碍模式（`getEnabledAccessibilityServiceList` 可跨应用枚举已启用的无障碍服务，卡片显示完整数量与最多 5 个应用包名）等风险项。无障碍状态以 200ms 轮询实时刷新（覆盖后台开关与服务集合变化，卡片随当前状态出现/更新/移除）

- **文件监听**  
  通过 `FileObserver` 监听截图目录文件变化；注册时回扫目录，覆盖打开应用前落盘的截图文件

- **权限状态面板**  
  顶栏安全等级图标实时反映授权情况：警告=三项授权（照片和视频/使用情况/应用列表）未齐，锁=三项已齐但本应用无障碍服务未启用，盾牌=全部就绪；点击展开详情（面板展开时 500ms 轮询）。未授权项点击获取：照片和视频为运行时权限直接弹系统授权框（仅勾选"不再询问"后才跳应用详情页），使用情况/应用列表为特殊访问授权跳对应设置页；应用列表项与消费路径同款调用全量枚举、按返回规模判定（ColorOS 开关拦截全量枚举而不拦截单包查询）

- **辅助检测**  
  通过 `MediaRouter` 辅助检测投屏行为

- **可疑行为检测**  
  检测切屏、分屏、画中画等可疑操作

- **悬浮窗检测**  
  通过 `FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` 触摸遮挡信号，以及"焦点被抢占但前台应用仍是自己"（`UsageStatsManager`）判定悬浮窗存在

- **自由小窗检测**  
  通过焦点归因检测自由小窗（ROM 小窗/自由窗口）：应用保持 RESUMED 而焦点与顶层应用归属其他应用——正常应用切换会先使本应用暂停。检测卡片会显示小窗内应用的包名

- **焦点被抢占检测**  
  检测应用处于前台但窗口焦点被其他窗口持续抢占的行为

- **窗口显示不完整检测**（Android 15+）  
  通过 `TrustedPresentationListener` 检测本应用窗口跌出可信呈现态（实际被渲染的像素比例跌破阈值）的行为——外部窗口遮挡、系统浮层、手势导航离场动画等均会触发，按信号本体语义上报，不区分具体成因。服务端仅在状态迁移时回调（窗口从注册首帧起就被遮挡时无迁移边沿、永不回调），故注册后超时未收到任何回调即判"启动即呈现不完整"兜底

- **ScreenshotFaker 特征检测**  
  检测设备中是否存在 ScreenshotFaker 相关特征

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
- `ScreenCaptureCallback`：检测按键截屏（Android 14+）
- `ScreenRecordingCallback`：检测录屏行为（Android 15+）
- `DisplayManager`：检测投屏状态与虚拟显示器（外接显示器检测按 `DisplayInfo.type` 仅计入有线外接，虚拟/Miracast/模拟显示器分别归入对应检测项）
- `MediaProjection`：检测屏幕投影服务状态
- `MediaRouter`：检测外部显示路由
- `ContentObserver` + `MediaStore`：检测媒体库新增截图
- `FileObserver`：检测截图目录文件创建/移动
- `Settings.Global` / `AccessibilityManager`：检测 ADB、开发者选项、无障碍服务（`getEnabledAccessibilityServiceList` 无需权限、跨应用可见且为实时 Binder 查询，卡片显示已启用服务的包名；无障碍状态经 200ms 轮询前后台即时同步，该项为实时状态——服务全部停用后卡片自动移除，其余环境项保持粘性以防关闭即抹除痕迹）
- `AudioManager.getActiveRecordingConfigurations`：检测设备级录音活动（跨应用；AOSP 对普通应用返回匿名化副本，仅含音源不含来源身份；`getOpsForPackage` 的 op 运行状态查询被 `GET_APP_OPS_STATS` 拦截，精确归因无应用层路径）。来源推测：回看窗口（5 分钟）内按最近使用排序的候选应用（`UsageEvents` 事件流 + `UsageStats` 聚合值双源，覆盖悬浮球/前台服务等无前台化路径）中，先动态排除系统流程性应用（桌面/系统设置/权限对话框宿主——系统设置也可能持有麦克风权限，不排除会在从设置返回后误中），再优先取窗口内有前台服务启动事件（`FOREGROUND_SERVICE_START`，后台录音必然由前台服务承载）的候选，其余取首个具备麦克风能力的应用（跳过无麦克风能力的夹层应用）——麦克风能力 = 持有 `RECORD_AUDIO` 权限且 `record_audio` AppOps 原始模式（`unsafeCheckOpRawNoThrow`，未评估、可区分"仅前台允许"与"已拒绝"）非拒绝态
- `FLAG_WINDOW_IS_OBSCURED` 和 `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`（Android 12+）：检测悬浮窗的触摸遮挡信号
- `TrustedPresentationListener`（Android 15+）：检测本应用窗口跌出可信呈现态，上报窗口显示不完整；服务端仅在状态迁移时回调，"先遮挡再打开应用"的场景以注册后超时未回调（bootstrap 超时）兜底
- `UsageStatsManager`（需"使用情况访问权"）：查询前台应用进行窗口检测归因——焦点被抢占但顶层应用仍是自己判定为悬浮窗；应用保持 RESUMED 而焦点与顶层应用归属他人（正常应用切换会先暂停本应用）判定为自由小窗，并对 `lastTimeUsed` 做新鲜度过滤排除陈旧的顶层应用记录

基于 Android 系统隐藏 API 实现：
- `WindowManagerGlobal.mViews`：枚举本进程窗口列表，排除自有对话框持有焦点造成的误报
- `DisplayManagerGlobal.getDisplayInfo`：读取 `DisplayInfo.type/flags/name` 与虚拟显示器创建者 `ownerPackageName`，实现显示器归因。不做录屏特征词推断（各软件命名方式不一，特征词必然漏报），统一上报"存在虚拟显示器 + 创建者包名"的事实
- `DisplayManager.getWifiDisplayStatus` 与 WFD 状态变化广播：Miracast 投屏连接状态与对端设备名（服务端明示无需权限）
- `AppOpsManager.checkOpNoThrow` + 隐藏 op 字符串 `android:project_media`：查询各应用投屏持久授权状态（`checkOperation` 服务端无越包校验，全量枚举需 `QUERY_ALL_PACKAGES`）
- `Settings.Global` 隐藏键：`adb_wifi_enabled`（无线调试）、`overlay_display_devices`（模拟辅助显示）、`wifi_display_on`（无线显示开关）

### 已知限制（实测 ColorOS 16 / Android 16）

- **录屏检测失效**：`ScreenRecordingCallback` 注册与状态语义均正常（未录屏时正确返回 `NOT_VISIBLE`，`dumpsys window` 中回调登记可见），但无论系统录屏还是三方录屏，WMS 录屏状态均不更新，状态翻转的边沿回调永不触发——系统录屏走 ROM 私有通道不经公共 MediaProjection，三方录屏的虚拟显示器也不被计入录屏状态。三方录屏仍可通过 MediaProjection 检测与媒体库/文件检测兜底覆盖
- **小窗检测失效**：系统对小窗内应用实施隐私屏蔽，不写入用量统计、不设置窗口化标志，应用层无法归因小窗与悬浮窗（焦点被抢占检测仍然有效）
- **显示器归因的边界**：`MediaProjection` 默认路径创建的虚拟显示器未设置 `VIRTUAL_DISPLAY_FLAG_PUBLIC` 时为私有显示器，对其他应用不可见（`getDisplayIds` 按 `FLAG_PRIVATE` 过滤），显示器归因仅覆盖可见（公开）显示器
- **录音来源为推测值**：AOSP 对普通应用的 `getActiveRecordingConfigurations` 返回匿名化副本（来源 uid/包名被剥离，`getClientUid` 等接口被 `MODIFY_AUDIO_ROUTING` 签名权限拦截，op 运行状态查询被 `GET_APP_OPS_STATS` 拦截），无法精确归因。当前推测会动态排除系统流程性应用（桌面/系统设置/权限对话框宿主）并优先采用窗口内有前台服务启动事件的候选，但后台偷录场景仍可能指向错误对象（如期间使用过其他持有麦克风权限且运行前台服务的应用），且依赖"使用情况访问权"
- **Miracast 检测依赖 WFD 通道**：`WifiDisplayStatus` 仅覆盖 Miracast/WFD 投屏；Google Cast 及 ROM 私有投屏协议仍依赖 MediaRouter/显示器检测

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