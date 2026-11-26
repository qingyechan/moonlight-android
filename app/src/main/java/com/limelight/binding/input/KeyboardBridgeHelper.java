package com.limelight.binding.input;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import com.limelight.utils.Dialog;

import java.util.List;

/**
 * Shared helper to query StreamKeyboardService status and open Android Accessibility settings.
 */
public final class KeyboardBridgeHelper {

    private KeyboardBridgeHelper() {
    }

    public static boolean isKeyboardBridgeEnabled(Context context) {
        if (context == null) {
            return false;
        }
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        String targetName = StreamKeyboardService.class.getName();
        if (am != null) {
            List<AccessibilityServiceInfo> enabledList =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabledList != null) {
                for (AccessibilityServiceInfo info : enabledList) {
                    if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                        String pkg = info.getResolveInfo().serviceInfo.packageName;
                        String cls = info.getResolveInfo().serviceInfo.name;
                        if (context.getPackageName().equals(pkg) && targetName.equals(cls)) {
                            return true;
                        }
                    }
                }
            }
        }

        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) {
            return false;
        }
        ComponentName component = new ComponentName(context, StreamKeyboardService.class);
        String full = component.flattenToString();
        String shortName = component.flattenToShortString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            String item = splitter.next();
            if (full.equalsIgnoreCase(item) || shortName.equalsIgnoreCase(item)) {
                return true;
            }
        }
        return false;
    }

    public static void openAccessibilitySettings(Activity activity, int fallbackTitleRes, int fallbackMessageRes) {
        if (activity == null) {
            return;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Dialog.displayDialog(activity,
                    activity.getResources().getString(fallbackTitleRes),
                    activity.getResources().getString(fallbackMessageRes),
                    false);
        }
    }
}
