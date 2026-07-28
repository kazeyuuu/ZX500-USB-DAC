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
        sub.setText(R.string.subtitle);
        sub.setTextSize(13); sub.setTextColor(Color.parseColor("#888888"));
        sub.setPadding(0,0,0,dp(16));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(15); status.setTextColor(Color.parseColor("#222222"));
        status.setPadding(dp(16),dp(16),dp(16),dp(16));
        status.setBackgroundColor(Color.parseColor("#F2F3F5"));
        status.setText(R.string.status_reading);
        root.addView(status, lp(dp(12)));

        root.addView(label(getString(R.string.section_usb_mode)));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        dacBtn = new Button(this); dacBtn.setText(R.string.btn_dac);
        mtpBtn = new Button(this); mtpBtn.setText(R.string.btn_mtp);
        modeRow.addView(dacBtn, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        modeRow.addView(mtpBtn, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        root.addView(modeRow, lp(dp(8)));

        root.addView(label(getString(R.string.section_max_volume)));
        maxVolLabel = new TextView(this);
        maxVolLabel.setTextSize(13); maxVolLabel.setTextColor(Color.parseColor("#222222"));
        root.addView(maxVolLabel);
        maxVolBar = new SeekBar(this);
        maxVolBar.setMax(maxVolLimit - 1);   /* index 1..limit; 0 would be silence */
        root.addView(maxVolBar, lp(dp(4)));

        refreshBtn = new Button(this); refreshBtn.setText(R.string.btn_refresh);
        root.addView(refreshBtn, lp(dp(16)));

        TextView note = new TextView(this);
        note.setText(R.string.note);
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
        maxVolLabel.setText(getString(R.string.maxvol_label, idx, maxVolLimit,
                String.format("%.1f", (idx - 120) / 2.0)));
    }

    void applyMaxVol(final int idx){
        bg(new Runnable(){ public void run(){
            final SuResult res = runSu(SH + "maxvol " + idx);
            ui.post(new Runnable(){ public void run(){
                if(res.ok) toast(getString(R.string.maxvol_applied, idx));
                else { toast(getString(R.string.maxvol_failed, res.problem())); refresh(); }
            }});
        }});
    }

    void refresh(){
        status.setText(R.string.status_reading);
        bg(new Runnable(){ public void run(){
            final SuResult res = runSu(SH + "appstatus");
            final Map<String,String> m = parse(res.out);
            ui.post(new Runnable(){ public void run(){
                if(!res.ok){
                    status.setText(getString(R.string.status_no_root, res.problem()));
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
                s.append(getString(R.string.row_mode, getString(dac ? R.string.mode_dac : R.string.mode_mtp)));
                if (dac) {
                    s.append(getString(R.string.row_rate, host > 0
                            ? getString(R.string.rate_from_host, fmtRate(host))
                            : getString(R.string.rate_idle)));
                    s.append(getString(R.string.row_bits, bits));
                    s.append(getString(R.string.row_drift, getString(fine ? R.string.drift_on : R.string.drift_off)));
                    s.append(getString(R.string.row_volume, fmtVolume(get(m,"VOLUME",""), get(m,"MUTE",""))));
                } else {
                    s.append(getString(R.string.hint_not_dac));
                }
                s.append(getString(R.string.row_autostart,
                        getString("1".equals(get(m,"AUTOSTART","0")) ? R.string.yes : R.string.no)));
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
        if (volQ8 == null || volQ8.isEmpty()) return getString(R.string.value_none);
        double db;
        try { db = Integer.parseInt(volQ8.trim()) / 256.0; } catch (Exception e){ return getString(R.string.value_none); }
        String s = String.format("%.1f dB", db);
        return "1".equals(mute.trim()) ? getString(R.string.volume_muted, s) : s;
    }

    void switchToDac(){
        setControlsEnabled(false);
        bg(new Runnable(){ public void run(){
            SuResult r = runSu(SH + "dac");   // no args: brings the gadget up once, macOS drives rate/volume
            if(r.ok) toast(getString(R.string.dac_entered));
            else     toast(getString(R.string.dac_failed, r.problem()));
            ui.post(new Runnable(){ public void run(){ refresh(); }});
        }});
    }

    void switchToMtp(){
        new AlertDialog.Builder(this)
            .setTitle(R.string.mtp_title)
            .setMessage(R.string.mtp_message)
            .setPositiveButton(R.string.mtp_restart_now, new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){
                bg(new Runnable(){ public void run(){ SuResult r = runSu(SH + "mtp"); if(!r.ok){ toast(getString(R.string.mtp_failed, r.problem())); return; } runSu("svc power reboot || reboot"); }}); }})
            .setNegativeButton(R.string.mtp_restart_later, new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){
                bg(new Runnable(){ public void run(){ SuResult r = runSu(SH + "mtp"); toast(r.ok ? getString(R.string.mtp_set) : getString(R.string.mtp_failed, r.problem())); ui.post(new Runnable(){ public void run(){ refresh(); }}); }}); }})
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
