package com.carstream.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class PollingRegistry {
    public interface Listener { void onChanged(); }

    public static final class Session {
        public enum ControlMode { FREE, GUIDED, LOCKED }

        public final String id;
        public volatile String name;
        public volatile String mediaId = "";
        public volatile String title = "Nothing playing";
        public volatile double position;
        public volatile double duration;
        public volatile boolean paused = true;
        public volatile boolean buffering;
        public volatile boolean needsGesture;
        public volatile long lastSeen = System.currentTimeMillis();
        public volatile ControlMode controlMode = ControlMode.FREE;
        private final AtomicLong commandVersion = new AtomicLong();
        private volatile JSONObject command;

        Session(String id, String name) {
            this.id = id;
            this.name = Strings.isBlank(name) ? "Tablet" : name;
        }

        public boolean online() { return System.currentTimeMillis() - lastSeen < 10_000L; }

        public int progressPercent() {
            if (duration <= 0) return 0;
            return (int) Math.max(0, Math.min(100, Math.round(position * 100.0 / duration)));
        }

        long queue(JSONObject value) {
            command = value;
            return commandVersion.incrementAndGet();
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("mediaId", mediaId)
                    .put("title", title)
                    .put("position", position)
                    .put("duration", duration)
                    .put("paused", paused)
                    .put("buffering", buffering)
                    .put("needsGesture", needsGesture)
                    .put("online", online())
                    .put("lastSeen", lastSeen)
                    .put("progress", progressPercent())
                    .put("controlMode", controlMode.name())
                    .put("commandVersion", commandVersion.get());
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public Session update(JSONObject value) {
        String id = value.optString("clientId", "").trim();
        if (id.isEmpty()) return null;
        Session session = sessions.computeIfAbsent(id,
                key -> new Session(key, value.optString("name", "Tablet")));
        String name = value.optString("name", "").trim();
        if (!name.isEmpty()) session.name = name;
        session.mediaId = value.optString("mediaId", session.mediaId);
        session.title = value.optString("title", session.title);
        session.position = finite(value.optDouble("position", session.position));
        session.duration = finite(value.optDouble("duration", session.duration));
        session.paused = value.optBoolean("paused", session.paused);
        session.buffering = value.optBoolean("buffering", session.buffering);
        session.needsGesture = value.optBoolean("needsGesture", session.needsGesture);
        session.lastSeen = System.currentTimeMillis();
        notifyChanged();
        return session;
    }

    public JSONObject response(Session session, long seenVersion) throws JSONException {
        long current = session.commandVersion.get();
        JSONObject response = new JSONObject()
                .put("commandVersion", current)
                .put("controlMode", session.controlMode.name())
                .put("serverTime", System.currentTimeMillis());
        JSONObject pending = session.command;
        if (pending != null && current > seenVersion) response.put("command", pending);
        return response;
    }

    public boolean send(String id, JSONObject command) {
        Session session = sessions.get(id);
        if (session == null) return false;
        session.queue(command);
        notifyChanged();
        return true;
    }

    public boolean setMode(String id, Session.ControlMode mode) {
        Session session = sessions.get(id);
        if (session == null) return false;
        session.controlMode = mode;
        try {
            send(id, new JSONObject().put("action", "controlMode").put("mode", mode.name()));
        } catch (JSONException ignored) { }
        notifyChanged();
        return true;
    }

    public List<Session> snapshot() {
        List<Session> result = new ArrayList<>(sessions.values());
        result.sort(Comparator.comparing(item -> item.name.toLowerCase(Locale.US)));
        return result;
    }

    public JSONArray toJson() throws JSONException {
        JSONArray result = new JSONArray();
        for (Session session : snapshot()) result.put(session.toJson());
        return result;
    }

    private void notifyChanged() {
        for (Listener listener : listeners) listener.onChanged();
    }

    private static double finite(double value) {
        return Double.isFinite(value) && value >= 0 ? value : 0;
    }
}
