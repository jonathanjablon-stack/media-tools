package com.carstream.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class TorBoxAccess {
    private final CellularNetworkProvider networks;
    private volatile List<MediaItem> current = Collections.emptyList();
    private volatile String apiKey = "";

    TorBoxAccess(CellularNetworkProvider networks) { this.networks = networks; }

    void setApiKey(String value) { apiKey = value == null ? "" : value.trim(); }

    List<MediaItem> currentLibrary() { return current; }

    MediaItem find(String id) {
        if (id == null) return null;
        for (MediaItem item : current) if (id.equals(item.id)) return item;
        return null;
    }

    List<MediaItem> refresh(String key) throws IOException, JSONException {
        setApiKey(key);
        URL url = new URL(TorBoxApi.BASE + "/mylist?bypass_cache=true&limit=1000");
        HttpURLConnection connection = networks.open(url);
        configure(connection, "GET", key);
        int status = connection.getResponseCode();
        String body = readText(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        requireSuccess(status, body, "TorBox library request failed");

        JSONObject root = new JSONObject(body);
        Object data = root.opt("data");
        JSONArray downloads = data instanceof JSONArray ? (JSONArray) data : new JSONArray();
        List<MediaItem> result = new ArrayList<>();
        for (int i = 0; i < downloads.length(); i++) {
            JSONObject download = downloads.optJSONObject(i);
            if (download == null) continue;
            long downloadId = download.optLong("id", download.optLong("torrent_id", -1));
            if (downloadId < 0) continue;
            boolean ready = isDownloadReady(download);
            JSONArray files = download.optJSONArray("files");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                JSONObject file = files.optJSONObject(j);
                if (file == null) continue;
                long fileId = file.optLong("id", file.optLong("file_id", j));
                String path = firstNonBlank(file.optString("name"),
                        file.optString("short_name"), file.optString("path"));
                if (!MediaItem.isPlayablePath(path)) continue;
                result.add(new MediaItem(downloadId + ":" + fileId,
                        downloadId, fileId, MediaItem.displayName(path), path,
                        file.optLong("size", 0), MediaItem.mimeFor(path), ready));
            }
        }
        result.sort(Comparator.comparing(item -> item.title.toLowerCase(Locale.US)));
        current = Collections.unmodifiableList(result);
        return current;
    }

    URL createStreamUrl(MediaItem item) throws IOException, JSONException {
        String key = apiKey;
        if (Strings.isBlank(key)) throw new IOException("TorBox API key is not configured");
        String query = "?token=" + encode(key)
                + "&torrent_id=" + item.torrentId
                + "&file_id=" + item.fileId
                + "&zip_link=false&redirect=false&append_name=false";
        HttpURLConnection connection = networks.open(new URL(TorBoxApi.BASE + "/requestdl" + query));
        configure(connection, "GET", key);
        int status = connection.getResponseCode();
        String body = readText(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        requireSuccess(status, body, "Could not create TorBox stream link");
        JSONObject root = new JSONObject(body);
        String value = root.optString("data", "").trim();
        if (value.isEmpty()) throw new IOException("TorBox returned an empty stream link");
        return new URL(value);
    }

    void addMagnet(String key, String magnet) throws IOException, JSONException {
        setApiKey(key);
        String boundary = "CarStream-" + UUID.randomUUID();
        HttpURLConnection connection = networks.open(new URL(TorBoxApi.BASE + "/createtorrent"));
        configure(connection, "POST", key);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
            writeField(output, boundary, "magnet", magnet);
            writeField(output, boundary, "seed", "1");
            writeField(output, boundary, "allow_zip", "false");
            output.writeBytes("--" + boundary + "--\r\n");
        }
        int status = connection.getResponseCode();
        String body = readText(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        requireSuccess(status, body, "TorBox rejected the magnet");
    }

    private static void configure(HttpURLConnection connection, String method, String key)
            throws IOException {
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "CarStream/0.1 Android");
    }

    private static boolean isDownloadReady(JSONObject download) {
        if (download.optBoolean("download_present", false)) return true;
        String state = firstNonBlank(download.optString("download_state"),
                download.optString("state"), download.optString("status")).toLowerCase(Locale.US);
        return state.equals("downloaded") || state.equals("cached")
                || state.equals("completed") || state.equals("download_ready");
    }

    private static void writeField(DataOutputStream output, String boundary,
                                   String name, String value) throws IOException {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.writeBytes("\r\n");
    }

    private static void requireSuccess(int status, String body, String fallback)
            throws IOException, JSONException {
        JSONObject root;
        try { root = body.isEmpty() ? new JSONObject() : new JSONObject(body); }
        catch (JSONException error) {
            if (status < 200 || status >= 300) throw new IOException(fallback + " (HTTP " + status + ")");
            throw error;
        }
        boolean apiSuccess = root.optBoolean("success", status >= 200 && status < 300);
        if (status < 200 || status >= 300 || !apiSuccess) {
            String detail = firstNonBlank(root.optString("detail"), root.optString("error"),
                    root.optString("message"), fallback);
            throw new IOException(detail + " (HTTP " + status + ")");
        }
    }

    private static String readText(InputStream input) throws IOException {
        if (input == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) out.append(buffer, 0, count);
            return out.toString();
        }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!Strings.isBlank(value)) return value;
        return "";
    }
}
