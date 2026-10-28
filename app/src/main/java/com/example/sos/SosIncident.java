package com.example.sos;

public class SosIncident {
    private final long timestamp;
    private final String trigger;
    private final String location;

    public SosIncident(long timestamp, String trigger, String location) {
        this.timestamp = timestamp;
        this.trigger = trigger;
        this.location = location;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getTrigger() {
        return trigger;
    }

    public String getLocation() {
        return location;
    }
}
