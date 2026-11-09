package com.abualawi.hawalat;

import android.app.Notification;
import android.os.Bundle;
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

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !"com.whatsapp".equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        Bundle e = n.extras;
        String title = str(e.getCharSequence(Notification.EXTRA_TITLE));
        String sub = str(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String conversation = str(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String big = str(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String body = big.isEmpty() ? str(e.getCharSequence(Notification.EXTRA_TEXT)) : big;
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) {
                String value = str(line);
                if (!value.isEmpty() && !body.contains(value)) body += "\n" + value;
            }
        }

        long posted = sbn.getPostTime();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(posted));
        String debug = stamp + "\nالعنوان: " + title + "\nالمجموعة/العنوان الفرعي: " + sub
                + "\nعنوان المحادثة: " + conversation + "\nالنص: " + body;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("debug_last_notification", debug)
                .putString("last_status", "وصل إشعار واتساب؛ جارٍ التحقق من المرسل والمجموعة")
                .apply();

        // In WhatsApp group notifications the sender is normally EXTRA_TITLE,
        // while the group name is EXTRA_SUB_TEXT or EXTRA_CONVERSATION_TITLE.
        if (!isTargetSender(title)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("last_status", "وصل إشعار واتساب، لكن عنوانه ليس المرسل المطلوب")
                    .apply();
            return;
        }

        String groupText = sub + " " + conversation + " " + title;
        String group = groupText.contains("العمقي") ? "العمقي"
                : (groupText.contains("بن دول") ? "بن دول » محمد علوي احمد" : "");
        if (group.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("last_status", "وصلت رسالة من المرسل المطلوب، لكن اسم المجموعة غير ظاهر في الإشعار")
                    .apply();
            return;
        }

        processMessage(body, group, posted);
    }

    private boolean isTargetSender(String title) {
        String t = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        return t.equals(SENDER) || t.contains(SENDER);
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
            if (posted - pendingTime >= 0 && posted - pendingTime <= PAIR_WINDOW_MS) {
                pendingName = saved[1];
                pendingAmount = saved[2];
            }
        }

        String name = "";
        String amount = "";
        String[] messageLines = normalize(body).split("[\\r\\n]+");
        for (String rawLine : messageLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String lineAmount = extractAmount(line);
            String lineName = extractName(line);
            if (!lineName.isEmpty()) name = lineName;
            if (!lineAmount.isEmpty()) amount = lineAmount;
        }

        // Also handle a combined message where the recipient and amount share a line.
        if (name.isEmpty()) name = extractName(normalize(body));
        if (amount.isEmpty()) amount = extractAmount(normalize(body));

        if (!name.isEmpty()) pendingName = name;
        if (!amount.isEmpty()) pendingAmount = amount;

        if (!pendingName.isEmpty() && !pendingAmount.isEmpty()) {
            saveTransfer(group, pendingName, pendingAmount, posted);
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

    private void setStatus(String value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_status", value).apply();
    }

    private static String str(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
