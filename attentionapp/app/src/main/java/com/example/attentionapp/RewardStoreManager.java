package com.example.attentionapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RewardStoreManager {

    public static final String PREF_NAME = "attention_store_prefs";
    public static final String KEY_BONUS_COINS = "bonus_coins_";
    public static final String KEY_SPENT_COINS = "spent_coins_";
    public static final String KEY_ORDERS = "orders_json_";

    public static final String ITEM_WATER_BOTTLE = "water_bottle";
    public static final String ITEM_STUDY_PAD = "study_pad";
    public static final String ITEM_T_SHIRT = "t_shirt";

    public static final int PRICE_WATER_BOTTLE = 5000;
    public static final int PRICE_STUDY_PAD = 7000;
    public static final int PRICE_T_SHIRT = 10000;

    private final Context context;
    private final String userId;
    private final SharedPreferences prefs;

    public static class StoreItem {
        public final String id;
        public final String title;
        public final String emoji;
        public final int price;
        public final String subtitle;
        public final String description;

        public StoreItem(String id, String title, String emoji, int price, String subtitle, String description) {
            this.id = id;
            this.title = title;
            this.emoji = emoji;
            this.price = price;
            this.subtitle = subtitle;
            this.description = description;
        }
    }

    public static class OrderRecord {
        public final String orderId;
        public final String itemId;
        public final String itemTitle;
        public final int pricePaid;
        public final long timestamp;
        public final String status;

        public OrderRecord(String orderId, String itemId, String itemTitle, int pricePaid, long timestamp, String status) {
            this.orderId = orderId;
            this.itemId = itemId;
            this.itemTitle = itemTitle;
            this.pricePaid = pricePaid;
            this.timestamp = timestamp;
            this.status = status;
        }

        public String getFormattedDate() {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        }
    }

    public RewardStoreManager(Context context, String userId) {
        this.context = context.getApplicationContext();
        this.userId = (userId != null && !userId.trim().isEmpty()) ? userId.trim() : "default";
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static List<StoreItem> getAvailableStoreItems() {
        List<StoreItem> list = new ArrayList<>();
        list.add(new StoreItem(
                ITEM_WATER_BOTTLE,
                "Focus Hydro Water Bottle",
                "🍶",
                PRICE_WATER_BOTTLE,
                "750ml Vacuum-Insulated Stainless Steel",
                "Keeps drinks icy cold for 24h. Laser-engraved focus markers and matte navy grip to stay hydrated while studying."
        ));
        list.add(new StoreItem(
                ITEM_STUDY_PAD,
                "Ergonomic Focus Study Pad",
                "📝",
                PRICE_STUDY_PAD,
                "900×400mm Extended Anti-Slip Desk Mat",
                "Micro-woven precision surface engineered for desk organization, keyboard comfort, and printed Pomodoro focus grids."
        ));
        list.add(new StoreItem(
                ITEM_T_SHIRT,
                "Deep Focus Edition T-Shirt",
                "👕",
                PRICE_T_SHIRT,
                "100% Combed Heavyweight Organic Cotton",
                "Exclusive AttentionAI Deep Work edition with custom embroidered emblem and tailored heavyweight streetwear comfort."
        ));
        return list;
    }

    public double getAvailableCoins() {
        double earned = SessionDatabaseHelper.getInstance(context).getTotalCoins(userId);
        double bonus = prefs.getFloat(KEY_BONUS_COINS + userId, 0f);
        double spent = prefs.getFloat(KEY_SPENT_COINS + userId, 0f);
        double available = earned + bonus - spent;
        return Math.max(0.0, Math.round(available * 100.0) / 100.0);
    }

    public void addBonusCoins(double amount) {
        float current = prefs.getFloat(KEY_BONUS_COINS + userId, 0f);
        prefs.edit().putFloat(KEY_BONUS_COINS + userId, (float) (current + amount)).apply();
    }

    public boolean isItemPurchased(String itemId) {
        List<OrderRecord> orders = getOrders();
        for (OrderRecord o : orders) {
            if (o.itemId.equals(itemId)) return true;
        }
        return false;
    }

    public int getPurchaseCount(String itemId) {
        int count = 0;
        for (OrderRecord o : getOrders()) {
            if (o.itemId.equals(itemId)) count++;
        }
        return count;
    }

    public boolean canAfford(StoreItem item) {
        return getAvailableCoins() >= item.price;
    }

    public OrderRecord purchaseItem(StoreItem item) {
        if (!canAfford(item)) return null;

        // Deduct coins
        float currentSpent = prefs.getFloat(KEY_SPENT_COINS + userId, 0f);
        prefs.edit().putFloat(KEY_SPENT_COINS + userId, currentSpent + item.price).apply();

        // Generate unique order ID
        String orderId = "ATTN-" + (int)(1000 + Math.random() * 9000);
        OrderRecord order = new OrderRecord(
                orderId,
                item.id,
                item.title,
                item.price,
                System.currentTimeMillis(),
                "Confirmed • Processing Dispatch"
        );

        saveOrder(order);
        return order;
    }

    private void saveOrder(OrderRecord order) {
        List<OrderRecord> list = getOrders();
        list.add(0, order);

        JSONArray arr = new JSONArray();
        for (OrderRecord r : list) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("orderId", r.orderId);
                obj.put("itemId", r.itemId);
                obj.put("itemTitle", r.itemTitle);
                obj.put("pricePaid", r.pricePaid);
                obj.put("timestamp", r.timestamp);
                obj.put("status", r.status);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString(KEY_ORDERS + userId, arr.toString()).apply();
    }

    public List<OrderRecord> getOrders() {
        List<OrderRecord> list = new ArrayList<>();
        String raw = prefs.getString(KEY_ORDERS + userId, null);
        if (raw == null || raw.trim().isEmpty()) return list;

        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new OrderRecord(
                        obj.getString("orderId"),
                        obj.getString("itemId"),
                        obj.getString("itemTitle"),
                        obj.getInt("pricePaid"),
                        obj.getLong("timestamp"),
                        obj.optString("status", "Confirmed • Processing Dispatch")
                ));
            }
        } catch (Exception ignored) {}
        return list;
    }
}
