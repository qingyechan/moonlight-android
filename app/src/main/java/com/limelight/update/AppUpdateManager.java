package com.limelight.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Checks for APK updates from GitHub Releases and installs verified APKs in-app.
 * Automatically races direct GitHub vs built-in mirror routes in the background
 * without blocking on dead/throttled endpoints or exposing mirror config in the UI.
 */
public final class AppUpdateManager {

    private static final String DEFAULT_GITHUB_REPO = "qingyechan/moonlight-android";
    private static final String[] ROUTE_PREFIXES = new String[]{
            "",
            "https://gh-proxy.org/",
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/"
    };
    private static final String APK_FILE_NAME = "MoonlightBridge-update.apk";

    private static final OkHttpClient CHECK_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    private static final OkHttpClient PROBE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build();

    private static final OkHttpClient DOWNLOAD_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
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
        String winningPrefix;
    }

    private static class RouteResult {
        final String prefix;
        final long elapsedMs;

        RouteResult(String prefix, long elapsedMs) {
            this.prefix = prefix;
            this.elapsedMs = elapsedMs;
        }
    }

    public static void checkForUpdates(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final List<Call> activeCalls = Collections.synchronizedList(new ArrayList<Call>());

        final AlertDialog checkingDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_dialog_title)
                .setMessage(R.string.update_checking)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cancelled.set(true);
                        cancelAllCalls(activeCalls);
                    }
                })
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String localSha256 = computeSha256(new File(activity.getApplicationInfo().sourceDir));
                final String apiBaseUrl = "https://api.github.com/repos/" + DEFAULT_GITHUB_REPO + "/releases/latest";

                final AtomicReference<String> errorRef = new AtomicReference<>(null);
                final UpdateInfo found = fetchLatestParallel(apiBaseUrl, activeCalls, cancelled, errorRef);

                if (cancelled.get()) {
                    return;
                }

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing()) {
                            return;
                        }
                        checkingDialog.dismiss();

                        if (found == null) {
                            String err = errorRef.get() != null ? errorRef.get() : "timeout";
                            new AlertDialog.Builder(activity)
                                    .setTitle(R.string.update_dialog_title)
                                    .setMessage(activity.getString(R.string.update_check_failed, err))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                            return;
                        }

                        if (!isUpdateAvailable(found, localSha256)) {
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

                        showUpdateAvailableDialog(activity, found);
                    }
                });
            }
        }).start();
    }

    private static UpdateInfo fetchLatestParallel(
            final String apiBaseUrl,
            final List<Call> activeCalls,
            final AtomicBoolean cancelled,
            final AtomicReference<String> errorRef) {
        ExecutorService pool = Executors.newFixedThreadPool(ROUTE_PREFIXES.length);
        CompletionService<UpdateInfo> ecs = new ExecutorCompletionService<>(pool);
        List<Future<UpdateInfo>> futures = new ArrayList<>();

        for (final String prefix : ROUTE_PREFIXES) {
            futures.add(ecs.submit(new Callable<UpdateInfo>() {
                @Override
                public UpdateInfo call() throws Exception {
                    if (cancelled.get()) {
                        return null;
                    }
                    String targetUrl = prefix.isEmpty() ? apiBaseUrl : (prefix + apiBaseUrl);
                    Request request = new Request.Builder()
                            .url(targetUrl)
                            .header("Accept", "application/json")
                            .build();
                    Call call = CHECK_CLIENT.newCall(request);
                    activeCalls.add(call);
                    try (Response response = call.execute()) {
                        if (!response.isSuccessful() || response.body() == null) {
                            throw new Exception("HTTP " + response.code());
                        }
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        UpdateInfo info = parseGitHubRelease(json);
                        if (info == null || info.apkUrl == null || info.apkUrl.isEmpty()) {
                            throw new Exception("No APK asset found");
                        }
                        info.winningPrefix = prefix;
                        return info;
                    }
                }
            }));
        }

        UpdateInfo winner = null;
        try {
            for (int i = 0; i < futures.size(); i++) {
                if (cancelled.get()) {
                    break;
                }
                try {
                    UpdateInfo candidate = ecs.take().get();
                    if (candidate != null) {
                        winner = candidate;
                        break;
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    errorRef.compareAndSet(null, cause.getMessage());
                }
            }
        } finally {
            cancelAllCalls(activeCalls);
            for (Future<UpdateInfo> f : futures) {
                f.cancel(true);
            }
            pool.shutdownNow();
        }
        return winner;
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

    private static UpdateInfo parseGitHubRelease(JSONObject json) {
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
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<Call> currentDownloadCall = new AtomicReference<>(null);

        final AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_downloading_title)
                .setMessage(activity.getString(R.string.update_selecting_route))
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cancelled.set(true);
                        Call call = currentDownloadCall.get();
                        if (call != null) {
                            call.cancel();
                        }
                    }
                })
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File outFile = new File(ApkUpdateProvider.getUpdatesDir(activity), APK_FILE_NAME);
                List<String> rankedUrls = probeAndRankDownloadUrls(info.apkUrl, info.winningPrefix, cancelled);

                Exception lastError = null;
                boolean downloadedOk = false;
                for (String downloadUrl : rankedUrls) {
                    if (cancelled.get()) {
                        break;
                    }
                    try {
                        downloadSingleAttempt(activity, progressDialog, downloadUrl, info, outFile, cancelled, currentDownloadCall);
                        downloadedOk = true;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                        //noinspection ResultOfMethodCallIgnored
                        outFile.delete();
                    }
                }

                if (cancelled.get()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                    return;
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

    /**
     * Probes direct GitHub and mirror prefixes concurrently with lightweight HEAD requests,
     * ordering fastest-responding healthy routes first so downloads never block on dead nodes.
     */
    private static List<String> probeAndRankDownloadUrls(
            final String rawApkUrl,
            final String hintPrefix,
            final AtomicBoolean cancelled) {
        ExecutorService pool = Executors.newFixedThreadPool(ROUTE_PREFIXES.length);
        List<Future<RouteResult>> futures = new ArrayList<>();
        for (final String prefix : ROUTE_PREFIXES) {
            futures.add(pool.submit(new Callable<RouteResult>() {
                @Override
                public RouteResult call() {
                    if (cancelled.get()) {
                        return null;
                    }
                    String target = prefix.isEmpty() ? rawApkUrl : (prefix + rawApkUrl);
                    long start = System.nanoTime();
                    Request req = new Request.Builder()
                            .url(target)
                            .head()
                            .build();
                    try (Response resp = PROBE_CLIENT.newCall(req).execute()) {
                        if (!resp.isSuccessful()) {
                            return null;
                        }
                        String contentType = resp.header("Content-Type", "");
                        if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                            return null;
                        }
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                        return new RouteResult(prefix, elapsedMs);
                    } catch (Exception e) {
                        return null;
                    }
                }
            }));
        }

        List<RouteResult> healthy = new ArrayList<>();
        try {
            for (Future<RouteResult> f : futures) {
                try {
                    RouteResult res = f.get();
                    if (res != null) {
                        healthy.add(res);
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }

        Collections.sort(healthy, new Comparator<RouteResult>() {
            @Override
            public int compare(RouteResult a, RouteResult b) {
                return Long.compare(a.elapsedMs, b.elapsedMs);
            }
        });

        Set<String> ordered = new LinkedHashSet<>();
        for (RouteResult r : healthy) {
            ordered.add(r.prefix.isEmpty() ? rawApkUrl : (r.prefix + rawApkUrl));
        }
        if (hintPrefix != null) {
            ordered.add(hintPrefix.isEmpty() ? rawApkUrl : (hintPrefix + rawApkUrl));
        }
        for (String prefix : ROUTE_PREFIXES) {
            ordered.add(prefix.isEmpty() ? rawApkUrl : (prefix + rawApkUrl));
        }
        return new ArrayList<>(ordered);
    }

    private static void downloadSingleAttempt(
            final Activity activity,
            final AlertDialog progressDialog,
            String downloadUrl,
            UpdateInfo info,
            File outFile,
            AtomicBoolean cancelled,
            AtomicReference<Call> activeCallRef) throws Exception {
        Request req = new Request.Builder().url(downloadUrl).build();
        Call call = DOWNLOAD_CLIENT.newCall(req);
        activeCallRef.set(call);
        try (Response resp = call.execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("HTTP " + resp.code());
            }
            String contentType = resp.header("Content-Type", "");
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                throw new Exception("Unexpected HTML response");
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
                    if (cancelled.get()) {
                        throw new Exception("Cancelled");
                    }
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        final int pct = (int) Math.min(100, (downloaded * 100L) / total);
                        if (pct != lastPct && (pct % 3 == 0 || pct == 100)) {
                            lastPct = pct;
                            final double doneMb = downloaded / (1024.0 * 1024.0);
                            final double totalMb = total / (1024.0 * 1024.0);
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!activity.isFinishing() && progressDialog.isShowing()) {
                                        progressDialog.setMessage(activity.getString(
                                                R.string.update_downloading_progress_detail,
                                                pct, doneMb, totalMb));
                                    }
                                }
                            });
                        }
                    }
                }
            }
        } finally {
            activeCallRef.compareAndSet(call, null);
        }

        if (info.sha256 != null && !info.sha256.isEmpty()) {
            String actualSha256 = computeSha256(outFile);
            if (!info.sha256.equalsIgnoreCase(actualSha256)) {
                throw new Exception("SHA-256 mismatch");
            }
        }
    }

    private static void cancelAllCalls(List<Call> calls) {
        synchronized (calls) {
            for (Call c : calls) {
                try {
                    c.cancel();
                } catch (Exception ignored) {
                }
            }
            calls.clear();
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
