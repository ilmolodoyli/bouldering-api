package dev.bouldering.judge;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.net.Uri;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the bundled judge frontend through the on-device JudgeServer,
 * the same read-only architecture as the desktop serve.py: static files
 * come from app assets, /api GETs are proxied natively to the live
 * backend, live writes stay blocked. No fake desktop server is assumed.
 */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(0x0b, 0x0c, 0x0d);
    private AppUpdater updater;
    private JudgeServer server;
    private WebView web;
    private String origin;
    private String pendingExport;
    private static final int EXPORT = 71;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        updater = new AppUpdater(this);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        try {
            server = new JudgeServer(this::readAsset, null);
            server.start();
        } catch (Exception e) {
            showFatal("Не удалось запустить локальный сервер: " + e.getMessage());
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        origin = "http://127.0.0.1:" + server.port();
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith(origin + "/");
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !url.startsWith(origin + "/");
            }
        });
        web.addJavascriptInterface(new ExportBridge(), "JudgeAndroid");
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(web);
        web.loadUrl("http://127.0.0.1:" + server.port() + "/");
        updater.startIfEnabled();
    }

    public boolean homeForUpdate() {
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);final boolean[] home={false};
        runOnUiThread(() -> {if(web==null){done.countDown();return;}web.evaluateJavascript("Boolean(document.querySelector('#db-training'))", value -> {home[0]="true".equals(value);done.countDown();});});
        try {done.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return home[0];
    }
    public void whenHome(Runnable update) {
        if(web==null)return;
        web.evaluateJavascript("Boolean(document.querySelector('#db-training'))", value -> {if("true".equals(value)) update.run();else getSharedPreferences(AppUpdater.PREF,0).edit().putString("status","Найдена новая версия. Установка отложена: открыт рабочий экран. Проверьте обновления после судейства.").apply();});
    }

    private byte[] readAsset(String path) throws IOException {
        InputStream in;
        try {
            in = getAssets().open(path);
        } catch (IOException missing) {
            return null; // AssetManager throws FileNotFoundException for absent paths.
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) != -1) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    private void showFatal(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(BG);
        text.setPadding(48, 96, 48, 48);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }

    @Override
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(() => {const b=document.querySelector('#close,#close-quick,#close-manage');if(b){b.click();return true;}return false;})()", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    public final class ExportBridge {
        @JavascriptInterface public void openUpdates() {runOnUiThread(() -> updater.show());}
        @JavascriptInterface public void exportTraining(String json) {
            if (json == null || json.length() > 2000000) return;
            runOnUiThread(() -> {
                pendingExport = json;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "bouldering-training.json");
                startActivityForResult(intent, EXPORT);
            });
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT) return;
        if (result == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                android.widget.Toast.makeText(this, "История сохранена", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "Не удалось сохранить историю", android.widget.Toast.LENGTH_LONG).show();
            }
        }
        pendingExport = null;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.close();
        if (web != null) web.destroy();
        super.onDestroy();
    }
            }
