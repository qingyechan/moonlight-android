package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manages local UI customizations: custom background wallpaper/scrim and
 * local-only PC display aliases (keyed by UUID so PIN pairing is never affected).
 */
public final class BridgeUiPrefs {

    private static final String PREF_UI = "bridge_ui_prefs";
    private static final String PREF_ALIASES = "bridge_pc_aliases";
    private static final String PREF_IGNORED_PCS = "bridge_ignored_pcs";
    private static final String PREF_COVER_MODES = "bridge_pc_cover_modes";
    private static final String PREF_SHORTCUTS = "bridge_pc_shortcuts";
    private static final String KEY_IGNORED_UUIDS = "ignored_uuids";
    private static final String KEY_SCRIM_ALPHA = "wallpaper_scrim_alpha";
    private static final String WALLPAPER_FILE_NAME = "custom_wallpaper.jpg";
    private static final int DEFAULT_SCRIM_ALPHA = 166; // ~65%
    private static final int MAX_WALLPAPER_DIM = 2560;
    private static final int MAX_COVER_DIM = 960;

    public static final int COVER_MODE_DEFAULT = 0;
    public static final int COVER_MODE_CUSTOM = 1;
    public static final int COVER_MODE_LIVE = 2;

    private static final LruCache<String, Bitmap> COVER_CACHE = new LruCache<>(16);

    private BridgeUiPrefs() {
    }

    // ---- Local PC Display Alias (UUID-keyed, does not affect pairing/PIN) ----

    private static String normalizeUuidKey(String uuid, String fallbackName) {
        if (uuid != null && !uuid.trim().isEmpty()) {
            return "uuid:" + uuid.trim().toLowerCase(Locale.US);
        }
        if (fallbackName != null && !fallbackName.trim().isEmpty()) {
            return "name:" + fallbackName.trim().toLowerCase(Locale.US);
        }
        return null;
    }

    public static String getCustomAlias(Context context, String uuid, String fallbackName) {
        String key = normalizeUuidKey(uuid, fallbackName);
        if (context == null || key == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_ALIASES, Context.MODE_PRIVATE);
        String val = prefs.getString(key, "");
        return val != null ? val.trim() : "";
    }

    public static String getDisplayName(Context context, ComputerDetails details) {
        if (details == null) {
            return "";
        }
        return getDisplayName(context, details.uuid, details.name);
    }

    public static String getDisplayName(Context context, String uuid, String originalName) {
        String alias = getCustomAlias(context, uuid, originalName);
        if (!alias.isEmpty()) {
            return alias;
        }
        return originalName != null ? originalName : "";
    }

    public static void setCustomAlias(Context context, ComputerDetails details, String alias) {
        if (context == null || details == null) {
            return;
        }
        String key = normalizeUuidKey(details.uuid, details.name);
        if (key == null) {
            return;
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREF_ALIASES, Context.MODE_PRIVATE).edit();
        String trimmed = alias != null ? alias.trim() : "";
        if (trimmed.isEmpty() || trimmed.equals(details.name)) {
            editor.remove(key);
        } else {
            editor.putString(key, trimmed);
        }
        editor.apply();
    }

    public static void clearAlias(Context context, String uuid) {
        if (context == null || uuid == null || uuid.trim().isEmpty()) {
            return;
        }
        context.getSharedPreferences(PREF_ALIASES, Context.MODE_PRIVATE)
                .edit()
                .remove("uuid:" + uuid.trim().toLowerCase(Locale.US))
                .apply();
    }

    public static void showRenameDialog(final Activity activity, final ComputerDetails details, final Runnable onUpdated) {
        if (activity == null || activity.isFinishing() || details == null) {
            return;
        }

        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        String currentDisplay = getDisplayName(activity, details);
        input.setText(currentDisplay);
        input.setHint(details.name);
        input.setSelection(input.getText().length());

        int padH = (int) (20 * activity.getResources().getDisplayMetrics().density);
        int padV = (int) (10 * activity.getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(padH, padV, padH, 0);
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.rename_pc_dialog_title)
                .setMessage(activity.getString(R.string.rename_pc_dialog_message, details.name))
                .setView(container)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setCustomAlias(activity, details, input.getText().toString());
                        if (onUpdated != null) {
                            onUpdated.run();
                        }
                    }
                })
                .setNeutralButton(R.string.rename_pc_reset, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setCustomAlias(activity, details, "");
                        if (onUpdated != null) {
                            onUpdated.run();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void showDeviceAliasPickerDialog(
            final Activity activity,
            final List<ComputerDetails> computers,
            final Runnable onUpdated) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (computers == null || computers.isEmpty()) {
            Toast.makeText(activity, R.string.rename_pc_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] labels = new String[computers.size()];
        for (int i = 0; i < computers.size(); i++) {
            ComputerDetails d = computers.get(i);
            String display = getDisplayName(activity, d);
            if (!display.equals(d.name)) {
                labels[i] = display + "  (" + d.name + ")";
            } else {
                labels[i] = d.name;
            }
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.title_rename_devices)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showRenameDialog(activity, computers.get(which), onUpdated);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Custom Background Wallpaper ----

    public static File getWallpaperFile(Context context) {
        return new File(context.getFilesDir(), WALLPAPER_FILE_NAME);
    }

    public static boolean hasWallpaper(Context context) {
        File f = getWallpaperFile(context);
        return f.exists() && f.length() > 0;
    }

    public static int getScrimAlpha(Context context) {
        return context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .getInt(KEY_SCRIM_ALPHA, DEFAULT_SCRIM_ALPHA);
    }

    public static void setScrimAlpha(Context context, int alpha) {
        int clamped = Math.max(0, Math.min(230, alpha));
        context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SCRIM_ALPHA, clamped)
                .apply();
    }

    public static void clearWallpaper(Context context) {
        File f = getWallpaperFile(context);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public static void saveWallpaperFromUri(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) {
            throw new IllegalArgumentException("Invalid image URI");
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try (InputStream exifStream = context.getContentResolver().openInputStream(uri)) {
                if (exifStream != null) {
                    ExifInterface exif = new ExifInterface(exifStream);
                    orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception ignored) {
            }
        }

        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new Exception("Unable to open image");
            }
            BitmapFactory.decodeStream(in, null, boundsOpts);
        }

        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            throw new Exception("Unsupported image format");
        }

        int sampleSize = 1;
        int w = boundsOpts.outWidth;
        int h = boundsOpts.outHeight;
        while ((w / sampleSize) > MAX_WALLPAPER_DIM || (h / sampleSize) > MAX_WALLPAPER_DIM) {
            sampleSize *= 2;
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new Exception("Unable to open image");
            }
            decoded = BitmapFactory.decodeStream(in, null, decodeOpts);
        }
        if (decoded == null) {
            throw new Exception("Failed to decode image");
        }

        Bitmap oriented = applyOrientation(decoded, orientation);
        File tmpFile = new File(context.getFilesDir(), WALLPAPER_FILE_NAME + ".tmp");
        File outFile = getWallpaperFile(context);
        try (FileOutputStream out = new FileOutputStream(tmpFile)) {
            oriented.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            if (oriented != decoded) {
                oriented.recycle();
            }
            decoded.recycle();
        }
        if (outFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }
        if (!tmpFile.renameTo(outFile)) {
            throw new Exception("Failed to save wallpaper");
        }
    }

    private static Bitmap applyOrientation(Bitmap bmp, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.preScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.preScale(1, -1);
                break;
            default:
                return bmp;
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
    }

    public static void applyBackground(Activity activity, ImageView bgImage, View bgScrim) {
        if (activity == null || bgImage == null || bgScrim == null) {
            return;
        }
        File file = getWallpaperFile(activity);
        if (!file.exists() || file.length() == 0) {
            bgImage.setImageDrawable(null);
            bgImage.setVisibility(View.GONE);
            bgScrim.setVisibility(View.GONE);
            return;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bmp == null) {
            bgImage.setImageDrawable(null);
            bgImage.setVisibility(View.GONE);
            bgScrim.setVisibility(View.GONE);
            return;
        }

        bgImage.setImageBitmap(bmp);
        bgImage.setVisibility(View.VISIBLE);
        int alpha = getScrimAlpha(activity);
        bgScrim.setBackgroundColor(Color.argb(alpha, 11, 17, 26));
        bgScrim.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
    }

    public static void showBackgroundMenu(
            final Activity activity,
            final int pickRequestCode,
            final Runnable onUpdated) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final boolean hasBg = hasWallpaper(activity);
        CharSequence[] items = hasBg
                ? new CharSequence[]{
                activity.getString(R.string.bg_menu_choose),
                activity.getString(R.string.bg_menu_dimness),
                activity.getString(R.string.bg_menu_reset)
        }
                : new CharSequence[]{
                activity.getString(R.string.bg_menu_choose)
        };

        new AlertDialog.Builder(activity)
                .setTitle(R.string.title_custom_background)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("image/*");
                            activity.startActivityForResult(intent, pickRequestCode);
                        } else if (which == 1 && hasBg) {
                            showDimnessPicker(activity, onUpdated);
                        } else if (which == 2 && hasBg) {
                            clearWallpaper(activity);
                            if (onUpdated != null) {
                                onUpdated.run();
                            }
                            Toast.makeText(activity, R.string.bg_reset_done, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showDimnessPicker(final Activity activity, final Runnable onUpdated) {
        final int[] alphas = new int[]{0, 102, 166, 204};
        String[] labels = new String[]{
                activity.getString(R.string.bg_dim_none),
                activity.getString(R.string.bg_dim_light),
                activity.getString(R.string.bg_dim_medium),
                activity.getString(R.string.bg_dim_dark)
        };
        int current = getScrimAlpha(activity);
        int checked = 2;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < alphas.length; i++) {
            int diff = Math.abs(current - alphas[i]);
            if (diff < bestDiff) {
                bestDiff = diff;
                checked = i;
            }
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.bg_menu_dimness)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setScrimAlpha(activity, alphas[which]);
                        if (onUpdated != null) {
                            onUpdated.run();
                        }
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Auto-Discovery Ignore List (UUID-keyed) ----

    public static boolean isComputerIgnored(Context context, String uuid) {
        if (context == null || uuid == null || uuid.trim().isEmpty()) {
            return false;
        }
        String norm = uuid.trim().toLowerCase(Locale.US);
        Set<String> set = context.getSharedPreferences(PREF_IGNORED_PCS, Context.MODE_PRIVATE)
                .getStringSet(KEY_IGNORED_UUIDS, null);
        return set != null && set.contains(norm);
    }

    public static void ignoreComputer(Context context, String uuid) {
        if (context == null || uuid == null || uuid.trim().isEmpty()) {
            return;
        }
        String norm = uuid.trim().toLowerCase(Locale.US);
        SharedPreferences prefs = context.getSharedPreferences(PREF_IGNORED_PCS, Context.MODE_PRIVATE);
        Set<String> existing = prefs.getStringSet(KEY_IGNORED_UUIDS, null);
        Set<String> updated = existing != null ? new HashSet<>(existing) : new HashSet<String>();
        updated.add(norm);
        prefs.edit().putStringSet(KEY_IGNORED_UUIDS, updated).apply();
    }

    public static void unignoreComputer(Context context, String uuid) {
        if (context == null || uuid == null || uuid.trim().isEmpty()) {
            return;
        }
        String norm = uuid.trim().toLowerCase(Locale.US);
        SharedPreferences prefs = context.getSharedPreferences(PREF_IGNORED_PCS, Context.MODE_PRIVATE);
        Set<String> existing = prefs.getStringSet(KEY_IGNORED_UUIDS, null);
        if (existing != null && existing.contains(norm)) {
            Set<String> updated = new HashSet<>(existing);
            updated.remove(norm);
            prefs.edit().putStringSet(KEY_IGNORED_UUIDS, updated).apply();
        }
    }

    // ---- Per-PC Card Cover Modes (Default / Custom Image / Live Screen Capture) ----

    private static String safeFileKey(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return "unknown";
        }
        return uuid.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9_-]", "_");
    }

    public static int getCoverMode(Context context, String uuid) {
        if (context == null || uuid == null || uuid.trim().isEmpty()) {
            return COVER_MODE_DEFAULT;
        }
        return context.getSharedPreferences(PREF_COVER_MODES, Context.MODE_PRIVATE)
                .getInt("mode_" + safeFileKey(uuid), COVER_MODE_DEFAULT);
    }

    public static void setCoverMode(Context context, String uuid, int mode) {
        if (context == null || uuid == null || uuid.trim().isEmpty()) {
            return;
        }
        context.getSharedPreferences(PREF_COVER_MODES, Context.MODE_PRIVATE)
                .edit()
                .putInt("mode_" + safeFileKey(uuid), mode)
                .apply();
    }

    public static File getCustomCoverFile(Context context, String uuid) {
        return new File(context.getFilesDir(), "pc_cover_" + safeFileKey(uuid) + ".jpg");
    }

    public static File getLivePreviewFile(Context context, String uuid) {
        return new File(context.getFilesDir(), "pc_live_" + safeFileKey(uuid) + ".jpg");
    }

    public static void saveCustomCoverFromUri(Context context, String uuid, Uri uri) throws Exception {
        if (context == null || uuid == null || uri == null) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        File outFile = getCustomCoverFile(context, uuid);
        saveSampledJpegFromUri(context, uri, outFile, MAX_COVER_DIM, 88);
        setCoverMode(context, uuid, COVER_MODE_CUSTOM);
        COVER_CACHE.evictAll();
    }

    public static void saveLivePreviewBitmap(Context context, String uuid, Bitmap bmp) {
        if (context == null || uuid == null || uuid.trim().isEmpty() || bmp == null || bmp.isRecycled()) {
            return;
        }
        File outFile = getLivePreviewFile(context, uuid);
        File tmpFile = new File(context.getFilesDir(), outFile.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmpFile)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tmpFile.renameTo(outFile);
            COVER_CACHE.evictAll();
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
        }
    }

    public static Bitmap loadCoverBitmap(Context context, String uuid, int mode) {
        if (context == null || uuid == null || uuid.trim().isEmpty() || mode == COVER_MODE_DEFAULT) {
            return null;
        }
        File file = (mode == COVER_MODE_CUSTOM)
                ? getCustomCoverFile(context, uuid)
                : getLivePreviewFile(context, uuid);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        String cacheKey = safeFileKey(uuid) + ":" + mode + ":" + file.lastModified();
        Bitmap cached = COVER_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp != null) {
            COVER_CACHE.put(cacheKey, bmp);
        }
        return bmp;
    }

    public static void clearPcCoverData(Context context, String uuid) {
        if (context == null || uuid == null || uuid.trim().isEmpty()) {
            return;
        }
        context.getSharedPreferences(PREF_COVER_MODES, Context.MODE_PRIVATE)
                .edit()
                .remove("mode_" + safeFileKey(uuid))
                .apply();
        File custom = getCustomCoverFile(context, uuid);
        if (custom.exists()) {
            //noinspection ResultOfMethodCallIgnored
            custom.delete();
        }
        File live = getLivePreviewFile(context, uuid);
        if (live.exists()) {
            //noinspection ResultOfMethodCallIgnored
            live.delete();
        }
        COVER_CACHE.evictAll();
    }

    public static void applyRoundedOutline(View view, final float radiusDp) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        final float radiusPx = radiusDp * view.getResources().getDisplayMetrics().density;
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(true);
    }

    public static void showCoverModeDialog(
            final Activity activity,
            final ComputerDetails details,
            final int pickRequestCode,
            final Runnable onUpdated,
            final CoverPickRequester requester) {
        if (activity == null || activity.isFinishing() || details == null) {
            return;
        }
        int currentMode = getCoverMode(activity, details.uuid);
        String[] options = new String[]{
                activity.getString(R.string.cover_mode_default),
                activity.getString(R.string.cover_mode_custom),
                activity.getString(R.string.cover_mode_live)
        };
        new AlertDialog.Builder(activity)
                .setTitle(R.string.pcview_menu_cover_mode)
                .setSingleChoiceItems(options, currentMode, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == COVER_MODE_CUSTOM) {
                            if (requester != null) {
                                requester.onRequestPickCover(details.uuid);
                            }
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("image/*");
                            activity.startActivityForResult(intent, pickRequestCode);
                        } else {
                            setCoverMode(activity, details.uuid, which);
                            COVER_CACHE.evictAll();
                            if (onUpdated != null) {
                                onUpdated.run();
                            }
                            if (which == COVER_MODE_LIVE && !getLivePreviewFile(activity, details.uuid).exists()) {
                                Toast.makeText(activity, R.string.cover_live_hint, Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public interface CoverPickRequester {
        void onRequestPickCover(String uuid);
    }

    public static final class CustomShortcut {
        public final String id;
        public final String name;
        public final String command;

        public CustomShortcut(String id, String name, String command) {
            this.id = id;
            this.name = name;
            this.command = command;
        }
    }

    private static String normalizeShortcutKey(String uuid) {
        return (uuid != null && !uuid.trim().isEmpty()) ? uuid.trim() : "global";
    }

    public static List<CustomShortcut> getCustomShortcuts(Context context, String uuid) {
        List<CustomShortcut> result = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREF_SHORTCUTS, Context.MODE_PRIVATE);
        String raw = prefs.getString(normalizeShortcutKey(uuid), "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                String name = obj.optString("name", "").trim();
                String cmd = obj.optString("command", "").trim();
                if (!id.isEmpty() && !name.isEmpty() && !cmd.isEmpty()) {
                    result.add(new CustomShortcut(id, name, cmd));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static void saveCustomShortcuts(Context context, String uuid, List<CustomShortcut> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (CustomShortcut sc : list) {
                if (sc == null || sc.id == null || sc.name == null || sc.command == null) continue;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("id", sc.id);
                    obj.put("name", sc.name.trim());
                    obj.put("command", sc.command.trim());
                    arr.put(obj);
                } catch (Exception ignored) {
                }
            }
        }
        context.getSharedPreferences(PREF_SHORTCUTS, Context.MODE_PRIVATE)
                .edit()
                .putString(normalizeShortcutKey(uuid), arr.toString())
                .apply();
    }

    public static CustomShortcut addCustomShortcut(Context context, String uuid, String name, String command) {
        List<CustomShortcut> list = getCustomShortcuts(context, uuid);
        CustomShortcut created = new CustomShortcut(UUID.randomUUID().toString(), name.trim(), command.trim());
        list.add(created);
        saveCustomShortcuts(context, uuid, list);
        return created;
    }

    public static void updateCustomShortcut(Context context, String uuid, String id, String name, String command) {
        List<CustomShortcut> list = getCustomShortcuts(context, uuid);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.set(i, new CustomShortcut(id, name.trim(), command.trim()));
                saveCustomShortcuts(context, uuid, list);
                return;
            }
        }
    }

    public static void removeCustomShortcut(Context context, String uuid, String id) {
        List<CustomShortcut> list = getCustomShortcuts(context, uuid);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
            }
        }
        saveCustomShortcuts(context, uuid, list);
    }

    private static void saveSampledJpegFromUri(Context context, Uri uri, File outFile, int maxDim, int quality) throws Exception {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try (InputStream exifStream = context.getContentResolver().openInputStream(uri)) {
                if (exifStream != null) {
                    ExifInterface exif = new ExifInterface(exifStream);
                    orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception ignored) {
            }
        }

        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new Exception("Unable to open image");
            }
            BitmapFactory.decodeStream(in, null, boundsOpts);
        }

        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            throw new Exception("Unsupported image format");
        }

        int sampleSize = 1;
        int w = boundsOpts.outWidth;
        int h = boundsOpts.outHeight;
        while ((w / sampleSize) > maxDim || (h / sampleSize) > maxDim) {
            sampleSize *= 2;
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new Exception("Unable to open image");
            }
            decoded = BitmapFactory.decodeStream(in, null, decodeOpts);
        }
        if (decoded == null) {
            throw new Exception("Failed to decode image");
        }

        Bitmap oriented = applyOrientation(decoded, orientation);
        File tmpFile = new File(context.getFilesDir(), outFile.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmpFile)) {
            oriented.compress(Bitmap.CompressFormat.JPEG, quality, out);
        } finally {
            if (oriented != decoded) {
                oriented.recycle();
            }
            decoded.recycle();
        }
        if (outFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }
        if (!tmpFile.renameTo(outFile)) {
            throw new Exception("Failed to save image");
        }
    }
}
