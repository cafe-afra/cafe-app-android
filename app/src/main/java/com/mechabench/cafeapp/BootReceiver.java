package com.mechabench.cafeapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** بعد از روشن شدن گوشی، اگر رمز مدیر قبلاً ذخیره شده، بررسی سفارش‌ها دوباره شروع می‌شود. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (OrderWatchService.loadAdminKey(context).length() > 0) {
            OrderWatchService.start(context);
        }
    }
}
