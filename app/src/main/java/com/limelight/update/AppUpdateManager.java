package com.limelight.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Checks for APK updates from a configured URL (custom JSON or GitHub Releases API)
 * or directly from paired host PCs running the local OTA update server on port 47995.
 */
public final class AppUpdateManager {

    public static final String PREF_UPDATE_URL = "pref_update_url";
    public static final String PREF_UPDATE_MIRROR = "pref_update_mirror";
    public static final String DEFAULT_GITHUB_REPO = "qingyechan/moonlight-android";
    public static final String DEFAULT_MIRROR_PREFIX = "https://gh-proxy.com/";
    public static final int DEFAULT_HOST_UPDATE_PORT = 47995;
    private static final String APK_FILE_NAME = "MoonlightBridge-update.apk";

    private static final OkHttpClient CHECK_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    private static final OkHttpClient DOWNLOAD_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static File pendingInstallApk = null;

    private AppUpdateManager() {
    }

    private static class UpdateInfo {
        int versionCode;
        String versionName;
        String sha256;
        long sizeBytes;
        String apkUrl;
        String notes;
        String sourceLabel;
    }

    public static void checkForUpdates(final Activity activity, final List<ComputerDetails> computers) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final AlertDialog checkingDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_dialog_title)
                .setMessage(R.string.update_checking)
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String localSha256 = computeSha256(new File(activity.getApplicationInfo().sourceDir));
                final List<String> candidateUrls = buildCandidateUrls(activity, computers);

                UpdateInfo found = null;
                String lastError = null;
                for (String url : candidateUrls) {
                    try {
                        found = fetchUpdateInfo(url);
                        if (found != null && found.apkUrl != null && !found.apkUrl.isEmpty()) {
                            break;
                        }
                    } catch (Exception e) {
                        lastError = e.getMessage();
                    }
                }

                final UpdateInfo result = found;
                final String errorMsg = lastError;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing()) {
                            return;
                        }
                        checkingDialog.dismiss();

                        if (result == null) {
                            String detail = candidateUrls.isEmpty()
                                    ? activity.getString(R.string.update_no_source)
                                    : activity.getString(R.string.update_check_failed,
                                            errorMsg != null ? errorMsg : "timeout");
                            new AlertDialog.Builder(activity)
                                    .setTitle(R.string.update_dialog_title)
                                    .setMessage(detail)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                            return;
                        }

                        if (!isUpdateAvailable(result, localSha256)) {
                            new AlertDialog.Builder(activity)
                                    .setTitle(R.string.update_dialog_title)
                                    .setMessage(activity.getString(R.string.update_up_to_date,
                                            BuildConfig.VERSION_NAME,
                                            localSha256 != null && localSha256.length() >= 8
                                                    ? localSha256.substring(0, 8)
                                                    : "unknown"))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                            return;
                        }

                        showUpdateAvailableDialog(activity, result);
                    }
                });
            }
        }).start();
    }

    public static void resumePendingInstallIfPermitted(Activity activity) {
        if (activity == null || pendingInstallApk == null || !pendingInstallApk.exists()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }
        File apk = pendingInstallApk;
        pendingInstallApk = null;
        launchApkInstaller(activity, apk);
    }

    private static List<String> buildCandidateUrls(Context context, List<ComputerDetails> computers) {
        Set<String> urls = new LinkedHashSet<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String custom = prefs.getString(PREF_UPDATE_URL, DEFAULT_GITHUB_REPO);
        custom = custom != null ? custom.trim() : "";
        String mirror = getMirrorPrefix(context);

        boolean isUserCustomized = !custom.isEmpty() && !custom.equalsIgnoreCase(DEFAULT_GITHUB_REPO);
        if (isUserCustomized) {
            addWithMirrorFallback(urls, normalizeUpdateUrl(custom), mirror);
        }

        // Prefer reachable paired PCs on local/Tailscale port 47995 for instant dev/LAN updates.
        if (computers != null) {
            String channel = BuildConfig.DEBUG ? "debug" : "release";
            for (ComputerDetails details : computers) {
                if (details == null || details.state == ComputerDetails.State.OFFLINE) {
                    continue;
                }
                addHostCandidate(urls, details.activeAddress, channel);
                addHostCandidate(urls, details.localAddress, channel);
                addHostCandidate(urls, details.manualAddress, channel);
            }
        }

        // Default to the fork's GitHub Releases (with China mirror first, direct as fallback).
        addWithMirrorFallback(urls, normalizeUpdateUrl(DEFAULT_GITHUB_REPO), mirror);
        return new ArrayList<>(urls);
    }

    private static String getMirrorPrefix(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String mirror = prefs.getString(PREF_UPDATE_MIRROR, DEFAULT_MIRROR_PREFIX);
        if (mirror == null) {
            return "";
        }
        mirror = mirror.trim();
        if (mirror.isEmpty()) {
            return "";
        }
        if (!mirror.endsWith("/")) {
            mirror += "/";
        }
        return mirror;
    }

    private static boolean isGitHubHostUrl(String url) {
        return url != null && (url.startsWith("https://api.github.com/")
                || url.startsWith("https://github.com/")
                || url.startsWith("https://raw.githubusercontent.com/"));
    }

    private static void addWithMirrorFallback(Set<String> urls, String url, String mirror) {
        if (isGitHubHostUrl(url) && mirror != null && !mirror.isEmpty()) {
            urls.add(mirror + url);
        }
        urls.add(url);
    }

    private static void addHostCandidate(Set<String> urls, ComputerDetails.AddressTuple tuple, String channel) {
        if (tuple == null || tuple.address == null || tuple.address.isEmpty()) {
            return;
        }
        String host = tuple.address.trim();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        urls.add("http://" + host + ":" + DEFAULT_HOST_UPDATE_PORT + "/update.json?channel=" + channel);
    }

    private static String normalizeUpdateUrl(String input) {
        if (!input.contains("://") && input.contains("/")) {
            // Allow shorthand "owner/repo" for GitHub Releases
            String cleaned = input.replaceAll("^/+|/+$", "");
            return "https://api.github.com/repos/" + cleaned + "/releases/latest";
        }
        return input;
    }

    private static UpdateInfo fetchUpdateInfo(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build();

        try (Response response = CHECK_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            String body = response.body().string();
            JSONObject json = new JSONObject(body);

            // GitHub Releases API format
            if (json.has("assets") && json.has("tag_name")) {
                return parseGitHubRelease(json, url);
            }

            // Local / custom update.json format
            UpdateInfo info = new UpdateInfo();
            info.versionCode = json.optInt("versionCode", 0);
            info.versionName = json.optString("versionName", BuildConfig.VERSION_NAME);
            info.sha256 = json.optString("sha256", "");
            info.sizeBytes = json.optLong("sizeBytes", 0);
            info.notes = json.optString("notes", "");
            info.sourceLabel = url;

            String rawApkUrl = json.optString("apkUrl", "");
            HttpUrl base = HttpUrl.parse(url);
            HttpUrl resolved = base != null ? base.resolve(rawApkUrl) : HttpUrl.parse(rawApkUrl);
            info.apkUrl = resolved != null ? resolved.toString() : rawApkUrl;
            return info;
        }
    }

    private static UpdateInfo parseGitHubRelease(JSONObject json, String sourceUrl) {
        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        String preferredSuffix = BuildConfig.DEBUG ? "-debug.apk" : "-release.apk";
        JSONObject selected = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            if (name.endsWith(preferredSuffix)) {
                selected = asset;
                break;
            }
            if (selected == null && name.endsWith(".apk")) {
                selected = asset;
            }
        }
        if (selected == null) {
            return null;
        }

        UpdateInfo info = new UpdateInfo();
        info.versionName = json.optString("tag_name", json.optString("name", ""));
        info.apkUrl = selected.optString("browser_download_url", "");
        info.sizeBytes = selected.optLong("size", 0);
        String digest = selected.optString("digest", "");
        if (digest.startsWith("sha256:")) {
            info.sha256 = digest.substring("sha256:".length());
        } else {
            info.sha256 = "";
        }
        info.notes = json.optString("body", "");
        info.sourceLabel = sourceUrl;
        return info;
    }

    private static boolean isUpdateAvailable(UpdateInfo info, String localSha256) {
        if (info.versionCode > BuildConfig.VERSION_CODE) {
            return true;
        }
        if (info.versionCode > 0 && info.versionCode < BuildConfig.VERSION_CODE) {
            return false;
        }
        if (info.sha256 != null && !info.sha256.isEmpty() && localSha256 != null) {
            return !info.sha256.equalsIgnoreCase(localSha256);
        }
        if (info.versionName != null && !info.versionName.isEmpty()) {
            String normalizedRemote = info.versionName.replaceFirst("^v", "");
            return !normalizedRemote.equalsIgnoreCase(BuildConfig.VERSION_NAME);
        }
        return false;
    }

    private static void showUpdateAvailableDialog(final Activity activity, final UpdateInfo info) {
        String sizeStr = info.sizeBytes > 0
                ? String.format(Locale.US, "%.1f MB", info.sizeBytes / (1024.0 * 1024.0))
                : "-";
        StringBuilder msg = new StringBuilder();
        msg.append(activity.getString(R.string.update_found_summary, info.versionName, sizeStr));
        if (info.notes != null && !info.notes.trim().isEmpty()) {
            msg.append("\n\n").append(info.notes.trim());
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_found_title)
                .setMessage(msg.toString())
                .setPositiveButton(R.string.update_download_install, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        downloadAndInstall(activity, info);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void downloadAndInstall(final Activity activity, final UpdateInfo info) {
        final AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_downloading_title)
                .setMessage(activity.getString(R.string.update_downloading_progress, 0))
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File outFile = new File(ApkUpdateProvider.getUpdatesDir(activity), APK_FILE_NAME);
                Set<String> downloadUrls = new LinkedHashSet<>();
                addWithMirrorFallback(downloadUrls, info.apkUrl, getMirrorPrefix(activity));

                Exception lastError = null;
                boolean downloadedOk = false;
                for (String downloadUrl : downloadUrls) {
                    try {
                        downloadSingleAttempt(activity, progressDialog, downloadUrl, info, outFile);
                        downloadedOk = true;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                        //noinspection ResultOfMethodCallIgnored
                        outFile.delete();
                    }
                }

                if (downloadedOk) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing()) {
                                return;
                            }
                            progressDialog.dismiss();
                            installOrRequestPermission(activity, outFile);
                        }
                    });
                } else {
                    final String errMsg = lastError != null ? lastError.getMessage() : "unknown";
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing()) {
                                return;
                            }
                            progressDialog.dismiss();
                            Toast.makeText(activity,
                                    activity.getString(R.string.update_download_failed, errMsg),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static void downloadSingleAttempt(
            final Activity activity,
            final AlertDialog progressDialog,
            String downloadUrl,
            UpdateInfo info,
            File outFile) throws Exception {
        Request req = new Request.Builder().url(downloadUrl).build();
        try (Response resp = DOWNLOAD_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("HTTP " + resp.code());
            }
            String contentType = resp.header("Content-Type", "");
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                throw new Exception("Unexpected HTML response from mirror");
            }
            ResponseBody body = resp.body();
            long total = body.contentLength() > 0 ? body.contentLength() : info.sizeBytes;
            long downloaded = 0;
            int lastPct = -1;

            byte[] buf = new byte[16384];
            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(outFile)) {
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        final int pct = (int) Math.min(100, (downloaded * 100L) / total);
                        if (pct != lastPct && (pct % 5 == 0 || pct == 100)) {
                            lastPct = pct;
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!activity.isFinishing() && progressDialog.isShowing()) {
                                        progressDialog.setMessage(activity.getString(
                                                R.string.update_downloading_progress, pct));
                                    }
                                }
                            });
                        }
                    }
                }
            }
        }

        if (info.sha256 != null && !info.sha256.isEmpty()) {
            String actualSha256 = computeSha256(outFile);
            if (!info.sha256.equalsIgnoreCase(actualSha256)) {
                throw new Exception("SHA-256 mismatch");
            }
        }
    }

    private static void installOrRequestPermission(final Activity activity, final File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstallApk = apkFile;
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setPositiveButton(R.string.update_permission_grant, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(intent);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            pendingInstallApk = null;
                        }
                    })
                    .show();
            return;
        }
        launchApkInstaller(activity, apkFile);
    }

    private static void launchApkInstaller(Activity activity, File apkFile) {
        Uri apkUri = ApkUpdateProvider.getApkUri(activity, apkFile.getName());
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(apkUri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        activity.startActivity(intent);
    }

    private static String computeSha256(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
