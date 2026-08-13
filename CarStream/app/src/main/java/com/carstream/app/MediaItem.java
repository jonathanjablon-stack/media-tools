package com.carstream.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

public final class MediaItem {
    public final String id;
    public final long torrentId;
    public final long fileId;
    public final String title;
    public final String path;
    public final long size;
    public final String mimeType;
    public final boolean ready;

    public MediaItem(String id, long torrentId, long fileId, String title, String path,
                     long size, String mimeType, boolean ready) {
        this.id = id;
        this.torrentId = torrentId;
        this.fileId = fileId;
        this.title = title;
        this.path = path;
        this.size = size;
        this.mimeType = mimeType;
        this.ready = ready;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("torrentId", torrentId)
                .put("fileId", fileId).put("title", title).put("path", path)
                .put("size", size).put("mimeType", mimeType).put("ready", ready);
    }

    public static boolean isPlayablePath(String path) {
        if (path == null) return false;
        String value = path.toLowerCase(Locale.US);
        return value.endsWith(".mp4") || value.endsWith(".m4v")
                || value.endsWith(".webm") || value.endsWith(".mov")
                || value.endsWith(".mkv");
    }

    public static String mimeFor(String path) {
        String value = path == null ? "" : path.toLowerCase(Locale.US);
        if (value.endsWith(".webm")) return "video/webm";
        if (value.endsWith(".mkv")) return "video/x-matroska";
        if (value.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    public static String displayName(String path) {
        if (Strings.isBlank(path)) return "Untitled video";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
