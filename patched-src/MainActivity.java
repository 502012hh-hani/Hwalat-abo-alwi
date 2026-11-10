package com.abualawi.hawalat;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView status, entries;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 28, 24, 28);
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("حوالات أبو علوي");
        title.setTextSize(27);
        title.setGravity(17);
        title.setPadding(0, 8, 0, 20);
        layout.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(4, 12, 4, 16);
        layout.addView(status);

        Button enable = new Button(this);
        enable.setText("تفعيل والوصول إلى إشعارات واتساب");
        layout.addView(enable);
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        Button refresh = new Button(this);
        refresh.setText("تحديث حالة الاستقبال والسجل");
        layout.addView(refresh);
        refresh.setOnClickListener(v -> {
            updateStatus();
            showEntries();
            Toast.makeText(this, "تم تحديث حالة الخدمة وآخر إشعار", Toast.LENGTH_SHORT).show();
        });

        Button export = new Button(this);
        export.setText("تصدير ملفات الحوالات اليومية");
        layout.addView(export);
        export.setOnClickListener(v -> exportFiles());

        TextView section = new TextView(this);
        section.setText("ـــــ تشخيص استقبال الإشعارات ـــــ");
        section.setTextSize(17);
        section.setGravity(17);
        section.setPadding(0, 22, 0, 8);
        layout.addView(section);

        entries = new TextView(this);
        entries.setTextSize(15);
        entries.setTextIsSelectable(true);
        entries.setPadding(4, 12, 4, 20);
        layout.addView(entries);

        TextView note = new TextView(this);
        note.setTextSize(13);
        note.setPadding(4, 12, 4, 20);
        note.setText("ملاحظة: يقرأ التطبيق إشعارات واتساب التي يسمح النظام بعرضها فقط، ولا يفتح محادثات واتساب مباشرة. ملفات CSV تفتح ببرنامج Excel.");
        layout.addView(note);

        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 71);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
        showEntries();
    }

    private void updateStatus() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        ComponentName me = new ComponentName(this, WhatsAppNotificationListener.class);
        boolean active = enabled != null && enabled.contains(me.flattenToString());
        String last = getSharedPreferences("hawalat", MODE_PRIVATE).getString("last_status", "لا توجد حالة مسجلة حتى الآن");
        status.setText("صلاحية الوصول إلى الإشعارات: " + (active ? "مفعّلة" : "غير مفعّلة") + "\n\nآخر حالة للخدمة:\n" + last);
    }

    private void showEntries() {
        String a = getSharedPreferences("hawalat", MODE_PRIVATE).getString("rows_العمقي", "");
        String b = getSharedPreferences("hawalat", MODE_PRIVATE).getString("rows_بن دول » محمد علوي احمد", "");
        String raw = getSharedPreferences("hawalat", MODE_PRIVATE).getString("debug_last_notification", "لم يصل أي إشعار واتساب إلى خدمة التطبيق بعد.");
        String listener = getSharedPreferences("hawalat", MODE_PRIVATE).getString("listener_state", "لم تسجل الخدمة اتصالها بالنظام بعد");
        String checked = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        entries.setText(
                "وقت آخر تحديث للشاشة: " + checked +
                "\n\nسجلات مجموعة العمقي: " + count(a) +
                "\nسجلات مجموعة بن دول: " + count(b) +
                "\n\nحالة خدمة استقبال الإشعارات:\n" + listener +
                "\n\nآخر حالة معالجة:\n" + getSharedPreferences("hawalat", MODE_PRIVATE).getString("last_status", "لا توجد") +
                "\n\nآخر إشعار واتساب وصل للتطبيق:\n" + raw
        );
    }

    private int count(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        return s.split("\\n").length;
    }

    private void exportFiles() {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            Toast.makeText(this, "التخزين غير متاح", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "حوالات أبو علوي");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد التصدير");
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            int total = writeGroup(dir, "العمقي", "rows_العمقي", today)
                    + writeGroup(dir, "بن دول", "rows_بن دول » محمد علوي احمد", today);
            Toast.makeText(this, "تم تصدير " + total + " سجل إلى Documents/حوالات أبو علوي", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, "تعذر التصدير: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int writeGroup(File dir, String label, String prefKey, String today) throws Exception {
        String data = getSharedPreferences("hawalat", MODE_PRIVATE).getString(prefKey, "");
        StringBuilder csv = new StringBuilder("\uFEFFاسم المستلم,المبلغ,التاريخ,الوقت\r\n");
        int n = 0;
        for (String row : data.split("\\n")) {
            String[] p = row.split("\\t", -1);
            if (p.length < 4 || !today.equals(p[0])) continue;
            csv.append(q(p[2])).append(',').append(q(p[3])).append(',').append(q(p[0])).append(',').append(q(p[1])).append("\r\n");
            n++;
        }
        File f = new File(dir, label + "-" + today + ".csv");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        }
        return n;
    }

    private String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}