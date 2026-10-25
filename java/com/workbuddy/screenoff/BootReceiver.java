package com.workbuddy.screenoff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机复位：模块异常（崩溃/被杀）后重启，状态键可能还是 1、钩子没加载。
 * 开机一律清状态，保证最坏情况也只是「变回普通手机」——本版本不碰亮度，
 * 面板电源重启后天然恢复上电，不需要（也不能）从 App 侧动它。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DarkScreen";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            int state = DarkState.getInt(ctx.getContentResolver(), DarkState.KEY_STATE, 0);
            DarkState.putInt(ctx.getContentResolver(), DarkState.KEY_STATE, 0);
            DarkState.putInt(ctx.getContentResolver(), DarkState.KEY_PANEL, 0);
            Log.i(TAG, "boot reset: state=" + state + " -> 0 (panel naturally powered on)");
        } catch (Throwable t) {
            Log.e(TAG, "boot reset fail", t);
        }
    }
}
