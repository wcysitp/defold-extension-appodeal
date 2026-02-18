package com.defold.appodeal;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.appodeal.ads.Appodeal;
import com.appodeal.ads.InterstitialCallbacks;
import com.appodeal.ads.RewardedVideoCallbacks;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppodealBridge {
    private static final String TAG = "DefoldAppodeal";
    private static final int AD_TYPES = Appodeal.INTERSTITIAL | Appodeal.REWARDED_VIDEO;
    private static final int INIT_RETRY_DELAY_MS = 100;
    private static final int INIT_RETRY_MAX_ATTEMPTS = 50;
    private static final int INIT_CALLBACK_TIMEOUT_MS = 15000;
    private static final int CONSENT_UPDATE_TIMEOUT_MS = 5000;
    private static final int CACHE_RETRY_DELAY_MS = 3000;
    /**
     * Delay before calling Appodeal.show() to give Defold engine's render
     * thread time to handle onPause and release its Surface/BLASTBufferQueue.
     * Without this, the render thread is still drawing when Android destroys
     * the game window for the ad Activity, causing SIGSEGV.
     */
    private static final int SHOW_DELAY_MS = 250;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sCallbacksConfigured = new AtomicBoolean(false);
    private static final AtomicBoolean sInitCallbackSent = new AtomicBoolean(false);
    private static final AtomicBoolean sConsentApiLogged = new AtomicBoolean(false);
    private static final AtomicBoolean sConsentInfoUpdated = new AtomicBoolean(false);
    private static final AtomicBoolean sAdaptersProbeLogged = new AtomicBoolean(false);
    private static volatile String sLastAppKey = null;
    private static volatile boolean sRewardedShownFired = false;
    private static volatile boolean sRewardedFinishedFired = false;
    private static volatile boolean sTestMode = false;

    private AppodealBridge() {
    }

    public static boolean initialize(final String appKey, final boolean testing, final String logLevel) {
        Log.i(TAG, "initialize called");
        if (appKey == null || appKey.trim().isEmpty()) {
            Log.e(TAG, "initialize failed: app_key_is_empty");
            return false;
        }

        sLastAppKey = appKey;
        sConsentInfoUpdated.set(false);
        sInitCallbackSent.set(false);
        scheduleInitialize(appKey, testing, logLevel, 0);
        return true;
    }

    private static void scheduleInitialize(
        final String appKey,
        final boolean testing,
        final String logLevel,
        final int attempt
    ) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                final Activity activity = getActivity();
                if (activity == null) {
                    if (attempt < INIT_RETRY_MAX_ATTEMPTS) {
                        MAIN_HANDLER.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                scheduleInitialize(appKey, testing, logLevel, attempt + 1);
                            }
                        }, INIT_RETRY_DELAY_MS);
                    } else {
                        Log.e(TAG, "initialize failed: activity_is_null");
                        notifyInitOnce(false, "activity_is_null");
                    }
                    return;
                }

                try {
                    configureCallbacks();
                    applyLogLevel(logLevel);
                    Appodeal.setTesting(testing);
                    sTestMode = testing;
                    configureAutoCache();
                    logAdapterProbeOnce(activity);
                    preconfigureConsentState(activity);

                    requestConsentInfoUpdate(activity, appKey, new Runnable() {
                        @Override
                        public void run() {
                            performInitializeCall(activity, appKey);
                        }
                    });
                } catch (Throwable throwable) {
                    Log.e(TAG, "initialize failed with exception", throwable);
                    notifyInitOnce(false, throwable.getClass().getSimpleName() + ":" + throwable.getMessage());
                }
            }
        });
    }

    private static void notifyInitOnce(boolean success, String reason) {
        if (sInitCallbackSent.compareAndSet(false, true)) {
            nativeOnInit(success, reason);
        }
    }

    public static boolean isInterstitialAvailable() {
        try {
            return Appodeal.isLoaded(Appodeal.INTERSTITIAL);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean showInterstitial() {
        Log.i(TAG, "showInterstitial called");
        final Activity activity = getActivity();
        if (activity == null) {
            Log.e(TAG, "showInterstitial: activity_is_null");
            nativeOnInterstitialEvent("show_failed", false, "activity_is_null");
            return false;
        }

        runOnUiThread(activity, new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isConsentReadyForAds()) {
                        Log.w(TAG, "showInterstitial: refreshing consent before show");
                        requestConsentInfoUpdate(activity, sLastAppKey, new Runnable() {
                            @Override
                            public void run() {
                                runOnUiThread(activity, new Runnable() {
                                    @Override
                                    public void run() {
                                        attemptShowInterstitial(activity);
                                    }
                                });
                            }
                        });
                        return;
                    }

                    attemptShowInterstitial(activity);
                } catch (Throwable throwable) {
                    Log.e(TAG, "showInterstitial failed with exception", throwable);
                    nativeOnInterstitialEvent(
                        "show_failed",
                        false,
                        throwable.getClass().getSimpleName() + ":" + throwable.getMessage()
                    );
                }
            }
        });

        return true;
    }

    public static boolean isRewardedAvailable() {
        try {
            return Appodeal.isLoaded(Appodeal.REWARDED_VIDEO);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean showRewarded() {
        Log.i(TAG, "showRewarded called");
        final Activity activity = getActivity();
        if (activity == null) {
            Log.e(TAG, "showRewarded: activity_is_null");
            nativeOnRewardedEvent("show_failed", false, "activity_is_null", false, 0.0d, null);
            return false;
        }

        runOnUiThread(activity, new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isConsentReadyForAds()) {
                        Log.w(TAG, "showRewarded: refreshing consent before show");
                        requestConsentInfoUpdate(activity, sLastAppKey, new Runnable() {
                            @Override
                            public void run() {
                                runOnUiThread(activity, new Runnable() {
                                    @Override
                                    public void run() {
                                        attemptShowRewarded(activity);
                                    }
                                });
                            }
                        });
                        return;
                    }

                    attemptShowRewarded(activity);
                } catch (Throwable throwable) {
                    Log.e(TAG, "showRewarded failed with exception", throwable);
                    nativeOnRewardedEvent(
                        "show_failed",
                        false,
                        throwable.getClass().getSimpleName() + ":" + throwable.getMessage(),
                        false,
                        0.0d,
                        null
                    );
                }
            }
        });

        return true;
    }

    private static void attemptShowInterstitial(final Activity activity) {
        if (activity == null) {
            nativeOnInterstitialEvent("show_failed", false, "activity_is_null");
            return;
        }

        if (!isConsentReadyForAds()) {
            Log.w(TAG, "showInterstitial blocked: consent_not_ready");
            nativeOnInterstitialEvent("show_failed", false, "consent_not_ready");
            return;
        }

        boolean canShow = Appodeal.canShow(Appodeal.INTERSTITIAL);
        if (canShow) {
            Log.i(TAG, "showInterstitial: canShow=true, scheduling with " + SHOW_DELAY_MS + "ms delay");
            // Delay show to give Defold's render thread time to handle
            // Surface lifecycle before Android creates ad Activity window.
            MAIN_HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            Log.w(TAG, "showInterstitial aborted: activity gone");
                            nativeOnInterstitialEvent("show_failed", false, "activity_destroyed");
                            return;
                        }
                        Log.i(TAG, "showInterstitial: calling Appodeal.show");
                        Appodeal.show(activity, Appodeal.INTERSTITIAL);
                    } catch (Throwable throwable) {
                        Log.e(TAG, "showInterstitial: Appodeal.show threw", throwable);
                        nativeOnInterstitialEvent("show_failed", false, throwable.getMessage());
                    }
                }
            }, SHOW_DELAY_MS);
        } else {
            Log.w(TAG, "showInterstitial: interstitial_not_available");
            scheduleCacheWarmup("interstitial_not_available");
            nativeOnInterstitialEvent("show_failed", false, "interstitial_not_available");
        }
    }

    private static void attemptShowRewarded(final Activity activity) {
        if (activity == null) {
            nativeOnRewardedEvent("show_failed", false, "activity_is_null", false, 0.0d, null);
            return;
        }

        if (!isConsentReadyForAds()) {
            Log.w(TAG, "showRewarded blocked: consent_not_ready");
            nativeOnRewardedEvent("show_failed", false, "consent_not_ready", false, 0.0d, null);
            return;
        }

        boolean canShow = Appodeal.canShow(Appodeal.REWARDED_VIDEO);
        if (canShow) {
            Log.i(TAG, "showRewarded: canShow=true, scheduling with " + SHOW_DELAY_MS + "ms delay");
            // Delay show to give Defold's render thread time to handle
            // Surface lifecycle before Android creates ad Activity window.
            MAIN_HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            Log.w(TAG, "showRewarded aborted: activity gone");
                            nativeOnRewardedEvent("show_failed", false, "activity_destroyed", false, 0.0d, null);
                            return;
                        }
                        Log.i(TAG, "showRewarded: calling Appodeal.show");
                        Appodeal.show(activity, Appodeal.REWARDED_VIDEO);
                    } catch (Throwable throwable) {
                        Log.e(TAG, "showRewarded: Appodeal.show threw", throwable);
                        nativeOnRewardedEvent("show_failed", false, throwable.getMessage(), false, 0.0d, null);
                    }
                }
            }, SHOW_DELAY_MS);
        } else {
            Log.w(TAG, "showRewarded: rewarded_not_available");
            scheduleCacheWarmup("rewarded_not_available");
            nativeOnRewardedEvent("show_failed", false, "rewarded_not_available", false, 0.0d, null);
        }
    }

    private static void performInitializeCall(Activity activity, String appKey) {
        try {
            logConsentState("before_initialize");

            InitCallResult initCallResult = callInitialize(activity, appKey);
            Log.i(TAG, "initialize success");
            warmUpCacheNow("initialize_success");
            if (initCallResult.waitForCallback) {
                MAIN_HANDLER.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!sInitCallbackSent.get()) {
                            if (!isConsentReadyForAds()) {
                                Log.w(TAG, "initialize callback timeout while consent is pending");
                                notifyInitOnce(true, "init_deferred_by_consent");
                            } else {
                                Log.e(TAG, "initialize callback timeout");
                                notifyInitOnce(false, "init_callback_timeout");
                            }
                        }
                    }
                }, INIT_CALLBACK_TIMEOUT_MS);
            } else {
                notifyInitOnce(true, null);
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "initialize failed with exception", throwable);
            notifyInitOnce(false, throwable.getClass().getSimpleName() + ":" + throwable.getMessage());
        }
    }

    private static void requestConsentInfoUpdate(Activity activity, String appKey, final Runnable onComplete) {
        if (activity == null || appKey == null || appKey.trim().isEmpty()) {
            if (onComplete != null) {
                runOnMainThread(onComplete);
            }
            return;
        }

        try {
            final Class<?> consentManagerClass = Class.forName("com.appodeal.consent.ConsentManager");
            Method requestMethod = consentManagerClass.getMethod(
                "requestConsentInfoUpdate",
                Class.forName("com.appodeal.consent.ConsentUpdateRequestParameters"),
                Class.forName("com.appodeal.consent.ConsentInfoUpdateCallback")
            );

            final AtomicBoolean completed = new AtomicBoolean(false);
            final Runnable finish = new Runnable() {
                @Override
                public void run() {
                    if (!completed.compareAndSet(false, true)) {
                        return;
                    }

                    logConsentState("after_consent_update");
                    if (onComplete != null) {
                        runOnMainThread(onComplete);
                    }
                }
            };

            Object requestParams = buildConsentUpdateRequest(activity, appKey);
            if (requestParams == null) {
                Log.w(TAG, "consent update skipped: cannot build ConsentUpdateRequestParameters");
                finish.run();
                return;
            }

            Object callback = createConsentUpdateCallback(
                requestMethod.getParameterTypes()[1],
                finish
            );
            if (callback == null) {
                Log.w(TAG, "consent update skipped: cannot create ConsentInfoUpdateCallback");
                finish.run();
                return;
            }

            MAIN_HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (completed.compareAndSet(false, true)) {
                        Log.w(TAG, "consent update timeout");
                        if (onComplete != null) {
                            runOnMainThread(onComplete);
                        }
                    }
                }
            }, CONSENT_UPDATE_TIMEOUT_MS);

            requestMethod.invoke(null, requestParams, callback);
            Log.i(TAG, "consent update requested");
        } catch (Throwable throwable) {
            Log.w(TAG, "consent update skipped: " + throwable.getClass().getSimpleName());
            if (onComplete != null) {
                runOnMainThread(onComplete);
            }
        }
    }

    private static Object buildConsentUpdateRequest(Activity activity, String appKey) {
        try {
            Class<?> requestClass = Class.forName("com.appodeal.consent.ConsentUpdateRequestParameters");
            Constructor<?>[] constructors = requestClass.getConstructors();
            String sdkVersion = getAppodealVersion();

            for (Constructor<?> constructor : constructors) {
                Class<?>[] params = constructor.getParameterTypes();
                Object[] args = new Object[params.length];
                int stringIndex = 0;
                boolean compatible = true;

                for (int i = 0; i < params.length; i++) {
                    Class<?> param = params[i];

                    if (Activity.class.isAssignableFrom(param)) {
                        args[i] = activity;
                    } else if (Context.class.isAssignableFrom(param)) {
                        args[i] = activity;
                    } else if (Boolean.TYPE.equals(param) || Boolean.class.equals(param)) {
                        args[i] = Boolean.FALSE;
                    } else if (String.class.equals(param)) {
                        if (stringIndex == 0) {
                            args[i] = appKey;
                        } else if (stringIndex == 1) {
                            args[i] = "Appodeal";
                        } else if (stringIndex == 2) {
                            args[i] = sdkVersion;
                        } else {
                            args[i] = "";
                        }
                        stringIndex++;
                    } else {
                        compatible = false;
                        break;
                    }
                }

                if (!compatible) {
                    continue;
                }

                try {
                    return constructor.newInstance(args);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static String getAppodealVersion() {
        try {
            Method method = Appodeal.class.getMethod("getVersion");
            Object value = method.invoke(null);
            return value != null ? String.valueOf(value) : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static Object createConsentUpdateCallback(final Class<?> callbackType, final Runnable onComplete) {
        if (callbackType == null || !callbackType.isInterface()) {
            return null;
        }

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String methodName = method != null ? method.getName() : "";
                if ("onUpdated".equals(methodName)) {
                    Log.i(TAG, "consent update callback: updated");
                    sConsentInfoUpdated.set(true);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                } else if ("onFailed".equals(methodName)) {
                    String reason = "unknown";
                    if (args != null && args.length > 0 && args[0] != null) {
                        reason = String.valueOf(args[0]);
                    }
                    Log.w(TAG, "consent update callback: failed, reason=" + reason);
                    sConsentInfoUpdated.set(false);
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
                return getDefaultReturnValue(method != null ? method.getReturnType() : null);
            }
        };

        return Proxy.newProxyInstance(
            callbackType.getClassLoader(),
            new Class<?>[] { callbackType },
            handler
        );
    }

    private static Boolean canShowAdsByConsent() {
        try {
            Class<?> consentManagerClass = Class.forName("com.appodeal.consent.ConsentManager");
            Method method = consentManagerClass.getMethod("canShowAds");
            Object value = method.invoke(null);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object getConsentStatus() {
        try {
            Class<?> consentManagerClass = Class.forName("com.appodeal.consent.ConsentManager");
            try {
                Method getter = consentManagerClass.getMethod("getStatus");
                return getter.invoke(null);
            } catch (NoSuchMethodException ignored) {
            }

            Field field = consentManagerClass.getField("status");
            return field.get(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void logConsentState(String stage) {
        try {
            Object status = getConsentStatus();
            Boolean canShow = canShowAdsByConsent();
            Log.i(TAG, "consent state[" + stage + "]: status=" + status + ", canShowAds=" + canShow);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isConsentReadyForAds() {
        if (!sConsentInfoUpdated.get()) {
            return false;
        }

        Boolean canShow = canShowAdsByConsent();
        if (Boolean.FALSE.equals(canShow)) {
            return false;
        }

        Object status = getConsentStatus();
        if (status != null) {
            String value = String.valueOf(status).toLowerCase(Locale.US);
            if (value.contains("required") || value.contains("unknown")) {
                return false;
            }
        }

        return true;
    }

    private static void configureCallbacks() {
        if (!sCallbacksConfigured.compareAndSet(false, true)) {
            return;
        }

        Appodeal.setInterstitialCallbacks(new InterstitialCallbacks() {
            @Override
            public void onInterstitialLoaded(boolean isPrecache) {
                Log.i(TAG, "interstitial loaded");
                nativeOnInterstitialEvent("loaded", true, null);
            }

            @Override
            public void onInterstitialFailedToLoad() {
                Log.w(TAG, "interstitial failed_to_load");
                scheduleCacheWarmup("interstitial_failed_to_load");
                nativeOnInterstitialEvent("failed_to_load", false, "failed_to_load");
            }

            @Override
            public void onInterstitialShowFailed() {
                Log.w(TAG, "interstitial show_failed");
                nativeOnInterstitialEvent("show_failed", false, "show_failed");
            }

            @Override
            public void onInterstitialShown() {
                nativeOnInterstitialEvent("shown", true, null);
            }

            @Override
            public void onInterstitialClicked() {
                nativeOnInterstitialEvent("clicked", true, null);
            }

            @Override
            public void onInterstitialClosed() {
                nativeOnInterstitialEvent("closed", true, null);
            }

            @Override
            public void onInterstitialExpired() {
                nativeOnInterstitialEvent("expired", false, "expired");
            }
        });

        Appodeal.setRewardedVideoCallbacks(new RewardedVideoCallbacks() {
            @Override
            public void onRewardedVideoLoaded(boolean isPrecache) {
                Log.i(TAG, "rewarded loaded");
                nativeOnRewardedEvent("loaded", true, null, false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoFailedToLoad() {
                Log.w(TAG, "rewarded failed_to_load");
                scheduleCacheWarmup("rewarded_failed_to_load");
                nativeOnRewardedEvent("failed_to_load", false, "failed_to_load", false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoShowFailed() {
                Log.w(TAG, "rewarded show_failed");
                nativeOnRewardedEvent("show_failed", false, "show_failed", false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoShown() {
                sRewardedShownFired = true;
                nativeOnRewardedEvent("shown", true, null, false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoClicked() {
                nativeOnRewardedEvent("clicked", true, null, false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoFinished(double amount, String name) {
                Log.i(TAG, "rewarded reward amount=" + amount + " currency=" + name);
                sRewardedFinishedFired = true;
                nativeOnRewardedEvent("reward", true, null, true, amount, name);
            }

            @Override
            public void onRewardedVideoClosed(boolean finished) {
                Log.i(TAG, "rewarded closed finished=" + finished
                    + " shownFired=" + sRewardedShownFired
                    + " finishedFired=" + sRewardedFinishedFired);

                // Some test creatives never fire onRewardedVideoFinished.
                // If the ad was shown and user didn't get show_failed, treat
                // it as a successful view when in test mode.
                boolean effectiveFinished = finished;
                if (!finished && sRewardedShownFired && !sRewardedFinishedFired) {
                    Log.w(TAG, "rewarded closed: SDK did not fire onRewardedVideoFinished."
                        + " This is common with test creatives.");
                    if (sTestMode) {
                        Log.i(TAG, "rewarded closed: test mode → treating as finished=true");
                        effectiveFinished = true;
                    }
                }

                // Reset flags for next show
                sRewardedShownFired = false;
                sRewardedFinishedFired = false;

                nativeOnRewardedEvent("closed", true, null, effectiveFinished, 0.0d, null);
            }

            @Override
            public void onRewardedVideoExpired() {
                nativeOnRewardedEvent("expired", false, "expired", false, 0.0d, null);
            }
        });
    }

    private static final class InitCallResult {
        final boolean waitForCallback;

        InitCallResult(boolean waitForCallback) {
            this.waitForCallback = waitForCallback;
        }
    }

    private static InitCallResult callInitialize(Activity activity, String appKey) throws Exception {
        Throwable lastError = null;
        Method[] methods = Appodeal.class.getMethods();

        // Prefer signatures with init callback first, then fallback to legacy 3-arg init.
        for (int pass = 0; pass < 2; pass++) {
            boolean requireCallback = pass == 0;
            for (Method method : methods) {
                if (!"initialize".equals(method.getName()) || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }

                Class<?>[] params = method.getParameterTypes();
                if (!isInitializeSignature(params)) {
                    continue;
                }

                if (requireCallback && params.length != 4) {
                    continue;
                }
                if (!requireCallback && params.length != 3) {
                    continue;
                }

                try {
                    Object[] args = buildInitializeArgs(params, activity, appKey);
                    if (params.length == 4 && args[3] == null) {
                        Log.w(TAG, "initialize callback could not be created for signature: " + method.toString());
                        continue;
                    }

                    method.invoke(null, args);
                    boolean waitForCallback = params.length == 4 && args[3] != null;
                    Log.i(TAG, "initialize signature selected: " + method.toString());
                    return new InitCallResult(waitForCallback);
                } catch (Throwable throwable) {
                    lastError = unwrapInvocationError(throwable);
                    Log.w(TAG, "initialize signature failed: " + method.toString() + ", reason=" + lastError);
                }
            }
        }

        if (lastError instanceof Exception) {
            throw (Exception) lastError;
        }
        if (lastError != null) {
            throw new Exception(lastError);
        }
        throw new NoSuchMethodException("No compatible Appodeal.initialize signature");
    }

    private static boolean isInitializeSignature(Class<?>[] params) {
        if (params == null || (params.length != 3 && params.length != 4)) {
            return false;
        }
        if (!isContextParameter(params[0])) {
            return false;
        }
        if (!String.class.equals(params[1])) {
            return false;
        }
        if (!isAdTypesParameter(params[2])) {
            return false;
        }
        return true;
    }

    private static boolean isContextParameter(Class<?> clazz) {
        return clazz != null && clazz.isAssignableFrom(Activity.class) && Context.class.isAssignableFrom(Activity.class);
    }

    private static boolean isAdTypesParameter(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        if (int.class.equals(clazz) || Integer.class.equals(clazz)) {
            return true;
        }
        return clazz.isArray() && int.class.equals(clazz.getComponentType());
    }

    private static Object[] buildInitializeArgs(Class<?>[] params, Activity activity, String appKey) {
        Object[] args = new Object[params.length];
        args[0] = activity;
        args[1] = appKey;

        if (int.class.equals(params[2])) {
            args[2] = AD_TYPES;
        } else if (Integer.class.equals(params[2])) {
            args[2] = Integer.valueOf(AD_TYPES);
        } else if (params[2].isArray() && int.class.equals(params[2].getComponentType())) {
            args[2] = new int[] { Appodeal.INTERSTITIAL, Appodeal.REWARDED_VIDEO };
        } else {
            args[2] = AD_TYPES;
        }

        if (params.length == 4) {
            args[3] = createInitializationCallback(params[3]);
        }

        return args;
    }

    private static Object createInitializationCallback(final Class<?> callbackType) {
        if (callbackType == null) {
            return null;
        }

        if (callbackType.isInterface()) {
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String methodName = method != null ? method.getName() : "";
                    if ("onInitializationFinished".equals(methodName) || "onInitialized".equals(methodName)) {
                        boolean success = true;
                        String reason = null;

                        if (args != null && args.length > 0) {
                            Object payload = args[0];
                            if (payload instanceof List) {
                                List<?> errors = (List<?>) payload;
                                success = errors == null || errors.isEmpty();
                                if (!success) {
                                    reason = String.valueOf(errors.get(0));
                                }
                            } else if (payload != null) {
                                String text = String.valueOf(payload);
                                if (!"null".equalsIgnoreCase(text) && text.length() > 0) {
                                    success = false;
                                    reason = text;
                                }
                            }
                        }

                        if (!success && isRecoverableInitializationReason(reason)) {
                            Log.w(TAG, "initialize callback non-fatal warning: " + reason);
                            success = true;
                        }

                        Log.i(TAG, "initialize callback finished: success=" + success + ", reason=" + reason);
                        notifyInitOnce(success, reason);
                        if (success) {
                            warmUpCacheNow("initialize_callback");
                        }
                    } else if ("onInitializationFailed".equals(methodName)) {
                        String reason = "init_failed";
                        if (args != null && args.length > 0 && args[0] != null) {
                            reason = String.valueOf(args[0]);
                        }
                        if (isRecoverableInitializationReason(reason)) {
                            Log.w(TAG, "initialize callback failed with non-fatal warning: " + reason);
                            notifyInitOnce(true, reason);
                            warmUpCacheNow("initialize_failed_non_fatal");
                        } else {
                            Log.e(TAG, "initialize callback failed: " + reason);
                            notifyInitOnce(false, reason);
                        }
                    }

                    return getDefaultReturnValue(method != null ? method.getReturnType() : null);
                }
            };

            return Proxy.newProxyInstance(
                callbackType.getClassLoader(),
                new Class<?>[] { callbackType },
                handler
            );
        }

        try {
            return callbackType.getDeclaredConstructor().newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getDefaultReturnValue(Class<?> returnType) {
        if (returnType == null || Void.TYPE.equals(returnType)) {
            return null;
        }
        if (Boolean.TYPE.equals(returnType)) {
            return Boolean.FALSE;
        }
        if (Byte.TYPE.equals(returnType)) {
            return Byte.valueOf((byte) 0);
        }
        if (Short.TYPE.equals(returnType)) {
            return Short.valueOf((short) 0);
        }
        if (Integer.TYPE.equals(returnType)) {
            return Integer.valueOf(0);
        }
        if (Long.TYPE.equals(returnType)) {
            return Long.valueOf(0L);
        }
        if (Float.TYPE.equals(returnType)) {
            return Float.valueOf(0f);
        }
        if (Double.TYPE.equals(returnType)) {
            return Double.valueOf(0d);
        }
        if (Character.TYPE.equals(returnType)) {
            return Character.valueOf('\0');
        }
        return null;
    }

    private static boolean isRecoverableInitializationReason(String reason) {
        if (reason == null) {
            return false;
        }

        String normalized = reason.toLowerCase(Locale.US);
        return normalized.contains("sdkconfigurationerror")
            || normalized.contains("adapters are not registered");
    }

    private static Throwable unwrapInvocationError(Throwable throwable) {
        if (throwable instanceof InvocationTargetException) {
            Throwable cause = ((InvocationTargetException) throwable).getCause();
            if (cause != null) {
                return cause;
            }
        }
        Throwable cause = throwable.getCause();
        return cause != null ? cause : throwable;
    }

    private static void preconfigureConsentState(Activity activity) {
        try {
            logConsentApiOnce();
            Object status = resolveConsentStatusValue();
            boolean applied = false;

            applied |= tryApplyConsentOnAppodeal(activity, status);
            applied |= tryApplyConsentOnConsentManager(status);
            applied |= tryApplyLegacyConsentInformation(status);

            if (applied) {
                Log.i(TAG, "consent preconfigured");
            } else {
                Log.w(TAG, "consent preconfigure did not find compatible API");
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "consent preconfigure skipped: " + throwable.getClass().getSimpleName());
        }
    }

    private static void logConsentApiOnce() {
        if (!sConsentApiLogged.compareAndSet(false, true)) {
            return;
        }

        try {
            for (Method method : Appodeal.class.getMethods()) {
                String name = method.getName();
                String lower = name.toLowerCase(Locale.US);
                if (lower.contains("consent") || lower.contains("lgpd")) {
                    Log.i(TAG, "consent api Appodeal." + method.toString());
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> consentManagerClass = Class.forName("com.appodeal.consent.ConsentManager");
            for (Method method : consentManagerClass.getMethods()) {
                String name = method.getName();
                if (name.toLowerCase(Locale.US).contains("consent")) {
                    Log.i(TAG, "consent api ConsentManager." + method.toString());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object resolveConsentStatusValue() {
        try {
            Class<?> consentStatusClass = Class.forName("com.appodeal.consent.ConsentStatus");
            if (!consentStatusClass.isEnum()) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumClass = (Class<? extends Enum>) consentStatusClass.asSubclass(Enum.class);

            String[] preferred = new String[] {
                "NotRequired",
                "NOT_REQUIRED",
                "NOTREQUIRED",
                "Obtained",
                "OBTAINED",
                "Granted",
                "GRANTED"
            };

            for (String name : preferred) {
                try {
                    return Enum.valueOf(enumClass, name);
                } catch (IllegalArgumentException ignored) {
                }
            }

            Object[] constants = enumClass.getEnumConstants();
            if (constants != null && constants.length > 0) {
                return constants[0];
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static boolean tryApplyConsentOnAppodeal(Activity activity, Object status) {
        boolean applied = false;

        Method[] methods = Appodeal.class.getMethods();
        for (Method method : methods) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            String name = method.getName().toLowerCase(Locale.US);
            if (!name.startsWith("set")) {
                continue;
            }
            if (!(name.contains("consent") || name.contains("lgpd"))) {
                continue;
            }

            Object[] args = buildConsentArgs(method.getParameterTypes(), activity, status);
            if (args == null) {
                continue;
            }

            try {
                method.invoke(null, args);
                Log.i(TAG, "consent preconfigure via Appodeal." + method.getName());
                applied = true;
            } catch (Throwable ignored) {
            }
        }

        return applied;
    }

    private static boolean tryApplyConsentOnConsentManager(Object status) {
        try {
            Class<?> consentManagerClass = Class.forName("com.appodeal.consent.ConsentManager");
            Object consentManager = obtainConsentManagerInstance(consentManagerClass);
            boolean applied = false;

            for (Method method : consentManagerClass.getMethods()) {
                String name = method.getName().toLowerCase(Locale.US);
                if (!name.startsWith("set") || !name.contains("consent")) {
                    continue;
                }

                Object[] args = buildConsentArgs(method.getParameterTypes(), null, status);
                if (args == null) {
                    continue;
                }

                boolean isStatic = Modifier.isStatic(method.getModifiers());
                if (!isStatic && consentManager == null) {
                    continue;
                }

                try {
                    method.invoke(isStatic ? null : consentManager, args);
                    Log.i(TAG, "consent preconfigure via ConsentManager." + method.getName());
                    applied = true;
                } catch (Throwable ignored) {
                }
            }

            return applied;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryApplyLegacyConsentInformation(Object status) {
        if (status == null) {
            return false;
        }

        try {
            Class<?> consentManagerClass = Class.forName("com.appodeal.consent.ConsentManager");
            Object consentManager = obtainConsentManagerInstance(consentManagerClass);
            if (consentManager == null) {
                return false;
            }

            Class<?> consentInfoClass = Class.forName("com.appodeal.consent.ConsentInformation");
            Object consentInfo = null;

            try {
                Class<?> statusInfoClass = Class.forName("com.appodeal.consent.b");
                consentInfo = newInstanceForStatus(statusInfoClass, status);
            } catch (Throwable ignored) {
            }

            if (consentInfo == null) {
                consentInfo = newInstanceForStatus(consentInfoClass, status);
            }

            if (consentInfo == null) {
                return false;
            }

            Method setConsentInfoMethod = null;
            try {
                setConsentInfoMethod = consentManagerClass.getMethod(
                    "setConsentInformation$consent_release",
                    consentInfoClass
                );
            } catch (NoSuchMethodException ignored) {
                for (Method method : consentManagerClass.getMethods()) {
                    if (method.getName().toLowerCase(Locale.US).contains("setconsentinformation")
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isAssignableFrom(consentInfo.getClass())) {
                        setConsentInfoMethod = method;
                        break;
                    }
                }
            }

            if (setConsentInfoMethod == null) {
                return false;
            }

            setConsentInfoMethod.invoke(consentManager, consentInfo);
            Log.i(TAG, "consent preconfigure via legacy ConsentInformation");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object obtainConsentManagerInstance(Class<?> consentManagerClass) {
        try {
            Field instanceField = consentManagerClass.getField("INSTANCE");
            Object value = instanceField.get(null);
            if (value != null) {
                return value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Constructor<?> ctor = consentManagerClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static Object[] buildConsentArgs(Class<?>[] params, Activity activity, Object status) {
        if (params == null) {
            return null;
        }

        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> param = params[i];

            if (activity != null && Activity.class.isAssignableFrom(param)) {
                args[i] = activity;
                continue;
            }
            if (activity != null && Context.class.isAssignableFrom(param)) {
                args[i] = activity.getApplicationContext();
                continue;
            }
            if (status != null && param.isAssignableFrom(status.getClass())) {
                args[i] = status;
                continue;
            }
            if (param.isEnum()) {
                Object enumValue = resolveEnumValue(param);
                if (enumValue == null) {
                    return null;
                }
                args[i] = enumValue;
                continue;
            }
            if (Boolean.TYPE.equals(param) || Boolean.class.equals(param)) {
                args[i] = Boolean.TRUE;
                continue;
            }
            if (Integer.TYPE.equals(param) || Integer.class.equals(param)) {
                args[i] = Integer.valueOf(1);
                continue;
            }
            if (Long.TYPE.equals(param) || Long.class.equals(param)) {
                args[i] = Long.valueOf(1L);
                continue;
            }
            if (String.class.equals(param)) {
                args[i] = "granted";
                continue;
            }

            return null;
        }

        return args;
    }

    private static Object resolveEnumValue(Class<?> enumClass) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Enum> typed = (Class<? extends Enum>) enumClass.asSubclass(Enum.class);

            String[] preferred = new String[] {
                "NotRequired",
                "NOT_REQUIRED",
                "NOTREQUIRED",
                "Obtained",
                "OBTAINED",
                "Granted",
                "GRANTED",
                "True",
                "TRUE"
            };

            for (String name : preferred) {
                try {
                    return Enum.valueOf(typed, name);
                } catch (IllegalArgumentException ignored) {
                }
            }

            Object[] constants = typed.getEnumConstants();
            if (constants != null && constants.length > 0) {
                return constants[0];
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static Object newInstanceForStatus(Class<?> clazz, Object status) {
        if (clazz == null || status == null) {
            return null;
        }

        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(status.getClass());
            ctor.setAccessible(true);
            return ctor.newInstance(status);
        } catch (Throwable ignored) {
        }

        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(status.getClass())) {
                try {
                    ctor.setAccessible(true);
                    return ctor.newInstance(status);
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    private static void applyLogLevel(String level) {
        if (level == null) {
            return;
        }

        String normalized = level.trim().toLowerCase(Locale.US);
        String enumName;
        if ("debug".equals(normalized)) {
            enumName = "DEBUG";
        } else if ("verbose".equals(normalized)) {
            enumName = "VERBOSE";
        } else {
            enumName = "NONE";
        }

        // Appodeal log level API has changed between SDK versions; use reflection for compatibility.
        try {
            Class<?> logLevelClass = Class.forName("com.appodeal.ads.LogLevel");
            Method setLogLevelMethod = Appodeal.class.getMethod("setLogLevel", logLevelClass);
            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumClass = (Class<? extends Enum>) logLevelClass.asSubclass(Enum.class);
            Object enumValue = Enum.valueOf(enumClass, enumName);
            setLogLevelMethod.invoke(null, enumValue);
            return;
        } catch (Throwable ignored) {
        }

        try {
            Method setLogLevelStringMethod = Appodeal.class.getMethod("setLogLevel", String.class);
            setLogLevelStringMethod.invoke(null, normalized);
        } catch (Throwable ignored) {
        }
    }

    private static void configureAutoCache() {
        try {
            Method setAutoCacheMethod = Appodeal.class.getMethod("setAutoCache", int.class, boolean.class);
            setAutoCacheMethod.invoke(null, Appodeal.INTERSTITIAL, Boolean.TRUE);
            setAutoCacheMethod.invoke(null, Appodeal.REWARDED_VIDEO, Boolean.TRUE);
            Log.i(TAG, "auto_cache configured");
        } catch (Throwable ignored) {
        }
    }

    private static void warmUpCacheNow(String reason) {
        try {
            invokeCacheForType(Appodeal.INTERSTITIAL);
            invokeCacheForType(Appodeal.REWARDED_VIDEO);
            Log.i(TAG, "cache warmup requested: " + reason);
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleCacheWarmup(final String reason) {
        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                warmUpCacheNow(reason);
            }
        }, CACHE_RETRY_DELAY_MS);
    }

    private static void invokeCacheForType(int adType) {
        try {
            Method cacheMethod = Appodeal.class.getMethod("cache", int.class);
            cacheMethod.invoke(null, Integer.valueOf(adType));
            return;
        } catch (Throwable ignored) {
        }

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        try {
            Method cacheMethod = Appodeal.class.getMethod("cache", Context.class, int.class);
            cacheMethod.invoke(null, activity, Integer.valueOf(adType));
        } catch (Throwable ignored) {
        }
    }

    private static void logAdapterProbeOnce(Activity activity) {
        if (!sAdaptersProbeLogged.compareAndSet(false, true)) {
            return;
        }

        logAdapterClassProbe();
        logAdapterAssetsProbe(activity);
    }

    private static void logAdapterClassProbe() {
        logClassAvailability("com.appodeal.ads.adapters.appodeal.AppodealNativeNetwork$builder");
        logClassAvailability("com.appodeal.ads.adapters.mraid.MraidNetwork$builder");
        logClassAvailability("com.appodeal.ads.adapters.vast.VASTNetwork$builder");
        logClassAvailability("com.appodeal.ads.unified.mraid.UnifiedMraidInterstitial");
        logClassAvailability("com.appodeal.ads.unified.vast.UnifiedVastVideo");
    }

    private static void logClassAvailability(String className) {
        try {
            Class.forName(className);
            Log.i(TAG, "adapter class ok: " + className);
        } catch (Throwable throwable) {
            Log.w(TAG, "adapter class missing: " + className + ", reason=" + throwable.getClass().getSimpleName());
        }
    }

    private static void logAdapterAssetsProbe(Activity activity) {
        if (activity == null) {
            Log.w(TAG, "adapter assets probe skipped: activity_is_null");
            return;
        }

        try {
            AssetManager assets = activity.getAssets();
            String[] files = assets.list("apd_adapters");
            if (files == null || files.length == 0) {
                Log.w(TAG, "adapter assets missing: apd_adapters");
                return;
            }

            for (String file : files) {
                Log.i(TAG, "adapter asset found: apd_adapters/" + file);
                logAssetFirstLine(assets, "apd_adapters/" + file);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "adapter assets probe failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static void logAssetFirstLine(AssetManager assets, String path) {
        InputStream stream = null;
        BufferedReader reader = null;
        try {
            stream = assets.open(path);
            reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            String firstLine = reader.readLine();
            if (firstLine != null) {
                Log.i(TAG, "adapter asset payload: " + firstLine);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static Activity getActivity() {
        Activity activity = tryGetDefoldActivity("com.dynamo.android.DefoldActivity");
        if (activity != null) {
            return activity;
        }
        activity = tryGetDefoldActivity("com.defold.android.DefoldActivity");
        if (activity != null) {
            return activity;
        }
        return tryGetFromActivityThread();
    }

    private static Activity tryGetDefoldActivity(String className) {
        try {
            ClassLoader classLoader = AppodealBridge.class.getClassLoader();
            Class<?> clazz = Class.forName(className, false, classLoader);
            Method method;
            try {
                method = clazz.getMethod("getActivity");
            } catch (NoSuchMethodException noPublicMethod) {
                method = clazz.getDeclaredMethod("getActivity");
                method.setAccessible(true);
            }
            Object value = method.invoke(null);
            if (value instanceof Activity) {
                return (Activity) value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Activity tryGetFromActivityThread() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentActivityThreadMethod.setAccessible(true);
            Object activityThread = currentActivityThreadMethod.invoke(null);
            if (activityThread == null) {
                return null;
            }

            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesObject = activitiesField.get(activityThread);
            if (!(activitiesObject instanceof Map)) {
                return null;
            }

            Map<?, ?> activities = (Map<?, ?>) activitiesObject;
            for (Object record : activities.values()) {
                if (record == null) {
                    continue;
                }

                Class<?> recordClass = record.getClass();
                Field activityField = recordClass.getDeclaredField("activity");
                activityField.setAccessible(true);
                Object activity = activityField.get(record);
                if (activity instanceof Activity) {
                    return (Activity) activity;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void runOnMainThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            MAIN_HANDLER.post(runnable);
        }
    }

    private static void runOnUiThread(Activity activity, Runnable runnable) {
        if (activity == null || runnable == null) {
            return;
        }
        activity.runOnUiThread(runnable);
    }

    private static native void nativeOnInit(boolean success, String reason);
    private static native void nativeOnInterstitialEvent(String event, boolean success, String reason);
    private static native void nativeOnRewardedEvent(
        String event,
        boolean success,
        String reason,
        boolean rewarded,
        double amount,
        String currency
    );
}
