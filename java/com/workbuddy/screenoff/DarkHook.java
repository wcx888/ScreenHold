package com.workbuddy.screenoff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.PowerManager;
import android.content.ContentResolver;
import android.provider.Settings;
import android.view.KeyEvent;


import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 息屏调试 —— LSPosed 系统框架钩子（scope = android，跑在 system_server 进程）。
 *
 * 核心机制（借鉴 chuyb-re/fakeScreen + ANTiqiuSama/FakeScreen + betterfakescreen 2.2，
 * 前两者 MIT；v3 吸收 betterfakescreen 拆包分析成果）：
 *   反射 SurfaceControl.setDisplayPowerMode(token, 0/2) 直接把面板物理断电/上电，
 *   PowerManagerService 全程收不到休眠指令 ⇒ wakefulness 恒 Awake ⇒ 不锁屏、
 *   Activity 不休眠、screencap 拿真实界面、input 注入有效 —— 而用户看到的是纯黑。
 *
 * v3 关键升级（来自 betterfakescreen 2.2 拆包，包名 top.chuyb.fakeScreen vc22）：
 *   A. hook setDisplayPowerMode 本身做「观察者」：系统任何组件写面板电源都被记录到
 *      sRealMode —— 状态永不失同步（fakeScreen issue #7「亮不回来」的根治法）；
 *   B. toggle 目标按「真实亮灭态」定（观察者状态 + isInteractive），不翻转变量；
 *   C. 双通道电源键：interceptKeyBeforeQueueing（兜底，HyperOS 可能绕过 powerPress）
 *      + powerPress（消费原生行为），400ms 时间窗防同一次按键触发两次；
 *   D. 恢复时 wakeUp 兜底：面板上电后若系统已 non-interactive（拦睡被绕过而真睡），
 *      反射 PowerManager.wakeUp 三签名拉回，避免「面板亮着但已锁屏」。
 *
 * 其余承重结构（沿用 v2）：
 *   拦 PMS goToSleep* / nap（面板断电期间不许系统真睡）；
 *   守护线程 30s 同步 App 开关 + 心跳判死（App 死 120s 自动恢复）；
 *   多击/长按放行（长按前先恢复面板）；开机 BootReceiver 清状态。
 *
 * 唤醒路径：电源键单击 / adb 广播 / App 按钮（双击亮屏功能已按真机实测移除）。
 * 工程规则：入口类不建 Handler/Looper，只用守护线程 + sleep；全程 try-catch；
 * 热路径零日志。禁 lambda（android.jar 无 LambdaMetafactory，铁律 218）。
 */
public class DarkHook implements IXposedHookLoadPackage {

    static final String TAG = "DarkScreen";
    static final String CMD_ACTION = "com.workbuddy.screenoff.CMD";
    static final String HOOKLOG_ACTION = "com.workbuddy.screenoff.HOOKLOG";
    /** 与 App 侧约定一致的暗号，防误触发 */
    static final String TOKEN = "d4rk-scr33n-7f2a91c4";

    /** SurfaceControl power mode 常量（隐藏 API，硬编码） */
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_DOZE = 3;
    private static final int POWER_MODE_ON = 2;

    private static final long STALE_MS = 600_000L; // v1.2.6：10min（黑屏期间根本不判死，此窗只管「面板亮着+App 真死」回收）
    /** daemon 周期 5s：既要心跳判死（120s 窗口足够），也要被睡自愈（快于用户感知） */
    private static final long CYCLE_MS = 5_000L;
    /** 同一次电源键在两个 hook 通道间的去重窗口（betterfakescreen 同款） */
    private static final long KEY_DEDUP_MS = 400L;

    // ---- 面板真实态（观察者实时维护；-1 = 未知）----
    private static volatile int sRealMode = -1;
    /** 面板内存态：我们自己的操作成功后提交（与观察者写入互为双保险） */
    private static volatile boolean sPanelDark = false;
    /** 开关快路径（App 心跳驱动；守护线程 30s 兜底） */
    private static volatile boolean sActive = false;
    private static volatile long sAppHb = 0L;
    /** 上次电源键处理时间（双 hook 去重） */
    private static volatile long sLastKeyTime = 0L;

    private static volatile Context sCtx = null;
    private static volatile ClassLoader sLoader = null;

    /** 自动关闭截止时刻（ms，0=不限时）。v14 起判定在钩子 daemon——
     *  App 进程会被 MIUI 息屏冻结，App 侧定时任务到点不跑（真机实测），system_server 永不冻结 */
    private static volatile long sAutoOffDeadline = 0L;

    /** 拦睡取证节流标志：真机若仍锁屏，看「sleep BLOCKED at <方法名>」日志判断拦截是否生效 */
    private static volatile boolean sBlockedLogged = false;

    /** userActivity 缺失提示节流（v1.0.3） */
    private static volatile boolean sUaMissingLogged = false;

    /** 拦睡成功次数（写 Settings 供真机取证，v1.1.1） */
    private static volatile int sBlockCount = 0;

    /** userActivity 成功次数（写 Settings 供真机取证，v1.1.1） */
    private static volatile int sUaOkCount = 0;

    /** 屏幕常亮锁（v1.1.1 治本）：系统级官方机制，持有中 = 熄屏超时整个停摆 */
    private static volatile android.os.PowerManager.WakeLock sKeepLock = null;

    /** 自己正在写面板电源的标志（v1.1.2）：观察者据此区分「我们写的 OFF」和「系统真睡写的 OFF」，
     *  防止观察者对我们自己的假熄屏做反向恢复造成死循环 */
    private static volatile boolean sInOwnWrite = false;

    /** 上次即时恢复时刻（v1.1.2 节流：1s 内不重复 heal） */
    private static volatile long sLastHealMs = 0L;

    // v1.2.6 逃生阀：黑屏中 10s 内连按 5 次电源键 → 强制退出模式（应对个别 ROM
    // setDisplayPowerMode(ON) 驱动不生效等未知「无法亮屏」死锁，绝不把用户锁死在黑屏里）
    private static volatile int sEscTaps = 0;
    private static volatile long sEscFirstMs = 0L;

    /** PMS 实例（构造器 hook 捕获）：daemon 的 userActivity 心跳要用（v1.0.3） */
    private static volatile Object sPms = null;

    /** 面板反射缓存（system_server 内只解析一次） */
    private static Method sSetPowerMode;
    private static Class<?> sSurfaceControl;
    private static Class<?> sDisplayControl;
    private static volatile boolean sPanelReady = false;
    /** 内置屏 token（观察者用它过滤：只跟踪内置屏的写入） */
    private static volatile IBinder sDisplayToken = null;

    private static final Object LOCK = new Object();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!"android".equals(lp.packageName)) {
            return;
        }
        sLoader = lp.classLoader;
        try {
            // A. 借 AccessibilityManagerService 构造器拿 system context
            //    （HyperCeiler GlobalActionBootstrap 同款姿势）
            XposedHelpers.findAndHookConstructor(
                    "com.android.server.accessibility.AccessibilityManagerService",
                    lp.classLoader, Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (sCtx != null) {
                                return;
                            }
                            try {
                                sCtx = (Context) param.args[0];
                                XposedBridge.log(TAG + ": system context captured");
                                startDaemon();
                                registerCmdReceiver(sCtx);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": bootstrap fail " + t);
                            }
                        }
                    });

            // B. 电源键双通道（betterfakescreen v2.2 同款双保险）。
            //    先装在 PhoneWindowManager 本类；构造器 hook 会在运行时发现 MIUI 子类
            //    override 了这些方法时，在实际子类上重装（v5 修「闪一下进入锁屏」：
            //    HyperOS 的电源键处理被厂商子类接管，只 hook 父类拦不住）
            hookPowerKey(lp.classLoader);
            hookPwmSubclass(lp.classLoader);

            // C. 面板电源观察者：任何组件 setDisplayPowerMode 都被记录
            hookDisplayModeObserver(lp.classLoader);

            // D. 拦系统休眠：面板断电期间 wakefulness 必须保持 Awake
            hookSleep(lp.classLoader);

            XposedBridge.log(TAG + ": installed (panel-off + observer, v3)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init fail " + t);
        }
    }

    // ================= 面板观察者（状态真实性的根基） =================

    /**
     * hook SurfaceControl.setDisplayPowerMode：proceed 后把 (token, mode) 记到 sRealMode。
     * 系统自己的灭屏（超时/其他模块/厂商逻辑）也被跟踪 ⇒ 状态永不失同步。
     * 只跟踪内置屏（sDisplayToken 匹配）；token 还没解析时先放行不记录。
     * mode 为非 OFF/DOZE（即上电）时同步解除假熄屏标记。
     */
    private static void hookDisplayModeObserver(ClassLoader loader) throws Throwable {
        Class<?> sc = XposedHelpers.findClass("android.view.SurfaceControl", loader);
        Method setMode = sc.getDeclaredMethod("setDisplayPowerMode", IBinder.class, int.class);
        setMode.setAccessible(true);
        XposedBridge.hookMethod(setMode, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Object token = param.args[0];
                    int mode = (Integer) param.args[1];
                    IBinder known = sDisplayToken;
                    if (known == null || token == null) {
                        return; // token 未解析/空参：不记录（首次操作前我们自己会解析）
                    }
                    if (!known.equals(token)) {
                        return; // 别的屏（外接/背屏），不归我们管
                    }
                    sRealMode = mode;
                    boolean darkNow = mode == POWER_MODE_OFF || mode == POWER_MODE_DOZE;
                    if (!darkNow) {
                        sPanelDark = false; // 面板被别人点亮 ⇒ 假熄屏结束（真实态优先）
                    }
                    // v1.1.2（拆 betterfakescreen 2.2 拆出的真答案）：非法灭屏毫秒级即时恢复。
                    // 别人软件不锁屏的秘密 = 观察者在「系统写入面板 OFF/DOZE 的同步瞬间」
                    // 判定这不是我们自己的写入（= 系统真睡/意外灭屏），立刻反向面板上电 +
                    // wakeUp——竞速在 keyguard show 的 handler 消息执行之前完成，锁屏根本
                    // 没机会弹出。我们此前只记录不动作，自愈等 5s daemon 轮询，锁屏早弹完了。
                    // sInOwnWrite 防自触发：我们自己写 OFF 时观察者不得反向恢复。
                    if (!darkNow || sInOwnWrite) {
                        return;
                    }
                    boolean shouldHeal = modeAlive(); // v1.2.6：黑屏期间不看心跳
                    long healNow = android.os.SystemClock.uptimeMillis();
                    if (shouldHeal && healNow - sLastHealMs < 1000L) {
                        shouldHeal = false; // 1s 节流：防 DisplayPowerController pending 态反复写 OFF 造成循环
                    }
                    if (!shouldHeal) {
                        return; // 调试模式没开/心跳断/节流中：系统灭屏是合法行为
                    }
                    sLastHealMs = healNow;
                    Context healCtx = sCtx;
                    if (healCtx == null) {
                        return;
                    }
                    XposedBridge.log(TAG + ": unexpected panel-off -> instant heal (racing keyguard)");
                    sInOwnWrite = true;
                    try {
                        setPanelDark(healCtx, false); // 面板上电（观察者随写随记 ON）
                        sPanelDark = false;
                        DarkState.putInt(healCtx.getContentResolver(), DarkState.KEY_PANEL, 0);
                        wakeUpIfAsleep(healCtx); // wakefulness 拉回 Awake，keyguard show 被竞速取消
                    } finally {
                        sInOwnWrite = false;
                    }
                    // 真实态同步给 App（低频：仅面板电源写入时触发）。
                    // v4 的教训：App 显示与实际面板态错位 ⇒ 「解锁后再点就失效」
                    Context c2 = sCtx;
                    if (c2 != null) {
                        DarkState.putInt(c2.getContentResolver(), DarkState.KEY_PANEL,
                                darkNow ? 1 : 0);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        XposedBridge.log(TAG + ": display mode observer hooked");
    }

    // ================= 电源键双通道 =================

    /**
     * powerPress 签名跨版本改过三次，按签名匹配而不是按 SDK 版本分支
     * （厂商 ROM 的私有改动让 SDK 判断不可靠）：
     *   (long, int)          很老的版本，无 count 无 displayId
     *   (long, int, int)     现代签名 (eventTime, count, displayId)
     *   (long, boolean, int) displayId 优先版本 (eventTime, interactive, displayId)
     */
    private static Method findPowerPress(Class<?> type) throws NoSuchMethodException {
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (!method.getName().equals("powerPress") || method.getReturnType() != void.class) {
                continue;
            }
            boolean modern = (args.length == 2 || args.length == 3) && args[0] == long.class
                    && args[1] == int.class && (args.length == 2 || args[2] == int.class);
            boolean older = args.length == 3 && args[0] == long.class
                    && args[1] == boolean.class && args[2] == int.class;
            if (modern || older) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("PhoneWindowManager.powerPress signature not found");
    }

    /** 已装过电源键 hook 的类（防父类+子类重复安装） */
    private static final java.util.HashSet<String> sHookedClasses =
            new java.util.HashSet<String>();

    /**
     * 对给定 PWM 类（可能是 MIUI 子类）安装全套电源键 hook：powerPress（消费）+
     * interceptKeyBeforeQueueing（兜底观察）+ 长按（先恢复再放行）。幂等。
     */
    private static void installPowerHooks(Class<?> pwm) {
        synchronized (sHookedClasses) {
            if (!sHookedClasses.add(pwm.getName())) {
                return; // 这个类已经装过
            }
        }
        try {
            final Method press = findPowerPress(pwm);
            final Class<?>[] pressTypes = press.getParameterTypes();

            // 主通道：powerPress（消费原生行为，防系统真睡）
            XposedBridge.hookMethod(press, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Context ctx = getContext(param.thisObject);
                        if (ctx == null) {
                            return;
                        }
                        int count = pressTypes[1] == int.class
                                ? (Integer) param.args[1] : (Integer) param.args[2];
                        boolean defaultDisplay = pressTypes[1] != int.class
                                || param.args.length < 3 || (Integer) param.args[2] <= 0;
                        if (!defaultDisplay) {
                            return;
                        }
                        // 双通道去重：interceptKeyBeforeQueueing 刚处理过（400ms 内）就不再 toggle，
                        // 但仍消费掉（防系统原生睡）
                        boolean handledByFallback =
                                (android.os.SystemClock.uptimeMillis() - sLastKeyTime) < KEY_DEDUP_MS;
                        if (count != 1) {
                            // 连击是系统手势（部分 ROM 连按=相机/截图）：恢复面板后放行
                            restorePanel(ctx);
                            return;
                        }
                        // v7 修「电源键完全失效」：只有真正 toggle 成功才消费；
                        // 开关没开 / 心跳断 → 完全放行原生行为（v6 无条件 setResult(null)
                        // + shouldBlock 拦死 goToSleep = 电源键既不干活也灭不了屏）
                        if (!handledByFallback && onPowerSingleClick(ctx)) {
                            sLastKeyTime = android.os.SystemClock.uptimeMillis();
                            param.setResult(null); // 消费：PMS 永远不知道按过电源键
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": powerPress fail " + t);
                    }
                }
            });

            // 兜底通道：interceptKeyBeforeQueueing（只观察 + toggle，不消费——
            // 这是所有按键的总入口，乱消费会破坏系统；厂商绕过 powerPress 就靠它）
            for (final Method m : pwm.getDeclaredMethods()) {
                if (!m.getName().equals("interceptKeyBeforeQueueing")) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types.length < 1 || types[0] != KeyEvent.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                KeyEvent ev = (KeyEvent) param.args[0];
                                if (ev == null || ev.getKeyCode() != KeyEvent.KEYCODE_POWER
                                        || ev.getAction() != KeyEvent.ACTION_DOWN) {
                                    return;
                                }
                                Context ctx = getContext(param.thisObject);
                                if (ctx == null) {
                                    return;
                                }
                                // 主通道大概率已处理（powerPress 在 dispatch 阶段必然经过）；
                                // 400ms 内去过重，只有主通道缺席（厂商绕过）时才在这里 toggle
                                if ((android.os.SystemClock.uptimeMillis() - sLastKeyTime)
                                        < KEY_DEDUP_MS) {
                                    return;
                                }
                                if (onPowerSingleClick(ctx)) {
                                    sLastKeyTime = android.os.SystemClock.uptimeMillis();
                                    XposedBridge.log(TAG + ": power via fallback channel");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    break; // 找到一个匹配签名就够
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": fallback hook fail " + t);
                }
            }

            // 长按 / 超长按：先恢复面板再放行（关机菜单不能对着黑屏按）
            for (final Method m : pwm.getDeclaredMethods()) {
                if (m.getName().equals("powerLongPress") || m.getName().equals("powerVeryLongPress")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    Context ctx = getContext(param.thisObject);
                                    if (ctx != null) {
                                        restorePanel(ctx);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": hook longpress fail " + t);
                    }
                }
            }
            // 双击亮屏已删除（v12）：v11 钩子层方案真机实测仍不生效（MIUI 面板断电后
            // 触摸事件大概率被 TSP 固件层直接丢弃，根本到不了 framework），按老板决定砍掉。
            XposedBridge.log(TAG + ": power hooks installed on " + pwm.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": installPowerHooks fail on " + pwm.getName() + ": " + t);
            synchronized (sHookedClasses) {
                sHookedClasses.remove(pwm.getName()); // 失败允许重试
            }
        }
    }

    private static void hookPowerKey(ClassLoader loader) throws Throwable {
        Class<?> pwm = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager", loader);
        installPowerHooks(pwm);
    }

    /**
     * v5 新增：hook PhoneWindowManager 构造器。实例化后检查运行时实际类——
     * MIUI/HyperOS 常用子类 override 电源键方法，只 hook 父类等于拦了个寂寞
     * （真机症状：按电源键「闪一下进入锁屏」= 厂商实现绕过父类 hook）。
     * 实际类 ≠ PhoneWindowManager 时在实际子类上重装全套 hook。
     */
    private static void hookPwmSubclass(ClassLoader loader) throws Throwable {
        Class<?> pwm = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager", loader);
        for (java.lang.reflect.Constructor<?> ctor : pwm.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Class<?> actual = param.thisObject.getClass();
                            if (actual.getName().equals(
                                    "com.android.server.policy.PhoneWindowManager")) {
                                return; // 没有子类，父类 hook 已覆盖
                            }
                            XposedBridge.log(TAG + ": PWM subclass detected: " + actual.getName());
                            installPowerHooks(actual);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": subclass hook fail " + t);
                        }
                    }
                });
                break; // 装一个构造器就够（任一构造器触发都能拿到实例）
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": pwm ctor hook fail " + t);
            }
        }
    }

    /**
     * 电源键单击的统一处理（主/兜底通道共用）：toggle 目标按真实亮灭态定。
     *
     * @return true = 事件被本模块消费（调用方应 setResult(null) / 记去重时间戳）
     */
    private static boolean onPowerSingleClick(Context ctx) {
        boolean enabled = isEnabled(ctx);
        if (!enabled) {
            return false; // 开关没开：完全放行系统原生行为
        }
        synchronized (LOCK) {
            // v1.2.6 逃生阀计数（在 toggle 之前）：10s 窗口内第 5 次按下 = 强制退出。
            // 前几次会正常亮/灭切换，第 5 次面板强制上电+模式全关+电源键交还系统。
            long escNow = android.os.SystemClock.uptimeMillis();
            if (escNow - sEscFirstMs > 10_000L) {
                sEscTaps = 0;
                sEscFirstMs = escNow;
            }
            if (++sEscTaps >= 5) {
                sEscTaps = 0;
                forceDeactivate(ctx);
                return false; // 放行系统：正常亮屏（可能带锁屏页，符合「已退出模式」语义）
            }
            boolean screenActuallyDark = isScreenActuallyDark(ctx);
            try {
                sInOwnWrite = true; // 观察者据此忽略我们自己写的 OFF（防反向恢复死循环）
                try {
                    setPanelDark(ctx, !screenActuallyDark); // ① 物理操作（目标 = 真实态取反）
                } finally {
                    sInOwnWrite = false;
                }
                sPanelDark = !screenActuallyDark;       // ② 成功才提交
                DarkState.putInt(ctx.getContentResolver(), DarkState.KEY_PANEL,
                        sPanelDark ? 1 : 0);
                // v1.0.3：点亮时联动唤醒——系统若已真睡，面板亮了也是黑画面
                //（SurfaceFlinger 停止合成），必须把 wakefulness 拉回
                if (!sPanelDark) {
                    wakeUpIfAsleep(ctx);
                }
                XposedBridge.log(TAG + ": panel=" + (sPanelDark ? "OFF" : "ON"));
                return true;
            } catch (Throwable original) {
                // native 调用可能执行到一半失败，无法确定面板实际状态 ⇒ 悲观假设已变暗，
                // 立刻尝试恢复；恢复也失败就放行给系统（电源键原生=真睡，总比黑屏卡死好）
                sPanelDark = true;
                try {
                    setPanelDark(ctx, false);
                    sPanelDark = false;
                    DarkState.putInt(ctx.getContentResolver(), DarkState.KEY_PANEL, 0);
                } catch (Throwable recovery) {
                    XposedBridge.log(TAG + ": panel recover fail " + recovery);
                }
                return false;
            }
        }
    }

    /** v1.2.6 逃生阀执行体：无条件全关（面板上电、还原超时、清态），电源键交还系统。 */
    private static void forceDeactivate(Context ctx) {
        synchronized (LOCK) {
            sActive = false;
            sAutoOffDeadline = 0L;
            sPanelDark = false;
            try {
                sInOwnWrite = true;
                try {
                    setPanelDark(ctx, false);
                } finally {
                    sInOwnWrite = false;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": escape panel-restore fail " + t);
            }
            restoreScreenOffTimeout(ctx);
            releaseKeepLock(ctx);
            DarkState.putInt(ctx.getContentResolver(), DarkState.KEY_PANEL, 0);
            DarkState.putInt(ctx.getContentResolver(), DarkState.KEY_STATE, 0);
            XposedBridge.log(TAG + ": ESCAPE -> force deactivate (5 power taps / 10s)");
        }
    }

    /** 真实亮灭态 = 观察者记录的 mode 优先，未知时退回 isInteractive() 判断 */
    private static boolean isScreenActuallyDark(Context ctx) {
        int mode = sRealMode;
        if (mode == POWER_MODE_OFF || mode == POWER_MODE_DOZE) {
            return true;
        }
        if (mode == POWER_MODE_ON) {
            return false;
        }
        return !isInteractive(ctx); // mode 未知：以系统交互态为准
    }

    /** 幂等恢复：只有面板处于断电态才真正操作硬件；上电后若系统已真睡则 wakeUp 拉回 */
    private static void restorePanel(Context ctx) {
        synchronized (LOCK) {
            if (!sPanelDark) {
                return;
            }
            try {
                setPanelDark(ctx, false);
                sPanelDark = false;
                DarkState.putInt(ctx.getContentResolver(), DarkState.KEY_PANEL, 0);
                wakeUpIfAsleep(ctx);
                XposedBridge.log(TAG + ": panel restored");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": restore fail " + t);
            }
        }
    }

    // ================= 面板物理操作（SurfaceControl 旁路） =================

    private static void setPanelDark(Context ctx, boolean dark) throws Throwable {
        ensurePanelReflection();
        IBinder token = displayToken(ctx);
        if (token == null) {
            throw new IllegalStateException("built-in display token unavailable");
        }
        sDisplayToken = token; // 观察者从此只跟踪这个屏
        sSetPowerMode.invoke(null, token, dark ? POWER_MODE_OFF : POWER_MODE_ON);
        // 状态由观察者 hook 同步（我们自己也是「任何组件」之一）；
        // 这里再写一份是双保险（观察者万一没装上/被卸载）
        sRealMode = dark ? POWER_MODE_OFF : POWER_MODE_ON;
    }

    private static void ensurePanelReflection() throws Throwable {
        if (sPanelReady) {
            return;
        }
        synchronized (LOCK) {
            if (sPanelReady) {
                return;
            }
            ClassLoader loader = sLoader;
            if (loader == null) {
                loader = DarkHook.class.getClassLoader();
            }
            sSurfaceControl = Class.forName("android.view.SurfaceControl", false, loader);
            // Android 14+ 起物理屏 token 解析挪到 server.display 包
            sDisplayControl = android.os.Build.VERSION.SDK_INT >= 34
                    ? Class.forName("com.android.server.display.DisplayControl", false, loader)
                    : null;
            sSetPowerMode = sSurfaceControl.getDeclaredMethod(
                    "setDisplayPowerMode", IBinder.class, int.class);
            sSetPowerMode.setAccessible(true);
            sPanelReady = true;
            XposedBridge.log(TAG + ": panel reflection ready (sdk="
                    + android.os.Build.VERSION.SDK_INT + ")");
        }
    }

    /**
     * 内置屏 token 三套版本分支（ANTiqiuSama 同款）：
     *   <29   SurfaceControl.getBuiltInDisplay(0)
     *   29-33 SurfaceControl.getInternalDisplayToken()
     *   >=34  DisplayControl.getPhysicalDisplayIds() + getPhysicalDisplayToken(id)
     * 多物理屏时不无脑取 ids[0]（顺序不保证内置屏在前），经默认屏 Address 反查物理 id。
     */
    private static IBinder displayToken(Context ctx) throws Throwable {
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk < 29) {
            return (IBinder) callStatic(sSurfaceControl, "getBuiltInDisplay",
                    new Class<?>[]{int.class}, 0);
        }
        if (sdk < 34) {
            return (IBinder) callStatic(sSurfaceControl, "getInternalDisplayToken",
                    new Class<?>[]{});
        }
        long[] ids = (long[]) callStatic(sDisplayControl, "getPhysicalDisplayIds",
                new Class<?>[]{});
        if (ids == null || ids.length == 0) {
            return null;
        }
        long internalId = ids[0];
        if (ids.length > 1 && ctx != null) {
            Object display = ctx.getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (display != null) {
                Method addressMethod = display.getClass().getDeclaredMethod("getAddress");
                addressMethod.setAccessible(true);
                Object address = addressMethod.invoke(display);
                if (address != null) {
                    Method idMethod = address.getClass()
                            .getDeclaredMethod("getPhysicalDisplayId");
                    idMethod.setAccessible(true);
                    internalId = (Long) idMethod.invoke(address);
                }
            }
        }
        return (IBinder) callStatic(sDisplayControl, "getPhysicalDisplayToken",
                new Class<?>[]{long.class}, internalId);
    }

    private static Object callStatic(Class<?> cls, String name, Class<?>[] types, Object... args)
            throws Throwable {
        Method m = cls.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    // ================= wakeUp 兜底（betterfakescreen c.g() 同款） =================

    /**
     * 面板上电后若系统仍 non-interactive（拦睡被绕过而真睡了），反射 wakeUp 拉回，
     * 否则「面板亮着但已锁屏」。三签名按参数多到少尝试（betterfakescreen 同款顺序）。
     */
    private static void wakeUpIfAsleep(Context ctx) {
        try {
            if (isInteractive(ctx)) {
                return; // 系统醒着，不用拉
            }
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm == null) {
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (invokeWakeUp(pm, new Class<?>[]{long.class, int.class, String.class},
                    new Object[]{now, 1, "DarkScreen"})
                    || invokeWakeUp(pm, new Class<?>[]{long.class, String.class},
                    new Object[]{now, "DarkScreen"})
                    || invokeWakeUp(pm, new Class<?>[]{long.class}, new Object[]{now})) {
                XposedBridge.log(TAG + ": wakeUp issued (system had slept)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": wakeUp fail " + t);
        }
    }

    private static boolean invokeWakeUp(PowerManager pm, Class<?>[] types, Object[] args) {
        try {
            Method m = PowerManager.class.getDeclaredMethod("wakeUp", types);
            m.setAccessible(true);
            m.invoke(pm, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** PhoneWindowManager 实例沿继承链找 mContext（mContext 声明在父类 WindowManagerPolicy） */
    private static Context getContext(Object policy) throws Throwable {
        for (Class<?> type = policy.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("mContext");
                field.setAccessible(true);
                return (Context) field.get(policy);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static boolean isInteractive(Context ctx) {
        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true; // 读不到就当作交互态，避免误拦
        }
    }

    /**
     * 开关判据（v7 起纯内存）：sActive 由 CMD setstate 设置、由 daemon 判死回收。
     * 不再读 Settings —— WRITE_SETTINGS 没授时 Settings 写入静默失败，
     * 读回来永远是 0，曾导致「App 显示未开启 + 电源键拒绝 toggle + 屏幕关不了」三连。
     */
    private static boolean isEnabled(Context ctx) {
        return modeAlive();
    }

    /** App 心跳是否新鲜 */
    private static boolean hbFresh() {
        return (System.currentTimeMillis() - sAppHb) < STALE_MS;
    }

    /**
     * v1.2.6 核心判据（修「黑屏一段时间后自动退出+锁屏」）：面板断电期间不检查 App
     * 心跳 —— MIUI 息屏冻结 App 是常态（项目铁律），黑屏调试恰是冻结高发期，心跳
     * 必然断流；功能由钩子自己维持，App 只是人机入口。面板亮着时才要求心跳新鲜
     * （App 真死 10 分钟后由 daemon 判死回收，见 syncFromSettings）。
     */
    private static boolean modeAlive() {
        return sActive && (sPanelDark || hbFresh());
    }

    // ================= 拦系统休眠 =================

    /** 对给定 PMS 类（可能是 MIUI 子类）安装拦睡 hook（goToSleep* / nap）。幂等。 */
    private static void installSleepHooks(Class<?> pms) {
        synchronized (sHookedClasses) {
            if (!sHookedClasses.add(pms.getName() + "#sleep")) {
                return;
            }
        }
        int hooked = 0;
        for (final Method m : pms.getDeclaredMethods()) {
            final String name = m.getName();
            // v16：Android 15（HyperOS 4.0）休眠入口重构——goToSleep* 之外新增
            // sleepPowerGroup*（PowerGroup 化重构），不拦它就等于超时后照常真睡
            // （真睡必然上锁，假熄屏视觉上看不出，点亮时才暴露锁屏页——真机已复现）。
            if (!(name.startsWith("goToSleep") || name.startsWith("sleepPowerGroup")
                    || "nap".equals(name))) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (shouldBlock()) {
                            if (!sBlockedLogged) {
                                sBlockedLogged = true; // 节流：每进程只打一次，供真机取证
                                XposedBridge.log(TAG + ": sleep BLOCKED at "
                                        + m.getName() + " (HyperOS bypass path?)");
                            }
                            sBlockCount++;
                            Context c = sCtx;
                            if (c != null && sBlockCount % 4 == 1) { // 节流写 Settings
                                DarkState.putInt(c.getContentResolver(),
                                        DarkState.KEY_BLOCK, sBlockCount);
                            }
                            param.setResult(boolean.class == m.getReturnType() ? Boolean.FALSE : null);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + name + " fail " + t);
            }
        }
        XposedBridge.log(TAG + ": sleep hooks = " + hooked + " on " + pms.getName());
        if (hooked == 0) {
            synchronized (sHookedClasses) {
                sHookedClasses.remove(pms.getName() + "#sleep"); // 没装上允许重试
            }
        }
    }

    private static void hookSleep(ClassLoader loader) throws Throwable {
        Class<?> pms = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService", loader);
        installSleepHooks(pms);
    }

    /**
     * v6 新增：PMS 构造器子类侦测（与 PWM 同款对称修复）。MIUI/HyperOS 若用子类
     * override 了 goToSleep*，只 hook 父类拦不住 ⇒ 电源键依旧真睡锁屏（真机已复现）。
     */
    private static void hookPmsSubclass(ClassLoader loader) throws Throwable {
        Class<?> pms = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService", loader);
        for (java.lang.reflect.Constructor<?> ctor : pms.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            // 无论主类还是子类，实例都存下来（daemon 的 userActivity 心跳要用）
                            sPms = param.thisObject;
                            Class<?> actual = param.thisObject.getClass();
                            if (actual.getName().equals(
                                    "com.android.server.power.PowerManagerService")) {
                                return;
                            }
                            XposedBridge.log(TAG + ": PMS subclass detected: " + actual.getName());
                            installSleepHooks(actual);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": pms subclass hook fail " + t);
                        }
                    }
                });
                break;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": pms ctor hook fail " + t);
            }
        }
    }

    /**
     * 拦睡判据（v6 起）：总开关开着（App 心跳新鲜）就拦 —— 调试模式期间系统
     * 永不自动休眠/锁屏（超时和电源键路径都覆盖）；关掉总开关即恢复原行为。
     * 自动关闭定时器（App 侧）兜底防忘关。
     */
    private static boolean shouldBlock() {
        return modeAlive(); // v1.2.6：黑屏期间不看心跳（同 isEnabled，MIUI 冻结 App 时不失效）
    }

    /**
     * userActivity 心跳（v1.0.3）：反射 PMS.userActivity(eventTime, event, flags) 刷新
     * 熄屏超时计时——调试模式开着时超时永远到不了，系统根本不会走到入睡流程。
     * 这是「不锁屏」的治本方案：不依赖拦住某个休眠入口（厂商改多少次名字都无所谓）。
     */
    private static void userActivityKick() {
        Object pms = sPms;
        if (pms == null) {
            return; // PMS 实例还没捕获（构造器 hook 未触发）
        }
        try {
            java.lang.reflect.Method m;
            try {
                // getDeclaredMethod：userActivity 可能是 private（getMethod 只查 public）
                m = pms.getClass().getDeclaredMethod(
                        "userActivity", long.class, int.class, int.class);
            } catch (NoSuchMethodException e0) {
                m = pms.getClass().getMethod(
                        "userActivity", long.class, int.class, int.class);
            }
            m.setAccessible(true);
            m.invoke(pms, android.os.SystemClock.uptimeMillis(), 0, 0);
            sUaOkCount++;
            Context c = sCtx;
            if (c != null) {
                DarkState.putInt(c.getContentResolver(), DarkState.KEY_UA_OK, sUaOkCount);
            }
        } catch (NoSuchMethodException e) {
            if (!sUaMissingLogged) {
                sUaMissingLogged = true;
                XposedBridge.log(TAG + ": userActivity not found on PMS (keep-awake fallback off)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": userActivityKick fail " + t);
        }
    }

    // ================= 守护线程：状态同步 + 判死还原 =================

    private static void startDaemon() {
        // v1.0.2 修致命 bug：compareAndSet(false,true) 首次成功返回 true，
        // 旧代码却拿 true 当「已在跑」直接 return ⇒ daemon 线程从未启动 ⇒
        // 自动关闭/心跳判死/被睡自愈全部失效（真机 hook_hb=null 实锤）。
        if (!sDaemonStarted.compareAndSet(false, true)) {
            return; // 已有 daemon 在跑
        }
        // v1.2.2 开机自愈：调试模式开着时直接关机/禁用模块 ⇒ screen_off_timeout=MAX
        // 残留到下次开机（永不自动锁屏）。开机时模式必未激活（内存态），有备份就恢复；
        // 幂等（old=-1 时不动），也覆盖「App 没来得及 deactivate 就死了」的残留。
        try {
            if (sCtx != null && !sActive) {
                restoreScreenOffTimeout(sCtx);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": boot self-heal fail " + t);
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(CYCLE_MS);
                        syncFromSettings();
                        writeHookHb();
                    } catch (Throwable t2) {
                        XposedBridge.log(TAG + ": daemon cycle fail " + t2);
                    }
                }
            }
        }, "darkscreen-daemon");
        t.setDaemon(true);
        t.start();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sDaemonStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** v1.2.2：KEY_STATE 最近一次写入值（-1=尚未写过），用于「仅变化时写」节流 */
    private static volatile int sLastStateWritten = -1;

    private static void syncFromSettings() {
        Context c = sCtx;
        if (c == null) {
            return;
        }
        try {
            // v7：判死走纯内存（sAppHb 由 CMD setstate/hb 刷新），Settings 不再参与判定。
            // v1.2.6 修「黑屏一阵后自动退出+锁屏」：MIUI 息屏冻结 App → 心跳断流 →
            // 旧逻辑 120s 判死=模式退出+还原超时 → 系统照常锁屏。黑屏本身就是钩子在
            // 维持的功能态，此期间绝不判死；只有面板亮着且心跳超窗（App 真死）才回收。
            long now = System.currentTimeMillis();
            if (sActive && !sPanelDark && !hbFresh()) {
                // App 死了（FGS 被杀/卸载）且面板不在假熄屏态：自动恢复并退出模式
                sActive = false;
                sAutoOffDeadline = 0L;
                restorePanel(c);
                restoreScreenOffTimeout(c); // v1.2.0：恢复用户原熄屏超时
                DarkState.putInt(c.getContentResolver(), DarkState.KEY_STATE, 0);
                XposedBridge.log(TAG + ": app heartbeat stale -> mode off, panel restored");
            }
            // v1.0.3：userActivity 心跳——调试模式期间每轮刷新超时计时，让「熄屏超时」
            // 永远到不了（不追着拦睡的入口，而是根本不走入睡流程 → 不会真睡不会上锁）。
            // v1.1.1：SCREEN_BRIGHT_WAKE_LOCK 屏幕常亮锁——系统级官方机制，持有中 = 超时
            // 停摆，比 userActivity 反射更硬（两者并行，互为冗余）；同时写诊断计数到
            // Settings（system uid 写入可靠），真机 adb 一条命令即可取证。
            // v1.2.6：判据换 modeAlive —— 黑屏期间（App 被冻结心跳停）keepLock 不能放。
            if (modeAlive()) {
                acquireKeepLock(c);
                userActivityKick();
                DarkState.putInt(c.getContentResolver(), DarkState.KEY_UA_OK, sUaOkCount);
            } else {
                releaseKeepLock(c);
            }
            DarkState.putInt(c.getContentResolver(), DarkState.KEY_LOCK,
                    sKeepLock != null && sKeepLock.isHeld() ? 1 : 0);
            // v14：自动关闭的最终执行者——倒计时到点完整关闭调试模式。
            // 判定在 system_server：App 被冻结/被杀都不影响，到点必然执行。
            if (sActive && sAutoOffDeadline > 0 && now > sAutoOffDeadline) {
                sActive = false;
                sAutoOffDeadline = 0L;
                sPanelDark = false;
                setPanelDark(c, false);
                restoreScreenOffTimeout(c); // v1.2.0：恢复用户原熄屏超时
                DarkState.putInt(c.getContentResolver(), DarkState.KEY_PANEL, 0);
                DarkState.putInt(c.getContentResolver(), DarkState.KEY_STATE, 0);
                XposedBridge.log(TAG + ": auto-off deadline -> mode off, panel restored");
            }
            // 被睡自愈：只在「面板假熄屏态」唤醒（v1.1.0 收紧）——调试模式开着但面板
            // 亮着时用户可能故意锁屏放着，绝不能主动把屏幕点亮骚扰人。
            if (sActive && sPanelDark && !isInteractive(c)) {
                setPanelDark(c, false);
                sPanelDark = false;
                DarkState.putInt(c.getContentResolver(), DarkState.KEY_PANEL, 0);
                wakeUpIfAsleep(c); // wakeUp 三签名把 wakefulness 拉回 Awake
                XposedBridge.log(TAG + ": system slept via bypass path -> woke up (self-heal)");
            }
            // v1.2.2：模式激活态权威写入（仅变化时写，避免每 5s 白写 Settings）。
            // 判死/自动关闭到点的分支已各自写 0，这里兜住其它失同步路径。
            int st = sActive ? 1 : 0;
            if (st != sLastStateWritten) {
                DarkState.putInt(c.getContentResolver(), DarkState.KEY_STATE, st);
                sLastStateWritten = st;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sync fail " + t);
        }
    }

    private static void writeHookHb() {
        if (sCtx == null) {
            return;
        }
        DarkState.putLong(sCtx.getContentResolver(), DarkState.KEY_HOOK_HB,
                System.currentTimeMillis());
    }

    // ============ screen_off_timeout 管理（v1.2.0，拆 betterfakescreen 实锤的防锁答案） ============
    // 开调试模式：备份用户原超时 → 写 Integer.MAX_VALUE（PMS 监听该键实时生效，超时永不触发）。
    // 关调试模式/判死/自动关闭到点：恢复备份原值。betterfakescreen 同款键名便于互通。

    private static void applyMaxScreenOffTimeout(Context c) {
        try {
            ContentResolver r = c.getContentResolver();
            int old = Settings.System.getInt(r, "old_screen_off_timeout", -1);
            int cur = Settings.System.getInt(r, "screen_off_timeout", 15000);
            if (old == -1 && cur != Integer.MAX_VALUE) {
                Settings.System.putInt(r, "old_screen_off_timeout", cur);
            }
            Settings.System.putInt(r, "screen_off_timeout", Integer.MAX_VALUE);
            XposedBridge.log(TAG + ": screen_off_timeout -> MAX (anti-lock by timeout removal)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyMaxScreenOffTimeout fail " + t);
        }
    }

    private static void restoreScreenOffTimeout(Context c) {
        try {
            ContentResolver r = c.getContentResolver();
            int old = Settings.System.getInt(r, "old_screen_off_timeout", -1);
            if (old != -1) {
                Settings.System.putInt(r, "screen_off_timeout", old);
                Settings.System.putInt(r, "old_screen_off_timeout", -1);
                XposedBridge.log(TAG + ": screen_off_timeout restored -> " + old);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": restoreScreenOffTimeout fail " + t);
        }
    }

    /** 屏幕常亮锁（v1.1.1）：SCREEN_BRIGHT（不点亮已灭屏幕，只防超时睡），35s 短持有 + daemon 续期 */
    private static void acquireKeepLock(Context c) {
        try {
            if (sKeepLock == null) {
                PowerManager pm = c.getSystemService(PowerManager.class);
                if (pm == null) {
                    return;
                }
                sKeepLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "DarkScreen:keep");
                sKeepLock.setReferenceCounted(false);
            }
            if (!sKeepLock.isHeld()) {
                sKeepLock.acquire(35_000L); // 5s 周期续期，35s 上限防泄漏
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": acquireKeepLock fail " + t);
        }
    }

    private static void releaseKeepLock(Context c) {
        try {
            if (sKeepLock != null && sKeepLock.isHeld()) {
                sKeepLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= CMD 广播（App → system_server） =================

    private static void registerCmdReceiver(Context ctx) {
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent == null || !CMD_ACTION.equals(intent.getAction())) {
                            return;
                        }
                        if (!TOKEN.equals(intent.getStringExtra("token"))) {
                            return;
                        }
                        String op = intent.getStringExtra("op");
                        if ("setstate".equals(op)) {
                            // v7 起总开关状态走纯内存（sActive），Settings 只做自查显示。
                            // 总开关绝不动面板（老板明确：点开关屏幕无反应）。
                            boolean on = intent.getBooleanExtra("on", false);
                            sAppHb = System.currentTimeMillis();
                            sActive = on;
                            // v1.2.2：模式激活态由钩子权威写入（system uid 必成）——
                            // App 重进后以此为准（App 进程内存态随退出丢失，曾显示
                            // 「关」而钩子模式仍开着）。KEY_STATE 语义升级为真源。
                            DarkState.putInt(sCtx.getContentResolver(),
                                    DarkState.KEY_STATE, on ? 1 : 0);
                            sLastStateWritten = on ? 1 : 0;
                            if (on) {
                                // v14：自动关闭时长随开关交给钩子（App 会被冻结，定时任务靠不住）
                                int amin = intent.getIntExtra("autoOffMin", 0);
                                sAutoOffDeadline = amin > 0
                                        ? System.currentTimeMillis() + amin * 60_000L : 0L;
                                // v1.2.0（拆 betterfakescreen 实锤的防锁答案）：直接把
                                // screen_off_timeout 改成 Integer.MAX_VALUE——超时永远不到，
                                // 不真睡不锁屏。system uid 写 Settings 即时生效（PMS 监听该键）。
                                applyMaxScreenOffTimeout(sCtx);
                            } else {
                                sAutoOffDeadline = 0L;
                                restoreScreenOffTimeout(sCtx); // 关总开关 = 恢复用户原超时 + 面板
                                restorePanel(sCtx); // 关总开关 = 连面板一起恢复
                            }
                            XposedBridge.log(TAG + ": setstate on=" + on
                                    + " autoOffMin in cmd=" + intent.getIntExtra("autoOffMin", 0));
                        } else if ("hb".equals(op)) {
                            // App 心跳（30s 一次）：App 进程活着 → 判死窗口刷新
                            sAppHb = System.currentTimeMillis();
                        } else if ("toggle".equals(op)) {
                            // 面板切换（App 按钮 / 脚本）：目标按真实态定（与电源键同口径）
                            boolean enabled = isEnabled(sCtx);
                            if (enabled) {
                                onPowerSingleClick(sCtx);
                            }
                        } else if ("restore".equals(op)) {
                            restorePanel(sCtx);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": cmd fail " + t);
                    }
                }
            };
            ctx.registerReceiver(r, new IntentFilter(CMD_ACTION), Context.RECEIVER_EXPORTED);
            XposedBridge.log(TAG + ": cmd receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerReceiver fail " + t);
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
        // 回播给 App 侧弹 Toast（不连电脑也能看到钩子动向）；
        // setPackage 限定只有本 App 能收，不会广播出去。
        try {
            if (sCtx != null) {
                sCtx.sendBroadcast(new Intent(HOOKLOG_ACTION)
                        .putExtra("msg", msg)
                        .setPackage("com.workbuddy.screenoff"));
            }
        } catch (Throwable ignored) {
        }
    }
}
