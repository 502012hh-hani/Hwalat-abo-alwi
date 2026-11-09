package com.abualawi.hawalat;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status, entries;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 32, 24, 24);
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("حوالات أبو علوي");
        title.setTextSize(27);
        title.setGravity(17);
        layout.addView(title);

        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, 20, 0, 16);
        layout.addView(status);

        Button enable = new Button(this);
        enable.setText("تفعيل الوصول إلى إشعارات واتساب");
        layout.addView(enable);
        enable.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        Button refresh = new Button(this);
        refresh.setText("تحديث الإشعارات المسجلة");
        layout.addView(refresh);
        refresh.setOnClickListener(v -> showEntries());

        entries = new TextView(this);
        entries.setTextSize(14);
        entries.setPadding(0, 20, 0, 20);
        layout.addView(entries);

        TextView note = new TextView(this);
        note.setTextSize(13);
        note.setText("يسجل التطبيق ما يظهر في إشعارات واتساب فقط. قد يخفي واتساب اسم المجموعة أو نص الرسالة حسب إعدادات الخصوصية.");
        layout.addView(note);
        setContentView(layout);
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
        showEntries();
    }

    private void updateStatus() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        ComponentName me = new ComponentName(this, WhatsAppNotificationListener.class);
        boolean active = enabled != null && enabled.contains(me.flattenToString());
        status.setText("المصدر: إشعارات واتساب\nالمجموعتان المطلوبتان: العمقي وبن دول\nصلاحية الإشعارات: "
                + (active ? "مفعّلة" : "غير مفعّلة"));
    }

    private void showEntries() {
        String log = getSharedPreferences("hawalat", MODE_PRIVATE)
                .getString("notifications", "");
        entries.setText(TextUtils.isEmpty(log)
                ? "لا توجد إشعارات مسجلة بعد. فعّل الصلاحية وانتظر رسالة واتساب جديدة."
                : "الإشعارات التي وصلت للتطبيق:\n\n" + log);
    }
}
