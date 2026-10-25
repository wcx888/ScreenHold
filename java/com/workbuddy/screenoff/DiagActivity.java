package com.workbuddy.screenoff;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 权限与诊断二级页（v9）：三项权限/作用域右侧实时状态，未授权项点击直达系统授权页；
 * 实时自检三指标收在这里。
 */
public class DiagActivity extends Activity {

    private TextView tvPermWrite;
    private TextView tvPermOverlay;
    private TextView tvLsp;
    private TextView tvVerify;
    private LinearLayout rowWrite;
    private LinearLayout rowOverlay;

    private final Handler mUi = new Handler(Looper.getMainLooper());

    private int mScreenOffEvents = 0;

    private final BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mScreenOffEvents++;
            }
        }
    };

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            mUi.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);

        tvPermWrite = findViewById(R.id.tv_perm_write);
        tvPermOverlay = findViewById(R.id.tv_perm_overlay);
        tvLsp = findViewById(R.id.tv_lsp);
        tvVerify = findViewById(R.id.tv_verify);
        rowWrite = findViewById(R.id.row_write);
        rowOverlay = findViewById(R.id.row_overlay);

        // 未授权项点击直达授权页（已授权点击无动作）
        rowWrite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Settings.System.canWrite(DiagActivity.this)) {
                    return;
                }
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable ignored) {
                }
            }
        });
        rowOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Settings.canDrawOverlays(DiagActivity.this)) {
                    return;
                }
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mScreenOffEvents = 0;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mScreenOffReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mScreenOffReceiver, f);
        }
        mUi.post(mTick);
    }

    @Override
    protected void onPause() {
        mUi.removeCallbacks(mTick);
        try {
            unregisterReceiver(mScreenOffReceiver);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    private void refresh() {
        boolean canWrite = Settings.System.canWrite(this);
        boolean canOverlay = Settings.canDrawOverlays(this);
        boolean canNotify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        setPermRow(tvPermWrite, canWrite);
        setPermRow(tvPermOverlay, canOverlay);

        long hookHb = DarkState.getLong(getContentResolver(), DarkState.KEY_HOOK_HB, 0L);
        long hookAge = hookHb == 0 ? -1 : (System.currentTimeMillis() - hookHb) / 1000;
        boolean hookAlive = hookAge >= 0 && hookAge <= 90;
        if (hookAlive) {
            tvLsp.setText("✅ 已生效（" + hookAge + " 秒前）");
            tvLsp.setTextColor(0xff3b6d11);
        } else {
            tvLsp.setText("未生效 ›");
            tvLsp.setTextColor(0xffa32d2d);
        }
        // 通知权限顺带在诊断里可见（FGS 通知需要）
        if (!canNotify && Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        boolean interactive = isInteractive();
        int uaOk = DarkState.getInt(getContentResolver(), DarkState.KEY_UA_OK, 0);
        int block = DarkState.getInt(getContentResolver(), DarkState.KEY_BLOCK, 0);
        int lock = DarkState.getInt(getContentResolver(), DarkState.KEY_LOCK, 0);
        int timeout;
        try {
            timeout = android.provider.Settings.System.getInt(
                    getContentResolver(), "screen_off_timeout", -1);
        } catch (Throwable t) {
            timeout = -1;
        }
        tvVerify.setText("ticks=" + SystemClock.elapsedRealtime() / 1000 + "s\n"
                + "interactive=" + interactive + "\n"
                + "screenOffEvents=" + mScreenOffEvents + "\n"
                + "userActivity=" + uaOk + "\n"
                + "sleepBlocked=" + block + "\n"
                + "keepLock=" + (lock == 1 ? "held" : "none") + "\n"
                + "screenOffTimeout=" + (timeout == Integer.MAX_VALUE ? "MAX(防锁中)" : String.valueOf(timeout)));
    }

    private void setPermRow(TextView tv, boolean granted) {
        if (granted) {
            tv.setText("✅ 已授权");
            tv.setTextColor(0xff3b6d11);
        } else {
            tv.setText("去授权 ›");
            tv.setTextColor(0xffa32d2d);
        }
    }

    private boolean isInteractive() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }
}
