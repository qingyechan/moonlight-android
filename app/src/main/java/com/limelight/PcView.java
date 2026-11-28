package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.binding.input.KeyboardBridgeHelper;
import com.limelight.update.AppUpdateManager;
import com.limelight.utils.BridgeUiPrefs;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    private View noPcFoundLayout;
    private View keyboardBridgePill;
    private View keyboardBridgeDot;
    private TextView keyboardBridgeText;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
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

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
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

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int RENAME_PC_ID = 12;
    private final static int IGNORE_PC_ID = 13;
    private final static int COVER_PC_ID = 14;
    private final static int REQUEST_PICK_WALLPAPER = 1001;
    private final static int REQUEST_PICK_PC_COVER = 1002;

    private volatile boolean pairingInProgress = false;
    private long lastPairAttemptElapsedMs = 0L;
    private AlertDialog activePairingDialog = null;
    private String pendingCoverUuid = null;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });

        View checkUpdateButton = findViewById(R.id.checkUpdateButton);
        if (checkUpdateButton != null) {
            checkUpdateButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    AppUpdateManager.checkForUpdates(PcView.this);
                }
            });
        }

        View bgSettingsButton = findViewById(R.id.bgSettingsButton);
        if (bgSettingsButton != null) {
            bgSettingsButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    BridgeUiPrefs.showAppearanceMenu(
                            PcView.this,
                            REQUEST_PICK_WALLPAPER,
                            new Runnable() {
                                @Override
                                public void run() {
                                    applyCustomBackground();
                                    if (pcGridAdapter != null) {
                                        pcGridAdapter.notifyDataSetChanged();
                                    }
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    recreate();
                                }
                            });
                }
            });
        }

        View renameDevicesButton = findViewById(R.id.renameDevicesButton);
        if (renameDevicesButton != null) {
            renameDevicesButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    BridgeUiPrefs.showDeviceAliasPickerDialog(PcView.this, collectComputers(), new Runnable() {
                        @Override
                        public void run() {
                            if (pcGridAdapter != null) {
                                pcGridAdapter.refreshSorting();
                            }
                        }
                    });
                }
            });
        }

        applyCustomBackground();

        keyboardBridgePill = findViewById(R.id.keyboardBridgePill);
        keyboardBridgeDot = findViewById(R.id.keyboardBridgeDot);
        keyboardBridgeText = findViewById(R.id.keyboardBridgeText);
        if (keyboardBridgePill != null) {
            keyboardBridgePill.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    KeyboardBridgeHelper.openAccessibilitySettings(
                            PcView.this,
                            R.string.title_keyboard_bridge_status,
                            R.string.summary_keyboard_bridge_disabled);
                }
            });
            updateKeyboardBridgePill();
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        pairingInProgress = false;
        if (activePairingDialog != null && activePairingDialog.isShowing()) {
            activePairingDialog.dismiss();
            activePairingDialog = null;
        }

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    private void updateKeyboardBridgePill() {
        if (keyboardBridgePill == null || keyboardBridgeDot == null || keyboardBridgeText == null) {
            return;
        }
        boolean enabled = KeyboardBridgeHelper.isKeyboardBridgeEnabled(this);
        keyboardBridgePill.setBackgroundResource(enabled
                ? R.drawable.bg_status_pill_ok
                : R.drawable.bg_status_pill_warn);
        keyboardBridgeDot.setBackgroundResource(enabled
                ? R.drawable.dot_online
                : R.drawable.dot_warn);
        keyboardBridgeText.setText(enabled
                ? R.string.keyboard_bridge_pill_on
                : R.string.keyboard_bridge_pill_off);
    }

    private void applyCustomBackground() {
        BridgeUiPrefs.applyThemeToPcView(this);
    }

    private List<ComputerDetails> collectComputers() {
        List<ComputerDetails> list = new ArrayList<>();
        if (pcGridAdapter != null) {
            for (int i = 0; i < pcGridAdapter.getCount(); i++) {
                ComputerObject obj = (ComputerObject) pcGridAdapter.getItem(i);
                if (obj != null && obj.details != null) {
                    list.add(obj.details);
                }
            }
        }
        return list;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        if (requestCode == REQUEST_PICK_WALLPAPER) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BridgeUiPrefs.saveWallpaperFromUri(PcView.this, uri);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing()) {
                                    applyCustomBackground();
                                    Toast.makeText(PcView.this, R.string.bg_saved_done, Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing()) {
                                    Toast.makeText(PcView.this,
                                            getString(R.string.bg_save_failed, e.getMessage()),
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }
            }).start();
        } else if (requestCode == REQUEST_PICK_PC_COVER && pendingCoverUuid != null) {
            final String targetUuid = pendingCoverUuid;
            pendingCoverUuid = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BridgeUiPrefs.saveCustomCoverFromUri(PcView.this, targetUuid, uri);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing() && pcGridAdapter != null) {
                                    pcGridAdapter.notifyDataSetChanged();
                                    Toast.makeText(PcView.this, R.string.cover_saved_done, Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing()) {
                                    Toast.makeText(PcView.this,
                                            getString(R.string.bg_save_failed, e.getMessage()),
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }
            }).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        applyCustomBackground();
        updateKeyboardBridgePill();
        if (pcGridAdapter != null) {
            pcGridAdapter.notifyDataSetChanged();
        }
        AppUpdateManager.resumePendingInstallIfPermitted(this);

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);
                
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        menu.setHeaderTitle(buildPcMenuTitle(computer));
        List<PcMenuAction> actions = buildPcMenuActions(computer);
        for (int i = 0; i < actions.size(); i++) {
            PcMenuAction action = actions.get(i);
            menu.add(Menu.NONE, action.id, i + 1, action.label);
        }
    }

    private static final class PcMenuAction {
        final int id;
        final String label;

        PcMenuAction(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private String buildPcMenuTitle(ComputerObject computer) {
        String displayName = BridgeUiPrefs.getDisplayName(this, computer.details);
        String headerName = displayName.equals(computer.details.name)
                ? displayName
                : (displayName + " (" + computer.details.name + ")");
        String headerTitle = headerName + " - ";
        switch (computer.details.state) {
            case ONLINE:
                return headerTitle + getResources().getString(R.string.pcview_menu_header_online);
            case OFFLINE:
                return headerTitle + getResources().getString(R.string.pcview_menu_header_offline);
            case UNKNOWN:
            default:
                return headerTitle + getResources().getString(R.string.pcview_menu_header_unknown);
        }
    }

    private List<PcMenuAction> buildPcMenuActions(ComputerObject computer) {
        List<PcMenuAction> actions = new ArrayList<>();
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
                computer.details.state == ComputerDetails.State.UNKNOWN) {
            actions.add(new PcMenuAction(WOL_ID, getResources().getString(R.string.pcview_menu_send_wol)));
            actions.add(new PcMenuAction(GAMESTREAM_EOL_ID, getResources().getString(R.string.pcview_menu_eol)));
        } else if (computer.details.pairState != PairState.PAIRED) {
            actions.add(new PcMenuAction(PAIR_ID, getResources().getString(R.string.pcview_menu_pair_pc)));
            if (computer.details.nvidiaServer) {
                actions.add(new PcMenuAction(GAMESTREAM_EOL_ID, getResources().getString(R.string.pcview_menu_eol)));
            }
        } else {
            if (computer.details.runningGameId != 0) {
                actions.add(new PcMenuAction(RESUME_ID, getResources().getString(R.string.applist_menu_resume)));
                actions.add(new PcMenuAction(QUIT_ID, getResources().getString(R.string.applist_menu_quit)));
            }
            if (computer.details.nvidiaServer) {
                actions.add(new PcMenuAction(GAMESTREAM_EOL_ID, getResources().getString(R.string.pcview_menu_eol)));
            }
            actions.add(new PcMenuAction(FULL_APP_LIST_ID, getResources().getString(R.string.pcview_menu_app_list)));
        }

        actions.add(new PcMenuAction(TEST_NETWORK_ID, getResources().getString(R.string.pcview_menu_test_network)));
        actions.add(new PcMenuAction(RENAME_PC_ID, getResources().getString(R.string.pcview_menu_rename_pc)));
        actions.add(new PcMenuAction(COVER_PC_ID, getResources().getString(R.string.pcview_menu_cover_mode)));
        actions.add(new PcMenuAction(DELETE_ID, getResources().getString(R.string.pcview_menu_delete_pc)));
        if (computer.details.pairState != PairState.PAIRED) {
            actions.add(new PcMenuAction(IGNORE_PC_ID, getResources().getString(R.string.pcview_menu_ignore_pc)));
        }
        actions.add(new PcMenuAction(VIEW_DETAILS_ID, getResources().getString(R.string.pcview_menu_details)));
        return actions;
    }

    private void showPcActionDialog(final ComputerObject computer) {
        if (computer == null || isFinishing()) {
            return;
        }
        stopComputerUpdates(false);
        final List<PcMenuAction> actions = buildPcMenuActions(computer);
        String[] labels = new String[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            labels[i] = actions.get(i).label;
        }
        final boolean[] actionChosen = new boolean[]{false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(buildPcMenuTitle(computer))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        actionChosen[0] = true;
                        executePcMenuAction(actions.get(which).id, computer);
                        if (actions.get(which).id != PAIR_ID) {
                            startComputerUpdates();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (!actionChosen[0] && !pairingInProgress) {
                    startComputerUpdates();
                }
            }
        });
        dialog.show();
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private static boolean isPairingConflictError(HostHttpResponseException e) {
        if (e == null) {
            return false;
        }
        if (e.getErrorCode() == 409) {
            return true;
        }
        String msg = e.getErrorMessage();
        if (msg == null) {
            msg = e.getMessage();
        }
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.US);
        return lower.contains("already exists")
                || lower.contains("in progress")
                || lower.contains("pairing session");
    }

    private static String generateFreshPairingUid() {
        return String.format(Locale.US, "%016x", new SecureRandom().nextLong());
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (pairingInProgress) {
            if (activePairingDialog != null && !activePairingDialog.isShowing() && !isFinishing()) {
                activePairingDialog.show();
            }
            return;
        }
        if (nowMs - lastPairAttemptElapsedMs < 650L) {
            return;
        }
        lastPairAttemptElapsedMs = nowMs;

        pairingInProgress = true;
        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn = null;
                String message = null;
                boolean success = false;
                final AtomicBoolean pairingCancelled = new AtomicBoolean(false);
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    httpConn.setUniqueId(managerBinder.getUniqueId());
                    final NvHTTP activeHttpConn = httpConn;
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        final String pinStr = PairingManager.generatePinString();

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing() || pairingCancelled.get()) {
                                    return;
                                }
                                if (activePairingDialog != null && activePairingDialog.isShowing()) {
                                    activePairingDialog.dismiss();
                                }
                                AlertDialog dlg = new AlertDialog.Builder(PcView.this)
                                        .setTitle(R.string.pair_pairing_title)
                                        .setMessage(getString(R.string.pair_pairing_prompt, pinStr))
                                        .setCancelable(false)
                                        .setNegativeButton(R.string.pair_cancel_button, new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                pairingCancelled.set(true);
                                                pairingInProgress = false;
                                                lastPairAttemptElapsedMs = SystemClock.elapsedRealtime();
                                                activePairingDialog = null;
                                                new Thread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        activeHttpConn.cancelActiveCall();
                                                        try {
                                                            activeHttpConn.unpair();
                                                        } catch (Exception ignored) {
                                                        }
                                                    }
                                                }).start();
                                                startComputerUpdates();
                                            }
                                        })
                                        .create();
                                dlg.setCanceledOnTouchOutside(false);
                                activePairingDialog = dlg;
                                dlg.show();
                            }
                        });

                        PairingManager pm = httpConn.getPairingManager();
                        String serverInfo = httpConn.getServerInfo(true);
                        PairState pairState = PairState.FAILED;

                        for (int attempt = 0; attempt < 3 && !pairingCancelled.get(); attempt++) {
                            boolean needRotateAndRetry = false;
                            try {
                                pairState = pm.pair(serverInfo, pinStr);
                                if (pairState == PairState.ALREADY_IN_PROGRESS) {
                                    needRotateAndRetry = true;
                                }
                            } catch (HostHttpResponseException e) {
                                if (!pairingCancelled.get() && isPairingConflictError(e) && attempt < 2) {
                                    needRotateAndRetry = true;
                                } else {
                                    throw e;
                                }
                            }

                            if (!needRotateAndRetry || attempt >= 2 || pairingCancelled.get()) {
                                break;
                            }

                            try {
                                httpConn.unpair();
                            } catch (Exception ignored) {
                            }
                            try {
                                Thread.sleep(250);
                            } catch (InterruptedException ignored) {
                            }
                            httpConn.setUniqueId(generateFreshPairingUid());
                        }

                        if (pairingCancelled.get()) {
                            return;
                        }

                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    if (!pairingCancelled.get()) {
                        e.printStackTrace();
                        message = e.getMessage();
                    }
                }

                if (pairingCancelled.get()) {
                    return;
                }

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pairingInProgress = false;
                        if (activePairingDialog != null && activePairingDialog.isShowing()) {
                            activePairingDialog.dismiss();
                        }
                        activePairingDialog = null;

                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        if (executePcMenuAction(item.getItemId(), computer)) {
            return true;
        }
        return super.onContextItemSelected(item);
    }

    private boolean executePcMenuAction(int actionId, final ComputerObject computer) {
        switch (actionId) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (computer.details.pairState != PairState.PAIRED && computer.details.manualAddress == null) {
                            BridgeUiPrefs.ignoreComputer(PcView.this, computer.details.uuid);
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case IGNORE_PC_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }
                BridgeUiPrefs.ignoreComputer(PcView.this, computer.details.uuid);
                removeComputer(computer.details);
                Toast.makeText(PcView.this, R.string.pcview_ignored_toast, Toast.LENGTH_LONG).show();
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false, false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            case RENAME_PC_ID:
                BridgeUiPrefs.showRenameDialog(PcView.this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (pcGridAdapter != null) {
                            pcGridAdapter.refreshSorting();
                        }
                    }
                });
                return true;

            case COVER_PC_ID:
                BridgeUiPrefs.showCoverModeDialog(PcView.this, computer.details, REQUEST_PICK_PC_COVER, new Runnable() {
                    @Override
                    public void run() {
                        if (pcGridAdapter != null) {
                            pcGridAdapter.notifyDataSetChanged();
                        }
                    }
                }, new BridgeUiPrefs.CoverPickRequester() {
                    @Override
                    public void onRequestPickCover(String uuid) {
                        pendingCoverUuid = uuid;
                    }
                });
                return true;

            default:
                return false;
        }
    }
    
    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();
        BridgeUiPrefs.clearAlias(this, details.uuid);
        BridgeUiPrefs.clearPcCoverData(this, details.uuid);

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Open the action menu if a PC is offline or refreshing
                    showPcActionDialog(computer);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    doAppList(computer.details, false, false);
                }
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
                showPcActionDialog(computer);
                return true;
            }
        });
        UiHelper.applyStatusBarPadding(listView);
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}
