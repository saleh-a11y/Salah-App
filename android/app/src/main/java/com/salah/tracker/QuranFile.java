package com.salah.tracker;

import android.net.Uri;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the colour-coded Qur'an PDF once and keeps it in the app's own storage
 * (kept through app updates, removed on uninstall). Resumes an interrupted download.
 */
@CapacitorPlugin(name = "QuranFile")
public class QuranFile extends Plugin {

    private static volatile boolean busy = false;

    private File file() {
        File dir = new File(getContext().getFilesDir(), "quran");
        dir.mkdirs();
        return new File(dir, "colour.pdf");
    }

    private File part() { return new File(file().getPath() + ".part"); }

    private JSObject info() {
        File f = file();
        JSObject r = new JSObject();
        r.put("have", f.exists() && f.length() > 0);
        r.put("size", f.exists() ? f.length() : 0);
        r.put("partial", part().exists() ? part().length() : 0);
        r.put("busy", busy);
        if (f.exists()) r.put("uri", Uri.fromFile(f).toString());
        return r;
    }

    @PluginMethod
    public void status(PluginCall call) { call.resolve(info()); }

    @PluginMethod
    public void remove(PluginCall call) {
        if (!busy) { file().delete(); part().delete(); }
        call.resolve(info());
    }

    @PluginMethod
    public void download(PluginCall call) {
        final String url = call.getString("url");
        if (url == null || !url.startsWith("https://")) { call.reject("A secure download link is needed"); return; }
        if (busy) { call.reject("busy"); return; }
        busy = true;
        new Thread(() -> {
            File p = part();
            HttpURLConnection conn = null;
            try {
                long have = p.exists() ? p.length() : 0;
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
                conn.connect();
                int code = conn.getResponseCode();
                boolean resume = code == 206 && have > 0;
                if (code == 416 && have > 0) { // the part file is already complete
                    resume = true;
                } else if (code < 200 || code > 299) {
                    throw new Exception("Download failed (" + code + ")");
                }
                if (!resume) have = 0;
                long len = code == 416 ? 0 : conn.getContentLengthLong();
                long total = len > 0 ? have + len : -1;
                long done = have;
                int lastPct = -1;
                if (code != 416) {
                    try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(p, resume)) {
                        byte[] buf = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            os.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                int pct = (int) (done * 100 / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    JSObject ev = new JSObject();
                                    ev.put("percent", pct);
                                    ev.put("done", done);
                                    ev.put("total", total);
                                    notifyListeners("progress", ev);
                                }
                            }
                        }
                    }
                }
                if (total > 0 && p.length() != total) throw new Exception("The download was incomplete");
                if (p.length() < 1024 * 1024) throw new Exception("The download was incomplete");
                File f = file();
                if (f.exists()) f.delete();
                if (!p.renameTo(f)) throw new Exception("Couldn't save the file");
                busy = false;
                call.resolve(info());
            } catch (Exception e) {
                busy = false; // keep the part file so the next try resumes
                call.reject(e.getMessage() != null ? e.getMessage() : "Download failed");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
