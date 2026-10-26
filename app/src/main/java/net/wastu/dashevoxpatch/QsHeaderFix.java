package net.wastu.dashevoxpatch;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class QsHeaderFix implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String SYSTEM_FRAMEWORK = "android";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CHARGER_CLASS_STATE = "dash_evox_charger_class";
    private static final String ROTATION_STATE = "dash_evox_rotation_state";
    private static final long ROTATION_SAVE_GRACE_MS = 35_000L;
    private static volatile android.content.ContentResolver contentResolver;

    private static final long CHARGE_ESTIMATE_STABILIZE_MS = 30_000L;
    private static volatile boolean wiredCharging;
    private static volatile int chargerClass;
    private static volatile int batteryLevel;
    private static volatile long pluggedSinceMs;
    private static volatile float smoothedBatteryCurrentUa;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        if (!startupParam.startsSystemServer) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.SystemServer",
                    null,
                    "run",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                ClassLoader classLoader = param.thisObject.getClass().getClassLoader();
                                hookDisplayBrightnessSmoothing(classLoader);
                                hookContextualSearch(classLoader);
                                runHook("super volume", () -> SuperVolume.hook(classLoader));
                                XposedBridge.log("dash-evox-patch: Installed display brightness smoothing hooks into system_server");
                            } catch (Throwable t) {
                                XposedBridge.log("dash-evox-patch: Failed to install brightness hooks: " + t);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("dash-evox-patch: Failed to hook SystemServer.run: " + t);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (SYSTEM_FRAMEWORK.equals(loadPackageParam.packageName)) {
            hookDisplayBrightnessSmoothing(loadPackageParam.classLoader);
            hookContextualSearch(loadPackageParam.classLoader);
            runHook("super volume", () -> SuperVolume.hook(loadPackageParam.classLoader));
            return;
        }

        if (!SYSTEM_UI.equals(loadPackageParam.packageName)) {
            return;
        }

        XposedHelpers.findAndHookMethod(
                android.app.Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    private boolean installed;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (installed) {
                            return;
                        }
                        installed = true;
                        Context context = (Context) param.args[0];
                        contentResolver = context.getContentResolver();
                        ClassLoader classLoader = context.getClassLoader();
                        runHook("rotation", () -> hookRotationPersistence(context));
                        runHook("quick settings date", () -> hookVariableDate(classLoader));
                        runHook("calendar shortcut", () -> hookCalendarShortcut(classLoader));
                        runHook("header edges", () -> hookHeaderEdges(classLoader));
                        runHook("swipe haptics", () -> hookSwipeHaptics(classLoader));
                        runHook("swipe up hint", () -> hookSwipeUpHint(classLoader));
                        runHook("hypercharge", () -> hookHyperCharge(classLoader));
                        XposedBridge.log("dash-evox-patch: SystemUI hooks installed");
                    }
                }
        );
    }

    private static final String CONTEXTUAL_SEARCH_FEATURE = "android.software.contextualsearch";

    private void hookContextualSearch(ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (CONTEXTUAL_SEARCH_FEATURE.equals(param.args[0])) {
                    param.setResult(true);
                }
            }
        };
        for (String className : new String[] {
                "com.android.server.pm.PackageManagerService",
                "com.android.server.pm.IPackageManagerBase"
        }) {
            try {
                XposedHelpers.findAndHookMethod(
                        className, classLoader, "hasSystemFeature", String.class, int.class, hook);
                XposedBridge.log("dash-evox-patch: Circle to Search feature hook installed on " + className);
            } catch (Throwable t) {
                XposedBridge.log("dash-evox-patch: Circle to Search feature hook failed on "
                        + className + ": " + t);
            }
        }
    }

    private void hookDisplayBrightnessSmoothing(ClassLoader classLoader) {
        try {
            Class<?> abcClass = XposedHelpers.findClassIfExists(
                    "com.android.server.display.AutomaticBrightnessController",
                    classLoader);
            if (abcClass != null) {
                XposedBridge.hookAllConstructors(abcClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            XposedHelpers.setLongField(param.thisObject, "mBrighteningLightDebounceConfig", 2000L);
                            XposedHelpers.setLongField(param.thisObject, "mDarkeningLightDebounceConfig", 4000L);
                            XposedBridge.log("dash-evox-patch: Patched AutomaticBrightnessController debounce windows (2000ms / 4000ms)");
                        } catch (Throwable t) {
                            XposedBridge.log("dash-evox-patch: Failed to set debounce fields: " + t);
                        }
                    }
                });
            }

            Class<?> dpcClass = XposedHelpers.findClassIfExists(
                    "com.android.server.display.DisplayPowerController",
                    classLoader);
            if (dpcClass != null) {
                XposedHelpers.findAndHookMethod(dpcClass, "loadBrightnessRampRates", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            float slowDecrease = XposedHelpers.getFloatField(param.thisObject, "mBrightnessRampSlowDecrease");
                            float factor = 2000.0f / 3946.0f;
                            float newSlow = slowDecrease * factor;
                            XposedHelpers.setFloatField(param.thisObject, "mBrightnessRampSlowDecrease", newSlow);
                            XposedHelpers.setFloatField(param.thisObject, "mBrightnessRampSlowIncrease", newSlow);
                            XposedHelpers.setFloatField(param.thisObject, "mBrightnessRampSlowDecreaseIdle", newSlow);
                            XposedHelpers.setFloatField(param.thisObject, "mBrightnessRampSlowIncreaseIdle", newSlow);
                            XposedBridge.log("dash-evox-patch: Patched DisplayPowerController slow ramp rate: " + slowDecrease + " -> " + newSlow);
                        } catch (Throwable t) {
                            XposedBridge.log("dash-evox-patch: Failed to patch brightness ramp rates: " + t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("dash-evox-patch: Failed to hook display brightness smoothing: " + t);
        }
        hookBrightnessFloor(classLoader);
    }

    // The panel tints red below roughly this level (about 6 nits on dash). Raising the range
    // minimum moves the slider's bottom end up and clamps auto-brightness to the same floor.
    private static final float BRIGHTNESS_FLOOR = 0.0034f;

    private void hookBrightnessFloor(ClassLoader classLoader) {
        try {
            Class<?> rangeClass = XposedHelpers.findClassIfExists(
                    "com.android.server.display.BrightnessRangeController", classLoader);
            if (rangeClass == null) {
                return;
            }
            XposedBridge.hookAllMethods(rangeClass, "getCurrentBrightnessMin", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof Float && (Float) result < BRIGHTNESS_FLOOR) {
                        param.setResult(BRIGHTNESS_FLOOR);
                    }
                }
            });
            XposedBridge.log("dash-evox-patch: Brightness floor hook installed (" + BRIGHTNESS_FLOOR + ")");
        } catch (Throwable t) {
            XposedBridge.log("dash-evox-patch: Failed to hook brightness floor: " + t);
        }
    }


    private static void hookVariableDate(ClassLoader classLoader) {
        Class<?> controllerClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.VariableDateViewController",
                classLoader
        );

        XposedHelpers.findAndHookMethod(
                controllerClass,
                "changePattern",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object dateView = XposedHelpers.getObjectField(param.thisObject, "mView");
                        String longerPattern = (String) XposedHelpers.getObjectField(dateView, "longerPattern");
                        if (longerPattern != null && !longerPattern.isEmpty()) {
                            param.args[0] = longerPattern;
                        }
                    }
                }
        );

        XposedHelpers.findAndHookMethod(
                controllerClass,
                "onViewAttached",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        android.widget.TextView dateView = (android.widget.TextView)
                                XposedHelpers.getObjectField(param.thisObject, "mView");
                        String longerPattern = (String) XposedHelpers.getObjectField(dateView, "longerPattern");
                        int minimumWidth = Math.round(90 * dateView.getResources().getDisplayMetrics().density);
                        dateView.setMinimumWidth(minimumWidth);
                        dateView.setVisibility(View.VISIBLE);
                        XposedHelpers.callMethod(param.thisObject, "changePattern", longerPattern);
                        dateView.requestLayout();
                    }
                }
        );
    }

    private static void hookCalendarShortcut(ClassLoader classLoader) {
        Class<?> controllerClass = XposedHelpers.findClass(
                "com.android.systemui.shade.ShadeHeaderController",
                classLoader
        );

        XposedHelpers.findAndHookMethod(
                controllerClass,
                "onViewAttached",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        android.widget.TextView date = (android.widget.TextView)
                                XposedHelpers.getObjectField(param.thisObject, "date");
                        date.setClickable(true);
                        date.setOnClickListener(view -> {
                            android.content.Intent intent = android.content.Intent.makeMainSelectorActivity(
                                    android.content.Intent.ACTION_MAIN,
                                    android.content.Intent.CATEGORY_APP_CALENDAR
                            );
                            Object activityStarter = XposedHelpers.getObjectField(param.thisObject, "activityStarter");
                            XposedHelpers.callMethod(
                                    activityStarter,
                                    "postStartActivityDismissingKeyguard",
                                    intent,
                                    0
                            );
                        });
                    }
                }
        );
    }

    private static void hookHyperCharge(ClassLoader classLoader) {
        try {
            Class<?> callbackClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.KeyguardIndicationController$BaseKeyguardCallback",
                    classLoader
            );
            Class<?> batteryStatusClass = XposedHelpers.findClass(
                    "com.android.settingslib.fuelgauge.BatteryStatus",
                    classLoader
            );
            XposedHelpers.findAndHookMethod(
                    callbackClass,
                    "onRefreshBatteryInfo",
                    batteryStatusClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            updateChargingState(param.args[0]);
                        }
                    }
            );

            Class<?> controllerClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.KeyguardIndicationController",
                    classLoader
            );
            XposedHelpers.findAndHookMethod(
                    controllerClass,
                    "computePowerChargingStringIndication",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (wiredCharging) {
                                param.setResult(buildChargingIndication(param.thisObject));
                            }
                        }
                    }
            );
        } catch (Throwable throwable) {
            XposedBridge.log("EvolutionXQsFix: failed to install HyperCharge hooks: " + throwable);
        }
    }

    private static void updateChargingState(Object status) {
        try {
            int plugged = XposedHelpers.getIntField(status, "plugged");
            int statusValue = XposedHelpers.getIntField(status, "status");
            boolean nowWired = (plugged == 1 || plugged == 2)
                    && (statusValue == 2 || statusValue == 5);
            batteryLevel = XposedHelpers.getIntField(status, "level");

            float frameworkMaxWatts = safeFloatField(status, "maxChargingWattage") / 1_000_000f;
            chargerClass = nowWired ? detectChargerClass(frameworkMaxWatts) : 0;

            long now = SystemClock.elapsedRealtime();
            if (nowWired && !wiredCharging) {
                pluggedSinceMs = now;
                smoothedBatteryCurrentUa = 0f;
            } else if (!nowWired) {
                pluggedSinceMs = 0L;
                smoothedBatteryCurrentUa = 0f;
            }
            wiredCharging = nowWired;
        } catch (Throwable throwable) {
            XposedBridge.log("EvolutionXQsFix: charging-state update failed: " + throwable);
        }
    }

    private static int detectChargerClass(float frameworkMaxWatts) {
        if (contentResolver != null) {
            int reportedClass = Settings.Secure.getInt(
                    contentResolver,
                    CHARGER_CLASS_STATE,
                    0
            );
            if (reportedClass == 1 || reportedClass == 2) {
                return reportedClass;
            }
        }
        long quickChargeType = readLong("/sys/class/power_supply/usb/quick_charge_type");
        long apdoMaxWatts = readLong("/sys/class/power_supply/usb/apdo_max");
        long powerMaxWatts = readLong("/sys/class/power_supply/usb/power_max");
        long authenticated = readLong("/sys/class/power_supply/usb/pd_authentication");
        long verificationDone = readLong("/sys/class/power_supply/usb/pd_verify_done");
        String realType = readString("/sys/class/power_supply/usb/real_type");

        boolean verifiedHyperCharge = authenticated == 1
                && verificationDone == 1
                && (apdoMaxWatts >= 90 || powerMaxWatts >= 90);
        if (quickChargeType >= 3 || verifiedHyperCharge) {
            return 2;
        }
        if (quickChargeType >= 1
                || apdoMaxWatts >= 18
                || powerMaxWatts >= 18
                || "USB_PD".equals(realType)
                || frameworkMaxWatts >= 18f) {
            return 1;
        }
        return 0;
    }


    private static String buildChargingIndication(Object controller) {
        int controllerLevel = safeIntField(controller, "mBatteryLevel", batteryLevel);
        int liveChargerClass = detectChargerClass(0f);
        if (liveChargerClass > 0) {
            chargerClass = liveChargerClass;
        }
        float currentUa = Math.abs((float) readLong("/sys/class/power_supply/battery/current_now"));
        if (currentUa < 100_000f || currentUa > 30_000_000f) {
            currentUa = Math.abs(safeFloatField(controller, "mChargingCurrent"));
        }
        if (currentUa >= 100_000f && currentUa <= 30_000_000f) {
            smoothedBatteryCurrentUa = smoothedBatteryCurrentUa == 0f
                    ? currentUa
                    : smoothedBatteryCurrentUa * 0.85f + currentUa * 0.15f;
        }

        float watts = readInputPowerWatts();
        if (watts <= 0f) {
            watts = safeFloatField(controller, "mChargingWattage") / 1_000_000f;
        }
        String status;
        if (chargerClass == 2) {
            status = "HyperCharge";
        } else if (chargerClass == 1) {
            status = "Fast charge";
        } else {
            status = "Charging";
        }
        String power = watts > 0f
                ? String.format(Locale.US, " %.1fW", watts)
                : "";
        return controllerLevel + "% • " + status + power + "\n"
                + estimateChargeTime(controllerLevel);
    }

    private static float readInputPowerWatts() {
        long currentMa = readLong("/sys/class/power_supply/usb/input_current_now");
        if (currentMa <= 0) {
            currentMa = readLong("/sys/class/power_supply/usb/current_now");
        }
        long voltageMv = readLong("/sys/class/power_supply/usb/pmic_vbus");
        if (voltageMv <= 0) {
            voltageMv = readLong("/sys/class/power_supply/usb/voltage_now");
        }
        if (currentMa > 0 && currentMa < 20_000 && voltageMv > 0 && voltageMv < 30_000) {
            return currentMa * voltageMv / 1_000_000f;
        }
        return 0f;
    }

    private static String estimateChargeTime(int level) {
        long elapsed = SystemClock.elapsedRealtime() - pluggedSinceMs;
        if (pluggedSinceMs == 0L
                || elapsed < CHARGE_ESTIMATE_STABILIZE_MS
                || smoothedBatteryCurrentUa < 300_000f) {
            return "estimating…";
        }
        double currentMa = smoothedBatteryCurrentUa / 1000d;
        double remainingMah = Math.max(0, 100 - level) * 8500d / 100d;
        double taperFactor = level < 60 ? 1.12d : level < 80 ? 1.25d : level < 90 ? 1.45d : 1.8d;
        long minutes = Math.max(1L, Math.min(
                Math.round(remainingMah / currentMa * 60d * taperFactor),
                720L
        ));
        if (minutes < 60L) {
            return "~" + minutes + " min";
        }
        return String.format(Locale.US, "~%dh %02dm", minutes / 60L, minutes % 60L);
    }

    private static float safeFloatField(Object target, String name) {
        try {
            return XposedHelpers.getFloatField(target, name);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static int safeIntField(Object target, String name, int fallback) {
        try {
            return XposedHelpers.getIntField(target, name);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String readString(String path) {
        File file = new File(path);
        if (!file.canRead()) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long readLong(String path) {
        try {
            String value = readString(path);
            return value.isEmpty() ? -1L : Long.parseLong(value);
        } catch (Throwable ignored) {
            return -1L;
        }
    }


    private static void hookRotationPersistence(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int[] state = parseRotationState(Settings.Secure.getString(resolver, ROTATION_STATE));
        if (state != null) {
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, state[1]);
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, state[0]);
        }

        // Only restore here. Saving changes is left to module/rotation-state.sh, which can see
        // who wrote the setting and ignores adb automation (monkey, uiautomator).
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (parseRotationState(Settings.Secure.getString(resolver, ROTATION_STATE)) == null) {
                saveRotationState(resolver);
            }
        }, ROTATION_SAVE_GRACE_MS);
        XposedBridge.log("dash-evox-patch: rotation persistence installed");
    }

    private static int[] parseRotationState(String saved) {
        if (saved == null || !saved.matches("[01]:[0-3]")) {
            return null;
        }
        return new int[]{saved.charAt(0) - '0', saved.charAt(2) - '0'};
    }

    private static void saveRotationState(ContentResolver resolver) {
        int accelerometerRotation = Settings.System.getInt(
                resolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
        );
        int userRotation = Settings.System.getInt(
                resolver,
                Settings.System.USER_ROTATION,
                0
        );
        if ((accelerometerRotation == 0 || accelerometerRotation == 1)
                && userRotation >= 0 && userRotation <= 3) {
            Settings.Secure.putString(
                    resolver,
                    ROTATION_STATE,
                    accelerometerRotation + ":" + userRotation
            );
        }
    }

    private static final int START = 6;
    private static final int END = 7;
    // On-screen edges of the collapsed status bar content, measured from its live layout.
    private static volatile int statusBarContentStart = -1;
    private static volatile int statusBarContentEnd = -1;
    private static volatile int statusBarWidth = -1;
    private static volatile WeakReference<Object> headerController = new WeakReference<>(null);

    /**
     * Gives the shade header the same side padding as the collapsed status bar. SystemUI only
     * offsets the header guides by the rounded-corner inset and pins the expanded state to the
     * parent edges, so the clock and battery sit closer to the screen edge than in the status bar.
     */
    private static void hookHeaderEdges(ClassLoader classLoader) {
        Class<?> controllerClass = XposedHelpers.findClass(
                "com.android.systemui.shade.ShadeHeaderController",
                classLoader
        );
        Class<?> motionLayoutClass = XposedHelpers.findClass(
                "androidx.constraintlayout.motion.widget.MotionLayout",
                classLoader
        );

        XposedHelpers.findAndHookMethod(
                controllerClass,
                "updateConstraintsForInsets",
                motionLayoutClass,
                WindowInsets.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        headerController = new WeakReference<>(param.thisObject);
                        try {
                            alignHeaderEdges((ViewGroup) param.args[0]);
                        } catch (Throwable throwable) {
                            XposedBridge.log("dash-evox-patch: failed to align header edges: " + throwable);
                        }
                    }
                }
        );

        XposedBridge.hookAllConstructors(
                XposedHelpers.findClass("com.android.systemui.statusbar.phone.PhoneStatusBarView", classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View statusBar = (View) param.thisObject;
                        statusBar.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) ->
                                view.post(() -> measureStatusBar(view)));
                    }
                }
        );
    }

    private static void measureStatusBar(View statusBar) {
        if (statusBar.getDisplay() == null
                || statusBar.getDisplay().getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
            return;
        }
        android.content.res.Resources resources = statusBar.getResources();
        View contents = statusBar.findViewById(id(resources, "status_bar_contents"));
        View systemIcons = statusBar.findViewById(id(resources, "system_icons"));
        View battery = systemIcons == null ? null : findBattery(systemIcons);
        // The collapsed bar hides while the shade is open; only trust a fully visible layout.
        if (contents == null || contents.getWidth() == 0 || battery == null || !battery.isShown()
                || battery.getWidth() == 0) {
            return;
        }
        int[] location = new int[2];
        contents.getLocationOnScreen(location);
        int start = location[0] + contents.getPaddingStart();
        battery.getLocationOnScreen(location);
        int end = location[0] + battery.getWidth() - battery.getPaddingEnd();

        int width = statusBar.getWidth();
        if (start == statusBarContentStart && end == statusBarContentEnd && width == statusBarWidth) {
            return;
        }
        statusBarContentStart = start;
        statusBarContentEnd = end;
        statusBarWidth = width;

        Object controller = headerController.get();
        if (controller == null) {
            return;
        }
        Object header = XposedHelpers.getObjectField(controller, "header");
        Object insets = XposedHelpers.getObjectField(controller, "lastInsets");
        if (header != null && insets != null) {
            XposedHelpers.callMethod(controller, "updateConstraintsForInsets", header, insets);
        }
    }

    private static View findBattery(View view) {
        if (view.getId() != View.NO_ID) {
            try {
                if (view.getResources().getResourceEntryName(view.getId()).contains("battery")) {
                    return view;
                }
            } catch (android.content.res.Resources.NotFoundException ignored) {
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View battery = findBattery(group.getChildAt(i));
                if (battery != null) {
                    return battery;
                }
            }
        }
        return null;
    }

    private static void alignHeaderEdges(ViewGroup header) {
        if (header.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL || statusBarContentStart < 0 || header.getWidth() == 0
                || header.getWidth() != statusBarWidth) {
            return;
        }
        android.content.res.Resources resources = header.getResources();
        int[] location = new int[2];
        header.getLocationOnScreen(location);
        int headerLeft = location[0];
        int headerRight = headerLeft + header.getWidth();

        // The header battery can sit inside the system icons container with its own end inset.
        int iconsEndInset = 0;
        View systemIcons = header.findViewById(id(resources, "shade_header_system_icons"));
        View battery = systemIcons == null ? null : findBattery(systemIcons);
        if (battery != null && battery.getWidth() > 0 && systemIcons.getWidth() > 0) {
            int[] batteryLocation = new int[2];
            battery.getLocationOnScreen(batteryLocation);
            systemIcons.getLocationOnScreen(location);
            iconsEndInset = (location[0] + systemIcons.getWidth())
                    - (batteryLocation[0] + battery.getWidth() - battery.getPaddingEnd());
        }

        int guideBegin = Math.max(statusBarContentStart - headerLeft - header.getPaddingLeft(), 0);
        int guideEnd = Math.max(
                headerRight - statusBarContentEnd - header.getPaddingRight() - iconsEndInset, 0);

        int clock = id(resources, "clock");
        int date = id(resources, "date");
        int beginGuide = id(resources, "begin_guide");
        int endGuide = id(resources, "end_guide");
        int privacy = id(resources, "privacy_container");
        for (String state : new String[] {"qqs_header_constraint", "qs_header_constraint"}) {
            int stateId = id(resources, state);
            Object constraints = XposedHelpers.callMethod(header, "getConstraintSet", stateId);
            XposedHelpers.callMethod(constraints, "setGuidelineBegin", beginGuide, guideBegin);
            XposedHelpers.callMethod(constraints, "setGuidelineEnd", endGuide, guideEnd);
            if (state.startsWith("qs_")) {
                XposedHelpers.callMethod(constraints, "connect", clock, START, beginGuide, START);
                XposedHelpers.callMethod(constraints, "connect", date, START, beginGuide, START);
                XposedHelpers.callMethod(constraints, "connect", id(resources, "shade_header_system_icons"), END, endGuide, END);
                XposedHelpers.callMethod(constraints, "connect", privacy, END, endGuide, END);
            }
            XposedHelpers.callMethod(header, "updateState", stateId, constraints);
        }
        header.requestLayout();
    }

    /**
     * Silences the haptics played while swiping a notification (the magnetic pull and the
     * dismiss threshold) and while dragging a volume slider. Other sliders, such as brightness,
     * keep theirs.
     */
    private static void hookSwipeHaptics(ClassLoader classLoader) {
        Class<?> emptyPlayerClass = XposedHelpers.findClass(
                "com.google.android.msdl.domain.EmptyMSDLPlayer", classLoader);

        XposedBridge.hookAllConstructors(
                XposedHelpers.findClass(
                        "com.android.systemui.statusbar.notification.stack.MagneticNotificationRowManagerImpl",
                        classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedHelpers.setObjectField(param.thisObject, "msdlPlayer",
                                XposedHelpers.newInstance(emptyPlayerClass));
                    }
                });

        // The volume and brightness sliders share this provider; only the caller tells them apart.
        XposedBridge.hookAllConstructors(
                XposedHelpers.findClass(
                        "com.android.systemui.haptics.slider.SliderHapticFeedbackProvider",
                        classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        for (StackTraceElement frame : new Throwable().getStackTrace()) {
                            if (frame.getClassName().startsWith("com.android.systemui.volume.")) {
                                XposedHelpers.setObjectField(param.thisObject, "msdlPlayer",
                                        XposedHelpers.newInstance(emptyPlayerClass));
                                XposedBridge.log("dash-evox-patch: muted volume slider haptics for "
                                        + frame.getClassName());
                                return;
                            }
                        }
                    }
                });
    }


    /**
    /**
     * Keeps the lock screen "swipe up" hint clear of the udfps icon. The dash overlay lifts the
     * indication area with a 130dp bottom margin, which parks the hint under the sensor circle.
     * Both views get a shared layout listener that runs synchronously inside the layout pass, so
     * the hint is already below the icon on the first rendered frame and never flashes.
     */
    private static void hookSwipeUpHint(ClassLoader classLoader) {
        View.OnLayoutChangeListener relay = (v, l, t, r, b, ol, ot, or, ob) -> {
            View area = v.getRootView().findViewById(id(v.getResources(), "keyguard_indication_area"));
            if (area != null) {
                placeSwipeUpHintBelowIcon(area);
            }
        };
        XC_MethodHook attach = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                attachSwipeUpHintRelay((View) param.thisObject, relay);
            }
        };
        for (String className : new String[] {
                "com.android.systemui.keyguard.ui.view.KeyguardIndicationArea",
                "com.android.systemui.keyguard.ui.view.DeviceEntryIconView"
        }) {
            XposedBridge.hookAllConstructors(
                    XposedHelpers.findClass(className, classLoader), attach);
        }
        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(
                            "com.android.systemui.statusbar.phone.KeyguardIndicationTextView",
                            classLoader),
                    "setText",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object text = param.args.length > 0 ? param.args[0] : null;
                            View view = (View) param.thisObject;
                            Object parent = view.getParent();
                            if (text instanceof CharSequence
                                    && ((CharSequence) text).length() > 0
                                    && view instanceof android.widget.TextView) {
                                // Text changes do not always relayout the area, so rerun the
                                // placement one frame after the text settles.
                                view.postDelayed(() -> {
                                    Object areaView = view.getParent();
                                    if (areaView instanceof View) {
                                        placeSwipeUpHintBelowIcon((View) areaView);
                                    }
                                }, 20);
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log("dash-evox-patch: swipe text hint hook failed: " + throwable);
        }
        // Views may already exist before our constructor hooks attach, so also latch on whenever
        // the keyguard sections bind their views. Sections return their root to bindData.
        for (String className : new String[] {
                "com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection",
                "com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntrySection"
        }) {
            try {
                XposedBridge.hookAllMethods(
                        XposedHelpers.findClass(className, classLoader),
                        "bindData",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                ViewGroup root = (ViewGroup) param.args[0];
                                attachSwipeUpHintRelay(
                                        root.findViewById(id(root.getResources(), "keyguard_indication_area")),
                                        relay);
                                attachSwipeUpHintRelay(
                                        root.findViewById(id(root.getResources(), "device_entry_icon_view")),
                                        relay);
                            }
                        });
            } catch (Throwable throwable) {
                XposedBridge.log("dash-evox-patch: swipe up hint section hook failed on "
                        + className + ": " + throwable);
            }
        }
    }

    private static void attachSwipeUpHintRelay(View view, View.OnLayoutChangeListener relay) {
        if (view == null) {
            return;
        }
        if (view.getTag() == null) {
            view.setTag(relay);
            view.addOnLayoutChangeListener(relay);
            XposedBridge.log("dash-evox-patch: swipe-up hint relay attached to "
                    + Integer.toHexString(view.getId()) + ")");
        }
    }


    private static void placeSwipeUpHintBelowIcon(View area) {
        try {
            if (area.getDisplay() == null
                    || area.getDisplay().getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
                return;
            }
            View icon = area.getRootView().findViewById(id(area.getResources(), "device_entry_icon_view"));
            if (icon == null || !icon.isShown() || icon.getHeight() == 0) {
                return;
            }
            if (icon.getTop() <= 0 || icon.getBottom() <= area.getTop()) {
                // The icon has not been laid out yet this traversal; a later listener reruns this.
                return;
            }
            List<View> areas = new ArrayList<>();
            collectViewsById(area.getRootView(),
                    id(area.getResources(), "keyguard_indication_area"), areas);
            int[] location = new int[2];
            icon.getLocationOnScreen(location);
            int iconBottom = location[1] + icon.getHeight();
            // More than one KeyguardIndicationArea can be attached; move the one holding the text.
            View target = null;
            int textTop = -1;
            int textBottom = -1;
            for (View candidate : areas) {
                candidate.setTranslationY(0);
                for (int i = 0; i < ((ViewGroup) candidate).getChildCount(); i++) {
                    View child = ((ViewGroup) candidate).getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE
                            || !(child instanceof android.widget.TextView)) {
                        continue;
                    }
                    if (((android.widget.TextView) child).getText().length() == 0 || !child.isShown()) {
                        continue;
                    }
                    child.getLocationOnScreen(location);
                    int top = location[1];
                    int bottom = top + child.getHeight();
                    textTop = textTop < 0 ? top : Math.min(textTop, top);
                    textBottom = textBottom < 0 ? bottom : Math.max(textBottom, bottom);
                }
                if (textTop >= 0) {
                    target = candidate;
                    break;
                }
            }
            if (target == null || textTop >= iconBottom) {
                return;
            }
            int gap = Math.round(12 * target.getResources().getDisplayMetrics().density);
            float translation = iconBottom + gap - textTop;
            // Never push the hint into the navigation bar.
            android.view.WindowInsets insets = target.getRootWindowInsets();
            if (insets != null) {
                int navBottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                int maxHeight = target.getResources().getDisplayMetrics().heightPixels - navBottom;
                if (textBottom + translation > maxHeight) {
                    translation = maxHeight - textBottom;
                }
            }
            if (translation > 0) {
                target.setTranslationY(translation);
                XposedBridge.log("dash-evox-patch: moved swipe-up hint down by " + Math.round(translation)
                        + "px (text " + textTop + "-" + textBottom + ", icon bottom " + iconBottom + ")");
            }
        } catch (Throwable throwable) {
            XposedBridge.log("dash-evox-patch: swipe-up hint placement failed: " + throwable);
        }
    }

    private static void collectViewsById(View view, int viewId, List<View> out) {
        if (view.getId() == viewId) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                collectViewsById(group.getChildAt(i), viewId, out);
            }
        }
    }
    private static int id(android.content.res.Resources resources, String name) {
        return resources.getIdentifier(name, "id", SYSTEM_UI);
    }

    private static void runHook(String name, Runnable hook) {
        try {
            hook.run();
        } catch (Throwable throwable) {
            XposedBridge.log("dash-evox-patch: " + name + " failed: " + throwable);
        }
    }

}
