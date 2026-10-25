# 屏息 ScreenHold —— 酷安发布文案（v2，含 GitHub 链接）

> 开源仓库：**https://github.com/wcx888/ScreenHold**
> APK 直达：仓库 `releases/` 目录（release=正式签名 / debug=调试签名）

## 主文案

---

**屏幕睡了，系统醒着 —— 屏息 ScreenHold，给连电脑调试的手机省一块 OLED 屏**

用过手机连电脑调试的兄弟都懂：adb 截图、input 注入、看 logcat，手机屏幕必须一直亮着。一天调试下来，OLED 有烧屏风险还刺眼，放桌上还以为在给手机做日光浴。

于是写了这个 LSPosed 模块：**装睡模式**。

一键开启后——
- 屏幕进入**纯黑**（面板物理断电，不是调亮度，零发光零烧屏）
- 系统**不锁屏、不休眠**，前台 App 照常跑
- **adb 截图拿到的是真实界面**，input 注入照常生效
- 按电源键 = 息屏 / 点亮切换，怎么按都不会上锁
- 下拉状态栏有快捷开关磁贴，不用进 App
- 可以设定时自动关闭，到点自动恢复（就算 App 被系统冻结也能准时关，判定在 system_server）

原理一句话：反射 SurfaceControl 把显示面板断电，同时拦住休眠入口保持系统清醒。屏幕黑着，SurfaceFlinger 合成照常——所以截屏是真画面，这也是它和"真熄屏"的本质区别。

**门槛（重要）**：
- 需要 root + LSPosed 2.2+，Android 8.1+（老调试机也能用）
- LSPosed 里勾选「系统框架」作用域并重启
- 系统级 hook 工具，风险自负，别拿主力机乱试（虽然试了也就是重启还原）

**隐私**：不联网、不要网络权限、不收集任何数据。

**完全开源（MIT）**：github.com/wcx888/ScreenHold
实现思路参考了 fakeScreen 两个开源项目与 betterfakescreen，致谢名单在 README。APK 在仓库 releases/ 目录自取， release 包正式签名可长期覆盖更新。

屏幕息了，心不慌。🪫➡️😴

---

## 一句话简介（商店短描述）

> 屏幕睡了，系统醒着：LSPosed 假熄屏调试工具，纯黑不锁屏，adb 截图/注入照常。开源 MIT。

## 建议标签

`LSPosed` `Xposed模块` `调试工具` `OLED保护` `开发者工具` `开源`

## 配图建议（酷安发图文 3~5 张）

1. 首页 App 截图（装睡模式开关卡）
2. 下拉快捷磁贴截图
3. 黑屏状态下 adb screencap 拉出的真实界面（对比图最有说服力）
4. 诊断页截图（体现工程透明度）
5. GitHub 仓库首页截图（README 渲染效果，佐证开源）

## 发布时的自检清单

- [ ] 酷安正文附 GitHub 链接（github.com/wcx888/ScreenHold）
- [ ] 评论区置顶：v1.2.4 更新内容 + APK 在仓库 releases/ 目录
- [ ] 确认酷安未误传第三方镜像 APK（一律指向 GitHub releases/）
