package com.utahmeta.coretv;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.graphics.Color;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {
    // CORETV_PLAYBACK_LIFECYCLE_V1
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService progressQueue = Executors.newSingleThreadExecutor();
    private String itemId = "";
    private String title = "Core TV";
    private boolean live = false;
    private boolean completed = false;
    private boolean prepared = false;
    private boolean playbackStarted = false;
    private long startMs = 0L;
    private float touchStartX = 0f, touchStartY = 0f;
    private int retryCount = 0;
    private boolean tuning = false;
    private String playbackUrl = "";
    private JSONArray liveQueue = new JSONArray();
    private int liveIndex = -1, previousLiveIndex = -1, liveGroupId = 0;
    private JSONArray liveGuide = new JSONArray();
    private boolean hudVisible = true;
    private JSONObject nextItem = null;
    private boolean autoNextCancelled = false;
    private boolean inPip = false;
    private final Set<String> failedLiveFeeds = new HashSet<>();
    private boolean failoverBusy = false;
    // CORETV_PLAYBACK_FALLBACK_V1
    private boolean compatibilityFallbackRequested = false;
    private boolean compatibilityTranscode = false;
    private final Runnable startupWatchdog = () -> {
        if (live || compatibilityFallbackRequested || player == null || isFinishing() || !prepared) return;
        try {
            long raw = Math.max(0L, player.getCurrentPosition());
            if (player.getPlayWhenReady() && raw < 750L) requestCompatibilityFallback();
        } catch (Exception ignored) {}
    };

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!live && prepared && !completed) reportProgress(false);
            handler.postDelayed(this, 15000L);
        }
    };

    private final Runnable sessionTick = new Runnable() {
        @Override public void run() {
            if(player!=null&&prepared&&!completed&&!isFinishing())reportEvent("playback_heartbeat","active");
            handler.postDelayed(this,20000L);
        }
    };

    private final Runnable nextEpisodeTick = new Runnable() {
        @Override public void run() {
            if (!live && player != null && prepared && nextItem != null && !autoNextCancelled && !completed) {
                long duration=player.getDuration(), pos=player.getCurrentPosition();
                long remain=(duration>0?duration-pos:Long.MAX_VALUE);
                if(remain>0 && remain<=20000L){
                    long sec=Math.max(1L,(remain+999L)/1000L);
                    overlay.setText("UP NEXT  "+safe(nextItem.optString("name","Next episode"))+"  in "+sec+" sec"+System.lineSeparator()+"UP play now   DOWN cancel autoplay");
                    overlay.setVisibility(View.VISIBLE);
                }
            }
            handler.postDelayed(this,1000L);
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
        if(live){try{String q=safe(getIntent().getStringExtra("live_queue"));if(!q.isEmpty())liveQueue=new JSONArray(q);}catch(Exception ignored){}liveGroupId=getIntent().getIntExtra("live_group_id",0);findLiveIndex();String last=safe(getIntent().getStringExtra("live_last_channel"));if(!last.isEmpty())previousLiveIndex=indexForChannel(last);loadLiveGuide();}
        else{try{String n=safe(getIntent().getStringExtra("next_item_json"));if(!n.isEmpty())nextItem=new JSONObject(n);}catch(Exception ignored){}}
        Log.i("CoreTVPlayer", "open item=" + itemId + " startMs=" + startMs + " live=" + live + " queue=" + liveQueue.length()+" next="+(nextItem!=null));

        if (playbackUrl.isEmpty()) {
            Toast.makeText(this, "Playback URL unavailable", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, live ? 18000 : 30000, 900, 1800)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setKeepScreenOn(true);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        vp.gravity = Gravity.CENTER;
        root.addView(playerView, vp);

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(18f);
        overlay.setBackgroundColor(0x99000000);
        overlay.setPadding(28, 14, 28, 14);
        overlay.setText(title + "   |   Loading...");
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        op.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        op.bottomMargin = 26;
        root.addView(overlay, op);

        setContentView(root);
        playerView.requestFocus();
        playerView.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                touchStartX = ev.getX(); touchStartY = ev.getY(); return true;
            }
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                float dx = ev.getX() - touchStartX;
                float dy = ev.getY() - touchStartY;
                if(live && Math.abs(dy)>120 && Math.abs(dy)>Math.abs(dx)) switchLive(dy<0?1:-1);
                else if(live && Math.abs(dx)>120 && Math.abs(dx)>Math.abs(dy)){if(dx>0)lastLive();else showLiveHud();}
                else if(!live && Math.abs(dy)>120 && Math.abs(dy)>Math.abs(dx)){if(nextPromptActive()){if(dy<0)playNextEpisode();else cancelAutoNext();}else if(dy<0)cycleAudio();else cycleSubtitles();}
                else if (!live && Math.abs(dx) > 100 && Math.abs(dx) > Math.abs(dy)) seekBy(dx > 0 ? 10000 : -10000);
                else if(live)toggleHud();else toggle();
                return true;
            }
            return true;
        });

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    showOverlayPersistent(live ? liveStatus("Tuning...") : "Buffering...");
                    reportEvent("playback_buffering", "media3");
                } else if (state == Player.STATE_READY) {
                    prepared = true;
                    tuning = false;
                    retryCount = 0;
                    try { player.setPlayWhenReady(true); player.play(); } catch (Exception ignored) {}
                    beginPlayback(live ? "live_media3_ready" : (startMs > 0L ? "server_resume=" + startMs : "media3_ready"));
                    if(!live){handler.removeCallbacks(nextEpisodeTick);handler.postDelayed(nextEpisodeTick,1000L);}
                } else if (state == Player.STATE_ENDED) {
                    completed = true;
                    reportEvent("playback_complete", "media3");
                    if (!live) reportProgress(true);
                    handler.removeCallbacks(progressTick);
                    handler.removeCallbacks(nextEpisodeTick);
                    if(!live && nextItem!=null && !autoNextCancelled){playNextEpisode();return;}
                    finish();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                String d = error.getErrorCodeName() + ":" + safe(error.getMessage());
                Log.w("CoreTVPlayer", "player error " + d, error);
                if (retryCount < 2 && !isFinishing()) {
                    retryCount++;
                    reportEvent("playback_retry", "media3:" + d);
                    showOverlayPersistent("Reconnecting...");
                    handler.postDelayed(() -> {
                        if (player == null || isFinishing()) return;
                        try {
                            player.stop();
                            player.clearMediaItems();
                            player.setMediaItem(mediaItem());
                            player.prepare();
                            player.play();
                        } catch (Exception ignored) {}
                    }, 1000L * retryCount);
                    return;
                }
                reportEvent("playback_error", "media3:" + d);
                if(live){
                    failedLiveFeeds.add(itemId);
                    findAlternateLiveFeed(d);
                    return;
                }
                Toast.makeText(PlayerActivity.this, "Playback error: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                showOverlayPersistent("Playback error   |   BACK return");
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                reportEvent(isPlaying ? "playback_started" : "playback_not_playing",
                        "state=" + player.getPlaybackState() + ";pwr=" + player.getPlayWhenReady() + ";suppression=" + player.getPlaybackSuppressionReason());
                if (isPlaying) hideOverlaySoon();
            }
        });

        player.setMediaItem(mediaItem());
        player.prepare();
        player.play();
    }

    private int indexForChannel(String cid){for(int i=0;i<liveQueue.length();i++){JSONObject o=liveQueue.optJSONObject(i);if(o!=null&&cid.equalsIgnoreCase(o.optString("id","")))return i;}return -1;}
    private void loadLiveGuide(){if(!live||liveGroupId<=0)return;final int gid=liveGroupId;new Thread(()->{try{JSONObject q=new JSONObject();q.put("group_id",gid);JSONObject r=PresenceService.coreCall(PlayerActivity.this,"/core-tv/v1/guide",q);JSONArray ar=r.optJSONArray("programs");if(ar!=null&&gid==liveGroupId){liveGuide=ar;runOnUiThread(()->{if(hudVisible)showLiveHud();});}}catch(Exception ignored){}},"CoreTV-LiveGuide").start();}
    private long epgTime(String s){try{return java.time.Instant.parse(s).toEpochMilli();}catch(Exception e){return 0L;}}
    private JSONObject liveNow(){long now=System.currentTimeMillis();for(int i=0;i<liveGuide.length();i++){JSONObject o=liveGuide.optJSONObject(i);if(o==null||!itemId.equals(o.optString("channel_id","")))continue;long st=epgTime(o.optString("start_date","")),en=epgTime(o.optString("end_date",""));if(st>0&&en>0&&now>=st&&now<en)return o;}return null;}
    private JSONObject liveNext(){long now=System.currentTimeMillis(),best=Long.MAX_VALUE;JSONObject pick=null;for(int i=0;i<liveGuide.length();i++){JSONObject o=liveGuide.optJSONObject(i);if(o==null||!itemId.equals(o.optString("channel_id","")))continue;long st=epgTime(o.optString("start_date",""));if(st>now&&st<best){best=st;pick=o;}}return pick;}
    private String liveHudText(){JSONObject n=liveNow(),nx=liveNext();String now=n==null?"Live programming":safe(n.optString("name","Live programming")),next=nx==null?"Guide updating...":safe(nx.optString("name","Program")),s=System.lineSeparator();return title+s+"NOW   "+now+s+"NEXT  "+next+s+"UP/DOWN channels   LEFT last channel   OK hide/show";}
    private void showLiveHud(){if(!live)return;hudVisible=true;overlay.setText(liveHudText());overlay.setVisibility(View.VISIBLE);handler.removeCallbacks(hideOverlayRunnable);handler.postDelayed(hideOverlayRunnable,6500L);}
    private void toggleHud(){if(!live)return;if(overlay.getVisibility()==View.VISIBLE){overlay.setVisibility(View.GONE);hudVisible=false;}else showLiveHud();}
    private void syncLiveTune(String cid){new Thread(()->{try{JSONObject q=new JSONObject();q.put("action","tune");q.put("channel_id",cid);PresenceService.coreCall(PlayerActivity.this,"/core-tv/v1/live-state",q);}catch(Exception ignored){}},"CoreTV-LiveState").start();}
    private void lastLive(){if(previousLiveIndex>=0&&previousLiveIndex<liveQueue.length()&&previousLiveIndex!=liveIndex)tuneIndex(previousLiveIndex);}
    private void findLiveIndex(){liveIndex=-1;for(int i=0;i<liveQueue.length();i++){JSONObject o=liveQueue.optJSONObject(i);if(o!=null&&itemId.equalsIgnoreCase(o.optString("id",""))){liveIndex=i;break;}}}
    private String liveStatus(String suffix){if(!live)return suffix;String label=title;if(liveIndex>=0){JSONObject o=liveQueue.optJSONObject(liveIndex);if(o!=null){String n=safe(o.optString("name",""));double d=o.optDouble("number",0);String num=d>0?(Math.rint(d)==d?String.valueOf((long)d):String.valueOf(d)):"";label=(num.isEmpty()?"":num+"  ")+(n.isEmpty()?title:n);}}return label+"   |   "+suffix;}
    private void findAlternateLiveFeed(String reason){
        if(!live||failoverBusy||isFinishing())return;
        failoverBusy=true;showOverlayPersistent(liveStatus("Feed failed - finding alternate..."));
        final String failed=itemId;
        new Thread(()->{
            try{
                JSONObject q=new JSONObject();q.put("alternate_for",failed);
                JSONObject r=PresenceService.coreCall(PlayerActivity.this,"/core-tv/v1/live",q);
                JSONArray ar=r.optJSONArray("alternates");JSONObject pick=null;
                if(ar!=null)for(int i=0;i<ar.length();i++){JSONObject o=ar.optJSONObject(i);if(o==null)continue;String id=safe(o.optString("id",""));if(id.isEmpty()||failedLiveFeeds.contains(id))continue;pick=o;break;}
                if(pick==null){runOnUiThread(()->{failoverBusy=false;Toast.makeText(PlayerActivity.this,"This live feed is unavailable and no verified alternate is ready.",Toast.LENGTH_LONG).show();showOverlayPersistent(liveStatus("Feed unavailable - UP/DOWN another channel"));});return;}
                final JSONObject alt=pick;
                runOnUiThread(()->{
                    try{
                        String cid=safe(alt.optString("id",""));int target=indexForChannel(cid);
                        if(target<0){
                            JSONObject z=new JSONObject();z.put("id",cid);z.put("name",alt.optString("name","Alternate feed"));z.put("number",alt.optDouble("channel_number",0));z.put("group_id",alt.optInt("group_id",liveGroupId));liveQueue.put(z);target=liveQueue.length()-1;
                        }
                        failoverBusy=false;
                        Toast.makeText(PlayerActivity.this,"Switching to a healthier alternate feed.",Toast.LENGTH_SHORT).show();
                        tuneIndex(target);
                    }catch(Exception e){failoverBusy=false;showOverlayPersistent(liveStatus("Alternate feed unavailable - UP/DOWN another channel"));}
                });
            }catch(Exception e){runOnUiThread(()->{failoverBusy=false;showOverlayPersistent(liveStatus("Feed unavailable - UP/DOWN another channel"));});}
        },"CoreTV-LiveFailover").start();
    }
    private void switchLive(int delta){if(!live||tuning||liveQueue.length()<2)return;if(liveIndex<0)findLiveIndex();int base=liveIndex<0?0:liveIndex,next=(base+delta)%liveQueue.length();if(next<0)next+=liveQueue.length();tuneIndex(next);}
    private void tuneIndex(int targetIndex){
        if(!live||tuning||targetIndex<0||targetIndex>=liveQueue.length())return;JSONObject o=liveQueue.optJSONObject(targetIndex);if(o==null)return;
        final String cid=safe(o.optString("id","")),nm=safe(o.optString("name","Channel"));if(cid.isEmpty())return;final double number=o.optDouble("number",0);final int gid=o.optInt("group_id",liveGroupId);
        tuning=true;prepared=false;playbackStarted=false;retryCount=0;showOverlayPersistent((number>0?(Math.rint(number)==number?String.valueOf((long)number):String.valueOf(number))+"  ":"")+nm+"   |   Tuning...");
        new Thread(()->{try{JSONObject q=new JSONObject();q.put("channel_id",cid);JSONObject r=PresenceService.streamCall(PlayerActivity.this,"/core-tv/v1/live-play",q);if(!r.optBoolean("ok",false))throw new Exception(r.optString("error","live_play_failed"));String u=PresenceService.resolveStreamUrl(PlayerActivity.this,r.optString("url",""));if(u.isEmpty())throw new Exception("stream_url_missing");runOnUiThread(()->{previousLiveIndex=liveIndex;itemId=cid;title=(number>0?(Math.rint(number)==number?String.valueOf((long)number):String.valueOf(number))+"  ":"")+nm;playbackUrl=u;liveIndex=targetIndex;if(gid!=liveGroupId){liveGroupId=gid;liveGuide=new JSONArray();loadLiveGuide();}syncLiveTune(cid);try{player.stop();player.clearMediaItems();player.setMediaItem(mediaItem());player.prepare();player.play();reportEvent("live_channel_change","index="+liveIndex);}catch(Exception e){tuning=false;showOverlayPersistent(liveStatus("Tune failed - UP/DOWN another channel"));}});}catch(Exception e){runOnUiThread(()->{tuning=false;showOverlayPersistent(liveStatus("Tune failed - UP/DOWN another channel"));});}}, "CoreTV-LiveTune").start();
    }

    private boolean nextPromptActive(){if(live||nextItem==null||autoNextCancelled||player==null)return false;long d=player.getDuration();return d>0&&d-player.getCurrentPosition()<=22000L;}
    private void cancelAutoNext(){autoNextCancelled=true;showOverlay("Autoplay next episode cancelled");}
    private String formatTrack(Format f){if(f==null)return "Track";String s=safe(f.label);if(s.isEmpty())s=safe(f.language);if(s.isEmpty())s=safe(f.codecs);return s.isEmpty()?"Track":s;}
    private void cycleAudio(){
        if(live||player==null)return;Tracks tr=player.getCurrentTracks();ArrayList<Tracks.Group> gs=new ArrayList<>();ArrayList<Integer> ix=new ArrayList<>();int current=-1;
        for(Tracks.Group g:tr.getGroups())if(g.getType()==C.TRACK_TYPE_AUDIO)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){if(g.isTrackSelected(i))current=gs.size();gs.add(g);ix.add(i);}
        if(gs.isEmpty()){showOverlay("No alternate audio tracks");return;}int n=current<0?0:(current+1)%gs.size();Tracks.Group g=gs.get(n);int ti=ix.get(n);
        TrackSelectionParameters params=player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).clearOverridesOfType(C.TRACK_TYPE_AUDIO).setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(),ti)).build();
        player.setTrackSelectionParameters(params);showOverlay("Audio: "+formatTrack(g.getTrackFormat(ti)));
    }
    private void cycleSubtitles(){
        if(live||player==null)return;Tracks tr=player.getCurrentTracks();ArrayList<Tracks.Group> gs=new ArrayList<>();ArrayList<Integer> ix=new ArrayList<>();int current=-1;
        for(Tracks.Group g:tr.getGroups())if(g.getType()==C.TRACK_TYPE_TEXT)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){if(g.isTrackSelected(i))current=gs.size();gs.add(g);ix.add(i);}
        TrackSelectionParameters.Builder b=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if(gs.isEmpty()){b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);player.setTrackSelectionParameters(b.build());showOverlay("Subtitles: Off");return;}
        if(current>=gs.size()-1){b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);player.setTrackSelectionParameters(b.build());showOverlay("Subtitles: Off");return;}
        int n=current<0?0:current+1;Tracks.Group g=gs.get(n);int ti=ix.get(n);b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(),ti));player.setTrackSelectionParameters(b.build());showOverlay("Subtitles: "+formatTrack(g.getTrackFormat(ti)));
    }
    private void loadFollowingNext(String currentId){new Thread(()->{try{JSONObject q=new JSONObject();q.put("item_id",currentId);JSONObject r=PresenceService.coreCall(PlayerActivity.this,"/core-tv/v1/next-episode",q);JSONObject ni=r.optJSONObject("item");runOnUiThread(()->{nextItem=(ni!=null&&!ni.optString("id","").isEmpty())?ni:null;autoNextCancelled=false;});}catch(Exception e){runOnUiThread(()->nextItem=null);}},"CoreTV-NextEpisode").start();}
    private void playNextEpisode(){
        if(live||nextItem==null||tuning)return;final JSONObject ni=nextItem;final String nid=safe(ni.optString("id","")),nn=safe(ni.optString("name","Next episode"));if(nid.isEmpty())return;
        nextItem=null;autoNextCancelled=false;prepared=false;playbackStarted=false;tuning=true;showOverlayPersistent("Starting next episode: "+nn);
        new Thread(()->{try{JSONObject q=new JSONObject();q.put("item_id",nid);q.put("position_ticks",0L);JSONObject r=PresenceService.streamCall(PlayerActivity.this,"/core-tv/v1/play",q);if(!r.optBoolean("ok",false))throw new Exception(r.optString("error","play_failed"));String u=PresenceService.resolveStreamUrl(PlayerActivity.this,r.optString("url",""));if(u.isEmpty())throw new Exception("stream_url_missing");runOnUiThread(()->{itemId=nid;title=nn.isEmpty()?"Next episode":nn;playbackUrl=u;startMs=0L;completed=false;prepared=false;playbackStarted=false;retryCount=0;tuning=false;compatibilityFallbackRequested=false;compatibilityTranscode=false;try{player.stop();player.clearMediaItems();player.setMediaItem(mediaItem());player.prepare();player.play();reportEvent("autoplay_next","next_episode");loadFollowingNext(nid);}catch(Exception e){showOverlayPersistent("Next episode unavailable");}});}catch(Exception e){runOnUiThread(()->{tuning=false;showOverlayPersistent("Next episode unavailable");});}},"CoreTV-Autoplay").start();
    }
    private void tryEnterPip(){if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O||isFinishing()||player==null||!player.isPlaying())return;try{if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))return;PictureInPictureParams pp=new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).build();enterPictureInPictureMode(pp);}catch(Exception ignored){}}

    private MediaItem mediaItem() {
        MediaItem.Builder b = new MediaItem.Builder().setUri(playbackUrl);
        if (live) b.setMimeType(MimeTypes.VIDEO_MP2T); else if (compatibilityTranscode) b.setMimeType(MimeTypes.VIDEO_MP4);
        return b.build();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private void beginPlayback(String reason) {
        if (playbackStarted || isFinishing()) return;
        playbackStarted = true;
        try { player.play(); } catch (Exception ignored) {}
        Log.i("CoreTVPlayer", "playback ready reason=" + reason + " current=" + positionMs());
        reportEvent("playback_ready", reason);
        handler.removeCallbacks(progressTick);
        handler.postDelayed(progressTick, 5000L);
        handler.removeCallbacks(sessionTick);
        handler.postDelayed(sessionTick, 2000L);
        handler.removeCallbacks(startupWatchdog);
        if (!live && !compatibilityTranscode) handler.postDelayed(startupWatchdog, 6500L);
        if(live){syncLiveTune(itemId);showLiveHud();}else showOverlay("Playing  |  UP audio  DOWN subtitles  LEFT/RIGHT seek");
    }

    private void requestCompatibilityFallback() {
        if (live || compatibilityFallbackRequested || itemId.isEmpty() || isFinishing()) return;
        compatibilityFallbackRequested = true;
        final long resumeMs = Math.max(startMs, positionMs());
        showOverlayPersistent("Optimizing playback...");
        reportEvent("playback_fallback_requested", "direct_to_h264_aac");
        new Thread(() -> {
            try {
                JSONObject q = new JSONObject();
                q.put("item_id", itemId);
                q.put("position_ticks", Math.max(0L, resumeMs) * 10000L);
                q.put("force_transcode", true);
                q.put("max_bitrate_kbps", 8000);
                JSONObject r = PresenceService.streamCall(PlayerActivity.this, "/core-tv/v1/play", q);
                if (!r.optBoolean("ok", false)) throw new Exception(r.optString("error", "fallback_play_failed"));
                String u = PresenceService.resolveStreamUrl(PlayerActivity.this, r.optString("url", ""));
                if (u.isEmpty()) throw new Exception("fallback_url_missing");
                runOnUiThread(() -> {
                    if (isFinishing() || player == null) return;
                    try {
                        playbackUrl = u;
                        startMs = resumeMs;
                        compatibilityTranscode = true;
                        prepared = false;
                        playbackStarted = false;
                        retryCount = 0;
                        player.stop();
                        player.clearMediaItems();
                        player.setMediaItem(mediaItem());
                        player.prepare();
                        player.setPlayWhenReady(true);
                        player.play();
                        reportEvent("playback_fallback_started", "h264_aac_mp4");
                    } catch (Exception e) {
                        reportEvent("playback_fallback_error", e.getClass().getSimpleName());
                        showOverlayPersistent("Playback unavailable   |   BACK return");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    reportEvent("playback_fallback_error", e.getClass().getSimpleName());
                    showOverlayPersistent("Playback unavailable   |   BACK return");
                });
            }
        }, "CoreTV-PlaybackFallback").start();
    }

    private void hideOverlaySoon() {
        overlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlayRunnable);
        handler.removeCallbacks(startupWatchdog);
        handler.postDelayed(hideOverlayRunnable, 3000L);
    }

    private final Runnable hideOverlayRunnable = () -> {
        if (!isFinishing() && player != null && player.isPlaying()) overlay.setVisibility(View.GONE);
    };

    private void showOverlayPersistent(String msg) {
        overlay.setText(title + "   |   " + msg);
        overlay.setVisibility(View.VISIBLE);
    }

    private void showOverlay(String msg) {
        showOverlayPersistent(msg);
        hideOverlaySoon();
    }

    private long positionMs() {
        try {
            long relative = Math.max(0L, player == null ? 0L : player.getCurrentPosition());
            return live ? relative : Math.max(0L, startMs + relative);
        } catch (Exception e) {
            return live ? 0L : Math.max(0L, startMs);
        }
    }

    private void reportEvent(String event, String detailText) {
        final String ev = safe(event), de = safe(detailText);
        new Thread(() -> {
            try {
                JSONObject p = new JSONObject();
                p.put("event", ev);
                p.put("item_id", itemId);
                p.put("title", title);
                p.put("live", live);
                p.put("position_ms", positionMs());
                p.put("detail", de);
                PresenceService.streamCall(PlayerActivity.this, "/core-tv/v1/client-event", p);
            } catch (Exception e) {
                Log.w("CoreTVPlayer", "event failed " + ev + " error=" + e.getClass().getSimpleName());
            }
        }, "CoreTV-Event").start();
    }

    private void reportProgress(boolean done) {
        if (live || itemId.isEmpty()) return;
        final long ticks = positionMs() * 10000L;
        final String progressItem = itemId;
        progressQueue.execute(() -> {
            try {
                JSONObject p = new JSONObject();
                p.put("item_id", progressItem);
                p.put("position_ticks", ticks);
                p.put("completed", done);
                PresenceService.streamCall(PlayerActivity.this, "/core-tv/v1/progress", p);
            } catch (Exception e) {
                Log.w("CoreTVPlayer", "progress failed " + e.getClass().getSimpleName());
            }
        });
    }

    private void toggle() {
        if (!prepared || player == null) return;
        if (player.isPlaying()) {
            player.pause();
            if (!live) reportProgress(false);
            showOverlay("Paused");
        } else {
            player.play();
            showOverlay("Playing");
        }
    }

    private void seekBy(long deltaMs) {
        if (live || !prepared || player == null) return;
        long current = Math.max(0L, player.getCurrentPosition());
        long duration = player.getDuration();
        long target = Math.max(0L, current + deltaMs);
        if (duration > 0L) target = Math.min(Math.max(0L, duration - 1000L), target);
        player.seekTo(target);
        showOverlay(deltaMs > 0 ? "+10 sec" : "-10 sec");
        reportProgress(false);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if(live && k==KeyEvent.KEYCODE_DPAD_UP){switchLive(-1);return true;}
            if(live && k==KeyEvent.KEYCODE_DPAD_DOWN){switchLive(1);return true;}
            if(live && (k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER)){toggleHud();return true;}
            if(live && k==KeyEvent.KEYCODE_DPAD_LEFT){lastLive();return true;}
            if(live && k==KeyEvent.KEYCODE_DPAD_RIGHT){showLiveHud();return true;}
            if(!live && k==KeyEvent.KEYCODE_DPAD_UP){if(nextPromptActive())playNextEpisode();else cycleAudio();return true;}
            if(!live && k==KeyEvent.KEYCODE_DPAD_DOWN){if(nextPromptActive())cancelAutoNext();else cycleSubtitles();return true;}
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_SPACE) {toggle(); return true;}
            if(!live && (k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER)){toggle();return true;}
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND) {seekBy(-10000); return true;}
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {seekBy(10000); return true;}
            if (k == KeyEvent.KEYCODE_BACK) {
                if (!live && !completed) reportProgress(false);
                reportEvent("playback_stop", "back");
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onUserLeaveHint(){super.onUserLeaveHint();tryEnterPip();}
    @Override public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, Configuration newConfig){super.onPictureInPictureModeChanged(inPictureInPictureMode,newConfig);inPip=inPictureInPictureMode;if(inPip)overlay.setVisibility(View.GONE);else if(live)showLiveHud();else showOverlay("Playing  |  UP audio  DOWN subtitles  LEFT/RIGHT seek");}
    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (player != null && prepared && !completed && !isFinishing()) {
            try { player.setPlayWhenReady(true); player.play(); } catch (Exception ignored) {}
        }
    }

    @Override protected void onPause() {
        super.onPause();
        // Android TV can transiently pause this Activity during focus/overlay changes.
        // Do not stop playback here; onStop handles a real background transition.
    }

    @Override protected void onStop() {
        super.onStop();
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O && isInPictureInPictureMode())return;
        if (!isFinishing() && player != null && player.isPlaying()) {
            player.pause();
            if (!live) reportProgress(false);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(progressTick);
        handler.removeCallbacks(nextEpisodeTick);
        handler.removeCallbacks(sessionTick);
        handler.removeCallbacks(hideOverlayRunnable);
        handler.removeCallbacks(startupWatchdog);
        if (!live && !completed) reportProgress(false);
        if (player != null) {
            player.release();
            player = null;
        }
        progressQueue.shutdown();
        super.onDestroy();
    }
}
