package com.workbuddy.screenoff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * adb 广播入口（exported 给 shell 用，token 防误触发）。
 *
 *  开关（主用法，手机连电脑时）：
 *   adb shell am broadcast -n com.workbuddy.screenoff/.ToggleReceiver \
 *       -a com.workbuddy.screenoff.TOGGLE --ez on true  --es token d4rk-scr33n-7f2a91c4
 *   （--ez on false 关闭；不带 on 则为翻转）
 *
 *  查状态：广播发出后会回一条 TOGGLE 状态广播（--ez active），adb logcat 可见。
 *
 *  说明：解锁功能已删除（老板明确不需要；那只是当初的提问）。
 *  本模块开着的时候系统根本不锁屏，不需要解锁。
 */
public class ToggleReceiver extends BroadcastReceiver {

    private static final String TAG = "DarkScreen";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        final String action = intent.getAction();
        try {
            if (DarkHook.HOOKLOG_ACTION.equals(action)) {
                // 钩子侧回播（setPackage 限定过，只有本 App 收得到）
                String msg = intent.getStringExtra("msg");
                if (msg != null) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                }
                return;
            }

            if (!DarkHook.TOKEN.equals(intent.getStringExtra("token"))) {
                Log.w(TAG, "bad token, ignored action=" + action);
                return;
            }

            if ("com.workbuddy.screenoff.TOGGLE".equals(action)) {
                boolean on = intent.getBooleanExtra("on", !isActive(ctx));
                Intent svc = new Intent(ctx, DarkService.class)
                        .setAction(on ? DarkService.ACTION_START : DarkService.ACTION_STOP);
                try {
                    ctx.startForegroundService(svc);
                } catch (Throwable fgsDenied) {
                    // v1.1.0：App 在后台时 Android 15 禁止启动前台服务（锁屏态 adb 开关
                    // 整个失效）。降级 = 直接把 CMD 转给钩子执行（钩子在 system_server，
                    // 不受限制）。此路径无 App 心跳，钩子 120s 判死兜底自动恢复。
                    ctx.sendBroadcast(new Intent(DarkHook.CMD_ACTION)
                            .putExtra("token", DarkHook.TOKEN)
                            .putExtra("op", "setstate")
                            .putExtra("on", on)
                            .putExtra("autoOffMin", DarkService.sAutoOffMin));
                    Log.w(TAG, "FGS denied in background -> forwarded CMD to hook directly");
                }
                // 回一条状态广播，方便脚本确认（adb logcat 可见）
                ctx.sendBroadcast(new Intent("com.workbuddy.screenoff.TOGGLE")
                        .putExtra("active", on)
                        .setPackage(ctx.getPackageName()));
                Log.i(TAG, "toggle -> " + on);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onReceive fail", t);
        }
    }

    static boolean isActive(Context ctx) {
        return DarkService.isActive(ctx);
    }
}
