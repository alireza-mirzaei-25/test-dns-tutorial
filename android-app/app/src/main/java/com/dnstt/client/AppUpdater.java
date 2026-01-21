package com.dnstt.client;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AppUpdater {
    private static final String TAG = "AppUpdater";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/mohjaf67/dnstt-fast-tunnel/releases/latest";

    private final Context context;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private UpdateCallback callback;
    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;

    public interface UpdateCallback {
        void onCheckStarted();
        void onUpdateAvailable(String version, String releaseNotes, String downloadUrl);
        void onNoUpdate(String currentVersion);
        void onError(String message);
        void onDownloadStarted();
        void onDownloadComplete(Uri apkUri);
    }

    public AppUpdater(Context context) {
        this.context = context;
    }

    public void setCallback(UpdateCallback callback) {
        this.callback = callback;
    }

    public String getCurrentVersion() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    public int getCurrentVersionCode() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }

    public void checkForUpdates() {
        if (callback != null) {
            callback.onCheckStarted();
        }

        executor.execute(() -> {
            try {
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    notifyError("Server returned code: " + responseCode);
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject release = new JSONObject(response.toString());
                String tagName = release.getString("tag_name");
                String releaseName = release.optString("name", tagName);
                String releaseBody = release.optString("body", "No release notes");

                // Parse version from tag (remove 'v' prefix if present)
                String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                String currentVersion = getCurrentVersion();

                Log.d(TAG, "Current version: " + currentVersion + ", Latest: " + latestVersion);

                // Compare versions
                if (isNewerVersion(latestVersion, currentVersion)) {
                    // Find the appropriate APK asset
                    String downloadUrl = findApkAsset(release);
                    if (downloadUrl != null) {
                        notifyUpdateAvailable(latestVersion, releaseBody, downloadUrl);
                    } else {
                        notifyError("No compatible APK found in release");
                    }
                } else {
                    notifyNoUpdate(currentVersion);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error checking for updates", e);
                notifyError(e.getMessage());
            }
        });
    }

    private String findApkAsset(JSONObject release) {
        try {
            JSONArray assets = release.getJSONArray("assets");
            String deviceAbi = Build.SUPPORTED_ABIS[0];

            // Priority order: device-specific APK, universal APK, any APK
            String universalUrl = null;
            String anyApkUrl = null;

            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name").toLowerCase();
                String downloadUrl = asset.getString("browser_download_url");

                if (!name.endsWith(".apk")) continue;

                // Check for device-specific APK
                if (name.contains(deviceAbi.toLowerCase()) ||
                    (deviceAbi.equals("arm64-v8a") && name.contains("arm64")) ||
                    (deviceAbi.equals("armeabi-v7a") && name.contains("arm"))) {
                    return downloadUrl;
                }

                // Universal APK
                if (name.contains("universal")) {
                    universalUrl = downloadUrl;
                }

                // Any APK as fallback
                if (anyApkUrl == null) {
                    anyApkUrl = downloadUrl;
                }
            }

            return universalUrl != null ? universalUrl : anyApkUrl;
        } catch (Exception e) {
            Log.e(TAG, "Error finding APK asset", e);
            return null;
        }
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");

            int maxLen = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < maxLen; i++) {
                int latestNum = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;
                int currentNum = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;

                if (latestNum > currentNum) return true;
                if (latestNum < currentNum) return false;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error comparing versions", e);
            return false;
        }
    }

    private int parseVersionPart(String part) {
        // Remove any non-numeric suffix (e.g., "1-beta" -> 1)
        StringBuilder numeric = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else {
                break;
            }
        }
        return numeric.length() > 0 ? Integer.parseInt(numeric.toString()) : 0;
    }

    public void downloadUpdate(String downloadUrl, String version) {
        try {
            if (callback != null) {
                callback.onDownloadStarted();
            }

            // Register download complete receiver
            registerDownloadReceiver();

            // Create download request
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("DNSTT Update v" + version);
            request.setDescription("Downloading update...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            String fileName = "dnstt-" + version + ".apk";
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            downloadId = downloadManager.enqueue(request);

            Log.d(TAG, "Download started with ID: " + downloadId);

        } catch (Exception e) {
            Log.e(TAG, "Error starting download", e);
            notifyError("Download failed: " + e.getMessage());
        }
    }

    private void registerDownloadReceiver() {
        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {}
        }

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Download broadcast received");
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    handleDownloadComplete();
                }
            }
        };

        // Must use RECEIVER_EXPORTED to receive broadcasts from DownloadManager (system app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void handleDownloadComplete() {
        Log.d(TAG, "Handling download complete");
        try {
            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);

            android.database.Cursor cursor = downloadManager.query(query);
            if (cursor != null && cursor.moveToFirst()) {
                int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int status = cursor.getInt(statusIndex);
                Log.d(TAG, "Download status: " + status);

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // Get the downloaded file path
                    int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    String uriString = cursor.getString(uriIndex);
                    Log.d(TAG, "Downloaded file URI: " + uriString);

                    // Parse the file:// URI and get actual file path
                    Uri downloadedUri = Uri.parse(uriString);
                    String filePath = downloadedUri.getPath();
                    Log.d(TAG, "File path: " + filePath);

                    File file = new File(filePath);
                    if (!file.exists()) {
                        // Try getting from Downloads directory directly
                        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        // Find the APK file we just downloaded
                        File[] files = downloadsDir.listFiles((dir, name) -> name.startsWith("dnstt-") && name.endsWith(".apk"));
                        if (files != null && files.length > 0) {
                            // Get the most recently modified file
                            file = files[0];
                            for (File f : files) {
                                if (f.lastModified() > file.lastModified()) {
                                    file = f;
                                }
                            }
                        }
                        Log.d(TAG, "Fallback file: " + file.getAbsolutePath());
                    }

                    if (file.exists()) {
                        Uri apkUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            apkUri = FileProvider.getUriForFile(context,
                                context.getPackageName() + ".fileprovider", file);
                        } else {
                            apkUri = Uri.fromFile(file);
                        }
                        Log.d(TAG, "APK URI for install: " + apkUri);

                        if (callback != null) {
                            callback.onDownloadComplete(apkUri);
                        }
                    } else {
                        notifyError("Downloaded file not found");
                    }
                } else {
                    notifyError("Download failed with status: " + status);
                }
                cursor.close();
            } else {
                Log.e(TAG, "Cursor is null or empty");
                notifyError("Could not query download status");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling download complete", e);
            notifyError("Error: " + e.getMessage());
        }
    }

    public void installApk(Uri apkUri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    public void cleanup() {
        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {}
            downloadReceiver = null;
        }
    }

    private void notifyUpdateAvailable(String version, String notes, String url) {
        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                callback.onUpdateAvailable(version, notes, url));
        }
    }

    private void notifyNoUpdate(String version) {
        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                callback.onNoUpdate(version));
        }
    }

    private void notifyError(String message) {
        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                callback.onError(message));
        }
    }
}
