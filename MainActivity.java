package it.lory.pontepc;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Random;

/** Schermata unica, in stile Android 4 (Holo scuro). Costruita da codice: nessun file di layout. */
public class MainActivity extends Activity {
    private static final int BLUE = 0xFF33B5E5;
    private SharedPreferences prefs;
    private TextView codeView, statusView, ipView;
    private Button toggle;
    private final Handler handler = new Handler();

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ponte", MODE_PRIVATE);
        if (!prefs.contains("token")) newCode();

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(root);

        TextView title = text("Ponte PC", 26, Color.WHITE);
        root.addView(title);
        View line = new View(this);
        line.setBackgroundColor(BLUE);
        root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));

        statusView = text("", 16, Color.GRAY);
        statusView.setPadding(0, dp(14), 0, dp(6));
        root.addView(statusView);

        toggle = new Button(this);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, ServerService.class);
                if (ServerService.running) stopService(i); else startService(i);
                handler.postDelayed(new Runnable() { public void run() { refresh(); } }, 400);
            }
        });
        root.addView(toggle);

        TextView l1 = text("Codice di abbinamento", 13, Color.GRAY);
        l1.setPadding(0, dp(18), 0, 0);
        root.addView(l1);
        codeView = text("", 40, BLUE);
        root.addView(codeView);
        Button nc = new Button(this);
        nc.setText("Genera nuovo codice");
        nc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newCode(); refresh(); }
        });
        root.addView(nc);

        ipView = text("", 14, Color.LTGRAY);
        ipView.setPadding(0, dp(16), 0, dp(8));
        root.addView(ipView);

        root.addView(check("Consenti anche collegamento Wi-Fi (meno sicuro)", "wifi"));
        root.addView(check("Usa permessi root (installazione app e terminale). Serve un telefono con root.", "root"));

        TextView help = text("Come si usa:\n1) Premi Avvia.\n2) Sul computer apri Ponte PC e scrivi questo codice nel profilo.\n"
                + "3) Cavo USB: attiva Opzioni sviluppatore > Debug USB, oppure Tethering USB, e premi Connetti sul computer.\n"
                + "4) Per installare APK dal PC attiva \"Origini sconosciute\" nelle Impostazioni > Sicurezza.", 13, Color.GRAY);
        help.setPadding(0, dp(18), 0, 0);
        root.addView(help);

        setContentView(sv);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private CheckBox check(String label, final String key) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(Color.WHITE);
        c.setChecked(prefs.getBoolean(key, false));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) { prefs.edit().putBoolean(key, on).commit(); }
        });
        c.setGravity(Gravity.CENTER_VERTICAL);
        return c;
    }

    private void newCode() {
        prefs.edit().putString("token", String.valueOf(100000 + new Random().nextInt(900000))).commit();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        boolean on = ServerService.running;
        statusView.setText(on ? "In funzione (porta " + ServerService.PORT + ")" : "Fermo");
        statusView.setTextColor(on ? 0xFF99CC00 : Color.GRAY);
        toggle.setText(on ? "Ferma" : "Avvia");
        codeView.setText(prefs.getString("token", ""));
        ipView.setText("Indirizzo del telefono sulla rete: " + localIp());
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            StringBuilder sb = new StringBuilder();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                Enumeration<InetAddress> ad = ni.getInetAddresses();
                while (ad.hasMoreElements()) {
                    InetAddress a = ad.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(a.getHostAddress());
                    }
                }
            }
            return sb.length() == 0 ? "nessuno" : sb.toString();
        } catch (Exception e) {
            return "sconosciuto";
        }
    }
}
