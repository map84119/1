package com.utahmeta.coretv;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

public class SignInActivity extends Activity {
    void immersive(){try{getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}catch(Exception e){}}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)immersive();}

    static final String LOGIN_URL="https://utahmeta.com/api/coretv/mobile-login";
    static final String LINK_URL="https://utahmeta.com/api/coretv/mobile-link";
    static final String PAIR_START_URL="https://utahmeta.com/api/coretv/pair-start";
    static final String PAIR_STATUS_URL="https://utahmeta.com/api/coretv/pair-status";
    SharedPreferences p;
    EditText identifier,password;
    TextView status;
    Button signIn;
    Handler handler=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle b){
        super.onCreate(b);immersive();
        p=getSharedPreferences("core_presence",MODE_PRIVATE);
        boolean tv=isTelevision();
        setRequestedOrientation(tv?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if(!p.getString("agent_id","").isEmpty()){openMain();return;}
        if(!p.getString("enrollment_token","").isEmpty()){showLinking("Finishing device link...");PresenceService.start(this);waitForEnrollment(0);return;}
        if(tv)showPairing();else showLogin();
    }

    boolean isTelevision(){
        PackageManager pm=getPackageManager();
        return pm.hasSystemFeature("android.software.leanback")||pm.hasSystemFeature("android.hardware.type.television");
    }
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    TextView text(String s,int sp,int color){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);return v;
    }

    String activePairId="";
    int pairPollGeneration=0;

    void showPairing(){
        pairPollGeneration++;
        final int generation=pairPollGeneration;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.HORIZONTAL);root.setGravity(Gravity.CENTER);
        root.setPadding(dp(34),dp(28),dp(34),dp(28));root.setBackgroundColor(Color.rgb(4,12,21));

        LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);left.setGravity(Gravity.CENTER);
        ImageView qr=new ImageView(this);qr.setAdjustViewBounds(true);
        LinearLayout.LayoutParams qrp=new LinearLayout.LayoutParams(dp(330),dp(330));left.addView(qr,qrp);
        TextView url=text("Creating secure phone link...",13,Color.rgb(125,145,165));url.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(dp(380),-2);up.topMargin=dp(10);left.addView(url,up);

        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setGravity(Gravity.CENTER_VERTICAL);
        right.setPadding(dp(38),0,0,0);
        TextView brand=text("UtahMeta",25,Color.WHITE);brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);right.addView(brand);
        TextView core=text("CORE TV",14,Color.rgb(21,145,255));core.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);right.addView(core);
        TextView title=text("Link this TV with your phone",30,Color.WHITE);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(24);right.addView(title,tp);
        TextView sub=text("Scan the QR code. On your phone you can sign in to an existing UtahMeta household or create a new household.",16,Color.rgb(159,178,199));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(10);right.addView(sub,sp);
        TextView codeLabel=text("PAIRING CODE",12,Color.rgb(125,145,165));LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(-1,-2);clp.topMargin=dp(24);right.addView(codeLabel,clp);
        TextView code=text("--------",30,Color.WHITE);code.setTypeface(android.graphics.Typeface.MONOSPACE,android.graphics.Typeface.BOLD);code.setLetterSpacing(.14f);right.addView(code);
        status=text("Creating secure pairing session...",14,Color.rgb(159,178,199));LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(-1,-2);stp.topMargin=dp(14);right.addView(status,stp);

        Button direct=new Button(this);direct.setText("SIGN IN ON TV INSTEAD");LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(-1,dp(54));dp1.topMargin=dp(24);right.addView(direct,dp1);
        direct.setOnClickListener(v->{pairPollGeneration++;showLogin();});
        Button refresh=new Button(this);refresh.setText("NEW QR CODE");LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(50));rp.topMargin=dp(10);right.addView(refresh,rp);
        refresh.setEnabled(false);refresh.setOnClickListener(v->showPairing());

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(400),-2);root.addView(left,lp);
        LinearLayout.LayoutParams rpane=new LinearLayout.LayoutParams(dp(590),-2);root.addView(right,rpane);
        setContentView(root);

        new Thread(()->{
            try{
                JSONObject body=new JSONObject();body.put("device_name",deviceName());
                JSONObject r=postJson(PAIR_START_URL,body);
                if(!r.optBoolean("ok",false))throw new Exception(r.optString("error","pair_start_failed"));
                final String pid=r.optString("pair_id",""),pairUrl=r.optString("pair_url",""),pairCode=r.optString("code","");
                if(pid.isEmpty()||pairUrl.isEmpty())throw new Exception("pair_start_incomplete");
                activePairId=pid;
                final Bitmap bitmap=qrBitmap(pairUrl,720);
                runOnUiThread(()->{
                    if(generation!=pairPollGeneration)return;
                    qr.setImageBitmap(bitmap);
                    code.setText(pairCode);
                    url.setText("Scan the QR code with your phone");
                    setStatus("Waiting for your phone...");
                    refresh.setEnabled(true);
                    pollPair(pid,generation,0);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(generation!=pairPollGeneration)return;
                    url.setText("Could not create QR code.");
                    setStatus("UtahMeta could not create the phone link. You can sign in on the TV or try again.");
                    refresh.setEnabled(true);
                });
            }
        },"CoreTV-PairStart").start();
    }

    Bitmap qrBitmap(String value,int size)throws Exception{
        com.google.zxing.common.BitMatrix bits=new com.google.zxing.qrcode.QRCodeWriter().encode(value,com.google.zxing.BarcodeFormat.QR_CODE,size,size);
        Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);
        int white=Color.WHITE,black=Color.BLACK;
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)b.setPixel(x,y,bits.get(x,y)?black:white);
        return b;
    }

    void pollPair(String pid,int generation,int attempts){
        if(generation!=pairPollGeneration||isFinishing())return;
        if(attempts>310){
            setStatus("Pairing expired. Choose NEW QR CODE to try again.");
            return;
        }
        handler.postDelayed(()->{
            if(generation!=pairPollGeneration||isFinishing())return;
            new Thread(()->{
                try{
                    JSONObject b=new JSONObject();b.put("pair_id",pid);
                    JSONObject r=postJson(PAIR_STATUS_URL,b);
                    String state=r.optString("state","");
                    if(r.optBoolean("ok",false)&&"linked".equals(state)){
                        runOnUiThread(()->{
                            if(generation!=pairPollGeneration)return;
                            setStatus("Phone approved. Linking this TV...");
                            completeLink(r);
                        });
                        return;
                    }
                    runOnUiThread(()->{
                        if(generation==pairPollGeneration){
                            int remain=r.optInt("expires_in",0);
                            setStatus(remain>0?"Waiting for your phone...  "+remain+"s":"Waiting for your phone...");
                            pollPair(pid,generation,attempts+1);
                        }
                    });
                }catch(Exception e){
                    runOnUiThread(()->{
                        if(generation==pairPollGeneration)pollPair(pid,generation,attempts+1);
                    });
                }
            },"CoreTV-PairPoll").start();
        },1800);
    }

    void showLogin(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(4,12,21));
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(28),dp(30),dp(28),dp(30));scroll.addView(outer,new ScrollView.LayoutParams(-1,-1));
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(28),dp(26),dp(28),dp(26));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(10,24,39));bg.setCornerRadius(dp(18));bg.setStroke(dp(1),Color.rgb(28,59,86));card.setBackground(bg);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(isTelevision()?dp(620):Math.min(dp(520),getResources().getDisplayMetrics().widthPixels-dp(32)),-2);
        outer.addView(card,cp);

        TextView brand=text("UtahMeta",24,Color.WHITE);brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);card.addView(brand);
        TextView core=text("CORE TV",14,Color.rgb(21,145,255));core.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);card.addView(core);
        TextView title=text("Sign in to Core TV",30,Color.WHITE);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(28);card.addView(title,tp);
        TextView sub=text("Use your UtahMeta email or username. This links this device to your household.",16,Color.rgb(159,178,199));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(8);sp.bottomMargin=dp(18);card.addView(sub,sp);

        identifier=new EditText(this);identifier.setHint("Email or username");identifier.setSingleLine(true);
        identifier.setTextColor(Color.WHITE);identifier.setHintTextColor(Color.rgb(125,145,165));identifier.setTextSize(17);
        identifier.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        identifier.setImeOptions(EditorInfo.IME_ACTION_NEXT);identifier.setPadding(dp(14),dp(12),dp(14),dp(12));card.addView(identifier,new LinearLayout.LayoutParams(-1,dp(54)));

        password=new EditText(this);password.setHint("Password");password.setSingleLine(true);
        password.setTextColor(Color.WHITE);password.setHintTextColor(Color.rgb(125,145,165));password.setTextSize(17);
        password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);password.setPadding(dp(14),dp(12),dp(14),dp(12));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(54));pp.topMargin=dp(12);card.addView(password,pp);

        signIn=new Button(this);signIn.setText("SIGN IN & LINK THIS DEVICE");signIn.setTextSize(15);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(56));bp.topMargin=dp(18);card.addView(signIn,bp);
        signIn.setOnClickListener(v->beginLogin());
        password.setOnEditorActionListener((v,id,e)->{if(id==EditorInfo.IME_ACTION_DONE){beginLogin();return true;}return false;});

        status=text("",14,Color.rgb(255,170,120));status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(-1,-2);stp.topMargin=dp(12);card.addView(status,stp);

        Button create=new Button(this);create.setText("CREATE A UTAHMETA ACCOUNT");
        LinearLayout.LayoutParams ctp=new LinearLayout.LayoutParams(-1,dp(50));ctp.topMargin=dp(12);card.addView(create,ctp);
        create.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://portal.utahmeta.com/signup")));}catch(Exception ignored){}});

        if(isTelevision()){
            Button phone=new Button(this);phone.setText("USE PHONE / QR INSTEAD");
            LinearLayout.LayoutParams ph=new LinearLayout.LayoutParams(-1,dp(50));ph.topMargin=dp(10);card.addView(phone,ph);
            phone.setOnClickListener(v->showPairing());
        }

        TextView note=text("Your password is used only for this sign-in request. Core TV does not save it on the device.",12,Color.rgb(125,145,165));
        note.setGravity(Gravity.CENTER);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=dp(16);card.addView(note,np);
        setContentView(scroll);identifier.requestFocus();
    }

    void beginLogin(){
        if(signIn==null)return;
        final String id=identifier.getText().toString().trim();
        final String pw=password.getText().toString();
        password.setText("");
        if(id.isEmpty()||pw.isEmpty()){setStatus("Enter your email/username and password.");return;}
        signIn.setEnabled(false);setStatus("Signing in to UtahMeta...");
        new Thread(()->{
            try{
                JSONObject b=new JSONObject();b.put("identifier",id);b.put("password",pw);b.put("device_name",deviceName());
                JSONObject r=postJson(LOGIN_URL,b);
                if(!r.optBoolean("ok",false)){fail(r.optString("error","sign_in_failed"));return;}
                if(r.optBoolean("requires_household",false)){showHouseholds(r);return;}
                completeLink(r);
            }catch(Exception e){fail("connection_error");}
        },"CoreTV-SignIn").start();
    }

    void showHouseholds(JSONObject r){
        final String ticket=r.optString("ticket","");
        final JSONArray hs=r.optJSONArray("households");
        if(ticket.isEmpty()||hs==null||hs.length()==0){fail("no_active_household");return;}
        final String[] labels=new String[hs.length()];final String[] ids=new String[hs.length()];
        for(int i=0;i<hs.length();i++){JSONObject h=hs.optJSONObject(i);ids[i]=h==null?"":h.optString("id","");labels[i]=(h==null?"Household":h.optString("name","Household"))+"  -  "+(h==null?"member":h.optString("role","member"));}
        runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Choose household").setItems(labels,(d,which)->linkHousehold(ticket,ids[which]))
            .setOnCancelListener(d->{if(signIn!=null)signIn.setEnabled(true);setStatus("Sign-in canceled.");}).show());
    }

    void linkHousehold(String ticket,String hid){
        setStatus("Linking this device...");
        new Thread(()->{
            try{JSONObject b=new JSONObject();b.put("ticket",ticket);b.put("household_id",hid);JSONObject r=postJson(LINK_URL,b);
                if(!r.optBoolean("ok",false)){fail(r.optString("error","link_failed"));return;}completeLink(r);
            }catch(Exception e){fail("connection_error");}
        },"CoreTV-Link").start();
    }

    void completeLink(JSONObject r){
        String token=r.optString("enrollment_token",""),gateway=r.optString("gateway",""),stream=r.optString("stream_gateway","");
        if(token.isEmpty()||gateway.isEmpty()){fail("link_failed");return;}
        JSONObject h=r.optJSONObject("household");
        p.edit().putString("enrollment_token",token).putString("gateway",gateway).putString("stream_gateway",stream)
            .putString("agent_name",deviceName()).putString("coretv_signed_in_name",r.optString("display_name",""))
            .putString("coretv_household_name",h==null?"":h.optString("name","")).apply();
        runOnUiThread(()->{showLinking("Signed in. Linking this device to Core...");PresenceService.start(this);waitForEnrollment(0);});
    }

    void showLinking(String message){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);
        root.setPadding(dp(30),dp(30),dp(30),dp(30));root.setBackgroundColor(Color.rgb(4,12,21));
        TextView b=text("UtahMeta",28,Color.WHITE);b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);root.addView(b);
        TextView c=text("CORE TV",15,Color.rgb(21,145,255));c.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);root.addView(c);
        ProgressBar bar=new ProgressBar(this);LinearLayout.LayoutParams bpp=new LinearLayout.LayoutParams(dp(56),dp(56));bpp.topMargin=dp(28);root.addView(bar,bpp);
        status=text(message,17,Color.WHITE);status.setGravity(Gravity.CENTER);LinearLayout.LayoutParams s=new LinearLayout.LayoutParams(-1,-2);s.topMargin=dp(22);root.addView(status,s);
        setContentView(root);
    }

    void waitForEnrollment(int n){
        handler.postDelayed(()->{
            if(isFinishing())return;
            if(!p.getString("agent_id","").isEmpty()){openMain();return;}
            if(n<40){waitForEnrollment(n+1);return;}
            showLinkRetry();
        },500);
    }
    void showLinkRetry(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(30),dp(30),dp(30),dp(30));root.setBackgroundColor(Color.rgb(4,12,21));
        TextView t=text("Device link is taking longer than expected.",20,Color.WHITE);t.setGravity(Gravity.CENTER);root.addView(t);
        Button retry=new Button(this);retry.setText("TRY LINK AGAIN");LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(54));rp.topMargin=dp(20);root.addView(retry,rp);
        retry.setOnClickListener(v->{showLinking("Trying the device link again...");PresenceService.start(this);waitForEnrollment(0);});
        Button back=new Button(this);back.setText("BACK TO SIGN IN");LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(54));bp.topMargin=dp(10);root.addView(back,bp);
        back.setOnClickListener(v->{p.edit().remove("enrollment_token").apply();showLogin();});
        setContentView(root);
    }

    void fail(String code){
        final String m=friendly(code);
        runOnUiThread(()->{if(signIn!=null)signIn.setEnabled(true);setStatus(m);});
    }
    String friendly(String e){
        if("invalid_credentials".equals(e))return "Email/username or password is incorrect.";
        if("too_many_login_attempts".equals(e))return "Too many sign-in attempts. Try again in a few minutes.";
        if("no_active_household".equals(e))return "This UtahMeta account is not attached to an active household.";
        if("link_session_expired".equals(e))return "The link session expired. Sign in again.";
        if("connection_error".equals(e)||"mobile_auth_unavailable".equals(e))return "UtahMeta could not be reached. Check your connection and try again.";
        return "Could not link this device. Please try again.";
    }
    void setStatus(String s){if(status!=null)status.setText(s);}
    String deviceName(){String m=(Build.MANUFACTURER==null?"Android":Build.MANUFACTURER).trim();String model=(Build.MODEL==null?"Device":Build.MODEL).trim();return (m+" "+model).trim();}

    JSONObject postJson(String urlText,JSONObject data)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(urlText).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","UtahMeta-CoreTV-Mobile/0.3");c.setDoOutput(true);
        byte[] raw=data.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(raw.length);
        try(OutputStream out=c.getOutputStream()){out.write(raw);}
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String body=read(in);c.disconnect();JSONObject r;
        try{r=body.isEmpty()?new JSONObject():new JSONObject(body);}catch(Exception e){r=new JSONObject();}
        r.put("_http",code);return r;
    }
    String read(InputStream in)throws Exception{if(in==null)return "";try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=x.read(b))>=0)o.write(b,0,n);return o.toString("UTF-8");}}
    void openMain(){Intent i=new Intent(this,ProfileActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);finish();}
}
