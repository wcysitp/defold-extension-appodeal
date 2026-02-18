package com.defold.appodeal;

import android.app.Activity;
import android.util.Log;
import com.appodeal.ads.Appodeal;
import com.appodeal.ads.InitializationListener;
import com.appodeal.ads.InterstitialCallbacks;
import com.appodeal.ads.LogLevel;
import com.appodeal.ads.RewardedVideoCallbacks;
import com.dynamo.android.DefoldActivity;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppodealBridge {
    private static final String TAG = "DefoldAppodeal";
    private static final int AD_TYPES = Appodeal.INTERSTITIAL | Appodeal.REWARDED_VIDEO;
    private static final AtomicBoolean sCallbacksConfigured = new AtomicBoolean(false);

    private AppodealBridge() {
    }

    public static boolean initialize(final String appKey, final boolean testing, final String logLevel) {
        Log.i(TAG, "initialize called");
        if (appKey == null || appKey.trim().isEmpty()) {
            Log.e(TAG, "initialize failed: app_key_is_empty");
            nativeOnInit(false, "app_key_is_empty");
            return false;
        }

        final Activity activity = getActivity();
        if (activity == null) {
            Log.e(TAG, "initialize failed: activity_is_null");
            nativeOnInit(false, "activity_is_null");
            return false;
        }

        runOnUiThread(activity, new Runnable() {
            @Override
            public void run() {
                try {
                    configureCallbacks();
                    applyLogLevel(logLevel);
                    Appodeal.setTesting(testing);
                    Appodeal.initialize(activity, appKey, AD_TYPES, new InitializationListener() {
                        @Override
                        public void onInitializationFinished(List<?> errors) {
                            if (errors == null || errors.isEmpty()) {
                                Log.i(TAG, "initialize success");
                                nativeOnInit(true, null);
                            } else {
                                Log.e(TAG, "initialize failed: " + errors);
                                nativeOnInit(false, "init_errors:" + errors.toString());
                            }
                        }
                    });
                } catch (Throwable throwable) {
                    Log.e(TAG, "initialize failed with exception", throwable);
                    nativeOnInit(false, throwable.getClass().getSimpleName() + ":" + throwable.getMessage());
                }
            }
        });

        return true;
    }

    public static boolean isInterstitialAvailable() {
        try {
            return Appodeal.isLoaded(Appodeal.INTERSTITIAL);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean showInterstitial() {
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
                    boolean canShow = Appodeal.canShow(Appodeal.INTERSTITIAL);
                    if (canShow) {
                        Log.i(TAG, "showInterstitial: canShow=true");
                        Appodeal.show(activity, Appodeal.INTERSTITIAL);
                    } else {
                        Log.w(TAG, "showInterstitial: interstitial_not_available");
                        nativeOnInterstitialEvent("show_failed", false, "interstitial_not_available");
                    }
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
                    boolean canShow = Appodeal.canShow(Appodeal.REWARDED_VIDEO);
                    if (canShow) {
                        Log.i(TAG, "showRewarded: canShow=true");
                        Appodeal.show(activity, Appodeal.REWARDED_VIDEO);
                    } else {
                        Log.w(TAG, "showRewarded: rewarded_not_available");
                        nativeOnRewardedEvent("show_failed", false, "rewarded_not_available", false, 0.0d, null);
                    }
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
                nativeOnRewardedEvent("failed_to_load", false, "failed_to_load", false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoShowFailed() {
                Log.w(TAG, "rewarded show_failed");
                nativeOnRewardedEvent("show_failed", false, "show_failed", false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoShown() {
                nativeOnRewardedEvent("shown", true, null, false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoClicked() {
                nativeOnRewardedEvent("clicked", true, null, false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoFinished(double amount, String name) {
                Log.i(TAG, "rewarded reward amount=" + amount + " currency=" + name);
                nativeOnRewardedEvent("reward", true, null, true, amount, name);
            }

            @Override
            public void onRewardedVideoClosed(boolean finished) {
                nativeOnRewardedEvent("closed", true, null, finished, 0.0d, null);
            }

            @Override
            public void onRewardedVideoExpired() {
                nativeOnRewardedEvent("expired", false, "expired", false, 0.0d, null);
            }
        });
    }

    private static void applyLogLevel(String level) {
        if (level == null) {
            return;
        }

        String normalized = level.trim().toLowerCase(Locale.US);
        if ("debug".equals(normalized)) {
            Appodeal.setLogLevel(LogLevel.debug);
        } else if ("verbose".equals(normalized)) {
            Appodeal.setLogLevel(LogLevel.verbose);
        } else if ("none".equals(normalized)) {
            Appodeal.setLogLevel(LogLevel.none);
        }
    }

    private static Activity getActivity() {
        return DefoldActivity.getActivity();
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
