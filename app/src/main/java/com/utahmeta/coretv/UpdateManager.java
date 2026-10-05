package com.utahmeta.coretv;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.Locale;
import org.json.JSONObject;

public final class UpdateManager {
    public interface Listener { void onState(boolean available,String version,String status,boolean busy); }
    private static final String META="https://utahmeta.com/coretv/update.json";
    private static final String META_FALLBACK="https://utahmeta.com/downloads/update.json";
    private static volatile long lastCheckMs=0L;
    private static final long RECHECK_MS=60000L;
    private static final String HELP="https://utahmeta.com/coretv/#install-help";
    private static JSONObject latest;
    private static final String APK="coretv-update.apk";
    private static final String PREF="core_presence";
    private UpdateManager(){}

    private static void state(Activity a,Listener l,boolean av,String v,String s,boolean busy){
        if(l==null)return;
        a.runOnUiThread(()->l.onState(av,v==null?"":v,s==null?"":s,busy));
    }
    private static long currentCode(Context c){
        try{
            if(Build.VERSION.SDK_INT>=28)return c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode();
            return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionCode;
        }catch(Exception e){return 0;}
    }
    private static JSONObject fetchOne(String base) throws Exception {
        URL u=new URL(base+"?t="+System.currentTimeMillis());
        HttpURLConnection h=(HttpURLConnection)u.openConnection();
        h.setConnectTimeout(5000);h.setReadTimeout(7000);h.setRequestProperty("Cache-Control","no-cache");
        try(InputStream in=h.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){
            byte[] z=new byte[8192];int n;while((n=in.read(z))>0)b.write(z,0,n);
            return new JSONObject(b.toString("UTF-8"));
        }finally{h.disconnect();}
    }
    private static JSONObject fetchMeta() throws Exception {
        Exception first=null;
        try{return fetchOne(META);}catch(Exception e){first=e;}
        try{return fetchOne(META_FALLBACK);}catch(Exception e){if(first!=null)e.addSuppressed(first);throw e;}
    }
    public static void check(Activity a,Listener l){
        lastCheckMs=System.currentTimeMillis();
        new Thread(()->{
            try{
                latest=fetchMeta();
                long remote=latest.optLong("versionCode",0),local=currentCode(a);
                boolean available=remote>local;
                String version=latest.optString("versionName","");
                state(a,l,available,version,available?"Update ready":"Up to date",false);
            }catch(Exception e){state(a,l,false,"","",false);}
        },"CoreTV-UpdateCheck").start();
    }
    public static void checkIfDue(Activity a,Listener l){
        long now=System.currentTimeMillis();
        if(now-lastCheckMs<RECHECK_MS)return;
        check(a,l);
    }
    public static void begin(Activity a,Listener l){
        new Thread(()->{
            try{
                if(latest==null){
                    state(a,l,false,"","Checking for update...",true);
                    latest=fetchMeta();
                }
                if(latest.optLong("versionCode",0)<=currentCode(a)){state(a,l,false,latest.optString("versionName",""),"Already up to date",false);return;}
                String url=latest.optString("apkUrl",""),want=latest.optString("sha256","").toLowerCase(Locale.US);
                if(url.isEmpty()||want.length()!=64)throw new IOException("Invalid update metadata");
                state(a,l,true,latest.optString("versionName",""),"Downloading update...",true);
                File dir=new File(a.getCacheDir(),"updates");if(!dir.exists())dir.mkdirs();
                File tmp=new File(dir,APK+".part"),out=new File(dir,APK);
                HttpURLConnection h=(HttpURLConnection)new URL(url+"?v="+latest.optLong("versionCode",0)).openConnection();
                h.setConnectTimeout(8000);h.setReadTimeout(30000);
                long total=h.getContentLengthLong(),done=0,lastPct=-1;
                MessageDigest md=MessageDigest.getInstance("SHA-256");
                try(InputStream in=h.getInputStream();FileOutputStream fo=new FileOutputStream(tmp)){
                    byte[] z=new byte[65536];int n;
                    while((n=in.read(z))>0){
                        fo.write(z,0,n);md.update(z,0,n);done+=n;
                        if(total>0){
                            long pct=(done*100L)/total;
                            if(pct/5!=lastPct/5){lastPct=pct;state(a,l,true,latest.optString("versionName",""),"Downloading "+pct+"%",true);}
                        }
                    }
                }finally{h.disconnect();}
                StringBuilder got=new StringBuilder();for(byte x:md.digest())got.append(String.format(Locale.US,"%02x",x));
                if(!got.toString().equals(want)){tmp.delete();throw new SecurityException("Update verification failed");}
                if(out.exists())out.delete();
                if(!tmp.renameTo(out))throw new IOException("Unable to prepare update");
                a.getSharedPreferences(PREF,0).edit().putBoolean("coretv_update_pending",true).apply();
                state(a,l,true,latest.optString("versionName",""),"Ready to install",false);
                a.runOnUiThread(()->requestInstall(a,l));
            }catch(Exception e){state(a,l,true,latest==null?"":latest.optString("versionName",""),"Update failed - tap to retry",false);}
        },"CoreTV-UpdateDownload").start();
    }
    private static void requestInstall(Activity a,Listener l){
        if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){
            try{
                Intent s=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName()));
                a.startActivity(s);
                state(a,l,true,latest==null?"":latest.optString("versionName",""),"Enable Allow from this source, then return",false);
                return;
            }catch(Exception e){
                try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(HELP)));}catch(Exception ignored){}
                return;
            }
        }
        install(a,l);
    }
    private static void install(Activity a,Listener l){
        File f=new File(new File(a.getCacheDir(),"updates"),APK);
        if(!f.exists()){a.getSharedPreferences(PREF,0).edit().putBoolean("coretv_update_pending",false).apply();return;}
        try{
            Uri u=Uri.parse("content://com.utahmeta.coretv.updates/"+APK);
            Intent i=new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u,"application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            state(a,l,true,latest==null?"":latest.optString("versionName",""),"Android installer opened",false);
        }catch(Exception e){
            try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(HELP)));}catch(Exception ignored){}
        }
    }
    public static void resumePending(Activity a,Listener l){
        boolean pending=a.getSharedPreferences(PREF,0).getBoolean("coretv_update_pending",false);
        if(!pending)return;
        File f=new File(new File(a.getCacheDir(),"updates"),APK);
        if(!f.exists()){a.getSharedPreferences(PREF,0).edit().putBoolean("coretv_update_pending",false).apply();return;}
        if(Build.VERSION.SDK_INT<26||a.getPackageManager().canRequestPackageInstalls()){
            a.getSharedPreferences(PREF,0).edit().putBoolean("coretv_update_pending",false).apply();
            install(a,l);
        }
    }
}
