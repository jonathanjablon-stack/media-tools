package com.carstream.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HostService extends Service implements WifiDirectController.Listener {
    public interface Listener { void onCarStreamChanged(); }

    public static final int PORT = 8844;
    private static final int NOTIFICATION_ID = 8844;
    private static final String CHANNEL_ID = "carstream_host";

    public final class LocalBinder extends Binder {
        public HostService getService() { return HostService.this; }
    }

    public static final class Snapshot {
        public final boolean running;
        public final boolean cellularReady;
        public final boolean apiKeyConfigured;
        public final String status;
        public final String networkName;
        public final String passphrase;
        public final String ownerAddress;
        public final String tabletUrl;
        public final String libraryMessage;
        public final List<PollingRegistry.Session> clients;
        public final List<MediaItem> library;

        Snapshot(boolean running, boolean cellularReady, boolean apiKeyConfigured,
                 String status, String networkName, String passphrase, String ownerAddress,
                 String tabletUrl, String libraryMessage,
                 List<PollingRegistry.Session> clients, List<MediaItem> library) {
            this.running = running;
            this.cellularReady = cellularReady;
            this.apiKeyConfigured = apiKeyConfigured;
            this.status = status;
            this.networkName = networkName;
            this.passphrase = passphrase;
            this.ownerAddress = ownerAddress;
            this.tabletUrl = tabletUrl;
            this.libraryMessage = libraryMessage;
            this.clients = Collections.unmodifiableList(new ArrayList<>(clients));
            this.library = Collections.unmodifiableList(new ArrayList<>(library));
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SecureStore secureStore;
    private CellularNetworkProvider networks;
    private TorBoxLibraryClient library;
    private PollingRegistry registry;
    private WifiDirectController wifiDirect;
    private EmbeddedServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private volatile boolean running;
    private volatile String status = "Stopped";
    private volatile String networkName = "";
    private volatile String passphrase = "";
    private volatile String ownerAddress = "";
    private volatile String tabletUrl = "";
    private volatile String libraryMessage = "TorBox library has not been loaded.";
    private String accessToken;

    @Override public void onCreate() {
        super.onCreate();
        secureStore = new SecureStore(this);
        networks = new CellularNetworkProvider(this);
        library = new TorBoxLibraryClient(networks);
        registry = new PollingRegistry();
        registry.addListener(this::notifyChanged);
        wifiDirect = new WifiDirectController(this, this);
        accessToken = randomToken();
        server = new EmbeddedServer(this, PORT, accessToken, registry, library, networks);
        String saved = secureStore.loadTorBoxKey();
        if (!Strings.isBlank(saved)) library.setApiKey(saved);
        createNotificationChannel();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) stopCarStream();
        return START_NOT_STICKY;
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public synchronized void startCarStream() {
        if (running) {
            wifiDirect.start();
            notifyChanged();
            return;
        }
        status = "Starting local server...";
        startForeground(NOTIFICATION_ID, buildNotification(status));
        networks.start();
        acquireLocks();
        try {
            server.start();
            running = true;
            status = "Starting Wi-Fi Direct...";
            updateNotification();
            wifiDirect.start();
            refreshLibrary();
        } catch (IOException error) {
            running = false;
            status = "Could not start local server: " + safeMessage(error);
            networks.stop();
            releaseLocks();
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        notifyChanged();
    }

    public synchronized void stopCarStream() {
        running = false;
        try { wifiDirect.stop(true); } catch (Exception ignored) { }
        try { server.close(); } catch (Exception ignored) { }
        networks.stop();
        releaseLocks();
        networkName = "";
        passphrase = "";
        ownerAddress = "";
        tabletUrl = "";
        status = "Stopped";
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyChanged();
        stopSelf();
    }

    public void saveApiKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isEmpty()) {
            libraryMessage = "Enter a TorBox API key first.";
            notifyChanged();
            return;
        }
        executor.execute(() -> {
            try {
                secureStore.saveTorBoxKey(key);
                library.setApiKey(key);
                libraryMessage = "API key saved. Loading TorBox library...";
                notifyChanged();
                refreshLibraryInternal(key);
            } catch (Exception error) {
                libraryMessage = "Could not save API key: " + safeMessage(error);
                notifyChanged();
            }
        });
    }

    public void refreshLibrary() {
        String key = secureStore.loadTorBoxKey();
        if (Strings.isBlank(key)) {
            libraryMessage = "TorBox API key is not configured.";
            notifyChanged();
            return;
        }
        library.setApiKey(key);
        executor.execute(() -> refreshLibraryInternal(key));
    }

    private void refreshLibraryInternal(String key) {
        try {
            libraryMessage = "Refreshing TorBox library...";
            notifyChanged();
            List<MediaItem> items = library.refresh(key);
            libraryMessage = items.isEmpty()
                    ? "No playable video files were found in download-ready torrents."
                    : items.size() + " playable video file" + (items.size() == 1 ? "" : "s") + " loaded.";
        } catch (Exception error) {
            libraryMessage = "TorBox library error: " + safeMessage(error);
        }
        notifyChanged();
    }

    public void addMagnet(String magnet) {
        String value = magnet == null ? "" : magnet.trim();
        if (!value.startsWith("magnet:?")) {
            libraryMessage = "Paste a complete magnet link.";
            notifyChanged();
            return;
        }
        String key = secureStore.loadTorBoxKey();
        if (Strings.isBlank(key)) {
            libraryMessage = "Save the TorBox API key before adding a magnet.";
            notifyChanged();
            return;
        }
        executor.execute(() -> {
            try {
                libraryMessage = "Adding magnet to TorBox...";
                notifyChanged();
                library.addMagnet(key, value);
                libraryMessage = "Magnet added. TorBox may need time to prepare uncached files.";
                notifyChanged();
                refreshLibraryInternal(key);
            } catch (Exception error) {
                libraryMessage = "Could not add magnet: " + safeMessage(error);
                notifyChanged();
            }
        });
    }

    public boolean sendPlayPause(String clientId, boolean play) {
        return sendSimple(clientId, play ? "play" : "pause");
    }

    public boolean sendStop(String clientId) { return sendSimple(clientId, "stop"); }

    public boolean sendSeekRelative(String clientId, double seconds) {
        try {
            return registry.send(clientId, new JSONObject()
                    .put("action", "seekRelative")
                    .put("seconds", seconds));
        } catch (JSONException ignored) { return false; }
    }

    public boolean playMedia(String clientId, String mediaId) {
        MediaItem item = library.find(mediaId);
        if (item == null) return false;
        try {
            return registry.send(clientId, new JSONObject()
                    .put("action", "setMedia")
                    .put("media", item.toJson())
                    .put("startAt", 0)
                    .put("autoplay", true));
        } catch (JSONException ignored) { return false; }
    }

    public boolean setControlMode(String clientId, PollingRegistry.Session.ControlMode mode) {
        return registry.setMode(clientId, mode);
    }

    private boolean sendSimple(String clientId, String action) {
        try { return registry.send(clientId, new JSONObject().put("action", action)); }
        catch (JSONException ignored) { return false; }
    }

    public Snapshot getSnapshot() {
        return new Snapshot(running, networks.hasCellularNetwork(),
                !Strings.isBlank(secureStore.loadTorBoxKey()), status,
                networkName, passphrase, ownerAddress, tabletUrl, libraryMessage,
                registry.snapshot(), library.currentLibrary());
    }

    @Override public void onStatus(String value) {
        status = value == null ? "" : value;
        updateNotification();
        notifyChanged();
    }

    @Override public void onGroupReady(String name, String password, String address) {
        networkName = name == null ? "" : name;
        passphrase = password == null ? "" : password;
        ownerAddress = address == null ? "" : address;
        tabletUrl = ownerAddress.isEmpty() ? ""
                : "http://" + ownerAddress + ":" + PORT + "/?token=" + accessToken;
        status = "Ready. Join the Wi-Fi Direct network from each tablet.";
        updateNotification();
        notifyChanged();
    }

    @Override public void onStopped() {
        if (running) status = "Wi-Fi Direct stopped";
        notifyChanged();
    }

    private void notifyChanged() {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onCarStreamChanged();
        });
    }

    private void acquireLocks() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarStream:HostWakeLock");
            wakeLock.setReferenceCounted(false);
            try { wakeLock.acquire(); } catch (Exception ignored) { }
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CarStream:WifiLock");
            wifiLock.setReferenceCounted(false);
            try { wifiLock.acquire(); } catch (Exception ignored) { }
        }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        wakeLock = null;
        wifiLock = null;
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "CarStream host", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the local in-car media server active");
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle("CarStream")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        if (!running) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(status));
    }

    private static String randomToken() {
        byte[] data = new byte[24];
        new SecureRandom().nextBytes(data);
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return Strings.isBlank(message) ? error.getClass().getSimpleName() : message;
    }

    @Override public void onDestroy() {
        running = false;
        try { wifiDirect.stop(true); } catch (Exception ignored) { }
        try { server.close(); } catch (Exception ignored) { }
        networks.stop();
        releaseLocks();
        executor.shutdownNow();
        super.onDestroy();
    }
}
