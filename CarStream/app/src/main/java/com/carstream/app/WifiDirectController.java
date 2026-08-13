package com.carstream.app;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;

public final class WifiDirectController {
    public interface Listener {
        void onStatus(String status);
        void onGroupReady(String networkName, String passphrase, String ownerAddress);
        void onStopped();
    }

    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver;
    private boolean registered;
    private boolean running;
    private String requestedName;
    private String requestedPassphrase;

    public WifiDirectController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null ? null : manager.initialize(context, Looper.getMainLooper(),
                () -> listener.onStatus("Wi-Fi Direct channel disconnected"));
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        listener.onStatus("Wi-Fi Direct is disabled on this phone");
                    }
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    requestGroupDetails();
                }
            }
        };
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (running) { requestGroupDetails(); return; }
        if (manager == null || channel == null) {
            listener.onStatus("This phone does not expose Android Wi-Fi Direct APIs");
            return;
        }
        running = true;
        registerReceiver();
        listener.onStatus("Preparing Wi-Fi Direct group...");
        manager.requestGroupInfo(channel, group -> {
            if (group != null && group.isGroupOwner()) publishGroup(group);
            else createGroup();
        });
    }

    @SuppressLint("MissingPermission")
    private void createGroup() {
        requestedName = "DIRECT-CS-CarStream";
        requestedPassphrase = randomPassphrase();
        WifiP2pManager.ActionListener callback = new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.onStatus("Wi-Fi Direct group created; reading connection details...");
                handler.postDelayed(WifiDirectController.this::requestGroupDetails, 700);
                handler.postDelayed(WifiDirectController.this::requestGroupDetails, 2_000);
            }
            @Override public void onFailure(int reason) {
                if (Build.VERSION.SDK_INT >= 29) {
                    listener.onStatus("Custom group setup failed; trying phone-generated credentials...");
                    createLegacyGroup();
                } else listener.onStatus("Could not create Wi-Fi Direct group (code " + reason + ")");
            }
        };
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                WifiP2pConfig config = new WifiP2pConfig.Builder()
                        .setNetworkName(requestedName)
                        .setPassphrase(requestedPassphrase)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                        .enablePersistentMode(false)
                        .build();
                manager.createGroup(channel, config, callback);
            } catch (Exception e) { createLegacyGroup(); }
        } else createLegacyGroup();
    }

    @SuppressLint("MissingPermission")
    private void createLegacyGroup() {
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.onStatus("Wi-Fi Direct group created; reading connection details...");
                handler.postDelayed(WifiDirectController.this::requestGroupDetails, 1_000);
            }
            @Override public void onFailure(int reason) {
                listener.onStatus("Could not create Wi-Fi Direct group (code " + reason + ")");
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestGroupDetails() {
        if (!running || manager == null || channel == null) return;
        manager.requestGroupInfo(channel, group -> {
            if (group == null) listener.onStatus("Waiting for Wi-Fi Direct group details...");
            else publishGroup(group);
        });
    }

    @SuppressLint("MissingPermission")
    private void publishGroup(WifiP2pGroup group) {
        manager.requestConnectionInfo(channel, info -> publishGroupAndInfo(group, info));
    }

    private void publishGroupAndInfo(WifiP2pGroup group, WifiP2pInfo info) {
        String address = findGroupOwnerIpv4Address(group, info);
        String name = group.getNetworkName();
        String passphrase = group.getPassphrase();
        if (Strings.isBlank(name) && requestedName != null) name = requestedName;
        if (Strings.isBlank(passphrase) && requestedPassphrase != null) passphrase = requestedPassphrase;
        listener.onGroupReady(name == null ? "Wi-Fi Direct group" : name,
                passphrase == null ? "" : passphrase, address);
    }

    @SuppressLint("MissingPermission")
    public void stop(boolean removeGroup) {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (removeGroup && manager != null && channel != null) {
            try {
                manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { }
                    @Override public void onFailure(int reason) { }
                });
            } catch (Exception ignored) { }
        }
        unregisterReceiver();
        listener.onStopped();
    }

    private void registerReceiver() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        context.registerReceiver(receiver, filter);
        registered = true;
    }

    private void unregisterReceiver() {
        if (!registered) return;
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
        registered = false;
    }

    private static String findGroupOwnerIpv4Address(WifiP2pGroup group, WifiP2pInfo info) {
        String interfaceName = group == null ? null : group.getInterface();
        String fromGroupInterface = findIpv4OnInterface(interfaceName);
        if (fromGroupInterface != null) return fromGroupInterface;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                    String name = networkInterface.getName();
                    if (name == null || !name.toLowerCase().contains("p2p")) continue;
                    String address = findIpv4(networkInterface);
                    if (address != null) return address;
                }
            }
        } catch (Exception ignored) { }
        if (info != null && isUsableIpv4(info.groupOwnerAddress)) return info.groupOwnerAddress.getHostAddress();
        return "192.168.49.1";
    }

    private static String findIpv4OnInterface(String interfaceName) {
        if (Strings.isBlank(interfaceName)) return null;
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
            return networkInterface == null ? null : findIpv4(networkInterface);
        } catch (Exception ignored) { return null; }
    }

    private static String findIpv4(NetworkInterface networkInterface) {
        for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
            if (isUsableIpv4(address)) return address.getHostAddress();
        }
        return null;
    }

    private static boolean isUsableIpv4(InetAddress address) {
        return address instanceof Inet4Address
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress();
    }

    private static String randomPassphrase() {
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder("CarStream-");
        for (int i = 0; i < 10; i++) value.append(chars.charAt(random.nextInt(chars.length())));
        return value.toString();
    }
}
