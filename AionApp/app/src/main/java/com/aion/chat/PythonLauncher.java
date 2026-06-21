package com.aion.chat;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import java.io.*;
import java.nio.file.*;

public class PythonLauncher extends Activity {
    private static final int PORT = 8080;
    private Process pythonProcess;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);
        
        TextView log = new TextView(this);
        log.setTextSize(12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        layout.addView(scroll);
        setContentView(layout);
        
        append(log, "Aion Backend starting...");
        
        File pythonDir = new File(getFilesDir(), "python");
        File pythonBin = new File(pythonDir, "python3.13");
        
        if (!pythonBin.exists()) {
            append(log, "Extracting Python...");
            pythonDir.mkdirs();
            copyAssetFolder(getAssets(), "python", pythonDir);
            pythonBin.setExecutable(true, false);
            append(log, "Python extracted: " + pythonBin.getAbsolutePath());
        }
        
        append(log, "Python binary: " + pythonBin.exists() + " (" + pythonBin.length() + " bytes)");
        
        File aionDir = new File(getFilesDir(), "aion-chat");
        if (!aionDir.exists() || aionDir.list().length < 5) {
            append(log, "Extracting aion-chat...");
            aionDir.mkdirs();
            copyAssetFolder(getAssets(), "aion-chat", aionDir);
        }
        append(log, "Aion dir: " + aionDir.getAbsolutePath());
        
        File sitePackages = new File(pythonDir, "lib/python3.13/site-packages");
        String pythonPath = pythonDir.getAbsolutePath() + ":" + sitePackages.getAbsolutePath();
        append(log, "PYTHONPATH: " + pythonPath);
        
        append(log, "Starting backend on port " + PORT + "...");
        
        try {
            ProcessBuilder pb = new ProcessBuilder(
                pythonBin.getAbsolutePath(),
                "-m", "uvicorn",
                "main:app",
                "--host", "127.0.0.1",
                "--port", String.valueOf(PORT)
            );
            pb.environment().put("PYTHONPATH", pythonPath);
            pb.environment().put("PYTHONHOME", pythonDir.getAbsolutePath());
            pb.environment().put("TERM", "xterm");
            pb.directory(aionDir);
            pb.redirectErrorStream(true);
            
            pythonProcess = pb.start();
            
            BufferedReader br = new BufferedReader(new InputStreamReader(pythonProcess.getInputStream()));
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < 20) {
                append(log, "[py] " + line);
                count++;
            }
            
            append(log, "Backend started, pid=" + pythonProcess.hashCode());
            Thread.sleep(3000);
            
            if (pythonProcess.isAlive()) {
                append(log, "SUCCESS: Backend running!");
                android.content.Intent intent = new android.content.Intent(this, WebViewActivity.class);
                intent.putExtra("url", "http://127.0.0.1:" + PORT + "/chat");
                startActivity(intent);
                finish();
            } else {
                append(log, "ERROR: Process died, exit=" + pythonProcess.exitValue());
            }
        } catch (Exception e) {
            append(log, "ERROR: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void append(TextView tv, String msg) {
        final String m = msg;
        runOnUiThread(() -> tv.append(m + "\\n"));
    }
    
    private void copyAssetFolder(android.content.res.AssetManager am, String src, File dst) {
        try {
            String[] files = am.list(src);
            if (files == null || files.length == 0) {
                copyAssetFile(am, src, new File(dst, new java.io.File(src).getName()));
            } else {
                dst.mkdirs();
                for (String f : files) {
                    copyAssetFolder(am, src + "/" + f, dst);
                }
            }
        } catch (IOException e) {}
    }
    
    private void copyAssetFile(android.content.res.AssetManager am, String src, File dst) {
        try {
            InputStream is = am.open(src);
            Files.copy(is, dst.toPath());
            is.close();
        } catch (IOException e) {}
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pythonProcess != null && pythonProcess.isAlive()) {
            pythonProcess.destroy();
        }
    }
}
