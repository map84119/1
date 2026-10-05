package com.utahmeta.coretv;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

public class ProfileActivity extends Activity {
    void immersive(){try{getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}catch(Exception e){}}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)immersive();}

    SharedPreferences p;
    LinearLayout cards; HorizontalScrollView profileScroll;
    TextView subtitle,status; Button continueBtn; String currentSelectedId="",currentSelectedName=""; boolean currentSelectedPin=false,currentSelectedChild=false;
    ExecutorService pool=Executors.newSingleThreadExecutor();
    boolean force;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);immersive();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        p=getSharedPreferences("core_presence",MODE_PRIVATE);
        force=getIntent().getBooleanExtra("force",false);
        if(p.getString("agent_id","").isEmpty()){startActivity(new Intent(this,SignInActivity.class));finish();return;}
        build();
        loadProfiles();
    }
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    TextView tx(String s,int z,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(color);if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);return v;}
    GradientDrawable box(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}
    void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_HORIZONTAL);root.setPadding(dp(44),dp(30),dp(44),dp(26));root.setBackgroundColor(Color.rgb(3,10,18));
        TextView brand=tx("UtahMeta",22,Color.WHITE,true);root.addView(brand);
        TextView core=tx("CORE TV",13,Color.rgb(20,145,255),true);root.addView(core);
        TextView title=tx("Who's watching?",38,Color.WHITE,true);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.topMargin=dp(18);root.addView(title,tp);
        subtitle=tx("Choose who is watching. History, recommendations, requests and My List stay personal to each profile.",16,Color.rgb(155,174,194),false);subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);sp.topMargin=dp(5);root.addView(subtitle,sp);

        profileScroll=new HorizontalScrollView(this);profileScroll.setHorizontalScrollBarEnabled(false);profileScroll.setFillViewport(true);profileScroll.setFocusable(false);profileScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        cards=new LinearLayout(this);cards.setOrientation(LinearLayout.HORIZONTAL);cards.setGravity(Gravity.CENTER_VERTICAL);cards.setPadding(dp(8),dp(12),dp(8),dp(12));profileScroll.addView(cards,new HorizontalScrollView.LayoutParams(-2,-1));
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,0,1f);hp.topMargin=dp(20);root.addView(profileScroll,hp);

        status=tx("Loading household profiles...",14,Color.rgb(155,174,194),false);status.setGravity(Gravity.CENTER);root.addView(status,new LinearLayout.LayoutParams(-1,dp(28)));

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setGravity(Gravity.CENTER);
        Button manage=new Button(this);manage.setText("ADD / MANAGE PROFILES");manage.setOnClickListener(v->openWeb("https://portal.utahmeta.com/portal"));
        Button link=new Button(this);link.setText("LINK ANOTHER DEVICE");link.setOnClickListener(v->openWeb("https://utahmeta.com/coretv/"));
        continueBtn=new Button(this);continueBtn.setText("CONTINUE AS CURRENT PROFILE");continueBtn.setOnClickListener(v->continueCurrent());
        actions.addView(manage,new LinearLayout.LayoutParams(dp(250),dp(50)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(250),dp(50));lp.leftMargin=dp(10);actions.addView(link,lp);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(285),dp(50));cp.leftMargin=dp(10);actions.addView(continueBtn,cp);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(55));ap.topMargin=dp(10);root.addView(actions,ap);
        setContentView(root);
    }
    void loadProfiles(){
        pool.execute(()->{
            try{
                JSONObject res=PresenceService.coreCall(this,"/core-tv/v1/profiles",new JSONObject());
                JSONArray ar=res.optJSONArray("profiles");if(ar==null)ar=new JSONArray();
                String selected=res.optString("selected_customer_id","");
                final JSONArray rows=ar;final String sel=selected;
                runOnUiThread(()->render(rows,sel));
            }catch(Exception e){runOnUiThread(()->status.setText("Could not load household profiles. Check Core connection and try again."));}
        });
    }
    void render(JSONArray rows,String selected){
        cards.removeAllViews();currentSelectedId="";currentSelectedName="";currentSelectedPin=false;currentSelectedChild=false;
        if(rows.length()==0){status.setText("No active household profiles are ready yet.");return;}
        status.setText(rows.length()+" household profile"+(rows.length()==1?"":"s")+"  -  each device can watch independently");
        int[] palette={0xff1591ff,0xff8b5cf6,0xff22c55e,0xfff59e0b,0xffef4444,0xff06b6d4,0xffec4899,0xff84cc16};
        for(int i=0;i<rows.length();i++){
            JSONObject o=rows.optJSONObject(i);if(o==null)continue;
            String cid=o.optString("customer_id",""),name=o.optString("display_name","Profile"),role=o.optString("role","member");
            boolean child=o.optBoolean("is_child",false),ready=o.optBoolean("jellyfin_ready",false),active=cid.equals(selected),pinRequired=o.optBoolean("pin_required",false);
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);card.setPadding(dp(14),dp(15),dp(14),dp(14));
            card.setBackground(box(active?0xff123d62:0xff0b1b2a,18,active?0xff1591ff:0xff18364f));
            TextView avatar=tx(name.isEmpty()?"?":name.substring(0,1).toUpperCase(),34,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);
            GradientDrawable ag=new GradientDrawable();ag.setShape(GradientDrawable.OVAL);ag.setColor(palette[Math.abs(o.optInt("avatar_seed",i))%palette.length]);avatar.setBackground(ag);
            card.addView(avatar,new LinearLayout.LayoutParams(dp(84),dp(84)));
            TextView nm=tx(name,20,Color.WHITE,true);nm.setGravity(Gravity.CENTER);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=dp(14);card.addView(nm,np);
            TextView meta=tx((child?"KIDS MODE  -  ":"")+(pinRequired?"PIN LOCKED  -  ":"")+role.toUpperCase(),12,child?0xffffd166:0xff9fb2c7,true);meta.setGravity(Gravity.CENTER);card.addView(meta,new LinearLayout.LayoutParams(-1,dp(25)));
            TextView rdy=tx(ready?"Ready to watch":"Media setup pending",12,ready?0xff65d98a:0xffffb454,false);rdy.setGravity(Gravity.CENTER);card.addView(rdy,new LinearLayout.LayoutParams(-1,dp(24)));
            if(active){currentSelectedId=cid;currentSelectedName=name;currentSelectedPin=pinRequired;currentSelectedChild=child;TextView cur=tx("CURRENT ON THIS DEVICE",11,0xff1591ff,true);cur.setGravity(Gravity.CENTER);card.addView(cur,new LinearLayout.LayoutParams(-1,dp(22)));if(continueBtn!=null)continueBtn.setText(pinRequired?"UNLOCK "+name.toUpperCase():"CONTINUE AS "+name.toUpperCase());}
            final int cardIndex=i;
            card.setTag(cid);card.setFocusable(true);card.setFocusableInTouchMode(false);card.setClickable(true);card.setOnClickListener(v->selectProfile(cid,name,pinRequired,child));
            card.setOnFocusChangeListener((v,has)->{card.setBackground(box(has?0xff14304a:(active?0xff123d62:0xff0b1b2a),18,has?0xff66baff:(active?0xff1591ff:0xff18364f)));if(has&&profileScroll!=null){profileScroll.post(()->{int vw=profileScroll.getWidth(),cw=v.getWidth();int target=Math.max(0,v.getLeft()-Math.max(0,(vw-cw)/2));profileScroll.smoothScrollTo(target,0);});}});
            card.setOnKeyListener((v,key,e)->{if(e.getAction()!=KeyEvent.ACTION_DOWN)return false;if(key==KeyEvent.KEYCODE_DPAD_RIGHT){int n=cardIndex+1;if(n<cards.getChildCount()){cards.getChildAt(n).requestFocus();return true;}}if(key==KeyEvent.KEYCODE_DPAD_LEFT){int n=cardIndex-1;if(n>=0){cards.getChildAt(n).requestFocus();return true;}}return false;});
            LinearLayout.LayoutParams cpar=new LinearLayout.LayoutParams(dp(210),dp(245));if(i>0)cpar.leftMargin=dp(18);cards.addView(card,cpar);
        }
        if(cards.getChildCount()>0){profileScroll.post(()->{View target=null;if(selected!=null&&!selected.isEmpty()){for(int j=0;j<cards.getChildCount();j++){View v=cards.getChildAt(j);if(selected.equals(String.valueOf(v.getTag()))){target=v;break;}}}if(target==null)target=cards.getChildAt(0);target.requestFocus();});}
    }
    void continueCurrent(){if(currentSelectedId.isEmpty()){status.setText("Choose a profile first.");return;}selectProfile(currentSelectedId,currentSelectedName,currentSelectedPin,currentSelectedChild);}
    void selectProfile(String cid,String name,boolean pinRequired,boolean child){if(pinRequired){promptPin(cid,name,child);return;}doSelectProfile(cid,name,"",child);}
    void promptPin(String cid,String name,boolean child){
        final EditText input=new EditText(this);input.setSingleLine(true);input.setHint("4-8 digit PIN");input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("Unlock "+name).setMessage("Enter this profile's PIN.").setView(input)
          .setPositiveButton("Unlock",(d,w)->{String pin=input.getText().toString().trim();if(pin.length()<4||pin.length()>8){status.setText("PIN must be 4-8 digits.");return;}doSelectProfile(cid,name,pin,child);})
          .setNegativeButton("Cancel",null).show();
    }
    void doSelectProfile(String cid,String name,String pin,boolean child){
        status.setText("Opening "+name+"...");
        pool.execute(()->{
            try{
                JSONObject q=new JSONObject();q.put("customer_id",cid);if(pin!=null&&!pin.isEmpty())q.put("pin",pin);
                JSONObject r=PresenceService.coreCall(this,"/core-tv/v1/profile-select",q);
                if(!r.optBoolean("ok",false))throw new Exception(r.optString("error","profile_select_failed"));
                JSONObject access=r.optJSONObject("access");boolean isChild=access!=null?access.optBoolean("is_child",child):child;
                p.edit().putString("coretv_profile_id",cid).putString("coretv_profile_name",name).putBoolean("coretv_profile_child",isChild).putString("coretv_bootstrap_json",r.toString()).remove("coretv_library_cache").remove("coretv_live_cache").remove("coretv_guide_cache").apply();
                PresenceService.start(this);
                runOnUiThread(this::openMain);
            }catch(Exception e){runOnUiThread(()->status.setText(pin!=null&&!pin.isEmpty()?"PIN incorrect or profile unavailable.":"Could not switch profile. Please try again."));}
        });
    }
    void openMain(){Intent i=new Intent(this,UtahMetaHomeActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);finish();}
    void openWeb(String u){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u)));}catch(Exception ignored){}}
    @Override protected void onDestroy(){pool.shutdownNow();super.onDestroy();}
}
