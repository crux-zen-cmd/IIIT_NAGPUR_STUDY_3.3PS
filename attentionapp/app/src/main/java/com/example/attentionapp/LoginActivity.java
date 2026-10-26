package com.example.attentionapp;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * LoginActivity — Midnight Aurora themed authentication gate.
 * Uses dummy auth stored in SharedPreferences (no network required).
 * Supports Sign In, Register, and Guest modes.
 */
public class LoginActivity extends AppCompatActivity {

    public static final String PREF_USER_FILE     = "attentionai_users";
    public static final String PREF_SESSION_FILE  = "attentionai_session";
    public static final String KEY_LOGGED_IN      = "logged_in";
    public static final String KEY_CURRENT_USER   = "current_username";
    public static final String KEY_DISPLAY_NAME   = "display_name";
    public static final String KEY_IS_GUEST       = "is_guest";

    // Tab state
    private boolean isLoginTab = true;

    // Views
    private TextView tabLogin, tabRegister;
    private EditText etLoginUsername, etLoginPassword;
    private Button btnSignIn, btnContinueGuest;
    private TextView tvLoginError, tvForgotPassword;
    private View cardLoginForm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If already logged in, skip directly to MainActivity
        SharedPreferences session = getSharedPreferences(PREF_SESSION_FILE, MODE_PRIVATE);
        if (session.getBoolean(KEY_LOGGED_IN, false)) {
            launchMain();
            return;
        }

        setContentView(R.layout.activity_login);
        bindViews();
        setupListeners();
        runEntranceAnimation();
    }

    private void bindViews() {
        tabLogin         = findViewById(R.id.tabLogin);
        tabRegister      = findViewById(R.id.tabRegister);
        etLoginUsername  = findViewById(R.id.etLoginUsername);
        etLoginPassword  = findViewById(R.id.etLoginPassword);
        btnSignIn        = findViewById(R.id.btnSignIn);
        btnContinueGuest = findViewById(R.id.btnContinueGuest);
        tvLoginError     = findViewById(R.id.tvLoginError);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
        cardLoginForm    = findViewById(R.id.cardLoginForm);
    }

    private void setupListeners() {
        tabLogin.setOnClickListener(v -> switchToLogin());
        tabRegister.setOnClickListener(v -> switchToRegister());

        btnSignIn.setOnClickListener(v -> {
            if (isLoginTab) handleSignIn();
            else handleRegister();
        });

        btnContinueGuest.setOnClickListener(v -> loginAsGuest());

        tvForgotPassword.setOnClickListener(v -> showError("Demo mode — no password recovery needed 😊"));
    }

    // ── Tab switching ────────────────────────────────────────────
    private void switchToLogin() {
        isLoginTab = true;
        tabLogin.setAlpha(1f);
        tabLogin.setTextColor(getColor(R.color.text_on_accent));
        tabLogin.setBackgroundResource(R.drawable.bg_button_gradient);
        tabRegister.setAlpha(0.6f);
        tabRegister.setTextColor(getColor(R.color.text_muted));
        tabRegister.setBackgroundColor(0x00000000);
        btnSignIn.setText("Sign In →");
        hideError();
    }

    private void switchToRegister() {
        isLoginTab = false;
        tabRegister.setAlpha(1f);
        tabRegister.setTextColor(getColor(R.color.text_on_accent));
        tabRegister.setBackgroundResource(R.drawable.bg_button_gradient);
        tabLogin.setAlpha(0.6f);
        tabLogin.setTextColor(getColor(R.color.text_muted));
        tabLogin.setBackgroundColor(0x00000000);
        btnSignIn.setText("Create Account →");
        hideError();
    }

    // ── Sign In ──────────────────────────────────────────────────
    private void handleSignIn() {
        String username = etLoginUsername.getText().toString().trim();
        String password = etLoginPassword.getText().toString().trim();

        if (TextUtils.isEmpty(username)) { showError("Please enter your username or email"); return; }
        if (TextUtils.isEmpty(password)) { showError("Please enter your password"); return; }

        // Look up stored account
        SharedPreferences users = getSharedPreferences(PREF_USER_FILE, MODE_PRIVATE);
        String storedPass = users.getString("pwd_" + username.toLowerCase(), null);

        if (storedPass == null) {
            showError("Account not found. Register first or use Guest.");
        } else if (!storedPass.equals(password)) {
            showError("Incorrect password. Try again.");
        } else {
            String displayName = users.getString("name_" + username.toLowerCase(), username);
            saveSession(username, displayName, false);
            launchMain();
        }
    }

    // ── Register ─────────────────────────────────────────────────
    private void handleRegister() {
        String username = etLoginUsername.getText().toString().trim();
        String password = etLoginPassword.getText().toString().trim();

        if (TextUtils.isEmpty(username)) { showError("Choose a username"); return; }
        if (username.length() < 3) { showError("Username must be at least 3 characters"); return; }
        if (TextUtils.isEmpty(password)) { showError("Choose a password"); return; }
        if (password.length() < 4) { showError("Password must be at least 4 characters"); return; }
        if (username.equalsIgnoreCase("guest")) { showError("\"guest\" is reserved. Pick another name!"); return; }

        SharedPreferences users = getSharedPreferences(PREF_USER_FILE, MODE_PRIVATE);
        String key = "pwd_" + username.toLowerCase();
        if (users.contains(key)) {
            showError("Username already taken. Sign in instead.");
            return;
        }

        // Store account
        users.edit()
            .putString(key, password)
            .putString("name_" + username.toLowerCase(), username)
            .apply();

        saveSession(username, username, false);
        launchMain();
    }

    // ── Guest ─────────────────────────────────────────────────────
    private void loginAsGuest() {
        saveSession("guest_" + System.currentTimeMillis() % 10000, "Guest Explorer", true);
        launchMain();
    }

    // ── Helpers ───────────────────────────────────────────────────
    private void saveSession(String username, String displayName, boolean isGuest) {
        getSharedPreferences(PREF_SESSION_FILE, MODE_PRIVATE).edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_CURRENT_USER, username)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putBoolean(KEY_IS_GUEST, isGuest)
            .apply();
    }

    private void launchMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void showError(String msg) {
        tvLoginError.setText(msg);
        tvLoginError.setVisibility(View.VISIBLE);
        ObjectAnimator shake = ObjectAnimator.ofFloat(cardLoginForm, "translationX",
            0f, -16f, 16f, -10f, 10f, -6f, 6f, 0f);
        shake.setDuration(400);
        shake.start();
    }

    private void hideError() {
        tvLoginError.setVisibility(View.GONE);
    }

    // ── Entrance animation ─────────────────────────────────────────
    private void runEntranceAnimation() {
        View root = cardLoginForm;
        if (root == null) return;
        root.setAlpha(0f);
        root.setTranslationY(60f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(root, "alpha", 0f, 1f);
        ObjectAnimator translateY = ObjectAnimator.ofFloat(root, "translationY", 60f, 0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(alpha, translateY);
        set.setDuration(500);
        set.setStartDelay(200);
        set.setInterpolator(new DecelerateInterpolator(1.5f));
        set.start();
    }
}
