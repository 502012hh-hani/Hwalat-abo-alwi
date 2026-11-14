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
                .putLong("listener_connected_at_ms", System.currentTimeMillis())
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

    // Intentionally do not rewrite stored rows on listener reconnect.
    // Every valid transfer row is retained, including exact duplicate transfers.
    
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
                    .putLong("active_probe_at_ms", System.currentTimeMillis())
                    .putString("active_probe_packages", packages.toString())
                    .putString("active_probe_whatsapp_details", details.toString())
                    .putBoolean("active_probe_whatsapp_found", whatsappFound)
                    .putInt("active_probe_count", total)
                    .remove("active_probe_error").apply();
        } catch (Exception ex) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("active_probe_time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                    .putLong("active_probe_at_ms", System.currentTimeMillis())
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

    // Build a stable content fingerprint instead of relying on Bundle.toString(),
    // whose representation is not a reliable signal for nested MessagingStyle updates.
    private String buildActiveNotificationSignature(StatusBarNotification sbn) {
        StringBuilder value = new StringBuilder();
        value.append(sbn.getPackageName()).append('|').append(sbn.getPostTime())
                .append('|').append(sbn.getKey());
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        if (extras == null) return value.toString();
        value.append('|').append(str(extras.getCharSequence(Notification.EXTRA_TITLE)))
                .append('|').append(str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)))
                .append('|').append(str(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)))
                .append('|').append(str(extras.getCharSequence(Notification.EXTRA_TEXT)))
                .append('|').append(str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)));
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) value.append("|line:").append(str(line));
        }
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null) {
            for (Parcelable item : messages) {
                if (!(item instanceof Bundle)) continue;
                Bundle message = (Bundle) item;
                value.append("|msg:")
                        .append(str(message.getCharSequence("text")))
                        .append("|sender:")
                        .append(str(message.getCharSequence("sender")))
                        .append("|time:")
                        .append(message.getLong("time", 0L));
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        Person person = message.getParcelable("sender_person");
                        if (person != null) value.append("|person:").append(str(person.getName()));
                    } catch (Exception ignored) {}
                }
            }
        }
        return value.toString();
    }

    private void processIfActiveNotificationChanged(StatusBarNotification sbn) {
        if (sbn == null) return;
        try {
            Bundle extras = sbn.getNotification() == null ? null : sbn.getNotification().extras;
            String signature = buildActiveNotificationSignature(sbn);
            String key = "active_seen_" + Integer.toHexString(sbn.getKey().hashCode());
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String previous = prefs.getString(key, null);
            if (previous == null) {
                prefs.edit().putString(key, signature).apply();
                // Recover a notification posted after this listener connection if
                // Android did not deliver onNotificationPosted for it.
                long connectedAt = prefs.getLong("listener_connected_at_ms", 0L);
                if (connectedAt > 0L && sbn.getPostTime() >= connectedAt - 2000L) {
                    handleNotificationPosted(sbn, false);
                }
            } else if (!previous.equals(signature)) {
                prefs.edit().putString(key, signature).apply();
                handleNotificationPosted(sbn, false);
            }
        } catch (Exception ignored) {}
    }

    private void handleNotificationPosted(StatusBarNotification sbn, boolean recordCallback) {
        if (sbn == null) return;
        // Record raw Android callback arrival before filtering by app, group, or sender.
        // This makes the diagnostic truthful even when the notification is unrelated.
        if (recordCallback) {
            android.content.SharedPreferences rawPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            rawPrefs.edit()
                    .putString("last_callback_package", sbn.getPackageName())
                    .putLong("last_callback_time", sbn.getPostTime())
                    .putInt("callback_count", rawPrefs.getInt("callback_count", 0) + 1)
                    .apply();
        }
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
        java.util.ArrayList<String> structuredTexts = new java.util.ArrayList<>();
        java.util.ArrayList<Long> structuredTimes = new java.util.ArrayList<>();
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
                        if (structuredBody.length() > 0) structuredBody.append("\n");
                        structuredBody.append(messageText);
                        structuredTexts.add(messageText);
                        long messageTime = message.getLong("time", sbn.getPostTime());
                        structuredTimes.add(messageTime > 0 ? messageTime : sbn.getPostTime());
                    }
                }
            }
        }
        if (structuredSenderMatched) body = structuredBody.toString();

        // لا نسجل حتى إشعارات المجموعة إلا إذا كان المرسل هو أبو علوي المطلوب.
        boolean legacySenderMatched = isTargetSender(title) || isTargetSender(sub)
                || isTargetSender(conversation) || isTargetSender(body);
        if (!structuredSenderMatched && !legacySenderMatched) return;

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
        // Callback arrival is recorded above before filtering; polling never increments it.
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
        setStatus("تم التعرف على المرسل والمجموعة: " + group);
        if (structuredSenderMatched && !structuredTexts.isEmpty()) {
            // عالج كل رسالة أصلية من أبو علوي وحدها؛ لا تدمج أسماء ومبالغ رسائل مختلفة.
            for (int i = 0; i < structuredTexts.size(); i++) {
                processMessageOnce(structuredTexts.get(i), group, structuredTimes.get(i));
            }
        } else {
            processMessageOnce(body, group, posted);
        }
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

    // Prevent the same WhatsApp MessagingStyle history item from being saved again
    // when WhatsApp reposts an updated notification containing older messages.
    private void processMessageOnce(String body, String group, long posted) {
        String signature = group + "|" + posted + "|" + (body == null ? "" : body.trim());
        String key = "processed_message_" + Integer.toHexString(signature.hashCode());
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String previous = prefs.getString(key, "");
        if (signature.equals(previous)) return;
        prefs.edit().putString(key, signature).apply();
        processMessage(body, group, posted);
    }

    private void processMessage(String body, String group, long posted) {
        if (body == null || body.trim().isEmpty()) {
            setStatus("وصل إشعار من " + group + " لكن نص الرسالة غير ظاهر");
            return;
        }

        // بعض إصدارات واتساب تمرر فواصل الأسطر كنص حرفي "\\n".
        String cleanBody = stripSenderPrefix(body).replace("\\n", "\n")
                .replace('\u200f', ' ').replace('\u200e', ' ').replace('\uFEFF', ' ');
        String name = extractName(cleanBody);
        String amount = extractAmount(cleanBody);
        String pendingKey = "pending_" + group;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String pending = prefs.getString(pendingKey, "");
        String pendingName = "";
        String pendingAmount = "";
        long pendingTime = 0L;
        String[] saved = pending.split("\\t", -1);
        if (saved.length >= 3) {
            try { pendingTime = Long.parseLong(saved[0]); } catch (Exception ignored) {}
            if (pendingTime <= 0 || posted < pendingTime || posted - pendingTime > PAIR_WINDOW_MS) {
                prefs.edit().remove(pendingKey).apply();
                pendingName = "";
                pendingAmount = "";
                pendingTime = 0L;
            } else {
                pendingName = saved[1];
                pendingAmount = saved[2];
            }
        }

        // إذا وصل الاسم والمبلغ معاً في الرسالة نفسها، لا نخلطهما مع جزء قديم.
        if (!name.isEmpty() && !amount.isEmpty()) {
            saveTransfer(group, name, amount, posted);
            prefs.edit().remove(pendingKey).apply();
            return;
        }

        // الرسائل المقسمة: نكمل فقط الجزء الناقص خلال عشر دقائق.
        if (!name.isEmpty() && pendingName.isEmpty() && !pendingAmount.isEmpty()) {
            saveTransfer(group, name, pendingAmount, pendingTime);
            prefs.edit().remove(pendingKey).apply();
            return;
        }
        if (!amount.isEmpty() && pendingAmount.isEmpty() && !pendingName.isEmpty()) {
            saveTransfer(group, pendingName, amount, pendingTime);
            prefs.edit().remove(pendingKey).apply();
            return;
        }

        if (!name.isEmpty() || !amount.isEmpty()) {
            // جزء جديد من نفس النوع يستبدل الجزء السابق بدلاً من دمج اسمين أو مبلغين.
            prefs.edit().putString(pendingKey, posted + "\t" + name + "\t" + amount)
                    .putString("last_status", "تم حفظ جزء من حوالة من " + group
                            + "؛ ننتظر الجزء المكمل خلال 10 دقائق")
                    .apply();
        } else {
            setStatus("وصلت رسالة من المرسل المطلوب في " + group
                    + " لكن لم أتعرف على اسم مستلم أو مبلغ صالح؛ تم تجاهل أرقام الحساب وعبارات الحساب");
        }
    }

    private void saveTransfer(String group, String name, String amount, long timeMillis) {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(timeMillis));
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(timeMillis));
        String key = "rows_" + group;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String old = prefs.getString(key, "");
        // Keep every legitimate transfer, including repeated recipient/amount pairs.
        // Export marks repeated pairs visually; notification reposts are filtered separately
        // by the active-notification signature before reaching this method.
        String row = date + "\t" + time + "\t" + name + "\t" + amount;
        prefs.edit().putString(key, row + (old.isEmpty() ? "" : "\n" + old))
                .putString("last_status", "تم تسجيل حوالة: " + name + " - " + amount
                        + " (" + group + ")" + (containsSameTransfer(old, date, name, amount)
                        ? " — تكرار محفوظ وسيُميّز في Excel" : ""))
                .apply();
    }

    private boolean containsSameTransfer(String old, String date, String name, String amount) {
        String normalizedName = normalizeArabic(name).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        for (String line : old.split("\\n")) {
            String[] p = line.split("\\t", -1);
            if (p.length >= 4 && date.equals(p[0]) && amount.equals(p[3])
                    && normalizedName.equals(normalizeArabic(p[2]).replaceAll("\\s+", "").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String extractAmount(String text) {
        if (text == null) return "";
        String s = normalize(text).replace("٬", "").replace(",", "").trim();
        // Remove phone/account identifiers before looking for amounts.
        s = s.replaceAll("(?:رقم\\s*(?:الحساب|الهاتف|الجوال)|الرقم|جوال المستلم)\\s*[:：]?\\s*[0-9]{6,}", " ");
        Matcher labeled = Pattern.compile("(?:المبلغ|مبلغ|القيمة)\\s*[:：]?\\s*([0-9]{2,7})").matcher(s);
        String labeledValue = "";
        while (labeled.find()) labeledValue = labeled.group(1);
        if (!labeledValue.isEmpty()) return labeledValue;

        Matcher currency = Pattern.compile("([0-9]{2,7})\\s*(?:﷼|ريال(?:\\s+يمني)?|سعودي|يمني|ر\\.?س|ر\\.?ي)").matcher(s);
        String currencyValue = "";
        while (currency.find()) currencyValue = currency.group(1);
        if (!currencyValue.isEmpty()) return currencyValue;

        // Some messages start with an amount followed by a dot, then the recipient name.
        Matcher leadingAmount = Pattern.compile("(?m)^\\s*([0-9]{2,7})\\s*[.)،:-]\\s*(?=[\\p{IsArabic}])").matcher(s);
        if (leadingAmount.find()) return leadingAmount.group(1);

        // Common local format: recipient name immediately followed by amount and /place.
        Matcher slashAmount = Pattern.compile("(?<![0-9])([0-9]{2,7})\\s*/\\s*[\\p{IsArabic}]+").matcher(s);
        String slashValue = "";
        while (slashAmount.find()) slashValue = slashAmount.group(1);
        if (!slashValue.isEmpty()) return slashValue;

        // Amounts sometimes touch the following place name without a separator.
        Matcher attachedPlace = Pattern.compile("(?<![0-9])([0-9]{2,7})(?=(?:حضرموت|المكلا|عتق|القطن|شبوه|شبوة|عدن))").matcher(s);
        String placeValue = "";
        while (attachedPlace.find()) placeValue = attachedPlace.group(1);
        if (!placeValue.isEmpty()) return placeValue;

        // Some transfer messages attach a short amount directly to a place or
        // descriptor (for example: "1000بيعث العمقي"). Account/phone numbers
        // are normally 8+ digits and are deliberately excluded by this bound.
        Matcher attachedArabic = Pattern.compile("(?m)^\\s*([0-9]{2,7})(?=[\\p{IsArabic}])").matcher(s);
        if (attachedArabic.find()) return attachedArabic.group(1);

        for (String line : s.split("[\\r\\n]+")) {
            String value = line.trim();
            Matcher standalone = Pattern.compile("^([0-9]{2,7})(?:\\s+.*)?$").matcher(value);
            if (standalone.matches()) return standalone.group(1);
        }
        return "";
    }

    private String extractName(String text) {
        if (text == null) return "";
        String s = normalize(text).replace("\\\\n", "\n");
        // Prefer explicit recipient labels and stop before sender/amount/location metadata.
        Matcher labeled = Pattern.compile("(?:اسم\\s*المستلم|المستلم|مستلم|الاسم|الإسم|باسم|بااسم)\\s*[:：/\\-]?\\s*([^\\n\\r]+)").matcher(s);
        while (labeled.find()) {
            String candidate = cleanName(labeled.group(1));
            if (validName(candidate)) return candidate;
        }

        String best = "";
        int bestScore = -1;
        for (String line : s.split("[\\r\\n]+")) {
            String candidate = cleanName(line);
            if (!validName(candidate)) continue;
            int words = candidate.split("\\s+").length;
            int score = words * 100 + candidate.length();
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private String cleanName(String value) {
        if (value == null) return "";
        String s = normalize(value).replace('\u200f', ' ').replace('\u200e', ' ')
                .replace(String.valueOf((char) 0xFEFF), " ");
        // Strip conversational lead-ins before a recipient name (e.g. "تسويها لنا باسم ...").
        s = s.replaceAll("^(?:(?:تسويها|سويها|اعملها|اعمل|حولها|حوله|ارسلها|ارسليها)\\s+)?(?:لنا\\s+)?", "");
        // A bank/account identifier may be concatenated immediately before the
        // recipient name: "254093946عامر حيدر عمر الصقير". Remove only a
        // 6+ digit prefix at the start of a line, never digits inside a name.
        s = s.replaceAll("(?m)^\\s*[0-9]{6,}\\s*(?=[\\p{IsArabic}])", "");
        // A labeled recipient field ends before sender, amount, or transfer metadata.
        s = s.replaceAll("(?i)\\s*(?:المرسل|اسم\\s*المرسل|المبلغ|مبلغ|القيمة|العملة|المنطقة|الموقع|العنوان|جوال\\s*المستلم|رقم\\s*(?:الحساب|الهاتف|الجوال)).*$", " ");
        // Remove a leading amount prefix such as "200. " before the recipient name.
        s = s.replaceAll("^\\s*[0-9]{2,7}\\s*[.)،:-]\\s*", "");
        // Unlabeled formats put the amount directly after the recipient, sometimes without spaces.
        s = s.replaceAll("[0-9٠-٩۰-۹].*$", " ");
        s = s.replaceAll("(?:رقم\\s*)?حساب(?:ي)?(?:\\s+العمقي|\\s+بن\\s*دول)?", " ")
             .replaceAll("(?i)ريال\\s*سعودي|ريال|سعودي|يمني|صافي|المبلغ|مبلغ|المرسل", " ")
             .replaceAll("حوالة|حواله|حوله|تحويل|ايداع|إيداع|إيداعه|في\\s+الحساب|بالحساب|الحساب|رقم", " ")
             .replaceAll("[^\\p{IsArabic}\\s]", " ")
             .replaceAll("\\s+", " ").trim();
        return s;
    }

    private boolean validName(String value) {
        if (value == null || value.length() < 7 || value.matches(".*[0-9].*")) return false;
        String v = value.replaceAll("\\s+", " ").trim();
        String[] words = v.split(" ");
        if (words.length < 2) return false;
        String lower = normalizeArabic(v);
        String[] reject = {"السلام", "صباح", "مساء", "شكرا", "تم", "مرفق", "يرجى",
                "الرجاء", "رقم", "حساب", "دول",
                "ريال", "سعودي", "يمني", "حواله", "حوالة", "حوله", "تحويل",
                "ايداع", "المبلغ", "مبلغ", "اليوم", "معه", "في"};
        for (String token : words) {
            String normalizedToken = normalizeArabic(token);
            for (String blocked : reject) {
                if (normalizedToken.equals(blocked)) return false;
            }
        }
        if (lower.contains("ابو علوي")) return false;
        int arabicLetters = v.replaceAll("[^\\p{IsArabic}]", "").length();
        return arabicLetters >= 6 && v.matches(".*[\\p{IsArabic}].*");
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
