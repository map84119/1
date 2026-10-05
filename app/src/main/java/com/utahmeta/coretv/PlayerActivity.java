package com.utahmeta.coretv;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Build-preserving recovery of the production Core TV Media3 player.
 * Uses the same production stream/progress APIs and intent contract.
 */
public class PlayerActivity extends Activity {
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService events = Executors.newSingleThreadExecutor();

    private String playbackUrl = "";
    private String itemId = "";
    private String title = "Core TV";
    private boolean live;
    private boolean completed;
    private boolean inPip;
    private long startMs;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            reportProgress(false);
            handler.postDelayed(this, 15000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();

        playbackUrl = safe(getIntent().getStringExtra("url"));
        itemId = safe(getIntent().getStringExtra("item_id"));
        title = safe(getIntent().getStringExtra("name"));
        if (title.isEmpty()) title = "Core TV";
        live = getIntent().getBooleanExtra("live", false);
        startMs = Math.max(0L, getIntent().getLongExtra("start_ms", 0L));

        if (playbackUrl.isEmpty()) {
            Toast.makeText(this, "Playback URL unavailable", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, live ? 18000 : 30000, 900, 1800)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(lc).build();

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(false);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(18f);
        overlay.setBackgroundColor(0x99000000);
        overlay.setPadding(28, 14, 28, 14);
        overlay.setText(title + "   |   Loading...");
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(-2, -2);
        op.gravity = Gravity.TOP | Gravity.START;
        op.setMargins(24, 24, 24, 24);
        root.addView(overlay, op);

        setContentView(root);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    if (!live && startMs > 0 && player.getCurrentPosition() < 1000L) player.seekTo(startMs);
                    player.play();
                    showOverlay("Playing");
                } else if (state == Player.STATE_BUFFERING) {
                    showOverlay("Buffering...");
                } else if (state == Player.STATE_ENDED) {
                    completed = true;
                    reportProgress(true);
                    finish();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                showOverlay("Playback error");
                Toast.makeText(PlayerActivity.this, "Playback error: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
            }
        });

        player.setMediaItem(MediaItem.fromUri(playbackUrl));
        player.prepare();
        player.play();
        if (!live) handler.postDelayed(progressTick, 15000L);
    }

    private void reportProgress(boolean done) {
        if (live || player == null || itemId.isEmpty()) return;
        final long pos = Math.max(0L, player.getCurrentPosition());
        final long dur = Math.max(0L, player.getDuration());
        events.execute(() -> {
            try {
                JSONObject q = new JSONObject();
                q.put("item_id", itemId);
                q.put("position_ticks", pos * 10000L);
                q.put("duration_ticks", dur * 10000L);
                q.put("completed", done);
                PresenceService.streamCall(PlayerActivity.this, "/core-tv/v1/progress", q);
            } catch (Exception ignored) {}
        });
    }

    private void showOverlay(String status) {
        overlay.setText(title + "   |   " + status + (live ? "   |   LIVE" : ""));
        overlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, 3500L);
    }

    private final Runnable hideOverlay = () -> {
        if (overlay != null) overlay.setVisibility(View.GONE);
    };

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (player == null) return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (player.isPlaying()) player.pause(); else player.play();
            showOverlay(player.isPlaying() ? "Playing" : "Paused");
            return true;
        }
        if (!live && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            player.seekTo(Math.min(player.getDuration(), player.getCurrentPosition() + 30000L));
            showOverlay("Forward 30 sec");
            return true;
        }
        if (!live && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            player.seekTo(Math.max(0L, player.getCurrentPosition() - 15000L));
            showOverlay("Back 15 sec");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            showOverlay(player.isPlaying() ? "Playing" : "Paused");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (android.os.Build.VERSION.SDK_INT >= 26 && player != null && player.isPlaying()) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build());
                inPip = true;
            } catch (Exception ignored) {}
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean pip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(pip, cfg);
        inPip = pip;
    }

    @Override protected void onStop() {
        super.onStop();
        if (!inPip && !isChangingConfigurations()) reportProgress(false);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(progressTick);
        handler.removeCallbacks(hideOverlay);
        if (!live && !completed) reportProgress(false);
        if (player != null) {
            player.release();
            player = null;
        }
        events.shutdown();
        super.onDestroy();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
