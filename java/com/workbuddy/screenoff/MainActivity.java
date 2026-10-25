package com.workbuddy.screenoff;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面（v9 重排）：标题瘦身、状态卡键值对、Switch 总开关、立即息屏行卡、
 * 工具列表卡（权限与诊断 / 自动关闭 / adb 说明）。
 * 状态显示以「面板真实态」（钩子观察者写回 KEY_PANEL）为准。
 */
public class MainActivity extends Activity {

    private TextView tvVersion;
    private TextView tvState;
    private TextView tvMode;
    private TextView tvInter;
    private TextView tvHb;
    private TextView tvPanelGo;
    private TextView tvPermBadge;

    /** v1.2.2：统一开关真源缓存（refresh() 里算好）——面板按钮/自动关闭同步共用，
     *  避免各处再单独调 isActive() 造成「显示开、guard 判关」的分叉。 */
    private boolean mActive;
    private TextView tvAutoOff;
    private View dotState;
    private LinearLayout rowPanel;
    private Switch swMode;

    private final Handler mUi = new Handler(Looper.getMainLooper());

    /** Switch 由 refresh() setChecked 时会触发 listener，用标志防循环 */
    private boolean mSwGuard = false;

    private int mScreenOffEvents = 0;
    private long mStarted = 0L;

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
        setContentView(R.layout.activity_main);

        tvVersion = findViewById(R.id.tv_version);
        tvState = findViewById(R.id.tv_state);
        tvMode = findViewById(R.id.tv_mode);
        tvInter = findViewById(R.id.tv_inter);
        tvHb = findViewById(R.id.tv_hb);
        tvPanelGo = findViewById(R.id.btn_panel_go);
        tvPermBadge = findViewById(R.id.tv_perm_badge);
        tvAutoOff = findViewById(R.id.tv_autooff);
        dotState = findViewById(R.id.dot_state);
        rowPanel = findViewById(R.id.row_panel);
        swMode = findViewById(R.id.sw_mode);

        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("v" + vn + " · 息屏不锁屏 · adb 截图/注入全通");
        } catch (Throwable ignored) {
        }

        // v1.1.0：自动关闭默认关闭。不回填旧 Settings 值——旧版（v12~v15）默认写 240，
        // 会把新语义下的「默认关」污染成「默认开」；用户重设即可。

        // 手工链用 android.jar 作 bootclasspath，无 LambdaMetafactory ⇒ 禁 lambda（铁律 218）
        swMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (mSwGuard) {
                    return; // refresh() 里 setChecked 引起的回声，忽略
                }
                startService(new Intent(MainActivity.this, DarkService.class)
                        .setAction(checked ? DarkService.ACTION_START : DarkService.ACTION_STOP));
                mUi.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 400);
            }
        });

        // 面板控制：右侧胶囊按钮发 CMD toggle（钩子按真实面板态取反，与电源键同口径）。
        // 点完 500ms 补刷一次 UI（钩子写回面板真实态后立即反映，不等 1s 轮询）
        tvPanelGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mActive) {
                    return; // 灰态不响应（v1.2.2：与开关显示同一真源）
                }
                try {
                    Intent i = new Intent(DarkHook.CMD_ACTION)
                            .putExtra("token", DarkHook.TOKEN)
                            .putExtra("op", "toggle");
                    sendBroadcast(i);
                    mUi.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    }, 500);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "发送失败：" + t, Toast.LENGTH_SHORT).show();
                }
            }
        });

        findViewById(R.id.row_diag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagActivity.class));
            }
        });

        findViewById(R.id.row_autooff).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAutoOffDialog();
            }
        });

        findViewById(R.id.row_adb).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAdbDialog();
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /**
     * 自动关闭（v1.1.0 重做）：独立开关 + 自定义 时/分。
     * 开关关 = 完全不倒计时；开 = 到点自动关闭调试模式（判定在钩子 daemon）。
     * 输入框留空 = 0（不再强制输入 0）。时长真源 = DarkService.sAutoOffMin
     * （>0 = 开启且 N 分钟；0 = 关闭）。
     */
    private void showAutoOffDialog() {
        int cur = DarkService.sAutoOffMin;
        boolean enabled = cur > 0;
        int curH = enabled ? cur / 60 : 0;
        int curM = enabled ? cur % 60 : 0;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 启用开关行
        LinearLayout swRow = new LinearLayout(this);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView swLabel = new TextView(this);
        swLabel.setText("启用自动关闭");
        swLabel.setTextSize(14);
        swLabel.setTextColor(0xff141416);
        swRow.addView(swLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final Switch swAuto = new Switch(this);
        swAuto.setChecked(enabled);
        swRow.addView(swAuto, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(swRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 时/分输入行（开关关时置灰不可编辑）
        final LinearLayout hourRow = new LinearLayout(this);
        hourRow.setOrientation(LinearLayout.HORIZONTAL);
        hourRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final EditText etH = new EditText(this);
        etH.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (enabled) {
            etH.setText(String.valueOf(curH));
        }
        etH.setHint("0");
        etH.setTextSize(16);
        etH.setTextColor(0xff141416);
        etH.setBackground(getResources().getDrawable(R.drawable.bg_input));
        etH.setGravity(android.view.Gravity.CENTER);
        hourRow.addView(etH, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView uh = new TextView(this);
        uh.setText("时");
        uh.setTextSize(14);
        uh.setTextColor(0xff8e8e93);
        uh.setGravity(android.view.Gravity.CENTER);
        hourRow.addView(uh, new LinearLayout.LayoutParams(dp(34),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final EditText etM = new EditText(this);
        etM.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (enabled) {
            etM.setText(String.valueOf(curM));
        }
        etM.setHint("0");
        etM.setTextSize(16);
        etM.setTextColor(0xff141416);
        etM.setBackground(getResources().getDrawable(R.drawable.bg_input));
        etM.setGravity(android.view.Gravity.CENTER);
        hourRow.addView(etM, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView um = new TextView(this);
        um.setText("分");
        um.setTextSize(14);
        um.setTextColor(0xff8e8e93);
        um.setGravity(android.view.Gravity.CENTER);
        hourRow.addView(um, new LinearLayout.LayoutParams(dp(34),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Runnable applyDim = new Runnable() {
            @Override
            public void run() {
                boolean on = swAuto.isChecked();
                hourRow.setAlpha(on ? 1f : 0.4f);
                etH.setEnabled(on);
                etM.setEnabled(on);
            }
        };
        applyDim.run();
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                applyDim.run();
            }
        });
        box.addView(hourRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tip = new TextView(this);
        tip.setText("开启后到达设定时长会自动关闭装睡模式；输入框留空按 0 计算。");
        tip.setTextSize(11);
        tip.setTextColor(0xffa9a9ae);
        tip.setPadding(0, dp(10), 0, 0);
        box.addView(tip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView go = new TextView(this);
        go.setText("确定");
        go.setTextSize(14);
        go.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        go.setTextColor(0xffffffff);
        go.setGravity(android.view.Gravity.CENTER);
        go.setBackgroundResource(R.drawable.bg_pill);
        go.setPadding(0, dp(11), 0, dp(11));
        LinearLayout.LayoutParams lpGo = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        lpGo.topMargin = dp(16);
        box.addView(go, lpGo);

        final Dialog d = showSheetDialog("自动关闭", null, box);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int newMin;
                if (swAuto.isChecked()) {
                    int h = parseMinuteSafe(etH.getText().toString());
                    int m = parseMinuteSafe(etM.getText().toString());
                    if (h < 0 || h > 99) {
                        Toast.makeText(MainActivity.this, "小时请填 0~99（留空按 0）",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (m < 0 || m > 59) {
                        Toast.makeText(MainActivity.this, "分钟请填 0~59",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    newMin = h * 60 + m;
                    if (newMin <= 0) {
                        Toast.makeText(MainActivity.this, "请填写时长（留空按 0，不能都是 0）",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else {
                    newMin = 0; // 开关关 = 自动关闭停用
                }
                DarkState.putInt(getContentResolver(), DarkState.KEY_AUTO_OFF, newMin);
                DarkService.sAutoOffMin = newMin; // 内存真源，Settings 只是尽力写
                if (mActive) {
                    // 模式开着时把新设置同步给钩子（重设/清除倒计时；v1.2.2 同一真源）
                    Intent i = new Intent(DarkHook.CMD_ACTION)
                            .putExtra("token", DarkHook.TOKEN)
                            .putExtra("op", "setstate")
                            .putExtra("on", true)
                            .putExtra("autoOffMin", newMin);
                    sendBroadcast(i);
                }
                d.dismiss();
                refresh();
            }
        });
    }

    /** 解析时/分输入：留空 = 0（v1.1.0：不再强制输入 0）；非数字 = -1 交由调用方报错 */
    private int parseMinuteSafe(String s) {
        if (s == null || s.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** ripple 引用（selectableItemBackground attr 取值） */
    private int resolveRipple() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 统一的自定义弹窗骨架（v10）：24dp 圆角白卡 + 缩放淡入动画 + 两侧留边，
     * 替代系统 AlertDialog 的直角白框。返回 Dialog 供调用方绑定按钮关闭。
     */
    private Dialog showSheetDialog(String title, String subtitle, View content) {
        final Dialog d = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog);
        int pad = dp(22);
        root.setPadding(pad, dp(20), pad, dp(12));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(0xff141416);
        root.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (subtitle != null) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(11);
            s.setTextColor(0xff8e8e93);
            s.setPadding(0, dp(4), 0, dp(10));
            root.addView(s, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            content.setPadding(0, dp(12), 0, 0);
        }

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        d.setContentView(root);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            // 显式宽度（MATCH_PARENT+horizontalMargin 在部分版本被忽略 ⇒ 弹窗贴边）
            int sw = getResources().getDisplayMetrics().widthPixels;
            w.setLayout(sw - dp(36), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = android.view.Gravity.CENTER;
            w.setWindowAnimations(R.style.DialogAnim);
            w.setDimAmount(0.35f);
        }
        d.show();
        return d;
    }

    private void showAdbDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView body = new TextView(this);
        body.setText("开关（手机连电脑时的主用方式）：\n\n"
                + "adb shell am broadcast -n com.workbuddy.screenoff/.ToggleReceiver\n"
                + "  -a com.workbuddy.screenoff.TOGGLE --ez on true\n"
                + "  --es token " + DarkHook.TOKEN + "\n\n"
                + "（--ez on false 关闭；不带 on 为翻转）\n\n"
                + "自查：settings get system darkscreen_panel（1=面板已断电）\n"
                + "logcat -s DarkScreen（钩子日志）");
        body.setTextSize(12);
        body.setTextColor(0xff444441);
        body.setLineSpacing(dp(3), 1f);
        box.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView ok = new TextView(this);
        ok.setText("知道了");
        ok.setTextSize(14);
        ok.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ok.setTextColor(0xff141416);
        ok.setGravity(android.view.Gravity.END);
        ok.setPadding(0, dp(16), dp(2), dp(4));
        ok.setBackgroundResource(resolveRipple());
        box.addView(ok, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final Dialog d = showSheetDialog("adb 使用说明", null, box);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mScreenOffEvents = 0;
        mStarted = SystemClock.elapsedRealtime();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mScreenOffReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mScreenOffReceiver, f);
        }
        mUi.post(mTick);
        // v1.2.2 重进接管：钩子模式仍开着但本进程 FGS 不在（App 曾退出重进）→
        // 重新拉起 FGS 续心跳，否则 120s 后钩子判死会把模式关掉
        // （用户看着开关是开的、实际即将失效）。
        // v1.2.3：自动关闭时长恢复已统一挪进 DarkService.activate()（磁贴入口同享）。
        try {
            long hookHb = DarkState.getLong(getContentResolver(),
                    DarkState.KEY_HOOK_HB, 0L);
            boolean hookAlive = hookHb > 0
                    && System.currentTimeMillis() - hookHb < 90_000L;
            if (hookAlive && !DarkService.isActive(this)
                    && DarkState.getInt(getContentResolver(),
                            DarkState.KEY_STATE, 0) == 1) {
                startService(new Intent(this, DarkService.class)
                        .setAction(DarkService.ACTION_START));
            }
        } catch (Throwable ignored) {
        }
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
        boolean panelDark = DarkState.getInt(getContentResolver(),
                DarkState.KEY_PANEL, 0) == 1;          // 面板真实态（钩子观察者写回）
        long hookHb = DarkState.getLong(getContentResolver(), DarkState.KEY_HOOK_HB, 0L);
        long hookAge = hookHb == 0 ? -1 : (System.currentTimeMillis() - hookHb) / 1000;
        boolean hookAlive = hookAge >= 0 && hookAge <= 90;   // 与 DiagActivity 同一判据
        // v1.2.2：开关显示真源双轨——钩子活着时以钩子写的 KEY_STATE 为准（App 退出
        // 重进后进程内存态丢失，曾显示「关」而钩子模式仍开着）；无钩子时回落 FGS 内存态。
        // v1.2.3：逻辑抽到 DarkService.isModeActive（QS 磁贴共用同一判定）
        boolean active = DarkService.isModeActive(this);
        mActive = active;
        boolean interactive = isInteractive();

        // 状态卡
        dotState.setBackgroundResource(panelDark ? R.drawable.dot_off : R.drawable.dot_on);
        tvState.setText(panelDark ? "已息屏（纯黑）" : "屏幕亮着");
        tvMode.setText(active ? "已开启" : "未开启");
        tvInter.setText(interactive ? "是（未锁屏）" : "否（已睡/已锁）");
        tvHb.setText(hookAge < 0 ? "无（未加载）"
                : hookAge <= 90 ? "活跃（" + hookAge + " 秒前）" : "超时（" + hookAge + " 秒）");

        // 总开关 Switch（防 setChecked 触发 listener 的回声）
        mSwGuard = true;
        swMode.setChecked(active);
        mSwGuard = false;

        // 面板控制行卡（右侧胶囊按钮）
        rowPanel.setEnabled(active);
        rowPanel.setAlpha(active ? 1f : 0.4f);
        tvPanelGo.setText(panelDark ? "点亮" : "息屏");

        // 权限徽章（诊断页入口的提示）—— 必须与诊断页内层同口径：
        // 除两项系统权限外，还要纳入 LSPosed 作用域判定（钩子心跳新鲜度），
        // 否则作用域关闭时外层仍显「正常」、内层却显「未生效」，自相矛盾。
        boolean canWrite = Settings.System.canWrite(this);
        boolean canOverlay = Settings.canDrawOverlays(this);
        int pending = (canWrite ? 0 : 1) + (canOverlay ? 0 : 1);
        // hookAlive 已在方法开头统一计算（v1.2.2 与开关真源共用）
        if (!hookAlive) {
            tvPermBadge.setText("作用域未生效");
            tvPermBadge.setTextColor(0xffa32d2d);
        } else if (pending > 0) {
            tvPermBadge.setText(pending + " 项待处理");
            tvPermBadge.setTextColor(0xffe24b4a);
        } else {
            tvPermBadge.setText("正常");
            tvPermBadge.setTextColor(0xff3b6d11);
        }

        // 自动关闭当前值（v1.1.0：独立开关 + 时长；内存真源）
        int min = DarkService.sAutoOffMin;
        if (min <= 0) {
            tvAutoOff.setText("关");
        } else {
            int h = min / 60;
            int m = min % 60;
            StringBuilder sb = new StringBuilder("开 · ");
            if (h > 0) {
                sb.append(h).append("小时");
            }
            if (m > 0 || h == 0) {
                sb.append(m).append("分钟");
            }
            tvAutoOff.setText(sb.toString());
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
