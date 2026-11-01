package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.media.VolumeProviderCompat;

import com.liskovsoft.smartyoutubetv2.mobile.notifications.MediaNotificationService;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Notification shade / lock screen controls for the video on the TV, like the official app's cast
 * notification: a MediaSession mirroring {@link LoungeCast}'s state, shown through the phone
 * player's {@link MediaNotificationService}. Its remote VolumeProvider makes the phone's volume
 * keys drive the TV volume, also with the screen locked.
 * ponytail: shares the one media notification with the phone player; whichever played last wins.
 */
final class TvMediaSession {
    private static final int VOLUME_STEP = 5;
    private static MediaSessionCompat sSession;
    private static VolumeProviderCompat sVolume;
    private static String sShownTitle;

    private TvMediaSession() {
    }

    /** Main thread: syncs the session with LoungeCast, creating or releasing it as needed. */
    static void update(Context context) {
        if (!LoungeCast.isConnected()) {
            release(context);
            return;
        }

        boolean created = sSession == null;
        if (created) {
            create(context);
        }
        if (LoungeCast.volume() >= 0) {
            sVolume.setCurrentVolume(LoungeCast.volume());
        }

        String title = LoungeCast.title() != null ? LoungeCast.title() : LoungeCast.screenName(context);
        sSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                        context.getString(R.string.mobile_cast_sent, LoungeCast.screenName(context)))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, LoungeCast.durationSec() * 1000L)
                .build());

        int state = LoungeCast.state() == LoungeCast.STATE_PLAYING ? PlaybackStateCompat.STATE_PLAYING
                : LoungeCast.state() == LoungeCast.STATE_BUFFERING ? PlaybackStateCompat.STATE_BUFFERING
                : PlaybackStateCompat.STATE_PAUSED;
        sSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(state, LoungeCast.positionSec() * 1000L, state == PlaybackStateCompat.STATE_PLAYING ? 1f : 0f)
                .build());

        // Like the phone player: (re)post on start and on every video change.
        if (created || !title.equals(sShownTitle)) {
            sShownTitle = title;
            MediaNotificationService.start(context, sSession.getSessionToken());
        }
    }

    /** Re-shows the TV notification after the phone player dropped its own; false when not casting. */
    static boolean repost(Context context) {
        if (sSession == null) {
            return false;
        }
        MediaNotificationService.start(context, sSession.getSessionToken());
        return true;
    }

    private static void create(Context context) {
        sSession = new MediaSessionCompat(context, "SmarterTubeTv");
        android.os.Bundle extras = new android.os.Bundle();
        extras.putBoolean(MediaNotificationService.EXTRA_TV_SESSION, true);
        sSession.setExtras(extras);
        sSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                LoungeCast.control(context, "play");
            }

            @Override
            public void onPause() {
                LoungeCast.control(context, "pause");
            }

            @Override
            public void onSeekTo(long pos) {
                LoungeCast.seekTo(context, (int) (pos / 1000));
            }

            @Override
            public void onSkipToNext() {
                LoungeCast.control(context, "next");
            }

            @Override
            public void onSkipToPrevious() {
                LoungeCast.control(context, "previous");
            }

            @Override
            public void onStop() {
                LoungeCast.stop(context);
            }
        });

        // Tapping the notification opens the app, not the (idle) phone player.
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            sSession.setSessionActivity(PendingIntent.getActivity(context, 1, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        sVolume = new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE, 100,
                Math.max(LoungeCast.volume(), 0)) {
            @Override
            public void onSetVolumeTo(int volume) {
                LoungeCast.setVolume(context, volume);
                setCurrentVolume(volume);
            }

            @Override
            public void onAdjustVolume(int direction) {
                LoungeCast.volumeBy(context, direction * VOLUME_STEP);
                setCurrentVolume(LoungeCast.volume());
            }
        };
        sSession.setPlaybackToRemote(sVolume);
        sSession.setActive(true);
    }

    private static void release(Context context) {
        if (sSession == null) {
            return;
        }
        sSession.release();
        sSession = null;
        sVolume = null;
        sShownTitle = null;
        // No MediaNotificationService.stop(): the release reaches the service as onSessionDestroyed,
        // and it only stops when it shows this session, not the phone player's.
    }
}
