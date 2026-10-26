package com.example.attentionapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class EventAdapter extends RecyclerView.Adapter<EventAdapter.EventViewHolder> {

    private final List<AttentionEvent> eventList;

    public EventAdapter(List<AttentionEvent> eventList) {
        this.eventList = eventList;
    }

    @NonNull
    @Override
    public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attention_event, parent, false);
        return new EventViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
        AttentionEvent event = eventList.get(position);
        holder.tvTitle.setText(event.getTitle());
        holder.tvDetails.setText(event.getDetails());
        holder.tvTime.setText(event.getTimestamp());

        if (event.getScoreChange() < 0) {
            holder.tvScore.setText(event.getScoreChange() + "%");
            holder.tvScore.setTextColor(holder.itemView.getContext().getColor(R.color.status_lost));
            holder.tvIcon.setText("⚠️");
        } else if (event.getScoreChange() > 0) {
            holder.tvScore.setText("+" + event.getScoreChange() + "%");
            holder.tvScore.setTextColor(holder.itemView.getContext().getColor(R.color.status_focused));
            holder.tvIcon.setText("✓");
        } else {
            holder.tvScore.setText("0%");
            holder.tvScore.setTextColor(holder.itemView.getContext().getColor(R.color.text_secondary));
            holder.tvIcon.setText("👁️");
        }
    }

    @Override
    public int getItemCount() {
        return eventList.size();
    }

    static class EventViewHolder extends RecyclerView.ViewHolder {
        TextView tvIcon, tvTitle, tvDetails, tvTime, tvScore;

        public EventViewHolder(@NonNull View itemView) {
            super(itemView);
            tvIcon = itemView.findViewById(R.id.tvEventIcon);
            tvTitle = itemView.findViewById(R.id.tvEventTitle);
            tvDetails = itemView.findViewById(R.id.tvEventDetails);
            tvTime = itemView.findViewById(R.id.tvEventTime);
            tvScore = itemView.findViewById(R.id.tvScorePenalty);
        }
    }
}
