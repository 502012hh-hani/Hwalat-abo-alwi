package com.abualawi.hawalat;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Build;
import android.os.Parcelable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WhatsAppNotificationListener extends NotificationListenerService {
    private static final String PREFS = "hawalat";
    private static final String SENDER = "ابو علوي رقم جديد";
    private static final long PAIR_WINDOW_MS = 10 * 60 * 1000L;
    private final Handler diagnosticHandler = new Handler(Looper.getMainLooper());
    private final Runnable activeNotificationProbe = new Runnable() {
        @Override public void run() { captureActiveNotificationSnapshot(); diagnosticHandler.postDelayed(this, 5000L); }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("listener_state", "متصل بالنظام")
                .putString("last_status", "خدمة استقبال الإشعارات متصلة بالنظام؛ يجري فحص الإشعارات النشطة كل 5 ثوانٍ")
                .apply();
        diagnosticHandler.removeCallbacks(activeNotificationProbe);
        diagnosticHandler.post(activeNotificationProbe);
    }

    @Override
    public void onListenerDisconnected() {
        diagnosticHandler.removeCallbacks(activeNotificationProbe);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("listener_state", "انقطع الاتصال بالنظام")
                .putString("last_status", "انقطع اتصال خدمة الإشعارات؛ جارٍ طلب إعادة ربطها")
                .apply();
        requestRebind(new android.content.ComponentName(this, WhatsAppNotificationListener.class));
        super.onListenerDisconnected();
    }

    private void captureActiveNotificationSnapshot() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            StringBuilder packages = new StringBuilder();
            StringBuilder details = new StringBuilder();
            boolean whatsappFound = false;
            int total = active == null ? 0 : active.length;
            if (active != null) for (StatusBarNotification item : active) {
                if (item == null) continue;
                String pkg = item.getPackageName();
                if ("com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg)) {
                    Bundle extras = item.getNotification() == null ? null : item.getNotification().extras;
                    String title = extras == null ? "" : str(extras.getCharSequence(Notification.EXTRA_TITLE));
                    String sub = extras == null ? "" : str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
                    String conversation = extras == null ? "" : str(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
                    // لا نعرض ولا نفحص إلا إشعارات المجموعتين المطلوبتين.
                    if (!isTargetGroup(title + " " + sub + " " + conversation).isEmpty()) {
                        if (packages.length() > 0) packages.append(", ");
                        packages.append(pkg);
                        whatsappFound = true;
                        String body = extras == null ? "" : str(extras.getCharSequence(Notification.EXTRA_TEXT));
                        if (details.length() > 0) details.append("\\n");
                        details.append(pkg).append(" | ").append(title).append(" | ").append(body);
                        // Seed existing target-group notifications once; process only later changes.
                        processIfActiveNotificationChanged(item);
                    }
                }
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("active_probe_time", stamp)
                    .putString("active_probe_packages", packages.toString())
                    .putString("active_probe_whatsapp_details", details.toString())
                    .putBoolean("active_probe_whatsapp_found", whatsappFound)
                    .putInt("active_probe_count", total)
                    .remove("active_probe_error").apply();
        } catch (Exception ex) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("active_probe_error", ex.getClass().getSimpleName() + ": " + ex.getMessage()).apply();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handleNotificationPosted(sbn, true);
    }

    // Android 5.0+ dispatches this overload; keep both entry points supported.
    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        handleNotificationPosted(sbn, true);
    }

    private void processIfActiveNotificationChanged(StatusBarNotification sbn) {
        if (sbn == null) return;
        try {
            Bundle extras = sbn.getNotification() == null ? null : sbn.getNotification().extras;
            String signature = String.valueOf(sbn.getPostTime()) + "|" +
                    (extras == null ? "" : extras.toString());
            String key = "active_seen_" + Integer.toHexString(sbn.getKey().hashCode());
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String previous = prefs.getString(key, null);
            if (previous == null) {
                prefs.edit().putString(key, signature).apply();
            } else if (!previous.equals(signature)) {
                prefs.edit().putString(key, signature).apply();
                handleNotificationPosted(sbn, false);
            }
        } catch (Exception ignored) {}
    }

    private void handleNotificationPosted(StatusBarNotification sbn, boolean recordCallback) {
        if (sbn == null) return;
        Notification n = sbn.getNotification();
        Bundle e = (n == null) ? null : n.extras;
        String packageName = sbn.getPackageName();
        String title = e == null ? "" : str(e.getCharSequence(Notification.EXTRA_TITLE));
        String sub = e == null ? "" : str(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String conversation = e == null ? "" : str(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String big = e == null ? "" : str(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String body = e == null ? "" : (big.isEmpty() ? str(e.getCharSequence(Notification.EXTRA_TEXT)) : big);
        boolean whatsappPackage = "com.whatsapp".equals(packageName)
                || "com.whatsapp.w4b".equals(packageName);
        String group = isTargetGroup(title + " " + sub + " " + conversation);
        // تجاهل أي إشعار ليس من واتساب أو ليس من إحدى المجموعتين المستهدفتين.
        if (!whatsappPackage || group.isEmpty()) return;

        // WhatsApp group messages are commonly delivered as MessagingStyle.
        // Their actual author and text live in EXTRA_MESSAGES, not necessarily
        // in EXTRA_TITLE / EXTRA_TEXT. Read those structured messages first.
        boolean structuredSenderMatched = false;
        StringBuilder structuredBody = new StringBuilder();
        if (e != null) {
            Parcelable[] rawMessages = e.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (rawMessages != null) {
                for (Parcelable item : rawMessages) {
                    if (!(item instanceof Bundle)) continue;
                    Bundle message = (Bundle) item;
                    String messageText = str(message.getCharSequence("text"));
                    String messageSender = str(message.getCharSequence("sender"));
                    if (Build.VERSION.SDK_INT >= 28) {
                        try {
                            Person person = message.getParcelable("sender_person");
                            if (person != null && person.getName() != null) {
                                messageSender = person.getName().toString();
                            }
                        } catch (Exception ignored) {}
                    }
                    if (!messageText.isEmpty() && isTargetSender(messageSender)) {
                        structuredSenderMatched = true;
                        if (structuredBody.length() > 0) structuredBody.append("\\n");
                        structuredBody.append(messageText);
                    }
                }
            }
        }
        if (structuredSenderMatched) body = structuredBody.toString();

        long posted = sbn.getPostTime();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(posted));
        String debug = stamp + "\nالتطبيق: " + packageName + "\nالعنوان: " + title
                + "\nالعنوان الفرعي: " + sub + "\nعنوان المحادثة: " + conversation
                + "\nالنص: " + body;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String oldHistory = prefs.getString("callback_history", "");
        String historyLine = stamp + " | " + packageName + " | " + title + " | " + sub + " | " + conversation + " | " + body;
        String history = historyLine + (oldHistory.isEmpty() ? "" : "\n" + oldHistory);
        String[] historyLines = history.split("\n");
        StringBuilder limitedHistory = new StringBuilder();
        for (int i = 0; i < Math.min(historyLines.length, 15); i++) {
            if (i > 0) limitedHistory.append("\n");
            limitedHistory.append(historyLines[i]);
        }
        android.content.SharedPreferences.Editor diagnostic = prefs.edit();
        // Polling is a fallback path and must not be mislabeled as an Android callback.
        if (recordCallback) {
            diagnostic.putString("last_callback_package", packageName)
                    .putLong("last_callback_time", posted)
                    .putString("callback_history", limitedHistory.toString());
        }
        if (whatsappPackage) {
            diagnostic.putString("debug_last_notification", debug)
                    .putString("last_whatsapp_notification_time", stamp);
        } else if (recordCallback) {
            diagnostic.putString("debug_last_system_notification", debug);
        }
        diagnostic.apply();

        // Only WhatsApp notifications are shown in the "last WhatsApp" field.
        if (!whatsappPackage) return;
        setStatus("وصل إشعار واتساب؛ جارٍ التحقق من المرسل والمجموعة");
        if (e == null) {
            setStatus("وصل إشعار واتساب لكن بياناته النصية غير متاحة");
            return;
        }

        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) {
                String value = str(line);
                if (!value.isEmpty() && !body.contains(value)) body += "\n" + value;
            }
        }

        setStatus("وصل إشعار واتساب؛ جارٍ التحقق من المرسل والمجموعة");
        boolean legacySenderMatched = isTargetSender(title) || isTargetSender(sub)
                || isTargetSender(conversation) || isTargetSender(body);
        if (!structuredSenderMatched && !legacySenderMatched) {
            setStatus("وصل إشعار واتساب، لكن لم يظهر اسم المرسل المطلوب في بيانات الإشعار");
            return;
        }

        setStatus("تم التعرف على المرسل والمجموعة: " + group);
        processMessage(body, group, posted);
    }

    private String isTargetGroup(String value) {
        if (value == null) return "";
        if (value.contains("العمقي")) return "العمقي";
        if (value.contains("بن دول") || value.contains("محمد علوي احمد")) return "بن دول » محمد علوي احمد";
        return "";
    }

    private boolean isTargetSender(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String t = normalizeArabic(value).replaceAll("[^\\p{IsArabic}]", "");
        String target = normalizeArabic(SENDER).replaceAll("[^\\p{IsArabic}]", "");
        return t.contains(target);
    }

    private String stripSenderPrefix(String value) {
        if (value == null) return "";
        String s = normalizeArabic(value);
        // Group notifications may prepend the sender name to the message body.
        s = s.replaceAll("ابو\\s*علوي\\s*رقم\\s*جديد\\s*[:：،\\-]?\\s*", "");
        return s.trim();
    }

    private void processMessage(String body, String group, long posted) {
        if (body == null || body.trim().isEmpty()) {
            setStatus("وصل إشعار من " + group + " لكن نص الرسالة غير ظاهر");
            return;
        }

        String pendingKey = "pending_" + group;
        String pending = getSharedPreferences(PREFS, MODE_PRIVATE).getString(pendingKey, "");
        String pendingName = "";
        String pendingAmount = "";
        long pendingTime = 0L;
        String[] saved = pending.split("\\t", -1);
        if (saved.length >= 3) {
            try { pendingTime = Long.parseLong(saved[0]); } catch (Exception ignored) {}
            if (pendingTime > 0 && posted >= pendingTime && posted - pendingTime <= PAIR_WINDOW_MS) {
                pendingName = saved[1];
                pendingAmount = saved[2];
            } else if (!pending.isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(pendingKey).apply();
                pendingTime = 0L;
            }
        }

        String cleanBody = stripSenderPrefix(body);
        String name = "";
        String amount = "";
        String[] messageLines = cleanBody.split("[\\r\\n]+");
        for (String rawLine : messageLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String lineAmount = extractAmount(line);
            String lineName = extractName(line);
            if (!lineName.isEmpty()) name = lineName;
            if (!lineAmount.isEmpty()) amount = lineAmount;
        }

        // Also handle a combined message where the recipient and amount share a line.
        if (name.isEmpty()) name = extractName(cleanBody);
        if (amount.isEmpty()) amount = extractAmount(cleanBody);

        // Do not silently combine two different recipient-name fragments or two
        // amount fragments. Keep the newest fragment and wait for its counterpart.
        if (!name.isEmpty() && !pendingName.isEmpty() && pendingAmount.isEmpty()) {
            pendingName = "";
            pendingTime = 0L;
        }
        if (!amount.isEmpty() && !pendingAmount.isEmpty() && pendingName.isEmpty()) {
            pendingAmount = "";
            pendingTime = 0L;
        }
        if (!name.isEmpty()) pendingName = name;
        if (!amount.isEmpty()) pendingAmount = amount;

        if (!pendingName.isEmpty() && !pendingAmount.isEmpty()) {
            long transferTime = pendingTime > 0 ? pendingTime : posted;
            saveTransfer(group, pendingName, pendingAmount, transferTime);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(pendingKey).apply();
            return;
        }

        if (!pendingName.isEmpty() || !pendingAmount.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(pendingKey, posted + "\t" + pendingName + "\t" + pendingAmount)
                    .putString("last_status", "تم حفظ جزء من حوالة من " + group
                            + "؛ ننتظر الجزء المكمل خلال 10 دقائق")
                    .apply();
        } else {
            setStatus("وصلت رسالة من المرسل المطلوب في " + group + " لكن لم أتعرف بعد على اسم أو مبلغ");
        }
    }

    private void saveTransfer(String group, String name, String amount, long timeMillis) {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(timeMillis));
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(timeMillis));
        String key = "rows_" + group;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String old = prefs.getString(key, "");
        String normalizedName = name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String duplicateKey = date + "\t" + normalizedName + "\t" + amount;
        for (String line : old.split("\\n")) {
            String[] a = line.split("\\t", -1);
            if (a.length >= 4) {
                String existing = a[0] + "\t" + a[2].replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                        + "\t" + a[3];
                if (existing.equals(duplicateKey)) {
                    setStatus("تم تجاهل حوالة مكررة: " + name + " - " + amount);
                    return;
                }
            }
        }
        String row = date + "\t" + time + "\t" + name + "\t" + amount;
        prefs.edit().putString(key, row + (old.isEmpty() ? "" : "\n" + old))
                .putString("last_status", "تم تسجيل حوالة: " + name + " - " + amount + " (" + group + ")")
                .apply();
    }

    private String extractAmount(String text) {
        if (text == null) return "";
        String s = normalize(text).replace("٬", "").replace(",", "").trim();
        Matcher labeled = Pattern.compile("(?:المبلغ|مبلغ|القيمة)\\s*[:：]?\\s*([0-9]{2,8})").matcher(s);
        if (labeled.find()) return labeled.group(1);
        Matcher currency = Pattern.compile("([0-9]{2,8})\\s*(?:ريال|سعودي|يمني|ر\\.?س|ر\\.?ي)").matcher(s);
        if (currency.find()) return currency.group(1);
        // A short, numeric-only notification line is commonly the second half of a transfer.
        if (s.matches("[0-9]{2,8}")) return s;
        return "";
    }

    private String extractName(String text) {
        if (text == null) return "";
        String s = normalize(text).trim();
        Matcher labeled = Pattern.compile("(?:اسم\\s*المستلم|المستلم|مستلم|الاسم|الإسم|باسم)\\s*[:：/\\-]?\\s*([^\\n\\r]+)").matcher(s);
        if (labeled.find()) {
            String candidate = cleanName(labeled.group(1));
            if (validName(candidate)) return candidate;
        }
        for (String line : s.split("[\\r\\n]+")) {
            String candidate = cleanName(line);
            if (validName(candidate)) return candidate;
        }
        return "";
    }

    private String cleanName(String value) {
        return value.replaceAll("[0-9٠-٩۰-۹].*$", " ")
                .replaceAll("(?i)ريال سعودي|ريال|سعودي|يمني|صافي|المبلغ|مبلغ|المرسل|حوالة|حواله", " ")
                .replaceAll("[،,؛;:：/|]+", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean validName(String value) {
        if (value == null || value.length() < 5 || value.matches(".*[0-9].*")) return false;
        String v = value.replaceAll("\\s+", " ").trim();
        if (v.split(" ").length < 2) return false;
        String lower = v.toLowerCase(Locale.ROOT);
        String[] reject = {"السلام", "صباح", "مساء", "شكرا", "شكرًا", "تم", "مرفق", "يرجى", "الرجاء", "ابو علوي"};
        for (String word : reject) if (lower.contains(word)) return false;
        return v.matches(".*[\\p{IsArabic}].*");
    }

    private static String normalize(String s) {
        return s.replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
                .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
                .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
                .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9');
    }

    private static String normalizeArabic(String s) {
        return s == null ? "" : s.replace('أ','ا').replace('إ','ا').replace('آ','ا')
                .replace('ى','ي').replaceAll("[\\u064B-\\u065F\\u0670]", "");
    }

    private void setStatus(String value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_status", value).apply();
    }

    private static String str(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
