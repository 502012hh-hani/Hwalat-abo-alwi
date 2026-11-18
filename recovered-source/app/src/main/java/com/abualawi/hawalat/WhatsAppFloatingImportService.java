package com.abualawi.hawalat;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Floating import entry point. It reads only text exposed by WhatsApp's
 * Accessibility tree; it does not access WhatsApp's private database.
 */
public class WhatsAppFloatingImportService extends AccessibilityService {
    private WindowManager windowManager;
    private TextView button;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy = false;
    private String lastDetectedGroup = "";
    private long lastScanStartedAt = 0L;
    private String scanDate = "";
    private static final int MAX_SCAN_PAGES = 100;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 150;
        setServiceInfo(info);
        showFloatingButton();
    }

    private void showFloatingButton() {
        if (button != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        button = new TextView(this);
        button.setText("📥 استيراد الحوالات");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setPadding(24, 15, 24, 15);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(15, 76, 110));
        bg.setCornerRadius(48);
        button.setBackground(bg);
        button.setOnClickListener(v -> {
            String group = detectOpenGroup();
            if (group.isEmpty()) Toast.makeText(this, "افتح مجموعة العمقي أو بن دول أولاً", Toast.LENGTH_SHORT).show();
            else startFullDayScan(group, true);
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = 110;
        try { windowManager.addView(button, lp); }
        catch (Exception e) { button = null; }
    }

    private String detectOpenGroup() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        try {
            String pkg = String.valueOf(root.getPackageName());
            if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) return "";
            StringBuilder text = new StringBuilder();
            collectText(root, text, 0);
            String[] lines = text.toString().split("\\n");
            // The conversation title is near the beginning of the tree. Prefer
            // it over older message text that may mention the other group.
            for (int i = 0; i < Math.min(lines.length, 12); i++) {
                String line = lines[i];
                if (line.contains("بن دول") || line.contains("محمد علوي احمد")) return "بن دول » محمد علوي احمد";
                if (line.contains("العمقي") || line.contains("العمقى")) return "العمقي";
            }
            return "";
        } finally {
            root.recycle();
        }
    }

    private void startFullDayScan(String group, boolean manual) {
        if (busy) return;
        long now = System.currentTimeMillis();
        if (!manual && now - lastScanStartedAt < 12000L) return;
        busy = true;
        lastScanStartedAt = now;
        scanDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
        if (button != null) button.setText("⏳ جارٍ فحص رسائل اليوم");
        Toast.makeText(this, "بدأ فحص رسائل اليوم في " + group, Toast.LENGTH_SHORT).show();
        ArrayList<String> pages = new ArrayList<>();
        scanOlderPages(group, pages, 0);
    }

    private void scanOlderPages(String group, ArrayList<String> pages, int page) {
        if (page >= MAX_SCAN_PAGES) {
            finishScan(group, pages, "توقف حد الأمان بعد " + MAX_SCAN_PAGES + " شاشة");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { finishScan(group, pages, "تعذر الوصول إلى شاشة واتساب"); return; }
        String pkg = String.valueOf(root.getPackageName());
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) {
            root.recycle();
            finishScan(group, pages, "توقف الفحص لأن الشاشة لم تعد واتساب");
            return;
        }
        StringBuilder visible = new StringBuilder();
        collectText(root, visible, 0);
        String pageText = visible.toString();
        if (!pageText.trim().isEmpty()) pages.add(pageText);

        // Keep the boundary page. The parser removes content before yesterday's
        // divider, because today's messages appear below that divider in chat order.
        boolean reachedYesterday = containsYesterdayOrOlderDivider(pageText);
        if (reachedYesterday) {
            root.recycle();
            finishScan(group, pages, "تم الوصول إلى فاصل ما قبل اليوم");
            return;
        }
        AccessibilityNodeInfo scrollable = findScrollable(root, 0);
        boolean moved = scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        if (scrollable != null) scrollable.recycle();
        root.recycle();
        if (!moved) { finishScan(group, pages, "وصلنا إلى أقدم محتوى يستطيع واتساب عرضه"); return; }
        handler.postDelayed(() -> scanOlderPages(group, pages, page + 1), 550);
    }

    private boolean containsYesterdayOrOlderDivider(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        return x.contains("أمس") || x.contains("امس") || x.contains("yesterday");
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;
        if (node.isScrollable()) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findScrollable(child, depth + 1);
                child.recycle();
                if (found != null) return found;
            }
        }
        return null;
    }

    private void finishScan(String group, ArrayList<String> pages, String reason) {
        StringBuilder all = new StringBuilder();
        for (int i = pages.size() - 1; i >= 0; i--) {
            if (all.length() > 0) all.append('\n');
            all.append(pages.get(i));
        }
        String date = scanDate.isEmpty()
                ? new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) : scanDate;
        List<FloatingImportParser.Transfer> parsed = FloatingImportParser.parsePages(pages);
        int added = saveParsedTransfers(parsed, group, date);
        getSharedPreferences("hawalat", MODE_PRIVATE).edit()
                .putString("last_scan_raw_" + (group.equals("العمقي") ? "amqi" : "bindol"), all.toString())
                .putString("last_scan_report", "المجموعة: " + group + "؛ التاريخ: " + date
                        + "؛ الشاشات المقروءة: " + pages.size() + "؛ الرسائل القابلة للتحليل: "
                        + parsed.size() + "؛ السجلات الجديدة: " + added + "؛ النتيجة: " + reason).commit();
        busy = false;
        if (button != null) button.setText("📥 إعادة استيراد حوالات اليوم");
        Toast.makeText(this, "انتهى الفحص: " + pages.size() + " شاشة؛ "
                + parsed.size() + " رسالة قابلة للتحليل؛ أضيف " + added
                + " سجل جديد دون إعادة استيراد الموجود. " + reason, Toast.LENGTH_LONG).show();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) {
            lastDetectedGroup = "";
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        handler.removeCallbacks(autoOpenCheck);
        handler.postDelayed(autoOpenCheck, 900);
    }

    private final Runnable autoOpenCheck = () -> {
        String group = detectOpenGroup();
        if (group.isEmpty()) { lastDetectedGroup = ""; return; }
        if (!group.equals(lastDetectedGroup)) {
            lastDetectedGroup = group;
            startFullDayScan(group, false);
        }
    };

    private void collectText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 45) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.append(t).append('\n');
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) out.append(d).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { collectText(child, out, depth + 1); }
                finally { child.recycle(); }
            }
        }
    }

    private int saveParsedTransfers(List<FloatingImportParser.Transfer> transfers, String group, String date) {
        SharedPreferences prefs = getSharedPreferences("hawalat", MODE_PRIVATE);
        String key = group.equals("العمقي") ? "rows_العمقي" : "rows_بن دول » محمد علوي احمد";
        synchronized (MainActivity.TRANSFER_DATA_LOCK) {
            String old = prefs.getString(key, "");
            Map<String, Integer> storedCounts = new HashMap<>();
            if (!old.isEmpty()) {
                for (String line : old.split("\\n")) {
                    String[] p = line.split("\\t", -1);
                    if (p.length >= 4) {
                        String fingerprint = FloatingImportParser.fingerprint(p[0], p[1], p[2], p[3]);
                        Integer count = storedCounts.get(fingerprint);
                        storedCounts.put(fingerprint, count == null ? 1 : count + 1);
                    }
                }
            }

            Map<String, Integer> scanCounts = new HashMap<>();
            StringBuilder additions = new StringBuilder();
            int added = 0;
            for (FloatingImportParser.Transfer transfer : transfers) {
                String fingerprint = FloatingImportParser.fingerprint(
                        date, transfer.time, transfer.name, transfer.amount);
                Integer seen = scanCounts.get(fingerprint);
                int occurrence = (seen == null ? 0 : seen) + 1;
                scanCounts.put(fingerprint, occurrence);
                Integer stored = storedCounts.get(fingerprint);
                int alreadyStored = stored == null ? 0 : stored;
                // Count occurrences rather than unique names: legitimate repeated
                // transfers remain separate, while a rescan is idempotent.
                if (occurrence <= alreadyStored) continue;
                String row = date + "\\t" + transfer.time + "\\t" + transfer.name + "\\t" + transfer.amount;
                if (additions.length() > 0) additions.append('\n');
                additions.append(row);
                storedCounts.put(fingerprint, occurrence);
                added++;
            }

            String next = additions.length() == 0 ? old
                    : additions.toString() + (old.isEmpty() ? "" : "\\n" + old);
            prefs.edit().putString(key, next)
                    .putString("last_floating_import", "فحص " + date + " في " + group
                            + "؛ رسائل قابلة للتحليل: " + transfers.size()
                            + "؛ سجلات جديدة: " + added + "؛ التكرارات الموجودة لم تُضف مرة أخرى.")
                    .commit();
            return added;
        }
    }

    @Override public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        busy = false;
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (button != null && windowManager != null) {
            try { windowManager.removeView(button); } catch (Exception ignored) { }
        }
        button = null;
        super.onDestroy();
    }
}