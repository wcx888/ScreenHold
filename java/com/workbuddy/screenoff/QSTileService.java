package com.workbuddy.screenoff;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * 下拉状态栏快捷开关磁贴（v1.2.3 老板需求）：不进 App 即可开/关息屏调试模式。
 *
 * 状态与 App 主页同一真源（DarkService.isModeActive：钩子优先、FGS 兜底）。
 * 点击 = 切换：
 *   关 → 开：startForegroundService 拉起 DarkService.activate（发 CMD setstate on
 *           通知钩子；activate 只开模式不动面板——点开关屏幕无反应是老板定的规矩）。
 *   开 → 关：ACTION_STOP 走 deactivate（恢复面板 + 恢复 screen_off_timeout）。
 *
 * ⚠ 面板断电黑屏期间触摸到不了 framework（MIUI 坑，v12 双击亮屏同因），
 *   此时磁贴点不了——恢复靠电源键（钩子 toggle）。磁贴的价值 = 不进 App 快速开关。
 */
public class QSTileService extends TileService {

    private final android.os.Handler mHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        boolean active = DarkService.isModeActive(this);
        try {
            if (active) {
                // 关：普通 startService 即可（磁贴点击属前台交互不受后台限制；
                // deactivate 不调 startForeground，用 startForegroundService 反而会崩）
                startService(new Intent(this, DarkService.class)
                        .setAction(DarkService.ACTION_STOP));
                toast("装睡模式已关闭");
            } else {
                // 开：Android 12+ 后台 FGS 限制 → 必须 startForegroundService
                //（activate() 必定 startForeground，满足 5s 规则）
                startForegroundService(new Intent(this, DarkService.class)
                        .setAction(DarkService.ACTION_START));
                toast("装睡模式已开启（按电源键息屏）");
            }
        } catch (Throwable t) {
            // 服务启动受限等异常：降级直接发 CMD 给钩子；App FGS 由心跳自愈收尾
            try {
                Intent i = new Intent(DarkHook.CMD_ACTION)
                        .putExtra("token", DarkHook.TOKEN)
                        .putExtra("op", "setstate")
                        .putExtra("on", !active)
                        .putExtra("autoOffMin", DarkService.sAutoOffMin);
                sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
        // 点击瞬间的 updateTile 读到的是切换前的旧态（服务启停是异步的），
        // 延迟补刷两次让磁贴高亮跟上真实状态（实测关路径服务收尾 <1.5s）
        updateTile();
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateTile();
            }
        }, 600);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateTile();
            }
        }, 1600);
    }

    private void updateTile() {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        boolean active = DarkService.isModeActive(this);
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(active ? "已开启" : "未开启");
        t.updateTile();
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
