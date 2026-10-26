package com.example.attentionapp;

import android.webkit.WebView;

public class TabModel {
    private String title;
    private String url;
    private boolean isStudyTab;
    private WebView webView;

    public TabModel(String title, String url, boolean isStudyTab, WebView webView) {
        this.title = title;
        this.url = url;
        this.isStudyTab = isStudyTab;
        this.webView = webView;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isStudyTab() {
        return isStudyTab;
    }

    public void setStudyTab(boolean studyTab) {
        isStudyTab = studyTab;
    }

    public WebView getWebView() {
        return webView;
    }

    public void setWebView(WebView webView) {
        this.webView = webView;
    }
}
