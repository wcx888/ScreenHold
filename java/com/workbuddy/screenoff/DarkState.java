package com.workbuddy.screenoff;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * 状态通道：统一走 Settings.System 自定义键。
 *
 * 为什么不用 SharedPreferences + XSharedPreferences：跨进程读 prefs 在 LSPosed 上
 * 有玄学兼容问题（UrgentProbe 那条路要走模块 dex 直接读文件），而 Settings.System
 * 是 system_server 天生可读可写的标准通道，且附带一个好处：
 *   adb shell settings get system darkscreen_state  ← 状态可自查，排障不用连 IDE。
 *
 * 安全红线：Settings.System 全局可读（READ_SETTINGS 只是普通权限）——
 * 这里只放非机密状态；本模块不再保存任何密码（解锁功能已按需求删除）。
 */
final class DarkState {
    private DarkState() {}

    static final String KEY_STATE    = "darkscreen_state";        // 0/1 息屏调试是否激活（App 写）
    static final String KEY_APP_HB   = "darkscreen_hb";           // App 侧心跳（ms，App 写）
    static final String KEY_HOOK_HB  = "darkscreen_hook_hb";      // 钩子侧心跳（ms，钩子写）
    static final String KEY_PANEL    = "darkscreen_panel";        // 0/1 面板是否处于假熄屏断电态（钩子写）
    static final String KEY_AUTO_OFF = "darkscreen_auto_off_min"; // 自动关闭分钟数，0=关闭
    static final String KEY_UA_OK    = "darkscreen_ua_ok";        // userActivity 心跳成功次数（v1.1.1 诊断）
    static final String KEY_BLOCK    = "darkscreen_block";        // 拦睡成功次数（v1.1.1 诊断）
    static final String KEY_LOCK     = "darkscreen_wakelock";     // 0/1 屏幕常亮锁持有中（v1.1.1 诊断）

    static int getInt(ContentResolver cr, String key, int def) {
        try {
            return Settings.System.getInt(cr, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static long getLong(ContentResolver cr, String key, long def) {
        try {
            return Settings.System.getLong(cr, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static void putInt(ContentResolver cr, String key, int v) {
        try {
            Settings.System.putInt(cr, key, v);
        } catch (Throwable ignored) {
        }
    }

    static void putLong(ContentResolver cr, String key, long v) {
        try {
            Settings.System.putLong(cr, key, v);
        } catch (Throwable ignored) {
        }
    }
}
