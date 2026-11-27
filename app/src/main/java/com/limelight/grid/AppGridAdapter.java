package com.limelight.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.BridgeUiPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {
    private static final int ART_WIDTH_PX = 300;
    private static final int SMALL_WIDTH_DP = 100;
    private static final int LARGE_WIDTH_DP = 150;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppView.AppObject> allApps = new ArrayList<>();

    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, getLayoutIdForPreferences(prefs));

        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateLayoutWithPreferences(context, prefs);
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        this.hiddenAppIds.clear();
        this.hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set
            itemList.clear();
            for (AppView.AppObject app : allApps) {
                if (app.customShortcut != null) {
                    app.isHidden = false;
                    itemList.add(app);
                    continue;
                }
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());

                if (!app.isHidden || showHiddenApps) {
                    itemList.add(app);
                }
            }
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = app.customShortcut == null && hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        if (prefs.smallIconMode) {
            return R.layout.app_grid_item_small;
        }
        else {
            return R.layout.app_grid_item;
        }
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp;

        if (prefs.smallIconMode) {
            dp = SMALL_WIDTH_DP;
        }
        else {
            dp = LARGE_WIDTH_DP;
        }

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));

        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    private static void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                boolean lCustom = lhs.customShortcut != null;
                boolean rCustom = rhs.customShortcut != null;
                if (lCustom != rCustom) {
                    return lCustom ? 1 : -1;
                }
                return lhs.app.getAppName().toLowerCase().compareTo(rhs.app.getAppName().toLowerCase());
            }
        });
    }

    public void syncCustomShortcuts(List<BridgeUiPrefs.CustomShortcut> shortcuts) {
        for (int i = allApps.size() - 1; i >= 0; i--) {
            if (allApps.get(i).customShortcut != null) {
                allApps.remove(i);
            }
        }
        for (int i = itemList.size() - 1; i >= 0; i--) {
            if (itemList.get(i).customShortcut != null) {
                itemList.remove(i);
            }
        }
        if (shortcuts != null) {
            for (BridgeUiPrefs.CustomShortcut sc : shortcuts) {
                AppView.AppObject obj = new AppView.AppObject(sc);
                allApps.add(obj);
                itemList.add(obj);
            }
        }
        sortList(allApps);
        sortList(itemList);
        notifyDataSetChanged();
    }

    public void addApp(AppView.AppObject app) {
        if (app.customShortcut != null) {
            app.isHidden = false;
            allApps.add(app);
            sortList(allApps);
            itemList.add(app);
            sortList(itemList);
            return;
        }

        // Update hidden state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);
        sortList(allApps);

        // Add the app to the adapter data if it's not hidden
        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache
            loader.queueCacheLoad(app.app);

            // Add the app to our sorted list
            itemList.add(app);
            sortList(itemList);
        }
    }

    public void removeApp(AppView.AppObject app) {
        itemList.remove(app);
        allApps.remove(app);
    }

    @Override
    public void clear() {
        super.clear();
        allApps.clear();
    }

    @Override
    public void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, AppView.AppObject obj) {
        if (obj.customShortcut != null) {
            loader.cancelImageViewLoad(imgView);
            imgView.setImageDrawable(null);
            overlayView.setImageResource(R.drawable.ic_shortcut_launch);
            overlayView.setVisibility(View.VISIBLE);
            txtView.setText(obj.customShortcut.name + "\n" + obj.customShortcut.command);
            txtView.setVisibility(View.VISIBLE);
            parentView.setAlpha(1.0f);
            return;
        }

        // Let the cached asset loader handle it
        loader.populateImageView(obj.app, imgView, txtView);

        if (obj.isRunning) {
            // Show the play button overlay
            overlayView.setImageResource(R.drawable.ic_play);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }

        if (obj.isHidden) {
            parentView.setAlpha(0.40f);
        }
        else {
            parentView.setAlpha(1.0f);
        }
    }
}
