package com.utahmeta.coretv;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class PresenceService extends Service {
    private static final String TAG = "CorePresence";
    private static final String CHANNEL = "utahmeta_core_presence";
    private static final int NOTIFY_ID = 933;
    private static final String[] CAPS = new String[] {
            "identity.stable",
            "telemetry.system",
            "androidtv.state",
            "androidtv.apps",
            "androidtv.package_inventory",
            "heartbeat.signed"
    };
    private ScheduledExecutorService worker;

    public static void start(Context c) {
        Intent i = new Intent(c, PresenceService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFY_ID, notification("Starting"));
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(this::tick, 0, 20, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("core_presence", Context.MODE_PRIVATE);
    }

    private void tick() {
        try {
            SharedPreferences p = prefs();
            String agentId = p.getString("agent_id", "");
            if (agentId.isEmpty()) {
                String token = p.getString("enrollment_token", "");
                if (!token.isEmpty()) enroll(token);
            }
            p = prefs();
            agentId = p.getString("agent_id", "");
            String secret = p.getString("agent_secret", "");
            String heartbeatUrl = p.getString("heartbeat_url", "");
            if (!agentId.isEmpty() && !secret.isEmpty() && !heartbeatUrl.isEmpty()) {
                heartbeat(agentId, secret, heartbeatUrl); try{bootstrap(agentId,secret);}catch(Throwable e){prefs().edit().putString("coretv_bootstrap_error",e.getClass().getSimpleName()).apply();}
            } else {
                setStatus("Not enrolled");
            }
        } catch (Throwable t) {
            Log.e(TAG, "tick", t);
            setStatus("Attention");
        }
    }

    private void enroll(String token) throws Exception {
        SharedPreferences p = prefs();
        String gateway = p.getString("gateway", "https://192.168.50.200:8140");
        String name = p.getString("agent_name", Build.MODEL == null ? "Android TV" : Build.MODEL);
        JSONObject body = new JSONObject();
        body.put("token", token);
        body.put("name", name);
        body.put("platform", isTelevision() ? "androidtv" : "android");
        body.put("capabilities", capabilitiesJson());
        body.put("local_test", false);
        JSONObject r = new JSONObject(postJson(gateway.replaceAll("/+$", "") + "/enroll", body.toString()));
        if (!r.optBoolean("ok", false)) throw new Exception("enrollment_failed");
        String agentId = r.optString("agent_id", "");
        String secret = r.optString("agent_secret", "");
        String heartbeatUrl = r.optString("heartbeat_url", "");
        if (agentId.isEmpty() || secret.isEmpty() || heartbeatUrl.isEmpty()) throw new Exception("enrollment_incomplete");
        p.edit()
                .putString("agent_id", agentId)
                .putString("agent_secret", secret)
                .putString("heartbeat_url", heartbeatUrl)
                .remove("enrollment_token")
                .apply();
        Log.i(TAG, "enrolled " + agentId);
        setStatus("Enrolled");
    }

    private void heartbeat(String agentId, String secret, String heartbeatUrl) throws Exception {
        JSONObject snap = snapshot();
        String payloadB64 = Base64.encodeToString(snap.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        long ts = System.currentTimeMillis() / 1000L;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String material = agentId + "|" + ts + "|" + nonce + "|" + payloadB64;
        String sig = hmacHex(secret, material);
        JSONObject body = new JSONObject();
        body.put("agent_id", agentId);
        body.put("ts", ts);
        body.put("nonce", nonce);
        body.put("payload_b64", payloadB64);
        body.put("sig", sig);
        String response = postJson(heartbeatUrl, body.toString());
        JSONObject r = response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
        if (r.has("ok") && !r.optBoolean("ok", false)) throw new Exception("heartbeat_rejected");
        prefs().edit().putLong("last_heartbeat_ms", System.currentTimeMillis()).apply();
        setStatus("Connected");
    }

    // CORETV_BOOTSTRAP_V1
    private void bootstrap(String id,String sec)throws Exception{SharedPreferences p=prefs();String g=p.getString("gateway","https://192.168.50.200:8140").replaceAll("/+$",""),path="/core-tv/v1/bootstrap",pb=Base64.encodeToString("{}".getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);long ts=System.currentTimeMillis()/1000L;String n=UUID.randomUUID().toString().replace("-",""),mat=id+"|"+ts+"|"+n+"|"+path+"|"+pb;JSONObject b=new JSONObject();b.put("agent_id",id);b.put("ts",ts);b.put("nonce",n);b.put("payload_b64",pb);b.put("sig",hmacHex(sec,mat));JSONObject r=new JSONObject(postJson(g+path,b.toString()));if(!r.optBoolean("ok",false))throw new Exception("bootstrap_rejected");p.edit().putString("coretv_bootstrap_json",r.toString()).putLong("coretv_bootstrap_ms",System.currentTimeMillis()).remove("coretv_bootstrap_error").apply();}


    // CORETV_SIGNED_API_V2
    public static JSONObject coreCall(Context ctx, String path, JSONObject payload) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences("core_presence", Context.MODE_PRIVATE);
        String id = p.getString("agent_id", "");
        String sec = p.getString("agent_secret", "");
        String g = p.getString("gateway", "https://192.168.50.200:8140").replaceAll("/+$", "");
        if (id.isEmpty() || sec.isEmpty()) throw new Exception("not_enrolled");
        String pj = payload == null ? "{}" : payload.toString();
        String pb = Base64.encodeToString(pj.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        long ts = System.currentTimeMillis() / 1000L;
        String n = UUID.randomUUID().toString().replace("-", "");
        String mat = id + "|" + ts + "|" + n + "|" + path + "|" + pb;
        JSONObject b = new JSONObject();
        b.put("agent_id", id); b.put("ts", ts); b.put("nonce", n); b.put("payload_b64", pb);
        b.put("sig", hmacHexStatic(sec, mat));
        return new JSONObject(postJsonStatic(ctx, g + path, b.toString()));
    }

    // CORETV_PLAYBACK_API_V1
    public static JSONObject streamCall(Context ctx, String path, JSONObject payload) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences("core_presence", Context.MODE_PRIVATE);
        String id = p.getString("agent_id", "");
        String sec = p.getString("agent_secret", "");
        if (id.isEmpty() || sec.isEmpty()) throw new Exception("not_enrolled");
        String pj = payload == null ? "{}" : payload.toString();
        String pb = Base64.encodeToString(pj.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        long ts = System.currentTimeMillis() / 1000L;
        String n = UUID.randomUUID().toString().replace("-", "");
        String mat = id + "|" + ts + "|" + n + "|" + path + "|" + pb;
        JSONObject b = new JSONObject();
        b.put("agent_id", id);
        b.put("ts", ts);
        b.put("nonce", n);
        b.put("payload_b64", pb);
        b.put("sig", hmacHexStatic(sec, mat));
        String sg = p.getString("stream_gateway", "http://192.168.50.200:8141").replaceAll("/+$", "");
        return new JSONObject(postJsonAnyStatic(ctx, sg + path, b.toString()));
    }

    private boolean isTelevision() {
        try { return getPackageManager().hasSystemFeature("android.software.leanback") || getPackageManager().hasSystemFeature("android.hardware.type.television"); }
        catch (Throwable t) { return false; }
    }
    private static boolean isPublicUtahMeta(URL url) {
        String h = url.getHost() == null ? "" : url.getHost().toLowerCase(java.util.Locale.US);
        return h.equals("utahmeta.com") || h.endsWith(".utahmeta.com");
    }
    private static String postJsonAnyStatic(Context ctx, String urlText, String json) throws Exception {
        URL u = new URL(urlText);
        return "https".equalsIgnoreCase(u.getProtocol()) ? postJsonStatic(ctx, urlText, json) : postJsonHttpStatic(urlText, json);
    }
    public static String resolveStreamUrl(Context ctx, String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        SharedPreferences p = ctx.getSharedPreferences("core_presence", Context.MODE_PRIVATE);
        String sg = p.getString("stream_gateway", "").replaceAll("/+$", "");
        String local = "http://192.168.50.200:8141";
        if (!sg.isEmpty() && raw.startsWith(local)) return sg + raw.substring(local.length());
        return raw;
    }

    private static String postJsonHttpStatic(String urlText, String json) throws Exception {
        URL url = new URL(urlText);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "UtahMeta-CoreTV/0.3");
        c.setDoOutput(true);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : readTextStatic(in);
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("http_" + code + ":" + text);
        return text;
    }
    private static String hmacHexStatic(String secretB64, String material) throws Exception {
        byte[] key = Base64.decode(secretB64, Base64.DEFAULT);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] raw = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
    private static String postJsonStatic(Context ctx, String urlText, String json) throws Exception {
        URL url = new URL(urlText);
        HttpsURLConnection c = (HttpsURLConnection)url.openConnection();
        if (!isPublicUtahMeta(url)) {
            c.setSSLSocketFactory(pinnedSslStatic(ctx).getSocketFactory());
            c.setHostnameVerifier((host, session) -> true);
        }
        c.setConnectTimeout(10000); c.setReadTimeout(30000);
        c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "UtahMeta-CoreTV/0.3"); c.setDoOutput(true);
        byte[] body = json.getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : readTextStatic(in); c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("http_" + code + ":" + text);
        return text;
    }
    private static SSLContext pinnedSslStatic(Context ctx) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (InputStream in = ctx.getResources().openRawResource(R.raw.gateway)) { cert = cf.generateCertificate(in); }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null, null); ks.setCertificateEntry("core-gateway", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(ks);
        SSLContext sc = SSLContext.getInstance("TLS"); sc.init(null, tmf.getTrustManagers(), null); return sc;
    }
    private static String readTextStatic(InputStream in) throws Exception {
        try (InputStream x = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = x.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
    private JSONObject snapshot() throws Exception {
        JSONObject o = new JSONObject();
        SharedPreferences p = prefs();
        String name = p.getString("agent_name", Build.MODEL == null ? "Android TV" : Build.MODEL);
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        o.put("computer", name);
        o.put("device_id", "android:" + (androidId == null ? "unknown" : androidId));
        o.put("platform", isTelevision() ? "androidtv" : "android");
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("model", Build.MODEL);
        o.put("product", Build.PRODUCT);
        o.put("device", Build.DEVICE);
        o.put("os_caption", (isTelevision() ? "Android TV " : "Android ") + Build.VERSION.RELEASE);
        o.put("os_version", Build.VERSION.RELEASE);
        o.put("sdk_int", Build.VERSION.SDK_INT);
        o.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000L);
        o.put("network_online", networkOnline());
        o.put("capabilities", capabilitiesJson());
        o.put("utc", Instant.now().toString());

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            am.getMemoryInfo(mi);
            o.put("memory_total_bytes", mi.totalMem);
            o.put("memory_free_bytes", mi.availMem);
        }

        JSONArray packages = new JSONArray();
        try {
            List<PackageInfo> rows = getPackageManager().getInstalledPackages(0);
            o.put("installed_package_count", rows.size());
            int limit = Math.min(rows.size(), 300);
            for (int i = 0; i < limit; i++) packages.put(rows.get(i).packageName);
        } catch (Throwable t) {
            o.put("installed_package_count", JSONObject.NULL);
        }
        o.put("installed_packages", packages);
        return o;
    }

    private boolean networkOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                return n != null;
            }
            return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    private JSONArray capabilitiesJson() {
        JSONArray a = new JSONArray();
        for (String c : CAPS) a.put(c);
        return a;
    }

    private String hmacHex(String secretB64, String material) throws Exception {
        byte[] key = Base64.decode(secretB64, Base64.DEFAULT);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] raw = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private String postJson(String urlText, String json) throws Exception {
        URL url = new URL(urlText);
        HttpsURLConnection c = (HttpsURLConnection)url.openConnection();
        if (!isPublicUtahMeta(url)) {
            c.setSSLSocketFactory(pinnedSsl().getSocketFactory());
            c.setHostnameVerifier((host, session) -> true);
        }
        c.setConnectTimeout(10000);
        c.setReadTimeout(90000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "UtahMeta-CoreTV/0.3");
        c.setDoOutput(true);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : readText(in);
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("http_" + code);
        return text;
    }

    private SSLContext pinnedSsl() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert;
        try (InputStream in = getResources().openRawResource(R.raw.gateway)) {
            cert = cf.generateCertificate(in);
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("core-gateway", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tmf.getTrustManagers(), null);
        return sc;
    }

    private String readText(InputStream in) throws Exception {
        try (InputStream x = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = x.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "UtahMeta Core Presence", NotificationManager.IMPORTANCE_MIN));
        }
    }

    private Notification notification(String state) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("UtahMeta Core Presence")
                .setContentText(state)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    private void setStatus(String state) {
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, notification(state));
    }
}

