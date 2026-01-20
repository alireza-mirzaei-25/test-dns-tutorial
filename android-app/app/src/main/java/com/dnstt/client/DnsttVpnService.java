package com.dnstt.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import hev.htproxy.TProxyService;
import mobile.Client;
import mobile.Config;
import mobile.Mobile;
import mobile.StatusCallback;

public class DnsttVpnService extends VpnService implements StatusCallback {
    private static final String TAG = "DnsttVpnService";
    private static final String CHANNEL_ID = "dnstt_vpn";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "com.dnstt.client.START";
    public static final String ACTION_STOP = "com.dnstt.client.STOP";

    public static final String EXTRA_TRANSPORT_TYPE = "transport_type";
    public static final String EXTRA_TRANSPORT_ADDR = "transport_addr";
    public static final String EXTRA_DOMAIN = "domain";
    public static final String EXTRA_PUBKEY = "pubkey";
    public static final String EXTRA_TUNNELS = "tunnels";

    private ParcelFileDescriptor vpnInterface;
    private Client dnsttClient;
    private TProxyService tun2socks;
    private volatile boolean running = false;
    private Thread statsThread;

    // Callback for UI updates
    private static StatusCallback uiCallback;

    public static void setUiCallback(StatusCallback callback) {
        uiCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        tun2socks = TProxyService.getInstance();
        log("VPN service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            log("Received null intent");
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        log("Received action: " + action);

        if (ACTION_STOP.equals(action)) {
            log("Stop action received");
            stopVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            String transportType = intent.getStringExtra(EXTRA_TRANSPORT_TYPE);
            String transportAddr = intent.getStringExtra(EXTRA_TRANSPORT_ADDR);
            String domain = intent.getStringExtra(EXTRA_DOMAIN);
            String pubkey = intent.getStringExtra(EXTRA_PUBKEY);
            int tunnels = intent.getIntExtra(EXTRA_TUNNELS, 8);

            log("Starting VPN with:");
            log("  Transport: " + transportType + " via " + transportAddr);
            log("  Domain: " + domain);
            log("  Tunnels: " + tunnels);

            startVpn(transportType, transportAddr, domain, pubkey, tunnels);
        }

        return START_STICKY;
    }

    private void startVpn(String transportType, String transportAddr, String domain, String pubkey, int tunnels) {
        // Start foreground service with proper type for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification("Connecting..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Connecting..."));
        }
        onStatusChange(1, "Initializing DNSTT client...");

        // Start DNSTT client first
        dnsttClient = Mobile.newClient();
        dnsttClient.setCallback(this);

        Config config = Mobile.newConfig();
        config.setTransportType(transportType);
        config.setTransportAddr(transportAddr);
        config.setDomain(domain);
        config.setPubkeyHex(pubkey);
        config.setListenAddr("127.0.0.1:1080");
        config.setTunnels(tunnels);
        config.setMTU(1232);
        config.setUTLSFingerprint("Chrome");
        config.setUseZstd(true); // Enable zstd compression (server has it on by default)
        log("Zstd compression: enabled");

        new Thread(() -> {
            try {
                log("Starting DNSTT client...");
                onStatusChange(1, "Establishing DNS tunnel...");
                dnsttClient.start(config);

                log("DNSTT client started, waiting for connection...");
                // Give DNSTT a moment to establish connection
                Thread.sleep(1000);

                log("Establishing VPN interface...");
                onStatusChange(1, "Establishing VPN interface...");
                // Now establish VPN with tun2socks
                establishVpn();

            } catch (Exception e) {
                log("Failed to start: " + e.getMessage());
                onStatusChange(3, "Error: " + e.getMessage());
                stopSelf();
            }
        }).start();
    }

    private void establishVpn() {
        try {
            log("Building VPN interface...");
            Builder builder = new Builder();
            builder.setSession("DNSTT VPN")
                    .addAddress("10.0.0.2", 24)
                    .setMtu(8500)  // Higher MTU for tun2socks efficiency
                    .setBlocking(false);  // Non-blocking for tun2socks

            // Route all traffic except DNS servers through the tunnel
            // We split the 0.0.0.0/0 route to exclude 8.8.8.8 and 8.8.4.4
            // This allows DNS to work normally while other traffic goes through tunnel
            builder.addRoute("0.0.0.0", 1);    // 0.0.0.0 - 127.255.255.255
            builder.addRoute("128.0.0.0", 1);  // 128.0.0.0 - 255.255.255.255

            // Note: Not adding DNS servers means the system will use existing DNS
            // This is intentional since DNSTT's SOCKS5 doesn't support UDP

            // Exclude our own app to prevent loops
            try {
                builder.addDisallowedApplication(getPackageName());
                log("Excluded self from VPN to prevent loops");
            } catch (Exception e) {
                log("Could not exclude self: " + e.getMessage());
            }

            vpnInterface = builder.establish();

            if (vpnInterface == null) {
                throw new IOException("VPN interface is null - permission may have been revoked");
            }

            log("VPN interface established successfully");
            log("  Address: 10.0.0.2/24");
            log("  MTU: 8500");
            log("  Routes: 0.0.0.0/1, 128.0.0.0/1 (excludes DNS)");

            // Create tun2socks config file
            String configPath = createTun2socksConfig();
            log("Created tun2socks config at: " + configPath);

            // Start tun2socks with the TUN fd
            int tunFd = vpnInterface.getFd();
            log("Starting tun2socks with TUN fd: " + tunFd);
            tun2socks.TProxyStartService(configPath, tunFd);
            log("tun2socks started successfully");

            running = true;

            // Start stats monitoring thread
            startStatsMonitor();

            updateNotification("Connected");
            onStatusChange(2, "VPN Connected - All traffic routed through tunnel");

        } catch (Exception e) {
            log("Failed to establish VPN: " + e.getMessage());
            e.printStackTrace();
            onStatusChange(3, "VPN Error: " + e.getMessage());
            stopVpn();
        }
    }

    private String createTun2socksConfig() throws IOException {
        // Create YAML config for hev-socks5-tunnel
        // Note: DNSTT SOCKS5 proxy only supports TCP, so we use udp: 'tcp' to tunnel
        // UDP packets (like DNS) over TCP through the SOCKS5 proxy.
        String config = "tunnel:\n" +
                "  name: tun0\n" +
                "  mtu: 8500\n" +
                "  ipv4: 10.0.0.2\n" +
                "\n" +
                "socks5:\n" +
                "  port: 1080\n" +
                "  address: 127.0.0.1\n" +
                "  udp: 'tcp'\n" +
                "\n" +
                "misc:\n" +
                "  task-stack-size: 81920\n" +
                "  connect-timeout: 5000\n" +
                "  read-write-timeout: 60000\n" +
                "  log-level: debug\n";

        File configFile = new File(getCacheDir(), "tun2socks.yml");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(config.getBytes());
        }

        return configFile.getAbsolutePath();
    }

    private void startStatsMonitor() {
        statsThread = new Thread(() -> {
            long lastTxBytes = 0;
            long lastRxBytes = 0;

            while (running) {
                try {
                    Thread.sleep(1000);

                    if (!running) break;

                    long[] stats = tun2socks.TProxyGetStats();
                    if (stats != null && stats.length >= 4) {
                        long txBytes = stats[1];
                        long rxBytes = stats[3];

                        // Report to UI
                        if (uiCallback != null) {
                            uiCallback.onBytesTransferred(rxBytes, txBytes);
                        }

                        // Log significant changes
                        if (txBytes - lastTxBytes > 10000 || rxBytes - lastRxBytes > 10000) {
                            log("Traffic: TX=" + formatBytes(txBytes) + " RX=" + formatBytes(rxBytes));
                            lastTxBytes = txBytes;
                            lastRxBytes = rxBytes;
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log("Stats error: " + e.getMessage());
                }
            }
        }, "StatsThread");
        statsThread.start();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }

    private void stopVpn() {
        log("Stopping VPN...");

        // Prevent multiple stop calls
        if (!running && vpnInterface == null && dnsttClient == null) {
            log("VPN already stopped");
            onStatusChange(0, "Disconnected");
            return;
        }

        running = false;

        // Stop stats thread
        if (statsThread != null) {
            statsThread.interrupt();
            statsThread = null;
        }

        // Stop tun2socks first
        try {
            log("Stopping tun2socks...");
            tun2socks.TProxyStopService();
            log("tun2socks stopped");
        } catch (Exception e) {
            log("Error stopping tun2socks: " + e.getMessage());
        }

        // Close VPN interface
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
                log("VPN interface closed");
            } catch (IOException e) {
                log("Error closing VPN interface: " + e.getMessage());
            }
            vpnInterface = null;
        }

        // Stop DNSTT client
        if (dnsttClient != null) {
            log("Stopping DNSTT client...");
            try {
                dnsttClient.stop();
                log("DNSTT client stopped");
            } catch (Exception e) {
                log("Error stopping DNSTT client: " + e.getMessage());
            }
            dnsttClient = null;
        }

        // Notify UI BEFORE stopping the service
        onStatusChange(0, "Disconnected");

        stopForeground(true);
        stopSelf();
        log("VPN service stopped");
    }

    @Override
    public void onDestroy() {
        log("VPN service being destroyed");
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void onStatusChange(long state, String message) {
        log("Status: " + state + " - " + message);

        if (state == 2) {
            updateNotification("Connected");
        } else if (state == 3) {
            updateNotification("Error");
        }

        if (uiCallback != null) {
            uiCallback.onStatusChange(state, message);
        }
    }

    @Override
    public void onBytesTransferred(long bytesIn, long bytesOut) {
        if (uiCallback != null) {
            uiCallback.onBytesTransferred(bytesIn, bytesOut);
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
        // Also send to UI if callback is set
        if (uiCallback != null) {
            uiCallback.onStatusChange(-1, "[VPN] " + message);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DNSTT VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows VPN connection status");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, DnsttVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DNSTT VPN")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(text));
        }
    }
}
