package com.carstream.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class TorBoxLibraryClient {
    private final CellularNetworkProvider networks;

    TorBoxLibraryClient(CellularNetworkProvider networks) { this.networks = networks; }

    List<MediaItem> refresh(String apiKey) throws IOException, JSONException {
        URL url = new URL(TorBoxApi.BASE + "/mylist?bypass_cache=true");
        HttpURLConnection connection = networks.open(url);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(45_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("User-Agent", "CarStream/0.1 Android");
        int status = connection.getResponseCode();
        String body = readText(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IOException("TorBox returned HTTP " + status);

        JSONObject root = new JSONObject(body);
        if (!root.optBoolean("success", true)) {
            throw new IOException(root.optString("detail", root.optString("error", "TorBox request failed")));
        }
        Object data = root.opt("data");
        JSONArray downloads = data instanceof JSONArray ? (JSONArray) data : new JSONArray();
        List<MediaItem> result = new ArrayList<>();
        for (int i = 0; i < downloads.length(); i++) {
            JSONObject download = downloads.optJSONObject(i);
            if (download == null) continue;
            long downloadId = download.optLong("id", download.optLong("torrent_id", -1));
            boolean ready = download.optBoolean("download_present", false);
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
        return Collections.unmodifiableList(result);
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

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!Strings.isBlank(value)) return value;
        return "Untitled video";
    }
}
