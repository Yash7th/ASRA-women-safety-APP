package com.example.sos;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class SosHistoryActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sos_history);
        setTitle("SOS History");

        RecyclerView historyList = findViewById(R.id.sosHistoryList);
        TextView emptyState = findViewById(R.id.sosHistoryEmpty);
        ArrayList<SosIncident> incidents = new DatabaseHelper(this).fetchIncidents();
        historyList.setLayoutManager(new LinearLayoutManager(this));
        historyList.setAdapter(new SosHistoryAdapter(incidents));
        emptyState.setVisibility(incidents.isEmpty() ? TextView.VISIBLE : TextView.GONE);
    }
}
