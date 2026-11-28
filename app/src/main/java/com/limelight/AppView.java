package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.BridgeUiPrefs;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends Activity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int HIDE_APP_ID = 7;
    private final static int CUSTOM_LAUNCH_ID = 8;
    private final static int CUSTOM_EDIT_ID = 9;
    private final static int CUSTOM_DELETE_ID = 10;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, getResources().getText(R.string.lost_connection), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, getResources().getText(R.string.scut_not_paired), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);
        String displayName = BridgeUiPrefs.getDisplayName(this, uuidString, computerName);

        BridgeUiPrefs.applyBackground(
                this,
                (android.widget.ImageView) findViewById(R.id.customBgImage),
                findViewById(R.id.customBgScrim));

        TextView label = findViewById(R.id.appListText);
        setTitle(displayName);
        label.setText(displayName);

        ImageButton addShortcutBtn = findViewById(R.id.addShortcutButton);
        if (addShortcutBtn != null) {
            addShortcutBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showShortcutEditorDialog(null);
                }
            });
        }

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void refreshCustomShortcuts() {
        if (appGridAdapter != null) {
            appGridAdapter.syncCustomShortcuts(BridgeUiPrefs.getCustomShortcuts(this, uuidString));
        }
    }

    private NvApp findDesktopApp() {
        if (appGridAdapter == null) {
            return null;
        }
        NvApp fallback = null;
        for (int i = 0; i < appGridAdapter.getCount(); i++) {
            AppObject obj = (AppObject) appGridAdapter.getItem(i);
            if (obj == null || obj.customShortcut != null || obj.app == null) {
                continue;
            }
            if (fallback == null) {
                fallback = obj.app;
            }
            String n = obj.app.getAppName();
            if (n != null && ("Desktop".equalsIgnoreCase(n.trim()) || "桌面".equals(n.trim()))) {
                return obj.app;
            }
        }
        return fallback;
    }

    private String findRunningAppName() {
        if (appGridAdapter == null || lastRunningAppId == 0) {
            return "";
        }
        for (int i = 0; i < appGridAdapter.getCount(); i++) {
            AppObject obj = (AppObject) appGridAdapter.getItem(i);
            if (obj != null && obj.customShortcut == null && obj.app != null && obj.app.getAppId() == lastRunningAppId) {
                return obj.app.getAppName();
            }
        }
        return "";
    }

    private void confirmAndLaunchCustomShortcut(final BridgeUiPrefs.CustomShortcut shortcut) {
        if (computer == null || computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        final NvApp desktopApp = findDesktopApp();
        if (desktopApp == null) {
            Toast.makeText(this, getResources().getString(R.string.shortcut_desktop_missing), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            return;
        }

        String displayPc = BridgeUiPrefs.getDisplayName(this, uuidString, computer.name);
        String msg;
        if (lastRunningAppId != 0 && lastRunningAppId != desktopApp.getAppId()) {
            String runningName = findRunningAppName();
            if (runningName.isEmpty()) {
                runningName = "#" + lastRunningAppId;
            }
            msg = getString(R.string.shortcut_confirm_quit_message,
                    runningName, displayPc, shortcut.name, shortcut.command);
        } else {
            msg = getString(R.string.shortcut_confirm_message,
                    displayPc, shortcut.name, shortcut.command);
        }

        new AlertDialog.Builder(this, R.style.BridgeAlertDialogTheme)
                .setTitle(R.string.shortcut_confirm_title)
                .setMessage(msg)
                .setPositiveButton(R.string.shortcut_confirm_start, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (managerBinder == null || computer.activeAddress == null) {
                            return;
                        }
                        Intent intent = ServerHelper.createStartIntent(AppView.this, desktopApp, computer, managerBinder);
                        intent.putExtra(Game.EXTRA_AUTO_LAUNCH_CMD, shortcut.command);
                        intent.putExtra(Game.EXTRA_AUTO_LAUNCH_LABEL, shortcut.name);
                        startActivity(intent);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showShortcutEditorDialog(final BridgeUiPrefs.CustomShortcut existing) {
        float density = getResources().getDisplayMetrics().density;
        int padH = Math.round(20 * density);
        int padV = Math.round(12 * density);

        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padH, padV, padH, Math.round(4 * density));
        scroll.addView(container);

        TextView nameLabel = new TextView(this);
        nameLabel.setText(R.string.shortcut_name_label);
        nameLabel.setTextColor(getResources().getColor(R.color.bridge_text_secondary));
        nameLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        container.addView(nameLabel);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.shortcut_name_hint);
        nameInput.setSingleLine(true);
        nameInput.setTextColor(getResources().getColor(R.color.bridge_text_primary));
        nameInput.setHintTextColor(getResources().getColor(R.color.bridge_text_secondary));
        if (existing != null) {
            nameInput.setText(existing.name);
        }
        container.addView(nameInput);

        TextView cmdLabel = new TextView(this);
        cmdLabel.setText(R.string.shortcut_cmd_label);
        cmdLabel.setTextColor(getResources().getColor(R.color.bridge_text_secondary));
        cmdLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams cmdLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cmdLabelLp.topMargin = Math.round(10 * density);
        cmdLabel.setLayoutParams(cmdLabelLp);
        container.addView(cmdLabel);

        final EditText cmdInput = new EditText(this);
        cmdInput.setHint(R.string.shortcut_cmd_hint);
        cmdInput.setSingleLine(true);
        cmdInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cmdInput.setTextColor(getResources().getColor(R.color.bridge_text_primary));
        cmdInput.setHintTextColor(getResources().getColor(R.color.bridge_text_secondary));
        if (existing != null) {
            cmdInput.setText(existing.command);
        }
        container.addView(cmdInput);

        TextView presetsLabel = new TextView(this);
        presetsLabel.setText(R.string.shortcut_presets_label);
        presetsLabel.setTextColor(getResources().getColor(R.color.bridge_text_secondary));
        presetsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams pLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLabelLp.topMargin = Math.round(12 * density);
        pLabelLp.bottomMargin = Math.round(6 * density);
        presetsLabel.setLayoutParams(pLabelLp);
        container.addView(presetsLabel);

        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetScroll.addView(presetRow);
        container.addView(presetScroll);

        final String[][] presets = new String[][] {
                {"Windows 终端", "wt.exe"},
                {"PowerShell", "powershell.exe"},
                {"CMD 命令行", "cmd.exe"},
                {"记事本", "notepad.exe"},
                {"资源管理器", "explorer.exe"},
                {"任务管理器", "taskmgr.exe"}
        };
        for (final String[] preset : presets) {
            TextView chip = new TextView(this);
            chip.setText(preset[0]);
            chip.setTextColor(getResources().getColor(R.color.bridge_text_primary));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_icon_pill);
            chip.setPadding(Math.round(12 * density), Math.round(6 * density), Math.round(12 * density), Math.round(6 * density));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = Math.round(8 * density);
            chip.setLayoutParams(chipLp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    nameInput.setText(preset[0]);
                    cmdInput.setText(preset[1]);
                    cmdInput.setSelection(preset[1].length());
                }
            });
            presetRow.addView(chip);
        }

        new AlertDialog.Builder(this, R.style.BridgeAlertDialogTheme)
                .setTitle(existing == null ? R.string.shortcut_add_title : R.string.shortcut_edit_title)
                .setView(scroll)
                .setPositiveButton(R.string.shortcut_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
                        String cmd = cmdInput.getText() != null ? cmdInput.getText().toString().trim() : "";
                        if (name.isEmpty() || cmd.isEmpty()) {
                            Toast.makeText(AppView.this, R.string.shortcut_invalid_toast, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (existing == null) {
                            BridgeUiPrefs.addCustomShortcut(AppView.this, uuidString, name, cmd);
                        } else {
                            BridgeUiPrefs.updateCustomShortcut(AppView.this, uuidString, existing.id, name, cmd);
                        }
                        refreshCustomShortcuts();
                        Toast.makeText(AppView.this, getString(R.string.shortcut_saved_toast, name), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);
        BridgeUiPrefs.applyBackground(
                this,
                (android.widget.ImageView) findViewById(R.id.customBgImage),
                findViewById(R.id.customBgScrim));

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());
        List<AppMenuAction> actions = buildAppMenuActions(selectedApp, info.targetView);
        for (int i = 0; i < actions.size(); i++) {
            AppMenuAction action = actions.get(i);
            MenuItem mi = menu.add(Menu.NONE, action.id, i + 1, action.label);
            if (action.id == HIDE_APP_ID) {
                mi.setCheckable(true);
                mi.setChecked(selectedApp.isHidden);
            }
        }
    }

    private static final class AppMenuAction {
        final int id;
        final String label;

        AppMenuAction(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private List<AppMenuAction> buildAppMenuActions(AppObject selectedApp, View itemView) {
        List<AppMenuAction> actions = new ArrayList<>();
        if (selectedApp.customShortcut != null) {
            actions.add(new AppMenuAction(CUSTOM_LAUNCH_ID, getResources().getString(R.string.shortcut_menu_launch)));
            actions.add(new AppMenuAction(CUSTOM_EDIT_ID, getResources().getString(R.string.shortcut_menu_edit)));
            actions.add(new AppMenuAction(CUSTOM_DELETE_ID, getResources().getString(R.string.shortcut_menu_delete)));
            return actions;
        }

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                actions.add(new AppMenuAction(START_OR_RESUME_ID, getResources().getString(R.string.applist_menu_resume)));
                actions.add(new AppMenuAction(QUIT_ID, getResources().getString(R.string.applist_menu_quit)));
            } else {
                actions.add(new AppMenuAction(START_WITH_QUIT, getResources().getString(R.string.applist_menu_quit_and_start)));
            }
        }

        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            String hideLabel = (selectedApp.isHidden ? "✓ " : "") + getResources().getString(R.string.applist_menu_hide_app);
            actions.add(new AppMenuAction(HIDE_APP_ID, hideLabel));
        }

        actions.add(new AppMenuAction(VIEW_DETAILS_ID, getResources().getString(R.string.applist_menu_details)));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && itemView != null) {
            ImageView appImageView = itemView.findViewById(R.id.grid_image);
            if (appImageView != null && appImageView.getDrawable() instanceof BitmapDrawable) {
                BitmapDrawable drawable = (BitmapDrawable) appImageView.getDrawable();
                if (drawable.getBitmap() != null) {
                    actions.add(new AppMenuAction(CREATE_SHORTCUT_ID, getResources().getString(R.string.applist_menu_scut)));
                }
            }
        }
        return actions;
    }

    private void showAppActionDialog(final AppObject selectedApp, final View itemView) {
        if (selectedApp == null || isFinishing()) {
            return;
        }
        final List<AppMenuAction> actions = buildAppMenuActions(selectedApp, itemView);
        String[] labels = new String[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            labels[i] = actions.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle(selectedApp.app.getAppName())
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        executeAppMenuAction(actions.get(which).id, selectedApp, itemView);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);
        if (executeAppMenuAction(item.getItemId(), app, info.targetView)) {
            return true;
        }
        return super.onContextItemSelected(item);
    }

    private boolean executeAppMenuAction(int actionId, final AppObject app, View itemView) {
        switch (actionId) {
            case CUSTOM_LAUNCH_ID:
                if (app.customShortcut != null) {
                    confirmAndLaunchCustomShortcut(app.customShortcut);
                }
                return true;

            case CUSTOM_EDIT_ID:
                if (app.customShortcut != null) {
                    showShortcutEditorDialog(app.customShortcut);
                }
                return true;

            case CUSTOM_DELETE_ID:
                if (app.customShortcut != null) {
                    String removedName = app.customShortcut.name;
                    BridgeUiPrefs.removeCustomShortcut(this, uuidString, app.customShortcut.id);
                    refreshCustomShortcuts();
                    Toast.makeText(this, getString(R.string.shortcut_deleted_toast, removedName), Toast.LENGTH_SHORT).show();
                }
                return true;

            case START_WITH_QUIT:
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
                    }
                }, null);
                return true;

            case START_OR_RESUME_ID:
                // Resume is the same as start for us
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
                return true;

            case QUIT_ID:
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                            @Override
                            public void run() {
                                // Trigger a poll immediately
                                suspendGridUpdates = false;
                                if (poller != null) {
                                    poller.pollNow();
                                }
                            }
                        });
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false, false);
                return true;

            case HIDE_APP_ID:
                if (app.isHidden) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                }
                else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;

            case CREATE_SHORTCUT_ID:
                if (itemView != null) {
                    ImageView appImageView = itemView.findViewById(R.id.grid_image);
                    Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                    if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                        Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                    }
                }
                return true;

            default:
                return false;
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                    if (existingApp.customShortcut != null) {
                        continue;
                    }

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.customShortcut != null) {
                            continue;
                        }
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                    if (existingApp.customShortcut != null) {
                        i++;
                        continue;
                    }

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, "App removed from PC");
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(appGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                AppObject app = (AppObject) appGridAdapter.getItem(pos);
                if (app.customShortcut != null) {
                    confirmAndLaunchCustomShortcut(app.customShortcut);
                    return;
                }

                // Only open the action menu if something is running, otherwise start it
                if (lastRunningAppId != 0) {
                    showAppActionDialog(app, arg1);
                } else {
                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder);
                }
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                AppObject app = (AppObject) appGridAdapter.getItem(position);
                showAppActionDialog(app, view);
                return true;
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        listView.requestFocus();
    }

    public static class AppObject {
        public final NvApp app;
        public final BridgeUiPrefs.CustomShortcut customShortcut;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
            this.customShortcut = null;
        }

        public AppObject(BridgeUiPrefs.CustomShortcut customShortcut) {
            if (customShortcut == null) {
                throw new IllegalArgumentException("customShortcut must not be null");
            }
            int syntheticId = -Math.max(1000, Math.abs(customShortcut.id.hashCode()));
            this.app = new NvApp(customShortcut.name, syntheticId, false);
            this.customShortcut = customShortcut;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
