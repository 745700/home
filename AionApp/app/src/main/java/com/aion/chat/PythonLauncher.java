package com.aion.chat;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import java.io.*;
import java.nio.file.*;

public class PythonLauncher extends Activity {
    private Process backend;
    private final StringBuilder sb = new StringBuilder();
    private TextView log;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        log = new TextView(this);
        log.setTextSize(11);
        log.setPadding(16, 16, 16, 16);
        ScrollView sc = new ScrollView(this);
        sc.addView(log);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.addView(sc);
        setContentView(ll);
        append("Aion starting...");
        new Thread(this::boot).start();
    }

    private void boot() {
        try {
            File fd = getFilesDir();
            File pyDir = new File(fd, "python");
            File aionDir = new File(fd, "aion-chat");
            File pyBin = new File(pyDir, "python3.13");

            if (!pyBin.exists()) {
                append("Extracting Python...");
                pyDir.mkdirs();
                copyAssets("python", pyDir);
                pyBin.setExecutable(true, false);
                append("Python: " + (pyBin.exists() ? pyBin.length() + " bytes" : "MISSING"));
            } else {
                append("Python: " + pyBin.length() + " bytes");
            }

            if (!aionDir.exists() || aionDir.list().length < 3) {
                append("Extracting backend...");
                aionDir.mkdirs();
                copyAssets("aion-chat", aionDir);
            }

            append("Starting server...");
            File sitePkgs = new File(pyDir, "lib/python3.13/site-packages");
            ProcessBuilder pb = new ProcessBuilder(
                pyBin.getAbsolutePath(), "-m", "uvicorn",
                "main:app", "--host", "127.0.0.1", "--port", "8080"
            );
            pb.environment().put("PYTHONPATH", pyDir.getAbsolutePath() + ":" + sitePkgs.getAbsolutePath());
            pb.environment().put("TERM", "xterm");
            pb.directory(aionDir);
            pb.redirectErrorStream(true);
            backend = pb.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(backend.getInputStream()));
            String l;
            int n = 0;
            while ((l = r.readLine()) != null && n++ < 15) {
                append("[srv] " + l);
            }

            sleep(3000);
            if (backend.isAlive()) {
                append("Server OK!");
                sleep(500);
                runOnUiThread(() -> {
                    try {
                        android.content.Intent i = new android.content.Intent(this, Class.forName("com.aion.chat.WebViewActivity"));
                        i.putExtra("url", "http://127.0.0.1:8080/chat");
                        startActivity(i);
                        finish();
                    } catch (Exception e) {
                        append("Open manually");
                    }
                });
            } else {
                append("ERROR: exit=" + backend.exitValue());
            }
        } catch (Exception e) {
            append("FATAL: " + e.getMessage());
        }
    }

    private void append(String msg) {
        sb.append(msg).append("\n");
        runOnUiThread(() -> {
            if (log != null) log.setText(sb.toString());
        });
    }

    private void copyAssets(String src, File dst) {
        try {
            String[] fs = getAssets().list(src);
            if (fs == null || fs.length == 0) {
                File out = new File(dst, new java.io.File(src).getName());
                try (InputStream i = getAssets().open(src)) {
                    Files.copy(i, out.toPath());
                }
            } else {
                dst.mkdirs();
                for (String f : fs) {
                    copyAssets(src + "/" + f, dst);
                }
            }
        } catch (IOException e) {
            append("copy error: " + e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (backend != null && backend.isAlive()) backend.destroy();
    }
}
