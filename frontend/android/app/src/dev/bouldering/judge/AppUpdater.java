package dev.bouldering.judge;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed-repository, same-certificate native APK updater. Never loads executable web code. */
public final class AppUpdater {
    static final String PREF = "judge_updates";
    static final String REPO = "https://api.github.com/repos/ilmolodoyli/bouldering-api/releases?per_page=100";
    static final String PREFIX = "https://github.com/ilmolodoyli/bouldering-api/releases/download/";
    static final long LIMIT = 20L * 1024 * 1024;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final Activity activity;
    private final android.content.SharedPreferences prefs;
    public AppUpdater(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(PREF, 0);
        int previous = prefs.getInt("install_version", 0);
        try {
            if (previous > 0 && installedCode() >= previous) {
                prefs.edit().putString("status", "Обновление установлено. Версия " + version()).remove("install_version").apply();
            }
        } catch (Exception ignored) { }
    }
    private long installedCode() throws Exception {PackageInfo p=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0);return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    private String version() {
        try { return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "?"; }
    }
    public void startIfEnabled() {
        // Only once at initial app opening, before judging starts. No timer inside a running session.
        if (prefs.getBoolean("auto", false) && System.currentTimeMillis() - prefs.getLong("checked", 0) > 21600000L) check(true);
    }
    public void show() {
        String trust = activity.getPackageManager().canRequestPackageInstalls() ? "Разрешение Android на установку есть." : "Для установки Android попросит разрешить этому приложению обновлять APK.";
        CheckBox box = new CheckBox(activity);
        box.setText("Автообновление при открытии приложения (не чаще 6 часов)");
        box.setChecked(prefs.getBoolean("auto", false));
        box.setOnCheckedChangeListener((b, enabled) -> prefs.edit().putBoolean("auto", enabled).apply());
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(1); layout.setPadding(24, 0, 24, 0); layout.addView(box);
        new AlertDialog.Builder(activity).setTitle("Обновления · " + version())
            .setMessage(prefs.getString("status", "Проверка ещё не выполнялась.") + "\n\nТестовый канал GitHub Releases. Будут установлены только APK этого приложения с той же подписью.\n\n" + trust + "\nНа Android 12+ запросим обновление без окна установки, если система это разрешает. Android или производитель могут потребовать подтверждение. Во время судейства обновление не запускается; установка может закрыть приложение. Локальная история сохраняется. Перед тестовым обновлением лучше экспортировать её.")
            .setView(layout).setPositiveButton("Проверить", (d,w) -> check(false))
            .setNeutralButton("Разрешение Android", (d,w) -> activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))))
            .setNegativeButton("Закрыть", null).show();
    }
    private void state(String text) {
        prefs.edit().putString("status", text).apply();
    }
    private void ui(Runnable work) {
        activity.runOnUiThread(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) work.run(); });
    }
    public void check(boolean automatic) {
        if (!BUSY.compareAndSet(false, true)) { if (!automatic) ui(() -> new AlertDialog.Builder(activity).setMessage(prefs.getString("status", "Проверка выполняется")).setPositiveButton("OK", null).show()); return; }
        state("Проверяем GitHub Releases…");
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                Candidate candidate = discover(read(REPO, 2 * 1024 * 1024), version());
                if (candidate == null) {
                    BUSY.set(false);
                    state("Новых тестовых APK не найдено. Текущая версия " + version());
                    if (!automatic) ui(this::show);
                    return;
                }
                state("Найдена тестовая версия " + candidate.version);
                BUSY.set(false);
                ui(() -> {
                    if (automatic) {
                        if (activity.getPackageManager().canRequestPackageInstalls()) ((MainActivity)activity).whenHome(() -> download(candidate, true));
                        else state("Найдена " + candidate.version + ". Откройте обновления и разрешите установку в Android.");
                    } else {
                        new AlertDialog.Builder(activity).setTitle("Тестовая версия " + candidate.version)
                            .setMessage("Скачать и установить обновление? APK проверяется по SHA256, пакету и той же подписи. Установка может закрыть приложение. Android может потребовать подтверждение. Перед обновлением экспортируйте важную локальную историю.")
                            .setPositiveButton("Скачать и обновить", (d,w) -> {
                                if (!activity.getPackageManager().canRequestPackageInstalls()) {
                                    state("Нужно разрешение Android. После выдачи разрешения снова нажмите «Проверить».");
                                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                                } else download(candidate, false);
                            }).setNegativeButton("Позже", null).show();
                    }
                });
            } catch (Exception e) {
                BUSY.set(false);
                state("Проверка не удалась. Данные приложения не изменены. " + safeError(e));
                if (!automatic) ui(this::show);
            }
        }, "judge-update-check").start();
    }
    static final class Candidate {
        final String version, url, digest; final long size;
        Candidate(String version, String url, String digest, long size) {this.version=version;this.url=url;this.digest=digest;this.size=size;}
    }
    static int compare(String left, String right) {
        if (!left.matches("\\d+\\.\\d+\\.\\d+") || !right.matches("\\d+\\.\\d+\\.\\d+")) throw new IllegalArgumentException("Некорректная версия");
        String[] a=left.split("\\."), b=right.split("\\.");
        for(int i=0;i<3;i++){int d=Long.compare(Long.parseLong(a[i]),Long.parseLong(b[i]));if(d!=0)return d;} return 0;
    }
    static Candidate discover(String body, String current) throws Exception {
        JSONArray releases=new JSONArray(body); Candidate best=null;
        for(int i=0;i<releases.length();i++) {
            JSONObject release=releases.getJSONObject(i);
            if(release.optBoolean("draft",true)) continue;
            String tag=release.optString("tag_name");
            if(!tag.matches("judge-v\\d+\\.\\d+\\.\\d+-test")) continue;
            String v=tag.substring(7,tag.length()-5);
            if(compare(v,current)<=0 || (best!=null && compare(v,best.version)<=0)) continue;
            JSONArray assets=release.optJSONArray("assets");if(assets==null) continue;
            for(int j=0;j<assets.length();j++) {
                JSONObject a=assets.getJSONObject(j);String name="tournament-judge-"+v+".apk";
                String url=PREFIX+tag+"/"+name, digest=a.optString("digest");long size=a.optLong("size",0);
                if(name.equals(a.optString("name")) && "uploaded".equals(a.optString("state")) && url.equals(a.optString("browser_download_url")) && digest.matches("sha256:[0-9a-f]{64}") && size>0 && size<=LIMIT)
                    best=new Candidate(v,url,digest.substring(7),size);
            }
        } return best;
    }
    private void download(Candidate c, boolean automatic) {
        if (!BUSY.compareAndSet(false,true)) {state("Операция уже выполняется. Повторите проверку позже.");return;}
        state("Скачиваем " + c.version + "…");
        new Thread(() -> {
            File apk=new File(activity.getCacheDir(),"judge-update.apk");
            try {
                byte[] bytes=readBytes(c.url,LIMIT);
                if(bytes.length!=c.size || !hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(c.digest)) throw new SecurityException("SHA256 или размер APK не совпал");
                try(FileOutputStream out=new FileOutputStream(apk)){out.write(bytes);out.getFD().sync();}
                PackageManager pm=activity.getPackageManager();
                int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
                PackageInfo own=pm.getPackageInfo(activity.getPackageName(),flags), next=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
                long code=next==null?0:(Build.VERSION.SDK_INT>=28?next.getLongVersionCode():next.versionCode);
                long old=Build.VERSION.SDK_INT>=28?own.getLongVersionCode():own.versionCode;
                if(next==null || !activity.getPackageName().equals(next.packageName) || !c.version.equals(next.versionName) || code<=old || !certs(own).equals(certs(next)) || certs(own).isEmpty()) throw new SecurityException("Пакет, версия или подпись APK не прошли проверку");
                state("APK " + c.version + " проверен. Передаём установку Android…");
                if(automatic && !((MainActivity)activity).homeForUpdate()) throw new SecurityException("Установка отложена: открыт рабочий экран. Повторите после судейства.");
                PackageInstaller installer=pm.getPackageInstaller();
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(activity.getPackageName());params.setSize(bytes.length);
                if(Build.VERSION.SDK_INT>=31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id=installer.createSession(params);
                try(PackageInstaller.Session session=installer.openSession(id)) {
                    try(OutputStream out=session.openWrite("base.apk",0,bytes.length)){out.write(bytes);session.fsync(out);}
                    Intent result=new Intent(activity,UpdateReceiver.class).setAction("dev.bouldering.judge.UPDATE_RESULT");
                    PendingIntent pending=PendingIntent.getBroadcast(activity,id,result,PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
                    prefs.edit().putInt("install_version",(int)code).putString("status","Установка передана Android. Успех ещё не подтверждён.").apply();
                    session.commit(pending.getIntentSender());
                } catch(Exception e){installer.abandonSession(id);throw e;}
            } catch(Exception e) {state("Обновление не установлено. " + safeError(e));if(!automatic)ui(this::show);}
            finally {apk.delete();BUSY.set(false);}
        },"judge-update-download").start();
    }
    static Set<String> certs(PackageInfo p) throws Exception {
        Signature[] signatures=Build.VERSION.SDK_INT>=28?(p.signingInfo==null?null:p.signingInfo.getApkContentsSigners()):p.signatures;
        Set<String> set=new HashSet<>();if(signatures!=null)for(Signature s:signatures)set.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));return set;
    }
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static String safeError(Exception e){if(e instanceof SecurityException)return e.getMessage();return "Сеть, файл или системный установщик недоступны. Попробуйте позже.";}
    private static String read(String url,long max)throws Exception{return new String(readBytes(url,max),java.nio.charset.StandardCharsets.UTF_8);}
    static byte[] readBytes(String address,long max) throws Exception {
        URL url=new URL(address);
        for(int redirects=0;redirects<6;redirects++) {
            if(!"https".equals(url.getProtocol()) || !(url.getHost().equals("api.github.com") || url.getHost().equals("github.com") || url.getHost().equals("release-assets.githubusercontent.com") || url.getHost().equals("objects.githubusercontent.com"))) throw new SecurityException("Недопустимый адрес обновления");
            HttpURLConnection c=(HttpURLConnection)url.openConnection(); c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","BoulderingJudge-Updater");c.setRequestProperty("Accept",url.getHost().equals("api.github.com")?"application/vnd.github+json":"application/octet-stream");
            try {
                int status=c.getResponseCode();
                if(status>=300&&status<400){String location=c.getHeaderField("Location");if(location==null)throw new IOException();url=new URL(url,location);continue;}
                if(status!=200 || c.getContentLengthLong()>max)throw new IOException();
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException();out.write(buf,0,n);}return out.toByteArray();}
            } finally{c.disconnect();}
        } throw new IOException("Redirect limit");
    }
}
