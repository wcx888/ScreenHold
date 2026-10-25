# 屏息 ScreenHold (offScreen)

**屏息 ScreenHold**（包名与代码标识：`offScreen`）是一个 LSPosed 模块：按一下开关进入**装睡模式**——手机屏幕**纯黑熄屏**（面板物理断电），但系统**不锁屏、不休眠**——adb 截图拿到的是真实界面、input 注入照常工作、前台 App 持续运行。

专为「手机连电脑做软件调试，但 OLED 屏幕不想一直亮着烧屏」的场景设计。

> 名字说明：`屏息 ScreenHold` 是对外名称；APK 包名、代码与调试标识沿用 `offScreen`；软件内的功能模式名为**装睡模式**（屏幕睡了，系统醒着）。

## 功能

- **装睡模式总开关**：开启后屏幕无任何反应；此时电源键 = 息屏 / 亮屏切换（不锁屏）
- **面板控制**：App 内一键息屏 / 点亮
- **下拉快捷开关磁贴**：不进 App 直接开关装睡模式，状态与 App 同步
- **自动关闭**：自定义时/分倒计时，到点自动关闭装睡模式（判定在 system_server，App 被冻结也不影响）
- **唤醒方式**：再按一次电源键 / App 内「点亮」按钮 / adb 广播
- **长按电源键**仍可正常关机（自动先亮屏）
- App 内置「权限与诊断」页：权限直达、钩子心跳、实时自检三指标

## 使用要求

- 已 root 并安装 **LSPosed 2.2+**（Magisk 模块）
- Android 8.1+（minSdk 27，覆盖 LSPosed 全部支持版本；建议 Android 9+）
- 在 LSPosed 管理器中勾选本模块的 **「系统框架」作用域** 并重启手机

## 安装步骤

1. 安装 APK
2. LSPosed 管理器 → 模块 → 屏息 ScreenHold → 勾选「系统框架」作用域 → 重启手机
3. 打开 App，打开「装睡模式」开关
4. 点「立即息屏」或按电源键进入黑屏状态；再按电源键点亮

## adb 用法（连电脑调试时）

```bash
# 开启
adb shell am broadcast -n com.workbuddy.screenoff/.ToggleReceiver \
  -a com.workbuddy.screenoff.TOGGLE --ez on true --es token d4rk-scr33n-7f2a91c4
# 关闭（--ez on false；不带 on 为翻转）
```

状态自查：`adb shell settings get system darkscreen_panel`（1 = 面板已断电）；  
钩子日志：`adb logcat -s DarkScreen`。

## 原理与边界（诚实声明）

- 熄屏实现 = 反射 `SurfaceControl.setDisplayPowerMode(token, 0)` 面板物理断电，  
  同时拦截 `PowerManagerService` 的休眠入口保持 wakefulness=Awake（不锁屏）。
- 防自动锁屏 = 拦截休眠期间将 `screen_off_timeout` 临时改写为极大值（退出时还原），  
  避免系统在黑屏期间照常触发锁屏。
- 息屏状态下 **screencap 拿到的是真实界面**（SurfaceFlinger 合成照常），  
  这是它与「真熄屏」的本质区别，也是本工具的调试价值所在。
- OLED 面板断电即无发光，无烧屏风险；装睡模式期间系统不会自动休眠（费一点电）。
- 本工具不修改任何系统文件，卸载模块并重启即完全还原。

## 隐私

本应用**不联网、不申请网络权限、不收集任何数据**。见 [PRIVACY.md](PRIVACY.md)。

## 风险声明

本工具依赖 root 与 Xposed 框架 hook 系统服务，属高风险类工具：  
请在自己的设备上自行承担使用风险；不要在缺少熄屏保护需求的场景长期开启；  
作者不对因使用本工具造成的任何损失负责。

## 致谢（开源借鉴标注）

本项目的实现思路与部分机制借鉴自以下开源项目，在此致谢：

- [chuyb-re/fakeScreen](https://github.com/chuyb-re/fakeScreen)（MIT License）——「假熄屏真调试」核心思路：面板断电 + 保持系统唤醒
- [ANTiqiuSama/FakeScreen](https://github.com/ANTiqiuSama/FakeScreen)（MIT License）——同上，另一参考实现
- **betterfakescreen 2.2**（闭源应用，仅作行为参考）——通过拆包分析确认其防自动锁屏机制（`screen_off_timeout` 改写），本项目据此以自己的代码独立实现；未复制其任何代码或资源

以上 MIT 项目原文见 [THIRD\_PARTY\_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可证

[MIT License](LICENSE)
