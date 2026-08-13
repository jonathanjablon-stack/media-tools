package com.carstream.app;

import java.net.HttpURLConnection;

final class TorBoxApi {
    static final String BASE = "https://api.torbox.app/v1/api/torrents";
    private TorBoxApi() { }
    static void authorize(HttpURLConnection connection, String key) {
        connection.setRequestProperty("Authorization", "Bearer " + key);
    }
}
