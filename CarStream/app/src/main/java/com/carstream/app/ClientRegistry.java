package com.carstream.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ClientRegistry {
    public interface Listener { void onClientsChanged(); }
    private final ConcurrentHashMap<String, ClientSession> values = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener value) { listeners.addIfAbsent(value); }
    public void removeListener(Listener value) { listeners.remove(value); }

    public ClientSession register(String id, String name, WebSocketConnection channel) {
        ClientSession item = values.computeIfAbsent(id, key -> new ClientSession(key, name));
        if (!Strings.isBlank(name)) item.name = name;
        item.socket = channel;
        item.lastSeenMillis = System.currentTimeMillis();
        changed();
        return item;
    }

    public void disconnected(String id, WebSocketConnection channel) {
        ClientSession item = values.get(id);
        if (item != null && item.socket == channel) item.socket = null;
        changed();
    }

    public void applyTelemetry(String id, JSONObject data) {
        ClientSession item = values.get(id);
        if (item == null) return;
        item.mediaId = data.optString("mediaId", item.mediaId);
        item.title = data.optString("title", item.title);
        item.positionSeconds = data.optDouble("position", item.positionSeconds);
        item.durationSeconds = data.optDouble("duration", item.durationSeconds);
        item.paused = data.optBoolean("paused", item.paused);
        item.buffering = data.optBoolean("buffering", item.buffering);
        item.needsGesture = data.optBoolean("needsGesture", false);
        item.lastSeenMillis = System.currentTimeMillis();
        changed();
    }

    public boolean send(String id, JSONObject data) {
        ClientSession item = values.get(id);
        if (item == null || item.socket == null || !item.socket.isOpen()) return false;
        try { item.socket.sendText(data.toString()); return true; }
        catch (Exception ignored) { return false; }
    }

    public boolean setControlMode(String id, ClientSession.ControlMode mode) {
        ClientSession item = values.get(id);
        if (item == null) return false;
        item.controlMode = mode;
        try { send(id, PlaybackCommand.simple("controlMode").put("mode", mode.name())); }
        catch (JSONException ignored) { }
        changed();
        return true;
    }

    public List<ClientSession> snapshot() {
        List<ClientSession> result = new ArrayList<>(values.values());
        result.sort(Comparator.comparing(item -> item.name.toLowerCase()));
        return result;
    }

    public JSONArray toJson() throws JSONException {
        JSONArray result = new JSONArray();
        for (ClientSession item : snapshot()) result.put(item.toJson());
        return result;
    }

    public void closeAll() {
        for (ClientSession item : values.values()) if (item.socket != null) item.socket.closeQuietly();
    }

    private void changed() { for (Listener item : listeners) item.onClientsChanged(); }
}
