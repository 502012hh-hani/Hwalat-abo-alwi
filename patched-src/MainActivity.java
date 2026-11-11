package com.abualawi.hawalat;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.ComponentName;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
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
        note.setText("ملاحظة: يقرأ التطبيق إشعارات واتساب التي يسمح النظام بعرضها فقط، ولا يفتح محادثات واتساب مباشرة. يتم تصدير ملفات Excel بصيغة XLSX.");
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
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            int n;
            if (Build.VERSION.SDK_INT >= 29) {
                // Use the same scoped-storage-safe MediaStore path as bulk export.
                n = writeGroupToDownloads(label, prefKey, today);
            } else {
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    throw new Exception("التخزين غير متاح");
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "حوالات أبو علوي");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد التصدير");
                n = writeGroup(dir, label, prefKey, today);
            }
            Toast.makeText(this, "تم إنشاء ملف Excel حقيقي (" + n + " سجل) في Download/حوالات أبو علوي", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            String reason = ex.getMessage();
            if (reason == null || reason.trim().isEmpty()) reason = ex.getClass().getSimpleName();
            Toast.makeText(this, "تعذر تصدير مجموعة " + label + ": " + reason, Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "تم إنشاء ملفات Excel (" + total + " سجل). افتح Download/حوالات أبو علوي", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            String reason = ex.getMessage();
            if (reason == null || reason.trim().isEmpty()) reason = ex.getClass().getSimpleName();
            Toast.makeText(this, "تعذر التصدير: " + reason, Toast.LENGTH_LONG).show();
        }
    }

    private int writeGroupToDownloads(String label, String prefKey, String today) throws Exception {
        final String fileName = label + "-" + today + ".xlsx";
        String data = getSharedPreferences("hawalat", MODE_PRIVATE).getString(prefKey, "");
        ArrayList<String[]> rows = new ArrayList<>();
        if (data != null && !data.isEmpty()) {
            for (String row : data.split("\\n")) {
                String[] p = row.split("\\t", -1);
                if (p.length >= 4 && today.equals(p[0])) {
                    rows.add(new String[]{p[2], p[3], p[0], p[1]});
                }
            }
        }

        // Android 10+ uses MediaStore. Direct File writes to public Downloads
        // can fail under scoped storage even when a legacy directory exists.
        byte[] bytes = buildXlsxBytes(rows);
        File validationFile = new File(getCacheDir(), fileName + ".validate");
        try {
            try (FileOutputStream out = new FileOutputStream(validationFile, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (!validationFile.isFile() || validationFile.length() != bytes.length) {
                throw new Exception("حجم ملف التحقق غير مطابق");
            }
            validateXlsx(validationFile, rows.size());
        } finally {
            if (validationFile.exists()) validationFile.delete();
        }

        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentResolver resolver = getContentResolver();
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/حوالات أبو علوي/";
            ArrayList<Uri> previousFiles = new ArrayList<>();
            String[] projection = new String[]{MediaStore.MediaColumns._ID};
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection, selection, new String[]{fileName, relativePath}, null)) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        previousFiles.add(Uri.withAppendedPath(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id)));
                    }
                }
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName + ".pending");
            values.put(MediaStore.MediaColumns.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri newFile = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (newFile == null) throw new Exception("تعذر إنشاء ملف في مجلد التنزيلات عبر MediaStore");

            boolean published = false;
            try {
                try (java.io.OutputStream out = resolver.openOutputStream(newFile, "w")) {
                    if (out == null) throw new Exception("تعذر فتح ملف التنزيلات للكتابة");
                    out.write(bytes);
                    out.flush();
                }
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
                int changed = resolver.update(newFile, publish, null, null);
                if (changed != 1) throw new Exception("تعذر اعتماد ملف XLSX في التنزيلات");
                published = true;
            } finally {
                if (!published) {
                    try { resolver.delete(newFile, null, null); } catch (Exception ignored) {}
                }
            }

            // Remove older same-day copies only after the new workbook is fully written
            // and published, so a failed export never destroys the previous valid file.
            for (Uri oldFile : previousFiles) {
                try { resolver.delete(oldFile, null, null); } catch (Exception ignored) {}
            }
            android.util.Log.i("HawalatExport", "MediaStore XLSX published: "
                    + fileName + ", rows=" + rows.size() + ", bytes=" + bytes.length);
            return rows.size();
        }

        // Legacy path for Android 9 and earlier.
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            throw new Exception("التخزين الخارجي غير متاح");
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "حوالات أبو علوي");
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new Exception("تعذر إنشاء مجلد التنزيلات: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory() || !dir.canWrite()) {
            throw new Exception("مجلد التنزيلات غير قابل للكتابة: " + dir.getAbsolutePath());
        }
        File target = new File(dir, fileName);
        File temp = new File(dir, fileName + ".tmp");
        File backup = new File(dir, fileName + ".bak");
        try {
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            validateXlsx(temp, rows.size());
            if (backup.exists() && !backup.delete()) throw new Exception("تعذر حذف النسخة الاحتياطية القديمة");
            boolean hadTarget = target.exists();
            if (hadTarget && !target.renameTo(backup)) throw new Exception("تعذر حفظ نسخة ملف اليوم السابقة");
            if (!temp.renameTo(target)) {
                if (hadTarget && backup.exists()) backup.renameTo(target);
                throw new Exception("تعذر اعتماد ملف Excel الجديد");
            }
            if (backup.exists()) backup.delete();
            return rows.size();
        } catch (Exception ex) {
            if (temp.exists()) temp.delete();
            if (!target.exists() && backup.exists()) backup.renameTo(target);
            throw ex;
        }
    }

    private void validateXlsx(File file, int expectedDataRows) throws Exception {
        java.util.HashSet<String> required = new java.util.HashSet<>();
        required.add("[Content_Types].xml");
        required.add("_rels/.rels");
        required.add("xl/workbook.xml");
        required.add("xl/_rels/workbook.xml.rels");
        required.add("xl/worksheets/sheet1.xml");
        int worksheetRows = -1;
        int workbookSheets = 0;
        String spreadsheetNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = zin.read(chunk)) != -1) content.write(chunk, 0, read);
                if (required.contains(name)) required.remove(name);
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    javax.xml.parsers.DocumentBuilderFactory factory =
                            javax.xml.parsers.DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
                    catch (Exception ignored) {}
                    org.w3c.dom.Document document = factory.newDocumentBuilder().parse(
                            new java.io.ByteArrayInputStream(content.toByteArray()));
                    if ("xl/workbook.xml".equals(name)) {
                        workbookSheets = document.getElementsByTagNameNS(spreadsheetNs, "sheet").getLength();
                    } else if ("xl/worksheets/sheet1.xml".equals(name)) {
                        worksheetRows = document.getElementsByTagNameNS(spreadsheetNs, "row").getLength();
                    }
                }
            }
        }
        if (!required.isEmpty()) throw new Exception("أجزاء XLSX الأساسية مفقودة: " + required);
        if (workbookSheets < 1) throw new Exception("ملف Excel لا يحتوي ورقة عمل");
        if (worksheetRows != expectedDataRows + 1) {
            throw new Exception("عدد صفوف Excel غير مطابق؛ المتوقع "
                    + (expectedDataRows + 1) + " والفعلي " + worksheetRows);
        }
    }

    private byte[] buildXlsxBytes(ArrayList<String[]> rows) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            putZipText(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "</Types>");
            putZipText(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            putZipText(zip, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"الحوالات\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            putZipText(zip, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "</Relationships>");

            StringBuilder sheet = new StringBuilder();
            sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                 .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            String[] headers = {"اسم المستلم", "المبلغ", "التاريخ", "الوقت"};
            appendXlsxRow(sheet, 1, headers, true);
            for (int i = 0; i < rows.size(); i++) appendXlsxRow(sheet, i + 2, rows.get(i), false);
            sheet.append("</sheetData></worksheet>");
            putZipText(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return buffer.toByteArray();
    }

    private void appendXlsxRow(StringBuilder xml, int rowNumber, String[] values, boolean header) {
        xml.append("<row r=\"").append(rowNumber).append("\">");
        for (int col = 0; col < values.length; col++) {
            String ref = String.valueOf((char)('A' + col)) + rowNumber;
            String value = values[col] == null ? "" : values[col];
            if (!header && col == 1 && value.matches("[0-9]+")) {
                xml.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
            } else {
                xml.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                   .append(xmlEscape(value)).append("</t></is></c>");
            }
        }
        xml.append("</row>");
    }

    private String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private void putZipText(java.util.zip.ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
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