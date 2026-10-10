package com.mechabench.cafeapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

public class MainActivity extends Activity {

    private static final String START_URL = "https://cafe-afra.github.io/cafe-app/";
    private static final String PREFS = "cafe_main";
    private static final String PREF_ASKED_BATTERY = "askedBattery";

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // تا وقتی اپ جلوی چشم است، هر ۱۵ ثانیه رمز مدیر را از صفحه می‌خوانیم
    // (تا بعد از وارد کردن رمز برای اولین بار، سرویس اعلان شروع شود)
    private final Runnable keyChecker = new Runnable() {
        @Override
        public void run() {
            syncAdminKey();
            handler.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                syncAdminKey();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl(START_URL);

        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(keyChecker);
        handler.postDelayed(keyChecker, 3000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(keyChecker);
        syncAdminKey();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(keyChecker);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------- رمز مدیر ← سرویس اعلان ----------------

    private void syncAdminKey() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{return localStorage.getItem('cafeAdminKey')||'';}catch(e){return '';}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String key = decodeJsString(value);
                        if (key.length() == 0) return;

                        if (!key.equals(OrderWatchService.loadAdminKey(MainActivity.this))) {
                            OrderWatchService.saveAdminKey(MainActivity.this, key);
                        }
                        OrderWatchService.start(MainActivity.this);
                        maybeAskBatteryExemption();
                    }
                });
    }

    /** نتیجه‌ی evaluateJavascript یک رشته‌ی JSON است (با گیومه)؛ اینجا به متن ساده تبدیل می‌شود. */
    private static String decodeJsString(String raw) {
        if (raw == null || raw.equals("null")) return "";
        try {
            return new JSONArray("[" + raw + "]").optString(0, "");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- مجوزها ----------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    /** بدون این مجوز، اندروید وقتی گوشی مدتی بی‌حرکت است بررسی سفارش‌ها را متوقف می‌کند. */
    private void maybeAskBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23 || isFinishing()) return;

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_ASKED_BATTERY, false)) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;

        prefs.edit().putBoolean(PREF_ASKED_BATTERY, true).apply();

        new AlertDialog.Builder(this)
                .setTitle("دریافت مطمئن اعلان سفارش‌ها")
                .setMessage("اندروید برنامه‌های پس‌زمینه را متوقف می‌کند و ممکن است اعلان سفارش دیر برسد. "
                        + "در صفحه‌ی بعد، گزینه‌ی «اجازه دادن» را بزنید.")
                .setPositiveButton("ادامه", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent i = new Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            // بعضی گوشی‌ها این صفحه را ندارند
                        }
                    }
                })
                .setNegativeButton("بعداً", null)
                .show();
    }
}
