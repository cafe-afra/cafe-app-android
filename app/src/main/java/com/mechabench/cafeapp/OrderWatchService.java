package com.mechabench.cafeapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * سرویس پس‌زمینه: هر چند ثانیه لیست پیش‌فاکتورهای باز را از سرور می‌گیرد
 * و برای هر سفارش QR جدید (یا آیتم اضافه‌شده به سفارش قبلی) اعلان می‌دهد.
 * رمز مدیر را MainActivity از حافظه‌ی صفحه می‌خواند و اینجا ذخیره می‌کند.
 */
public class OrderWatchService extends Service {

    private static final String PREFS = "cafe_watch";
    private static final String PREF_ADMIN_KEY = "adminKey";
    private static final String PREF_SEEN = "seen";

    private static final String API_URL =
            "https://skateapp.ir/cafe-api/get.php?key=openInvoices";

    private static final String CHANNEL_SERVICE = "service_v1";
    private static final String CHANNEL_ORDERS = "orders_v1";
    private static final int ONGOING_ID = 1;

    static volatile boolean running = false;

    private volatile boolean stopRequested = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private String lastStatus = "";

    // ---------------- کمکی‌های استاتیک ----------------

    static void saveAdminKey(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_ADMIN_KEY, key).apply();
    }

    static String loadAdminKey(Context context) {
        String key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_ADMIN_KEY, "");
        return key == null ? "" : key;
    }

    static void start(Context context) {
        if (running) return;
        Intent intent = new Intent(context, OrderWatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // مثلاً وقتی اندروید اجازه‌ی شروع سرویس از پس‌زمینه را نمی‌دهد
        }
    }

    // ---------------- چرخه‌ی عمر سرویس ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        running = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cafe:orders");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) {
            wakeLock = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = lastStatus.length() == 0 ? "در حال بررسی سفارش‌ها" : lastStatus;
        if (!goForeground(text)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (worker == null || !worker.isAlive()) {
            stopRequested = false;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "cafe-order-watch");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        if (worker != null) worker.interrupt();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            // نادیده گرفته می‌شود
        }
        running = false;
        super.onDestroy();
    }

    private boolean goForeground(String text) {
        try {
            Notification n = buildServiceNotification(text);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ONGOING_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- حلقه‌ی بررسی سفارش‌ها ----------------

    private void loop() {
        while (!stopRequested) {
            long delayMs = 8000;
            try {
                String key = loadAdminKey(this);
                if (key.length() == 0) {
                    setStatus("رمز مدیر ثبت نشده؛ اپ را یک بار باز کنید");
                    delayMs = 15000;
                } else {
                    HttpResult r = httpGet(key);
                    if (r.code == 200) {
                        handleBody(r.body);
                        setStatus("در حال بررسی سفارش‌ها");
                    } else if (r.code == 401) {
                        setStatus("رمز مدیر اشتباه است؛ اپ را باز کنید");
                        delayMs = 30000;
                    } else {
                        setStatus("خطای سرور (" + r.code + ")؛ دوباره تلاش می‌کنم");
                        delayMs = 15000;
                    }
                }
            } catch (JSONException e) {
                setStatus("پاسخ سرور نامعتبر بود؛ دوباره تلاش می‌کنم");
                delayMs = 15000;
            } catch (Exception e) {
                setStatus("اتصال برقرار نیست؛ دوباره تلاش می‌کنم");
                delayMs = 15000;
            }

            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void handleBody(String body) throws JSONException {
        List<OrderLogic.QrOrder> current = OrderLogic.parseQrOrders(body);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, String> seen = OrderLogic.seenFromJson(prefs.getString(PREF_SEEN, ""));
        OrderLogic.Diff diff = OrderLogic.diff(seen, current);

        for (OrderLogic.QrOrder q : diff.fresh) notifyOrder(q, false);
        for (OrderLogic.QrOrder q : diff.added) notifyOrder(q, true);

        prefs.edit().putString(PREF_SEEN, OrderLogic.seenToJson(current)).apply();
    }

    // ---------------- شبکه ----------------

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private HttpResult httpGet(String adminKey) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("GET");
            c.setRequestProperty("X-Admin-Key", adminKey);
            c.setRequestProperty("Cache-Control", "no-cache");

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            return new HttpResult(code, body);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ---------------- اعلان‌ها ----------------

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel orders = new NotificationChannel(
                CHANNEL_ORDERS, "سفارش‌های جدید", NotificationManager.IMPORTANCE_HIGH);
        orders.setDescription("اعلان سفارش‌هایی که مشتری‌ها از طریق QR ثبت می‌کنند");
        orders.enableVibration(true);
        orders.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
        orders.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(orders);

        NotificationChannel svc = new NotificationChannel(
                CHANNEL_SERVICE, "وضعیت بررسی سفارش‌ها", NotificationManager.IMPORTANCE_LOW);
        svc.setDescription("نشان می‌دهد اپ در حال بررسی سفارش‌هاست");
        nm.createNotificationChannel(svc);
    }

    private Notification.Builder newBuilder(String channelId) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, channelId);
        }
        return new Notification.Builder(this);
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    private Notification buildServiceNotification(String text) {
        Notification.Builder b = newBuilder(CHANNEL_SERVICE);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("☕ کافه افرا")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openAppIntent());
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        return b.build();
    }

    private Notification buildOrderNotification(String title, String text) {
        Notification.Builder b = newBuilder(CHANNEL_ORDERS);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(openAppIntent());
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_ALL);
        }
        return b.build();
    }

    private void setStatus(String text) {
        if (text.equals(lastStatus)) return;
        lastStatus = text;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(ONGOING_ID, buildServiceNotification(text));
        }
    }

    private void notifyOrder(OrderLogic.QrOrder q, boolean addition) {
        String table = q.table.length() == 0 ? "؟" : q.table;
        String title = addition
                ? "➕ آیتم جدید برای میز " + table
                : "🔔 سفارش جدید — میز " + table;
        String text = q.summary.length() == 0 ? "برای دیدن جزئیات اپ را باز کنید" : q.summary;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // شناسه از روی سفارش ساخته می‌شود تا اعلان همان سفارش به‌روز شود، نه تکراری
        int id = 2000 + Math.abs(q.id.hashCode() % 100000);
        nm.notify(id, buildOrderNotification(title, text));
    }
}
