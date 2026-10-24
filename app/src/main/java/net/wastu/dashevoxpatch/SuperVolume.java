package net.wastu.dashevoxpatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * HyperOS "Super Volume" (200% / 400% speaker boost) for the stock MediaTek HAL.
 *
 * The HAL switches the speaker DSP scene from SpkVolIdx (scene = index - 15, capped at 2) for a
 * stream, but only while that stream is marked active on a speaker output through
 * customStartStreamType / customStopStreamType. HyperOS drives both from its framework; AOSP
 * does neither. Volume up at the media maximum on the speaker steps into 200% and then 400%;
 * volume down steps back out.
 */
final class SuperVolume {
    private static final String TAG = "dash-evox-patch: super volume: ";
    private static final int STREAM_MUSIC = AudioManager.STREAM_MUSIC;
    private static final int BASE_INDEX = 15;
    private static final int MAX_LEVEL = 2;
    private static final String[] LEVEL_LABELS = {"100%", "200%", "400%"};

    private static final String AUDIO_FLINGER = "media.audio_flinger";
    private static final String AUDIO_FLINGER_DESCRIPTOR = "android.media.IAudioFlingerService";
    // IAudioFlingerService codes on EvolutionX 17. probeAudioFlinger() checks them before use.
    private static final int TRANSACTION_SET_PARAMETERS = 21;
    private static final int TRANSACTION_GET_PARAMETERS = 22;
    private static final String PROBE_KEY = "GET_SUPER_VOLUME_SUPPORT";

    // Hidden broadcast and extra from AudioManager.
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private static boolean hooked;
    private static SuperVolume instance;

    private final Context context;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean audioFlingerVerified;
    private int probeRetries;
    private int level;
    private boolean musicMarkedActive;
    private int markedOutput;
    private Toast toast;

    private SuperVolume(Context context) {
        this.context = context;
        this.audioManager = context.getSystemService(AudioManager.class);
    }

    static synchronized void hook(ClassLoader classLoader) {
        if (hooked) {
            return;
        }
        hooked = true;
        Object support = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.os.SystemProperties", null),
                "get", "ro.vendor.audio.supervolume.support");
        if (!"true".equals(support)) {
            XposedBridge.log(TAG + "HAL support property is off, not installing");
            return;
        }
        Class<?> audioService = XposedHelpers.findClass(
                "com.android.server.audio.AudioService", classLoader);

        XposedHelpers.findAndHookMethod(audioService, "onSystemReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                instance = new SuperVolume(context);
                instance.handler.post(instance::start);
            }
        });

        XposedHelpers.findAndHookMethod(audioService, "adjustStreamVolume",
                int.class, int.class, int.class,
                XposedHelpers.findClass("android.media.AudioDeviceAttributes", classLoader),
                String.class,
                String.class, int.class, int.class, String.class, boolean.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        SuperVolume superVolume = instance;
                        if (superVolume == null || param.args[3] != null) {
                            return;
                        }
                        try {
                            if (superVolume.onAdjust((int) param.args[0], (int) param.args[1])) {
                                param.setResult(null);
                            }
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + "adjust failed: " + throwable);
                        }
                    }
                });
        XposedBridge.log(TAG + "hooks installed");
    }

    private void start() {
        if (audioFlingerVerified) {
            return;
        }
        if (!probeAudioFlinger()) {
            if (probeRetries++ == 0) {
                XposedBridge.log(TAG + "IAudioFlingerService did not verify; will retry");
            }
            if (probeRetries <= 6) {
                handler.postDelayed(this::start, 15_000L);
                return;
            }
            XposedBridge.log(TAG + "IAudioFlingerService did not verify after "
                    + probeRetries + " probes; disabled");
            instance = null;
            return;
        }
        audioFlingerVerified = true;
        audioManager.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                updateMusicActivity(configs);
            }
        }, handler);
        audioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
                resetIfNotAtSpeakerMax();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                resetIfNotAtSpeakerMax();
            }
        }, handler);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == STREAM_MUSIC) {
                    resetIfNotAtSpeakerMax();
                }
            }
        }, new IntentFilter(VOLUME_CHANGED_ACTION), null, handler, Context.RECEIVER_NOT_EXPORTED);
        linkToAudioServerDeath();
        applyLevel();
        XposedBridge.log(TAG + "ready");
    }

    /** Returns true when the volume key was consumed by a boost step. */
    private boolean onAdjust(int streamType, int direction) {
        if (!audioFlingerVerified) {
            return false;
        }
        if (streamType != STREAM_MUSIC || !isAtSpeakerMax()) {
            return false;
        }
        if (direction == AudioManager.ADJUST_RAISE && level < MAX_LEVEL) {
            handler.post(() -> setLevel(level + 1));
            return false; // Let AudioService show the volume panel at its maximum.
        }
        if (direction == AudioManager.ADJUST_LOWER && level > 0) {
            handler.post(() -> setLevel(level - 1));
            return true; // Stay at the maximum until the boost is gone.
        }
        return false;
    }

    private void setLevel(int newLevel) {
        if (newLevel == level) {
            return;
        }
        level = newLevel;
        applyLevel();
        showLevel();
    }

    private void resetIfNotAtSpeakerMax() {
        if (level != 0 && !isAtSpeakerMax()) {
            level = 0;
            applyLevel();
        }
    }

    private void applyLevel() {
        audioManager.setParameters("SpkVolIdx=" + (BASE_INDEX + level)
                + ";SuperStream=" + STREAM_MUSIC);
    }

    private void showLevel() {
        if (toast != null) {
            toast.cancel();
        }
        String text = level == 0 ? "Volume boost off" : "Volume boost " + LEVEL_LABELS[level];
        toast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
        toast.show();
    }

    private boolean isAtSpeakerMax() {
        if (audioManager.getStreamVolume(STREAM_MUSIC)
                < audioManager.getStreamMaxVolume(STREAM_MUSIC)) {
            return false;
        }
        AudioAttributes media = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        List<?> devices = (List<?>) XposedHelpers.callMethod(
                audioManager, "getDevicesForAttributes", media);
        return devices.size() == 1
                && (int) XposedHelpers.callMethod(devices.get(0), "getType")
                        == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }

    private void updateMusicActivity(List<AudioPlaybackConfiguration> configs) {
        boolean active = false;
        for (AudioPlaybackConfiguration config : configs) {
            if ((boolean) XposedHelpers.callMethod(config, "isActive")
                    && config.getAudioAttributes().getVolumeControlStream() == STREAM_MUSIC) {
                active = true;
                break;
            }
        }
        if (active == musicMarkedActive) {
            return;
        }
        if (active) {
            int output = primaryOutput();
            if (output > 0 && setOutputParameters(output, "customStartStreamType=" + STREAM_MUSIC)) {
                markedOutput = output;
                musicMarkedActive = true;
            }
        } else {
            setOutputParameters(markedOutput, "customStopStreamType=" + STREAM_MUSIC);
            musicMarkedActive = false;
        }
    }

    /** The HAL counts active streams per stream type; any speaker output can carry the mark. */
    private int primaryOutput() {
        try {
            ArrayList<Object> ports = new ArrayList<>();
            XposedHelpers.callStaticMethod(AudioManager.class, "listAudioPorts", ports);
            int fallback = -1;
            for (Object port : ports) {
                if (!"android.media.AudioMixPort".equals(port.getClass().getName())) {
                    continue;
                }
                // Source mix ports are outputs.
                if ((int) XposedHelpers.callMethod(port, "role") != 1) {
                    continue;
                }
                int ioHandle = (int) XposedHelpers.callMethod(port, "ioHandle");
                String name = String.valueOf(XposedHelpers.callMethod(port, "name"));
                if (name.contains("primary")) {
                    return ioHandle;
                }
                if (fallback < 0) {
                    fallback = ioHandle;
                }
            }
            return fallback;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "listing outputs failed: " + throwable);
            return -1;
        }
    }

    private boolean probeAudioFlinger() {
        String reply = transact(TRANSACTION_GET_PARAMETERS, 0, PROBE_KEY, true);
        return reply != null && reply.startsWith(PROBE_KEY + "=");
    }

    private boolean setOutputParameters(int output, String keyValuePairs) {
        if (!audioFlingerVerified || output <= 0) {
            return false;
        }
        return transact(TRANSACTION_SET_PARAMETERS, output, keyValuePairs, false) != null;
    }

    /** Calls IAudioFlingerService set/getParameters(ioHandle, String) directly. */
    private static String transact(int code, int ioHandle, String keyValuePairs, boolean readString) {
        IBinder binder = (IBinder) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.os.ServiceManager", null),
                "getService", AUDIO_FLINGER);
        if (binder == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(AUDIO_FLINGER_DESCRIPTOR);
            data.writeInt(ioHandle);
            data.writeString(keyValuePairs);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return readString ? reply.readString() : "";
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "audio_flinger call " + code + " failed: " + throwable);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** A restarted audio HAL forgets both the stream mark and the scene. */
    private void linkToAudioServerDeath() {
        try {
            IBinder binder = (IBinder) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.ServiceManager", null),
                    "getService", AUDIO_FLINGER);
            binder.linkToDeath(() -> handler.postDelayed(() -> {
                musicMarkedActive = false;
                level = 0;
                linkToAudioServerDeath();
                applyLevel();
                updateMusicActivity(audioManager.getActivePlaybackConfigurations());
            }, 3000), 0);
        } catch (Throwable throwable) {
            handler.postDelayed(this::linkToAudioServerDeath, 3000);
        }
    }
}
