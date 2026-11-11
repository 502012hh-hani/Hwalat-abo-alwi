package com.abualawi.hawalat;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView status, entries;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startBackgroundMonitor();

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

        Button recordsAlAmqi = new Button(this);
        recordsAlAmqi.setText("سجلات مجموعة العمقي");
        layout.addView(recordsAlAmqi);
        recordsAlAmqi.setOnClickListener(v -> showGroupRecords("العمقي", "rows_العمقي"));

        Button recordsBinDawol = new Button(this);
        recordsBinDawol.setText("سجلات مجموعة بن دول");
        layout.addView(recordsBinDawol);
        recordsBinDawol.setOnClickListener(v -> showGroupRecords("بن دول", "rows_بن دول » محمد علوي احمد"));

        Button export = new Button(this);
        export.setText("تصدير ملفات الحوالات اليومية للمجموعتين");
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
        startBackgroundMonitor();
        updateStatus();
        showEntries();
    }

    private void startBackgroundMonitor() {
        try {
            Intent service = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        } catch (Exception ex) {
            getSharedPreferences("hawalat", MODE_PRIVATE).edit().putString("watchdog_status", "تعذر تشغيل مراقبة الخلفية: " + ex.getClass().getSimpleName()).apply();
        }
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
        String raw = "مخفية لحماية بيانات المستلمين؛ يعرض التطبيق الاسم والمبلغ والتاريخ والوقت فقط.";
        String listener = getSharedPreferences("hawalat", MODE_PRIVATE).getString("listener_state", "لم تسجل الخدمة اتصالها بالنظام بعد");
        String callbackPackage = getSharedPreferences("hawalat", MODE_PRIVATE).getString("last_callback_package", "لم يصل أي callback بعد");
        long callbackTime = getSharedPreferences("hawalat", MODE_PRIVATE).getLong("last_callback_time", 0L);
        String callbackStamp = callbackTime == 0L ? "غير متوفر" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(callbackTime));
        String systemDebug = "معلومات الإشعارات التفصيلية مخفية؛ لا يتم عرض محتوى الرسائل أو الصور أو أرقام الهواتف.";
        String callbackHistory = "سجل محتوى الإشعارات مخفي لحماية الخصوصية.";
        String probeTime = getSharedPreferences("hawalat", MODE_PRIVATE).getString("active_probe_time", "لم يبدأ");
        String probePackages = getSharedPreferences("hawalat", MODE_PRIVATE).getString("active_probe_packages", "غير متوفر");
        String probeDetails = "تم رصد إشعار واتساب دون عرض محتواه.";
        boolean probeWhatsApp = getSharedPreferences("hawalat", MODE_PRIVATE).getBoolean("active_probe_whatsapp_found", false);
        int probeCount = getSharedPreferences("hawalat", MODE_PRIVATE).getInt("active_probe_count", 0);
        String probeError = getSharedPreferences("hawalat", MODE_PRIVATE).getString("active_probe_error", "لا يوجد");
        String checked = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        entries.setText(
                "وقت آخر تحديث للشاشة: " + checked +
                "\n\nسجلات مجموعة العمقي: " + count(a) +
                "\nسجلات مجموعة بن دول: " + count(b) +
                "\n\nحالة خدمة استقبال الإشعارات:\n" + listener +
                "\n\nآخر callback استلمه Android:\nالتطبيق: " + callbackPackage + "\nالوقت: " + callbackStamp +
                "\n\nآخر إشعار نظام (للتشخيص):\n" + systemDebug +
                "\n\nفحص الإشعارات النشطة كل 5 ثوانٍ:\nآخر فحص: " + probeTime + "\nعدد الإشعارات: " + probeCount + "\nواتساب نشط؟ " + (probeWhatsApp ? "نعم" : "لا") + "\nالحزم النشطة: " + probePackages + "\nتفاصيل واتساب: " + probeDetails + "\nخطأ الفحص: " + probeError +
                "\n\nحالة مراقبة الخلفية:\n" + getSharedPreferences("hawalat", MODE_PRIVATE).getString("watchdog_status", "لم تبدأ بعد") +
                "\n\nسجل آخر 15 إشعاراً استلمه Android:\n" + callbackHistory +
                "\n\nتفاصيل سجلات مجموعة العمقي:\n" + formatEntries(a) +
                "\n\nتفاصيل سجلات مجموعة بن دول:\n" + formatEntries(b) +
                "\n\nآخر حالة معالجة:\n" + getSharedPreferences("hawalat", MODE_PRIVATE).getString("last_status", "لا توجد") +
                "\n\nملاحظة الخصوصية:\n" + raw
        );
    }

    private void showGroupRecords(String label, String prefKey) {
        String data = getSharedPreferences("hawalat", MODE_PRIVATE).getString(prefKey, "");
        ArrayList<String[]> rows = new ArrayList<>();
        if (!TextUtils.isEmpty(data)) {
            for (String row : data.split("\\n")) {
                String[] p = row.split("\\t", -1);
                if (p.length >= 4) rows.add(p);
            }
        }
        Collections.sort(rows, new Comparator<String[]>() {
            @Override public int compare(String[] x, String[] y) {
                int byName = x[2].compareToIgnoreCase(y[2]);
                if (byName != 0) return byName;
                return x[3].compareTo(y[3]);
            }
        });
        StringBuilder table = new StringBuilder();
        table.append("اسم المستلم                 | المبلغ | الوقت | التاريخ\n");
        table.append("------------------------------------------------\n");
        for (String[] p : rows) {
            table.append(p[2]).append(" | ").append(p[3]).append(" | ")
                 .append(p[1]).append(" | ").append(p[0]).append("\n");
        }
        if (rows.isEmpty()) table.append("لا توجد حوالات مكتملة مسجلة حتى الآن.");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("سجلات مجموعة " + label + " (" + rows.size() + ")")
                .setMessage(table.toString())
                .setNegativeButton("إغلاق", null)
                .setPositiveButton("تصدير إلى Excel", (d, w) -> exportOneGroup(label, prefKey))
                .create();
        dialog.show();
    }

    private void exportOneGroup(String label, String prefKey) {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            Toast.makeText(this, "التخزين غير متاح", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "حوالات أبو علوي");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد التصدير");
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            int n = writeGroup(dir, label, prefKey, today);
            Toast.makeText(this, "تم تصدير " + n + " سجل إلى Download/حوالات أبو علوي", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, "تعذر التصدير: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String formatEntries(String data) {
        if (TextUtils.isEmpty(data)) return "لا توجد حوالات مسجلة حتى الآن.";
        StringBuilder out = new StringBuilder();
        String[] rows = data.split("\\n");
        for (int i = 0; i < rows.length; i++) {
            String[] p = rows[i].split("\\t", -1);
            if (p.length >= 4) {
                out.append(i + 1).append(") اسم المستلم: ").append(p[2])
                   .append("\n   المبلغ: ").append(p[3])
                   .append("\n   التاريخ: ").append(p[0])
                   .append("\n   الوقت: ").append(p[1]).append("\n\n");
            }
        }
        return out.length() == 0 ? "لا توجد حوالات مكتملة؛ قد تكون هناك رسالة بانتظار الجزء الآخر." : out.toString();
    }

    private int count(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        return s.split("\\n").length;
    }

    private void exportFiles() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        try {
            int total;
            if (Build.VERSION.SDK_INT >= 29) {
                total = writeGroupToDownloads("العمقي", "rows_العمقي", today)
                        + writeGroupToDownloads("بن دول", "rows_بن دول » محمد علوي احمد", today);
            } else {
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    throw new Exception("التخزين الخارجي غير متاح");
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "حوالات أبو علوي");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد Download؛ تحقق من مساحة التخزين");
                total = writeGroup(dir, "العمقي", "rows_العمقي", today)
                        + writeGroup(dir, "بن دول", "rows_بن دول » محمد علوي احمد", today);
            }
            Toast.makeText(this, "تم تصدير " + total + " سجل. افتح مجلد Download/حوالات أبو علوي", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            String reason = ex.getMessage();
            if (reason == null || reason.trim().isEmpty()) reason = ex.getClass().getSimpleName();
            Toast.makeText(this, "تعذر التصدير: " + reason, Toast.LENGTH_LONG).show();
        }
    }

    private int writeGroupToDownloads(String label, String prefKey, String today) throws Exception {
        String data = getSharedPreferences("hawalat", MODE_PRIVATE).getString(prefKey, "");
        StringBuilder csv = new StringBuilder("\uFEFFاسم المستلم,المبلغ,التاريخ,الوقت\r\n");
        int n = 0;
        for (String row : data.split("\\n")) {
            String[] p = row.split("\\t", -1);
            if (p.length < 4 || !today.equals(p[0])) continue;
            csv.append(q(p[2])).append(',').append(q(p[3])).append(',').append(q(p[0])).append(',').append(q(p[1])).append("\r\n");
            n++;
        }
        String fileName = label + "-" + today + ".csv";
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/حوالات أبو علوي");
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
        android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("تعذر إنشاء ملف " + fileName + " في Download");
        try {
            java.io.OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new Exception("تعذر فتح ملف " + fileName + " للكتابة");
            try (java.io.OutputStream stream = out) {
                stream.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
            android.content.ContentValues ready = new android.content.ContentValues();
            ready.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, ready, null, null);
        } catch (Exception ex) {
            getContentResolver().delete(uri, null, null);
            throw ex;
        }
        return n;
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