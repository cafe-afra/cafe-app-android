package com.mechabench.cafeapp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * منطق تشخیص سفارش‌های QR جدید (بدون وابستگی به اندروید، قابل تست).
 */
final class OrderLogic {

    private OrderLogic() {}

    /** یک سفارش QR باز. sig با هر بار اضافه شدن آیتم به سفارش عوض می‌شود. */
    static final class QrOrder {
        final String id;
        final String sig;
        final String table;
        final String summary;

        QrOrder(String id, String sig, String table, String summary) {
            this.id = id;
            this.sig = sig;
            this.table = table;
            this.summary = summary;
        }
    }

    static final class Diff {
        /** سفارش‌های کاملاً جدید */
        final List<QrOrder> fresh = new ArrayList<>();
        /** سفارش‌هایی که آیتم جدید به آن‌ها اضافه شده */
        final List<QrOrder> added = new ArrayList<>();
    }

    private static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    /**
     * پاسخ get.php به شکل {"value":"[...]","updatedAt":123} است
     * که value خودش یک رشته‌ی JSON از آرایه‌ی پیش‌فاکتورهاست.
     */
    static List<QrOrder> parseQrOrders(String responseBody) throws JSONException {
        List<QrOrder> result = new ArrayList<>();
        JSONObject outer = new JSONObject(responseBody);
        String value = str(outer, "value");
        if (value.length() == 0) return result;

        JSONArray invoices = new JSONArray(value);
        for (int i = 0; i < invoices.length(); i++) {
            JSONObject inv = invoices.optJSONObject(i);
            if (inv == null) continue;
            if (!"qr".equals(str(inv, "source"))) continue;

            String id = str(inv, "id");
            if (id.length() == 0) continue;

            String lastApplied = "";
            JSONArray applied = inv.optJSONArray("appliedOrderIds");
            if (applied != null && applied.length() > 0) {
                lastApplied = applied.optString(applied.length() - 1, "");
            }

            String table = str(inv, "table");
            if (table.length() == 0) table = str(inv, "tableNumber");

            result.add(new QrOrder(
                    id,
                    id + ":" + lastApplied,
                    table,
                    summarize(inv.optJSONArray("items"))
            ));
        }
        return result;
    }

    /** مثلاً «قهوه ×2، چای ×1» (حداکثر ۴ قلم) */
    static String summarize(JSONArray items) {
        if (items == null || items.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            if (shown == 4) {
                sb.append("، …");
                break;
            }
            if (sb.length() > 0) sb.append("، ");
            sb.append(str(it, "name")).append(" ×").append(it.optInt("qty", 1));
            shown++;
        }
        return sb.toString();
    }

    /**
     * seen == null یعنی هنوز مبنایی ذخیره نشده (اولین اجرا): هیچ اعلانی داده نمی‌شود
     * و فقط وضعیت فعلی به‌عنوان مبنا ثبت می‌شود.
     */
    static Diff diff(Map<String, String> seen, List<QrOrder> current) {
        Diff d = new Diff();
        if (seen == null) return d;
        for (QrOrder q : current) {
            String old = seen.get(q.id);
            if (old == null) {
                d.fresh.add(q);
            } else if (!old.equals(q.sig)) {
                d.added.add(q);
            }
        }
        return d;
    }

    /** رشته‌ی خالی یا خراب = بدون مبنا (null) */
    static Map<String, String> seenFromJson(String json) {
        if (json == null || json.length() == 0) return null;
        Map<String, String> map = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                map.put(k, o.optString(k, ""));
            }
        } catch (JSONException e) {
            return null;
        }
        return map;
    }

    static String seenToJson(List<QrOrder> current) throws JSONException {
        JSONObject o = new JSONObject();
        for (QrOrder q : current) {
            o.put(q.id, q.sig);
        }
        return o.toString();
    }
}
