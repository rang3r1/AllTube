package com.liskovsoft.smartyoutubetv2.mobile.ui.signin;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.fragment.app.Fragment;

import java.util.Arrays;
import java.util.List;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.MobileBrowseActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs.MobileAppDialogActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.disposables.Disposable;

/**
 * Single-phone sign-in screen. Implements {@link SignInView} and runs the OAuth device-code flow
 * itself through {@link DeviceCodeSignIn}, which mirrors {@link YTSignInPresenter} but polls again
 * right away when the user comes back from the browser.
 *
 * The device-code flow is mandatory — it is the only flow that yields InnerTube-compatible
 * credentials, and Google blocks OAuth inside embedded WebViews. To make it seamless on a
 * single phone, the code is pre-filled into the URL and opened in an in-app Chrome Custom
 * Tab: the user signs in and approves without leaving the app, then the presenter's poll
 * detects completion and closes this screen automatically.
 */
public class MobileSignInFragment extends Fragment implements SignInView {
    private static final String TAG = MobileSignInFragment.class.getSimpleName();
    // Copied from YTSignInPresenter, private there
    private static final String SIGN_IN_URL = "https://yt.be/activate";
    private static final String QR_SIGN_IN_URL = "https://youtube.com/qr/activate/";
    private final DeviceCodeSignIn mSignIn = new DeviceCodeSignIn();
    private Disposable mSignInAction;
    private View mWaitHint;
    private View mTitleView;
    private View mInstructionsView;
    private View mCodeLabelView;
    private TextView mCodeView;
    private Button mBrowserButton;
    private Button mDoneButton;
    private LinearLayout mErrorBlock;
    private TextView mErrorBody;
    private boolean mBrowserLaunched;
    private String mFullSignInUrl;

    // Bounded auto-retry of the user-code fetch. The device-code flow occasionally errors
    // on a transient OS-resolver miss (UnknownHostException) — the app's network stack is
    // fine (Home loads), it's a momentary DNS blip. Rather than dump the user straight to
    // the "Sign-in failed" screen, silently re-arm the flow a few times with a short
    // backoff first. Only applies BEFORE the browser is launched (a pre-approval failure to
    // even get a code); errors after that fall through to the manual error screen.
    private static final int MAX_AUTO_RETRIES = 3;
    private static final long AUTO_RETRY_DELAY_MS = 2000;
    private int mAutoRetries;
    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mobile_signin_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        });

        mTitleView = view.findViewById(R.id.signin_title);
        mInstructionsView = view.findViewById(R.id.signin_instructions);
        mCodeLabelView = view.findViewById(R.id.signin_code_label);
        mCodeView = view.findViewById(R.id.signin_code);
        mBrowserButton = view.findViewById(R.id.signin_browser_button);
        mDoneButton = view.findViewById(R.id.signin_done_button);
        mErrorBlock = view.findViewById(R.id.signin_error_block);
        mErrorBody = view.findViewById(R.id.signin_error_body);
        mWaitHint = view.findViewById(R.id.signin_wait_hint);

        mBrowserButton.setEnabled(false);
        mBrowserButton.setOnClickListener(v -> openInBrowser());
        mDoneButton.setOnClickListener(v -> returnToApp());
        mCodeView.setOnClickListener(v -> copyCode());
        view.findViewById(R.id.signin_retry_button).setOnClickListener(v -> retrySignIn());

        startSignIn();
    }

    /** For when Google's page asks for the code instead of taking it from the link. */
    private void copyCode() {
        CharSequence code = mCodeView.getText();
        if (TextUtils.isEmpty(code) || getContext() == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.mobile_signin_code_label), code));
        // Android 13+ confirms clipboard writes itself
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(getContext(), R.string.mobile_signin_code_copied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Back from the browser: the app was frozen meanwhile, so check the approval now
        if (mBrowserLaunched) {
            mSignIn.pollNow();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        RxHelper.disposeActions(mSignInAction);
    }

    /** Same callbacks as YTSignInPresenter.updateUserCode; a new call requests a fresh code. */
    private void startSignIn() {
        RxHelper.disposeActions(mSignInAction);
        mSignInAction = mSignIn.observe().subscribe(
                userCode -> {
                    String url = mSignIn.getVerificationUrl();
                    showCode(userCode, SIGN_IN_URL, url != null
                            ? url + "?user_code=" + userCode.replace(" ", "-")
                            : QR_SIGN_IN_URL + userCode.replace(" ", "-"));
                },
                error -> {
                    Log.e(TAG, "Sign in error: %s", error.getMessage());
                    showCode(error.getMessage(), "");
                },
                () -> {
                    Context context = getContext();
                    close();
                    if (context != null) {
                        AccountSelectionPresenter.instance(context).show(true);
                    }
                });
    }

    @Override
    public void showCode(String userCode, String signInUrl) {
        showCode(userCode, signInUrl, null);
    }

    @Override
    public void showCode(String userCode, String signInUrl, String fullSignInUrl) {
        if (!isAdded()) {
            return;
        }

        // YTSignInPresenter routes errors through this same callback with an empty
        // signInUrl and the error message in the userCode slot (see
        // YTSignInPresenter.updateUserCode -> error -> showCode(error.getMessage(), "")).
        if (TextUtils.isEmpty(signInUrl) && fullSignInUrl == null) {
            // Transient failure before the user ever opened the browser (e.g. a momentary
            // DNS miss fetching the user code): silently re-arm a few times before giving
            // up to the manual error screen.
            if (!mBrowserLaunched && mAutoRetries < MAX_AUTO_RETRIES) {
                mAutoRetries++;
                mHandler.postDelayed(this::reArmSignIn, AUTO_RETRY_DELAY_MS);
                return;
            }
            showError(userCode);
            return;
        }
        if (TextUtils.isEmpty(userCode)) {
            return;
        }

        mAutoRetries = 0; // got a code — reset the budget for any later re-arm
        hideError();
        mFullSignInUrl = fullSignInUrl != null
                ? fullSignInUrl
                : signInUrl + "?user_code=" + userCode.replace(" ", "-");
        mCodeView.setText(userCode);
        mBrowserButton.setEnabled(true);
    }

    private void showError(@Nullable String message) {
        String safeMessage = message != null ? message : "";
        mErrorBody.setText(getString(R.string.mobile_signin_error_generic, safeMessage));
        mErrorBlock.setVisibility(View.VISIBLE);

        if (mTitleView != null) mTitleView.setVisibility(View.GONE);
        if (mInstructionsView != null) mInstructionsView.setVisibility(View.GONE);
        if (mCodeLabelView != null) mCodeLabelView.setVisibility(View.GONE);
        mCodeView.setVisibility(View.GONE);
        mBrowserButton.setVisibility(View.GONE);
        mDoneButton.setVisibility(View.GONE);
        mWaitHint.setVisibility(View.GONE);
    }

    private void hideError() {
        if (mErrorBlock != null) mErrorBlock.setVisibility(View.GONE);
        if (mTitleView != null) mTitleView.setVisibility(View.VISIBLE);
        if (mInstructionsView != null) mInstructionsView.setVisibility(View.VISIBLE);
        if (mCodeLabelView != null) mCodeLabelView.setVisibility(View.VISIBLE);
        mCodeView.setVisibility(View.VISIBLE);
        mBrowserButton.setVisibility(View.VISIBLE);
        if (mDoneButton != null) {
            mDoneButton.setVisibility(mBrowserLaunched ? View.VISIBLE : View.GONE);
        }
        mWaitHint.setVisibility(mBrowserLaunched ? View.VISIBLE : View.GONE);
    }

    /**
     * Re-arm the device-code observable from scratch. The previous attempt errored
     * and the observable terminated; calling {@code onViewInitialized()} disposes the
     * dead disposable and calls {@code updateUserCode()} again, yielding a fresh code.
     */
    private void retrySignIn() {
        mAutoRetries = 0; // manual retry restores the auto-retry budget
        mBrowserLaunched = false;
        hideError();
        mBrowserButton.setEnabled(false);
        mCodeView.setText("");
        startSignIn();
    }

    /**
     * Silent re-arm used by the bounded auto-retry: unlike {@link #retrySignIn()} it does
     * not reset the retry budget or touch the (still hidden) error UI — it just re-runs the
     * device-code fetch. Guards on still being added in case the user left during backoff.
     */
    private void reArmSignIn() {
        if (!isAdded()) {
            return;
        }
        startSignIn();
    }

    @Override
    public void close() {
        // Sign-in succeeded. Land on a clean Home: CLEAR_TOP finishes everything stacked
        // above the Home root — this sign-in screen AND any Settings/Accounts dialog the
        // sign-in was launched from — so the account-selection dialog the presenter opens
        // next sits over Home, and dismissing it returns to Home, not a stale Settings page.
        // (returnToApp()'s REORDER_TO_FRONT only reorders and would leave that Settings
        // dialog in the back stack; it stays for the manual "Done" button, which needs to
        // pull our task above the Chrome Custom Tab.)
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        // Right after this returns, YTSignInPresenter force-shows the account-selection
        // dialog (show(true)) — even for a single account. On a fresh single-account
        // sign-in that picker is redundant (the service already auto-selected the new
        // account), and dismissing it strands the user on whatever section Home last
        // showed (Settings). Suppress that one redundant dialog when there's <=1 account;
        // MobileAppDialogActivity consumes the one-shot flag and finishes itself. With 2+
        // accounts we leave the picker so the user can choose.
        if (accountCount() <= 1) {
            MobileAppDialogActivity.suppressNextDialog();
        }

        Intent intent = new Intent(activity, MobileBrowseActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        activity.finish();

        // Force Home onto the Home feed regardless of which section (e.g. Settings) was
        // showing when sign-in began — this is the equivalent of tapping the Home menu
        // item. No-ops safely if Home isn't ready yet; MobileBrowseFragment's
        // autoSelectDefaultSection is the backstop.
        BrowsePresenter.instance(activity).selectSection(MediaGroup.TYPE_HOME);
    }

    /** Read-only count of stored accounts; 0 if the service isn't available. */
    private int accountCount() {
        try {
            List<Account> accounts = YouTubeServiceManager.instance().getSignInService().getAccounts();
            return accounts != null ? accounts.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Bring the app's task to the foreground. The Custom Tab opens YouTube's
     * activation page in its OWN task (Chrome's), so {@code CLEAR_TOP} alone does not
     * cover it — {@code REORDER_TO_FRONT} pulls our task on top of Chrome's.
     */
    private void returnToApp() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, MobileBrowseActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(intent);
    }

    private String resolveCustomTabsBrowser() {
        if (getContext() == null) {
            return null;
        }
        String pkg = CustomTabsClient.getPackageName(getContext(), null);
        if (pkg != null) {
            return pkg;
        }
        List<String> candidates = Arrays.asList(
                "com.android.chrome",
                "com.sec.android.app.sbrowser",
                "com.chrome.beta",
                "com.brave.browser",
                "org.mozilla.firefox");
        return CustomTabsClient.getPackageName(getContext(), candidates);
    }

    /**
     * Opens the activation URL in an in-app Chrome Custom Tab, pinned to a Custom Tabs-
     * supporting browser so Android's intent resolution can't hand youtube.com to the
     * YouTube app's deep-link handler. Falls back to an external browser if no provider
     * is available.
     */
    private void openInBrowser() {
        if (mFullSignInUrl == null || getContext() == null) {
            return;
        }
        try {
            CustomTabsIntent customTabs = new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build();
            String browserPkg = resolveCustomTabsBrowser();
            if (browserPkg != null) {
                customTabs.intent.setPackage(browserPkg);
            }
            customTabs.launchUrl(getContext(), Uri.parse(mFullSignInUrl));
        } catch (Exception e) {
            Utils.openLinkExt(getContext(), mFullSignInUrl);
        }
        mBrowserLaunched = true;
        if (mDoneButton != null) {
            mDoneButton.setVisibility(View.VISIBLE);
        }
        mWaitHint.setVisibility(View.VISIBLE);
    }
}
