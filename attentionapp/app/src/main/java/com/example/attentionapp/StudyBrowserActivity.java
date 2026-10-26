package com.example.attentionapp;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

public class StudyBrowserActivity extends AppCompatActivity {

    private FrameLayout browserWebViewContainer;
    private ProgressBar pbBrowserLoading;
    private TabLayout browserTabLayout;
    private ImageButton btnBrowserClose, btnBrowserAddTab, btnBrowserBack, btnBrowserForward;
    private EditText etBrowserUrl;
    private Button btnBrowserGo;

    private final List<TabModel> tabList = new ArrayList<>();
    private int currentTabIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_study_browser);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.studyBrowserRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupListeners();

        // Initial tab
        addNewTab("Research", "https://en.wikipedia.org");
    }

    private void initViews() {
        browserWebViewContainer = findViewById(R.id.browserWebViewContainer);
        pbBrowserLoading = findViewById(R.id.pbBrowserLoading);
        browserTabLayout = findViewById(R.id.browserTabLayout);
        btnBrowserClose = findViewById(R.id.btnBrowserClose);
        btnBrowserAddTab = findViewById(R.id.btnBrowserAddTab);
        btnBrowserBack = findViewById(R.id.btnBrowserBack);
        btnBrowserForward = findViewById(R.id.btnBrowserForward);
        etBrowserUrl = findViewById(R.id.etBrowserUrl);
        btnBrowserGo = findViewById(R.id.btnBrowserGo);
    }

    private void setupListeners() {
        btnBrowserClose.setOnClickListener(v -> finish());

        btnBrowserAddTab.setOnClickListener(v -> {
            int tabNum = tabList.size() + 1;
            addNewTab("Tab " + tabNum, "https://en.wikipedia.org");
        });

        browserTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                if (pos >= 0 && pos < tabList.size() && pos != currentTabIndex) {
                    switchTab(pos);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        btnBrowserGo.setOnClickListener(v -> navigateUrl());
        etBrowserUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateUrl();
                return true;
            }
            return false;
        });

        btnBrowserBack.setOnClickListener(v -> {
            WebView wv = getCurrentWebView();
            if (wv != null && wv.canGoBack()) wv.goBack();
        });

        btnBrowserForward.setOnClickListener(v -> {
            WebView wv = getCurrentWebView();
            if (wv != null && wv.canGoForward()) wv.goForward();
        });
    }

    private void addNewTab(String title, String url) {
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    pbBrowserLoading.setVisibility(View.VISIBLE);
                } else {
                    pbBrowserLoading.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String t) {
                super.onReceivedTitle(view, t);
                if (t != null && !t.isEmpty()) {
                    for (int i = 0; i < tabList.size(); i++) {
                        if (tabList.get(i).getWebView() == view) {
                            tabList.get(i).setTitle(t);
                            TabLayout.Tab tab = browserTabLayout.getTabAt(i);
                            if (tab != null) {
                                String shortTitle = t.length() > 16 ? t.substring(0, 14) + "..." : t;
                                tab.setText(shortTitle);
                            }
                            break;
                        }
                    }
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                super.onPageStarted(view, u, favicon);
                if (getCurrentWebView() == view) {
                    etBrowserUrl.setText(u);
                }
            }
        });

        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        for (TabModel tm : tabList) {
            if (tm.getWebView() != null) tm.getWebView().setVisibility(View.GONE);
        }

        browserWebViewContainer.addView(webView);
        webView.setVisibility(View.VISIBLE);

        TabModel tab = new TabModel(title, url, false, webView);
        tabList.add(tab);
        currentTabIndex = tabList.size() - 1;

        TabLayout.Tab newTab = browserTabLayout.newTab();
        newTab.setText(title);
        browserTabLayout.addTab(newTab, true);

        webView.loadUrl(url);
        etBrowserUrl.setText(url);
    }

    private void switchTab(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= tabList.size()) return;

        // If in session, record a tab switch!
        if (SessionManager.getInstance().isSessionActive()) {
            SessionManager.getInstance().recordTabSwitch(10);
        }

        for (int i = 0; i < tabList.size(); i++) {
            WebView wv = tabList.get(i).getWebView();
            if (wv != null) {
                wv.setVisibility(i == targetIndex ? View.VISIBLE : View.GONE);
            }
        }
        currentTabIndex = targetIndex;
        WebView currentWv = getCurrentWebView();
        if (currentWv != null && currentWv.getUrl() != null) {
            etBrowserUrl.setText(currentWv.getUrl());
        }
    }

    private WebView getCurrentWebView() {
        if (currentTabIndex >= 0 && currentTabIndex < tabList.size()) {
            return tabList.get(currentTabIndex).getWebView();
        }
        return null;
    }

    private void navigateUrl() {
        String input = etBrowserUrl.getText().toString().trim();
        if (input.isEmpty()) return;

        String targetUrl;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            targetUrl = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            targetUrl = "https://" + input;
        } else {
            targetUrl = "https://duckduckgo.com/?q=" + android.net.Uri.encode(input);
        }

        WebView wv = getCurrentWebView();
        if (wv != null) {
            wv.loadUrl(targetUrl);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (TabModel tm : tabList) {
            if (tm.getWebView() != null) {
                tm.getWebView().destroy();
            }
        }
        tabList.clear();
    }
}
