package com.liskovsoft.smartyoutubetv2.mobile.ui.base;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.lang.ref.WeakReference;

/**
 * Touch feedback for the phone UI: haptics, the small "pop" on toggles, the loading placeholder
 * and the card-to-player launch animation. Phone flavor only; upstream and TV code never call it.
 *
 * Haptics go through {@link View#performHapticFeedback}, so the system "Touch feedback" setting
 * is respected without any setting of our own.
 */
public final class Motion {
    /** A card tap older than this didn't start the player (e.g. the user opened a menu instead). */
    private static final long LAUNCH_VIEW_MAX_AGE_MS = 1_500;
    private static WeakReference<View> sLaunchView = new WeakReference<>(null);
    private static long sLaunchViewMs;

    private Motion() {
    }

    /** Confirming tap: like, subscribe, bell, tabs. */
    public static void tap(@Nullable View view) {
        if (view != null) {
            view.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /** Light tick: seek steps, a gesture hitting its limit. */
    public static void tick(@Nullable View view) {
        if (view != null) {
            view.performHapticFeedback(Build.VERSION.SDK_INT >= 21
                    ? HapticFeedbackConstants.CLOCK_TICK : HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /** Haptic plus a short bounce: the toggle visibly reacts before the server answers. */
    public static void pop(@Nullable View view) {
        if (view == null) {
            return;
        }
        tap(view);
        view.animate().cancel();
        view.setScaleX(0.85f);
        view.setScaleY(0.85f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    /** Remember the tapped card so the player can grow out of it ({@link #launchOptions}). */
    public static void rememberLaunch(@Nullable View card) {
        sLaunchView = new WeakReference<>(card);
        sLaunchViewMs = SystemClock.elapsedRealtime();
    }

    /** Options that let the next activity grow out of the last tapped card, or null. */
    @Nullable
    public static Bundle launchOptions() {
        View card = sLaunchView.get();
        sLaunchView = new WeakReference<>(null);
        boolean fresh = SystemClock.elapsedRealtime() - sLaunchViewMs < LAUNCH_VIEW_MAX_AGE_MS;
        if (card == null || !fresh || card.getWindowToken() == null || card.getWidth() == 0) {
            return null;
        }
        return ActivityOptions.makeScaleUpAnimation(card, 0, 0, card.getWidth(), card.getHeight()).toBundle();
    }

    /**
     * Loading state of a list screen: placeholder cards while nothing is shown yet, the small
     * spinner while more is loading below existing content, nothing when done.
     */
    public static void showLoading(@Nullable View spinner, @Nullable View skeleton, @Nullable View content,
                                   boolean loading, boolean empty, boolean swipeRefreshing) {
        boolean showSkeleton = loading && empty && !swipeRefreshing;
        if (spinner != null) {
            spinner.setVisibility(loading && !empty && !swipeRefreshing ? View.VISIBLE : View.GONE);
        }
        if (skeleton != null) {
            boolean wasShown = skeleton.getVisibility() == View.VISIBLE;
            pulse(skeleton, showSkeleton);
            // Content replacing the placeholder comes in item by item.
            if (wasShown && !showSkeleton && !empty && content instanceof ViewGroup) {
                ViewGroup list = (ViewGroup) content;
                list.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(list.getContext(), R.anim.mobile_list_in));
                list.scheduleLayoutAnimation();
            }
        }
    }

    private static void pulse(View skeleton, boolean on) {
        Object running = skeleton.getTag(skeleton.getId());
        if (on) {
            if (running == null) {
                skeleton.animate().cancel(); // a fade-out still running from the last hide
                skeleton.setAlpha(1f);
                skeleton.setVisibility(View.VISIBLE);
                ObjectAnimator anim = ObjectAnimator.ofFloat(skeleton, View.ALPHA, 1f, 0.45f);
                anim.setDuration(800);
                anim.setRepeatMode(ValueAnimator.REVERSE);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.start();
                skeleton.setTag(skeleton.getId(), anim);
            }
        } else if (running != null || skeleton.getVisibility() == View.VISIBLE) {
            if (running != null) {
                ((ObjectAnimator) running).cancel();
                skeleton.setTag(skeleton.getId(), null);
            }
            // Fade out so the arriving content doesn't pop in.
            skeleton.animate().cancel();
            skeleton.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                skeleton.setVisibility(View.GONE);
                skeleton.setAlpha(1f);
            }).start();
        }
    }
}
