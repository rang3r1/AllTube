package com.liskovsoft.smartyoutubetv2.mobile.ui.signin;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.googlecommon.common.models.auth.UserCode;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.auth.V2.AuthService;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.liskovsoft.youtubeapi.service.internal.YouTubeAccountManager;

import java.lang.reflect.Method;

import io.reactivex.Observable;

/**
 * Device-code sign-in like upstream's {@code YouTubeAccountManager.signInObserve()}, but the 3 s wait
 * between polls can be cut short with {@link #pollNow()}. Android freezes the app while the browser is in
 * front, so upstream's sleep loop noticed the approval only up to ~8 s after the user came back.
 * The token is stored through upstream's own private {@code persistRefreshToken} (accounts, selection,
 * persistence). If upstream renames it, {@link #observe()} falls back to upstream's flow unchanged.
 */
final class DeviceCodeSignIn {
    private static final String TAG = DeviceCodeSignIn.class.getSimpleName();
    private static final long POLL_INTERVAL_MS = 3_000;
    private static final int POLL_ATTEMPTS = 200;

    private final Object mLock = new Object();
    private boolean mPollNow;

    /** Emits the user code, completes once the account is stored. */
    Observable<String> observe() {
        Method persist = persistMethod();
        if (persist == null) {
            Log.e(TAG, "persistRefreshToken not found, using upstream sign-in without fast poll");
            return YouTubeSignInService.instance().signInObserve();
        }

        return RxHelper.createLong(emitter -> {
            AuthService auth = AuthService.instance();
            UserCode userCode = auth.getUserCode();
            if (userCode == null) {
                RxHelper.onError(emitter, "User code result is empty");
                return;
            }
            emitter.onNext(userCode.getUserCode());

            try {
                for (int i = 0; i < POLL_ATTEMPTS; i++) {
                    awaitNextPoll(POLL_INTERVAL_MS);
                    AccessToken token = auth.getAccessToken(userCode.getDeviceCode());
                    if (token != null && token.getRefreshToken() != null) {
                        persist.invoke(YouTubeAccountManager.instance(YouTubeSignInService.instance()), token.getRefreshToken());
                        emitter.onComplete();
                        return;
                    }
                }
                RxHelper.onError(emitter, "Refresh token is empty");
            } catch (InterruptedException e) {
                // Disposed
            } catch (ReflectiveOperationException e) {
                Log.e(TAG, "Storing the account failed: %s", e);
                RxHelper.onError(emitter, "Storing the account failed: " + e);
            }
        });
    }

    /** Ends the current wait, e.g. when the user returns from the browser. */
    void pollNow() {
        synchronized (mLock) {
            mPollNow = true;
            mLock.notifyAll();
        }
    }

    void awaitNextPoll(long timeoutMs) throws InterruptedException {
        synchronized (mLock) {
            if (!mPollNow) {
                mLock.wait(timeoutMs);
            }
            mPollNow = false;
        }
    }

    @Nullable
    static Method persistMethod() {
        try {
            Method m = YouTubeAccountManager.class.getDeclaredMethod("persistRefreshToken", String.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }
}
