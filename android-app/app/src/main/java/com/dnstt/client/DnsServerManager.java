package com.dnstt.client;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Manages the list of DNS servers for auto-connect functionality.
 * Loads servers from assets/dns_servers.txt and provides random selection.
 */
public class DnsServerManager {
    private static final String TAG = "DnsServerManager";
    private static final String DNS_SERVERS_FILE = "dns_servers.txt";

    private final List<String> dnsServers;
    private final Random random;
    private int currentIndex;

    public DnsServerManager(Context context) {
        this.dnsServers = new ArrayList<>();
        this.random = new Random();
        this.currentIndex = 0;
        loadServers(context);
    }

    private void loadServers(Context context) {
        try {
            InputStream is = context.getAssets().open(DNS_SERVERS_FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    dnsServers.add(line);
                }
            }
            reader.close();
            Log.d(TAG, "Loaded " + dnsServers.size() + " DNS servers");

            // Shuffle for randomness
            Collections.shuffle(dnsServers, random);

            // Move well-known reliable public DNS servers to the front
            // These are most likely to work in various network conditions
            prioritizeServer("8.8.8.8");      // Google
            prioritizeServer("8.8.4.4");      // Google
            prioritizeServer("1.1.1.1");      // Cloudflare
            prioritizeServer("1.0.0.1");      // Cloudflare
            prioritizeServer("9.9.9.9");      // Quad9
            prioritizeServer("149.112.112.112"); // Quad9
            prioritizeServer("208.67.222.222"); // OpenDNS
            prioritizeServer("208.67.220.220"); // OpenDNS
        } catch (IOException e) {
            Log.e(TAG, "Failed to load DNS servers: " + e.getMessage());
            // Add some fallback servers
            dnsServers.add("1.1.1.1");
            dnsServers.add("8.8.8.8");
            dnsServers.add("9.9.9.9");
        }
    }

    /**
     * Move a server to the front of the list if it exists.
     */
    private void prioritizeServer(String server) {
        if (dnsServers.remove(server)) {
            dnsServers.add(0, server);
        }
    }

    /**
     * Get a random DNS server from the list.
     */
    public String getRandomServer() {
        if (dnsServers.isEmpty()) {
            return "1.1.1.1";
        }
        return dnsServers.get(random.nextInt(dnsServers.size()));
    }

    /**
     * Get the next DNS server in the list (for sequential rotation).
     */
    public String getNextServer() {
        if (dnsServers.isEmpty()) {
            return "1.1.1.1";
        }
        String server = dnsServers.get(currentIndex);
        currentIndex = (currentIndex + 1) % dnsServers.size();
        return server;
    }

    /**
     * Get multiple random servers.
     */
    public List<String> getRandomServers(int count) {
        List<String> selected = new ArrayList<>();
        if (dnsServers.isEmpty()) {
            selected.add("1.1.1.1");
            return selected;
        }

        // Shuffle and take first N
        List<String> shuffled = new ArrayList<>(dnsServers);
        Collections.shuffle(shuffled, random);
        for (int i = 0; i < Math.min(count, shuffled.size()); i++) {
            selected.add(shuffled.get(i));
        }
        return selected;
    }

    /**
     * Format a DNS server address for UDP transport (adds port 53).
     */
    public String formatForUdp(String server) {
        if (server.contains(":")) {
            return server; // Already has port
        }
        return server + ":53";
    }

    /**
     * Get total number of available servers.
     */
    public int getServerCount() {
        return dnsServers.size();
    }

    /**
     * Check if we have servers loaded.
     */
    public boolean hasServers() {
        return !dnsServers.isEmpty();
    }

    /**
     * Get all servers as a newline-separated string (for Go API).
     */
    public String getAllServersAsString() {
        StringBuilder sb = new StringBuilder();
        for (String server : dnsServers) {
            sb.append(server).append("\n");
        }
        return sb.toString();
    }

    /**
     * Get a subset of servers as a newline-separated string (for testing).
     * @param maxCount Maximum number of servers to return
     */
    public String getServersAsString(int maxCount) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(maxCount, dnsServers.size());
        for (int i = 0; i < count; i++) {
            sb.append(dnsServers.get(i)).append("\n");
        }
        return sb.toString();
    }
}
