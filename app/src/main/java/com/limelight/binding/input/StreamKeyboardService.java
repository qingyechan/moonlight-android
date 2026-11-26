package com.limelight.binding.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.limelight.Game;

import java.util.HashSet;
import java.util.Set;

/**
 * Bridges PC system modifiers, combinations, and hijacked special keys to the host PC
 * while a streaming session is active and focused.
 *
 * Beyond Alt and Windows keys, Android/OEM ROMs and IMEs may intercept Ctrl combinations
 * (e.g. Ctrl+Space, Ctrl+Shift+Esc, Ctrl+Alt+Del), Shift+Space, Esc, Function keys (F1-F12),
 * PrintScreen, and lock keys before they reach the stream view. Consuming them here and
 * feeding them directly into {@link Game#handleBridgedKeyEvent(KeyEvent)} keeps physical
 * keyboard behavior consistent with a local Windows PC while leaving non-streaming apps
 * and software keyboards untouched.
 */
public class StreamKeyboardService extends AccessibilityService {

    private static final String TAG = "StreamKeyboard";

    private final Set<Integer> interceptedPressedKeys = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        int flags = info != null ? info.flags : 0;
        Log.i(TAG, "Service connected, flags=0x" + Integer.toHexString(flags));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window state is irrelevant here.
    }

    @Override
    public void onInterrupt() {
        interceptedPressedKeys.clear();
        Log.w(TAG, "Service interrupted, cleared pressed key state");
    }

    @Override
    public void onDestroy() {
        interceptedPressedKeys.clear();
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // Never interfere with on-screen soft keyboards or virtual navigation keys.
        if ((event.getFlags() & (KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_VIRTUAL_HARD_KEY)) != 0) {
            return false;
        }

        // Leave gamepad buttons untouched. Do not use ControllerHandler.isGameControllerDevice()
        // because some keyboard/HID sub-devices report KEYBOARD_TYPE_NON_ALPHABETIC.
        if (KeyEvent.isGamepadButton(event.getKeyCode())) {
            return false;
        }

        final int keyCode = event.getKeyCode();
        final int action = event.getAction();
        final Game game = Game.getActiveInstance();

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) {
                return interceptedPressedKeys.contains(keyCode);
            }

            if (!shouldInterceptDown(keyCode, event)) {
                return false;
            }

            if (game == null || !game.canBridgeKeyEvent(event)) {
                if (isBridgedModifierKey(keyCode) || isSpecialSystemKey(keyCode)) {
                    Log.d(TAG, "Ignored DOWN " + KeyEvent.keyCodeToString(keyCode)
                            + " (game=" + (game != null)
                            + ", ready=" + (game != null && game.isStreamKeyboardReady()) + ")");
                }
                return false;
            }

            if (game.handleBridgedKeyEvent(event)) {
                interceptedPressedKeys.add(keyCode);
                Log.d(TAG, "Forward DOWN " + KeyEvent.keyCodeToString(keyCode)
                        + " meta=0x" + Integer.toHexString(event.getMetaState()));
                return true;
            }
            return false;
        }

        if (action == KeyEvent.ACTION_UP) {
            if (!interceptedPressedKeys.remove(keyCode)) {
                return false;
            }
            if (game != null && game.handleBridgedKeyEvent(event)) {
                Log.d(TAG, "Forward UP " + KeyEvent.keyCodeToString(keyCode));
                return true;
            }
            Log.d(TAG, "Skipped UP " + KeyEvent.keyCodeToString(keyCode) + " (stream disconnected)");
            return false;
        }

        return false;
    }

    private boolean shouldInterceptDown(int keyCode, KeyEvent event) {
        if (isBridgedModifierKey(keyCode) || isSpecialSystemKey(keyCode)) {
            return true;
        }
        if (isComboModifierActive(event)) {
            return true;
        }
        // Some Android IMEs/ROMs hijack Shift+Space before View.onKeyPreIme sees it.
        return event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_SPACE;
    }

    private boolean isComboModifierActive(KeyEvent event) {
        if (event.isMetaPressed() || event.isAltPressed() || event.isCtrlPressed()) {
            return true;
        }
        return interceptedPressedKeys.contains(KeyEvent.KEYCODE_META_LEFT)
                || interceptedPressedKeys.contains(KeyEvent.KEYCODE_META_RIGHT)
                || interceptedPressedKeys.contains(KeyEvent.KEYCODE_ALT_LEFT)
                || interceptedPressedKeys.contains(KeyEvent.KEYCODE_ALT_RIGHT)
                || interceptedPressedKeys.contains(KeyEvent.KEYCODE_CTRL_LEFT)
                || interceptedPressedKeys.contains(KeyEvent.KEYCODE_CTRL_RIGHT);
    }

    private static boolean isBridgedModifierKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private static boolean isSpecialSystemKey(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_F13 && keyCode <= KeyEvent.KEYCODE_F24) {
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_SYSRQ:
            case KeyEvent.KEYCODE_SCREENSHOT:
            case KeyEvent.KEYCODE_PRINT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
            case KeyEvent.KEYCODE_BREAK:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }
}
