package com.example.attentionapp;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SessionHistoryAdapter extends RecyclerView.Adapter<SessionHistoryAdapter.ViewHolder> {

    private final List<SessionRecord> list;

    public SessionHistoryAdapter(List<SessionRecord> list) {
        this.list = list;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_completed_session, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SessionRecord session = list.get(position);

        holder.tvCourse.setText(session.getTopic());
        holder.tvDateTime.setText(session.getFormattedDateTime());
        holder.tvDuration.setText("⏱️ " + session.getFormattedDuration());
        holder.tvDistraction.setText("⚡ " + session.getTabSwitches() + " tabs");
        holder.tvNotifs.setText("🔔 " + session.getNotificationsReceived() + " (" + session.getNotificationsIgnored() + " Ignored)");

        // Time of Day Tag
        String tod = session.getTimeOfDay();
        holder.tvTimeOfDayTag.setText(tod);

        // Coins Earned Badge
        if (holder.tvCoins != null) {
            holder.tvCoins.setText("+" + session.getFormattedCoins() + " 🪙");
        }

        // Score Pill styling
        int score = session.getFocusScore();
        holder.tvScore.setText(score + "%");
        if (score >= 70) {
            holder.tvScore.setTextColor(Color.parseColor("#10B981"));
            holder.tvScore.setBackgroundResource(R.drawable.bg_badge_focused);
        } else if (score >= 50) {
            holder.tvScore.setTextColor(Color.parseColor("#F59E0B"));
            holder.tvScore.setBackgroundResource(R.drawable.bg_badge_distracted);
        } else {
            holder.tvScore.setTextColor(Color.parseColor("#EF4444"));
            holder.tvScore.setBackgroundResource(R.drawable.bg_badge_lost);
        }

        // Breakdown Progress Bar (weights)
        int focused = session.getFocusedPercent();
        int distracted = session.getDistractedPercent();
        int lost = session.getLostPercent();

        LinearLayout.LayoutParams lpFocused = (LinearLayout.LayoutParams) holder.viewBarFocused.getLayoutParams();
        lpFocused.weight = Math.max(1, focused);
        holder.viewBarFocused.setLayoutParams(lpFocused);

        LinearLayout.LayoutParams lpDistracted = (LinearLayout.LayoutParams) holder.viewBarDistracted.getLayoutParams();
        lpDistracted.weight = Math.max(0, distracted);
        holder.viewBarDistracted.setLayoutParams(lpDistracted);

        LinearLayout.LayoutParams lpLost = (LinearLayout.LayoutParams) holder.viewBarLost.getLayoutParams();
        lpLost.weight = Math.max(0, lost);
        holder.viewBarLost.setLayoutParams(lpLost);
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvCourse, tvPlatform, tvDateTime, tvTimeOfDayTag, tvDuration, tvDistraction, tvNotifs, tvScore, tvCoins;
        View viewBarFocused, viewBarDistracted, viewBarLost;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCourse = itemView.findViewById(R.id.tvSessionCourse);
            tvPlatform = itemView.findViewById(R.id.tvSessionPlatform);
            tvDateTime = itemView.findViewById(R.id.tvSessionDateTime);
            tvTimeOfDayTag = itemView.findViewById(R.id.tvSessionTimeOfDayTag);
            tvDuration = itemView.findViewById(R.id.tvSessionDuration);
            tvDistraction = itemView.findViewById(R.id.tvSessionDistraction);
            tvNotifs = itemView.findViewById(R.id.tvSessionNotifs);
            tvScore = itemView.findViewById(R.id.tvSessionFocusScore);
            tvCoins = itemView.findViewById(R.id.tvSessionCoins);

            viewBarFocused = itemView.findViewById(R.id.viewBarFocused);
            viewBarDistracted = itemView.findViewById(R.id.viewBarDistracted);
            viewBarLost = itemView.findViewById(R.id.viewBarLost);
        }
    }
}
