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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String pkg = String.valueOf(root.getPackageName());
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) return "";
        StringBuilder text = new StringBuilder();
        collectText(root, text, 0);
        String s = text.toString();
        if (s.contains("العمقي") || s.contains("العمقى")) return "العمقي";
        if (s.contains("بن دول") || s.contains("محمد علوي احمد")) return "بن دول » محمد علوي احمد";
        return "";
    }

    private void startFullDayScan(String group, boolean manual) {
        if (busy) return;
        long now = System.currentTimeMillis();
        if (!manual && now - lastScanStartedAt < 12000L) return;
        busy = true;
        lastScanStartedAt = now;
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
        StringBuilder visible = new StringBuilder();
        collectText(root, visible, 0);
        String pageText = visible.toString();
        if (!pageText.trim().isEmpty()) pages.add(pageText);

        // WhatsApp's date divider is used as the stopping boundary. We keep
        // the page containing the divider so the final parser can exclude older content.
        if (containsYesterdayOrOlderDivider(pageText)) {
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
        for (int i = pages.size() - 1; i >= 0; i--) all.append(pages.get(i)).append("\\n");
        // The parser stores only records it can identify; raw pages are retained
        // for review instead of silently discarding text it cannot parse.
        int added = parseAndSave(all.toString(), group);
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        getSharedPreferences("hawalat", MODE_PRIVATE).edit()
                .putString("last_scan_raw_" + (group.equals("العمقي") ? "amqi" : "bindol"), all.toString())
                .putString("last_scan_report", "المجموعة: " + group + "؛ التاريخ: " + date
                        + "؛ الشاشات المقروءة: " + pages.size() + "؛ السجلات المضافة: " + added
                        + "؛ النتيجة: " + reason).commit();
        busy = false;
        if (button != null) button.setText("📥 إعادة استيراد حوالات اليوم");
        Toast.makeText(this, "انتهى الفحص: " + pages.size() + " شاشة، أضيف " + added
                + " سجل. " + reason + ". راجع السجلات غير المحللة قبل اعتماد التقرير.", Toast.LENGTH_LONG).show();
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
        for (int i=0; i<node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, out, depth+1);
                child.recycle();
            }
        }
    }

    private int parseAndSave(String screen, String group) {
        String sender = "ابو علوي رقم جديد";
        if (!screen.contains(sender)) {
            Toast.makeText(this, "لم تظهر رسائل المرسل «ابو علوي رقم جديد» في الشاشة الحالية", Toast.LENGTH_LONG).show();
            return 0;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        Pattern namePattern = Pattern.compile("(?:الاسم|المستلم|اسم\\s*المستلم)\\s*[:：/\\-]?\\s*([\\p{L}ء-يـ ]{4,})");
        Pattern amountPattern = Pattern.compile("(?<![\\d٠-٩])([0-9٠-٩][0-9٠-٩٬,٫.]*)");
        Matcher nm = namePattern.matcher(screen);
        Matcher am = amountPattern.matcher(screen);
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> amounts = new ArrayList<>();
        while (nm.find()) names.add(nm.group(1).replaceAll("[\\s.،؛]+$","").trim());
        while (am.find()) amounts.add(am.group(1).replace("٬","").replace(",","").replace("٫","."));
        if (names.isEmpty() || amounts.isEmpty()) return 0;

        SharedPreferences prefs = getSharedPreferences("hawalat", MODE_PRIVATE);
        String key = group.equals("العمقي") ? "rows_العمقي" : "rows_بن دول » محمد علوي احمد";
        synchronized (MainActivity.TRANSFER_DATA_LOCK) {
            String old = prefs.getString(key, "");
            StringBuilder next = new StringBuilder(old);
            int count = Math.min(names.size(), amounts.size());
            int added = 0;
            for (int i=0; i<count; i++) {
                String row = date + "\t" + time + "\t" + names.get(i) + "\t" + amounts.get(i);
                if (old.contains(row) || next.indexOf(row) >= 0) continue;
                if (next.length() > 0) next.append('\n');
                next.append(row);
                added++;
            }
            prefs.edit().putString(key, next.toString())
                    .putString("last_floating_import", "تمت قراءة الشاشة الظاهرة " + date + " " + time
                            + "؛ أضيف " + added + " سجل (يلزم مراجعة الرسائل غير الظاهرة).").commit();
            return added;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        if (button != null && windowManager != null) {
            try { windowManager.removeView(button); } catch (Exception ignored) { }
        }
        button = null;
        super.onDestroy();
    }
}