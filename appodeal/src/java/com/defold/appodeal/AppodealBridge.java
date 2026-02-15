package com.defold.appodeal;

import android.app.Activity;
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
    private static final int AD_TYPES = Appodeal.INTERSTITIAL | Appodeal.REWARDED_VIDEO;
    private static final AtomicBoolean sCallbacksConfigured = new AtomicBoolean(false);

    private AppodealBridge() {
    }

    public static boolean initialize(final String appKey, final boolean testing, final String logLevel) {
        if (appKey == null || appKey.trim().isEmpty()) {
            nativeOnInit(false, "app_key_is_empty");
            return false;
        }

        final Activity activity = getActivity();
        if (activity == null) {
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
                                nativeOnInit(true, null);
                            } else {
                                nativeOnInit(false, "init_errors:" + errors.toString());
                            }
                        }
                    });
                } catch (Throwable throwable) {
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
            nativeOnInterstitialEvent("show_failed", false, "activity_is_null");
            return false;
        }

        runOnUiThread(activity, new Runnable() {
            @Override
            public void run() {
                try {
                    if (Appodeal.canShow(Appodeal.INTERSTITIAL)) {
                        Appodeal.show(activity, Appodeal.INTERSTITIAL);
                    } else {
                        nativeOnInterstitialEvent("show_failed", false, "interstitial_not_available");
                    }
                } catch (Throwable throwable) {
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
            nativeOnRewardedEvent("show_failed", false, "activity_is_null", false, 0.0d, null);
            return false;
        }

        runOnUiThread(activity, new Runnable() {
            @Override
            public void run() {
                try {
                    if (Appodeal.canShow(Appodeal.REWARDED_VIDEO)) {
                        Appodeal.show(activity, Appodeal.REWARDED_VIDEO);
                    } else {
                        nativeOnRewardedEvent("show_failed", false, "rewarded_not_available", false, 0.0d, null);
                    }
                } catch (Throwable throwable) {
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
                nativeOnInterstitialEvent("loaded", true, null);
            }

            @Override
            public void onInterstitialFailedToLoad() {
                nativeOnInterstitialEvent("failed_to_load", false, "failed_to_load");
            }

            @Override
            public void onInterstitialShowFailed() {
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
                nativeOnRewardedEvent("loaded", true, null, false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoFailedToLoad() {
                nativeOnRewardedEvent("failed_to_load", false, "failed_to_load", false, 0.0d, null);
            }

            @Override
            public void onRewardedVideoShowFailed() {
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
