package com.dnstt.client;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import mobile.Client;
import mobile.Config;
import mobile.Mobile;
import mobile.ResolverCallback;
import mobile.StatusCallback;

public class MainActivity extends AppCompatActivity implements StatusCallback {

    private static final String PREFS_NAME = "dnstt_prefs";

    private Client client;
    private Handler handler;
    private volatile boolean isConnected = false;
    private volatile boolean isSearching = false;
    private volatile boolean cancelSearch = false;
    private boolean vpnMode = true;
    private boolean autoConnect = false;
    private boolean useAutoDns = true;  // Auto DNS: test and select best resolver
    private boolean hasAutoConnected = false;

    // Performance settings
    private int parallelThreads = 5;  // Number of parallel DNS tests (1-10)
    private int dnsTimeout = 3000;  // DNS test timeout in milliseconds (500-10000)
    private String currentConnectedDns = null;  // Track current connected DNS for retry

    private DnsServerManager dnsServerManager;
    private Thread searchThread = null;
    private ExecutorService dnsTestExecutor = null;  // Track parallel DNS testing executor
    private static final long SEARCH_TIMEOUT_MS = 60000; // 60 seconds total timeout for DNS search

    // DoH provider presets - name -> URL mapping
    private static final String[][] DOH_PROVIDERS = {
        {"Google", "https://dns.google/dns-query"},
        {"Cloudflare", "https://cloudflare-dns.com/dns-query"},
        {"Cloudflare (1.1.1.1)", "https://1.1.1.1/dns-query"},
        {"Quad9", "https://dns.quad9.net/dns-query"},
        {"AdGuard", "https://dns.adguard.com/dns-query"},
        {"NextDNS", "https://dns.nextdns.io/dns-query"},
        {"OpenDNS", "https://doh.opendns.com/dns-query"},
        {"Shecan (Iran)", "https://free.shecan.ir/dns-query"},
        {"403.online (Iran)", "https://dns.403.online/dns-query"},
        {"Electro (Iran)", "https://electro.ir/dns-query"},
        {"Custom", ""}  // Custom option for manual entry
    };

    // UI Elements
    private TextView statusText;
    private TextView statusSubtext;
    private View statusCircle;
    private MaterialButton connectButton;
    private MaterialButton updateButton;
    private TextView versionText;
    private MaterialCardView statsCard;
    private TextView bytesInText;
    private TextView bytesOutText;
    private TextView qualityText;
    private TextView latencyText;
    private TextView speedText;
    private ProgressBar qualityBar;
    private View qualityBarLayout;
    private TextView logText;
    private AutoCompleteTextView transportType;
    private AutoCompleteTextView dohProvider;
    private TextInputLayout dohProviderLayout;
    private TextInputLayout transportAddrLayout;
    private TextInputEditText transportAddr;
    private TextInputEditText domain;
    private TextInputEditText pubkey;
    private TextInputEditText tunnels;
    private SwitchMaterial vpnModeSwitch;
    private SwitchMaterial autoConnectSwitch;
    private SwitchMaterial autoDnsSwitch;
    private TextView autoDnsLabel;
    private AutoCompleteTextView dnsSourceDropdown;
    private MaterialButton btnConfigureDns;
    private MaterialButton retryButton;
    private TextInputEditText parallelThreadsInput;
    private TextInputEditText dnsTimeoutInput;
    private View parallelThreadsLayout;
    private View dnsTimeoutLayout;

    // App updater
    private AppUpdater appUpdater;

    // DNS config manager
    private DnsConfigManager dnsConfigManager;

    // Activity result launcher for configuration activity
    private ActivityResultLauncher<Intent> configActivityLauncher;

    // Connection quality tracking
    private long lastBytesIn = 0;
    private long lastBytesOut = 0;
    private long lastUpdateTime = 0;
    private long currentLatencyMs = 0;

    // VPN permission launcher
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());
        client = mobile.Mobile.newClient();
        client.setCallback(this);

        // Initialize app updater
        appUpdater = new AppUpdater(this);

        // Initialize DNS server manager
        dnsServerManager = new DnsServerManager(this);
        appendLog("Loaded " + dnsServerManager.getServerCount() + " DNS servers");

        // Initialize DNS config manager
        dnsConfigManager = new DnsConfigManager(this);

        // Register Configuration Activity launcher
        configActivityLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // Reload custom lists from storage to get latest changes
                    dnsConfigManager.reloadCustomLists();

                    // Refresh dropdown (user might have added/deleted custom lists)
                    setupDnsSourceDropdown();

                    // Update Auto DNS label to reflect any changes in DNS count
                    updateAutoDnsLabel();

                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String selectedDns = result.getData().getStringExtra("selected_dns");
                        String selectedDnsName = result.getData().getStringExtra("selected_dns_name");

                        if (selectedDns != null) {
                            handleDnsSelection(selectedDns, selectedDnsName);
                        }
                    }
                }
        );

        // Register VPN permission launcher
        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        appendLog("VPN permission granted");
                        startVpnService();
                    } else {
                        appendLog("VPN permission denied by user");
                        setInputsEnabled(true);
                    }
                }
        );

        initViews();
        loadSettings();
        setupTransportDropdown();

        // Set up UI callback for VPN service
        DnsttVpnService.setUiCallback(this);

        appendLog("DNSTT Client initialized");
        appendLog("VPN mode: " + (vpnMode ? "enabled" : "disabled"));
        appendLog("Auto-connect: " + (autoConnect ? "enabled" : "disabled"));
        appendLog("Auto DNS: " + (useAutoDns ? "enabled (will test resolvers)" : "disabled (manual)"));

        // Auto-connect if enabled and settings are valid
        if (autoConnect && !hasAutoConnected && hasValidSettings()) {
            hasAutoConnected = true;
            appendLog("Auto-connecting...");
            handler.postDelayed(this::connect, 500);
        }
    }

    private boolean hasValidSettings() {
        String pubkeyStr = getText(pubkey);
        String domainStr = getText(domain);
        return pubkeyStr != null && !pubkeyStr.isEmpty() &&
               domainStr != null && !domainStr.isEmpty();
    }

    private void initViews() {
        statusText = findViewById(R.id.statusText);
        statusSubtext = findViewById(R.id.statusSubtext);
        statusCircle = findViewById(R.id.statusCircle);
        connectButton = findViewById(R.id.connectButton);
        updateButton = findViewById(R.id.updateButton);
        versionText = findViewById(R.id.versionText);
        statsCard = findViewById(R.id.statsCard);
        bytesInText = findViewById(R.id.bytesInText);
        bytesOutText = findViewById(R.id.bytesOutText);
        qualityText = findViewById(R.id.qualityText);
        latencyText = findViewById(R.id.latencyText);
        speedText = findViewById(R.id.speedText);
        qualityBar = findViewById(R.id.qualityBar);
        qualityBarLayout = findViewById(R.id.qualityBarLayout);
        logText = findViewById(R.id.logText);
        transportType = findViewById(R.id.transportType);
        dohProvider = findViewById(R.id.dohProvider);
        dohProviderLayout = findViewById(R.id.dohProviderLayout);
        transportAddrLayout = findViewById(R.id.transportAddrLayout);
        transportAddr = findViewById(R.id.transportAddr);
        domain = findViewById(R.id.domain);
        pubkey = findViewById(R.id.pubkey);
        tunnels = findViewById(R.id.tunnels);
        vpnModeSwitch = findViewById(R.id.vpnModeSwitch);
        autoConnectSwitch = findViewById(R.id.autoConnectSwitch);
        autoDnsSwitch = findViewById(R.id.autoDnsSwitch);
        autoDnsLabel = findViewById(R.id.autoDnsLabel);
        dnsSourceDropdown = findViewById(R.id.dnsSourceDropdown);
        btnConfigureDns = findViewById(R.id.btnConfigureDns);
        retryButton = findViewById(R.id.retryButton);
        parallelThreadsInput = findViewById(R.id.parallelThreadsInput);
        dnsTimeoutInput = findViewById(R.id.dnsTimeoutInput);
        parallelThreadsLayout = findViewById(R.id.parallelThreadsLayout);
        dnsTimeoutLayout = findViewById(R.id.dnsTimeoutLayout);

        // Set version text
        versionText.setText("v" + appUpdater.getCurrentVersion());

        // Setup DNS source dropdown
        setupDnsSourceDropdown();

        // Setup configure DNS button
        btnConfigureDns.setOnClickListener(v -> {
            Intent intent = new Intent(this, ConfigurationActivity.class);
            configActivityLauncher.launch(intent);
        });

        // Setup retry button - initially hidden
        if (retryButton != null) {
            retryButton.setVisibility(View.GONE);
            retryButton.setOnClickListener(v -> retryWithDifferentDns());
        }

        // Setup parallel threads input with validation
        if (parallelThreadsInput != null) {
            parallelThreadsInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    try {
                        int threads = Integer.parseInt(s.toString());
                        if (threads < 1) threads = 1;
                        if (threads > 10) threads = 10;
                        parallelThreads = threads;
                        saveSettings();
                    } catch (NumberFormatException e) {
                        parallelThreads = 5; // default
                    }
                }
            });
        }

        // Setup DNS timeout input with validation
        if (dnsTimeoutInput != null) {
            dnsTimeoutInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    try {
                        int timeout = Integer.parseInt(s.toString());
                        if (timeout < 500) timeout = 500;
                        if (timeout > 10000) timeout = 10000;
                        dnsTimeout = timeout;
                        saveSettings();
                    } catch (NumberFormatException e) {
                        dnsTimeout = 3000; // default
                    }
                }
            });
        }

        // Setup update button
        updateButton.setOnClickListener(v -> checkForUpdates());

        connectButton.setOnClickListener(v -> {
            if (isSearching) {
                // Cancel search if currently searching
                cancelDnsSearch();
            } else if (isConnected) {
                disconnect();
            } else {
                connect();
            }
        });

        vpnModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            vpnMode = isChecked;
            appendLog("VPN mode " + (isChecked ? "enabled" : "disabled"));
            saveSettings();
        });

        autoConnectSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoConnect = isChecked;
            appendLog("Auto-connect " + (isChecked ? "enabled" : "disabled"));
            saveSettings();
        });

        autoDnsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useAutoDns = isChecked;
            appendLog("Auto DNS " + (isChecked ? "enabled (will test resolvers)" : "disabled (manual mode)"));
            updateAutoDnsLabel();

            if (isChecked) {
                // Auto DNS only works with UDP - automatically switch to UDP
                transportType.setText("UDP", false);
                dohProviderLayout.setVisibility(View.GONE);
                transportAddrLayout.setVisibility(View.VISIBLE);
                transportAddr.setText("(auto-select best resolver)");
                transportAddr.setEnabled(false);
                appendLog("Transport switched to UDP for Auto DNS");
            } else {
                // Manual mode - enable transport address if UDP is selected
                if (transportType.getText().toString().equalsIgnoreCase("UDP")) {
                    transportAddr.setText("1.1.1.1:53");
                    transportAddr.setEnabled(true);
                }
            }
            saveSettings();
        });

        // Setup DoH provider dropdown
        setupDohProviderDropdown();
    }

    private void updateAutoDnsLabel() {
        if (useAutoDns) {
            // Get server count based on selected source
            int serverCount = 0;
            String selectedSource = dnsConfigManager.getSelectedSource();

            if (DnsConfigManager.SOURCE_GLOBAL.equals(selectedSource)) {
                serverCount = dnsServerManager.getServerCount();
            } else {
                // Custom list selected
                String listId = dnsConfigManager.getSelectedListId();
                if (listId != null) {
                    com.dnstt.client.models.CustomDnsList list = dnsConfigManager.getCustomList(listId);
                    if (list != null) {
                        serverCount = list.getSize();
                    }
                }
            }

            autoDnsLabel.setText("Auto DNS (" + serverCount + " servers)");
        } else {
            autoDnsLabel.setText("Auto DNS (manual mode)");
        }
    }

    private void setupDohProviderDropdown() {
        // Create array of provider names
        String[] providerNames = new String[DOH_PROVIDERS.length];
        for (int i = 0; i < DOH_PROVIDERS.length; i++) {
            providerNames[i] = DOH_PROVIDERS[i][0];
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.dropdown_item, providerNames);
        dohProvider.setAdapter(adapter);

        dohProvider.setOnItemClickListener((parent, view, position, id) -> {
            String url = DOH_PROVIDERS[position][1];
            if (position == DOH_PROVIDERS.length - 1) {
                // Custom option - enable manual entry
                transportAddrLayout.setVisibility(View.VISIBLE);
                transportAddr.setEnabled(true);
                transportAddr.setText("");
                transportAddr.requestFocus();
                appendLog("DoH Provider: Custom (enter URL manually)");
            } else {
                // Preset provider - set URL and hide manual entry
                transportAddr.setText(url);
                transportAddrLayout.setVisibility(View.GONE);
                appendLog("DoH Provider: " + DOH_PROVIDERS[position][0]);
            }
            saveSettings();
        });
    }

    private void updateDohProviderVisibility() {
        String type = transportType.getText().toString();
        boolean isDoH = type.equalsIgnoreCase("DoH");
        dohProviderLayout.setVisibility(isDoH ? View.VISIBLE : View.GONE);

        // Show transport address field if not DoH OR if Custom is selected
        if (isDoH) {
            String selectedProvider = dohProvider.getText().toString();
            boolean isCustom = selectedProvider.equals("Custom");
            transportAddrLayout.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        } else {
            transportAddrLayout.setVisibility(View.VISIBLE);
        }
    }

    private void setupTransportDropdown() {
        String[] types = {"DoH", "DoT", "UDP"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.dropdown_item, types);
        transportType.setAdapter(adapter);

        transportType.setOnItemClickListener((parent, view, position, id) -> {
            switch (position) {
                case 0: // DoH
                    // Auto DNS only works with UDP - disable it when switching to DoH
                    if (useAutoDns) {
                        useAutoDns = false;
                        autoDnsSwitch.setChecked(false);
                        updateAutoDnsLabel();
                        appendLog("Auto DNS disabled (only works with UDP)");
                    }
                    // Show DoH provider dropdown, hide manual address
                    dohProviderLayout.setVisibility(View.VISIBLE);
                    String selectedProvider = dohProvider.getText().toString();
                    boolean isCustom = selectedProvider.equals("Custom");
                    transportAddrLayout.setVisibility(isCustom ? View.VISIBLE : View.GONE);
                    // Set URL based on selected provider
                    for (String[] provider : DOH_PROVIDERS) {
                        if (provider[0].equals(selectedProvider)) {
                            if (!provider[1].isEmpty()) {
                                transportAddr.setText(provider[1]);
                            }
                            break;
                        }
                    }
                    transportAddr.setEnabled(true);
                    appendLog("Transport: DoH (DNS over HTTPS)");
                    break;
                case 1: // DoT
                    // Auto DNS only works with UDP - disable it when switching to DoT
                    if (useAutoDns) {
                        useAutoDns = false;
                        autoDnsSwitch.setChecked(false);
                        updateAutoDnsLabel();
                        appendLog("Auto DNS disabled (only works with UDP)");
                    }
                    dohProviderLayout.setVisibility(View.GONE);
                    transportAddrLayout.setVisibility(View.VISIBLE);
                    transportAddr.setText("dns.google:853");
                    transportAddr.setEnabled(true);
                    appendLog("Transport: DoT (DNS over TLS)");
                    break;
                case 2: // UDP
                    dohProviderLayout.setVisibility(View.GONE);
                    transportAddrLayout.setVisibility(View.VISIBLE);
                    if (useAutoDns) {
                        transportAddr.setText("(auto-select best resolver)");
                        transportAddr.setEnabled(false);
                        appendLog("Transport: UDP - Auto DNS will test and select best");
                    } else {
                        transportAddr.setText("1.1.1.1:53");
                        transportAddr.setEnabled(true);
                        appendLog("Transport: UDP (manual)");
                    }
                    break;
            }
            saveSettings();
        });
    }

    private void setupDnsSourceDropdown() {
        // Get list of DNS sources
        List<String> sources = new ArrayList<>();
        sources.add("Global DNS");

        // Add custom lists
        List<com.dnstt.client.models.CustomDnsList> customLists = dnsConfigManager.getCustomLists();
        for (com.dnstt.client.models.CustomDnsList list : customLists) {
            sources.add(list.getName());
        }

        // Create adapter for dropdown
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, sources);
        dnsSourceDropdown.setAdapter(adapter);

        // Make it non-editable but clickable (dropdown only)
        dnsSourceDropdown.setKeyListener(null);
        dnsSourceDropdown.setFocusable(false);
        dnsSourceDropdown.setClickable(true);

        // Set current selection
        String selectedSource = dnsConfigManager.getSelectedSourceDisplayName();
        dnsSourceDropdown.setText(selectedSource, false);

        // Handle dropdown item selection
        dnsSourceDropdown.setOnItemClickListener((parent, view, position, id) -> {
            String selected = sources.get(position);

            if (selected.equals("Global DNS")) {
                dnsConfigManager.setSelectedSource(DnsConfigManager.SOURCE_GLOBAL, null);
                appendLog("DNS Source changed to: Global DNS");
                Toast.makeText(this, "Using Global DNS for Auto DNS", Toast.LENGTH_SHORT).show();
            } else {
                // Find the custom list by name
                for (com.dnstt.client.models.CustomDnsList list : customLists) {
                    if (list.getName().equals(selected)) {
                        dnsConfigManager.setSelectedSource(DnsConfigManager.SOURCE_CUSTOM, list.getId());
                        appendLog("DNS Source changed to: " + list.getName() + " (" + list.getSize() + " servers)");
                        Toast.makeText(this, "Using " + list.getName() + " for Auto DNS", Toast.LENGTH_SHORT).show();
                        break;
                    }
                }
            }
            // Update Auto DNS label to reflect the new server count
            updateAutoDnsLabel();
            saveSettings();
        });

        // Show dropdown when clicked
        dnsSourceDropdown.setOnClickListener(v -> {
            dnsSourceDropdown.showDropDown();
        });
    }

    private void handleDnsSelection(String dnsAddress, String dnsName) {
        appendLog("DNS selected: " + dnsName + " (" + dnsAddress + ")");

        // Stop VPN if running
        if (isConnected) {
            appendLog("Stopping current connection...");
            disconnect();

            // Wait a moment for disconnect to complete
            handler.postDelayed(() -> applySelectedDns(dnsAddress, dnsName), 1500);
        } else {
            applySelectedDns(dnsAddress, dnsName);
        }
    }

    private void applySelectedDns(String dnsAddress, String dnsName) {
        // Disable auto DNS
        useAutoDns = false;
        autoDnsSwitch.setChecked(false);
        updateAutoDnsLabel();

        // Set UDP transport
        transportType.setText("UDP", false);

        // Set the DNS address
        transportAddr.setText(dnsAddress);
        transportAddr.setEnabled(true);

        // Update visibility
        dohProviderLayout.setVisibility(View.GONE);
        transportAddrLayout.setVisibility(View.VISIBLE);

        appendLog("Ready to connect with " + dnsName);
        appendLog("Auto DNS disabled - using selected DNS");

        saveSettings();

        Toast.makeText(this, "DNS configured: " + dnsName, Toast.LENGTH_SHORT).show();
    }

    private void connect() {
        if (!hasValidSettings()) {
            appendLog("Error: Domain and public key are required");
            return;
        }

        saveSettings();
        setInputsEnabled(false);

        String type = transportType.getText().toString();
        String dom = getText(domain);
        int numTunnels = 8;
        try {
            numTunnels = Integer.parseInt(getText(tunnels));
        } catch (NumberFormatException ignored) {}

        // If using Auto DNS and UDP, test resolvers and select the best one
        if (useAutoDns && type.equalsIgnoreCase("UDP")) {
            appendLog("Auto DNS: Testing resolvers to find best one...");
            // Use selected DNS source
            testAndConnectWithBestResolver(dom, numTunnels);
            return;
        }

        // Manual mode or non-UDP transport
        String addr = getText(transportAddr);
        appendLog("Connecting to " + dom);
        appendLog("Transport: " + type + " via " + addr);
        appendLog("Tunnels: " + numTunnels);

        if (vpnMode) {
            appendLog("Requesting VPN permission...");
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent);
            } else {
                appendLog("VPN permission already granted");
                startVpnService();
            }
        } else {
            appendLog("Starting SOCKS5 proxy mode...");
            connectSocksProxy();
        }
    }

    private void testAndConnectWithBestResolver(String dom, int numTunnels) {
        if (isSearching) {
            appendLog("Search already in progress");
            return;
        }

        String pubkeyHex = getText(pubkey);
        if (pubkeyHex == null || pubkeyHex.isEmpty()) {
            appendLog("Error: Public key is required");
            return;
        }

        // Get resolvers with prioritization (last successful first, no exclusions)
        String resolvers = dnsConfigManager.getDnsServersForAutoSearchWithPriority(null);

        // Count resolvers
        int totalResolvers = resolvers.split("\n").length;
        if (totalResolvers == 0) {
            appendLog("ERROR: No DNS servers available in selected source");
            appendLog("Please add DNS servers or switch to Global DNS");
            connectButton.setText(R.string.connect);
            statusText.setText(R.string.status_disconnected);
            statusText.setTextColor(getColor(R.color.disconnected));
            statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
            setInputsEnabled(true);
            return;
        }

        // Update button to show testing state
        isSearching = true;
        cancelSearch = false;
        connectButton.setText("Cancel");
        statusText.setText("Finding working resolver...");
        statusText.setTextColor(getColor(R.color.connecting));
        statusCircle.setBackgroundResource(R.drawable.status_circle_connecting);

        appendLog("Testing " + totalResolvers + " resolvers with " + parallelThreads + " parallel threads");

        String[] resolverArray = resolvers.split("\n");
        dnsTestExecutor = Executors.newFixedThreadPool(parallelThreads);  // Track executor for cleanup
        AtomicReference<String> foundResolver = new AtomicReference<>(null);
        AtomicInteger testedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        final long searchStartTime = System.currentTimeMillis();

        // Submit all resolvers to executor
        for (String resolver : resolverArray) {
            if (resolver.trim().isEmpty()) continue;

            dnsTestExecutor.submit(() -> {
                if (foundResolver.get() != null || cancelSearch) {
                    return; // Already found or cancelled
                }

                int tested = testedCount.incrementAndGet();
                handler.post(() -> statusText.setText("Testing: " + tested + "/" + totalResolvers));

                // Test this resolver
                final String[] result = {null};
                final long[] latency = {0};
                CountDownLatch testLatch = new CountDownLatch(1);

                try {
                    Mobile.findFirstWorkingResolver(
                        resolver.trim() + "\n",
                        dom,
                        pubkeyHex,
                        dnsTimeout, // Use configurable timeout
                        new ResolverCallback() {
                            @Override
                            public void onProgress(long tested, long total, String currentResolver) {
                                // Not used for single resolver
                            }

                            @Override
                            public void onResult(String res, boolean success, long latencyMs, String errorMsg) {
                                if (success) {
                                    result[0] = res;
                                    latency[0] = latencyMs;
                                }
                                testLatch.countDown();
                            }
                        }
                    );

                    testLatch.await(dnsTimeout + 1000, TimeUnit.MILLISECONDS);

                    if (result[0] != null && foundResolver.compareAndSet(null, result[0])) {
                        // This thread found the first working resolver!
                        final String foundDns = result[0];
                        final long foundLatency = latency[0];

                        handler.post(() -> {
                            appendLog("FOUND: " + foundDns + " (" + foundLatency + "ms)");
                            currentLatencyMs = foundLatency;
                            latencyText.setText(foundLatency + " ms");
                        });

                        latch.countDown();
                    }
                } catch (Exception e) {
                    // Test failed, continue
                }
            });
        }

        // Wait for first result or timeout in background thread
        new Thread(() -> {
            try {
                boolean found = latch.await(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (dnsTestExecutor != null) {
                    dnsTestExecutor.shutdownNow(); // Stop all threads
                    dnsTestExecutor = null;
                }

                final String workingResolver = foundResolver.get();
                final long searchDuration = System.currentTimeMillis() - searchStartTime;

                handler.post(() -> {
                    isSearching = false;

                    if (cancelSearch) {
                        appendLog("DNS search cancelled by user");
                        connectButton.setText(R.string.connect);
                        statusText.setText(R.string.status_disconnected);
                        statusText.setTextColor(getColor(R.color.disconnected));
                        statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                        setInputsEnabled(true);
                        cancelSearch = false;
                        return;
                    }

                    if (workingResolver == null || workingResolver.isEmpty()) {
                        appendLog("ERROR: No working resolver found after " + (searchDuration / 1000) + " seconds");
                        appendLog("Try switching to DoH or DoT transport");
                        connectButton.setText(R.string.connect);
                        statusText.setText(R.string.status_disconnected);
                        statusText.setTextColor(getColor(R.color.disconnected));
                        statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                        setInputsEnabled(true);
                        return;
                    }

                    // Save successful DNS for future prioritization
                    currentConnectedDns = workingResolver;
                    dnsConfigManager.saveLastSuccessfulDns(workingResolver);

                    appendLog("====================================");
                    appendLog("✓ USING DNS: " + workingResolver);
                    appendLog("====================================");
                    transportAddr.setText(workingResolver);
                    connectButton.setText(R.string.disconnect);

                    // Now connect with the working resolver
                    appendLog("Connecting via " + workingResolver);

                    if (vpnMode) {
                        appendLog("Requesting VPN permission...");
                        Intent vpnIntent = VpnService.prepare(MainActivity.this);
                        if (vpnIntent != null) {
                            vpnPermissionLauncher.launch(vpnIntent);
                        } else {
                            appendLog("VPN permission already granted");
                            startVpnService();
                        }
                    } else {
                        appendLog("Starting SOCKS5 proxy mode...");
                        connectSocksProxy();
                    }
                });
            } catch (InterruptedException e) {
                if (dnsTestExecutor != null) {
                    dnsTestExecutor.shutdownNow();
                    dnsTestExecutor = null;
                }
                handler.post(() -> {
                    isSearching = false;
                    appendLog("ERROR: Search interrupted");
                    connectButton.setText(R.string.connect);
                    statusText.setText(R.string.status_disconnected);
                    statusText.setTextColor(getColor(R.color.disconnected));
                    statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                    setInputsEnabled(true);
                });
            }
        }, "ParallelDNSSearchThread").start();
    }

    private void retryWithDifferentDns() {
        if (!isConnected || currentConnectedDns == null) {
            return;
        }

        appendLog("Moving " + currentConnectedDns + " to end of list");

        // Move current DNS to the end of the list
        dnsConfigManager.moveDnsToEnd(currentConnectedDns);

        appendLog("Retrying with different DNS from reordered list");

        // Disconnect current VPN
        disconnect();

        // Wait for disconnect to complete, then search for new DNS
        handler.postDelayed(() -> {
            String dom = getText(domain);
            int numTunnels = 8;
            try {
                numTunnels = Integer.parseInt(getText(tunnels));
            } catch (NumberFormatException ignored) {}

            // Get resolvers with new order (failed DNS now at the end)
            String resolvers = dnsConfigManager.getDnsServersForAutoSearchWithPriority(null);

            if (resolvers == null || resolvers.trim().isEmpty()) {
                appendLog("ERROR: No DNS servers available");
                Toast.makeText(this, "No DNS servers available", Toast.LENGTH_SHORT).show();
                return;
            }

            appendLog("Searching from top of reordered list...");
            testAndConnectWithBestResolver(dom, numTunnels);
        }, 1500);
    }

    private void cancelDnsSearch() {
        if (!isSearching) {
            return;
        }

        appendLog("Cancelling DNS search...");
        cancelSearch = true;
        isSearching = false;

        // Shutdown parallel DNS test executor immediately
        if (dnsTestExecutor != null) {
            appendLog("Stopping parallel DNS tests...");
            dnsTestExecutor.shutdownNow();
            dnsTestExecutor = null;
        }

        // Interrupt the search thread
        if (searchThread != null && searchThread.isAlive()) {
            searchThread.interrupt();
            searchThread = null;
        }

        // Update UI immediately
        handler.post(() -> {
            connectButton.setText(R.string.connect);
            connectButton.setEnabled(true);
            statusText.setText(R.string.status_disconnected);
            statusText.setTextColor(getColor(R.color.disconnected));
            statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
            setInputsEnabled(true);
        });
    }

    private void startVpnService() {
        appendLog("Starting VPN service...");
        Intent intent = new Intent(this, DnsttVpnService.class);
        intent.setAction(DnsttVpnService.ACTION_START);
        intent.putExtra(DnsttVpnService.EXTRA_TRANSPORT_TYPE, transportType.getText().toString().toLowerCase());
        intent.putExtra(DnsttVpnService.EXTRA_TRANSPORT_ADDR, getText(transportAddr));
        intent.putExtra(DnsttVpnService.EXTRA_DOMAIN, getText(domain));
        intent.putExtra(DnsttVpnService.EXTRA_PUBKEY, getText(pubkey));

        try {
            intent.putExtra(DnsttVpnService.EXTRA_TUNNELS, Integer.parseInt(getText(tunnels)));
        } catch (NumberFormatException e) {
            intent.putExtra(DnsttVpnService.EXTRA_TUNNELS, 8);
        }

        startForegroundService(intent);
    }

    private void connectSocksProxy() {
        Config config = mobile.Mobile.newConfig();

        String type = transportType.getText().toString().toLowerCase();
        config.setTransportType(type);
        config.setTransportAddr(getText(transportAddr));
        config.setDomain(getText(domain));
        config.setPubkeyHex(getText(pubkey));
        config.setListenAddr("127.0.0.1:1080");

        try {
            config.setTunnels(Integer.parseInt(getText(tunnels)));
        } catch (NumberFormatException e) {
            config.setTunnels(8);
        }

        config.setMTU(1232);
        config.setUTLSFingerprint("none"); // Use standard TLS - uTLS causes errors on Android
        config.setUseZstd(true); // Enable zstd compression (server has it on by default)
        appendLog("Zstd compression: enabled");

        new Thread(() -> {
            try {
                appendLog("Establishing tunnels...");
                client.start(config);
            } catch (Exception e) {
                handler.post(() -> {
                    appendLog("Connection error: " + e.getMessage());
                    appendLog("Stack trace: " + android.util.Log.getStackTraceString(e));
                    setInputsEnabled(true);
                });
            }
        }).start();
    }

    private void disconnect() {
        appendLog("====================================");
        appendLog("Disconnecting and stopping all tunnels...");
        appendLog("====================================");
        connectButton.setEnabled(false);  // Prevent double-clicks
        connectButton.setText("Stopping...");

        // Cancel any ongoing search
        if (isSearching) {
            cancelDnsSearch();
        }

        // Force shutdown any remaining DNS test executor
        if (dnsTestExecutor != null) {
            appendLog("Stopping parallel DNS tests...");
            dnsTestExecutor.shutdownNow();
            dnsTestExecutor = null;
        }

        // Interrupt any search threads
        if (searchThread != null && searchThread.isAlive()) {
            appendLog("Stopping DNS search thread...");
            searchThread.interrupt();
            searchThread = null;
        }

        if (vpnMode) {
            // Stop VPN service
            appendLog("Stopping VPN service and all tunnels...");
            Intent intent = new Intent(this, DnsttVpnService.class);
            intent.setAction(DnsttVpnService.ACTION_STOP);
            startForegroundService(intent);
        } else {
            appendLog("Stopping SOCKS proxy and all tunnels...");
            new Thread(() -> {
                try {
                    if (client != null) {
                        appendLog("Stopping DNSTT client...");
                        client.stop();
                        appendLog("DNSTT client stopped");
                        client = null;
                    }
                } catch (Exception e) {
                    handler.post(() -> appendLog("Error stopping client: " + e.getMessage()));
                }
                handler.post(() -> {
                    connectButton.setEnabled(true);
                    setInputsEnabled(true);
                    appendLog("All tunnels stopped");
                });
            }, "DisconnectThread").start();
        }
    }

    private String getText(TextInputEditText editText) {
        if (editText == null) {
            return "";
        }
        CharSequence text = editText.getText();
        return text != null ? text.toString().trim() : "";
    }

    private void setInputsEnabled(boolean enabled) {
        transportType.setEnabled(enabled);
        // Only enable transport address if not in Auto DNS + UDP mode
        if (enabled && useAutoDns && transportType.getText().toString().equalsIgnoreCase("UDP")) {
            transportAddr.setEnabled(false);
        } else {
            transportAddr.setEnabled(enabled);
        }
        domain.setEnabled(enabled);
        pubkey.setEnabled(enabled);
        tunnels.setEnabled(enabled);
        vpnModeSwitch.setEnabled(enabled);
        autoConnectSwitch.setEnabled(enabled);
        autoDnsSwitch.setEnabled(enabled);
    }

    private void appendLog(String message) {
        if (handler == null || logText == null) {
            return;
        }

        handler.post(() -> {
            try {
                String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(new java.util.Date());
                String logLine = "[" + timestamp + "] " + message;

                String current = logText.getText() != null ? logText.getText().toString() : "";
                String newText = logLine + "\n" + current;
                // Keep last 50 lines for more history
                String[] lines = newText.split("\n");
                if (lines.length > 50) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 50; i++) {
                        sb.append(lines[i]).append("\n");
                    }
                    newText = sb.toString();
                }
                logText.setText(newText);
            } catch (Exception e) {
                // Ignore errors in logging
            }
        });
    }

    @Override
    public void onStatusChange(long state, String message) {
        if (handler == null) {
            return;
        }

        handler.post(() -> {
            try {
                // Don't show internal VPN logs in status bar, just in log
                if (state == -1) {
                    appendLog(message);
                    return;
                }

                appendLog(message);

                // Reset searching state when connection state changes
                if (state != 1) { // Not connecting
                    isSearching = false;
                    cancelSearch = false;
                }

                switch ((int) state) {
                    case 0: // Stopped
                        if (statusText != null) statusText.setText(R.string.status_disconnected);
                        if (statusText != null) statusText.setTextColor(getColor(R.color.disconnected));
                        if (statusSubtext != null) statusSubtext.setText("Tap connect to start");
                        if (statusCircle != null) statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                        if (connectButton != null) connectButton.setText(R.string.connect);
                        if (connectButton != null) connectButton.setEnabled(true);
                        isConnected = false;
                        setInputsEnabled(true);
                        // Hide stats card
                        if (statsCard != null) statsCard.setVisibility(View.GONE);
                        if (qualityText != null) qualityText.setText("--");
                        if (latencyText != null) latencyText.setText("-- ms");
                        if (speedText != null) speedText.setText("-- KB/s");
                        // Hide retry button when disconnected
                        if (retryButton != null) retryButton.setVisibility(View.GONE);
                        currentConnectedDns = null;
                        lastBytesIn = 0;
                        lastBytesOut = 0;
                        lastUpdateTime = 0;
                        break;
                    case 1: // Connecting
                        if (statusText != null) statusText.setText(R.string.status_connecting);
                        if (statusText != null) statusText.setTextColor(getColor(R.color.connecting));
                        if (statusSubtext != null) statusSubtext.setText("Establishing connection...");
                        if (statusCircle != null) statusCircle.setBackgroundResource(R.drawable.status_circle_connecting);
                        if (connectButton != null) connectButton.setText(R.string.disconnect);
                        isConnected = false;
                        break;
                    case 2: // Connected
                        if (statusText != null) statusText.setText(R.string.status_connected);
                        if (statusText != null) statusText.setTextColor(getColor(R.color.connected));
                        if (statusSubtext != null) statusSubtext.setText("Your traffic is protected");
                        if (statusCircle != null) statusCircle.setBackgroundResource(R.drawable.status_circle_connected);
                        if (connectButton != null) connectButton.setText(R.string.disconnect);
                        isConnected = true;

                        // Log connected DNS prominently
                        if (currentConnectedDns != null) {
                            appendLog("====================================");
                            appendLog("✓ CONNECTED TO DNS: " + currentConnectedDns);
                            if (currentLatencyMs > 0) {
                                appendLog("  Latency: " + currentLatencyMs + "ms");
                            }
                            appendLog("====================================");
                        }

                        // Show stats card
                        if (statsCard != null) statsCard.setVisibility(View.VISIBLE);
                        // Show retry button when connected (only if using auto DNS)
                        if (retryButton != null && useAutoDns) retryButton.setVisibility(View.VISIBLE);
                        break;
                    case 3: // Error
                        if (statusText != null) statusText.setText("Error");
                        if (statusText != null) statusText.setTextColor(getColor(R.color.disconnected));
                        if (statusSubtext != null) statusSubtext.setText("Connection failed");
                        if (statusCircle != null) statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                        if (connectButton != null) connectButton.setText(R.string.connect);
                        if (connectButton != null) connectButton.setEnabled(true);
                        isConnected = false;
                        setInputsEnabled(true);
                        // Hide retry button on error
                        if (retryButton != null) retryButton.setVisibility(View.GONE);
                        currentConnectedDns = null;
                        break;
                }
            } catch (Exception e) {
                // Ignore UI update errors
            }
        });
    }

    @Override
    public void onBytesTransferred(long bytesIn, long bytesOut) {
        if (handler == null) {
            return;
        }

        handler.post(() -> {
            try {
                if (bytesInText != null) bytesInText.setText(formatBytes(bytesIn));
                if (bytesOutText != null) bytesOutText.setText(formatBytes(bytesOut));

                // Calculate speed and update quality indicator
                long currentTime = System.currentTimeMillis();
                if (lastUpdateTime > 0 && isConnected) {
                    long timeDelta = currentTime - lastUpdateTime;
                    if (timeDelta > 0) {
                        long bytesInDelta = bytesIn - lastBytesIn;
                        long bytesOutDelta = bytesOut - lastBytesOut;
                        long totalBytesDelta = bytesInDelta + bytesOutDelta;

                        // Calculate speed in KB/s
                        double speedKBps = (totalBytesDelta / 1024.0) / (timeDelta / 1000.0);
                        if (speedText != null) speedText.setText(String.format("%.1f KB/s", speedKBps));

                        // Estimate latency from response time (rough approximation)
                        // If we have data transfer, estimate latency based on throughput
                        if (totalBytesDelta > 0 && currentLatencyMs == 0) {
                            // Rough estimate: latency = timeDelta / number of round trips
                            // Assume ~1KB per DNS query/response
                            long estimatedRoundTrips = Math.max(1, totalBytesDelta / 1024);
                            currentLatencyMs = timeDelta / estimatedRoundTrips;
                            if (currentLatencyMs > 0 && currentLatencyMs < 5000 && latencyText != null) {
                                latencyText.setText(currentLatencyMs + " ms");
                            }
                        }

                        // Update quality indicator based on speed
                        updateConnectionQuality(speedKBps);
                    }

                    // Show quality bar when connected
                    if (qualityBarLayout != null && qualityBarLayout.getVisibility() != View.VISIBLE) {
                        qualityBarLayout.setVisibility(View.VISIBLE);
                    }
                }

                lastBytesIn = bytesIn;
                lastBytesOut = bytesOut;
                lastUpdateTime = currentTime;
            } catch (Exception e) {
                // Ignore UI update errors
            }
        });
    }

    private void updateConnectionQuality(double speedKBps) {
        // Quality score based on speed (0-100)
        int qualityScore;
        String qualityLabel;
        int qualityColor;

        if (speedKBps >= 100) {
            qualityScore = 100;
            qualityLabel = "Excellent";
            qualityColor = Color.parseColor("#4CAF50"); // Green
        } else if (speedKBps >= 50) {
            qualityScore = 80;
            qualityLabel = "Good";
            qualityColor = Color.parseColor("#8BC34A"); // Light green
        } else if (speedKBps >= 20) {
            qualityScore = 60;
            qualityLabel = "Fair";
            qualityColor = Color.parseColor("#FFEB3B"); // Yellow
        } else if (speedKBps >= 5) {
            qualityScore = 40;
            qualityLabel = "Poor";
            qualityColor = Color.parseColor("#FF9800"); // Orange
        } else if (speedKBps > 0) {
            qualityScore = 20;
            qualityLabel = "Very Poor";
            qualityColor = Color.parseColor("#F44336"); // Red
        } else {
            qualityScore = 0;
            qualityLabel = "No Data";
            qualityColor = Color.parseColor("#9E9E9E"); // Gray
        }

        qualityText.setText(qualityLabel);
        qualityText.setTextColor(qualityColor);
        qualityBar.setProgress(qualityScore);
        qualityBar.getProgressDrawable().setColorFilter(qualityColor, android.graphics.PorterDuff.Mode.SRC_IN);

        // Update latency display (if we have latency data)
        if (currentLatencyMs > 0) {
            latencyText.setText(currentLatencyMs + " ms");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString("transportType", transportType.getText().toString())
                .putString("dohProvider", dohProvider.getText().toString())
                .putString("transportAddr", getText(transportAddr))
                .putString("domain", getText(domain))
                .putString("pubkey", getText(pubkey))
                .putString("tunnels", getText(tunnels))
                .putBoolean("vpnMode", vpnMode)
                .putBoolean("autoConnect", autoConnect)
                .putBoolean("useAutoDns", useAutoDns)
                .putInt("parallelThreads", parallelThreads)
                .putInt("dnsTimeout", dnsTimeout)
                .apply();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        String type = prefs.getString("transportType", "DoH");
        transportType.setText(type, false);

        // Load DoH provider
        String provider = prefs.getString("dohProvider", "Google");
        dohProvider.setText(provider, false);

        transportAddr.setText(prefs.getString("transportAddr", "https://dns.google/dns-query"));
        domain.setText(prefs.getString("domain", "t.example.com"));
        pubkey.setText(prefs.getString("pubkey", ""));
        tunnels.setText(prefs.getString("tunnels", "8"));

        vpnMode = prefs.getBoolean("vpnMode", true);
        vpnModeSwitch.setChecked(vpnMode);

        autoConnect = prefs.getBoolean("autoConnect", false);
        autoConnectSwitch.setChecked(autoConnect);

        useAutoDns = prefs.getBoolean("useAutoDns", true);
        autoDnsSwitch.setChecked(useAutoDns);
        updateAutoDnsLabel();

        // Load performance settings
        parallelThreads = prefs.getInt("parallelThreads", 5);
        dnsTimeout = prefs.getInt("dnsTimeout", 3000);

        // Set UI values for performance settings
        if (parallelThreadsInput != null) {
            parallelThreadsInput.setText(String.valueOf(parallelThreads));
        }
        if (dnsTimeoutInput != null) {
            dnsTimeoutInput.setText(String.valueOf(dnsTimeout));
        }

        // Update visibility based on transport type
        updateDohProviderVisibility();

        // Disable transport address for UDP + Auto DNS mode
        if (useAutoDns && transportType.getText().toString().equalsIgnoreCase("UDP")) {
            transportAddr.setEnabled(false);
            transportAddr.setText("(auto-select best resolver)");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        appendLog("App closing - cleaning up all resources...");

        // Cancel any ongoing search
        if (isSearching) {
            cancelSearch = true;
            if (searchThread != null && searchThread.isAlive()) {
                searchThread.interrupt();
                searchThread = null;
            }
        }

        // Shutdown DNS test executor
        if (dnsTestExecutor != null) {
            appendLog("Shutting down DNS test executor...");
            dnsTestExecutor.shutdownNow();
            dnsTestExecutor = null;
        }

        // Stop client if connected in non-VPN mode
        if (!vpnMode && client != null) {
            try {
                appendLog("Stopping SOCKS client on app close...");
                client.stop();
                appendLog("SOCKS client stopped");
            } catch (Exception e) {
                appendLog("Error stopping client on destroy: " + e.getMessage());
            }
        }

        // For VPN mode, ensure service is stopped
        if (vpnMode && isConnected) {
            try {
                appendLog("Stopping VPN service on app close...");
                Intent intent = new Intent(this, DnsttVpnService.class);
                intent.setAction(DnsttVpnService.ACTION_STOP);
                startService(intent);
            } catch (Exception e) {
                appendLog("Error stopping VPN on destroy: " + e.getMessage());
            }
        }

        // Remove UI callback to prevent memory leak
        DnsttVpnService.setUiCallback(null);

        // Cleanup app updater
        if (appUpdater != null) {
            appUpdater.cleanup();
            appUpdater = null;
        }

        // Remove all handler callbacks to prevent memory leaks
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // Clear references
        client = null;

        appendLog("App cleanup completed");
    }

    private void checkForUpdates() {
        updateButton.setEnabled(false);
        updateButton.setText("Checking...");
        appendLog("Checking for updates...");

        appUpdater.setCallback(new AppUpdater.UpdateCallback() {
            @Override
            public void onCheckStarted() {
                // Already showing "Checking..."
            }

            @Override
            public void onUpdateAvailable(String version, String releaseNotes, String downloadUrl) {
                updateButton.setEnabled(true);
                updateButton.setText("Update");
                appendLog("Update available: v" + version);

                new MaterialAlertDialogBuilder(MainActivity.this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setTitle("Update Available")
                        .setMessage("Version " + version + " is available.\n\n" + releaseNotes)
                        .setPositiveButton("Download", (dialog, which) -> {
                            appUpdater.downloadUpdate(downloadUrl, version);
                        })
                        .setNegativeButton("Later", null)
                        .show();
            }

            @Override
            public void onNoUpdate(String currentVersion) {
                updateButton.setEnabled(true);
                updateButton.setText("Check Update");
                appendLog("App is up to date (v" + currentVersion + ")");

                new MaterialAlertDialogBuilder(MainActivity.this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setTitle("Up to Date")
                        .setMessage("You're running the latest version (v" + currentVersion + ")")
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                updateButton.setEnabled(true);
                updateButton.setText("Check Update");
                appendLog("Update check failed: " + message);

                new MaterialAlertDialogBuilder(MainActivity.this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setTitle("Update Check Failed")
                        .setMessage("Could not check for updates:\n" + message)
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onDownloadStarted() {
                appendLog("Downloading update...");
                updateButton.setText("Downloading...");
            }

            @Override
            public void onDownloadComplete(Uri apkUri) {
                updateButton.setEnabled(true);
                updateButton.setText("Check Update");
                appendLog("Download complete, installing...");
                appUpdater.installApk(apkUri);
            }
        });

        appUpdater.checkForUpdates();
    }
}
