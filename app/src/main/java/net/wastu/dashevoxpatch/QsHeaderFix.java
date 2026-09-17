package net.wastu.dashevoxpatch;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.content.res.Resources;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class QsHeaderFix implements IXposedHookLoadPackage {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ROTATION_STATE = "dash_evox_rotation_state";
    private static final int START = 6;
    private static final int END = 7;

    private static final long CHARGE_ESTIMATE_STABILIZE_MS = 30_000L;
    private static volatile boolean wiredCharging;
    private static volatile int chargerClass;
    private static volatile int batteryLevel;
    private static volatile long pluggedSinceMs;
    private static volatile float smoothedBatteryCurrentUa;
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
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
                        ClassLoader classLoader = context.getClassLoader();
                        hookRotationPersistence(context);
                        hookVariableDate(classLoader);
                        hookHeaderInsets(classLoader);
                        hookNotificationDismissHaptics(classLoader);
                        hookHyperCharge(classLoader);
                        XposedBridge.log("EvolutionXQsFix: hooks installed");
                    }
                }
        );
    }


    private static void hookRotationPersistence(Context context) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            String saved = Settings.Secure.getString(resolver, ROTATION_STATE);
            int[] state = parseRotationState(saved);
            if (state == null) {
                saveRotationState(resolver);
            } else {
                Settings.System.putInt(resolver, Settings.System.USER_ROTATION, state[1]);
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, state[0]);
            }

            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    saveRotationState(resolver);
                }
            };
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                    false,
                    observer
            );
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.USER_ROTATION),
                    false,
                    observer
            );
        } catch (Throwable throwable) {
            XposedBridge.log("EvolutionXQsFix: failed to preserve rotation state: " + throwable);
        }
    }

    private static int[] parseRotationState(String saved) {
        if (saved == null || !saved.matches("[01]:[0-3]")) {
            return null;
        }
        return new int[]{saved.charAt(0) - '0', saved.charAt(2) - '0'};
    }

    private static void saveRotationState(android.content.ContentResolver resolver) {
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

    private static void hookHeaderInsets(ClassLoader classLoader) {
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
                android.view.WindowInsets.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            alignExpandedHeader(param.thisObject);
                        } catch (Throwable throwable) {
                            XposedBridge.log("EvolutionXQsFix: failed to align header: " + throwable);
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

    private static void alignExpandedHeader(Object controller) {
        View header = (View) XposedHelpers.getObjectField(controller, "header");
        Context context = header.getContext();
        Resources resources = context.getResources();

        int qsState = id(resources, "qs_header_constraint");
        int qqsState = id(resources, "qqs_header_constraint");
        int clock = id(resources, "clock");
        int date = id(resources, "date");
        int systemIcons = id(resources, "shade_header_system_icons");
        int beginGuide = id(resources, "begin_guide");
        int endGuide = id(resources, "end_guide");
        int minimumDateWidth = Math.round(90 * resources.getDisplayMetrics().density);

        Object qqsConstraints = XposedHelpers.callMethod(header, "getConstraintSet", qqsState);
        XposedHelpers.callMethod(qqsConstraints, "clear", date, END);
        XposedHelpers.callMethod(qqsConstraints, "constrainWidth", date, -2);
        XposedHelpers.callMethod(qqsConstraints, "constrainMinWidth", date, minimumDateWidth);
        XposedHelpers.callMethod(header, "updateState", qqsState, qqsConstraints);

        Object qsConstraints = XposedHelpers.callMethod(header, "getConstraintSet", qsState);
        XposedHelpers.callMethod(qsConstraints, "connect", clock, START, beginGuide, START);
        XposedHelpers.callMethod(qsConstraints, "connect", date, START, beginGuide, START);
        XposedHelpers.callMethod(qsConstraints, "connect", systemIcons, END, endGuide, END);
        XposedHelpers.callMethod(header, "updateState", qsState, qsConstraints);
        header.requestLayout();
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

            long quickChargeType = readLong("/sys/class/power_supply/usb/quick_charge_type");
            long apdoMaxWatts = readLong("/sys/class/power_supply/usb/apdo_max");
            long authenticated = readLong("/sys/class/power_supply/usb/pd_authentication");
            long verificationDone = readLong("/sys/class/power_supply/usb/pd_verify_done");
            String realType = readString("/sys/class/power_supply/usb/real_type");
            float frameworkMaxWatts = safeFloatField(status, "maxChargingWattage") / 1_000_000f;

            boolean hyperCharge = quickChargeType >= 3
                    || (apdoMaxWatts >= 90 && authenticated == 1 && verificationDone == 1);
            boolean fastCharge = quickChargeType >= 1
                    || apdoMaxWatts >= 18
                    || "USB_PD".equals(realType)
                    || frameworkMaxWatts >= 18f;
            chargerClass = nowWired ? (hyperCharge ? 2 : fastCharge ? 1 : 0) : 0;

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

    private static String buildChargingIndication(Object controller) {
        int controllerLevel = safeIntField(controller, "mBatteryLevel", batteryLevel);
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
        String icon = chargerClass == 2 ? "⚡⚡" : chargerClass == 1 ? "⚡" : "🐌";
        String power = watts > 0f
                ? String.format(Locale.US, "%.1fW", watts)
                : "charging";
        return controllerLevel + "% • " + icon + power + "\n" + estimateChargeTime(controllerLevel);
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


    private static void hookNotificationDismissHaptics(ClassLoader classLoader) {
        Class<?> factoryClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.stack.MagneticNotificationRowManagerImpl_Factory",
                classLoader
        );
        Class<?> emptyPlayerClass = XposedHelpers.findClass(
                "com.google.android.msdl.domain.EmptyMSDLPlayer",
                classLoader
        );

        XposedHelpers.findAndHookMethod(
                factoryClass,
                "get",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object manager = param.getResult();
                        Object emptyPlayer = XposedHelpers.newInstance(emptyPlayerClass);
                        XposedHelpers.setObjectField(manager, "msdlPlayer", emptyPlayer);
                    }
                }
        );
    }

    private static int id(Resources resources, String name) {
        int value = resources.getIdentifier(name, "id", SYSTEM_UI);
        if (value == 0) {
            throw new Resources.NotFoundException(name);
        }
        return value;
    }
}
