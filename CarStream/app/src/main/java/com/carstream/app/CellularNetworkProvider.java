package com.carstream.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public final class CellularNetworkProvider {
    private final ConnectivityManager connectivityManager;
    private volatile Network cellularNetwork;
    private ConnectivityManager.NetworkCallback callback;

    public CellularNetworkProvider(Context context) {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public synchronized void start() {
        if (callback != null || connectivityManager == null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { promoteIfUsable(network); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (isUsableCellular(capabilities)) cellularNetwork = network;
                else if (network.equals(cellularNetwork)) cellularNetwork = null;
            }
            @Override public void onLost(Network network) {
                if (network.equals(cellularNetwork)) cellularNetwork = null;
            }
        };
        try { connectivityManager.requestNetwork(request, callback); }
        catch (SecurityException ignored) { callback = null; }
    }

    public synchronized void stop() {
        if (callback != null) {
            try { connectivityManager.unregisterNetworkCallback(callback); }
            catch (Exception ignored) { }
        }
        callback = null;
        cellularNetwork = null;
    }

    public HttpURLConnection open(URL url) throws IOException {
        Network network = findUsableCellularNetwork();
        if (network == null) {
            throw new IOException("Mobile data is not ready. Keep cellular data enabled while CarStream is running.");
        }
        return (HttpURLConnection) network.openConnection(url);
    }

    public boolean hasCellularNetwork() { return findUsableCellularNetwork() != null; }

    private void promoteIfUsable(Network network) {
        if (network == null || connectivityManager == null) return;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (isUsableCellular(capabilities)) cellularNetwork = network;
    }

    private Network findUsableCellularNetwork() {
        if (connectivityManager == null) return null;
        Network preferred = cellularNetwork;
        if (preferred != null && isUsableCellular(connectivityManager.getNetworkCapabilities(preferred))) return preferred;
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                if (isUsableCellular(connectivityManager.getNetworkCapabilities(network))) {
                    cellularNetwork = network;
                    return network;
                }
            }
        } catch (SecurityException ignored) { }
        cellularNetwork = null;
        return null;
    }

    private static boolean isUsableCellular(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
