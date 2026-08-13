package com.carstream.app;

import org.json.JSONException;
import org.json.JSONObject;

public final class PlaybackCommand {
    private PlaybackCommand() { }

    public static JSONObject simple(String action) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("type", "command");
        value.put("action", action);
        return value;
    }

    public static JSONObject seekRelative(double seconds) throws JSONException {
        JSONObject value = simple("seekRelative");
        value.put("seconds", seconds);
        return value;
    }

    public static JSONObject setMedia(MediaItem item, double startAt, boolean autoplay)
            throws JSONException {
        JSONObject value = simple("setMedia");
        value.put("media", item.toJson());
        value.put("startAt", startAt);
        value.put("autoplay", autoplay);
        return value;
    }
}
