package com.dnstt.client;

import android.content.Context;
import android.content.SharedPreferences;

import com.dnstt.client.models.CustomDnsList;
import com.dnstt.client.models.DnsConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages DNS configurations and custom lists
 */
public class DnsConfigManager {
    private static final String PREFS_NAME = "dns_config_prefs";
    private static final String KEY_CUSTOM_LISTS = "custom_lists";
    private static final String KEY_SELECTED_SOURCE = "selected_source";
    private static final String KEY_SELECTED_LIST_ID = "selected_list_id";

    public static final String SOURCE_GLOBAL = "global";
    public static final String SOURCE_CUSTOM = "custom";

    private final Context context;
    private final SharedPreferences prefs;
    private final Gson gson;
    private List<CustomDnsList> customLists;

    public DnsConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        loadCustomLists();
    }

    /**
     * Get global DNS configurations
     * Loads all DNS servers from dns_servers.txt asset file
     */
    public List<DnsConfig> getGlobalDnsConfigs() {
        List<DnsConfig> configs = new ArrayList<>();

        try {
            java.io.InputStream is = context.getAssets().open("dns_servers.txt");
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
            String line;
            int index = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Skip empty lines and comments
                if (!line.isEmpty() && !line.startsWith("#")) {
                    // Create DnsConfig from IP address
                    String address = line.contains(":") ? line : line + ":53";
                    configs.add(new DnsConfig(
                            UUID.randomUUID().toString(),
                            "DNS Server " + index,
                            address,
                            "Public DNS server",
                            true
                    ));
                    index++;
                }
            }
            reader.close();
        } catch (java.io.IOException e) {
            // Fallback to popular DNS servers if file can't be read
            configs.add(new DnsConfig(
                    UUID.randomUUID().toString(),
                    "Cloudflare DNS",
                    "1.1.1.1:53",
                    "Fast and privacy-focused DNS",
                    true
            ));
            configs.add(new DnsConfig(
                    UUID.randomUUID().toString(),
                    "Google DNS",
                    "8.8.8.8:53",
                    "Reliable Google DNS",
                    true
            ));
        }

        return configs;
    }

    /**
     * Load custom DNS lists from storage
     */
    private void loadCustomLists() {
        String json = prefs.getString(KEY_CUSTOM_LISTS, null);
        if (json != null) {
            Type type = new TypeToken<List<CustomDnsList>>() {}.getType();
            customLists = gson.fromJson(json, type);
        } else {
            customLists = new ArrayList<>();
            // Create default custom list
            CustomDnsList defaultList = new CustomDnsList(UUID.randomUUID().toString(), "My DNS List");
            customLists.add(defaultList);
            saveCustomLists();
        }
    }

    /**
     * Save custom DNS lists to storage
     */
    private void saveCustomLists() {
        String json = gson.toJson(customLists);
        prefs.edit().putString(KEY_CUSTOM_LISTS, json).apply();
    }

    /**
     * Get all custom DNS lists
     */
    public List<CustomDnsList> getCustomLists() {
        return new ArrayList<>(customLists);
    }

    /**
     * Get a specific custom list by ID
     */
    public CustomDnsList getCustomList(String listId) {
        for (CustomDnsList list : customLists) {
            if (list.getId().equals(listId)) {
                return list;
            }
        }
        return null;
    }

    /**
     * Create a new custom DNS list
     */
    public CustomDnsList createCustomList(String name) {
        CustomDnsList newList = new CustomDnsList(UUID.randomUUID().toString(), name);
        customLists.add(newList);
        saveCustomLists();
        return newList;
    }

    /**
     * Update a custom DNS list
     */
    public void updateCustomList(CustomDnsList list) {
        for (int i = 0; i < customLists.size(); i++) {
            if (customLists.get(i).getId().equals(list.getId())) {
                customLists.set(i, list);
                saveCustomLists();
                return;
            }
        }
    }

    /**
     * Delete a custom DNS list
     */
    public void deleteCustomList(String listId) {
        customLists.removeIf(list -> list.getId().equals(listId));
        saveCustomLists();

        // If deleted list was selected, reset to global
        if (listId.equals(getSelectedListId())) {
            setSelectedSource(SOURCE_GLOBAL, null);
        }
    }

    /**
     * Add DNS config to a custom list
     */
    public void addDnsConfigToList(String listId, DnsConfig config) {
        CustomDnsList list = getCustomList(listId);
        if (list != null) {
            config.setListName(list.getName());
            list.addDnsConfig(config);
            updateCustomList(list);
        }
    }

    /**
     * Update DNS config in a custom list
     */
    public void updateDnsConfig(String listId, DnsConfig config) {
        CustomDnsList list = getCustomList(listId);
        if (list != null) {
            List<DnsConfig> configs = list.getDnsConfigs();
            for (int i = 0; i < configs.size(); i++) {
                if (configs.get(i).getId().equals(config.getId())) {
                    configs.set(i, config);
                    updateCustomList(list);
                    return;
                }
            }
        }
    }

    /**
     * Remove DNS config from a custom list
     */
    public void removeDnsConfig(String listId, String configId) {
        CustomDnsList list = getCustomList(listId);
        if (list != null) {
            list.removeDnsConfig(configId);
            updateCustomList(list);
        }
    }

    /**
     * Get selected DNS source (global or custom)
     */
    public String getSelectedSource() {
        return prefs.getString(KEY_SELECTED_SOURCE, SOURCE_GLOBAL);
    }

    /**
     * Get selected custom list ID (if source is custom)
     */
    public String getSelectedListId() {
        return prefs.getString(KEY_SELECTED_LIST_ID, null);
    }

    /**
     * Set selected DNS source
     */
    public void setSelectedSource(String source, String listId) {
        prefs.edit()
                .putString(KEY_SELECTED_SOURCE, source)
                .putString(KEY_SELECTED_LIST_ID, listId)
                .apply();
    }

    /**
     * Get DNS servers for auto DNS based on selected source
     */
    public String getDnsServersForAutoSearch() {
        StringBuilder sb = new StringBuilder();

        if (SOURCE_GLOBAL.equals(getSelectedSource())) {
            // Use existing DnsServerManager for global servers
            DnsServerManager manager = new DnsServerManager(context);
            return manager.getAllServersAsString();
        } else {
            // Use selected custom list
            String listId = getSelectedListId();
            if (listId != null) {
                CustomDnsList list = getCustomList(listId);
                if (list != null) {
                    for (DnsConfig config : list.getDnsConfigs()) {
                        String address = config.getAddress();
                        // Extract IP without port
                        if (address.contains(":")) {
                            address = address.substring(0, address.indexOf(":"));
                        }
                        sb.append(address).append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Get display name for selected source
     */
    public String getSelectedSourceDisplayName() {
        if (SOURCE_GLOBAL.equals(getSelectedSource())) {
            return "Global DNS";
        } else {
            String listId = getSelectedListId();
            if (listId != null) {
                CustomDnsList list = getCustomList(listId);
                if (list != null) {
                    return list.getName();
                }
            }
            return "Custom List";
        }
    }
}
