/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.policy;

import com.android.internal.app.AlertController;
import com.android.internal.app.AlertController.AlertParams;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.R;
import com.android.internal.util.ThemeUtils;
import com.android.internal.widget.LockPatternUtils;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.INotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.PorterDuff.Mode;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraAccessException;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.gesture.IEdgeGestureService;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicy.WindowManagerFuncs;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.ListView;
import android.widget.TextView;

import com.android.internal.util.thewiz.PowerMenuColorHelper;
import com.android.internal.util.thewiz.PowerMenuConstants;
import com.android.internal.util.thewiz.PowerMenuHelper;
import com.android.internal.util.thewiz.ActionConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that
 * may show depending on whether the keyguard is showing, and whether the device
 * is provisioned.
 */
class GlobalActions implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener  {

    private static final String TAG = "GlobalActions";

    public static final String ACTION_TOGGLE_GLOBAL_ACTIONS = "android.intent.action.TOGGLE_GLOBAL_ACTIONS";

    private final Context mContext;
    private final WindowManagerFuncs mWindowManagerFuncs;
    private Context mUiContext;
    private final AudioManager mAudioManager;
    private final IDreamManager mDreamManager;
    private IEdgeGestureService mEdgeGestureService;
    private Object mServiceAquireLock = new Object();

    private ArrayList<Action> mItems;
    private GlobalActionsDialog mDialog;

    private Action mSilentModeAction;
    private ToggleAction mAirplaneModeOn;
    private ToggleAction mExpandedDesktopModeOn;
    private ToggleAction mPieModeOn;
    private boolean mTorchEnabled = false;

    private MyAdapter mAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;
    private ToggleAction.State mAirplaneState = ToggleAction.State.Off;
    private ToggleAction.State mExpandedDesktopState = ToggleAction.State.Off;
    private ToggleAction.State mPieState = ToggleAction.State.Off;
    private boolean mIsWaitingForEcmExit = false;
    private boolean mHasTelephony;
    private boolean mHasVibrator;
    private final boolean mShowSilentToggle;

    private static int mIconNormalColor;
    private static int mIconEnabledSelectedColor;
    private static int mRippleColor;
    private static int mTextColor;
    private static int mSecondaryTextColor;

    /**
     * @param context everything needs a context :(
     */
    public GlobalActions(Context context, WindowManagerFuncs windowManagerFuncs) {
        mContext = context;
        mWindowManagerFuncs = windowManagerFuncs;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        filter.addAction(ACTION_TOGGLE_GLOBAL_ACTIONS);
        context.registerReceiver(mBroadcastReceiver, filter);

        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasTelephony = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        // get notified of phone state changes
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver);
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = vibrator != null && vibrator.hasVibrator();

        // TODO check zen mode?
        mShowSilentToggle = !mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useFixedVolume);
    }

    /**
     * Show the global actions dialog (creating if necessary)
     * @param keyguardShowing True if keyguard is showing
     */
    public void showDialog(boolean keyguardShowing, boolean isDeviceProvisioned) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
            updateColors();
            mDialog = createDialog();
            // Show delayed, so that the dismiss of the previous dialog completes
            mHandler.sendEmptyMessage(MESSAGE_SHOW);
        } else {
            updateColors();
            mDialog = createDialog();
            handleShow();
        }
    }

    private void awakenIfNecessary() {
        if (mDreamManager != null) {
            try {
                if (mDreamManager.isDreaming()) {
                    mDreamManager.awaken();
                }
            } catch (RemoteException e) {
                // we tried
            }
        }
    }

    private void updateColors() {
        mIconNormalColor = PowerMenuColorHelper.getIconNormalColor(mContext);
        mIconEnabledSelectedColor = PowerMenuColorHelper.getIconEnabledSelectedColor(mContext);
        mRippleColor = PowerMenuColorHelper.getRippleColor(mContext);
        mTextColor = PowerMenuColorHelper.getTextColor(mContext);
        mSecondaryTextColor = (179 << 24) | (mTextColor & 0x00ffffff);
    }

    private static void setButtonRippleColor(Context context, View v) {
        RippleDrawable rd = (RippleDrawable) context.getDrawable(
                R.drawable.global_actions_button_bg);

        int states[][] = new int[][] {
            new int[] {
                com.android.internal.R.attr.state_enabled
            }
        };
        int colors[] = new int[] {
            mRippleColor
        };
        ColorStateList color = new ColorStateList(states, colors);

        rd.setColor(color);
        v.setBackground(rd);
    }

    private static void setSilentModeItemRippleColor(Context context, View v) {
        RippleDrawable rd = (RippleDrawable) context.getDrawable(
                R.drawable.global_actions_silent_mode_item_bg);

        int states[][] = new int[][] {
            new int[] {
                com.android.internal.R.attr.state_enabled
            }
        };
        int colors[] = new int[] {
            mRippleColor
        };
        ColorStateList color = new ColorStateList(states, colors);

        rd.setColor(color);
        v.setBackground(rd);
    }

    private void handleShow() {
        awakenIfNecessary();
        prepareDialog();

        WindowManager.LayoutParams attrs = mDialog.getWindow().getAttributes();
        attrs.setTitle("GlobalActions");

        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int powermenuAnimations = isPrimary ? getPowermenuAnimations() : 0;

        if (powermenuAnimations == 0) {
         // default AOSP action
        }
        if (powermenuAnimations == 1) {
            attrs.windowAnimations = R.style.PowerMenuBottomAnimation;
            attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 2) {
            attrs.windowAnimations = R.style.PowerMenuLeftAnimation;
            attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 3) {
            attrs.windowAnimations = R.style.PowerMenuRightAnimation;
            attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 4) {
                attrs.windowAnimations = R.style.PowerMenuRotateAnimation;
                attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 5) {
            attrs.windowAnimations = R.style.PowerMenuTopAnimation;
            attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 6) {
                attrs.windowAnimations = R.style.PowerMenuXylonAnimation;
                attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 7) {
                attrs.windowAnimations = R.style.PowerMenuTranslucentAnimation;
                attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 8) {
                attrs.windowAnimations = R.style.PowerMenuTnAnimation;
                attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 9) {
                attrs.windowAnimations = R.style.PowerMenuflyAnimation;
                attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 10) {
                attrs.windowAnimations = R.style.PowerMenuCardAnimation;
                attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 11) {
                attrs.windowAnimations = R.style.PowerMenuTranslucentAnimation;
                attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }
        if (powermenuAnimations == 12) {
                attrs.windowAnimations = R.style.PowerMenuTranslucentAnimation;
                attrs.gravity = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
        }

        mDialog.getWindow().setAttributes(attrs);
        mDialog.show();
        mDialog.getWindow().getDecorView().setSystemUiVisibility(View.STATUS_BAR_DISABLE_EXPAND);
    }

    private int getPowermenuAnimations() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.POWER_MENU_ANIMATIONS, 0);
    }

    private Context getUiContext() {
        mUiContext = ThemeUtils.createUiContext(mContext);
        mUiContext.setTheme(android.R.style.Theme_DeviceDefault_Light_DarkActionBar);
        return mUiContext != null ? mUiContext : mContext;
    }

    /**
     * Create the global actions dialog.
     * @return A new dialog.
     */
    private GlobalActionsDialog createDialog() {
        mSilentModeAction = new SilentModeTriStateAction(mContext, mAudioManager, mHandler);
        ArrayList<ActionConfig> powerMenuConfig =
                PowerMenuHelper.getPowerMenuConfig(mContext);

        mItems = new ArrayList<Action>();

        for (final ActionConfig config : powerMenuConfig) {
            if (config.getClickAction().equals(PowerMenuConstants.ACTION_POWER_OFF)) {
                mItems.add(getPowerAction());
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_REBOOT)) {
                mItems.add(new RebootAction());
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_AIRPLANE)) {
                constructAirPlaneModeToggle();
                mItems.add(mAirplaneModeOn);
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_USERS)) {
                addUsersToMenu(mItems);
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_SYSTEM_SETTINGS)) {
                mItems.add(getSettingsAction());
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_LOCK_DOWN)) {
                mItems.add(getLockdownAction());
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_EXPANDED_DESKTOP)) {
                constructExpandedDesktopModeToggle();
                mItems.add(mExpandedDesktopModeOn);
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_PIE)) {
                constructPieToggle();
                mItems.add(mPieModeOn);
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_SCREENSHOT)) {
                mItems.add(getScreenshotAction());
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_TORCH)) {
                mItems.add(getTorchToggleAction());
            } else if (config.getClickAction().equals(PowerMenuConstants.ACTION_SOUND) && mShowSilentToggle) {
                if (!mHasVibrator) {
                    mSilentModeAction = new SilentModeToggleAction();
                } else {
                    mSilentModeAction = new SilentModeTriStateAction(mContext, mAudioManager, mHandler);
                }
                mItems.add(mSilentModeAction);
            }
        }

        mAdapter = new MyAdapter();

        AlertParams params = new AlertParams(getUiContext());
        params.mAdapter = mAdapter;
        params.mOnClickListener = this;
        params.mForceInverseBackground = true;

        GlobalActionsDialog dialog = new GlobalActionsDialog(getUiContext(), params);
        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.

        dialog.getListView().setItemsCanFocus(true);
        dialog.getListView().setLongClickable(true);
        dialog.getListView().setOnItemLongClickListener(
                new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent, View view, int position,
                            long id) {
                        final Action action = mAdapter.getItem(position);
                        if (action instanceof LongPressAction) {
                            return ((LongPressAction) action).onLongPress();
                        }
                        return false;
                    }
        });
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);

        dialog.setOnDismissListener(this);

        return dialog;
    }

    private Action getPowerAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_lock_power_off,
                R.string.global_action_power_off) {

            @Override
            public void onPress() {
            // shutdown by making sure radio and power are handled accordingly.
            mWindowManagerFuncs.shutdown(false /* confirm */);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private final class RebootAction extends SinglePressAction implements LongPressAction {
        private RebootAction() {
            super(com.android.internal.R.drawable.ic_lock_reboot,
                R.string.global_action_reboot);
        }

        @Override
        public void onPress() {
            mWindowManagerFuncs.reboot();
        }

        @Override
        public boolean onLongPress() {
            UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            if (!um.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) {
                mWindowManagerFuncs.rebootSafeMode(true);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }

    private void constructAirPlaneModeToggle() {
        mAirplaneModeOn = new ToggleAction(
                R.drawable.ic_lock_airplane_mode,
                R.drawable.ic_lock_airplane_mode_off,
                R.string.global_actions_toggle_airplane_mode,
                R.string.global_actions_airplane_mode_on_status,
                R.string.global_actions_airplane_mode_off_status) {

            void onToggle(boolean on) {
                if (mHasTelephony && Boolean.parseBoolean(
                        SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {
                    mIsWaitingForEcmExit = true;
                    // Launch ECM exit dialog
                    Intent ecmDialogIntent =
                            new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null);
                    ecmDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(ecmDialogIntent);
                } else {
                    changeAirplaneModeSystemSetting(on);
                }
            }

            @Override
            protected void changeStateFromPress(boolean buttonOn) {
                if (!mHasTelephony) return;

                // In ECM mode airplane state cannot be changed
                if (!(Boolean.parseBoolean(
                        SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE)))) {
                    mState = buttonOn ? State.TurningOn : State.TurningOff;
                    mAirplaneState = mState;
                }
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
        onAirplaneModeChanged();
    }

    private class BugReportAction extends SinglePressAction implements LongPressAction {

        public BugReportAction() {
            super(com.android.internal.R.drawable.ic_lock_bugreport, R.string.bugreport_title);
        }

        @Override
        public void onPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return;
            }
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Take an "interactive" bugreport.
                        MetricsLogger.action(mContext,
                                MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_INTERACTIVE);
                        ActivityManagerNative.getDefault().requestBugReport(
                                ActivityManager.BUGREPORT_OPTION_INTERACTIVE);
                    } catch (RemoteException e) {
                    }
                }
            }, 500);
        }

        @Override
        public boolean onLongPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return false;
            }
            try {
                // Take a "full" bugreport.
                MetricsLogger.action(mContext, MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_FULL);
                ActivityManagerNative.getDefault().requestBugReport(
                        ActivityManager.BUGREPORT_OPTION_FULL);
            } catch (RemoteException e) {
            }
            return false;
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public String getStatus() {
            return mContext.getString(
                    com.android.internal.R.string.bugreport_status,
                    Build.VERSION.RELEASE,
                    Build.ID);
        }
    }

    private Action getSettingsAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_settings,
                R.string.global_action_settings) {

            @Override
            public void onPress() {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getAssistAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_action_assist_focused,
                R.string.global_action_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getVoiceAssistAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_voice_search,
                R.string.global_action_voice_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_VOICE_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getLockdownAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_lock_lock,
                R.string.global_action_lockdown) {

            @Override
            public void onPress() {
                new LockPatternUtils(mContext).requireCredentialEntry(UserHandle.USER_ALL);
                try {
                    WindowManagerGlobal.getWindowManagerService().lockNow(null);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error while trying to lock device.", e);
                }
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return false;
            }
        };
    }

    private void constructExpandedDesktopModeToggle() {
        mExpandedDesktopModeOn = new ToggleAction(
                R.drawable.ic_lock_expanded_desktop,
                R.drawable.ic_lock_expanded_desktop,
                R.string.global_actions_toggle_expanded_desktop_mode,
                R.string.global_actions_expanded_desktop_mode_on_status,
                R.string.global_actions_expanded_desktop_mode_off_status) {

            void onToggle(boolean on) {
                com.android.internal.util.thewiz.Action.processAction(
                    mContext, PowerMenuConstants.ACTION_EXPANDED_DESKTOP, false);
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
        onExpandedDesktopModeChanged();
    }

    private void constructPieToggle() {
        mPieModeOn = new ToggleAction(
                R.drawable.ic_lock_pie,
                R.drawable.ic_lock_pie,
                R.string.global_actions_toggle_pie_mode,
                R.string.global_actions_pie_mode_on_status,
                R.string.global_actions_pie_mode_off_status) {

            void onToggle(boolean on) {
                com.android.internal.util.thewiz.Action.processAction(
                    mContext, PowerMenuConstants.ACTION_PIE, false);
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
        onPieModeChanged();
    }

    private Action getScreenshotAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_lock_screenshot,
                R.string.global_action_screenshot) {

            @Override
            public void onPress() {
                com.android.internal.util.thewiz.Action.processAction(
                        mContext, PowerMenuConstants.ACTION_SCREENSHOT, false);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getTorchToggleAction() {
        return new SinglePressAction(com.android.internal.R.drawable.ic_lock_torch,
                R.string.global_action_torch) {

            public void onPress() {
                try {
                    CameraManager cameraManager = (CameraManager)
                            mContext.getSystemService(Context.CAMERA_SERVICE);
                    for (final String cameraId : cameraManager.getCameraIdList()) {
                        CameraCharacteristics characteristics =
                            cameraManager.getCameraCharacteristics(cameraId);
                        int orient = characteristics.get(CameraCharacteristics.LENS_FACING);
                        if (orient == CameraCharacteristics.LENS_FACING_BACK) {
                            cameraManager.setTorchMode(cameraId, !mTorchEnabled);
                            mTorchEnabled = !mTorchEnabled;
                        }
                    }
                } catch (CameraAccessException e) {
                }
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };
    }

    private UserInfo getCurrentUser() {
        try {
            return ActivityManagerNative.getDefault().getCurrentUser();
        } catch (RemoteException re) {
            return null;
        }
    }

    private boolean isCurrentUserOwner() {
        UserInfo currentUser = getCurrentUser();
        return currentUser == null || currentUser.isPrimary();
    }

    private void addUsersToMenu(ArrayList<Action> items) {
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um.isUserSwitcherEnabled()) {
            List<UserInfo> users = um.getUsers();
            UserInfo currentUser = getCurrentUser();
            for (final UserInfo user : users) {
                if (user.supportsSwitchToByUser()) {
                    boolean isCurrentUser = currentUser == null
                            ? user.id == 0 : (currentUser.id == user.id);
                    Drawable icon = user.iconPath != null ? Drawable.createFromPath(user.iconPath)
                            : null;
                    SinglePressAction switchToUser = new SinglePressAction(
                            com.android.internal.R.drawable.ic_menu_cc, icon,
                            (user.name != null ? user.name : "Primary")
                            + (isCurrentUser ? " \u2714" : "")) {
                        public void onPress() {
                            try {
                                ActivityManagerNative.getDefault().switchUser(user.id);
                            } catch (RemoteException re) {
                                Log.e(TAG, "Couldn't switch user " + re);
                            }
                        }

                        public boolean showDuringKeyguard() {
                            return true;
                        }

                        public boolean showBeforeProvisioning() {
                            return false;
                        }
                    };
                    items.add(switchToUser);
                }
            }
        }
    }

    private void prepareDialog() {
        if (mSilentModeAction != null) {
            if (mShowSilentToggle) {
                IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
                mContext.registerReceiver(mRingerModeReceiver, filter);
            }
        }

        // Global menu is showing. Notify EdgeGestureService.
        IEdgeGestureService edgeGestureService = getEdgeGestureService();
        try {
            if (edgeGestureService != null) {
                edgeGestureService.setOverwriteImeIsActive(true);
            }
        } catch (RemoteException e) {
             mEdgeGestureService = null;
        }

        if (mAirplaneModeOn != null) {
            mAirplaneModeOn.updateState(mAirplaneState);
        }
        if (mExpandedDesktopModeOn != null) {
            mExpandedDesktopModeOn.updateState(mExpandedDesktopState);
        }
        if (mPieModeOn != null) {
            mPieModeOn.updateState(mPieState);
        }

        // Start observing setting changes during
        // dialog shows up
        mSettingsObserver.observe();

        mAdapter.notifyDataSetChanged();
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        mDialog.setTitle(R.string.global_actions);
    }

    /** {@inheritDoc} */
    public void onDismiss(DialogInterface dialog) {
        if (mShowSilentToggle) {
            try {
                mContext.unregisterReceiver(mRingerModeReceiver);
            } catch (IllegalArgumentException ie) {
                // ignore this
                Log.w(TAG, ie);
            }
        }
        // Global menu dismiss. Notify EdgeGestureService.
        IEdgeGestureService edgeGestureService = getEdgeGestureService();
        try {
            if (edgeGestureService != null) {
                edgeGestureService.setOverwriteImeIsActive(false);
            }
        } catch (RemoteException e) {
             mEdgeGestureService = null;
        }
    }

    /** {@inheritDoc} */
    public void onClick(DialogInterface dialog, int which) {
        mAdapter.getItem(which).onPress();
    }

    /**
     * The adapter used for the list within the global actions dialog, taking
     * into account whether the keyguard is showing via
     * {@link GlobalActions#mKeyguardShowing} and whether the device is provisioned
     * via {@link GlobalActions#mDeviceProvisioned}.
     */
    private class MyAdapter extends BaseAdapter {

        public int getCount() {
            int count = 0;

            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);

                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                count++;
            }
            return count;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position).isEnabled();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        public Action getItem(int position) {

            int filteredPos = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);
                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position
                    + " out of range of showable actions"
                    + ", filtered count=" + getCount()
                    + ", keyguardshowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }


        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            final Context context = getUiContext();
            return action.create(context, convertView, parent, LayoutInflater.from(context));
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    private interface Action {
        /**
         * @return Text that will be announced when dialog is created.  null
         *     for none.
         */
        CharSequence getLabelForAccessibility(Context context);

        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

        void onPress();

        /**
         * @return whether this action should appear in the dialog when the keygaurd
         *    is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog before the
         *   device is provisioned.
         */
        boolean showBeforeProvisioning();

        boolean isEnabled();
    }

    /**
     * An action that also supports long press.
     */
    private interface LongPressAction extends Action {
        boolean onLongPress();
    }

    /**
     * A single press action maintains no state, just responds to a press
     * and takes an action.
     */
    private static abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final Drawable mIcon;
        private final int mMessageResId;
        private final CharSequence mMessage;

        protected SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
            mMessage = null;
            mIcon = null;
        }

        protected SinglePressAction(int iconResId, Drawable icon, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = icon;
        }

        public boolean isEnabled() {
            return true;
        }

        public String getStatus() {
            return null;
        }

        abstract public void onPress();

        public CharSequence getLabelForAccessibility(Context context) {
            if (mMessage != null) {
                return mMessage;
            } else {
                return context.getString(mMessageResId);
            }
        }

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);

            TextView statusView = (TextView) v.findViewById(R.id.status);
            final String status = getStatus();

            setButtonRippleColor(context, v);

            if (!TextUtils.isEmpty(status)) {
                statusView.setText(status);
                statusView.setTextColor(mSecondaryTextColor);
            } else {
                statusView.setVisibility(View.GONE);
            }
            if (mIcon != null) {
                icon.setImageDrawable(mIcon);
                icon.setColorFilter(mIconNormalColor, Mode.MULTIPLY);
                icon.setScaleType(ScaleType.CENTER_CROP);
            } else if (mIconResId != 0) {
                icon.setImageDrawable(context.getDrawable(mIconResId));
                icon.setColorFilter(mIconNormalColor, Mode.MULTIPLY);
            }
            if (mMessage != null) {
                messageView.setText(mMessage);
                messageView.setTextColor(mTextColor);
            } else {
                messageView.setText(mMessageResId);
                messageView.setTextColor(mTextColor);
            }

            return v;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon
     * and status message accordingly.
     */
    private static abstract class ToggleAction implements Action {

        enum State {
            Off(false),
            TurningOn(true),
            TurningOff(true),
            On(false);

            private final boolean inTransition;

            State(boolean intermediate) {
                inTransition = intermediate;
            }

            public boolean inTransition() {
                return inTransition;
            }
        }

        protected State mState = State.Off;

        // prefs
        protected int mEnabledIconResId;
        protected int mDisabledIconResid;
        protected int mMessageResId;
        protected int mEnabledStatusMessageResId;
        protected int mDisabledStatusMessageResId;

        /**
         * @param enabledIconResId The icon for when this action is on.
         * @param disabledIconResid The icon for when this action is off.
         * @param essage The general information message, e.g 'Silent Mode'
         * @param enabledStatusMessageResId The on status message, e.g 'sound disabled'
         * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
         */
        public ToggleAction(int enabledIconResId,
                int disabledIconResid,
                int message,
                int enabledStatusMessageResId,
                int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = message;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        /**
         * Override to make changes to resource IDs just before creating the
         * View.
         */
        void willCreate() {

        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return context.getString(mMessageResId);
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(R
                            .layout.global_actions_item, parent, false);

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            TextView statusView = (TextView) v.findViewById(R.id.status);
            final boolean enabled = isEnabled();

            setButtonRippleColor(context, v);

            if (messageView != null) {
                messageView.setText(mMessageResId);
                messageView.setTextColor(mTextColor);
                messageView.setEnabled(enabled);
            }

            boolean on = ((mState == State.On) || (mState == State.TurningOn));
            if (icon != null) {
                icon.setImageDrawable(context.getDrawable(
                        (on ? mEnabledIconResId : mDisabledIconResid)));
                icon.setColorFilter((on ? mIconEnabledSelectedColor : mIconNormalColor), Mode.MULTIPLY);
                icon.setEnabled(enabled);
            }

            if (statusView != null) {
                statusView.setText(on ? mEnabledStatusMessageResId : mDisabledStatusMessageResId);
                statusView.setTextColor(mSecondaryTextColor);
                statusView.setVisibility(View.VISIBLE);
                statusView.setEnabled(enabled);
            }
            v.setEnabled(enabled);

            return v;
        }

        public final void onPress() {
            if (mState.inTransition()) {
                Log.w(TAG, "shouldn't be able to toggle when in transition");
                return;
            }

            final boolean nowOn = !(mState == State.On);
            onToggle(nowOn);
            changeStateFromPress(nowOn);
        }

        public boolean isEnabled() {
            return !mState.inTransition();
        }

        /**
         * Implementations may override this if their state can be in on of the intermediate
         * states until some notification is received (e.g airplane mode is 'turning off' until
         * we know the wireless connections are back online
         * @param buttonOn Whether the button was turned on or off
         */
        protected void changeStateFromPress(boolean buttonOn) {
            mState = buttonOn ? State.On : State.Off;
        }

        abstract void onToggle(boolean on);

        public void updateState(State state) {
            mState = state;
        }
    }

    private final class SilentModeToggleAction extends ToggleAction {
        public SilentModeToggleAction() {
            super(R.drawable.ic_audio_vol_mute,
                    R.drawable.ic_audio_vol,
                    R.string.global_action_toggle_silent_mode,
                    R.string.global_action_silent_mode_on_status,
                    R.string.global_action_silent_mode_off_status);
        }

        void onToggle(boolean on) {
            if (on) {
                mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
            }
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private final class SilentModeTriStateAction implements Action, View.OnClickListener {

        private final int[] ITEM_IDS = { R.id.option1, R.id.option2, R.id.option3, R.id.option4, R.id.option5 };
        private final int[] IMAGE_VIEW_IDS = { R.id.option1_icon, R.id.option2_icon, R.id.option3_icon, R.id.option4_icon, R.id.option5_icon };

        private final AudioManager mAudioManager;
        private final Handler mHandler;
        private final Context mContext;

        SilentModeTriStateAction(Context context, AudioManager audioManager, Handler handler) {
            mAudioManager = audioManager;
            mHandler = handler;
            mContext = context;
        }

        private int indexToRingerMode(int index) {
            if (index == 2) {
                return AudioManager.RINGER_MODE_SILENT;
            }
            if (index == 3) {
                if (mHasVibrator) {
                    return AudioManager.RINGER_MODE_VIBRATE;
                } else {
                    return AudioManager.RINGER_MODE_NORMAL;
                }
            }
            return AudioManager.RINGER_MODE_NORMAL;
        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return null;
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_silent_mode, parent, false);

            int ringerMode = mAudioManager.getRingerModeInternal();
            int zenMode = Global.getInt(mContext.getContentResolver(), Global.ZEN_MODE, Global.ZEN_MODE_OFF);
            int selectedIndex = 0;
            if (zenMode != Global.ZEN_MODE_OFF) {
                if (zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                    selectedIndex = 0;
                } else if (zenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
                    selectedIndex = 1;
                }
            } else if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                selectedIndex = 2;
            } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                selectedIndex = 3;
            } else if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                selectedIndex = 4;
            }

            for (int i = 0; i < ITEM_IDS.length; i++) {
                View itemView = v.findViewById(ITEM_IDS[i]);
                View iv = v.findViewById(IMAGE_VIEW_IDS[i]);
                // TODO dont hardcode 3 here
                if (!mHasVibrator && i == 3) {
                    itemView.setVisibility(View.GONE);
                    continue;
                }
                iv.setSelected(selectedIndex == i);
                if (selectedIndex == i) {
                    ((ImageView)iv).setColorFilter(mIconEnabledSelectedColor, Mode.MULTIPLY);
                } else {
                    ((ImageView)iv).setColorFilter(mIconNormalColor, Mode.MULTIPLY);
                }
                // Set up click handler
                itemView.setTag(i);
                setSilentModeItemRippleColor(context, itemView);
                itemView.setOnClickListener(this);
            }
            return v;
        }

        public void onPress() {
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }

        public boolean isEnabled() {
            return true;
        }

        void willCreate() {
        }

        public void onClick(View v) {
            if (!(v.getTag() instanceof Integer)) return;

            int index = (Integer) v.getTag();
            if (index == 0 || index == 1) {
                int zenMode = index == 0
                        ? Global.ZEN_MODE_NO_INTERRUPTIONS
                        : Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                INotificationManager noMan = INotificationManager.Stub.asInterface(
                        ServiceManager.getService(Context.NOTIFICATION_SERVICE));
                try {
                    noMan.setZenMode(zenMode, null, TAG);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to set zen mode", e);
                }
            } else {
                Global.putInt(mContext.getContentResolver(), Global.ZEN_MODE, Global.ZEN_MODE_OFF);
            }
            // must be after zen mode!
            if (index == 2 || index == 3 || index == 4) {
                int ringerMode = indexToRingerMode(index);
                mAudioManager.setRingerModeInternal(ringerMode);
            }
            mAdapter.notifyDataSetChanged();
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISMISS, DIALOG_DISMISS_DELAY);
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY);
                if (!PhoneWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                }
            } else if (TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED.equals(action)) {
                // Airplane mode can be changed after ECM exits if airplane toggle button
                // is pressed during ECM mode
                if (!(intent.getBooleanExtra("PHONE_IN_ECM_STATE", false)) &&
                        mIsWaitingForEcmExit) {
                    mIsWaitingForEcmExit = false;
                    changeAirplaneModeSystemSetting(true);
                }
            }
        }
    };

    private SettingsObserver mSettingsObserver = new SettingsObserver(new Handler());
    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_CONTROLS), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri.equals(Settings.System.getUriFor(
                    Settings.System.PIE_CONTROLS))) {
                onPieModeChanged();
            }
            mAdapter.notifyDataSetChanged();
        }
    }

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (!mHasTelephony || mAirplaneModeOn == null || mAdapter == null) return;
            final boolean inAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            mAirplaneState = inAirplaneMode ? ToggleAction.State.On : ToggleAction.State.Off;
            if (mAirplaneModeOn != null) {
                mAirplaneModeOn.updateState(mAirplaneState);
            }
            mAdapter.notifyDataSetChanged();
        }
    };

    private BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mHandler.sendEmptyMessage(MESSAGE_REFRESH);
            }
        }
    };

    private ContentObserver mAirplaneModeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            onAirplaneModeChanged();
        }
    };

    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_REFRESH = 1;
    private static final int MESSAGE_SHOW = 2;
    private static final int DIALOG_DISMISS_DELAY = 300; // ms

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_DISMISS:
                if (mDialog != null) {
                    mDialog.dismiss();
                    mDialog = null;
                }
                break;
            case MESSAGE_REFRESH:
                mAdapter.notifyDataSetChanged();
                break;
            case MESSAGE_SHOW:
                handleShow();
                break;
            }
        }
    };

    private void onAirplaneModeChanged() {
        // Let the service state callbacks handle the state.
        if (mHasTelephony) return;

        boolean airplaneModeOn = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                0) == 1;
        mAirplaneState = airplaneModeOn ? ToggleAction.State.On : ToggleAction.State.Off;
        mAirplaneModeOn.updateState(mAirplaneState);
    }

    private void onExpandedDesktopModeChanged() {
        ContentResolver cr = mContext.getContentResolver();
        String value = Settings.Global.getString(cr, Settings.Global.POLICY_CONTROL);
        boolean expandedDesktopModeOn = "immersive.full=*".equals(value);
        mExpandedDesktopState = expandedDesktopModeOn ? ToggleAction.State.On : ToggleAction.State.Off;
        if (mExpandedDesktopModeOn != null) {
            mExpandedDesktopModeOn.updateState(mExpandedDesktopState);
        }
    }

    private void onPieModeChanged() {
        boolean pieModeOn = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.PIE_CONTROLS,
                0, UserHandle.USER_CURRENT) == 1;
        mPieState = pieModeOn ? ToggleAction.State.On : ToggleAction.State.Off;
        if (mPieModeOn != null) {
            mPieModeOn.updateState(mPieState);
        }
    }

    /**
     * Change the airplane mode system setting
     */
    private void changeAirplaneModeSystemSetting(boolean on) {
        Settings.Global.putInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                on ? 1 : 0);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        if (!mHasTelephony) {
            mAirplaneState = on ? ToggleAction.State.On : ToggleAction.State.Off;
        }
    }

    private boolean getExpandedDesktopState(ContentResolver cr) {
        String value = Settings.Global.getString(cr, Settings.Global.POLICY_CONTROL);
        if ("immersive.full=*".equals(value)) {
            return true;
        }
        return false;
    }

    /**
     * If not set till now get EdgeGestureService.
     */
    private IEdgeGestureService getEdgeGestureService() {
        synchronized (mServiceAquireLock) {
            if (mEdgeGestureService == null) {
                mEdgeGestureService = IEdgeGestureService.Stub.asInterface(
                            ServiceManager.getService("edgegestureservice"));
            }
            return mEdgeGestureService;
        }
    }

    private static final class GlobalActionsDialog extends Dialog implements DialogInterface {
        private final Context mContext;
        private final int mWindowTouchSlop;
        private final AlertController mAlert;
        private final MyAdapter mAdapter;

        private EnableAccessibilityController mEnableAccessibilityController;

        private boolean mIntercepted;
        private boolean mCancelOnUp;

        public GlobalActionsDialog(Context context, AlertParams params) {
            super(context, com.android.internal.R.style.Theme_Material_Dialog_Dark);
            mContext = getContext();
            mAlert = new AlertController(mContext, this, getWindow());
            mAdapter = (MyAdapter) params.mAdapter;
            mWindowTouchSlop = ViewConfiguration.get(context).getScaledWindowTouchSlop();
            params.apply(mAlert);
        }

        private static int getDialogTheme(Context context) {
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(com.android.internal.R.attr.alertDialogTheme,
                    outValue, true);
            return outValue.resourceId;
        }

        @Override
        protected void onStart() {
            // If global accessibility gesture can be performed, we will take care
            // of dismissing the dialog on touch outside. This is because the dialog
            // is dismissed on the first down while the global gesture is a long press
            // with two fingers anywhere on the screen.
            if (EnableAccessibilityController.canEnableAccessibilityViaGesture(mContext)) {
                mEnableAccessibilityController = new EnableAccessibilityController(mContext,
                        new Runnable() {
                    @Override
                    public void run() {
                        dismiss();
                    }
                });
                super.setCanceledOnTouchOutside(false);
            } else {
                mEnableAccessibilityController = null;
                super.setCanceledOnTouchOutside(true);
            }

            super.onStart();
        }

        @Override
        protected void onStop() {
            if (mEnableAccessibilityController != null) {
                mEnableAccessibilityController.onDestroy();
            }
            super.onStop();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (mEnableAccessibilityController != null) {
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    View decor = getWindow().getDecorView();
                    final int eventX = (int) event.getX();
                    final int eventY = (int) event.getY();
                    if (eventX < -mWindowTouchSlop
                            || eventY < -mWindowTouchSlop
                            || eventX >= decor.getWidth() + mWindowTouchSlop
                            || eventY >= decor.getHeight() + mWindowTouchSlop) {
                        mCancelOnUp = true;
                    }
                }
                try {
                    if (!mIntercepted) {
                        mIntercepted = mEnableAccessibilityController.onInterceptTouchEvent(event);
                        if (mIntercepted) {
                            final long now = SystemClock.uptimeMillis();
                            event = MotionEvent.obtain(now, now,
                                    MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
                            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                            mCancelOnUp = true;
                        }
                    } else {
                        return mEnableAccessibilityController.onTouchEvent(event);
                    }
                } finally {
                    if (action == MotionEvent.ACTION_UP) {
                        if (mCancelOnUp) {
                            cancel();
                        }
                        mCancelOnUp = false;
                        mIntercepted = false;
                    }
                }
            }
            return super.dispatchTouchEvent(event);
        }

        public ListView getListView() {
            return mAlert.getListView();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mAlert.installContent();
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                for (int i = 0; i < mAdapter.getCount(); ++i) {
                    CharSequence label =
                            mAdapter.getItem(i).getLabelForAccessibility(getContext());
                    if (label != null) {
                        event.getText().add(label);
                    }
                }
            }
            return super.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (mAlert.onKeyDown(keyCode, event)) {
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (mAlert.onKeyUp(keyCode, event)) {
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }
    }
}
