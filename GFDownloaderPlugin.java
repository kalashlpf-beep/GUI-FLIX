package com.guiflix.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Téléchargeur de GUI FLIX : reprise, pause, annulation et progression dans la barre de notification. */
@CapacitorPlugin(name = "GFDownloader")
public class GFDownloaderPlugin extends Plugin {
    private static final String CHANNEL = "gf_dl";
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private boolean asked = false;

    private static class Task {
        String id;
        String url;
        String title;
        File file;
        volatile boolean pause;
        volatile boolean cancel;
        volatile boolean running;
        volatile long done;
        volatile long total;
    }

    private static int nid(String id) {
        return (Math.abs(id.hashCode()) % 100000) + 1000;
    }

    @PluginMethod
    public void start(PluginCall call) {
        String id = call.getString("id");
        String url = call.getString("url");
        String path = call.getString("path");
        if (id == null || url == null || path == null || path.contains("..")) {
            call.reject("Parametres invalides");
            return;
        }
        askNotifications();
        Task t = tasks.get(id);
        if (t == null) {
            t = new Task();
            t.id = id;
            tasks.put(id, t);
        }
        t.url = url;
        t.title = call.getString("title", "GUI FLIX");
        t.file = new File(getContext().getFilesDir(), path);
        run(t);
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        String id = call.getString("id");
        Task t = id == null ? null : tasks.get(id);
        if (t != null) t.pause = true;
        call.resolve();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String id = call.getString("id");
        String path = call.getString("path");
        if (id == null) {
            call.resolve();
            return;
        }
        Task t = tasks.get(id);
        if (t != null && t.running) {
            t.cancel = true;
        } else {
            File f = null;
            if (t != null && t.file != null) f = t.file;
            else if (path != null && !path.contains("..")) f = new File(getContext().getFilesDir(), path);
            if (f != null) f.delete();
            tasks.remove(id);
            clearNote(id);
            emit(id, "cancelled", 0, 0, null);
        }
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        String id = call.getString("id");
        String path = call.getString("path");
        Task t = id == null ? null : tasks.get(id);
        JSObject o = new JSObject();
        if (t != null && t.running) {
            o.put("running", true);
            o.put("done", t.done);
            o.put("total", t.total);
        } else {
            long len = 0;
            if (path != null && !path.contains("..")) {
                File f = new File(getContext().getFilesDir(), path);
                if (f.exists()) len = f.length();
            }
            o.put("running", false);
            o.put("done", len);
        }
        call.resolve(o);
    }

    private void run(final Task t) {
        if (t.running) return;
        t.running = true;
        t.pause = false;
        t.cancel = false;
        new Thread(() -> {
            HttpURLConnection c = null;
            RandomAccessFile out = null;
            InputStream in = null;
            try {
                File parent = t.file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                long start = t.file.exists() ? t.file.length() : 0;
                c = (HttpURLConnection) new URL(t.url).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) GUIFLIX");
                if (start > 0) c.setRequestProperty("Range", "bytes=" + start + "-");
                int code = c.getResponseCode();
                if (code == 416 && start > 0) {
                    t.done = start;
                    if (t.total <= 0) t.total = start;
                    finish(t);
                    return;
                }
                if (code != 200 && code != 206) throw new Exception("Le serveur a repondu " + code);
                boolean partial = code == 206;
                if (!partial) start = 0;
                long len = c.getContentLengthLong();
                t.total = len > 0 ? start + len : 0;
                t.done = start;
                out = new RandomAccessFile(t.file, "rw");
                if (!partial) out.setLength(0);
                out.seek(start);
                in = c.getInputStream();
                byte[] buf = new byte[65536];
                int n;
                long last = 0;
                while ((n = in.read(buf)) != -1) {
                    if (t.cancel || t.pause) break;
                    out.write(buf, 0, n);
                    t.done += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 700) {
                        last = now;
                        emit(t.id, "run", t.done, t.total, null);
                        note(t, true);
                    }
                }
                out.close();
                out = null;
                if (t.cancel) {
                    t.file.delete();
                    tasks.remove(t.id);
                    clearNote(t.id);
                    emit(t.id, "cancelled", 0, 0, null);
                } else if (t.pause) {
                    emit(t.id, "paused", t.done, t.total, null);
                    noteText(t, "En pause", false, 0, false);
                } else if (t.total > 0 && t.done < t.total) {
                    throw new Exception("Connexion interrompue");
                } else {
                    finish(t);
                }
            } catch (Exception e) {
                String m = e.getMessage() == null ? "Erreur reseau" : e.getMessage();
                emit(t.id, "error", t.done, t.total, m);
                noteText(t, "Interrompu : ouvre l'application pour reprendre", false, 0, false);
            } finally {
                try {
                    if (out != null) out.close();
                } catch (Exception ignored) {
                }
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) {
                }
                if (c != null) c.disconnect();
                t.running = false;
            }
        }).start();
    }

    private void finish(Task t) {
        emit(t.id, "done", t.done, t.total, null);
        noteText(t, "Telecharge. Disponible dans GUI FLIX.", false, 100, false);
        tasks.remove(t.id);
    }

    private void emit(String id, String state, long done, long total, String msg) {
        JSObject o = new JSObject();
        o.put("id", id);
        o.put("state", state);
        o.put("done", done);
        o.put("total", total);
        if (msg != null) o.put("message", msg);
        notifyListeners("progress", o);
    }

    private void note(Task t, boolean ongoing) {
        int pct = t.total > 0 ? (int) Math.min(100, (t.done * 100) / t.total) : 0;
        String txt = t.total > 0 ? pct + " %  (" + (t.done / 1048576) + " / " + (t.total / 1048576) + " Mo)" : (t.done / 1048576) + " Mo";
        noteText(t, txt, ongoing, pct, t.total <= 0);
    }

    private void noteText(Task t, String text, boolean ongoing, int pct, boolean indeterminate) {
        try {
            Context c = getContext();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Telechargements", NotificationManager.IMPORTANCE_LOW));
            }
            Intent li = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            PendingIntent pi = li == null ? null : PendingIntent.getActivity(c, 0, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
                .setContentTitle(t.title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
            if (pi != null) b.setContentIntent(pi);
            if (ongoing) b.setProgress(100, pct, indeterminate);
            nm.notify(nid(t.id), b.build());
        } catch (Exception ignored) {
        }
    }

    private void clearNote(String id) {
        try {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(nid(id));
        } catch (Exception ignored) {
        }
    }

    private void askNotifications() {
        if (asked || Build.VERSION.SDK_INT < 33 || getActivity() == null) return;
        asked = true;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7001);
        }
    }
}
