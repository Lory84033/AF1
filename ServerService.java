package it.lory.pontepc;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import java.io.File;
import java.util.List;

public class ServerService extends Service {
    public static final int PORT = 8765;
    public static volatile boolean running = false;
    private PonteServer server;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null) {
            try {
                server = new PonteServer(new AndroidHost(this), PORT);
                server.start();
                running = true;
            } catch (Exception e) {
                running = false;
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0);
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Ponte PC attivo")
                .setContentText("Pronto per il collegamento con il computer")
                .setContentIntent(pi);
        Notification n = Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
        startForeground(1, n);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        running = false;
        super.onDestroy();
    }

    /** Collegamento tra il server e le funzioni di Android. */
    static class AndroidHost implements PonteServer.Host {
        private final Context ctx;
        AndroidHost(Context c) { ctx = c; }
        private SharedPreferences prefs() { return ctx.getSharedPreferences("ponte", MODE_PRIVATE); }

        public String token() { return prefs().getString("token", "000000"); }
        public File root() { return Environment.getExternalStorageDirectory(); }
        public boolean rootAllowed() { return prefs().getBoolean("root", false); }
        public boolean wifiAllowed() { return prefs().getBoolean("wifi", false); }

        public String infoJson() {
            return "\"name\":" + PonteServer.jsonStr(Build.MODEL)
                    + ",\"model\":" + PonteServer.jsonStr(Build.MANUFACTURER + " " + Build.MODEL)
                    + ",\"android\":" + PonteServer.jsonStr(Build.VERSION.RELEASE);
        }

        public String appsJson() {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            StringBuilder b = new StringBuilder("{\"apps\":[");
            boolean first = true;
            for (ApplicationInfo a : apps) {
                if (!first) b.append(',');
                first = false;
                boolean sys = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                b.append("{\"pkg\":").append(PonteServer.jsonStr(a.packageName))
                 .append(",\"name\":").append(PonteServer.jsonStr(String.valueOf(a.loadLabel(pm))))
                 .append(",\"system\":").append(sys).append('}');
            }
            return b.append("]}").toString();
        }

        private String su(String cmd) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                int code = p.waitFor();
                return code == 0 ? null : "Comando root fallito (codice " + code + ")";
            } catch (Exception e) {
                return "Root non disponibile: " + e.getMessage();
            }
        }

        public String install(String apkPath, boolean useRoot) {
            if (useRoot) return su("pm install -r '" + apkPath.replace("'", "") + "'");
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(new File(apkPath)), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return null;
            } catch (Exception e) {
                return "Impossibile avviare l'installazione: " + e.getMessage();
            }
        }

        public String uninstall(String pkg, boolean useRoot) {
            if (useRoot) return su("pm uninstall " + pkg);
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return null;
            } catch (Exception e) {
                return "Impossibile avviare la disinstallazione: " + e.getMessage();
            }
        }

        public String[] shellPrefix(boolean useRoot) {
            return useRoot ? new String[]{"su", "-c"} : new String[]{"sh", "-c"};
        }
    }
}
