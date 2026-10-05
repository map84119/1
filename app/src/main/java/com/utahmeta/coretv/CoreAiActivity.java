package com.utahmeta.coretv;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.*;
import android.speech.RecognizerIntent;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.util.ArrayList;
import java.util.concurrent.*;

public class CoreAiActivity extends Activity {
    void immersive(){try{getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}catch(Exception e){}}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)immersive();}

    static final int VOICE=701;
    SharedPreferences p; LinearLayout messages; EditText input; Button ask,voice; TextView state;
    ExecutorService pool=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle b){super.onCreate(b);immersive();setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);p=getSharedPreferences("core_presence",0);build();}
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    TextView tx(String s,int z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);return v;}
    void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.HORIZONTAL);root.setBackgroundColor(0xff030a12);
        LinearLayout rail=new LinearLayout(this);rail.setOrientation(LinearLayout.VERTICAL);rail.setPadding(dp(26),dp(26),dp(20),dp(22));rail.setBackgroundColor(0xff071522);
        rail.addView(tx("UtahMeta",24,Color.WHITE,true));rail.addView(tx("CORE AI",15,0xff1591ff,true));
        TextView title=tx("Your media assistant",27,Color.WHITE,true);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(24);rail.addView(title,tp);
        TextView note=tx("Ask what to watch, find something for the family, compare choices, or turn an idea into a search.",15,0xff9fb2c7,false);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=dp(8);rail.addView(note,np);
        String[] examples={"Play Moana","What games are on live right now?","A family movie for tonight","Request the newest Mission Impossible"};
        for(String e:examples){Button b=new Button(this);b.setText(e);b.setOnClickListener(v->{input.setText(e);ask();});LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(48));ep.topMargin=dp(8);rail.addView(b,ep);}
        Space s=new Space(this);rail.addView(s,new LinearLayout.LayoutParams(1,0,1f));
        Button back=new Button(this);back.setText("BACK TO CORE TV");back.setOnClickListener(v->finish());rail.addView(back,new LinearLayout.LayoutParams(-1,dp(50)));
        root.addView(rail,new LinearLayout.LayoutParams(dp(360),-1));

        LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);main.setPadding(dp(28),dp(24),dp(28),dp(22));
        state=tx("CORE AI  -  subscription-aware  -  verified media actions",13,0xff7f96aa,false);main.addView(state,new LinearLayout.LayoutParams(-1,dp(28)));
        ScrollView sv=new ScrollView(this);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(0,dp(8),0,dp(12));sv.addView(messages,new ScrollView.LayoutParams(-1,-2));main.addView(sv,new LinearLayout.LayoutParams(-1,0,1f));
        bubble("Core AI","What are you in the mood to watch?",false);
        LinearLayout compose=new LinearLayout(this);compose.setOrientation(LinearLayout.HORIZONTAL);
        input=new EditText(this);input.setSingleLine(true);input.setHint("Ask Core AI about your media...");input.setTextColor(Color.WHITE);input.setHintTextColor(0xff70859a);input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v,id,e)->{if(id==EditorInfo.IME_ACTION_SEND){ask();return true;}return false;});
        compose.addView(input,new LinearLayout.LayoutParams(0,dp(58),1f));
        voice=new Button(this);voice.setText("VOICE");voice.setOnClickListener(v->voice());LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(dp(120),dp(58));vp.leftMargin=dp(8);compose.addView(voice,vp);
        ask=new Button(this);ask.setText("ASK");ask.setOnClickListener(v->ask());LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(120),dp(58));ap.leftMargin=dp(8);compose.addView(ask,ap);
        main.addView(compose,new LinearLayout.LayoutParams(-1,dp(58)));
        root.addView(main,new LinearLayout.LayoutParams(0,-1,1f));
        setContentView(root);
    }
    void bubble(String who,String text,boolean me){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(dp(18),dp(13),dp(18),dp(13));wrap.setBackgroundColor(me?0xff0f4f7d:0xff0c2132);
        TextView w=tx(who,12,me?0xff8dcfff:0xff65d98a,true);wrap.addView(w);
        TextView body=tx(text,17,Color.WHITE,false);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(5);wrap.addView(body,bp);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);messages.addView(wrap,lp);
    }
    void ask(){
        String q=input.getText().toString().trim();if(q.length()<2)return;input.setText("");bubble("You",q,true);setBusy(true,"Core AI is thinking...");
        pool.execute(()->{try{
            JSONObject req=new JSONObject();req.put("q",q);JSONObject r=PresenceService.coreCall(this,"/core-tv/v1/core-ai",req);
            if(!r.optBoolean("ok",false)){String er=r.optString("error","ai_unavailable");runOnUiThread(()->{if("subscription_required".equals(er))bubble("Core AI","Core AI is not included with this household's current subscription yet.",false);else bubble("Core AI","I couldn't reach the media assistant right now.",false);setBusy(false,"CORE AI");});return;}
            String ans=r.optString("answer","");JSONArray rec=r.optJSONArray("recommendations"),actions=r.optJSONArray("actions");
            if(rec!=null&&rec.length()>0&&(actions==null||actions.length()==0)){StringBuilder sb=new StringBuilder(ans);sb.append("\n\nSuggestions:");for(int i=0;i<Math.min(5,rec.length());i++)sb.append("\n -  ").append(String.valueOf(rec.opt(i)));ans=sb.toString();}
            final String out=ans;final JSONArray acts=actions==null?new JSONArray():actions;
            runOnUiThread(()->{bubble("Core AI",out.isEmpty()?"I don't have a recommendation yet.":out,false);renderActions(acts);setBusy(false,"CORE AI  -  ready");});
        }catch(Exception e){runOnUiThread(()->{bubble("Core AI","The assistant is unavailable right now. Your media app still works normally.",false);setBusy(false,"CORE AI");});}});
    }
    void renderActions(JSONArray actions){
        if(actions==null||actions.length()==0)return;
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(dp(14),dp(10),dp(14),dp(12));wrap.setBackgroundColor(0xff081c2b);
        wrap.addView(tx("READY ACTIONS",12,0xff1591ff,true));
        for(int i=0;i<Math.min(6,actions.length());i++){JSONObject a=actions.optJSONObject(i);if(a==null)continue;final JSONObject action=a;String type=a.optString("type","search"),title=a.optString("title","Media");String verb="EXPLORE";if("play".equals(type))verb="PLAY";else if("watch_live".equals(type))verb="WATCH LIVE";else if("request".equals(type))verb="REQUEST";Button bt=new Button(this);bt.setText(verb+"  -  "+title);bt.setAllCaps(false);bt.setOnClickListener(v->runAction(action));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.topMargin=dp(7);wrap.addView(bt,lp);}
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.topMargin=dp(10);messages.addView(wrap,wp);
    }
    void runAction(JSONObject a){String type=a.optString("type","search");if("play".equals(type)){playItem(a);return;}if("watch_live".equals(type)){watchLive(a);return;}if("request".equals(type)){confirmRequest(a);return;}String q=a.optString("query",a.optString("title",""));if(!q.isEmpty()){Intent i=new Intent(this,MainActivity.class);i.putExtra("coretv_search_query",q);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);}}
    void playItem(JSONObject a){final String id=a.optString("item_id",""),title=a.optString("title","Media"),mt=a.optString("media_type","");final long ticks=a.optLong("position_ticks",0L);if(id.isEmpty())return;setBusy(true,"Opening "+title+"...");pool.execute(()->{try{JSONObject q=new JSONObject();q.put("item_id",id);q.put("position_ticks",ticks);JSONObject r=PresenceService.streamCall(this,"/core-tv/v1/play",q);if(!r.optBoolean("ok",false))throw new Exception(r.optString("error","play_failed"));String url=PresenceService.resolveStreamUrl(this,r.optString("url",""));if(url.isEmpty())throw new Exception("stream_url_missing");String next="";if("Episode".equals(mt)){try{JSONObject nq=new JSONObject();nq.put("item_id",id);JSONObject nr=PresenceService.coreCall(this,"/core-tv/v1/next-episode",nq);JSONObject ni=nr.optJSONObject("item");if(ni!=null)next=ni.toString();}catch(Exception ignored){}}final String fn=next,fu=url;runOnUiThread(()->{Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",fu);i.putExtra("item_id",id);i.putExtra("name",title);i.putExtra("live",false);i.putExtra("start_ms",ticks/10000L);if(!fn.isEmpty())i.putExtra("next_item_json",fn);startActivity(i);setBusy(false,"CORE AI  -  ready");});}catch(Exception e){runOnUiThread(()->{bubble("Core AI","I found it, but playback could not start right now.",false);setBusy(false,"CORE AI  -  ready");});}});}
    void watchLive(JSONObject a){final String cid=a.optString("channel_id",""),cn=a.optString("channel_name","Channel"),title=a.optString("title","Live TV");final int gid=a.optInt("group_id",0);if(cid.isEmpty())return;setBusy(true,"Tuning "+cn+"...");pool.execute(()->{try{JSONObject lp=new JSONObject();lp.put("channel_id",cid);JSONObject pr=PresenceService.streamCall(this,"/core-tv/v1/live-play",lp);if(!pr.optBoolean("ok",false))throw new Exception(pr.optString("error","live_play_failed"));String url=PresenceService.resolveStreamUrl(this,pr.optString("url",""));if(url.isEmpty())throw new Exception("stream_url_missing");JSONArray queue=new JSONArray();if(gid>0){try{JSONObject gq=new JSONObject();gq.put("group_id",gid);JSONObject gr=PresenceService.coreCall(this,"/core-tv/v1/live",gq);JSONArray ch=gr.optJSONArray("channels");if(ch!=null)for(int x=0;x<ch.length();x++){JSONObject c=ch.optJSONObject(x);if(c==null)continue;JSONObject z=new JSONObject();z.put("id",c.optString("id",""));z.put("name",c.optString("name","Channel"));z.put("number",c.optDouble("channel_number",0));z.put("group_id",c.optInt("group_id",gid));queue.put(z);}}catch(Exception ignored){}}String last="";try{JSONObject sq=new JSONObject();sq.put("action","get");JSONObject st=PresenceService.coreCall(this,"/core-tv/v1/live-state",sq);last=st.optString("last_channel_id","");}catch(Exception ignored){}final String fu=url,fq=queue.toString(),fl=last;runOnUiThread(()->{Intent i=new Intent(this,PlayerActivity.class);i.putExtra("url",fu);i.putExtra("item_id",cid);i.putExtra("name",cn+"  -  "+title);i.putExtra("live",true);i.putExtra("live_queue",fq);i.putExtra("live_group_id",gid);i.putExtra("live_last_channel",fl);startActivity(i);setBusy(false,"CORE AI  -  ready");});}catch(Exception e){runOnUiThread(()->{bubble("Core AI","That channel is mapped, but its stream is unavailable right now.",false);setBusy(false,"CORE AI  -  ready");});}});}
    void confirmRequest(JSONObject a){final String title=a.optString("title","this title"),mt=a.optString("media_type",""),mid=String.valueOf(a.opt("media_id"));new AlertDialog.Builder(this).setTitle("Request "+title+"?").setMessage("Core TV will send this request to your household media service.").setPositiveButton("Request",(d,w)->submitRequest(title,mt,mid)).setNegativeButton("Cancel",null).show();}
    void submitRequest(String title,String mt,String mid){setBusy(true,"Requesting "+title+"...");pool.execute(()->{try{JSONObject q=new JSONObject();q.put("media_type",mt);q.put("media_id",mid);JSONObject r=PresenceService.coreCall(this,"/core-tv/v1/request-create",q);if(!r.optBoolean("ok",false))throw new Exception(r.optString("error","request_failed"));runOnUiThread(()->{bubble("Core AI",title+" has been requested. Core will track when it becomes available.",false);setBusy(false,"CORE AI  -  ready");});}catch(Exception e){runOnUiThread(()->{bubble("Core AI","I couldn't submit that request. It may already exist or requests may be restricted for this profile.",false);setBusy(false,"CORE AI  -  ready");});}});}
    void setBusy(boolean b,String s){ask.setEnabled(!b);voice.setEnabled(!b);state.setText(s);}
    void voice(){try{Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Ask Core AI");startActivityForResult(i,VOICE);}catch(Exception e){Toast.makeText(this,"Voice recognition isn't available on this device.",Toast.LENGTH_LONG).show();}}
    @Override protected void onActivityResult(int rc,int result,Intent data){super.onActivityResult(rc,result,data);if(rc==VOICE&&result==RESULT_OK&&data!=null){ArrayList<String> r=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);if(r!=null&&!r.isEmpty()){input.setText(r.get(0));ask();}}}
    @Override protected void onDestroy(){pool.shutdownNow();super.onDestroy();}
}
