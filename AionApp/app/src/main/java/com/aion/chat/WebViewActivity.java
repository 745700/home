package com.aion.chat;

import android.os.Bundle;
import android.webkit.*;
import android.widget.Toast;

public class WebViewActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        WebView wv = new WebView(this);
        setContentView(wv);

        String url = getIntent().getStringExtra("url");
        if (url == null) url = "http://127.0.0.1:8080/chat";

        wv.loadUrl(url);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, int e, String d) {
                Toast.makeText(WebViewActivity.this, d, Toast.LENGTH_SHORT).show();
            }
        });

        Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
    }
}
