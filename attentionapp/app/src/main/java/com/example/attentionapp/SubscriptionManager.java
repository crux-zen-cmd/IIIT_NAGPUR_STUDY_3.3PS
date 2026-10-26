package com.example.attentionapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SubscriptionManager {

    public static final String PREF_NAME = "attention_subscription_prefs";

    public static final String KEY_PLAN_TYPE = "sub_plan_type_"; // "FREE", "MONTHLY", "YEARLY"
    public static final String KEY_EXPIRY_TS = "sub_expiry_ts_";
    public static final String KEY_SUBSCRIBED_TS = "sub_subscribed_ts_";
    public static final String KEY_TRIAL_ACTIVE = "sub_trial_active_";

    public static final String PLAN_FREE = "FREE";
    public static final String PLAN_MONTHLY = "MONTHLY";
    public static final String PLAN_YEARLY = "YEARLY";

    // Pricing details in INR and USD
    public static final String PRICE_MONTHLY_DISPLAY = "₹299 / month";
    public static final String PRICE_YEARLY_DISPLAY = "₹1,499 / year";
    public static final String PRICE_YEARLY_MONTHLY_EQUIVALENT = "₹125 / mo (58% OFF)";

    private static SubscriptionManager instance;
    private final Context context;
    private final SharedPreferences prefs;

    private SubscriptionManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SubscriptionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SubscriptionManager(context);
        }
        return instance;
    }

    public String getPlanType(String userId) {
        String uid = sanitize(userId);
        String plan = prefs.getString(KEY_PLAN_TYPE + uid, PLAN_FREE);
        if (isPlanExpired(uid)) {
            // Revert back to free if expired
            setPlanType(uid, PLAN_FREE, 0, false);
            return PLAN_FREE;
        }
        return plan;
    }

    public boolean isPro(String userId) {
        String plan = getPlanType(userId);
        return PLAN_MONTHLY.equals(plan) || PLAN_YEARLY.equals(plan);
    }

    public boolean isYearly(String userId) {
        String plan = getPlanType(userId);
        return PLAN_YEARLY.equals(plan);
    }

    public boolean isTrialActive(String userId) {
        String uid = sanitize(userId);
        return prefs.getBoolean(KEY_TRIAL_ACTIVE + uid, false) && !isPlanExpired(uid);
    }

    public double getCoinMultiplier(String userId) {
        return isPro(userId) ? 2.0 : 1.0;
    }

    public String getPlanDisplayName(String userId) {
        String plan = getPlanType(userId);
        if (PLAN_YEARLY.equals(plan)) {
            return isTrialActive(userId) ? "👑 Focus Pro Annual (Free Trial)" : "👑 Focus Pro Annual";
        } else if (PLAN_MONTHLY.equals(plan)) {
            return "⭐ Focus Pro Monthly";
        }
        return "Free Starter";
    }

    public String getFormattedExpiryDate(String userId) {
        String uid = sanitize(userId);
        long expiry = prefs.getLong(KEY_EXPIRY_TS + uid, 0);
        if (expiry <= 0) return "No active subscription";
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        return "Valid until " + sdf.format(new Date(expiry));
    }

    public void subscribeMonthly(String userId) {
        String uid = sanitize(userId);
        long now = System.currentTimeMillis();
        long expiry = now + (30L * 24 * 60 * 60 * 1000); // 30 days
        setPlanType(uid, PLAN_MONTHLY, expiry, false);
    }

    public void subscribeYearly(String userId) {
        String uid = sanitize(userId);
        long now = System.currentTimeMillis();
        // 7 days trial + 365 days
        long expiry = now + (372L * 24 * 60 * 60 * 1000);
        setPlanType(uid, PLAN_YEARLY, expiry, true);
    }

    public void cancelSubscription(String userId) {
        String uid = sanitize(userId);
        setPlanType(uid, PLAN_FREE, 0, false);
    }

    private void setPlanType(String uid, String plan, long expiry, boolean isTrial) {
        prefs.edit()
                .putString(KEY_PLAN_TYPE + uid, plan)
                .putLong(KEY_EXPIRY_TS + uid, expiry)
                .putLong(KEY_SUBSCRIBED_TS + uid, System.currentTimeMillis())
                .putBoolean(KEY_TRIAL_ACTIVE + uid, isTrial)
                .apply();
    }

    private boolean isPlanExpired(String uid) {
        String plan = prefs.getString(KEY_PLAN_TYPE + uid, PLAN_FREE);
        if (PLAN_FREE.equals(plan)) return false;
        long expiry = prefs.getLong(KEY_EXPIRY_TS + uid, 0);
        return expiry > 0 && System.currentTimeMillis() > expiry;
    }

    private String sanitize(String userId) {
        return (userId != null && !userId.trim().isEmpty()) ? userId.trim() : "default";
    }
}
