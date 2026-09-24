package com.setsail.bilifiddler;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private EditText pcAddr;
    private TextView statusView;
    private TextView logView;
    private Switch mainSwitch;
    private volatile boolean busy = false;
    private volatile boolean suppressToggle = false;

    private static final String PREFS = "bilifiddler";
    private static final String RELAY_BIN = "/data/local/tmp/bfrelay";
    private static final String CONF = "/data/local/tmp/bilifiddler.conf";
    private static final int RELAY_PORT = 18987;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(245, 246, 248));

        TextView title = new TextView(this);
        title.setText("BiliFiddler 抓包转发");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.rgb(251, 114, 153));
        root.addView(title);
        root.addView(gap(8));

        TextView sub = new TextView(this);
        sub.setText("将哔哩哔哩(tv.danmaku.bili)的直连流量经内核转发到同一局域网内 PC 端的 Fiddler。\nPC 端只需正常开启 Fiddler(8888 端口 + 允许远程连接 + 开启 HTTPS 解密)。");
        sub.setTextSize(12);
        sub.setTextColor(Color.rgb(120, 124, 130));
        root.addView(sub);
        root.addView(gap(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = new TextView(this);
        lbl.setText("Fiddler 地址");
        lbl.setTextSize(14);
        lbl.setPadding(0, 0, dp(10), 0);
        row.addView(lbl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pcAddr = new EditText(this);
        pcAddr.setSingleLine(true);
        pcAddr.setTextSize(14);
        pcAddr.setHint("192.168.1.100:8888");
        row.addView(pcAddr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        root.addView(gap(14));

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView swLbl = new TextView(this);
        swLbl.setText("强制引流到 Fiddler");
        swLbl.setTextSize(16);
        switchRow.addView(swLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mainSwitch = new Switch(this);
        switchRow.addView(mainSwitch);
        root.addView(switchRow);
        root.addView(gap(14));

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setPadding(dp(6), dp(10), dp(6), dp(10));
        statusView.setTextColor(Color.rgb(40, 44, 50));
        root.addView(statusView);
        root.addView(gap(10));

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setTextSize(14);
        refresh.setOnClickListener(v -> { if (!busy) runAsync(() -> actionStatus()); });
        root.addView(refresh);
        root.addView(gap(8));

        Button clear = new Button(this);
        clear.setText("关闭并清理引流");
        clear.setTextSize(14);
        clear.setOnClickListener(v -> { if (!busy) runAsync(() -> { actionOff(); runOnUiThread(() -> setSwitch(false)); }); });
        root.addView(clear);

        root.addView(gap(12));
        TextView logTitle = new TextView(this);
        logTitle.setText("操作日志");
        logTitle.setTextSize(13);
        logTitle.setTypeface(null, Typeface.BOLD);
        root.addView(logTitle);
        root.addView(gap(6));
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.rgb(90, 94, 100));
        root.addView(logView);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        mainSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (suppressToggle) { suppressToggle = false; return; }
            if (busy) { setSwitch(false); return; }
            runAsync(() -> {
                if (checked) {
                    if (actionOn()) { setSwitch(true); saveState(true); }
                    else setSwitch(false);
                } else {
                    actionOff();
                    setSwitch(false);
                    saveState(false);
                }
            });
        });

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString("addr", "");
        pcAddr.setText(saved);
        if (sp.getBoolean("enabled", false)) {
            runAsync(() -> {
                if (actionOn()) { setSwitch(true); appendLog("已恢复上次的引流状态"); }
            });
        } else {
            runAsync(() -> actionStatus());
        }
    }

    private View gap(int dpv) {
        View v = new View(this);
        v.setMinimumHeight(dp(dpv));
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void setSwitch(final boolean on) {
        runOnUiThread(() -> {
            suppressToggle = true;
            mainSwitch.setChecked(on);
        });
    }

    private void saveState(boolean enabled) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putString("addr", pcAddr.getText().toString().trim()).putBoolean("enabled", enabled).apply();
    }

    private interface Job { void run() throws Exception; }

    private void runAsync(Job job) {
        busy = true;
        new Thread(() -> {
            try { job.run(); } catch (Exception e) { appendLog("ERR: " + e); }
            busy = false;
        }).start();
    }

    private void appendLog(final String s) {
        runOnUiThread(() -> {
            String prev = logView.getText().toString();
            logView.setText(timeNow() + "  " + s + "\n" + prev);
        });
    }

    private String timeNow() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private String su(String script) throws Exception {
        Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
        OutputStream os = p.getOutputStream();
        os.write(script.getBytes("UTF-8"));
        os.write("\nexit\n".getBytes("UTF-8"));
        os.flush();
        StringBuilder out = new StringBuilder();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.append(new String(buf, 0, n, "UTF-8"));
        p.waitFor();
        return out.toString().trim();
    }

    private void writeBinaryToAppDir() throws Exception {
        byte[] bin = loadBinary();
        java.io.File out = new java.io.File(getFilesDir(), "bfrelay");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        fos.write(bin);
        fos.close();
    }

    private byte[] loadBinary() throws Exception {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        String asset = abi.contains("64") ? "native/bfrelay64" : "native/bfrelay32";
        InputStream in = getAssets().open(asset);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private boolean actionOn() {
        try {
            String addr = pcAddr.getText().toString().trim();
            if (!addr.contains(":")) {
                runOnUiThread(() -> Toast.makeText(this, "地址格式应为 IP:端口", Toast.LENGTH_SHORT).show());
                return false;
            }
            String host = addr.substring(0, addr.indexOf(':')).trim();
            String port = addr.substring(addr.indexOf(':') + 1).trim();
            if (host.isEmpty() || port.isEmpty() || Integer.parseInt(port) <= 0 || Integer.parseInt(port) > 65535) {
                runOnUiThread(() -> Toast.makeText(this, "地址格式应为 IP:端口", Toast.LENGTH_SHORT).show());
                return false;
            }
            appendLog("开启引流 -> " + host + ":" + port + " ...");

            writeBinaryToAppDir();
            String script = "mkdir -p /data/local/tmp\n" +
                "echo 'proxy " + host + " " + port + "' > " + CONF + "\n" +
                "cp " + getFilesDir().getAbsolutePath() + "/bfrelay " + RELAY_BIN + "\n" +
                "chmod 755 " + RELAY_BIN + "\n";
            su(script);

            String enableScript =
                "pkill -f bfrelay 2>/dev/null; sleep 1\n" +
                "nohup setsid " + RELAY_BIN + " >/data/local/tmp/bfrelay.log 2>&1 </dev/null &\n" +
                "sleep 1\n" +
                "UID=$(dumpsys package tv.danmaku.bili 2>/dev/null | grep -oE 'userId=[0-9]+' | head -1 | cut -d= -f2)\n" +
                "[ -z \"$UID\" ] && UID=10334\n" +
                "while iptables -t nat -D OUTPUT -m owner --uid-owner $UID -p tcp --dport 443 -j REDIRECT --to-ports " + RELAY_PORT + " 2>/dev/null; do :; done\n" +
                "while iptables -t nat -D OUTPUT -m owner --uid-owner $UID -p tcp --dport 80 -j REDIRECT --to-ports " + RELAY_PORT + " 2>/dev/null; do :; done\n" +
                "while iptables -D OUTPUT -m owner --uid-owner $UID -p udp --dport 443 -j REJECT 2>/dev/null; do :; done\n" +
                "iptables -t nat -A OUTPUT -m owner --uid-owner $UID -p tcp --dport 443 -j REDIRECT --to-ports " + RELAY_PORT + "\n" +
                "iptables -t nat -A OUTPUT -m owner --uid-owner $UID -p tcp --dport 80 -j REDIRECT --to-ports " + RELAY_PORT + "\n" +
                "iptables -A OUTPUT -m owner --uid-owner $UID -p udp --dport 443 -j REJECT\n" +
                "touch /data/local/tmp/bilifiddler.enabled\n" +
                "echo ENABLED uid=$UID\n";
            String out = su(enableScript);
            appendLog(out.isEmpty() ? "规则已应用" : out);
            runOnUiThread(() -> Toast.makeText(this, "已开启，可打开 B站抓包", Toast.LENGTH_SHORT).show());
            actionStatus();
            return true;
        } catch (Exception e) {
            appendLog("ERR: " + e);
            runOnUiThread(() -> Toast.makeText(this, "开启失败，请确认已 Root", Toast.LENGTH_LONG).show());
            return false;
        }
    }

    private void actionOff() {
        try {
            appendLog("关闭引流 ...");
            String script =
                "UID=$(dumpsys package tv.danmaku.bili 2>/dev/null | grep -oE 'userId=[0-9]+' | head -1 | cut -d= -f2)\n" +
                "[ -z \"$UID\" ] && UID=10334\n" +
                "while iptables -t nat -D OUTPUT -m owner --uid-owner $UID -p tcp --dport 443 -j REDIRECT --to-ports " + RELAY_PORT + " 2>/dev/null; do :; done\n" +
                "while iptables -t nat -D OUTPUT -m owner --uid-owner $UID -p tcp --dport 80 -j REDIRECT --to-ports " + RELAY_PORT + " 2>/dev/null; do :; done\n" +
                "while iptables -D OUTPUT -m owner --uid-owner $UID -p udp --dport 443 -j REJECT 2>/dev/null; do :; done\n" +
                "pkill -f bfrelay 2>/dev/null\n" +
                "rm -f /data/local/tmp/bilifiddler.enabled\n" +
                "echo DISABLED\n";
            su(script);
            appendLog("已关闭引流");
            runOnUiThread(() -> Toast.makeText(this, "已关闭", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            appendLog("ERR: " + e);
        }
    }

    private void actionStatus() {
        try {
            String script =
                "UID=$(dumpsys package tv.danmaku.bili 2>/dev/null | grep -oE 'userId=[0-9]+' | head -1 | cut -d= -f2)\n" +
                "[ -z \"$UID\" ] && UID=10334\n" +
                "R=$(iptables -t nat -S OUTPUT 2>/dev/null | grep -c 'uid-owner $UID')\n" +
                "P=$(ps -A 2>/dev/null | grep -c bfrelay)\n" +
                "echo rules=$R relay=$P uid=$UID\n";
            String out = su(script);
            boolean on = out.contains("rules=2") && out.contains("relay=1");
            setSwitch(on);
            String txt = out.isEmpty() ? "无法获取状态" : out.replaceAll("\n", "  ");
            runOnUiThread(() -> {
                statusView.setText(on ? "● 引流已开启 (B站直连流量正在转发到 Fiddler)" : "○ 引流已关闭");
                statusView.setTextColor(on ? Color.rgb(46, 160, 67) : Color.rgb(120, 124, 130));
            });
            appendLog("状态: " + txt);
        } catch (Exception e) {
            appendLog("ERR: " + e);
            runOnUiThread(() -> Toast.makeText(this, "状态获取失败，请确认已 Root", Toast.LENGTH_SHORT).show());
        }
    }
}
