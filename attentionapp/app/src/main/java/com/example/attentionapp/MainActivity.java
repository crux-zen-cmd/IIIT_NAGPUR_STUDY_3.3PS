package com.example.attentionapp;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.Gravity;
import android.widget.HorizontalScrollView;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements AttentionBridge.DomEventListener, SessionManager.SessionUpdateListener {

    public static final String KEY_CHRONOTYPE = "chronotype_preference";
    public static final String CHRONOTYPE_DAY = "DAY";
    public static final String CHRONOTYPE_NIGHT = "NIGHT";

    // Header User & Stats
    private TextView tvHeaderStreak;
    private TextView tvHeaderCoins;
    private TextView btnHeaderProfile;
    private String currentUserId = StatisticsActivity.DEFAULT_USER_ID;
    private String displayName = "Learner";

    // XP & Achievement systems
    private XpManager xpManager;
    private AchievementManager achievementManager;

    // Bottom Navigation & Tab Containers
    private BottomNavigationView bottomNavigationView;
    private NestedScrollView layoutTabStudy, layoutTabAnalytics, layoutTabTelemetry, layoutTabProfile;

    // Analytics Views
    private TextView tvYesterdaySessionsBadge, tvYesterdayTotalStudy, tvYesterdayAvgFocus, tvYesterdayCoins;
    private TextView tvAnalyticsUserId, btnSwitchUserAnalytics, tvAnalyticsTodayStudy, tvAnalyticsTodaySessions, tvAnalyticsTotalCoins, tvAnalyticsTodayCoins;
    private TextView tvAnalyticsHeatmapStreakBadge, tvAnalyticsCurrentStreak, tvAnalyticsMaxStreak, tvAnalyticsActiveDays, tvAnalyticsHeatmapDayDetail;
    private HorizontalScrollView scrollAnalyticsHeatmap;
    private StreakHeatmapView viewAnalyticsHeatmap;
    private TextView tvAnalyticsBestTime;
    private FocusScoreLineChartView chartAnalyticsScoreTimeline;
    private StudyDurationBarChartView chartAnalyticsDurationByDay;
    private RecyclerView rvAnalyticsSessionHistory;
    private TextView tvAnalyticsEmptyHistory;

    // Telemetry Views
    private LinearLayout layoutExternalAppsTelemetryList;
    private TextView tvNoExternalAppsTelemetry;
    private TextView tvTelemetryNotifsRecv, tvTelemetryNotifsIgnored, tvTelemetryNotifsInterrupted;

    // Profile Views
    private TextView tvStoreAvailableCoins, tvStoreClaimedCount;
    private Button btnStoreAddTestCoins;
    private Button btnStoreBuyBottle, btnStoreBuyPad, btnStoreBuyTshirt;
    private ProgressBar pbStoreBottleProgress, pbStorePadProgress, pbStoreTshirtProgress;
    private TextView tvStoreBottleStatus, tvStorePadStatus, tvStoreTshirtStatus;
    private LinearLayout layoutStoreOrdersList;

    private TextView tvProfileUserId;
    private Button btnProfileSwitchUser;
    private TextView tvProfileChronotypeStatus;
    private Button btnProfileToggleChronotype;
    private TextView tvProfileUsagePermStatus;
    private Button btnProfileGrantUsage;
    private TextView tvProfileNotifPermStatus;
    private Button btnProfileGrantNotif;
    private TextView tvProfilePostNotifPermStatus;
    private Button btnProfileGrantPostNotif;
    private TextView tvProfileLifetimeSessions, tvProfileLifetimeHours, tvProfileLifetimeCoins, tvProfileAverageSessionTime;

    // Focus Pro Subscription Views
    private TextView tvProfileProStatusBadge;
    private TextView tvProfileProSubtitle;
    private Button btnProfileOpenSubscription;
    private TextView tvProfileProPlanPrice;

    // Inactive Session State Views
    private LinearLayout layoutSessionInactive;
    private EditText etSessionTopic;
    private TextView chipAlgo, chipMath, chipExam, chipResearch, chipReading;
    private Button btnStartSession;
    private TextView tvInactiveStreak, tvInactiveTodayTime, tvInactiveTotalSessions, tvInactiveCoins;
    private TextView tvUsageStatsStatus, tvNotifListenerStatus;

    // Active Session State Views
    private LinearLayout layoutSessionActive;
    private TextView tvActiveSessionTopic, tvSessionTimer, tvLiveCoinsEarned;
    private TextView tvAttentionState, tvTabSwitches, tvNotifsStats, tvFocusScore;
    private ProgressBar pbFocusScore;
    private Button btnEndSession;

    // Focus Audio & Pomodoro Views
    private LinearLayout cardFocusMusicController;
    private TextView tvFocusMusicTitle, tvFocusMusicStatus;
    private Button btnFocusMusicToggle, btnFocusMusicOpenYouTube, btnFocusMusicSilence;

    // Alert Banner Views
    private LinearLayout layoutAlertBanner;
    private TextView tvAlertBannerTitle, tvAlertBannerSubtitle;
    private Button btnBannerReturn;

    // Events RecyclerView
    private RecyclerView rvEvents;
    private TextView tvEmptyLogNotice;
    private EventAdapter eventAdapter;
    private final List<AttentionEvent> eventList = new ArrayList<>();

    private boolean isDistracted = false;
    private long distractionStartTime = 0;
    private boolean isActivityResumed = false;
    private int focusedContinuousSeconds = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable sessionStopwatchRunnable;
    private Runnable awayTimerRunnable;
    private Dialog currentAlertDialog = null;

    // Permission launcher for Android 13+ POST_NOTIFICATIONS
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, "Notification permission is needed for tab switch alerts", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        SessionManager.getInstance().setUpdateListener(this);

        // Read login session
        android.content.SharedPreferences loginSession = getSharedPreferences(LoginActivity.PREF_SESSION_FILE, MODE_PRIVATE);
        String loggedInUser = loginSession.getString(LoginActivity.KEY_CURRENT_USER, null);
        displayName = loginSession.getString(LoginActivity.KEY_DISPLAY_NAME, "Learner");

        SharedPreferences prefs = getSharedPreferences(StatisticsActivity.PREFS_NAME, MODE_PRIVATE);
        if (loggedInUser != null) {
            currentUserId = loggedInUser;
            prefs.edit().putString(StatisticsActivity.KEY_USER_ID, loggedInUser).apply();
        } else {
            currentUserId = prefs.getString(StatisticsActivity.KEY_USER_ID, StatisticsActivity.DEFAULT_USER_ID);
        }

        xpManager = new XpManager(this, currentUserId);
        achievementManager = new AchievementManager(this, currentUserId);

        // Seed strictly yesterday's 5 sessions as requested
        SessionDatabaseHelper.getInstance(this).seedYesterdayOnlySessions(currentUserId);

        initViews();
        setupRecyclerView();
        setupListeners();
        checkNotificationPermission();
        updatePermissionStatusLabels();
        updateInactiveSummary();
        checkChronotypePreference();

        // Handle widget or notification action intents
        handleReturnToStudyIntent(getIntent());
        handleWidgetStartIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;
        updateHeaderStreak();
        updateInactiveSummary();
        AttentionAppWidgetProvider.updateAllWidgets(this);
        updatePermissionStatusLabels();
        loadProfileData();

        SessionManager sm = SessionManager.getInstance();
        if (sm.isSessionActive()) {
            layoutSessionInactive.setVisibility(View.GONE);
            layoutSessionActive.setVisibility(View.VISIBLE);
            tvActiveSessionTopic.setText("🎓 " + sm.getSessionTopic());

            if (isDistracted || sm.isExternalAppDistracted() || distractionStartTime > 0) {
                returnToStudyTab("Returned to Study App");
            } else {
                updateHUD();
            }
            startSessionStopwatch();
        } else {
            layoutSessionActive.setVisibility(View.GONE);
            layoutSessionInactive.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleReturnToStudyIntent(intent);
        handleWidgetStartIntent(intent);
    }

    private void handleReturnToStudyIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(AttentionNotificationManager.EXTRA_RETURN_TO_STUDY, false)) {
            mainHandler.post(() -> {
                returnToStudyTab("Returned via System Notification");
                Toast.makeText(this, "Welcome back to your Study Session!", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void handleWidgetStartIntent(Intent intent) {
        if (intent != null && AttentionAppWidgetProvider.ACTION_START_SESSION_FROM_WIDGET.equals(intent.getAction())) {
            mainHandler.postDelayed(() -> {
                if (!SessionManager.getInstance().isSessionActive()) {
                    startStudySession();
                    Toast.makeText(this, "Session started via Home Screen Widget! 🚀", Toast.LENGTH_SHORT).show();
                }
            }, 300);
        }
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void updatePermissionStatusLabels() {
        boolean hasUsage = SessionManager.getInstance().hasUsageStatsPermission(this);
        tvUsageStatsStatus.setText(hasUsage ? "📱 App Tracking: Active ✓" : "📱 App Tracking: Tap to enable");
        tvUsageStatsStatus.setTextColor(ContextCompat.getColor(this, hasUsage ? R.color.status_focused : R.color.primary_taupe));

        boolean hasNotifListener = AppNotificationListener.isNotificationAccessGranted(this);
        tvNotifListenerStatus.setText(hasNotifListener ? "🔔 Notifs: Active ✓" : "🔔 Notifs: Tap to enable");
        tvNotifListenerStatus.setTextColor(ContextCompat.getColor(this, hasNotifListener ? R.color.status_focused : R.color.primary_taupe));
    }

    private void initViews() {
        // Bottom Navigation & Tab Containers
        bottomNavigationView = findViewById(R.id.bottomNavigationView);
        layoutTabStudy = findViewById(R.id.layoutTabStudy);
        layoutTabAnalytics = findViewById(R.id.layoutTabAnalytics);
        layoutTabTelemetry = findViewById(R.id.layoutTabTelemetry);
        layoutTabProfile = findViewById(R.id.layoutTabProfile);

        // Header
        tvHeaderStreak = findViewById(R.id.tvHeaderStreak);
        tvHeaderCoins = findViewById(R.id.tvHeaderCoins);
        btnHeaderProfile = findViewById(R.id.btnHeaderProfile);
        updateHeaderStreak();

        // Inactive Session Views
        layoutSessionInactive = findViewById(R.id.layoutSessionInactive);
        etSessionTopic = findViewById(R.id.etSessionTopic);
        chipAlgo = findViewById(R.id.chipAlgo);
        chipMath = findViewById(R.id.chipMath);
        chipExam = findViewById(R.id.chipExam);
        chipResearch = findViewById(R.id.chipResearch);
        chipReading = findViewById(R.id.chipReading);
        btnStartSession = findViewById(R.id.btnStartSession);
        tvInactiveStreak = findViewById(R.id.tvInactiveStreak);
        tvInactiveTodayTime = findViewById(R.id.tvInactiveTodayTime);
        tvInactiveTotalSessions = findViewById(R.id.tvInactiveTotalSessions);
        tvInactiveCoins = findViewById(R.id.tvInactiveCoins);
        tvUsageStatsStatus = findViewById(R.id.tvUsageStatsStatus);
        tvNotifListenerStatus = findViewById(R.id.tvNotifListenerStatus);

        // Active Session Views
        layoutSessionActive = findViewById(R.id.layoutSessionActive);
        tvActiveSessionTopic = findViewById(R.id.tvActiveSessionTopic);
        tvSessionTimer = findViewById(R.id.tvSessionTimer);
        tvLiveCoinsEarned = findViewById(R.id.tvLiveCoinsEarned);
        tvAttentionState = findViewById(R.id.tvAttentionState);
        tvTabSwitches = findViewById(R.id.tvTabSwitches);
        tvNotifsStats = findViewById(R.id.tvNotifsStats);
        tvFocusScore = findViewById(R.id.tvFocusScore);
        pbFocusScore = findViewById(R.id.pbFocusScore);
        btnEndSession = findViewById(R.id.btnEndSession);

        // Focus Audio & Pomodoro Views
        cardFocusMusicController = findViewById(R.id.cardFocusMusicController);
        tvFocusMusicTitle = findViewById(R.id.tvFocusMusicTitle);
        tvFocusMusicStatus = findViewById(R.id.tvFocusMusicStatus);
        btnFocusMusicToggle = findViewById(R.id.btnFocusMusicToggle);
        btnFocusMusicOpenYouTube = findViewById(R.id.btnFocusMusicOpenYouTube);
        btnFocusMusicSilence = findViewById(R.id.btnFocusMusicSilence);

        // Alert Banner
        layoutAlertBanner = findViewById(R.id.layoutAlertBanner);
        tvAlertBannerTitle = findViewById(R.id.tvAlertBannerTitle);
        tvAlertBannerSubtitle = findViewById(R.id.tvAlertBannerSubtitle);
        btnBannerReturn = findViewById(R.id.btnBannerReturn);

        // Telemetry Tab Views
        rvEvents = findViewById(R.id.rvEvents);
        tvEmptyLogNotice = findViewById(R.id.tvEmptyLogNotice);
        layoutExternalAppsTelemetryList = findViewById(R.id.layoutExternalAppsTelemetryList);
        tvNoExternalAppsTelemetry = findViewById(R.id.tvNoExternalAppsTelemetry);
        tvTelemetryNotifsRecv = findViewById(R.id.tvTelemetryNotifsRecv);
        tvTelemetryNotifsIgnored = findViewById(R.id.tvTelemetryNotifsIgnored);
        tvTelemetryNotifsInterrupted = findViewById(R.id.tvTelemetryNotifsInterrupted);

        findViewById(R.id.tvClearLogs).setOnClickListener(v -> {
            eventList.clear();
            if (eventAdapter != null) eventAdapter.notifyDataSetChanged();
            if (tvEmptyLogNotice != null) tvEmptyLogNotice.setVisibility(View.VISIBLE);
        });

        // Analytics Tab Views
        tvYesterdaySessionsBadge = findViewById(R.id.tvYesterdaySessionsBadge);
        tvYesterdayTotalStudy = findViewById(R.id.tvYesterdayTotalStudy);
        tvYesterdayAvgFocus = findViewById(R.id.tvYesterdayAvgFocus);
        tvYesterdayCoins = findViewById(R.id.tvYesterdayCoins);

        tvAnalyticsUserId = findViewById(R.id.tvAnalyticsUserId);
        btnSwitchUserAnalytics = findViewById(R.id.btnSwitchUserAnalytics);
        tvAnalyticsTodayStudy = findViewById(R.id.tvAnalyticsTodayStudy);
        tvAnalyticsTodaySessions = findViewById(R.id.tvAnalyticsTodaySessions);
        tvAnalyticsTotalCoins = findViewById(R.id.tvAnalyticsTotalCoins);
        tvAnalyticsTodayCoins = findViewById(R.id.tvAnalyticsTodayCoins);
        tvAnalyticsHeatmapStreakBadge = findViewById(R.id.tvAnalyticsHeatmapStreakBadge);
        tvAnalyticsCurrentStreak = findViewById(R.id.tvAnalyticsCurrentStreak);
        tvAnalyticsMaxStreak = findViewById(R.id.tvAnalyticsMaxStreak);
        tvAnalyticsActiveDays = findViewById(R.id.tvAnalyticsActiveDays);
        tvAnalyticsHeatmapDayDetail = findViewById(R.id.tvAnalyticsHeatmapDayDetail);
        scrollAnalyticsHeatmap = findViewById(R.id.scrollAnalyticsHeatmap);
        viewAnalyticsHeatmap = findViewById(R.id.viewAnalyticsHeatmap);
        tvAnalyticsBestTime = findViewById(R.id.tvAnalyticsBestTime);
        chartAnalyticsScoreTimeline = findViewById(R.id.chartAnalyticsScoreTimeline);
        chartAnalyticsDurationByDay = findViewById(R.id.chartAnalyticsDurationByDay);
        rvAnalyticsSessionHistory = findViewById(R.id.rvAnalyticsSessionHistory);
        tvAnalyticsEmptyHistory = findViewById(R.id.tvAnalyticsEmptyHistory);

        // Profile Tab Views
        tvStoreAvailableCoins = findViewById(R.id.tvStoreAvailableCoins);
        tvStoreClaimedCount = findViewById(R.id.tvStoreClaimedCount);
        btnStoreAddTestCoins = findViewById(R.id.btnStoreAddTestCoins);
        btnStoreBuyBottle = findViewById(R.id.btnStoreBuyBottle);
        btnStoreBuyPad = findViewById(R.id.btnStoreBuyPad);
        btnStoreBuyTshirt = findViewById(R.id.btnStoreBuyTshirt);
        pbStoreBottleProgress = findViewById(R.id.pbStoreBottleProgress);
        pbStorePadProgress = findViewById(R.id.pbStorePadProgress);
        pbStoreTshirtProgress = findViewById(R.id.pbStoreTshirtProgress);
        tvStoreBottleStatus = findViewById(R.id.tvStoreBottleStatus);
        tvStorePadStatus = findViewById(R.id.tvStorePadStatus);
        tvStoreTshirtStatus = findViewById(R.id.tvStoreTshirtStatus);
        layoutStoreOrdersList = findViewById(R.id.layoutStoreOrdersList);

        tvProfileUserId = findViewById(R.id.tvProfileUserId);
        btnProfileSwitchUser = findViewById(R.id.btnProfileSwitchUser);
        tvProfileChronotypeStatus = findViewById(R.id.tvProfileChronotypeStatus);
        btnProfileToggleChronotype = findViewById(R.id.btnProfileToggleChronotype);
        tvProfileUsagePermStatus = findViewById(R.id.tvProfileUsagePermStatus);
        btnProfileGrantUsage = findViewById(R.id.btnProfileGrantUsage);
        tvProfileNotifPermStatus = findViewById(R.id.tvProfileNotifPermStatus);
        btnProfileGrantNotif = findViewById(R.id.btnProfileGrantNotif);
        tvProfilePostNotifPermStatus = findViewById(R.id.tvProfilePostNotifPermStatus);
        btnProfileGrantPostNotif = findViewById(R.id.btnProfileGrantPostNotif);
        tvProfileLifetimeSessions = findViewById(R.id.tvProfileLifetimeSessions);
        tvProfileLifetimeHours = findViewById(R.id.tvProfileLifetimeHours);
        tvProfileLifetimeCoins = findViewById(R.id.tvProfileLifetimeCoins);
        tvProfileAverageSessionTime = findViewById(R.id.tvProfileAverageSessionTime);

        // Subscription Banner Views
        tvProfileProStatusBadge = findViewById(R.id.tvProfileProStatusBadge);
        tvProfileProSubtitle = findViewById(R.id.tvProfileProSubtitle);
        btnProfileOpenSubscription = findViewById(R.id.btnProfileOpenSubscription);
        tvProfileProPlanPrice = findViewById(R.id.tvProfileProPlanPrice);
    }

    private void setupRecyclerView() {
        eventAdapter = new EventAdapter(eventList);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvEvents.setLayoutManager(lm);
        rvEvents.setAdapter(eventAdapter);
    }

    private void switchTab(int tabId) {
        if (layoutTabStudy != null) layoutTabStudy.setVisibility(tabId == R.id.nav_study ? View.VISIBLE : View.GONE);
        if (layoutTabAnalytics != null) layoutTabAnalytics.setVisibility(tabId == R.id.nav_analytics ? View.VISIBLE : View.GONE);
        if (layoutTabTelemetry != null) layoutTabTelemetry.setVisibility(tabId == R.id.nav_telemetry ? View.VISIBLE : View.GONE);
        if (layoutTabProfile != null) layoutTabProfile.setVisibility(tabId == R.id.nav_profile ? View.VISIBLE : View.GONE);

        if (tabId == R.id.nav_study) {
            updateInactiveSummary();
            updateHUD();
        } else if (tabId == R.id.nav_analytics) {
            loadAnalyticsData();
        } else if (tabId == R.id.nav_telemetry) {
            loadTelemetryData();
        } else if (tabId == R.id.nav_profile) {
            loadProfileData();
        }
    }

    private void setupListeners() {
        btnStartSession.setOnClickListener(v -> promptStartStudySession());
        btnEndSession.setOnClickListener(v -> endStudySession());

        // Focus Music Controller Listeners
        if (btnFocusMusicToggle != null) {
            btnFocusMusicToggle.setOnClickListener(v -> {
                FocusMusicManager fmm = FocusMusicManager.getInstance(this);
                if (fmm.isPlaying()) {
                    fmm.pauseAudio();
                    btnFocusMusicToggle.setText("▶️ Resume");
                    if (tvFocusMusicStatus != null) {
                        tvFocusMusicStatus.setText("Continuous Audio: Paused ⏸");
                        tvFocusMusicStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber));
                    }
                    fmm.speak("Audio paused");
                } else {
                    fmm.resumeAudio();
                    btnFocusMusicToggle.setText("⏸ Pause");
                    if (tvFocusMusicStatus != null) {
                        tvFocusMusicStatus.setText("Continuous Audio: Playing 🟢");
                        tvFocusMusicStatus.setTextColor(ContextCompat.getColor(this, R.color.status_focused));
                    }
                    fmm.speak("Audio playing");
                }
            });
        }

        if (btnFocusMusicOpenYouTube != null) {
            btnFocusMusicOpenYouTube.setOnClickListener(v -> {
                FocusMusicManager.getInstance(this).openYouTubePomodoro(this);
                if (tvFocusMusicStatus != null) {
                    tvFocusMusicStatus.setText("YouTube Stream: Active 🔴");
                    tvFocusMusicStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose));
                }
            });
        }

        if (btnFocusMusicSilence != null) {
            btnFocusMusicSilence.setOnClickListener(v -> {
                FocusMusicManager fmm = FocusMusicManager.getInstance(this);
                fmm.stopAudio();
                if (btnFocusMusicToggle != null) btnFocusMusicToggle.setText("▶️ Play Audio");
                if (tvFocusMusicStatus != null) {
                    tvFocusMusicStatus.setText("Audio Muted 🔇");
                    tvFocusMusicStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
                }
                Toast.makeText(this, "Focus audio stopped", Toast.LENGTH_SHORT).show();
            });
        }

        // Quick Topic Selection Chips
        if (chipAlgo != null) chipAlgo.setOnClickListener(v -> etSessionTopic.setText("Algorithms"));
        if (chipMath != null) chipMath.setOnClickListener(v -> etSessionTopic.setText("Mathematics"));
        if (chipExam != null) chipExam.setOnClickListener(v -> etSessionTopic.setText("Exam Prep"));
        if (chipResearch != null) chipResearch.setOnClickListener(v -> etSessionTopic.setText("Research Paper"));
        if (chipReading != null) chipReading.setOnClickListener(v -> etSessionTopic.setText("Reading Material"));

        // Bottom Navigation Bar Listener
        if (bottomNavigationView != null) {
            bottomNavigationView.setOnItemSelectedListener(item -> {
                switchTab(item.getItemId());
                return true;
            });
        }

        // Header Navigation Shortcuts
        if (btnHeaderProfile != null) {
            btnHeaderProfile.setOnClickListener(v -> showUserProfileDialog());
        }
        if (tvHeaderStreak != null) {
            tvHeaderStreak.setOnClickListener(v -> {
                if (bottomNavigationView != null) bottomNavigationView.setSelectedItemId(R.id.nav_analytics);
            });
        }
        if (tvHeaderCoins != null) {
            tvHeaderCoins.setOnClickListener(v -> {
                if (bottomNavigationView != null) bottomNavigationView.setSelectedItemId(R.id.nav_analytics);
            });
        }

        // Analytics Tab Listeners
        if (btnSwitchUserAnalytics != null) {
            btnSwitchUserAnalytics.setOnClickListener(v -> showSwitchUserDialog());
        }

        // Rewards Store Listeners
        if (btnStoreAddTestCoins != null) {
            btnStoreAddTestCoins.setOnClickListener(v -> {
                RewardStoreManager store = new RewardStoreManager(this, currentUserId);
                store.addBonusCoins(5000);
                Toast.makeText(this, "🪙 +5,000 Focus Coins added for testing store redemptions!", Toast.LENGTH_SHORT).show();
                loadProfileData();
                updateHeaderStreak();
            });
        }
        if (btnStoreBuyBottle != null) {
            btnStoreBuyBottle.setOnClickListener(v -> promptPurchaseStoreItem(RewardStoreManager.ITEM_WATER_BOTTLE));
        }
        if (btnStoreBuyPad != null) {
            btnStoreBuyPad.setOnClickListener(v -> promptPurchaseStoreItem(RewardStoreManager.ITEM_STUDY_PAD));
        }
        if (btnStoreBuyTshirt != null) {
            btnStoreBuyTshirt.setOnClickListener(v -> promptPurchaseStoreItem(RewardStoreManager.ITEM_T_SHIRT));
        }

        // Profile Tab Listeners
        if (btnProfileOpenSubscription != null) {
            btnProfileOpenSubscription.setOnClickListener(v -> showSubscriptionPlansDialog());
        }
        if (btnProfileSwitchUser != null) {
            btnProfileSwitchUser.setOnClickListener(v -> showSwitchUserDialog());
        }
        if (btnProfileToggleChronotype != null) {
            btnProfileToggleChronotype.setOnClickListener(v -> showChronotypeDialog(true));
        }
        if (btnProfileGrantUsage != null) {
            btnProfileGrantUsage.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "Open Android Settings -> Usage Access", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (btnProfileGrantNotif != null) {
            btnProfileGrantNotif.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "Open Android Settings -> Notification Access", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (btnProfileGrantPostNotif != null) {
            btnProfileGrantPostNotif.setOnClickListener(v -> checkNotificationPermission());
        }

        tvUsageStatsStatus.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Open Android Settings -> Usage Access", Toast.LENGTH_SHORT).show();
            }
        });

        tvNotifListenerStatus.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Open Android Settings -> Notification Access", Toast.LENGTH_SHORT).show();
            }
        });

        btnBannerReturn.setOnClickListener(v -> returnToStudyTab("Banner Return Button"));
    }

    private void updateInactiveSummary() {
        SessionDatabaseHelper db = SessionDatabaseHelper.getInstance(this);
        int streak = db.getCurrentStreak(currentUserId);
        int todaySec = db.getTodayTotalSeconds(currentUserId);
        int totalSessions = db.getTotalSessionsCount(currentUserId);

        if (tvInactiveStreak != null) {
            tvInactiveStreak.setText("🔥 " + streak + (streak == 1 ? " Day" : " Days"));
        }
        if (tvInactiveTotalSessions != null) {
            tvInactiveTotalSessions.setText(String.valueOf(totalSessions));
        }
        if (tvInactiveCoins != null) {
            double todayCoins = db.getTodayCoins(currentUserId);
            tvInactiveCoins.setText(String.format(Locale.US, "%.2f", todayCoins));
        }
        if (tvInactiveTodayTime != null) {
            int todayMin = todaySec / 60;
            int hours = todayMin / 60;
            int mins = todayMin % 60;
            if (hours > 0) {
                tvInactiveTodayTime.setText(hours + "h " + mins + "m");
            } else {
                tvInactiveTodayTime.setText(mins + "m");
            }
        }
    }

    private void loadAnalyticsData() {
        if (tvAnalyticsUserId != null) {
            tvAnalyticsUserId.setText("👤 " + currentUserId);
        }

        SessionDatabaseHelper db = SessionDatabaseHelper.getInstance(this);

        // 0. Yesterday's Study Performance Card
        int yestSec = db.getYesterdayTotalSeconds(currentUserId);
        int yestCount = db.getYesterdaySessionCount(currentUserId);
        int yestAvgFocus = db.getYesterdayAverageFocusScore(currentUserId);
        double yestCoins = db.getYesterdayCoins(currentUserId);

        int yestMin = yestSec / 60;
        int yestHours = yestMin / 60;
        int yestRemMin = yestMin % 60;
        String yestFormatted = (yestHours > 0) ? (yestHours + "h " + yestRemMin + "m") : (yestMin + "m");

        if (tvYesterdaySessionsBadge != null) tvYesterdaySessionsBadge.setText(yestCount + " Sessions Done");
        if (tvYesterdayTotalStudy != null) tvYesterdayTotalStudy.setText(yestFormatted);
        if (tvYesterdayAvgFocus != null) tvYesterdayAvgFocus.setText(yestAvgFocus + "%");
        if (tvYesterdayCoins != null) tvYesterdayCoins.setText(String.format(Locale.US, "🪙 %.2f", yestCoins));

        // 1. Today's stats
        int todaySec = db.getTodayTotalSeconds(currentUserId);
        int todayCount = db.getTodaySessionCount(currentUserId);
        int min = todaySec / 60;
        int hours = min / 60;
        int remMin = min % 60;
        String formattedTime = (hours > 0) ? (hours + "h " + remMin + "m") : (min + "m");
        if (tvAnalyticsTodayStudy != null) tvAnalyticsTodayStudy.setText(formattedTime);
        if (tvAnalyticsTodaySessions != null) tvAnalyticsTodaySessions.setText(todayCount + " session" + (todayCount == 1 ? "" : "s") + " today");

        // 2. Coins
        double totalCoins = db.getTotalCoins(currentUserId);
        double todayCoins = db.getTodayCoins(currentUserId);
        if (tvAnalyticsTotalCoins != null) tvAnalyticsTotalCoins.setText(String.format(Locale.US, "%.2f", totalCoins));
        if (tvAnalyticsTodayCoins != null) tvAnalyticsTodayCoins.setText(String.format(Locale.US, "+%.2f today", todayCoins));

        // 3. Best study window
        SharedPreferences prefs = getSharedPreferences(StatisticsActivity.PREFS_NAME, MODE_PRIVATE);
        String chronotype = prefs.getString("chronotype_preference", CHRONOTYPE_DAY);
        String bestWindow = db.getBestStudyTime(currentUserId, chronotype);
        if (tvAnalyticsBestTime != null) tvAnalyticsBestTime.setText(bestWindow);

        // 4. Streak & Heatmap
        int currentStreak = db.getCurrentStreak(currentUserId);
        int maxStreak = db.getMaxStreak(currentUserId);
        int activeDays = db.getDistinctActiveDaysCount(currentUserId);

        if (tvAnalyticsHeatmapStreakBadge != null) tvAnalyticsHeatmapStreakBadge.setText("🔥 " + currentStreak + (currentStreak == 1 ? " Day Streak" : " Days Streak"));
        if (tvAnalyticsCurrentStreak != null) tvAnalyticsCurrentStreak.setText(currentStreak + (currentStreak == 1 ? " Day" : " Days"));
        if (tvAnalyticsMaxStreak != null) tvAnalyticsMaxStreak.setText(maxStreak + (maxStreak == 1 ? " Day" : " Days"));
        if (tvAnalyticsActiveDays != null) tvAnalyticsActiveDays.setText(activeDays + (activeDays == 1 ? " Day" : " Days"));

        if (tvAnalyticsHeatmapDayDetail != null) {
            tvAnalyticsHeatmapDayDetail.setText("📌 Yesterday • " + yestMin + "m studied • " + yestCount + " sessions (" + yestAvgFocus + "% avg focus)");
        }

        if (viewAnalyticsHeatmap != null) {
            Map<String, SessionDatabaseHelper.DailyStudyStat> dailyMap = db.getDailyStudyStats(currentUserId, 365);
            viewAnalyticsHeatmap.setStudyStats(dailyMap);
            viewAnalyticsHeatmap.setOnDaySelectedListener((dateStr, displayDate, totalMinutes, sessionCount) -> {
                if (tvAnalyticsHeatmapDayDetail != null) {
                    if (totalMinutes > 0) {
                        tvAnalyticsHeatmapDayDetail.setText("📌 " + displayDate + " • " + totalMinutes + "m studied • " + sessionCount + " session" + (sessionCount == 1 ? "" : "s"));
                    } else {
                        tvAnalyticsHeatmapDayDetail.setText("📌 " + displayDate + " • No study sessions recorded");
                    }
                }
            });
            if (scrollAnalyticsHeatmap != null) {
                scrollAnalyticsHeatmap.post(() -> scrollAnalyticsHeatmap.fullScroll(View.FOCUS_RIGHT));
            }
        }

        // 5. Charts
        List<SessionRecord> userSessions = db.getSessionsForUser(currentUserId);
        if (chartAnalyticsScoreTimeline != null) {
            chartAnalyticsScoreTimeline.setRecords(db.getScoreTimelineForUser(currentUserId, 20));
        }
        if (chartAnalyticsDurationByDay != null) {
            double[] durations = db.getDurationByDayOfWeek(currentUserId);
            chartAnalyticsDurationByDay.setDurationsMinutes(durations);
        }

        // 6. Recent sessions history
        if (rvAnalyticsSessionHistory != null && tvAnalyticsEmptyHistory != null) {
            if (userSessions == null || userSessions.isEmpty()) {
                tvAnalyticsEmptyHistory.setVisibility(View.VISIBLE);
                rvAnalyticsSessionHistory.setVisibility(View.GONE);
            } else {
                tvAnalyticsEmptyHistory.setVisibility(View.GONE);
                rvAnalyticsSessionHistory.setVisibility(View.VISIBLE);
                rvAnalyticsSessionHistory.setLayoutManager(new LinearLayoutManager(this));
                rvAnalyticsSessionHistory.setAdapter(new SessionHistoryAdapter(userSessions));
            }
        }
    }

    private void loadTelemetryData() {
        SessionManager sm = SessionManager.getInstance();
        if (tvTelemetryNotifsRecv != null) tvTelemetryNotifsRecv.setText(String.valueOf(sm.getNotificationsReceivedCount()));
        if (tvTelemetryNotifsIgnored != null) tvTelemetryNotifsIgnored.setText(String.valueOf(sm.getNotificationsIgnoredCount()));
        if (tvTelemetryNotifsInterrupted != null) tvTelemetryNotifsInterrupted.setText(String.valueOf(sm.getNotificationsInterruptedCount()));

        if (layoutExternalAppsTelemetryList != null && tvNoExternalAppsTelemetry != null) {
            layoutExternalAppsTelemetryList.removeAllViews();
            List<ExternalAppUsage> apps = sm.getExternalAppList(this);
            if (apps == null || apps.isEmpty()) {
                tvNoExternalAppsTelemetry.setVisibility(View.VISIBLE);
            } else {
                tvNoExternalAppsTelemetry.setVisibility(View.GONE);
                for (ExternalAppUsage app : apps) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_session_card));
                    row.setPadding(20, 14, 20, 14);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(0, 0, 0, 12);
                    row.setLayoutParams(lp);

                    TextView tvName = new TextView(this);
                    tvName.setText("📱 " + app.getAppName() + " (" + app.getOpenCount() + "x open)");
                    tvName.setTextColor(ContextCompat.getColor(this, R.color.primary_taupe_deep));
                    tvName.setTextSize(12);
                    tvName.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    TextView tvDur = new TextView(this);
                    tvDur.setText(app.getFormattedDuration());
                    tvDur.setTextColor(ContextCompat.getColor(this, R.color.status_distracted));
                    tvDur.setTextSize(12);
                    tvDur.setTypeface(null, android.graphics.Typeface.BOLD);

                    row.addView(tvName);
                    row.addView(tvDur);
                    layoutExternalAppsTelemetryList.addView(row);
                }
            }
        }
    }

    private void loadProfileData() {
        if (tvProfileUserId != null) {
            tvProfileUserId.setText(currentUserId);
        }

        SharedPreferences prefs = getSharedPreferences(StatisticsActivity.PREFS_NAME, MODE_PRIVATE);
        String chronotype = prefs.getString("chronotype_preference", CHRONOTYPE_DAY);
        if (tvProfileChronotypeStatus != null) {
            tvProfileChronotypeStatus.setText(CHRONOTYPE_DAY.equals(chronotype) ? "☀️ Day Person (Early Bird)" : "🌙 Night Person (Night Owl)");
        }

        // Permissions
        boolean hasUsage = SessionManager.getInstance().hasUsageStatsPermission(this);
        if (tvProfileUsagePermStatus != null) {
            tvProfileUsagePermStatus.setText(hasUsage ? "Active ✓ (Permission Granted)" : "Inactive ✗ (Tap Grant to allow app switch tracking)");
            tvProfileUsagePermStatus.setTextColor(ContextCompat.getColor(this, hasUsage ? R.color.status_focused : R.color.status_distracted));
        }
        if (btnProfileGrantUsage != null) {
            btnProfileGrantUsage.setText(hasUsage ? "Granted" : "Grant");
            btnProfileGrantUsage.setEnabled(!hasUsage);
        }

        boolean hasNotif = AppNotificationListener.isNotificationAccessGranted(this);
        if (tvProfileNotifPermStatus != null) {
            tvProfileNotifPermStatus.setText(hasNotif ? "Active ✓ (Permission Granted)" : "Inactive ✗ (Tap Grant to track resisted notifications)");
            tvProfileNotifPermStatus.setTextColor(ContextCompat.getColor(this, hasNotif ? R.color.status_focused : R.color.status_distracted));
        }
        if (btnProfileGrantNotif != null) {
            btnProfileGrantNotif.setText(hasNotif ? "Granted" : "Grant");
            btnProfileGrantNotif.setEnabled(!hasNotif);
        }

        boolean hasPost = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPost = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        if (tvProfilePostNotifPermStatus != null) {
            tvProfilePostNotifPermStatus.setText(hasPost ? "Active ✓ (Permission Granted)" : "Inactive ✗ (Tap Grant for alert notifications)");
            tvProfilePostNotifPermStatus.setTextColor(ContextCompat.getColor(this, hasPost ? R.color.status_focused : R.color.status_distracted));
        }
        if (btnProfileGrantPostNotif != null) {
            btnProfileGrantPostNotif.setText(hasPost ? "Granted" : "Grant");
            btnProfileGrantPostNotif.setEnabled(!hasPost);
        }

        // Lifetime stats
        SessionDatabaseHelper db = SessionDatabaseHelper.getInstance(this);
        int totalSessions = db.getTotalSessionsCount(currentUserId);
        int totalSec = db.getTotalDurationSecondsAllTime(currentUserId);
        double totalCoins = db.getTotalCoins(currentUserId);
        int avgSec = db.getAverageDurationSecondsPerSession(currentUserId);

        int totalMin = totalSec / 60;
        int hours = totalMin / 60;
        int remMin = totalMin % 60;
        String totalFormatted = (hours > 0) ? (hours + "h " + remMin + "m") : (totalMin + "m");

        int avgMin = avgSec / 60;

        if (tvProfileLifetimeSessions != null) tvProfileLifetimeSessions.setText(String.valueOf(totalSessions));
        if (tvProfileLifetimeHours != null) tvProfileLifetimeHours.setText(totalFormatted);
        if (tvProfileLifetimeCoins != null) tvProfileLifetimeCoins.setText(String.format(Locale.US, "%.2f", totalCoins));
        if (tvProfileAverageSessionTime != null) tvProfileAverageSessionTime.setText(avgMin + "m");

        loadSubscriptionDashboard();
        loadStoreDashboard();
    }

    private void loadSubscriptionDashboard() {
        SubscriptionManager sub = SubscriptionManager.getInstance(this);
        boolean isPro = sub.isPro(currentUserId);
        if (tvProfileProStatusBadge != null) {
            if (isPro) {
                tvProfileProStatusBadge.setText(sub.isYearly(currentUserId) ? "👑 PRO ANNUAL" : "⭐ PRO MONTHLY");
                tvProfileProStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_focused));
                tvProfileProStatusBadge.setBackgroundResource(R.drawable.bg_badge_focused);
            } else {
                tvProfileProStatusBadge.setText("Free Starter");
                tvProfileProStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tvProfileProStatusBadge.setBackgroundResource(R.drawable.bg_badge_distracted);
            }
        }
        if (tvProfileProSubtitle != null) {
            if (isPro) {
                tvProfileProSubtitle.setText(sub.getFormattedExpiryDate(currentUserId) + " • 2X Coin Boost active on all study sessions!");
            } else {
                tvProfileProSubtitle.setText("Get 2X Coins on all sessions, strict social media shield, and full AI eye telemetry. Monthly & Annual plans available.");
            }
        }
        if (btnProfileOpenSubscription != null) {
            btnProfileOpenSubscription.setText(isPro ? "👑 Manage Plan" : "⭐ View Plans · Save 58%");
        }
        if (tvProfileProPlanPrice != null) {
            tvProfileProPlanPrice.setText(isPro ? "Active ⭐" : "from ₹125/mo");
        }
    }

    private void showSubscriptionPlansDialog() {
        if (isFinishing() || isDestroyed()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_subscription_plans);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        SubscriptionManager sub = SubscriptionManager.getInstance(this);
        boolean currentlyPro = sub.isPro(currentUserId);

        TextView tvCurrentBadge = dialog.findViewById(R.id.tvSubCurrentBadge);
        LinearLayout cardPlanYearly = dialog.findViewById(R.id.cardPlanYearly);
        LinearLayout cardPlanMonthly = dialog.findViewById(R.id.cardPlanMonthly);
        TextView radioPlanYearly = dialog.findViewById(R.id.radioPlanYearly);
        TextView radioPlanMonthly = dialog.findViewById(R.id.radioPlanMonthly);
        Button btnAction = dialog.findViewById(R.id.btnSubAction);
        TextView tvDisclaimer = dialog.findViewById(R.id.tvSubDisclaimer);
        Button btnCancel = dialog.findViewById(R.id.btnSubCancelPlan);
        Button btnClose = dialog.findViewById(R.id.btnSubClose);

        if (tvCurrentBadge != null) {
            tvCurrentBadge.setText("Current: " + sub.getPlanDisplayName(currentUserId));
        }

        final boolean[] isYearlySelected = new boolean[]{!sub.isPro(currentUserId) || sub.isYearly(currentUserId)};

        Runnable updateSelectionUi = () -> {
            if (isYearlySelected[0]) {
                if (cardPlanYearly != null) cardPlanYearly.setBackgroundResource(R.drawable.bg_card_selected);
                if (radioPlanYearly != null) {
                    radioPlanYearly.setText("✓");
                    radioPlanYearly.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_taupe)));
                }
                if (cardPlanMonthly != null) cardPlanMonthly.setBackgroundResource(R.drawable.bg_card_unselected);
                if (radioPlanMonthly != null) {
                    radioPlanMonthly.setText("");
                    radioPlanMonthly.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.border_primary)));
                }
                if (btnAction != null) {
                    btnAction.setText(currentlyPro ? "Switch to Annual Plan (₹1,499/yr)" : "Start 7-Day Free Trial (₹1,499/yr)");
                }
                if (tvDisclaimer != null) {
                    tvDisclaimer.setText("No charge today. Cancel anytime in Google Play before 7-day trial ends.");
                }
            } else {
                if (cardPlanMonthly != null) cardPlanMonthly.setBackgroundResource(R.drawable.bg_card_selected);
                if (radioPlanMonthly != null) {
                    radioPlanMonthly.setText("✓");
                    radioPlanMonthly.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_taupe)));
                }
                if (cardPlanYearly != null) cardPlanYearly.setBackgroundResource(R.drawable.bg_card_unselected);
                if (radioPlanYearly != null) {
                    radioPlanYearly.setText("");
                    radioPlanYearly.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.border_primary)));
                }
                if (btnAction != null) {
                    btnAction.setText("Subscribe Monthly (₹299/mo)");
                }
                if (tvDisclaimer != null) {
                    tvDisclaimer.setText("Billed monthly at ₹299. Cancel anytime in Google Play Store settings.");
                }
            }
        };

        updateSelectionUi.run();

        if (cardPlanYearly != null) {
            cardPlanYearly.setOnClickListener(v -> {
                isYearlySelected[0] = true;
                updateSelectionUi.run();
            });
        }

        if (cardPlanMonthly != null) {
            cardPlanMonthly.setOnClickListener(v -> {
                isYearlySelected[0] = false;
                updateSelectionUi.run();
            });
        }

        if (btnAction != null) {
            btnAction.setOnClickListener(v -> {
                dialog.dismiss();
                if (isYearlySelected[0]) {
                    sub.subscribeYearly(currentUserId);
                    new AlertDialog.Builder(this)
                            .setTitle("🎉 Welcome to Focus Pro!")
                            .setMessage("Your 7-Day Free Trial on Focus Pro Annual (₹1,499/yr) is now active!\n\nPerks unlocked:\n• 2X Coin Multiplier on all sessions\n• Strict Social Media Distraction Shield\n• Full AI Eye & Gaze Tracking Telemetry\n• Free Priority Dispatch for Store Rewards")
                            .setPositiveButton("Awesome!", null)
                            .show();
                } else {
                    sub.subscribeMonthly(currentUserId);
                    new AlertDialog.Builder(this)
                            .setTitle("⭐ Focus Pro Monthly Active!")
                            .setMessage("You are now subscribed to Focus Pro Monthly (₹299/mo)!\n\nEnjoy 2X Coins and unlimited AI focus protection.")
                            .setPositiveButton("Let's Focus!", null)
                            .show();
                }
                loadSubscriptionDashboard();
                updateHeaderStreak();
            });
        }

        if (currentlyPro && btnCancel != null) {
            btnCancel.setVisibility(View.VISIBLE);
            btnCancel.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Cancel Focus Pro")
                        .setMessage("Are you sure you want to cancel your Focus Pro subscription? You will lose the 2X Coin multiplier and advanced telemetry.")
                        .setPositiveButton("Yes, Cancel", (d, w) -> {
                            sub.cancelSubscription(currentUserId);
                            dialog.dismiss();
                            Toast.makeText(this, "Subscription cancelled. Reverted to Free Starter.", Toast.LENGTH_SHORT).show();
                            loadSubscriptionDashboard();
                            updateHeaderStreak();
                        })
                        .setNegativeButton("Keep Pro", null)
                        .show();
            });
        } else if (btnCancel != null) {
            btnCancel.setVisibility(View.GONE);
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
        dialog.show();
    }

    private void loadStoreDashboard() {
        RewardStoreManager store = new RewardStoreManager(this, currentUserId);
        double available = store.getAvailableCoins();

        if (tvStoreAvailableCoins != null) {
            tvStoreAvailableCoins.setText(String.format(Locale.US, "%.2f Coins", available));
        }

        List<RewardStoreManager.OrderRecord> orders = store.getOrders();
        if (tvStoreClaimedCount != null) {
            tvStoreClaimedCount.setText(orders.size() + " Claimed");
        }

        // 1. Water Bottle (5,000 Coins)
        updateStoreItemUi(
                RewardStoreManager.ITEM_WATER_BOTTLE,
                RewardStoreManager.PRICE_WATER_BOTTLE,
                available,
                store,
                btnStoreBuyBottle,
                pbStoreBottleProgress,
                tvStoreBottleStatus
        );

        // 2. Study Pad (7,000 Coins)
        updateStoreItemUi(
                RewardStoreManager.ITEM_STUDY_PAD,
                RewardStoreManager.PRICE_STUDY_PAD,
                available,
                store,
                btnStoreBuyPad,
                pbStorePadProgress,
                tvStorePadStatus
        );

        // 3. T-Shirt (10,000 Coins)
        updateStoreItemUi(
                RewardStoreManager.ITEM_T_SHIRT,
                RewardStoreManager.PRICE_T_SHIRT,
                available,
                store,
                btnStoreBuyTshirt,
                pbStoreTshirtProgress,
                tvStoreTshirtStatus
        );

        // Populate Claimed Orders if any
        if (layoutStoreOrdersList != null) {
            layoutStoreOrdersList.removeAllViews();
            if (orders.isEmpty()) {
                layoutStoreOrdersList.setVisibility(View.GONE);
            } else {
                layoutStoreOrdersList.setVisibility(View.VISIBLE);

                TextView tvHeader = new TextView(this);
                tvHeader.setText("📦 My Redeemed Orders (" + orders.size() + ")");
                tvHeader.setTextColor(ContextCompat.getColor(this, R.color.primary_taupe_deep));
                tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                tvHeader.setTextSize(12);
                tvHeader.setPadding(0, 8, 0, 6);
                layoutStoreOrdersList.addView(tvHeader);

                for (RewardStoreManager.OrderRecord order : orders) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setBackgroundResource(R.drawable.bg_card_unselected);
                    row.setPadding(16, 12, 16, 12);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(0, 4, 0, 8);
                    row.setLayoutParams(lp);

                    LinearLayout topRow = new LinearLayout(this);
                    topRow.setOrientation(LinearLayout.HORIZONTAL);
                    topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

                    TextView tvTitle = new TextView(this);
                    tvTitle.setText(order.itemTitle);
                    tvTitle.setTextColor(ContextCompat.getColor(this, R.color.primary_taupe_deep));
                    tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvTitle.setTextSize(12);
                    tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    TextView tvStatus = new TextView(this);
                    tvStatus.setText("✓ " + order.status);
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_focused));
                    tvStatus.setTextSize(10);
                    tvStatus.setTypeface(null, android.graphics.Typeface.BOLD);

                    topRow.addView(tvTitle);
                    topRow.addView(tvStatus);
                    row.addView(topRow);

                    TextView tvMeta = new TextView(this);
                    tvMeta.setText("Order #" + order.orderId + " • " + order.pricePaid + " 🪙 • " + order.getFormattedDate());
                    tvMeta.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
                    tvMeta.setTextSize(10);
                    tvMeta.setPadding(0, 4, 0, 0);
                    row.addView(tvMeta);

                    layoutStoreOrdersList.addView(row);
                }
            }
        }
    }

    private void updateStoreItemUi(
            String itemId,
            int price,
            double available,
            RewardStoreManager store,
            Button btnBuy,
            ProgressBar pbProgress,
            TextView tvStatus
    ) {
        if (btnBuy == null) return;

        boolean isPurchased = store.isItemPurchased(itemId);
        int purchaseCount = store.getPurchaseCount(itemId);

        if (pbProgress != null) {
            pbProgress.setMax(price);
            pbProgress.setProgress(Math.min(price, (int) available));
        }

        if (isPurchased) {
            btnBuy.setText("✓ Claimed (" + purchaseCount + "x)");
            btnBuy.setBackgroundResource(R.drawable.bg_badge_focused);
            btnBuy.setTextColor(ContextCompat.getColor(this, R.color.status_focused));
            if (tvStatus != null) {
                tvStatus.setText("✓ Redeemed! Claimed with focus coins.");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_focused));
            }
        } else if (available >= price) {
            btnBuy.setText("Buy (" + price + " 🪙)");
            btnBuy.setBackgroundResource(R.drawable.bg_button_gradient);
            btnBuy.setTextColor(ContextCompat.getColor(this, R.color.text_on_taupe));
            if (tvStatus != null) {
                tvStatus.setText("🎉 You have enough coins! Tap to redeem now.");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_focused));
            }
        } else {
            double needed = price - available;
            btnBuy.setText("Buy (" + price + " 🪙)");
            btnBuy.setBackgroundResource(R.drawable.bg_button_restore);
            btnBuy.setTextColor(ContextCompat.getColor(this, R.color.text_on_taupe));
            if (tvStatus != null) {
                tvStatus.setText("Need " + (int) Math.ceil(needed) + " more coins to redeem");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            }
        }
    }

    private void promptPurchaseStoreItem(String itemId) {
        RewardStoreManager store = new RewardStoreManager(this, currentUserId);
        RewardStoreManager.StoreItem item = null;
        for (RewardStoreManager.StoreItem si : RewardStoreManager.getAvailableStoreItems()) {
            if (si.id.equals(itemId)) {
                item = si;
                break;
            }
        }
        if (item == null) return;

        double available = store.getAvailableCoins();
        if (available < item.price) {
            double needed = item.price - available;
            new AlertDialog.Builder(this)
                    .setTitle("Insufficient Focus Coins 🪙")
                    .setMessage("You need " + (int) Math.ceil(needed) + " more coins to purchase the " + item.title + " (" + item.price + " 🪙).\n\n" +
                            "💡 Tip: Maintain high focus in study sessions to earn coins faster (0.5 coins per 2 minutes scaled by focus percentage)!\n\n" +
                            "Would you like to add +5,000 test coins to test purchasing now?")
                    .setPositiveButton("+5,000 Test Coins", (dialog, which) -> {
                        store.addBonusCoins(5000);
                        Toast.makeText(this, "🪙 +5,000 Focus Coins added!", Toast.LENGTH_SHORT).show();
                        loadProfileData();
                        updateHeaderStreak();
                    })
                    .setNegativeButton("OK", null)
                    .show();
        } else {
            final RewardStoreManager.StoreItem targetItem = item;
            new AlertDialog.Builder(this)
                    .setTitle("Confirm Purchase 🛍️")
                    .setMessage("Redeem your Focus Coins for:\n\n" +
                            targetItem.emoji + " " + targetItem.title + "\n" +
                            "Cost: " + targetItem.price + " Coins\n\n" +
                            "Remaining Balance after purchase: " + String.format(Locale.US, "%.2f", (available - targetItem.price)) + " Coins\n\n" +
                            "Proceed with order placement?")
                    .setPositiveButton("Confirm & Buy", (dialog, which) -> {
                        RewardStoreManager.OrderRecord order = store.purchaseItem(targetItem);
                        if (order != null) {
                            loadProfileData();
                            updateHeaderStreak();
                            updateInactiveSummary();
                            loadAnalyticsData();

                            new AlertDialog.Builder(this)
                                    .setTitle("🎉 Order Placed Successfully!")
                                    .setMessage("Order #" + order.orderId + " for " + targetItem.title + " has been confirmed!\n\n" +
                                            "Status: " + order.status + "\n\n" +
                                            "Your merchandise will be prepared for delivery to your registered student profile. Keep up the high focus!")
                                    .setPositiveButton("Awesome! 🚀", null)
                                    .show();
                        } else {
                            Toast.makeText(this, "Could not complete purchase. Check balance.", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Session Lifecycle Management
    // ══════════════════════════════════════════════════════════════════

    private void promptStartStudySession() {
        String topic = etSessionTopic.getText().toString().trim();
        if (topic.isEmpty()) topic = "General Study";
        final String finalTopic = topic;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🎧 Pomodoro Focus Audio");
        builder.setMessage("Would you like to play focus music for better concentration during \"" + finalTopic + "\"?\n\n" +
                "• YouTube Stream: 4-Hour ADHD Brown Noise Pomodoro\n" +
                "• In-App Audio: Resonant Zen Bell & Lo-Fi Brown Noise\n" +
                "• Voice Guidance: Audible start bell and focus cues");

        builder.setPositiveButton("▶️ Open YouTube Pomodoro", (dialog, which) -> {
            startStudySessionInternal(finalTopic);
            FocusMusicManager.getInstance(this).playZenBell();
            FocusMusicManager.getInstance(this).openYouTubePomodoro(this);
            if (tvFocusMusicStatus != null) {
                tvFocusMusicStatus.setText("YouTube Stream: Active 🔴");
                tvFocusMusicStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose));
            }
        });

        builder.setNeutralButton("🌊 Play In-App Audio", (dialog, which) -> {
            startStudySessionInternal(finalTopic);
            FocusMusicManager fmm = FocusMusicManager.getInstance(this);
            fmm.playZenBell();
            fmm.speak("Pomodoro focus session started. Stay in the zone.");
            fmm.startBrownNoise();
            if (btnFocusMusicToggle != null) btnFocusMusicToggle.setText("⏸ Pause");
            if (tvFocusMusicStatus != null) {
                tvFocusMusicStatus.setText("Continuous Audio: Playing 🟢");
                tvFocusMusicStatus.setTextColor(ContextCompat.getColor(this, R.color.status_focused));
            }
        });

        builder.setNegativeButton("Quiet Study (Voice Only)", (dialog, which) -> {
            startStudySessionInternal(finalTopic);
            FocusMusicManager.getInstance(this).playZenBell();
            FocusMusicManager.getInstance(this).speak("Focus session started.");
            if (btnFocusMusicToggle != null) btnFocusMusicToggle.setText("▶️ Play Audio");
            if (tvFocusMusicStatus != null) {
                tvFocusMusicStatus.setText("Audio: Quiet / Voice Bell 🔔");
                tvFocusMusicStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            }
        });

        builder.show();
    }

    private void startStudySession() {
        promptStartStudySession();
    }

    private void startStudySessionInternal(String topic) {
        SessionManager.getInstance().startSession(this, topic);

        layoutSessionInactive.setVisibility(View.GONE);
        layoutSessionActive.setVisibility(View.VISIBLE);
        tvActiveSessionTopic.setText("🎓 " + topic);

        isDistracted = false;
        distractionStartTime = 0;
        focusedContinuousSeconds = 0;

        startSessionStopwatch();
        updateHUD();

        logRealEvent("SESSION_START", "Study Session Began", "Active topic: " + topic, 0);
        Toast.makeText(this, "Session Started! Timer is running & attention is being monitored.", Toast.LENGTH_SHORT).show();
    }

    private void endStudySession() {
        if (!SessionManager.getInstance().isSessionActive()) return;

        // Stop focus audio and play completion sound & voice announcement
        FocusMusicManager fmm = FocusMusicManager.getInstance(this);
        fmm.stopAudio();
        fmm.playZenBell();
        fmm.speak("Great job! Your study session is completed.");

        SessionManager sm = SessionManager.getInstance();
        sm.endSession(this);

        if (sessionStopwatchRunnable != null) {
            mainHandler.removeCallbacks(sessionStopwatchRunnable);
        }
        if (awayTimerRunnable != null) {
            mainHandler.removeCallbacks(awayTimerRunnable);
        }
        layoutAlertBanner.setVisibility(View.GONE);
        AttentionNotificationManager.cancelNotification(this);

        layoutSessionActive.setVisibility(View.GONE);
        layoutSessionInactive.setVisibility(View.VISIBLE);

        // Persist session to local SQLite database under active User ID
        long start = sm.getSessionStartTime();
        long end = sm.getSessionEndTime();
        int durationSec = (int) Math.max(1, (end - start) / 1000);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(start);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        String timeOfDay;
        if (hour >= 6 && hour < 12) {
            timeOfDay = "Morning";
        } else if (hour >= 12 && hour < 17) {
            timeOfDay = "Afternoon";
        } else if (hour >= 17 && hour < 21) {
            timeOfDay = "Evening";
        } else if (hour >= 21 && hour < 24) {
            timeOfDay = "Night";
        } else {
            timeOfDay = "Late Night";
        }

        double mult = SubscriptionManager.getInstance(this).getCoinMultiplier(currentUserId);
        double coinsEarned = SessionDatabaseHelper.calculateCoinsEarned(durationSec, sm.getFocusScore(), mult);

        SessionRecord record = new SessionRecord(
                UUID.randomUUID().toString(),
                currentUserId,
                sm.getSessionTopic(),
                start,
                end,
                durationSec,
                sm.getFocusScore(),
                sm.getTabSwitchCount(),
                timeOfDay,
                sm.getNotificationsReceivedCount(),
                sm.getNotificationsIgnoredCount(),
                "{}",
                coinsEarned
        );
        SessionDatabaseHelper.getInstance(this).insertSession(record);

        // Award XP
        int xpEarned = xpManager.awardSessionXp(durationSec, sm.getFocusScore());
        int newLevel = xpManager.getLevel();
        String levelTitle = xpManager.getLevelTitle();

        // Evaluate achievements
        double lifetimeCoins = SessionDatabaseHelper.getInstance(this).getTotalCoins(currentUserId);
        int totalSessions = SessionDatabaseHelper.getInstance(this).getTotalSessionsCount(currentUserId);
        int currentStreak = SessionDatabaseHelper.getInstance(this).getCurrentStreak(currentUserId);
        // Night session check (22:00–04:00)
        int sessionHour = cal.get(Calendar.HOUR_OF_DAY);
        if (sessionHour >= 22 || sessionHour < 4) {
            if (achievementManager.recordNightSession()) {
                mainHandler.postDelayed(() -> Toast.makeText(this, "🏅 Achievement Unlocked: Night Owl Elite 🦉", Toast.LENGTH_LONG).show(), 2500);
            }
        }
        java.util.List<String> newAchievements = achievementManager.evaluateSessionAchievements(
            record, totalSessions, currentStreak, lifetimeCoins, durationSec, 0, xpManager.getTotalXp()
        );
        for (int i = 0; i < newAchievements.size(); i++) {
            final String achName = newAchievements.get(i);
            final int delay = 1500 + i * 1200;
            mainHandler.postDelayed(() -> Toast.makeText(this, "🏅 Achievement Unlocked: " + achName, Toast.LENGTH_LONG).show(), delay);
        }

        updateHeaderStreak();
        updateInactiveSummary();
        loadAnalyticsData();
        loadProfileData();
        loadTelemetryData();
        AttentionAppWidgetProvider.updateAllWidgets(this);

        logRealEvent(
                "SESSION_END",
                "Session Completed & Saved",
                "Duration: " + sm.getFormattedSessionDuration() + " | Switches: " + sm.getTabSwitchCount() + " | XP: +" + xpEarned + " | Coins: +" + String.format(Locale.US, "%.2f", coinsEarned) + " 🪙",
                0
        );

        showSessionSummaryModal();
    }

    private void startSessionStopwatch() {
        if (sessionStopwatchRunnable != null) {
            mainHandler.removeCallbacks(sessionStopwatchRunnable);
        }

        sessionStopwatchRunnable = new Runnable() {
            @Override
            public void run() {
                if (SessionManager.getInstance().isSessionActive()) {
                    SessionManager sm = SessionManager.getInstance();
                    tvSessionTimer.setText(sm.getFormattedSessionDuration());

                    // If user is inside the app focused on study, slowly increase the percentage
                    if (!isDistracted && !sm.isExternalAppDistracted() && isActivityResumed) {
                        focusedContinuousSeconds++;
                        // Every 3 seconds of continuous active focus: recover +1% (slowly increase)
                        if (focusedContinuousSeconds >= 3 && focusedContinuousSeconds % 3 == 0) {
                            if (sm.getFocusScore() < 100) {
                                boolean recovered = sm.recoverFocusGradually(1);
                                if (recovered && sm.getFocusScore() % 5 == 0) {
                                    logRealEvent(
                                            "FOCUS_RECOVERY",
                                            "📈 Focus Climbing (" + sm.getFocusScore() + "%)",
                                            "Sustained unbroken study in app • Focus score slowly recovering",
                                            1
                                    );
                                }
                            }
                        }
                    } else {
                        focusedContinuousSeconds = 0;
                    }

                    updateHUD();

                    int durSec = (int) Math.max(0, (System.currentTimeMillis() - sm.getSessionStartTime()) / 1000);
                    double mult = SubscriptionManager.getInstance(MainActivity.this).getCoinMultiplier(currentUserId);
                    double liveCoins = SessionDatabaseHelper.calculateCoinsEarned(durSec, sm.getFocusScore(), mult);
                    if (tvLiveCoinsEarned != null) {
                        if (mult > 1.0) {
                            tvLiveCoinsEarned.setText(String.format(Locale.US, "👑 🪙 +%.2f Coins (2X Boost)", liveCoins));
                        } else {
                            tvLiveCoinsEarned.setText(String.format(Locale.US, "🪙 +%.2f Coins Earning", liveCoins));
                        }
                    }

                    mainHandler.postDelayed(this, 1000);
                }
            }
        };
        mainHandler.post(sessionStopwatchRunnable);
    }

    private void showSessionSummaryModal() {
        if (isFinishing() || isDestroyed()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_session_summary);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        SessionManager sm = SessionManager.getInstance();

        TextView tvTopic = dialog.findViewById(R.id.tvSummaryTopic);
        TextView tvDuration = dialog.findViewById(R.id.tvSummaryDuration);
        TextView tvScore = dialog.findViewById(R.id.tvSummaryScore);
        TextView tvSwitches = dialog.findViewById(R.id.tvSummarySwitches);
        TextView tvCoinsEarned = dialog.findViewById(R.id.tvSummaryCoinsEarned);
        TextView tvCoinsExpl = dialog.findViewById(R.id.tvSummaryCoinsExplanation);
        TextView tvNotifRecv = dialog.findViewById(R.id.tvSummaryNotifReceived);
        TextView tvNotifIgnored = dialog.findViewById(R.id.tvSummaryNotifIgnored);
        TextView tvNotifInterrupted = dialog.findViewById(R.id.tvSummaryNotifInterrupted);
        LinearLayout layoutAppsList = dialog.findViewById(R.id.layoutExternalAppsList);
        TextView tvNoApps = dialog.findViewById(R.id.tvNoExternalApps);
        Button btnViewStats = dialog.findViewById(R.id.btnSummaryViewStats);
        Button btnClose = dialog.findViewById(R.id.btnSummaryClose);

        tvTopic.setText("Topic: " + sm.getSessionTopic());
        tvDuration.setText(sm.getFormattedSessionDuration());
        tvScore.setText(sm.getFocusScore() + "%");
        tvSwitches.setText(String.valueOf(sm.getTabSwitchCount()));

        int durSec = (int) Math.max(1, (sm.getSessionEndTime() - sm.getSessionStartTime()) / 1000);
        double mult = SubscriptionManager.getInstance(this).getCoinMultiplier(currentUserId);
        double coinsEarned = SessionDatabaseHelper.calculateCoinsEarned(durSec, sm.getFocusScore(), mult);
        if (tvCoinsEarned != null) {
            if (mult > 1.0) {
                tvCoinsEarned.setText(String.format(Locale.US, "+%.2f Coins Earned! 👑 (2X Pro Boost)", coinsEarned));
            } else {
                tvCoinsEarned.setText(String.format(Locale.US, "+%.2f Coins Earned! 🪙", coinsEarned));
            }
        }
        if (tvCoinsExpl != null) {
            if (mult > 1.0) {
                tvCoinsExpl.setText(String.format(Locale.US, "0.5 coins / 2 mins × %d%% Focus × 2.0X Pro Boost 👑", sm.getFocusScore()));
            } else {
                tvCoinsExpl.setText(String.format(Locale.US, "0.5 coins / 2 mins × %d%% Focus", sm.getFocusScore()));
            }
        }

        int recv = sm.getNotificationsReceivedCount();
        int ignored = sm.getNotificationsIgnoredCount();
        int interrupted = sm.getNotificationsInterruptedCount();

        tvNotifRecv.setText(String.valueOf(recv));
        int pctResisted = (recv > 0) ? (int) Math.round(((double) ignored / recv) * 100) : 100;
        tvNotifIgnored.setText(ignored + " (" + pctResisted + "% Resisted)");
        tvNotifInterrupted.setText(String.valueOf(interrupted));

        // Populate external apps list
        List<ExternalAppUsage> apps = sm.getExternalAppList(this);
        if (apps != null && !apps.isEmpty()) {
            tvNoApps.setVisibility(View.GONE);
            for (ExternalAppUsage app : apps) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 4, 0, 4);

                TextView tvName = new TextView(this);
                tvName.setText("• " + app.getAppName() + " (" + app.getOpenCount() + "x):");
                tvName.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tvName.setTextSize(12);
                tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvTime = new TextView(this);
                tvTime.setText(app.getFormattedDuration());
                tvTime.setTextColor(ContextCompat.getColor(this, R.color.status_distracted));
                tvTime.setTextSize(12);
                tvTime.setTypeface(null, android.graphics.Typeface.BOLD);

                row.addView(tvName);
                row.addView(tvTime);
                layoutAppsList.addView(row);
            }
        } else {
            tvNoApps.setVisibility(View.VISIBLE);
        }

        if (btnViewStats != null) {
            btnViewStats.setOnClickListener(v -> {
                dialog.dismiss();
                if (bottomNavigationView != null) {
                    bottomNavigationView.setSelectedItemId(R.id.nav_analytics);
                } else {
                    startActivity(new Intent(this, StatisticsActivity.class));
                }
            });
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showSwitchUserDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Switch User Profile");
        builder.setMessage("Enter the User ID for this study session:");

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentUserId);
        input.setSelectAllOnFocus(true);
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newId = input.getText().toString().trim();
            if (!newId.isEmpty()) {
                currentUserId = newId;
                SharedPreferences prefs = getSharedPreferences(StatisticsActivity.PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putString(StatisticsActivity.KEY_USER_ID, newId).apply();
                updateHeaderStreak();
                updateInactiveSummary();
                loadAnalyticsData();
                loadProfileData();
                AttentionAppWidgetProvider.updateAllWidgets(this);
                Toast.makeText(this, "Active User ID: " + currentUserId, Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void updateHeaderStreak() {
        SessionDatabaseHelper db = SessionDatabaseHelper.getInstance(this);
        SubscriptionManager sub = SubscriptionManager.getInstance(this);
        boolean isPro = sub.isPro(currentUserId);

        if (tvHeaderStreak != null) {
            int streak = db.getCurrentStreak(currentUserId);
            tvHeaderStreak.setText("🔥 " + streak + (streak == 1 ? " Day" : " Days"));
        }
        if (tvHeaderCoins != null) {
            double totalCoins = db.getTotalCoins(currentUserId);
            if (isPro) {
                tvHeaderCoins.setText(String.format(Locale.US, "👑 🪙 %.2f", totalCoins));
            } else {
                tvHeaderCoins.setText(String.format(Locale.US, "🪙 %.2f", totalCoins));
            }
        }
    }

    private void showUserProfileDialog() {
        if (isFinishing() || isDestroyed()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_user_profile);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        SessionDatabaseHelper db = SessionDatabaseHelper.getInstance(this);
        int streak = db.getCurrentStreak(currentUserId);
        int totalSessions = db.getTotalSessionsCount(currentUserId);
        double avgSessionsPerDay = db.getAverageSessionsPerDay(currentUserId);
        int avgDurationSec = db.getAverageDurationSecondsPerSession(currentUserId);
        int allTimeSec = db.getTotalDurationSecondsAllTime(currentUserId);

        TextView tvUserId = dialog.findViewById(R.id.tvProfileUserId);
        TextView tvStreakTag = dialog.findViewById(R.id.tvProfileStreakTag);
        TextView tvTotal = dialog.findViewById(R.id.tvProfileTotalSessions);
        TextView tvAvgDay = dialog.findViewById(R.id.tvProfileAvgSessionsPerDay);
        TextView tvAvgDur = dialog.findViewById(R.id.tvProfileAvgDuration);
        TextView tvAllTime = dialog.findViewById(R.id.tvProfileAllTimeTime);
        TextView tvChronotype = dialog.findViewById(R.id.tvProfileChronotype);
        Button btnChangeChronotype = dialog.findViewById(R.id.btnProfileChangeChronotype);
        Button btnSwitch = dialog.findViewById(R.id.btnProfileSwitchUser);
        Button btnSettings = dialog.findViewById(R.id.btnProfileSettings);
        Button btnClose = dialog.findViewById(R.id.btnProfileClose);

        tvUserId.setText(currentUserId);
        tvStreakTag.setText("🔥 " + streak + (streak == 1 ? " Day Streak Active" : " Days Streak Active"));
        tvTotal.setText(totalSessions + (totalSessions == 1 ? " session" : " sessions"));
        tvAvgDay.setText(String.format(Locale.US, "%.1f / day", avgSessionsPerDay));

        // Format average duration per session
        int avgMin = avgDurationSec / 60;
        int avgSecRem = avgDurationSec % 60;
        String formattedAvg;
        if (avgMin >= 60) {
            formattedAvg = (avgMin / 60) + "h " + (avgMin % 60) + "m";
        } else if (avgMin > 0) {
            formattedAvg = avgMin + "m " + avgSecRem + "s";
        } else {
            formattedAvg = avgSecRem + "s";
        }
        tvAvgDur.setText(formattedAvg);

        // Format all-time study duration
        int totalMin = allTimeSec / 60;
        int totalHours = totalMin / 60;
        int remMin = totalMin % 60;
        String formattedTotal;
        if (totalHours > 0) {
            formattedTotal = totalHours + "h " + remMin + "m";
        } else {
            formattedTotal = totalMin + "m";
        }
        tvAllTime.setText(formattedTotal);

        TextView tvProfileTotalCoins = dialog.findViewById(R.id.tvProfileTotalCoins);
        if (tvProfileTotalCoins != null) {
            double totalCoins = db.getTotalCoins(currentUserId);
            tvProfileTotalCoins.setText(String.format(Locale.US, "%.2f 🪙", totalCoins));
        }

        SharedPreferences prefs = getSharedPreferences(StatisticsActivity.PREFS_NAME, MODE_PRIVATE);
        String savedChronotype = prefs.getString(KEY_CHRONOTYPE, CHRONOTYPE_DAY);
        if (tvChronotype != null) {
            tvChronotype.setText(CHRONOTYPE_DAY.equals(savedChronotype) ? "☀️ Day Person (Early Bird)" : "🌙 Night Person (Night Owl)");
        }

        // Subscription Tier in Profile Dialog
        TextView tvSubTier = dialog.findViewById(R.id.tvProfileSubscriptionTier);
        Button btnSub = dialog.findViewById(R.id.btnProfileSubscription);
        SubscriptionManager subManager = SubscriptionManager.getInstance(this);
        boolean isProUser = subManager.isPro(currentUserId);
        if (tvSubTier != null) {
            tvSubTier.setText(subManager.getPlanDisplayName(currentUserId));
            tvSubTier.setTextColor(ContextCompat.getColor(this, isProUser ? R.color.status_focused : R.color.primary_taupe));
        }
        if (btnSub != null) {
            btnSub.setText(isProUser ? "👑 Manage Focus Pro Plan" : "👑 Upgrade to Focus Pro (Save 58%)");
            btnSub.setOnClickListener(v -> {
                dialog.dismiss();
                showSubscriptionPlansDialog();
            });
        }

        if (btnChangeChronotype != null) {
            btnChangeChronotype.setOnClickListener(v -> {
                dialog.dismiss();
                showChronotypeDialog(true);
            });
        }

        btnSwitch.setOnClickListener(v -> {
            dialog.dismiss();
            showSwitchUserDialog();
        });

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                dialog.dismiss();
                try {
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(intent);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }

        // Logout button
        Button btnLogout = dialog.findViewById(R.id.btnProfileLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> {
                dialog.dismiss();
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Sign Out")
                    .setMessage("Sign out of " + displayName + "?")
                    .setPositiveButton("Sign Out", (d2, w) -> {
                        getSharedPreferences(LoginActivity.PREF_SESSION_FILE, MODE_PRIVATE).edit()
                            .putBoolean(LoginActivity.KEY_LOGGED_IN, false).apply();
                        Intent loginIntent = new Intent(this, LoginActivity.class);
                        loginIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(loginIntent);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void checkChronotypePreference() {
        SharedPreferences prefs = getSharedPreferences(StatisticsActivity.PREFS_NAME, MODE_PRIVATE);
        if (!prefs.contains(KEY_CHRONOTYPE)) {
            mainHandler.postDelayed(() -> showChronotypeDialog(false), 350);
        }
    }

    private void showChronotypeDialog(boolean isEditing) {
        if (isFinishing() || isDestroyed()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_chronotype_preference);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        SharedPreferences prefs = getSharedPreferences(StatisticsActivity.PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString(KEY_CHRONOTYPE, CHRONOTYPE_DAY);

        final String[] selected = new String[]{current};

        LinearLayout layoutOptionDay = dialog.findViewById(R.id.layoutOptionDay);
        LinearLayout layoutOptionNight = dialog.findViewById(R.id.layoutOptionNight);
        TextView tvRadioDay = dialog.findViewById(R.id.tvRadioDay);
        TextView tvRadioNight = dialog.findViewById(R.id.tvRadioNight);
        Button btnSave = dialog.findViewById(R.id.btnSaveChronotype);

        Runnable updateUi = () -> {
            boolean isDay = CHRONOTYPE_DAY.equals(selected[0]);
            if (isDay) {
                layoutOptionDay.setBackgroundResource(R.drawable.bg_card_selected);
                tvRadioDay.setText("✓");
                tvRadioDay.setTextColor(ContextCompat.getColor(this, R.color.text_on_taupe));
                tvRadioDay.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_taupe)));

                layoutOptionNight.setBackgroundResource(R.drawable.bg_card_unselected);
                tvRadioNight.setText("");
                tvRadioNight.setBackgroundTintList(null);
            } else {
                layoutOptionNight.setBackgroundResource(R.drawable.bg_card_selected);
                tvRadioNight.setText("✓");
                tvRadioNight.setTextColor(ContextCompat.getColor(this, R.color.text_on_taupe));
                tvRadioNight.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_taupe)));

                layoutOptionDay.setBackgroundResource(R.drawable.bg_card_unselected);
                tvRadioDay.setText("");
                tvRadioDay.setBackgroundTintList(null);
            }
        };

        updateUi.run();

        layoutOptionDay.setOnClickListener(v -> {
            selected[0] = CHRONOTYPE_DAY;
            updateUi.run();
        });

        layoutOptionNight.setOnClickListener(v -> {
            selected[0] = CHRONOTYPE_NIGHT;
            updateUi.run();
        });

        btnSave.setOnClickListener(v -> {
            prefs.edit().putString(KEY_CHRONOTYPE, selected[0]).apply();
            dialog.dismiss();
            String label = CHRONOTYPE_DAY.equals(selected[0]) ? "☀️ Day Person (Early Bird)" : "🌙 Night Person (Night Owl)";
            Toast.makeText(this, "Study Rhythm saved: " + label, Toast.LENGTH_SHORT).show();
            loadProfileData();
            loadAnalyticsData();
            if (isEditing) {
                showUserProfileDialog();
            }
        });

        dialog.setCancelable(isEditing);
        dialog.show();
    }

    // ══════════════════════════════════════════════════════════════════
    // Background Tab Switch & Distraction Monitoring
    // ══════════════════════════════════════════════════════════════════

    private void handleTabSwitch(String fromTab, String toTab, String source) {
        SessionManager sm = SessionManager.getInstance();
        if (!sm.isSessionActive()) return;

        sm.recordTabSwitch(0);
        sm.markLatestNotificationInterrupted();

        isDistracted = true;
        distractionStartTime = System.currentTimeMillis();
        focusedContinuousSeconds = 0; // reset continuous recovery counter

        updateHUD();
        vibratePhone(250);

        AttentionNotificationManager.showTabSwitchNotification(
                this,
                sm.getTabSwitchCount(),
                sm.getFocusScore(),
                fromTab,
                toTab
        );

        showAlertBanner(toTab);

        logRealEvent(
                "TAB_SWITCH",
                "⚠️ Switch: " + toTab,
                "Switched from " + fromTab + " → " + toTab + " • Returning sooner saves focus score",
                0
        );
    }

    private void showAlertBanner(String appName) {
        layoutAlertBanner.setVisibility(View.VISIBLE);
        tvAlertBannerTitle.setText("⚠️ SWITCHED TO " + appName.toUpperCase() + " - FOCUS LOST");

        if (awayTimerRunnable != null) {
            mainHandler.removeCallbacks(awayTimerRunnable);
        }

        awayTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDistracted && SessionManager.getInstance().isSessionActive()) {
                    SessionManager sm = SessionManager.getInstance();
                    long elapsedSec = (System.currentTimeMillis() - distractionStartTime) / 1000;
                    String timeStr = String.format(Locale.getDefault(), "%02d:%02d", elapsedSec / 60, elapsedSec % 60);

                    int projectedPenalty = Math.max(2, Math.min(75, 2 + (int) Math.round(elapsedSec * 0.6f)));
                    int projectedScore = Math.max(5, sm.getFocusScore() - projectedPenalty);
                    tvAlertBannerSubtitle.setText("Away for " + timeStr + " • Penalty: -" + projectedPenalty + "% (Score: " + projectedScore + "%) • Return to stop loss!");

                    updateHUD();
                    mainHandler.postDelayed(this, 1000);
                }
            }
        };
        mainHandler.post(awayTimerRunnable);
    }

    private void returnToStudyTab(String reason) {
        if (!isDistracted && !SessionManager.getInstance().isExternalAppDistracted() && distractionStartTime <= 0) {
            return;
        }

        isDistracted = false;
        if (awayTimerRunnable != null) {
            mainHandler.removeCallbacks(awayTimerRunnable);
        }

        layoutAlertBanner.setVisibility(View.GONE);
        AttentionNotificationManager.cancelNotification(this);

        SessionManager sm = SessionManager.getInstance();
        int awaySec = 1;
        if (distractionStartTime > 0) {
            awaySec = (int) Math.max(1, (System.currentTimeMillis() - distractionStartTime) / 1000);
            distractionStartTime = 0;
        }

        focusedContinuousSeconds = 0; // reset recovery streak

        if (sm.isSessionActive()) {
            int penalty;
            if (sm.isExternalAppDistracted()) {
                penalty = sm.restoreFocus(this);
            } else {
                penalty = sm.deductTimePenalty(awaySec);
            }

            logRealEvent(
                    "TIME_PENALTY",
                    "⚡ Focus Reduced -" + penalty + "%",
                    "Away for " + awaySec + "s before returning to app • Current Focus: " + sm.getFocusScore() + "%",
                    -penalty
            );

            logRealEvent(
                    "FOCUS_RESTORED",
                    "✓ Focus Restored to Study",
                    reason + " (away for " + awaySec + "s) • Stay in app to slowly recover percentage!",
                    0
            );

            Toast.makeText(this, "Focus -" + penalty + "% (" + awaySec + "s away). Stay in app to slowly recover!", Toast.LENGTH_SHORT).show();
        }

        updateHUD();
    }

    private void updateHUD() {
        SessionManager sm = SessionManager.getInstance();
        boolean active = sm.isSessionActive();

        if (!active) {
            tvAttentionState.setText("⚪ INACTIVE");
            tvAttentionState.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            tvAttentionState.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_badge_distracted));
        } else if (isDistracted || sm.isExternalAppDistracted()) {
            tvAttentionState.setText("🔴 FOCUS LOST");
            tvAttentionState.setTextColor(ContextCompat.getColor(this, R.color.status_lost));
            tvAttentionState.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_badge_lost));
        } else {
            if (sm.getFocusScore() < 100 && focusedContinuousSeconds >= 3) {
                tvAttentionState.setText("🟢 RECOVERING (" + sm.getFocusScore() + "%)");
            } else {
                tvAttentionState.setText("🟢 FOCUSED");
            }
            tvAttentionState.setTextColor(ContextCompat.getColor(this, R.color.status_focused));
            tvAttentionState.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_badge_focused));
        }

        tvTabSwitches.setText("⚡ " + sm.getTabSwitchCount() + " Switches");
        tvNotifsStats.setText("🔔 " + sm.getNotificationsReceivedCount() + " Recv (" + sm.getNotificationsIgnoredCount() + " Ignored)");
        tvFocusScore.setText(sm.getFocusScore() + "%");
        pbFocusScore.setProgress(sm.getFocusScore());

        int scoreColor = (isDistracted || sm.isExternalAppDistracted()) ? R.color.status_lost : R.color.status_focused;
        tvFocusScore.setTextColor(ContextCompat.getColor(this, scoreColor));
        pbFocusScore.setProgressTintList(ContextCompat.getColorStateList(this, scoreColor));
    }

    private void logRealEvent(String type, String title, String details, int scoreChange) {
        AttentionEvent event = new AttentionEvent(type, title, details, scoreChange);
        eventList.add(event);
        tvEmptyLogNotice.setVisibility(View.GONE);
        if (eventAdapter != null) {
            eventAdapter.notifyItemInserted(eventList.size() - 1);
            rvEvents.scrollToPosition(eventList.size() - 1);
        }
    }

    private void vibratePhone(long millis) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(millis);
            }
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════
    // SessionManager.SessionUpdateListener Callbacks
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void onSessionUpdated() {
        mainHandler.post(this::updateHUD);
    }

    @Override
    public void onAppSwitchDetected(String packageName, String appName) {
        mainHandler.post(() -> {
            isDistracted = true;
            if (distractionStartTime <= 0) {
                distractionStartTime = System.currentTimeMillis();
            }
            focusedContinuousSeconds = 0;
            updateHUD();
            vibratePhone(250);
            showAlertBanner(appName);
            logRealEvent(
                    "APP_SWITCH",
                    "⚠️ Switched to " + appName,
                    "Destination: " + appName + " (" + packageName + ") • Return quickly to save focus score!",
                    0
            );
        });
    }

    @Override
    public void onFocusRestored(String reason) {
        mainHandler.post(() -> {
            returnToStudyTab(reason);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // AttentionBridge.DomEventListener Callbacks
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void onDomVisibilityChanged(boolean isHidden, String title, String url) {
        mainHandler.post(() -> {
            if (!SessionManager.getInstance().isSessionActive()) return;

            if (isHidden) {
                if (!isDistracted) {
                    handleTabSwitch("Study", "Background / Hidden", "DOM visibilitychange");
                }
            } else {
                if (isDistracted) {
                    returnToStudyTab("DOM returned to visible");
                }
            }
        });
    }

    @Override
    public void onWindowBlur(String title) {
        mainHandler.post(() -> {
            if (SessionManager.getInstance().isSessionActive() && !isDistracted) {
                logRealEvent("DOM_BLUR", "Focus Shift", "Focus lost on: " + title, -5);
            }
        });
    }

    @Override
    public void onWindowFocus(String title) {
        mainHandler.post(() -> {
            if (SessionManager.getInstance().isSessionActive()) {
                updateHUD();
            }
        });
    }

    @Override
    public void onUserActivity() {
        mainHandler.post(() -> {
            if (SessionManager.getInstance().isSessionActive() && !isDistracted) {
                SessionManager.getInstance().recoverScore(1);
            }
        });
    }

    @Override
    public void onTabSwitchTriggered(String tabName, String reason) {
        mainHandler.post(() -> {
            if (SessionManager.getInstance().isSessionActive()) {
                handleTabSwitch("Study", tabName, "DOM Trigger: " + reason);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // Android Activity Lifecycle Hooks
    // ══════════════════════════════════════════════════════════════════


    @Override
    protected void onPause() {
        super.onPause();
        isActivityResumed = false;
        if (SessionManager.getInstance().isSessionActive()) {
            // Check after a brief delay if user actually opened a distracting app
            mainHandler.postDelayed(() -> {
                if (SessionManager.getInstance().isSessionActive() && !isActivityResumed) {
                    String fg = SessionManager.getForegroundAppPackage(MainActivity.this);
                    if (fg != null && !fg.equals(getPackageName())) {
                        SessionManager.getInstance().recordAppSwitch(MainActivity.this, fg);
                    }
                    // If fg is null, home screen, or neutral app, score is NOT reduced!
                }
            }, 600);
        }
    }

    @Override
    protected void onDestroy() {
        FocusMusicManager.getInstance(this).shutdown();
        if (sessionStopwatchRunnable != null) {
            mainHandler.removeCallbacks(sessionStopwatchRunnable);
        }
        if (awayTimerRunnable != null) {
            mainHandler.removeCallbacks(awayTimerRunnable);
        }
        if (currentAlertDialog != null && currentAlertDialog.isShowing()) {
            currentAlertDialog.dismiss();
        }
        super.onDestroy();
    }
}