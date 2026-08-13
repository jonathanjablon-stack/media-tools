package com.carstream.app;

import org.json.JSONException;
import org.json.JSONObject;

public final class ClientSession {
    public enum ControlMode { SHARED, PARENT_LOCKED, OBSERVE_ONLY }

    public final String id;
    public volatile String name;
    public volatile String mediaId = "";
    public volatile String title = "Nothing playing";
    public volatile double positionSeconds;
    public volatile double durationSeconds;
    public volatile boolean paused = true;
    public volatile boolean buffering;
    public volatile boolean needsGesture;
    public volatile long lastSeenMillis = System.currentTimeMillis();
    public volatile ControlMode controlMode = ControlMode.SHARED;
    volatile WebSocketConnection socket;

    public ClientSession(String id, String name) {
        this.id = id;
        this.name = Strings.isBlank(name) ? "Tablet" : name;
    }

    public int progressPercent() {
        if (durationSeconds <= 0) return 0;
        return (int) Math.max(0, Math.min(100,
                Math.round(positionSeconds * 100.0 / durationSeconds)));
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("mediaId", mediaId)
                .put("title", title)
                .put("position", positionSeconds)
                .put("duration", durationSeconds)
                .put("paused", paused)
                .put("buffering", buffering)
                .put("needsGesture", needsGesture)
                .put("lastSeen", lastSeenMillis)
                .put("online", socket != null && socket.isOpen())
                .put("controlMode", controlMode.name());
    }
}
