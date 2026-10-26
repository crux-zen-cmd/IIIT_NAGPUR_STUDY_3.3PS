package com.example.attentionapp;

import android.webkit.JavascriptInterface;

public class AttentionBridge {

    public interface DomEventListener {
        void onDomVisibilityChanged(boolean isHidden, String title, String url);
        void onWindowBlur(String title);
        void onWindowFocus(String title);
        void onUserActivity();
        void onTabSwitchTriggered(String tabName, String reason);
    }

    private final DomEventListener listener;

    public AttentionBridge(DomEventListener listener) {
        this.listener = listener;
    }

    @JavascriptInterface
    public void onDomVisibilityChanged(boolean isHidden, String title, String url) {
        if (listener != null) {
            listener.onDomVisibilityChanged(isHidden, title != null ? title : "", url != null ? url : "");
        }
    }

    @JavascriptInterface
    public void onWindowBlur(String title) {
        if (listener != null) {
            listener.onWindowBlur(title != null ? title : "");
        }
    }

    @JavascriptInterface
    public void onWindowFocus(String title) {
        if (listener != null) {
            listener.onWindowFocus(title != null ? title : "");
        }
    }

    @JavascriptInterface
    public void onUserActivity() {
        if (listener != null) {
            listener.onUserActivity();
        }
    }

    @JavascriptInterface
    public void onTabSwitchTriggered(String tabName, String reason) {
        if (listener != null) {
            listener.onTabSwitchTriggered(tabName != null ? tabName : "Unknown", reason != null ? reason : "");
        }
    }
}
