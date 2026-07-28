package com.zx.dac;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/* The gadget is output-only with native eight-rate switching. The host selects
 * sample rate, volume and mute; the app brings the DAC up and reports its state. */
public class MainActivity extends Activity {

    static final String SH = "sh /data/local/tmp/usb-dac.sh ";

    final Handler ui = new Handler(Looper.getMainLooper());
    final MainActivity self = this;
    TextView status;
    Button dacBtn, mtpBtn, refreshBtn;
    String mode = "?";
    /* Highest Sony hardware index the host slider may reach. Applied by
     * usb-dac.sh the next time DAC mode starts, not to the running stream. */
    SeekBar maxVolBar;
    TextView maxVolLabel;
    int maxVolLimit = 120;

    int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("ZX500 USB-DAC");
        title.setTextSize(24); title.setTextColor(Color.parseColor("#111111"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("hi-res USB-DAC 控制");
        sub.setTextSize(13); sub.setTextColor(Color.parseColor("#888888"));
        sub.setPadding(0,0,0,dp(16));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(15); status.setTextColor(Color.parseColor("#222222"));
        status.setPadding(dp(16),dp(16),dp(16),dp(16));
        status.setBackgroundColor(Color.parseColor("#F2F3F5"));
        status.setText("读取状态中…");
        root.addView(status, lp(dp(12)));

        root.addView(label("USB 模式"));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        dacBtn = new Button(this); dacBtn.setText("DAC 模式");
        mtpBtn = new Button(this); mtpBtn.setText("MTP / 普通");
        modeRow.addView(dacBtn, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        modeRow.addView(mtpBtn, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        root.addView(modeRow, lp(dp(8)));

        root.addView(label("最大音量上限"));
        maxVolLabel = new TextView(this);
        maxVolLabel.setTextSize(13); maxVolLabel.setTextColor(Color.parseColor("#222222"));
        root.addView(maxVolLabel);
        maxVolBar = new SeekBar(this);
        maxVolBar.setMax(maxVolLimit - 1);   /* index 1..limit; 0 would be silence */
        root.addView(maxVolBar, lp(dp(4)));

        refreshBtn = new Button(this); refreshBtn.setText("刷新状态");
        root.addView(refreshBtn, lp(dp(16)));

        TextView note = new TextView(this);
        note.setText("说明：\n"
            + "• 采样率、音量、静音都在电脑系统中选择（macOS Audio MIDI Setup / Windows 声音设置）。\n"
            + "• 支持 44.1 / 48 / 88.2 / 96 / 176.4 / 192 / 352.8 / 384 kHz，24 bit，切换无需拔插。\n"
            + "• 本机音量已交给电脑控制；音量条即 ZX500 硬件音量。\n"
            + "• 上限决定电脑音量条拉满时的响度，默认 100（Sony 满刻度为 120）。\n"
            + "• 修改上限后需重新进入 DAC 模式才生效。\n"
            + "• 进入 DAC 后若电脑未识别，拔插一次 USB 线。\n"
            + "• 切到 MTP 需重启生效。\n"
            + "• 首次使用请在 KernelSU 里给本应用授予 root。");
        note.setTextSize(12); note.setTextColor(Color.parseColor("#999999"));
        note.setPadding(0,dp(20),0,0);
        root.addView(note);

        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(Color.WHITE); sv.addView(root);
        setContentView(sv);

        /* Track while dragging, persist once on release: one flash write per change. */
        maxVolBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser){
                if (fromUser) showMaxVol(p + 1);
            }
            public void onStartTrackingTouch(SeekBar sb){}
            public void onStopTrackingTouch(SeekBar sb){ applyMaxVol(sb.getProgress() + 1); }
        });

        dacBtn.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ switchToDac(); }});
        mtpBtn.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ switchToMtp(); }});
        refreshBtn.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ refresh(); }});

        refresh();
    }

    LinearLayout.LayoutParams lp(int topMargin){
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        p.topMargin = topMargin; return p;
    }
    TextView label(String s){
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(13); t.setTextColor(Color.parseColor("#555555"));
        t.setPadding(0,dp(16),0,dp(4)); return t;
    }

    /* Result of a `su -c` invocation. Success is decided by the EXIT CODE alone. */
    static class SuResult {
        final boolean ok; final String out; final String err;
        SuResult(boolean ok, String out, String err){ this.ok=ok; this.out=out; this.err=err; }
        String problem(){ String e = err.trim(); return e.isEmpty() ? out.trim() : e; }
    }

    SuResult runSu(String cmd){
        StringBuilder out = new StringBuilder(), err = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su","-c",cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line; while((line=r.readLine())!=null) out.append(line).append('\n');
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while((line=e.readLine())!=null) err.append(line).append('\n');
            int rc = p.waitFor();
            return new SuResult(rc == 0, out.toString(), err.toString());
        } catch (Exception ex){
            return new SuResult(false, "", "su failed: " + ex.getMessage());
        }
    }
    void bg(Runnable task){ new Thread(task).start(); }
    void toast(final String s){ ui.post(new Runnable(){ public void run(){ Toast.makeText(self, s, Toast.LENGTH_LONG).show(); }}); }

    void setControlsEnabled(boolean on){
        boolean dac = "DAC".equals(mode);
        dacBtn.setEnabled(on && !dac);
        mtpBtn.setEnabled(on && dac);
        refreshBtn.setEnabled(on);
        maxVolBar.setEnabled(on);
    }

    /* One Sony index is 0.5 dB and index 120 is 0 dB, so the ceiling in dB is
     * (index - 120) / 2. Shown alongside the raw index, which is what the Sony UI
     * and `usb-dac.sh maxvol` both speak. */
    void showMaxVol(int idx){
        maxVolLabel.setText("电脑音量最大可到 " + idx + " / " + maxVolLimit
                + "（" + String.format("%.1f", (idx - 120) / 2.0) + " dB）");
    }

    void applyMaxVol(final int idx){
        bg(new Runnable(){ public void run(){
            final SuResult res = runSu(SH + "maxvol " + idx);
            ui.post(new Runnable(){ public void run(){
                if(res.ok) toast("上限已设为 " + idx + "，下次进入 DAC 模式生效");
                else { toast("设置失败：" + res.problem()); refresh(); }
            }});
        }});
    }

    void refresh(){
        status.setText("读取状态中…");
        bg(new Runnable(){ public void run(){
            final SuResult res = runSu(SH + "appstatus");
            final Map<String,String> m = parse(res.out);
            ui.post(new Runnable(){ public void run(){
                if(!res.ok){
                    status.setText("无法读取状态。\n请在 KernelSU 里给本应用授予 root。\n\n" + res.problem());
                    setControlsEnabled(false);
                    refreshBtn.setEnabled(true);
                    return;
                }
                mode = get(m,"MODE","?");
                int bits = pInt(get(m,"BITS","24"));
                int host = pInt(get(m,"HOSTRATE","0"));
                boolean dac = "DAC".equals(mode);
                boolean fine = "fine".equals(get(m,"DRIFT","fine"));

                StringBuilder s = new StringBuilder();
                s.append("模式: ").append(dac ? "DAC (USB-DAC)" : "MTP / 普通");
                if (dac) {
                    s.append("\n采样率: ").append(host > 0
                            ? (fmtRate(host) + "（由电脑选择）")
                            : "待机（电脑无音频流）");
                    s.append("\n位深: ").append(bits).append(" bit");
                    s.append("\n漂移校正: ").append(fine ? "开 (fine)" : "关");
                    s.append("\n音量: ").append(fmtVolume(get(m,"VOLUME",""), get(m,"MUTE","")));
                } else {
                    s.append("\n(重启后自动进入 DAC 或点下方 DAC 模式)");
                }
                s.append("\n开机自动 DAC: ").append("1".equals(get(m,"AUTOSTART","0")) ? "是" : "否");
                status.setText(s.toString());

                int lim = pInt(get(m,"MAXVOLLIMIT","120"));
                if (lim >= 1) { maxVolLimit = lim; maxVolBar.setMax(lim - 1); }
                int mv = pInt(get(m,"MAXVOL","100"));
                if (mv < 1) mv = 1; else if (mv > maxVolLimit) mv = maxVolLimit;
                maxVolBar.setProgress(mv - 1);
                showMaxVol(mv);

                setControlsEnabled(true);
            }});
        }});
    }

    /* Show the host rate as kHz (e.g. 44100 -> "44.1 kHz", 384000 -> "384 kHz"). */
    String fmtRate(int hz){
        if (hz % 1000 == 0) return (hz/1000) + " kHz";
        return String.format("%.1f kHz", hz/1000.0);
    }

    /* VOLUME is signed Q8.8 dB; MUTE is 0/1. Show e.g. "-27.5 dB" or "静音 (-27.5 dB)". */
    String fmtVolume(String volQ8, String mute){
        if (volQ8 == null || volQ8.isEmpty()) return "—";
        double db;
        try { db = Integer.parseInt(volQ8.trim()) / 256.0; } catch (Exception e){ return "—"; }
        String s = String.format("%.1f dB", db);
        return "1".equals(mute.trim()) ? ("静音 (" + s + ")") : s;
    }

    void switchToDac(){
        setControlsEnabled(false);
        bg(new Runnable(){ public void run(){
            SuResult r = runSu(SH + "dac");   // no args: brings the gadget up once, macOS drives rate/volume
            if(r.ok) toast("已进入 DAC 模式，如未识别请拔插 USB");
            else     toast("进入 DAC 失败: " + r.problem());
            ui.post(new Runnable(){ public void run(){ refresh(); }});
        }});
    }

    void switchToMtp(){
        new AlertDialog.Builder(this)
            .setTitle("切换到 MTP / 普通模式")
            .setMessage("切换到普通 USB 模式需要重启才能生效。是否现在重启？")
            .setPositiveButton("现在重启", new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){
                bg(new Runnable(){ public void run(){ SuResult r = runSu(SH + "mtp"); if(!r.ok){ toast("设置 MTP 失败: " + r.problem()); return; } runSu("svc power reboot || reboot"); }}); }})
            .setNegativeButton("稍后手动重启", new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){
                bg(new Runnable(){ public void run(){ SuResult r = runSu(SH + "mtp"); toast(r.ok ? "已设置为 MTP，重启后生效" : ("设置 MTP 失败: " + r.problem())); ui.post(new Runnable(){ public void run(){ refresh(); }}); }}); }})
            .show();
    }

    Map<String,String> parse(String s){
        Map<String,String> m = new HashMap<String,String>();
        for(String line : s.split("\n")){ int i=line.indexOf('='); if(i>0) m.put(line.substring(0,i).trim(), line.substring(i+1).trim()); }
        return m;
    }
    String get(Map<String,String> m,String k,String d){ String v=m.get(k); return v==null||v.isEmpty()?d:v; }
    int pInt(String s){ try{ return Integer.parseInt(s.trim()); }catch(Exception e){ return 0; } }
}
