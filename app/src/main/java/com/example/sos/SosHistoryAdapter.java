package com.example.sos;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class SosHistoryAdapter extends RecyclerView.Adapter<SosHistoryAdapter.ViewHolder> {
    private final List<SosIncident> incidents;

    public SosHistoryAdapter(List<SosIncident> incidents) {
        this.incidents = incidents;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sos_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SosIncident incident = incidents.get(position);
        holder.trigger.setText(incident.getTrigger());
        holder.time.setText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(new Date(incident.getTimestamp())));
        holder.location.setText(incident.getLocation());
    }

    @Override
    public int getItemCount() {
        return incidents.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView trigger;
        final TextView time;
        final TextView location;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            trigger = itemView.findViewById(R.id.historyTrigger);
            time = itemView.findViewById(R.id.historyTime);
            location = itemView.findViewById(R.id.historyLocation);
        }
    }
}
