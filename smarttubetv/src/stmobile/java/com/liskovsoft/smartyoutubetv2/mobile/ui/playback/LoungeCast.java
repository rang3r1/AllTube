package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.widget.EditText;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * "Play on TV": the sender side of YouTube's "Link with TV code" (lounge protocol), like the
 * official app's cast. Pairs once with the TV code and keeps only the screenId; a fresh lounge
 * token is fetched per session, so pairings never expire. While a session is open, a long-poll
 * thread mirrors the TV's state (position, play state, volume) for the player, which then remotes
 * the TV with its own controls ({@link MobilePlaybackFragment#isCasting()}), like the YouTube app.
 * {@link TvMediaSession} mirrors the same state to the notification shade and lock screen.
 */
final class LoungeCast {
    private static final String TAG = LoungeCast.class.getSimpleName();
    private static final String API = "https://www.youtube.com/api/lounge/";
    private static final String PREFS = "lounge_cast";
    private static final String KEY_SCREEN_ID = "screen_id";
    private static final String KEY_SCREEN_NAME = "screen_name";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SAVED = "saved_tvs";
    static final int STATE_PLAYING = 1;
    static final int STATE_BUFFERING = 3;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static OkHttpClient sClient;

    // Open lounge session; commands run on sExecutor, events arrive on the poll thread.
    private static volatile HttpUrl sBind;
    private static volatile String sSid;
    private static volatile String sGsession;
    private static int sRid;
    private static int sOfs;
    private static volatile long sAid;

    // Last state the TV reported.
    private static volatile float sPositionSec;
    private static volatile float sDurationSec;
    private static volatile long sPositionAtMs;
    private static volatile int sState = -1;
    private static volatile int sVolume = -1;
    private static volatile Runnable sOnChange;
    private static volatile String sVideoId;
    private static volatile String sTitle;
    private static volatile long sCastAtMs;
    private static volatile boolean sStopping;
    private static volatile Context sApp;
    // ponytail: after a cast the TV may still report the previous video for a moment; ignore that
    // for this long. A real switch on the TV inside the window is picked up by the next report.
    private static final long CAST_SETTLE_MS = 5_000;

    private LoungeCast() {
    }

    /** Paired TV name, or null when not paired yet. */
    static String screenName(Context context) {
        return prefs(context).getString(KEY_SCREEN_NAME, null);
    }

    static boolean isConnected() {
        return sSid != null && !sStopping;
    }

    /** Casts the video, asking which TV first when none is paired; the player then remotes it. */
    static void cast(Activity activity, String videoId, int positionSec, Runnable onSuccess) {
        if (prefs(activity).getString(KEY_SCREEN_ID, null) == null) {
            chooseTv(activity, () -> cast(activity, videoId, positionSec, onSuccess));
            return;
        }

        // Known at once, so the player doesn't follow the TV back to the old video meanwhile.
        sVideoId = videoId;
        sTitle = null;
        sCastAtMs = SystemClock.elapsedRealtime();
        boolean wake = !isConnected();
        sStopping = false;
        if (wake) {
            MessageHelpers.showMessage(activity, activity.getString(R.string.mobile_cast_waking, screenName(activity)));
        }
        run(activity, () -> {
            if (wake) {
                wake(activity);
                sCastAtMs = SystemClock.elapsedRealtime();
            }
            command(activity, "setPlaylist", "videoId", videoId, "videoIds", videoId,
                    "currentTime", Integer.toString(positionSec), "currentIndex", "0");
            fetchTitle(videoId);
        }, () -> {
            MessageHelpers.showMessage(activity, activity.getString(R.string.mobile_cast_sent, screenName(activity)));
            onSuccess.run();
        });
    }

    /**
     * Starts YouTube on the TV first, like the official app: a TV showing another app (or in
     * standby) accepts lounge commands but plays nothing. Needs the TV on this Wi-Fi with Cast;
     * a TV linked only by code is left as it is. Matched by name, like {@link #chooseTv}.
     */
    private static void wake(Context context) {
        String name = screenName(context);
        for (TvDiscovery.Tv tv : TvDiscovery.find(context)) {
            if (tv.name.equals(name)) {
                try {
                    TvDiscovery.screenId(tv); // returns once YouTube is up and its lounge is online
                } catch (Exception e) {
                    Log.e(TAG, "Couldn't start YouTube on %s: %s", name, e.getMessage());
                }
                return;
            }
        }
        Log.d(TAG, "%s not found on the Wi-Fi, sending without starting YouTube", name);
    }

    /** Video on the TV (or just sent to it), or null. */
    static String videoId() {
        return sVideoId;
    }

    static void seekTo(Context context, int sec) {
        sPositionSec = Math.max(0, sec);
        sPositionAtMs = SystemClock.elapsedRealtime();
        control(context, "seekTo", "newTime", Integer.toString((int) sPositionSec));
    }

    static void volumeBy(Context context, int delta) {
        setVolume(context, (sVolume < 0 ? 50 : sVolume) + delta);
    }

    static void setVolume(Context context, int volume) {
        sVolume = Math.max(0, Math.min(100, volume));
        control(context, "setVolume", "volume", Integer.toString(sVolume), "muted", "false");
    }

    /** Stops the TV; counts as disconnected at once, so the phone can take over right away. */
    static void stop(Context context) {
        sStopping = true;
        notifyChange();
        run(context, () -> command(context, "stopVideo"), LoungeCast::reset);
    }

    static void control(Context context, String name, String... args) {
        run(context, () -> command(context, name, args), null);
    }

    /** Position the TV is at now, extrapolated from its last report while playing. */
    static int positionSec() {
        float pos = sPositionSec;
        if (sState == STATE_PLAYING) {
            pos += (SystemClock.elapsedRealtime() - sPositionAtMs) / 1000f;
        }
        return (int) (sDurationSec > 0 ? Math.min(pos, sDurationSec) : pos);
    }

    static int durationSec() {
        return (int) sDurationSec;
    }

    static int state() {
        return sState;
    }

    static int volume() {
        return sVolume;
    }

    /** Title of the video on the TV, or null until known. */
    static String title() {
        return sTitle;
    }

    /** Called on the main thread whenever the TV reports a change; null to stop listening. */
    static void setOnChange(Runnable onChange) {
        sOnChange = onChange;
    }

    private interface Task {
        void run() throws Exception;
    }

    private static void run(Context context, Task task, Runnable onSuccess) {
        sApp = context.getApplicationContext();
        sExecutor.execute(() -> {
            try {
                task.run();
                if (onSuccess != null) {
                    main(onSuccess);
                }
            } catch (Exception e) {
                Log.e(TAG, "Cast failed: %s", e.getMessage());
                main(() -> MessageHelpers.showMessage(context, R.string.mobile_cast_failed));
            }
        });
    }

    /** Lists the TVs found on the Wi-Fi plus "Link with TV code", pairs the pick, then runs onPaired. */
    static void chooseTv(Activity activity, Runnable onPaired) {
        sApp = activity.getApplicationContext();
        MessageHelpers.showMessage(activity, R.string.mobile_cast_searching);
        sExecutor.execute(() -> {
            JSONArray saved = savedTvs(activity);
            List<TvDiscovery.Tv> tvs = TvDiscovery.find(activity);
            // A found TV that is already saved needs no new link: list it once, as saved.
            for (int i = 0; i < saved.length(); i++) {
                String name = saved.optJSONObject(i).optString("name");
                for (int j = tvs.size() - 1; j >= 0; j--) {
                    if (tvs.get(j).name.equals(name)) {
                        tvs.remove(j);
                    }
                }
            }

            main(() -> {
                String current = prefs(activity).getString(KEY_SCREEN_ID, null);
                int count = saved.length();
                String[] names = new String[count + tvs.size() + 1];
                for (int i = 0; i < count; i++) {
                    JSONObject tv = saved.optJSONObject(i);
                    names[i] = (tv.optString("id").equals(current) ? "✓ " : "") + tv.optString("name");
                }
                for (int i = 0; i < tvs.size(); i++) {
                    names[count + i] = tvs.get(i).name;
                }
                names[names.length - 1] = activity.getString(R.string.mobile_cast_use_code);

                new AlertDialog.Builder(activity)
                        .setTitle(R.string.mobile_cast_choose_tv)
                        .setItems(names, (d, which) -> {
                            if (which == names.length - 1) {
                                askCode(activity, onPaired);
                                return;
                            }
                            if (which < count) {
                                JSONObject tv = saved.optJSONObject(which);
                                save(activity, tv.optString("id"), tv.optString("name"));
                                onPaired.run();
                                return;
                            }
                            TvDiscovery.Tv tv = tvs.get(which - count);
                            sExecutor.execute(() -> {
                                try {
                                    save(activity, TvDiscovery.screenId(tv), tv.name);
                                    main(onPaired);
                                } catch (Exception e) {
                                    Log.e(TAG, "Linking %s failed: %s", tv.name, e.getMessage());
                                    main(() -> MessageHelpers.showMessage(activity, R.string.mobile_cast_pair_failed));
                                }
                            });
                        })
                        .show();
            });
        });
    }

    private static void askCode(Activity activity, Runnable onPaired) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.mobile_cast_to_tv)
                .setMessage(R.string.mobile_cast_enter_code)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String code = input.getText().toString().replaceAll("\\D", "");
                    sExecutor.execute(() -> {
                        try {
                            pair(activity, code);
                            main(onPaired);
                        } catch (Exception e) {
                            Log.e(TAG, "Pairing failed: %s", e.getMessage());
                            main(() -> MessageHelpers.showMessage(activity, R.string.mobile_cast_pair_failed));
                        }
                    });
                })
                .show();
    }

    private static void pair(Context context, String code) throws Exception {
        JSONObject screen = new JSONObject(post(API + "pairing/get_screen",
                new FormBody.Builder().add("pairing_code", code).build())).getJSONObject("screen");
        save(context, screen.getString("screenId"), screen.optString("name", "TV"));
    }

    /** Makes the TV the current one and remembers it (most recent first). */
    private static void save(Context context, String screenId, String name) {
        reset();
        JSONArray saved = new JSONArray();
        try {
            saved.put(new JSONObject().put("id", screenId).put("name", name));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        JSONArray old = savedTvs(context);
        for (int i = 0; i < old.length(); i++) {
            if (!screenId.equals(old.optJSONObject(i).optString("id"))) {
                saved.put(old.optJSONObject(i));
            }
        }
        prefs(context).edit()
                .putString(KEY_SCREEN_ID, screenId)
                .putString(KEY_SCREEN_NAME, name)
                .putString(KEY_SAVED, saved.toString())
                .apply();
    }

    /** Drops the current TV, e.g. after it was unlinked on the TV side. */
    private static void forgetCurrent(Context context) {
        String current = prefs(context).getString(KEY_SCREEN_ID, null);
        JSONArray saved = savedTvs(context);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < saved.length(); i++) {
            if (!saved.optJSONObject(i).optString("id").equals(current)) {
                kept.put(saved.optJSONObject(i));
            }
        }
        prefs(context).edit().remove(KEY_SCREEN_ID).remove(KEY_SCREEN_NAME).putString(KEY_SAVED, kept.toString()).apply();
    }

    /** Remembered TVs as [{id, name}]; includes a TV paired before this list existed. */
    private static JSONArray savedTvs(Context context) {
        try {
            String json = prefs(context).getString(KEY_SAVED, null);
            if (json != null) {
                return new JSONArray(json);
            }
            String current = prefs(context).getString(KEY_SCREEN_ID, null);
            JSONArray legacy = new JSONArray();
            if (current != null) {
                legacy.put(new JSONObject().put("id", current).put("name", screenName(context)));
            }
            return legacy;
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** Sends one command, opening the session first; a stale session is reopened once. */
    private static void command(Context context, String name, String... args) throws Exception {
        for (int attempt = 0; ; attempt++) {
            if (sSid == null) {
                connect(context);
            }

            FormBody.Builder body = new FormBody.Builder()
                    .add("count", "1")
                    .add("ofs", Integer.toString(sOfs))
                    .add("req0__sc", name);
            for (int i = 0; i + 1 < args.length; i += 2) {
                body.add("req0_" + args[i], args[i + 1]);
            }

            try {
                post(session().addQueryParameter("RID", Integer.toString(++sRid)).build().toString(), body.build());
                sOfs++;
                return;
            } catch (Exception e) {
                reset();
                if (attempt > 0) {
                    throw e;
                }
            }
        }
    }

    private static void connect(Context context) throws Exception {
        String screenId = prefs(context).getString(KEY_SCREEN_ID, null);

        // Network and server errors pass through here; only an answer without a token means unlinked.
        String answer = post(API + "pairing/get_lounge_token_batch",
                new FormBody.Builder().add("screen_ids", screenId).build());
        String token;
        try {
            token = new JSONObject(answer).getJSONArray("screens").getJSONObject(0).getString("loungeToken");
        } catch (JSONException e) {
            // The TV was unlinked: forget it, so the next attempt asks for a new code.
            forgetCurrent(context);
            throw e;
        }

        sBind = HttpUrl.parse(API + "bc/bind").newBuilder()
                .addQueryParameter("device", "REMOTE_CONTROL")
                .addQueryParameter("app", "youtube-desktop")
                .addQueryParameter("name", "SmarterTube")
                .addQueryParameter("id", deviceId(context))
                .addQueryParameter("mdx-version", "3")
                .addQueryParameter("loungeIdToken", token)
                .addQueryParameter("VER", "8")
                .addQueryParameter("CVER", "1")
                .addQueryParameter("zx", Long.toString(new Random().nextLong() & Long.MAX_VALUE, 36))
                .build();
        sRid = 1;
        sOfs = 0;
        sAid = 0;

        parse(new StringReader(post(sBind.newBuilder().addQueryParameter("RID", "1").build().toString(),
                new FormBody.Builder().add("count", "0").build())));
        if (sSid == null || sGsession == null) {
            throw new IllegalStateException("No session in bind response");
        }

        String sid = sSid;
        new Thread(() -> poll(sid), "LoungePoll").start();
    }

    /** Long-polls the TV's events until the session changes or breaks. */
    private static void poll(String sid) {
        OkHttpClient client = client().newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
        while (sid.equals(sSid)) {
            Request request = new Request.Builder().url(session()
                    .addQueryParameter("RID", "rpc")
                    .addQueryParameter("CI", "0")
                    .addQueryParameter("TYPE", "xmlhttp")
                    .addQueryParameter("AID", Long.toString(sAid))
                    .build()).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.d(TAG, "Session closed: HTTP %s", response.code());
                    if (sid.equals(sSid)) {
                        reset();
                    }
                    return;
                }
                parse(response.body().charStream());
            } catch (Exception e) {
                Log.d(TAG, "Poll failed: %s", e.getMessage());
                SystemClock.sleep(2_000);
            }
        }
    }

    /** Reads "<length>\n<json>" chunks; the json may span lines, so parse once it is complete. */
    static void parse(Reader source) throws Exception {
        BufferedReader reader = new BufferedReader(source);
        StringBuilder chunk = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (chunk.length() == 0 && line.matches("\\d+")) {
                continue;
            }
            chunk.append(line).append('\n');
            JSONArray events;
            try {
                events = new JSONArray(chunk.toString());
            } catch (JSONException incomplete) {
                continue;
            }
            chunk.setLength(0);
            for (int i = 0; i < events.length(); i++) {
                onEvent(events.getJSONArray(i));
            }
            notifyChange();
        }
    }

    private static void notifyChange() {
        main(() -> {
            if (sApp != null) {
                TvMediaSession.update(sApp);
            }
            Runnable onChange = sOnChange;
            if (onChange != null) {
                onChange.run();
            }
        });
    }

    /** Video title via oEmbed (no API key, no session needed). */
    private static void fetchTitle(String videoId) {
        try {
            String title = new JSONObject(execute(new Request.Builder()
                    .url("https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=" + videoId)
                    .build())).getString("title");
            if (videoId.equals(sVideoId)) {
                sTitle = title;
                notifyChange();
            }
        } catch (Exception e) {
            Log.d(TAG, "No title for %s: %s", videoId, e.getMessage());
        }
    }

    private static void onEvent(JSONArray event) throws JSONException {
        sAid = event.getLong(0);
        JSONArray payload = event.getJSONArray(1);
        JSONObject data = payload.optJSONObject(1);

        switch (payload.getString(0)) {
            case "c":
                sSid = payload.getString(1);
                break;
            case "S":
                sGsession = payload.getString(1);
                break;
            case "nowPlaying":
            case "onStateChange":
                if (data == null) {
                    break;
                }
                String videoId = data.optString("videoId", "");
                boolean settling = SystemClock.elapsedRealtime() - sCastAtMs < CAST_SETTLE_MS;
                if (!videoId.isEmpty() && !videoId.equals(sVideoId) && settling) {
                    break; // still the video from before our cast: its time and state don't apply either
                }
                if (!videoId.isEmpty() && !videoId.equals(sVideoId)) {
                    sVideoId = videoId;
                    sTitle = null;
                    sExecutor.execute(() -> fetchTitle(videoId));
                }
                if (data.has("currentTime")) {
                    sPositionSec = (float) data.optDouble("currentTime", 0);
                    sPositionAtMs = SystemClock.elapsedRealtime();
                }
                if (data.has("duration")) {
                    sDurationSec = (float) data.optDouble("duration", 0);
                }
                if (data.has("state")) {
                    sState = data.optInt("state", sState);
                }
                break;
            case "onVolumeChanged":
                if (data != null) {
                    sVolume = data.optInt("volume", sVolume);
                }
                break;
            case "loungeScreenDisconnected":
                reset();
                break;
        }
    }

    private static void reset() {
        sSid = null;
        sGsession = null;
        sState = -1;
        sVideoId = null;
        sTitle = null;
        notifyChange();
    }

    private static HttpUrl.Builder session() {
        return sBind.newBuilder()
                .addQueryParameter("SID", sSid)
                .addQueryParameter("gsessionid", sGsession);
    }

    private static String post(String url, FormBody body) throws Exception {
        return execute(new Request.Builder().url(url).post(body).build());
    }

    private static String execute(Request request) throws Exception {
        try (Response response = client().newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code() + " " + request.url().encodedPath()
                        + ": " + text.substring(0, Math.min(300, text.length())));
            }
            return text;
        }
    }

    private static synchronized OkHttpClient client() {
        if (sClient == null) {
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return sClient;
    }

    /** Stable per-install id; the lounge identifies the remote by it. */
    private static String deviceId(Context context) {
        String id = prefs(context).getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs(context).edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void main(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
