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
        button.setOnClickListener(v -> importVisibleChat());
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

    private void importVisibleChat() {
        if (busy) return;
        String pkg = getRootInActiveWindow() == null ? "" :
                String.valueOf(getRootInActiveWindow().getPackageName());
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) {
            Toast.makeText(this, "افتح مجموعة واتساب أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Toast.makeText(this, "تعذر قراءة شاشة واتساب الحالية", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder text = new StringBuilder();
        collectText(root, text, 0);
        String screen = text.toString();
        String group = screen.contains("العمقي") || screen.contains("العمقى") ? "العمقي" :
                (screen.contains("بن دول") || screen.contains("محمد علوي احمد")
                        ? "بن دول » محمد علوي احمد" : "");
        if (group.isEmpty()) {
            Toast.makeText(this, "لم أتعرف على المجموعة. افتح العمقي أو بن دول", Toast.LENGTH_LONG).show();
            return;
        }
        busy = true;
        int imported = parseAndSave(screen, group);
        busy = false;
        Toast.makeText(this, "تم فحص النص الظاهر: " + imported
                + " حوالة مضافة. هذه قراءة للمحتوى المحمّل والظاهر فقط؛ راجع بقية رسائل اليوم بالتمرير.", Toast.LENGTH_LONG).show();
    }

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