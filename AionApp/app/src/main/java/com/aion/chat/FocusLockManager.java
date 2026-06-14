package com.aion.chat;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * 全屏专注锁管理器
 *
 * 功能：
 * - 显示全屏半透明遮挡层（手机"变砖"）
 * - 锁屏中心显示 AI 回复内容
 * - 底部交互区：用户可发送 1 条消息（≤15字）
 * - 倒计时后台运行，解锁后通过 JS 回调通知前端
 *
 * 使用方法：
 * FocusLockManager manager = new FocusLockManager(activity, webView);
 * manager.lock(30);        // 锁定 30 分钟
 * manager.unlock();        // 提前解锁
 * manager.isLocked();      // 是否在锁定中
 */
public class FocusLockManager {

    private final WeakReference<Activity> activityRef;
    private final WeakReference<android.webkit.WebView> webViewRef;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 状态
    private boolean isLocked = false;
    private boolean userCanSend = true;     // 用户是否可以发消息
    private int remainingSeconds = 0;       // 剩余秒数
    private int totalSeconds = 0;            // 总秒数
    private String aiReplyText = "";        // 显示在锁屏上的 AI 回复
    private Runnable countdownRunnable = null;

    // UI
    private WindowManager windowManager = null;
    private View lockView = null;           // 全屏遮挡层
    private TextView aiReplyTv = null;      // 锁屏上显示的 AI 回复
    private TextView hintTv = null;         // 提示文字
    private EditText inputEt = null;        // 消息输入框
    private TextView charCountTv = null;    // 字符计数
    private TextView sendBtn = null;        // 发送按钮
    private TextView sentStatusTv = null;   // "已发送" 状态
    private View inputArea = null;          // 输入区域

    // WebView 回调
    private String onLockedJsCallback = "";
    private String onUnlockedJsCallback = "";
    private String onUserMessageJsCallback = "";

    private static final int MAX_CHARS = 15;

    public FocusLockManager(Activity activity, android.webkit.WebView webView) {
        this.activityRef = new WeakReference<>(activity);
        this.webViewRef = new WeakReference<>(webView);
    }

    // ── 公开 API ──

    /** 锁定 minutes 分钟（最大60分钟）*/
    public void lock(int minutes) {
        lock(minutes, "");
    }

    /** 锁定 minutes 分钟，并显示 aiMessage 在锁屏上 */
    public void lock(int minutes, String aiMessage) {
        Activity activity = activityRef.get();
        android.webkit.WebView webView = webViewRef.get();
        if (activity == null || webView == null) return;
        if (isLocked) return;

        // 限制最大60分钟
        int actualMinutes = Math.min(Math.max(minutes, 1), 60);
        totalSeconds = actualMinutes * 60;
        remainingSeconds = totalSeconds;
        userCanSend = true;
        aiReplyText = (aiMessage != null) ? aiMessage : "";
        isLocked = true;

        activity.runOnUiThread(() -> {
            showLockOverlay();
            startCountdown();
            fireOnLockedCallback();
        });
    }

    /** 提前解锁 */
    public void unlock() {
        if (!isLocked) return;
        Activity activity = activityRef.get();
        if (activity == null) return;

        activity.runOnUiThread(() -> {
            stopCountdown();
            hideLockOverlay();
            isLocked = false;
            userCanSend = true;
            aiReplyText = "";
            fireOnUnlockedCallback();
        });
    }

    public boolean isLocked() {
        return isLocked;
    }

    public boolean canUserSendMessage() {
        return isLocked && userCanSend;
    }

    /** 用户发送了一条消息 */
    public void onUserMessageSent(String message) {
        userCanSend = false;
        aiReplyText = ""; // 清空旧 AI 回复，发送后等待新回复
        updateInputAreaState();
        fireOnUserMessageCallback(message);
    }

    /** 更新锁屏上显示的 AI 回复 */
    public void setAiReplyText(String text) {
        aiReplyText = (text != null) ? text : "";
        Activity activity = activityRef.get();
        if (activity == null || !isLocked) return;
        activity.runOnUiThread(() -> {
            if (aiReplyTv != null) {
                aiReplyTv.setText(aiReplyText);
                aiReplyTv.setVisibility(aiReplyText.isEmpty() ? View.GONE : View.VISIBLE);
            }
            // 同时通知 JS 侧更新 focus.html 的锁屏状态条
            fireOnAIMessageCallback(aiReplyText);
        });
    }

    private void fireOnAIMessageCallback(String text) {
        android.webkit.WebView webView = webViewRef.get();
        if (webView == null) return;
        String escaped = (text != null ? text : "")
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "");
        String js = "try{if(window.updateFocusLockAIMessage)window.updateFocusLockAIMessage('" + escaped + "');}catch(e){console.error('[FocusLock]',e)}";
        webView.evaluateJavascript(js, null);
    }

    public void setOnLockedCallback(String jsCode) {
        this.onLockedJsCallback = (jsCode != null) ? jsCode : "";
    }

    public void setOnUnlockedCallback(String jsCode) {
        this.onUnlockedJsCallback = (jsCode != null) ? jsCode : "";
    }

    public void setOnUserMessageCallback(String jsCode) {
        this.onUserMessageJsCallback = (jsCode != null) ? jsCode : "";
    }

    public void destroy() {
        stopCountdown();
        Activity activity = activityRef.get();
        if (activity != null) {
            activity.runOnUiThread(this::hideLockOverlay);
        }
        isLocked = false;
    }

    // ── 私有方法 ──

    private void showLockOverlay() {
        Activity activity = activityRef.get();
        android.webkit.WebView webView = webViewRef.get();
        if (activity == null) return;

        windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);

        // 全屏遮挡层参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;

        // 构建锁屏界面
        lockView = buildLockView();

        try {
            windowManager.addView(lockView, params);
        } catch (Exception e) {
            e.printStackTrace();
            lockView = null;
        }
    }

    private View buildLockView() {
        Activity activity = activityRef.get();
        if (activity == null) return null;

        // 根容器：半透明黑色背景
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.parseColor("#CC1a1a2e"));

        // 顶层：中央内容（垂直排列）
        LinearLayout centerLayout = new LinearLayout(activity);
        centerLayout.setOrientation(LinearLayout.VERTICAL);
        centerLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        centerLayout.setPadding(60, 0, 60, 0);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        centerLp.gravity = Gravity.CENTER;
        root.addView(centerLayout, centerLp);

        // 顶部锁图标
        ImageView lockIcon = new ImageView(activity);
        lockIcon.setImageResource(android.R.drawable.ic_lock_lock);
        lockIcon.setColorFilter(Color.WHITE);
        int iconSize = dp(56);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.gravity = Gravity.CENTER;
        iconLp.bottomMargin = dp(20);
        centerLayout.addView(lockIcon, iconLp);

        // 标题
        TextView titleTv = new TextView(activity);
        titleTv.setText("专注中");
        titleTv.setTextColor(Color.parseColor("#80FFFFFF"));
        titleTv.setTextSize(13);
        titleTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleLp.gravity = Gravity.CENTER;
        titleLp.bottomMargin = dp(16);
        centerLayout.addView(titleTv, titleLp);

        // AI 回复内容（多行，可滚动）
        aiReplyTv = new TextView(activity);
        aiReplyTv.setText(aiReplyText);
        aiReplyTv.setTextColor(Color.WHITE);
        aiReplyTv.setTextSize(16);
        aiReplyTv.setTypeface(Typeface.DEFAULT);
        aiReplyTv.setGravity(Gravity.CENTER);
        aiReplyTv.setLineSpacing(dp(4), 1.4f);
        aiReplyTv.setVisibility(aiReplyText.isEmpty() ? View.GONE : View.VISIBLE);
        int maxLines = 8;
        aiReplyTv.setMaxLines(maxLines);
        aiReplyTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams replyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        replyLp.gravity = Gravity.CENTER;
        replyLp.bottomMargin = dp(24);
        centerLayout.addView(aiReplyTv, replyLp);

        // 输入区域容器
        inputArea = buildInputArea(root);

        // 返回按钮（调试用，实际不应该显示）
        // 用户无法主动解锁，这里不做任何解锁按钮

        return root;
    }

    private View buildInputArea(FrameLayout parent) {
        Activity activity = activityRef.get();
        if (activity == null) return null;

        // 底部输入区域
        LinearLayout inputRoot = new LinearLayout(activity);
        inputRoot.setOrientation(LinearLayout.VERTICAL);
        inputRoot.setGravity(Gravity.CENTER_HORIZONTAL);
        inputRoot.setPadding(dp(20), dp(12), dp(20), dp(32));
        inputRoot.setBackgroundColor(Color.parseColor("#33FFFFFF"));

        FrameLayout.LayoutParams inputLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        inputLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        inputLp.bottomMargin = dp(40);
        parent.addView(inputRoot, inputLp);

        // 提示文字
        hintTv = new TextView(activity);
        hintTv.setText("专注中，你可以发送一条消息给 AI");
        hintTv.setTextColor(Color.parseColor("#80FFFFFF"));
        hintTv.setTextSize(12);
        hintTv.setGravity(Gravity.CENTER);
        hintTv.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        hintLp.gravity = Gravity.CENTER;
        hintLp.bottomMargin = dp(8);
        inputRoot.addView(hintTv, hintLp);

        // 输入框 + 发送按钮行
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputRoot.addView(row, rowLp);

        // 输入框
        inputEt = new EditText(activity);
        inputEt.setHint("说点什么...");
        inputEt.setHintTextColor(Color.parseColor("#80FFFFFF"));
        inputEt.setTextColor(Color.WHITE);
        inputEt.setTextSize(15);
        inputEt.setPadding(dp(12), dp(8), dp(12), dp(8));
        inputEt.setBackgroundColor(Color.parseColor("#44FFFFFF"));
        inputEt.setMaxLines(2);
        inputEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        inputEt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_CHARS)});
        inputEt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 字符计数
        charCountTv = new TextView(activity);
        charCountTv.setText("0/" + MAX_CHARS);
        charCountTv.setTextColor(Color.parseColor("#60FFFFFF"));
        charCountTv.setTextSize(11);
        charCountTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams charLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        charLp.leftMargin = dp(6);
        charLp.rightMargin = dp(6);
        charLp.gravity = Gravity.CENTER_VERTICAL;

        // 发送按钮
        sendBtn = new TextView(activity);
        sendBtn.setText("发送");
        sendBtn.setTextColor(Color.WHITE);
        sendBtn.setTextSize(14);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        sendBtn.setBackgroundColor(Color.parseColor("#ff8359"));
        sendBtn.setClickable(true);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sendLp.gravity = Gravity.CENTER_VERTICAL;
        sendLp.leftMargin = dp(6);

        // 已发送状态（初始隐藏）
        sentStatusTv = new TextView(activity);
        sentStatusTv.setText("✓ 已发送");
        sentStatusTv.setTextColor(Color.parseColor("#80FFFFFF"));
        sentStatusTv.setTextSize(14);
        sentStatusTv.setVisibility(View.GONE);
        sentStatusTv.setGravity(Gravity.CENTER);
        sentStatusTv.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams sentLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sentLp.gravity = Gravity.CENTER_VERTICAL;
        sentLp.leftMargin = dp(6);

        row.addView(inputEt);
        row.addView(charCountTv);
        row.addView(sendBtn);
        row.addView(sentStatusTv);

        sendBtn.setVisibility(View.VISIBLE);
        sentStatusTv.setVisibility(View.GONE);

        // 监听输入
        inputEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                int len = s.length();
                if (charCountTv != null) charCountTv.setText(len + "/" + MAX_CHARS);
                if (len > 0 && sendBtn != null) {
                    sendBtn.setBackgroundColor(Color.parseColor("#ff8359"));
                    sendBtn.setClickable(true);
                }
            }
        });

        // 发送按钮点击
        sendBtn.setOnClickListener(v -> {
            String text = (inputEt != null) ? inputEt.getText().toString().trim() : "";
            if (text.isEmpty()) return;
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
            onSendMessage(text);
        });

        updateInputAreaState();
        return inputRoot;
    }

    private void onSendMessage(String message) {
        if (!userCanSend) return;
        userCanSend = false;
        updateInputAreaState();
        onUserMessageSent(message);
    }

    private void updateInputAreaState() {
        if (inputEt == null || hintTv == null || sendBtn == null || sentStatusTv == null) return;

        if (userCanSend) {
            inputEt.setEnabled(true);
            inputEt.setHint("说点什么...");
            hintTv.setText("专注中，你可以发送一条消息给 AI");
            sendBtn.setVisibility(View.VISIBLE);
            sentStatusTv.setVisibility(View.GONE);
            hintTv.setVisibility(View.VISIBLE);
        } else {
            inputEt.setEnabled(false);
            inputEt.setHint("已发送，等待 AI 回复...");
            inputEt.setText("");
            hintTv.setText("专注中，无法继续发送消息");
            sendBtn.setVisibility(View.GONE);
            sentStatusTv.setVisibility(View.VISIBLE);
        }
    }

    private void startCountdown() {
        stopCountdown();
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isLocked) return;
                remainingSeconds--;
                if (remainingSeconds <= 0) {
                    unlock();
                } else {
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.postDelayed(countdownRunnable, 1000);
    }

    private void stopCountdown() {
        if (countdownRunnable != null) {
            handler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
    }

    private void hideLockOverlay() {
        if (windowManager != null && lockView != null) {
            try {
                windowManager.removeView(lockView);
            } catch (Exception e) {
                e.printStackTrace();
            }
            lockView = null;
        }
    }

    private void fireOnLockedCallback() {
        android.webkit.WebView webView = webViewRef.get();
        if (webView == null || onLockedJsCallback.isEmpty()) return;
        String js = "try{" + onLockedJsCallback + "}catch(e){console.error('[FocusLock]',e)}";
        webView.evaluateJavascript(js, null);
    }

    private void fireOnUnlockedCallback() {
        android.webkit.WebView webView = webViewRef.get();
        if (webView == null || onUnlockedJsCallback.isEmpty()) return;
        String js = "try{" + onUnlockedJsCallback + "}catch(e){console.error('[FocusLock]',e)}";
        webView.evaluateJavascript(js, null);
    }

    private void fireOnUserMessageCallback(String message) {
        android.webkit.WebView webView = webViewRef.get();
        if (webView == null || onUserMessageJsCallback.isEmpty()) return;
        String escaped = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
        String js = "try{" + onUserMessageJsCallback.replace("'__MSG__'", "'" + escaped + "'") + "}catch(e){console.error('[FocusLock]',e)}";
        webView.evaluateJavascript(js, null);
    }

    private int dp(int px) {
        Activity activity = activityRef.get();
        if (activity == null) return px;
        return (int) (px * activity.getResources().getDisplayMetrics().density);
    }
}
