package it.lory.pontepc;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Piccolo server HTTP (solo Java, nessuna libreria) usato dal PC per esplorare
 * i file, trasferirli (anche oltre 1 GB), installare/disinstallare app e usare il terminale.
 * Tutta la parte che dipende da Android e' nell'interfaccia Host.
 */
public class PonteServer {

    public interface Host {
        String token();
        File root();                 // radice visibile (memoria condivisa, /sdcard)
        boolean rootAllowed();       // l'utente ha attivato il root sul telefono
        boolean wifiAllowed();       // consente collegamenti non-USB
        String infoJson();           // "name":"..","model":"..","android":".."  (senza graffe)
        String appsJson();           // {"apps":[...]}
        String install(String apkPath, boolean useRoot);   // null = ok, altrimenti errore
        String uninstall(String pkg, boolean useRoot);     // null = ok, altrimenti errore
        String[] shellPrefix(boolean useRoot);             // es. {"sh","-c"} oppure {"su","-c"}
    }

    private final Host host;
    private final int port;
    private ServerSocket server;
    private volatile boolean running;
    private final Map<String, long[]> failures = new HashMap<String, long[]>();

    public PonteServer(Host host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() throws IOException {
        server = new ServerSocket(port, 50);
        server.setReuseAddress(true);
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            public void run() { handle(s); }
                        }).start();
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }
        }, "PonteServer");
        t.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ HTTP

    private static class Req {
        String method, path;
        Map<String, String> query = new HashMap<String, String>();
        Map<String, String> headers = new HashMap<String, String>();
        InputStream in;
        OutputStream out;
        long contentLength = 0;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            Req r = new Req();
            r.in = new BufferedInputStream(s.getInputStream(), 65536);
            r.out = s.getOutputStream();
            String line = readLine(r.in);
            if (line == null || line.length() == 0) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            r.method = parts[0];
            String target = parts[1];
            int q = target.indexOf('?');
            r.path = q >= 0 ? target.substring(0, q) : target;
            if (q >= 0) parseQuery(target.substring(q + 1), r.query);
            while (true) {
                String h = readLine(r.in);
                if (h == null || h.length() == 0) break;
                int c = h.indexOf(':');
                if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
            }
            try { r.contentLength = Long.parseLong(r.headers.get("content-length")); } catch (Exception e) { r.contentLength = 0; }

            String addr = s.getInetAddress().getHostAddress();
            if (!allowedAddress(s.getInetAddress())) { error(r, 403, "Collegamento non consentito (attiva Wi-Fi nelle impostazioni del telefono)"); return; }
            if (isBlocked(addr)) { error(r, 429, "Troppi tentativi, riprova tra un minuto"); return; }
            String tok = r.headers.get("x-token");
            if (tok == null || !tok.equals(host.token())) { registerFailure(addr); error(r, 401, "Codice errato"); return; }
            clearFailures(addr);

            boolean rootMode = host.rootAllowed() && "1".equals(r.headers.get("x-root"));
            route(r, rootMode);
        } catch (Exception e) {
            // connessione chiusa o errore: niente da fare
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private boolean allowedAddress(InetAddress a) {
        if (host.wifiAllowed()) return true;
        if (a.isLoopbackAddress()) return true;
        String h = a.getHostAddress();
        return h.startsWith("192.168.42."); // tethering USB di Android
    }

    private synchronized boolean isBlocked(String addr) {
        long[] f = failures.get(addr);
        return f != null && f[0] >= 5 && System.currentTimeMillis() < f[1];
    }
    private synchronized void registerFailure(String addr) {
        long[] f = failures.get(addr);
        if (f == null) { f = new long[]{0, 0}; failures.put(addr, f); }
        f[0]++;
        if (f[0] >= 5) f[1] = System.currentTimeMillis() + 60000;
    }
    private synchronized void clearFailures(String addr) { failures.remove(addr); }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("riga troppo lunga");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), "UTF-8");
    }

    private static void parseQuery(String qs, Map<String, String> out) throws IOException {
        for (String kv : qs.split("&")) {
            if (kv.length() == 0) continue;
            int e = kv.indexOf('=');
            String k = e >= 0 ? kv.substring(0, e) : kv;
            String v = e >= 0 ? kv.substring(e + 1) : "";
            out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v.replace("+", "%2B"), "UTF-8"));
        }
    }

    // ------------------------------------------------------------------ risposte

    static String jsonStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private void send(Req r, int code, String type, byte[] body) throws IOException {
        String msg = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 429 ? "Too Many Requests" : "Error";
        String head = "HTTP/1.1 " + code + " " + msg + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        r.out.write(head.getBytes("UTF-8"));
        r.out.write(body);
        r.out.flush();
    }
    private void json(Req r, int code, String json) throws IOException { send(r, code, "application/json; charset=utf-8", json.getBytes("UTF-8")); }
    private void error(Req r, int code, String msg) throws IOException { json(r, code, "{\"error\":" + jsonStr(msg) + "}"); }
    private void ok(Req r) throws IOException { json(r, 200, "{\"ok\":true}"); }

    // ------------------------------------------------------------------ percorsi

    private File base(boolean rootMode) { return rootMode ? new File("/") : host.root(); }

    /** Restituisce il file solo se resta dentro la radice consentita (blocca ../ e collegamenti). */
    private File resolve(String p, boolean rootMode) throws IOException {
        File base = base(rootMode).getCanonicalFile();
        if (p == null) p = "/";
        File f = new File(base, p).getCanonicalFile();
        String bp = base.getPath();
        if (bp.equals("/")) return f;
        if (f.getPath().equals(bp) || f.getPath().startsWith(bp + File.separator)) return f;
        return null;
    }
    private String display(File f, boolean rootMode) throws IOException {
        String bp = base(rootMode).getCanonicalFile().getPath();
        String fp = f.getCanonicalFile().getPath();
        if (bp.equals("/")) return fp;
        String rel = fp.substring(bp.length());
        return rel.length() == 0 ? "/" : rel;
    }

    private byte[] readBody(Req r, long max) throws IOException {
        if (r.contentLength > max) return null;
        byte[] b = new byte[(int) r.contentLength];
        int off = 0;
        while (off < b.length) {
            int n = r.in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("corpo incompleto");
            off += n;
        }
        return b;
    }

    // ------------------------------------------------------------------ rotte

    private void route(Req r, boolean rootMode) throws Exception {
        String p = r.path;
        if (p.equals("/ping")) {
            json(r, 200, "{" + host.infoJson() + ",\"root\":" + host.rootAllowed() + ",\"rootMode\":" + rootMode + "}");
        } else if (p.equals("/list")) {
            File d = resolve(r.query.get("path"), rootMode);
            if (d == null) { error(r, 403, "Percorso non consentito"); return; }
            if (!d.isDirectory()) { error(r, 404, "Cartella non trovata"); return; }
            File[] files = d.listFiles();
            StringBuilder b = new StringBuilder("{\"path\":" + jsonStr(display(d, rootMode)) + ",\"items\":[");
            boolean first = true;
            if (files != null) {
                for (File f : files) {
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"name\":").append(jsonStr(f.getName()))
                     .append(",\"dir\":").append(f.isDirectory())
                     .append(",\"size\":").append(f.isDirectory() ? 0 : f.length())
                     .append(",\"mtime\":").append(f.lastModified()).append('}');
                }
            }
            json(r, 200, b.append("]}").toString());
        } else if (p.equals("/download")) {
            File f = resolve(r.query.get("path"), rootMode);
            if (f == null) { error(r, 403, "Percorso non consentito"); return; }
            if (!f.isFile()) { error(r, 404, "File non trovato"); return; }
            String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length() + "\r\nConnection: close\r\n\r\n";
            r.out.write(head.getBytes("UTF-8"));
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) r.out.write(buf, 0, n);
            } finally { in.close(); }
            r.out.flush();
        } else if (p.equals("/upload")) {
            File f = resolve(r.query.get("path"), rootMode);
            if (f == null) { error(r, 403, "Percorso non consentito"); return; }
            if (f.isDirectory()) { error(r, 400, "Esiste gia una cartella con questo nome"); return; }
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(f.getPath() + ".part");
            long left = r.contentLength;
            FileOutputStream out = null;
            boolean done = false;
            try {
                out = new FileOutputStream(tmp);
                byte[] buf = new byte[256 * 1024];
                while (left > 0) {
                    int n = r.in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("trasferimento interrotto");
                    out.write(buf, 0, n);
                    left -= n;
                }
                out.close(); out = null;
                if (f.exists()) f.delete();
                done = tmp.renameTo(f);
            } catch (IOException e) {
                if (out != null) try { out.close(); } catch (IOException ignored) { }
                tmp.delete();
                error(r, 500, "Errore durante il salvataggio: " + e.getMessage());
                return;
            }
            if (done) ok(r); else { tmp.delete(); error(r, 500, "Impossibile salvare il file"); }
        } else if (p.equals("/rename")) {
            File f = resolve(r.query.get("path"), rootMode);
            String to = r.query.get("to");
            if (f == null || !f.exists()) { error(r, 404, "File non trovato"); return; }
            if (to == null || to.length() == 0 || to.equals(".") || to.equals("..") || to.indexOf('/') >= 0 || to.indexOf('\\') >= 0) { error(r, 400, "Nome non valido"); return; }
            File t = new File(f.getParentFile(), to);
            if (t.exists()) { error(r, 400, "Esiste gia un file con questo nome"); return; }
            if (f.renameTo(t)) ok(r); else error(r, 500, "Impossibile rinominare");
        } else if (p.equals("/mkdir")) {
            File f = resolve(r.query.get("path"), rootMode);
            if (f == null) { error(r, 403, "Percorso non consentito"); return; }
            if (f.isDirectory() || f.mkdirs()) ok(r); else error(r, 500, "Impossibile creare la cartella");
        } else if (p.equals("/delete")) {
            File f = resolve(r.query.get("path"), rootMode);
            if (f == null || f.getPath().equals(base(rootMode).getCanonicalPath())) { error(r, 403, "Percorso non consentito"); return; }
            if (!f.exists()) { error(r, 404, "File non trovato"); return; }
            if (deleteRec(f)) ok(r); else error(r, 500, "Impossibile eliminare");
        } else if (p.equals("/readtext")) {
            File f = resolve(r.query.get("path"), rootMode);
            if (f == null || !f.isFile()) { error(r, 404, "File non trovato"); return; }
            if (f.length() > 2 * 1024 * 1024) { error(r, 413, "File di testo troppo grande (max 2 MB)"); return; }
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try { int off = 0; while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; } } finally { in.close(); }
            send(r, 200, "text/plain; charset=utf-8", b);
        } else if (p.equals("/writetext")) {
            File f = resolve(r.query.get("path"), rootMode);
            if (f == null || f.isDirectory()) { error(r, 403, "Percorso non consentito"); return; }
            byte[] b = readBody(r, 5 * 1024 * 1024);
            if (b == null) { error(r, 413, "Testo troppo grande"); return; }
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(b); } finally { out.close(); }
            ok(r);
        } else if (p.equals("/apps")) {
            json(r, 200, host.appsJson());
        } else if (p.equals("/install")) {
            File f = resolve(r.query.get("path"), rootMode);
            if (f == null || !f.isFile() || !f.getName().toLowerCase(Locale.US).endsWith(".apk")) { error(r, 404, "APK non trovato"); return; }
            String e = host.install(f.getPath(), rootMode);
            if (e == null) ok(r); else error(r, 500, e);
        } else if (p.equals("/uninstall")) {
            String pkg = r.query.get("pkg");
            if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) { error(r, 400, "Pacchetto non valido"); return; }
            String e = host.uninstall(pkg, rootMode);
            if (e == null) ok(r); else error(r, 500, e);
        } else if (p.equals("/shell")) {
            byte[] b = readBody(r, 8192);
            if (b == null || b.length == 0) { error(r, 400, "Comando vuoto o troppo lungo"); return; }
            shell(r, new String(b, "UTF-8"), rootMode);
        } else {
            error(r, 404, "Non trovato");
        }
    }

    private boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (!deleteRec(k)) return false;
        }
        return f.delete();
    }

    private void shell(Req r, String cmd, boolean rootMode) throws IOException {
        List<String> args = new ArrayList<String>();
        for (String a : host.shellPrefix(rootMode)) args.add(a);
        args.add(cmd);
        ProcessBuilder pb = new ProcessBuilder(args);
        File wd = host.root();
        if (wd != null && wd.isDirectory()) pb.directory(wd);
        pb.redirectErrorStream(true);
        final Process pr;
        try { pr = pb.start(); } catch (IOException e) { error(r, 500, "Impossibile avviare il comando: " + e.getMessage()); return; }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread rd = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = pr.getInputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (out) { if (out.size() < 200000) out.write(buf, 0, n); }
                    }
                } catch (IOException ignored) { }
            }
        });
        rd.start();
        try { rd.join(20000); } catch (InterruptedException ignored) { }
        String extra = "";
        int code = 0;
        if (rd.isAlive()) { pr.destroy(); extra = "\n[interrotto dopo 20 secondi]"; code = 124; }
        else { try { code = pr.waitFor(); } catch (InterruptedException e) { code = 1; } }
        String text;
        synchronized (out) { text = new String(out.toByteArray(), "UTF-8"); }
        json(r, 200, "{\"out\":" + jsonStr(text + extra) + ",\"code\":" + code + "}");
    }
}
