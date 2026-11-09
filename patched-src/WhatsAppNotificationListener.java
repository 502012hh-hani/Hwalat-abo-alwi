package com.abualawi.hawalat;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WhatsAppNotificationListener extends NotificationListenerService {
    private static final String PREFS = "hawalat";
    private static final String KEY_LOG = "notifications";

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !"com.whatsapp".equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;

        Bundle extras = notification.extras;
        String title = clean(extras.getCharSequence(Notification.EXTRA_TITLE));
        String sub = clean(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String body = clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) body = clean(extras.getCharSequence(Notification.EXTRA_TEXT));

        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        StringBuilder message = new StringBuilder(body);
        if (lines != null) {
            for (CharSequence line : lines) {
                String value = clean(line);
                if (!value.isEmpty() && message.indexOf(value) < 0) {
                    if (message.length() > 0) message.append(" / ");
                    message.append(value);
                }
            }
        }
        if (title.isEmpty() && message.length() == 0) return;

        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(sbn.getPostTime()));
        String marker = "KEY=" + sbn.getKey() + "@" + sbn.getPostTime();
        String row = stamp + " | العنوان: " + title
                + " | التفاصيل: " + sub + " | الرسالة: " + message;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String old = prefs.getString(KEY_LOG, "");
        if (old.contains(marker)) return;
        prefs.edit().putString(KEY_LOG, marker + "\n" + row + "\n\n" + old).apply();
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
