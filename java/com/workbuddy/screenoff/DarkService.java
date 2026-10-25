package com.workbuddy.screenoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 息屏调试前台服务：App 侧「活着」的证明（v11 精简）。
 *
 * 职责边界（重要）：面板断电动作只能由 system_server 里的钩子执行
 * （App 没有 system uid，反射不了 SurfaceControl），所以本服务不直接碰屏幕，只做两件事：
 *   1) 开关状态 + 30s 心跳（CMD 广播：setstate/hb；钩子据此判死，App 被杀 120s 自动恢复）
 *   2) 双击亮屏的检测也已移入钩子（system_server 永不被 MIUI 熄屏冻结，
 *      App 进程收不到熄屏后的触摸 = v8 双击失效根因），本服务不再挂旁听窗。
 */
public class DarkService extends Service {

    private static final String TAG = "DarkScreen";

    public static final String ACTION_START = "com.workbuddy.screenoff.START";
    public static final String ACTION_STOP = "com.workbuddy.screenoff.STOP";

    private static final int NOTIF_ID = 0x5301;
    private static final long HEARTBEAT_MS = 30_000L;

    private ScheduledExecutorService mExec;
    private ScheduledFuture<?> mHbFuture;

    private static volatile boolean sRunning = false;

    /** 自动关闭时长（分钟）。v1.1.0：0 = 自动关闭关闭（默认），>0 = 开启且 N 分钟。
     *  内存为真源——Settings 写入依赖 WRITE_SETTINGS，没授权时静默失败会吞掉用户的修改；
     *  进程重启回落到 Settings/默认值。 */
    static volatile int sAutoOffMin = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        mExec = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        // v13：START_NOT_STICKY——进程被杀就是彻底死，系统不再拉活。
        // （v12 真机 bug：自动关闭 killProcess 属进程异常死亡，START_STICKY 把 Service
        //  拉活 → 拉活时重新 activate → 又进调试模式 ⇒ 「按钮没关、后台赖着」。
        //  调试模式意外失效由钩子 120s 心跳判死兜底自动恢复面板，语义正确。）
        if (ACTION_STOP.equals(action)) {
            deactivate();
        } else if (!sRunning) {
            // 仅 ACTION_START 正常进入；进程死后不复活
            activate();
        }
        return START_NOT_STICKY;
    }

    // ---------------- 激活 / 关闭 ----------------

    private void activate() {
        try {
            startForegroundCompat();

            // v1.2.3：自动关闭时长从 Settings 恢复（内存真源随进程丢失）。
            // 磁贴/接管等新进程入口开启时也要带上用户设的倒计时；
            // 用户显式设「关」时 KEY_AUTO_OFF=0，读回 0 语义正确。
            if (sAutoOffMin <= 0) {
                sAutoOffMin = DarkState.getInt(getContentResolver(),
                        DarkState.KEY_AUTO_OFF, 0);
            }

            // 1) 内存态（v7：isActive 的唯一真源，不再依赖 Settings 写入——
            //    WRITE_SETTINGS 没授时那路是静默失败的，曾把整个状态显示搞死）
            sRunning = true;
            DarkState.putInt(getContentResolver(), DarkState.KEY_STATE, 1);
            DarkState.putLong(getContentResolver(), DarkState.KEY_APP_HB,
                    System.currentTimeMillis());

            // 2) 通知钩子进入调试模式（只开模式，绝不动面板——点总开关屏幕无反应）。
            //    autoOffMin 随命令交给钩子：倒计时判定在 system_server（v14），
            //    App 进程会被 MIUI 息屏冻结、App 侧定时任务到点不跑（真机实测）。
            sendCmd("setstate", true);

            // 3) 心跳。自动关闭的执行在钩子 daemon（到点必然触发）。
            startHeartbeat();
            Log.i(TAG, "activated");
        } catch (Throwable t) {
            Log.e(TAG, "activate fail", t);
        }
    }

    private void deactivate() {
        try {
            sRunning = false;
            cancelFutures();

            // 请求钩子恢复面板（CMD 丢包时钩子 daemon 兜底判死也能恢复）
            sendCmd("setstate", false);
            DarkState.putInt(getContentResolver(), DarkState.KEY_STATE, 0);
            Log.i(TAG, "deactivated");
        } catch (Throwable t) {
            Log.e(TAG, "deactivate fail", t);
        } finally {
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            stopSelf();
        }
    }

    /** 只读当前是否激活（v7：内存态，App 进程内自洽，不依赖 Settings 权限） */
    static boolean isActive(Context c) {
        return sRunning;
    }

    /**
     * v1.2.3：统一开关真源（App 主页与 QS 磁贴共用）。
     * 钩子活着 → 以钩子写的 KEY_STATE 为准（App 退出重进后进程内存态丢失，
     * 曾显示「关」而钩子模式仍开着）；无钩子时回落 FGS 内存态。
     */
    static boolean isModeActive(Context c) {
        long hb = DarkState.getLong(c.getContentResolver(), DarkState.KEY_HOOK_HB, 0L);
        boolean hookAlive = hb > 0 && System.currentTimeMillis() - hb < 90_000L;
        return hookAlive
                ? DarkState.getInt(c.getContentResolver(), DarkState.KEY_STATE, 0) == 1
                : sRunning;
    }

    // ---------------- CMD 广播 ----------------

    private void sendCmd(String op, boolean on) {
        try {
            // 注意：绝不能 setPackage("com.workbuddy.screenoff") —— 那会把广播限定为
            // 「只投递给本包名注册的接收器」，而 CMD 接收器注册在 system_server（归属
            // 包名是 android），setPackage 后广播永远到不了钩子（v2 踩过的坑：
            // 表现为点开关屏幕毫无反应）。action 名 + token 双重校验已足够防误触发。
            Intent i = new Intent(DarkHook.CMD_ACTION)
                    .putExtra("token", DarkHook.TOKEN)
                    .putExtra("op", op);
            if ("setstate".equals(op)) {
                i.putExtra("on", on);
                // v14：自动关闭时长随命令交给钩子（0=不限时）；时长真源在内存
                i.putExtra("autoOffMin", sAutoOffMin);
            }
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 心跳 ----------------

    private void startHeartbeat() {
        if (mHbFuture != null) {
            mHbFuture.cancel(false);
        }
            mHbFuture = mExec.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!sRunning) {
                            return;
                        }
                        // v7：心跳走 CMD 广播（真机验证过的通道）。Settings 写入保留为
                        // 可选自查（没授 WRITE_SETTINGS 时静默失败，不影响功能）。
                        sendCmd("hb", false);
                        DarkState.putLong(getContentResolver(), DarkState.KEY_APP_HB,
                                System.currentTimeMillis());
                        // v1.2.2：钩子已把模式关掉（自动关闭到点/心跳判死）→ App 本地收尾。
                        // 否则 FGS 空挂着（通知常驻白耗电），且开关显示停留在「开」——
                        // 老板实测的两个现象同根。判定条件：钩子活着且它写的 KEY_STATE=0；
                        // 缺省 1 + 无钩子时不触发（模拟器/未装 LSPosed 场景不能误杀 App 态）。
                        if (DarkState.getInt(getContentResolver(),
                                DarkState.KEY_STATE, 1) == 0) {
                            long hookHb = DarkState.getLong(getContentResolver(),
                                    DarkState.KEY_HOOK_HB, 0L);
                            boolean hookAlive = hookHb > 0
                                    && System.currentTimeMillis() - hookHb < 90_000L;
                            if (hookAlive) {
                                Log.i(TAG, "hook side mode off -> app sync stop");
                                deactivate(); // 幂等：setstate false / restore 钩子侧均无副作用
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    // v14：App 侧自动关闭定时已删——MIUI 息屏冻结 App 后 mExec 任务到点不跑（真机实测），
    // 倒计时判定与执行全部移入钩子 daemon（system_server，永不冻结，到点必然关闭模式）。

    private void cancelFutures() {
        if (mHbFuture != null) mHbFuture.cancel(false);
    }

    // ---------------- 通知 ----------------

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(getString(R.string.notif_channel)) == null) {
            NotificationChannel ch = new NotificationChannel(
                    getString(R.string.notif_channel), getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Notification n = new Notification.Builder(this, getString(R.string.notif_channel))
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.ic_lock_power_off)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.notif_off),
                        PendingIntent.getService(this, 2,
                                new Intent(this, DarkService.class).setAction(ACTION_STOP),
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                        .build())
                .build();

        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // 34+ 必须声明 FGS 类型；以下版本无此要求（specialUse 是 34 概念）
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            // 个别 ROM 对 specialUse 挑剔：退化成无类型前台，通知照常
            Log.w(TAG, "startForeground(specialUse) fail, fallback: " + t);
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        cancelFutures();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
