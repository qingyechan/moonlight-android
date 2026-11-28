package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
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
    private static final String KEY_UI_STYLE = "ui_style_mode";
    private static final String KEY_THEME_COLOR = "theme_color_id";
    private static final String KEY_CUSTOM_ACCENT_HEX = "theme_custom_accent_hex";
    private static final String KEY_LOADING_STYLE = "loading_dialog_style";
    private static final String WALLPAPER_FILE_NAME = "custom_wallpaper.jpg";
    private static final int DEFAULT_SCRIM_ALPHA = 166; // ~65%
    private static final int MAX_WALLPAPER_DIM = 2560;
    private static final int MAX_COVER_DIM = 960;

    public static final int UI_STYLE_BRIDGE = 0;
    public static final int UI_STYLE_CLASSIC = 1;

    public static final int LOADING_STYLE_STARLIGHT = 0;
    public static final int LOADING_STYLE_GLOW = 1;
    public static final int LOADING_STYLE_CYBER = 2;
    public static final int LOADING_STYLE_CLASSIC = 3;

    public static final String THEME_SKY = "sky";
    public static final String THEME_SAKURA = "sakura";
    public static final String THEME_VIOLET = "violet";
    public static final String THEME_MINT = "mint";
    public static final String THEME_AMBER = "amber";
    public static final String THEME_EMERALD = "emerald";
    public static final String THEME_CUSTOM = "custom";

    public static final int COVER_MODE_DEFAULT = 0;
    public static final int COVER_MODE_CUSTOM = 1;
    public static final int COVER_MODE_LIVE = 2;

    /**
     * Resolved color palette for the active UI version and color preset.
     */
    public static final class ThemePalette {
        public final String id;
        public final int nameResId;
        public final int bgColor;
        public final int surfaceColor;
        public final int cardColor;
        public final int cardPressedColor;
        public final int borderColor;
        public final int accentColor;
        public final int sidebarStartColor;
        public final int sidebarEndColor;

        ThemePalette(
                String id,
                int nameResId,
                int bgColor,
                int surfaceColor,
                int cardColor,
                int cardPressedColor,
                int borderColor,
                int accentColor,
                int sidebarStartColor,
                int sidebarEndColor) {
            this.id = id;
            this.nameResId = nameResId;
            this.bgColor = bgColor;
            this.surfaceColor = surfaceColor;
            this.cardColor = cardColor;
            this.cardPressedColor = cardPressedColor;
            this.borderColor = borderColor;
            this.accentColor = accentColor;
            this.sidebarStartColor = sidebarStartColor;
            this.sidebarEndColor = sidebarEndColor;
        }
    }

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

    public static int getUiStyle(Context context) {
        if (context == null) {
            return UI_STYLE_BRIDGE;
        }
        return context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .getInt(KEY_UI_STYLE, UI_STYLE_BRIDGE);
    }

    public static void setUiStyle(Context context, int style) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_UI_STYLE, style == UI_STYLE_CLASSIC ? UI_STYLE_CLASSIC : UI_STYLE_BRIDGE)
                .apply();
    }

    public static boolean isClassicUi(Context context) {
        return getUiStyle(context) == UI_STYLE_CLASSIC;
    }

    public static int getLoadingStyle(Context context) {
        if (context == null) {
            return LOADING_STYLE_STARLIGHT;
        }
        int val = context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .getInt(KEY_LOADING_STYLE, LOADING_STYLE_STARLIGHT);
        if (val < LOADING_STYLE_STARLIGHT || val > LOADING_STYLE_CLASSIC) {
            return LOADING_STYLE_STARLIGHT;
        }
        return val;
    }

    public static void setLoadingStyle(Context context, int style) {
        if (context == null) {
            return;
        }
        int clamped = (style >= LOADING_STYLE_STARLIGHT && style <= LOADING_STYLE_CLASSIC)
                ? style
                : LOADING_STYLE_STARLIGHT;
        context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_LOADING_STYLE, clamped)
                .apply();
    }

    public static String getThemeColorId(Context context) {
        if (context == null) {
            return THEME_SKY;
        }
        String val = context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .getString(KEY_THEME_COLOR, THEME_SKY);
        return val != null ? val : THEME_SKY;
    }

    public static void setThemeColorId(Context context, String themeId) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME_COLOR, themeId != null ? themeId : THEME_SKY)
                .apply();
    }

    public static String getCustomAccentHex(Context context) {
        if (context == null) {
            return "#FF6B9E";
        }
        String val = context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_ACCENT_HEX, "#FF6B9E");
        return val != null ? val : "#FF6B9E";
    }

    public static void setCustomAccentHex(Context context, String hex) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_ACCENT_HEX, hex)
                .putString(KEY_THEME_COLOR, THEME_CUSTOM)
                .apply();
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static ThemePalette buildCustomPalette(int accentColor) {
        float[] hsv = new float[3];
        Color.colorToHSV(accentColor, hsv);
        float hue = hsv[0];
        float sat = Math.max(0.18f, Math.min(0.42f, hsv[1] * 0.45f));
        int bg = Color.HSVToColor(new float[]{hue, sat, 0.08f});
        int surface = Color.HSVToColor(new float[]{hue, sat, 0.12f});
        int card = Color.HSVToColor(new float[]{hue, Math.min(0.45f, sat + 0.03f), 0.17f});
        int pressed = Color.HSVToColor(new float[]{hue, Math.min(0.48f, sat + 0.06f), 0.24f});
        int border = Color.HSVToColor(new float[]{hue, Math.min(0.50f, sat + 0.08f), 0.30f});
        int sideStart = withAlpha(surface, 0xD6);
        int sideEnd = withAlpha(bg, 0xE2);
        return new ThemePalette(
                THEME_CUSTOM, R.string.theme_color_custom,
                bg, surface, card, pressed, border, accentColor, sideStart, sideEnd);
    }

    private static final ThemePalette[] PRESET_PALETTES = new ThemePalette[]{
            new ThemePalette(
                    THEME_SKY, R.string.theme_color_sky,
                    0xFF0D1117, 0xFF141A22, 0xFF1B222C, 0xFF232D3B, 0xFF2B3646, 0xFF38BDF8,
                    0xD4162238, 0xE00B1322),
            new ThemePalette(
                    THEME_SAKURA, R.string.theme_color_sakura,
                    0xFF130D14, 0xFF1C131E, 0xFF251828, 0xFF312036, 0xFF472A4E, 0xFFFB7185,
                    0xD42A162E, 0xE0160D19),
            new ThemePalette(
                    THEME_VIOLET, R.string.theme_color_violet,
                    0xFF0F0D18, 0xFF171324, 0xFF201A33, 0xFF2B2344, 0xFF3B305E, 0xFFA78BFA,
                    0xD421193B, 0xE0100D1E),
            new ThemePalette(
                    THEME_MINT, R.string.theme_color_mint,
                    0xFF0B1314, 0xFF111D1F, 0xFF18292B, 0xFF203639, 0xFF2B494D, 0xFF2DD4BF,
                    0xD414282B, 0xE00B1618),
            new ThemePalette(
                    THEME_AMBER, R.string.theme_color_amber,
                    0xFF14110B, 0xFF1E1910, 0xFF292216, 0xFF362D1D, 0xFF4A3D27, 0xFFFBBF24,
                    0xD42B2212, 0xE017120A),
            new ThemePalette(
                    THEME_EMERALD, R.string.theme_color_emerald,
                    0xFF0B1410, 0xFF111E18, 0xFF182A22, 0xFF20382D, 0xFF2B4B3C, 0xFF34D399,
                    0xD413291F, 0xE00A1610)
    };

    public static ThemePalette getThemePalette(Context context) {
        if (isClassicUi(context)) {
            return new ThemePalette(
                    "classic", R.string.ui_style_classic,
                    0xFF111111, 0xFF191919, 0xFF1F1F1F, 0xFF2B2B2B, 0xFF323232, 0xFF4FC3F7,
                    0xD4181818, 0xE0101010);
        }
        String id = getThemeColorId(context);
        if (THEME_CUSTOM.equals(id)) {
            try {
                int accent = Color.parseColor(getCustomAccentHex(context));
                return buildCustomPalette(accent);
            } catch (IllegalArgumentException ignored) {
                return PRESET_PALETTES[0];
            }
        }
        for (ThemePalette p : PRESET_PALETTES) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return PRESET_PALETTES[0];
    }

    public static GradientDrawable buildRoundedShape(Context context, int fillColor, int strokeColor, float strokeDp, float radiusDp) {
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        if (strokeDp > 0f) {
            d.setStroke(Math.max(1, Math.round(strokeDp * density)), strokeColor);
        }
        if (radiusDp > 0f) {
            d.setCornerRadius(radiusDp * density);
        }
        return d;
    }

    public static StateListDrawable buildStateCardDrawable(
            Context context,
            int normalBg,
            int pressedBg,
            int borderColor,
            int accentColor,
            float radiusDp) {
        StateListDrawable sld = new StateListDrawable();
        GradientDrawable active = buildRoundedShape(context, pressedBg, accentColor, 1.5f, radiusDp);
        GradientDrawable normal = buildRoundedShape(context, normalBg, borderColor, 1.0f, radiusDp);
        sld.addState(new int[]{android.R.attr.state_pressed}, active);
        sld.addState(new int[]{android.R.attr.state_focused}, active);
        sld.addState(new int[]{android.R.attr.state_selected}, active);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    private static void styleActionButton(Context context, View btn, ThemePalette palette, boolean classic) {
        if (btn == null) {
            return;
        }
        float radius = classic ? 8f : 12f;
        int normalBg = classic ? 0xFF222222 : palette.cardColor;
        int pressedBg = classic ? 0xFF333333 : palette.cardPressedColor;
        int border = classic ? 0xFF363636 : palette.borderColor;
        btn.setBackground(buildStateCardDrawable(context, normalBg, pressedBg, border, palette.accentColor, radius));
    }

    public static void applyThemeToPcView(Activity activity) {
        if (activity == null) {
            return;
        }
        ThemePalette palette = getThemePalette(activity);
        boolean classic = isClassicUi(activity);

        View root = activity.findViewById(R.id.pcViewRoot);
        if (root != null) {
            root.setBackgroundColor(palette.bgColor);
        }

        View topBar = activity.findViewById(R.id.topBar);
        if (topBar != null) {
            topBar.setBackground(classic
                    ? buildRoundedShape(activity, 0xFF171717, 0xFF2A2A2A, 1f, 8f)
                    : buildRoundedShape(activity, withAlpha(palette.surfaceColor, 0xEE), withAlpha(palette.borderColor, 0xAA), 1f, 18f));
        }

        View sideRail = activity.findViewById(R.id.sideRail);
        if (sideRail != null) {
            sideRail.setBackground(classic
                    ? buildRoundedShape(activity, 0xFF171717, 0xFF2A2A2A, 1f, 8f)
                    : buildRoundedShape(activity, withAlpha(palette.surfaceColor, 0xEE), withAlpha(palette.borderColor, 0xAA), 1f, 18f));
        }

        View subtitle = activity.findViewById(R.id.pcTitleSubtitle);
        if (subtitle != null) {
            subtitle.setVisibility(classic ? View.GONE : View.VISIBLE);
        }

        styleActionButton(activity, activity.findViewById(R.id.settingsButton), palette, classic);
        styleActionButton(activity, activity.findViewById(R.id.bgSettingsButton), palette, classic);
        styleActionButton(activity, activity.findViewById(R.id.renameDevicesButton), palette, classic);
        styleActionButton(activity, activity.findViewById(R.id.checkUpdateButton), palette, classic);
        styleActionButton(activity, activity.findViewById(R.id.manuallyAddPc), palette, classic);

        View noPc = activity.findViewById(R.id.no_pc_found_layout);
        if (noPc != null) {
            noPc.setBackground(buildRoundedShape(
                    activity,
                    classic ? 0xFF1A1A1A : withAlpha(palette.surfaceColor, 0xEE),
                    classic ? 0xFF303030 : palette.borderColor,
                    1f,
                    classic ? 10f : 18f));
        }
        ProgressBar pcsLoading = activity.findViewById(R.id.pcs_loading);
        if (pcsLoading != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pcsLoading.setIndeterminateTintList(ColorStateList.valueOf(palette.accentColor));
        }

        applyBackground(
                activity,
                (ImageView) activity.findViewById(R.id.customBgImage),
                activity.findViewById(R.id.customBgScrim));
    }

    public static void applyThemeToAppView(Activity activity) {
        if (activity == null) {
            return;
        }
        ThemePalette palette = getThemePalette(activity);
        boolean classic = isClassicUi(activity);

        View root = activity.findViewById(R.id.appViewRoot);
        if (root != null) {
            root.setBackgroundColor(palette.bgColor);
        }
        View header = activity.findViewById(R.id.appHeaderContainer);
        if (header != null) {
            header.setBackgroundColor(palette.surfaceColor);
        }
        View divider = activity.findViewById(R.id.appHeaderDivider);
        if (divider != null) {
            divider.setBackgroundColor(classic ? palette.borderColor : withAlpha(palette.accentColor, 0x44));
        }
        styleActionButton(activity, activity.findViewById(R.id.addShortcutButton), palette, classic);

        applyBackground(
                activity,
                (ImageView) activity.findViewById(R.id.customBgImage),
                activity.findViewById(R.id.customBgScrim));
    }

    public static void applyThemeToPcCard(Context context, View cardView) {
        if (context == null || cardView == null) {
            return;
        }
        ThemePalette palette = getThemePalette(context);
        boolean classic = isClassicUi(context);
        if (classic) {
            cardView.setBackground(buildStateCardDrawable(
                    context, 0xFF1E1E1E, 0xFF2B2B2B, 0xFF303030, palette.accentColor, 8f));
        } else {
            cardView.setBackground(buildStateCardDrawable(
                    context,
                    withAlpha(palette.cardColor, 0xDE),
                    withAlpha(palette.cardPressedColor, 0xF0),
                    withAlpha(palette.borderColor, 0x99),
                    palette.accentColor,
                    18f));
        }
    }

    public static void applyThemeToAppCard(Context context, View cardView, boolean isCustomShortcut) {
        if (context == null || cardView == null) {
            return;
        }
        ThemePalette palette = getThemePalette(context);
        boolean classic = isClassicUi(context);
        int border = isCustomShortcut && !classic
                ? withAlpha(palette.accentColor, 0x88)
                : (classic ? 0xFF303030 : withAlpha(palette.borderColor, 0x99));
        cardView.setBackground(buildStateCardDrawable(
                context,
                classic ? 0xFF1E1E1E : withAlpha(palette.cardColor, 0xDE),
                classic ? 0xFF2B2B2B : withAlpha(palette.cardPressedColor, 0xF0),
                border,
                palette.accentColor,
                classic ? 8f : 16f));
    }

    public static void applyThemeToGameSidebar(Context context, View triggerView, View sidebarPanel) {
        if (context == null) {
            return;
        }
        ThemePalette palette = getThemePalette(context);
        if (triggerView != null) {
            triggerView.setBackground(buildRoundedShape(
                    context,
                    withAlpha(palette.surfaceColor, 0xB8),
                    withAlpha(palette.accentColor, 0x88),
                    1f,
                    11f));
        }
        if (sidebarPanel != null) {
            float density = context.getResources().getDisplayMetrics().density;
            float r = 14f * density;
            GradientDrawable panelBg = new GradientDrawable(
                    GradientDrawable.Orientation.RIGHT_LEFT,
                    new int[]{palette.sidebarStartColor, palette.sidebarEndColor});
            panelBg.setStroke(Math.max(1, Math.round(density)), withAlpha(palette.accentColor, 0x66));
            panelBg.setCornerRadii(new float[]{r, r, 0f, 0f, 0f, 0f, r, r});
            sidebarPanel.setBackground(panelBg);
        }
    }

    public static void applyBackground(Activity activity, ImageView bgImage, View bgScrim) {
        if (activity == null || bgImage == null || bgScrim == null) {
            return;
        }
        if (isClassicUi(activity)) {
            bgImage.setImageDrawable(null);
            bgImage.setVisibility(View.GONE);
            bgScrim.setVisibility(View.GONE);
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
        showAppearanceMenu(activity, pickRequestCode, onUpdated, null);
    }

    public static void showAppearanceMenu(
            final Activity activity,
            final int pickRequestCode,
            final Runnable onThemeUpdated,
            final Runnable onUiStyleChanged) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final boolean hasBg = hasWallpaper(activity);
        String uiLabel = activity.getString(isClassicUi(activity) ? R.string.ui_style_classic : R.string.ui_style_bridge);
        ThemePalette activePalette = getThemePalette(activity);
        String colorLabel = THEME_CUSTOM.equals(activePalette.id)
                ? getCustomAccentHex(activity)
                : activity.getString(activePalette.nameResId);
        String loadingLabel = getLoadingStyleLabel(activity, getLoadingStyle(activity));

        List<String> itemList = new ArrayList<>();
        itemList.add(activity.getString(R.string.appearance_menu_ui_style, uiLabel));
        itemList.add(activity.getString(R.string.appearance_menu_theme_color, colorLabel));
        itemList.add(activity.getString(R.string.appearance_menu_loading_style, loadingLabel));
        itemList.add(activity.getString(R.string.bg_menu_choose));
        if (hasBg) {
            itemList.add(activity.getString(R.string.bg_menu_dimness));
            itemList.add(activity.getString(R.string.bg_menu_reset));
        }
        CharSequence[] items = itemList.toArray(new CharSequence[0]);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.title_appearance_settings)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showUiStylePicker(activity, onUiStyleChanged, onThemeUpdated);
                        } else if (which == 1) {
                            showThemeColorPicker(activity, onThemeUpdated);
                        } else if (which == 2) {
                            showLoadingStylePicker(activity);
                        } else if (which == 3) {
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("image/*");
                            activity.startActivityForResult(intent, pickRequestCode);
                        } else if (which == 4 && hasBg) {
                            showDimnessPicker(activity, onThemeUpdated);
                        } else if (which == 5 && hasBg) {
                            clearWallpaper(activity);
                            if (onThemeUpdated != null) {
                                onThemeUpdated.run();
                            }
                            Toast.makeText(activity, R.string.bg_reset_done, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String getLoadingStyleLabel(Context context, int style) {
        switch (style) {
            case LOADING_STYLE_GLOW:
                return context.getString(R.string.loading_style_glow);
            case LOADING_STYLE_CYBER:
                return context.getString(R.string.loading_style_cyber);
            case LOADING_STYLE_CLASSIC:
                return context.getString(R.string.loading_style_classic);
            case LOADING_STYLE_STARLIGHT:
            default:
                return context.getString(R.string.loading_style_starlight);
        }
    }

    private static void showUiStylePicker(
            final Activity activity,
            final Runnable onUiStyleChanged,
            final Runnable onThemeUpdated) {
        final int[] styles = new int[]{UI_STYLE_BRIDGE, UI_STYLE_CLASSIC};
        String[] labels = new String[]{
                activity.getString(R.string.ui_style_bridge),
                activity.getString(R.string.ui_style_classic)
        };
        int checked = isClassicUi(activity) ? 1 : 0;

        new AlertDialog.Builder(activity)
                .setTitle(R.string.ui_style_dialog_title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int chosen = styles[which];
                        setUiStyle(activity, chosen);
                        dialog.dismiss();
                        Toast.makeText(
                                activity,
                                activity.getString(R.string.ui_style_switched_toast, labels[which]),
                                Toast.LENGTH_SHORT).show();
                        if (onUiStyleChanged != null) {
                            onUiStyleChanged.run();
                        } else if (onThemeUpdated != null) {
                            onThemeUpdated.run();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showThemeColorPicker(final Activity activity, final Runnable onUpdated) {
        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Integer> swatchColors = new ArrayList<>();

        for (ThemePalette p : PRESET_PALETTES) {
            ids.add(p.id);
            labels.add(activity.getString(p.nameResId));
            swatchColors.add(p.accentColor);
        }
        ids.add(THEME_CUSTOM);
        labels.add(activity.getString(R.string.theme_color_custom) + " (" + getCustomAccentHex(activity) + ")");
        int customPreview;
        try {
            customPreview = Color.parseColor(getCustomAccentHex(activity));
        } catch (IllegalArgumentException e) {
            customPreview = 0xFFFF6B9E;
        }
        swatchColors.add(customPreview);

        final String activeId = getThemeColorId(activity);
        final float density = activity.getResources().getDisplayMetrics().density;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padH = Math.round(20 * density);
                int padV = Math.round(13 * density);
                row.setPadding(padH, padV, padH, padV);

                View swatch = new View(activity);
                int size = Math.round(20 * density);
                LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(size, size);
                swLp.rightMargin = Math.round(14 * density);
                swatch.setLayoutParams(swLp);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(swatchColors.get(position));
                circle.setStroke(Math.round(1.5f * density), 0x80FFFFFF);
                swatch.setBackground(circle);
                row.addView(swatch);

                TextView title = new TextView(activity);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                title.setLayoutParams(tLp);
                title.setText(labels.get(position));
                title.setTextColor(0xFFF1F5F9);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                row.addView(title);

                if (ids.get(position).equals(activeId)) {
                    TextView check = new TextView(activity);
                    check.setText("✓");
                    check.setTextColor(swatchColors.get(position));
                    check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                    row.addView(check);
                }
                return row;
            }
        };

        new AlertDialog.Builder(activity)
                .setTitle(R.string.theme_color_dialog_title)
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String chosenId = ids.get(which);
                        if (THEME_CUSTOM.equals(chosenId)) {
                            showCustomHexDialog(activity, onUpdated);
                            return;
                        }
                        if (isClassicUi(activity)) {
                            setUiStyle(activity, UI_STYLE_BRIDGE);
                        }
                        setThemeColorId(activity, chosenId);
                        if (onUpdated != null) {
                            onUpdated.run();
                        }
                        Toast.makeText(
                                activity,
                                activity.getString(R.string.theme_color_updated_toast, labels.get(which)),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showCustomHexDialog(final Activity activity, final Runnable onUpdated) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(R.string.theme_color_custom_hint);
        input.setText(getCustomAccentHex(activity));
        input.setSelection(input.getText().length());
        int pad = Math.round(18 * activity.getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(activity);
        container.setPadding(pad, Math.round(8 * activity.getResources().getDisplayMetrics().density), pad, 0);
        container.addView(input);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.theme_color_custom_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText().toString().trim();
                        if (!raw.startsWith("#")) {
                            raw = "#" + raw;
                        }
                        if (!raw.matches("^#[0-9A-Fa-f]{6}$")) {
                            Toast.makeText(activity, R.string.theme_color_invalid_hex, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String normalized = raw.toUpperCase(Locale.US);
                        if (isClassicUi(activity)) {
                            setUiStyle(activity, UI_STYLE_BRIDGE);
                        }
                        setCustomAccentHex(activity, normalized);
                        if (onUpdated != null) {
                            onUpdated.run();
                        }
                        Toast.makeText(
                                activity,
                                activity.getString(R.string.theme_color_updated_toast, normalized),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showLoadingStylePicker(final Activity activity) {
        final int[] styles = new int[]{
                LOADING_STYLE_STARLIGHT,
                LOADING_STYLE_GLOW,
                LOADING_STYLE_CYBER,
                LOADING_STYLE_CLASSIC
        };
        String[] labels = new String[]{
                activity.getString(R.string.loading_style_starlight),
                activity.getString(R.string.loading_style_glow),
                activity.getString(R.string.loading_style_cyber),
                activity.getString(R.string.loading_style_classic)
        };
        int current = getLoadingStyle(activity);
        int checked = 0;
        for (int i = 0; i < styles.length; i++) {
            if (styles[i] == current) {
                checked = i;
                break;
            }
        }
        final int[] selectedHolder = new int[]{current};

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.loading_style_dialog_title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        selectedHolder[0] = styles[which];
                        setLoadingStyle(activity, selectedHolder[0]);
                    }
                })
                .setNeutralButton(R.string.loading_style_preview_btn, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();

        View previewBtn = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (previewBtn != null) {
            previewBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setLoadingStyle(activity, selectedHolder[0]);
                    final SpinnerDialog preview = SpinnerDialog.displayDialog(
                            activity,
                            activity.getString(R.string.loading_style_preview_title),
                            activity.getString(R.string.loading_style_preview_msg),
                            false);
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!activity.isFinishing()) {
                                preview.dismiss();
                            }
                        }
                    }, 2200);
                }
            });
        }
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
