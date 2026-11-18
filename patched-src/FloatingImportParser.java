package com.abualawi.hawalat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java parser for the text exposed by WhatsApp's Accessibility tree.
 * Each transfer is parsed inside its own sender block; names and amounts are
 * never paired from independent screen-wide lists.
 */
public final class FloatingImportParser {
    private static final Pattern SENDER_LINE = Pattern.compile(
            "(?im)^\\s*ابو\\s+علوي\\s+رقم\\s+جديد\\s*[:：-]?\\s*$");
    private static final Pattern TIME = Pattern.compile(
            "(?i)(?<!\\d)(\\d{1,2}):(\\d{2})\\s*(ص|م|AM|PM)?(?!\\d)");
    private static final Pattern NAME_LABEL = Pattern.compile(
            "(?:اسم\\s*المستلم|المستلم|مستلم|الاسم|الإسم|باسم|بااسم)\\s*[:：/\\-]?\\s*([^\\n\\r]+)");
    private static final Pattern LABELED_AMOUNT = Pattern.compile(
            "(?:المبلغ|مبلغ|القيمة)\\s*[:：]?\\s*([0-9]{2,7})");
    private static final Pattern CURRENCY_AMOUNT = Pattern.compile(
            "([0-9]{2,7})\\s*(?:﷼|ريال(?:\\s+يمني)?|سعودي|يمني|ر\\.?س|ر\\.?ي)");
    private static final Pattern LEADING_AMOUNT = Pattern.compile(
            "(?m)^\\s*([0-9]{2,7})\\s*[.)،:-]\\s*(?=[\\p{IsArabic}])");
    private static final Pattern SLASH_AMOUNT = Pattern.compile(
            "(?<![0-9])([0-9]{2,7})\\s*/\\s*[\\p{IsArabic}]+");
    private static final Pattern ATTACHED_PLACE = Pattern.compile(
            "(?<![0-9])([0-9]{2,7})(?=(?:حضرموت|المكلا|عتق|القطن|شبوه|شبوة|عدن))");
    private static final Pattern ATTACHED_ARABIC = Pattern.compile(
            "(?m)^\\s*([0-9]{2,7})(?=[\\p{IsArabic}])");
    private static final Pattern STANDALONE_AMOUNT = Pattern.compile(
            "^([0-9]{2,7})(?:\\s+.*)?$");

    public static final class Transfer {
        public final String name;
        public final String amount;
        public final String time;
        public final String raw;

        Transfer(String name, String amount, String time, String raw) {
            this.name = name;
            this.amount = amount;
            this.time = time;
            this.raw = raw;
        }
    }

    private FloatingImportParser() { }

    public static List<Transfer> parsePages(List<String> pages) {
        if (pages == null || pages.isEmpty()) return Collections.emptyList();
        ArrayList<String> mergedBlocks = new ArrayList<>();

        // The scanner stores pages newest-to-oldest. Merge them oldest-to-newest
        // and remove only the overlapping message blocks between adjacent pages.
        for (int pageIndex = pages.size() - 1; pageIndex >= 0; pageIndex--) {
            String page = keepTodayPart(pages.get(pageIndex));
            List<String> blocks = extractSenderBlocks(page);
            int overlap = longestOverlap(mergedBlocks, blocks);
            for (int i = overlap; i < blocks.size(); i++) mergedBlocks.add(blocks.get(i));
        }

        ArrayList<Transfer> transfers = new ArrayList<>();
        for (String block : mergedBlocks) {
            String normalized = normalizeDigits(block).replace("\\\\n", "\n")
                    .replace('\u200f', ' ').replace('\u200e', ' ')
                    .replace('\uFEFF', ' ').trim();
            String name = extractName(normalized);
            String amount = extractAmount(normalized);
            String time = extractTime(normalized);
            // Never invent a current timestamp for an older message. If WhatsApp
            // does not expose its message time, leave it in the raw scan for review.
            if (!name.isEmpty() && !amount.isEmpty() && !time.isEmpty()) {
                transfers.add(new Transfer(name, amount, time, block));
            }
        }
        return transfers;
    }

    public static String fingerprint(String date, String time, String name, String amount) {
        String minute = time == null ? "" : time.trim();
        if (minute.length() >= 5) minute = minute.substring(0, 5);
        String normalizedName = normalizeArabic(name).replaceAll("[\\sـ]", "").toLowerCase(Locale.ROOT);
        String normalizedAmount = normalizeDigits(amount).replaceAll("[^0-9]", "");
        String value = String.valueOf(date) + "|" + minute + "|" + normalizedName + "|" + normalizedAmount;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static List<Transfer> onlyNewOccurrences(List<Transfer> scan, String existingRows, String date) {
        ArrayList<Transfer> additions = new ArrayList<>();
        java.util.HashMap<String, Integer> storedCounts = new java.util.HashMap<>();
        if (existingRows != null && !existingRows.isEmpty()) {
            for (String line : existingRows.split("\\n")) {
                String[] p = line.split("\\t", -1);
                if (p.length >= 4) {
                    String key = fingerprint(p[0], p[1], p[2], p[3]);
                    Integer count = storedCounts.get(key);
                    storedCounts.put(key, count == null ? 1 : count + 1);
                }
            }
        }
        java.util.HashMap<String, Integer> scanCounts = new java.util.HashMap<>();
        if (scan != null) for (Transfer transfer : scan) {
            String key = fingerprint(date, transfer.time, transfer.name, transfer.amount);
            Integer seen = scanCounts.get(key);
            int occurrence = (seen == null ? 0 : seen) + 1;
            scanCounts.put(key, occurrence);
            Integer stored = storedCounts.get(key);
            int alreadyStored = stored == null ? 0 : stored;
            if (occurrence > alreadyStored) {
                additions.add(transfer);
                storedCounts.put(key, occurrence);
            }
        }
        return additions;
    }

    private static String keepTodayPart(String page) {
        if (page == null || page.isEmpty()) return "";
        String[] lines = page.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = normalizeArabic(lines[i]).toLowerCase(Locale.ROOT).trim();
            if (line.startsWith("امس") || line.equals("yesterday") || line.startsWith("yesterday")) {
                StringBuilder today = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) {
                    if (today.length() > 0) today.append('\n');
                    today.append(lines[j]);
                }
                return today.toString();
            }
        }
        return page;
    }

    private static List<String> extractSenderBlocks(String page) {
        ArrayList<String> blocks = new ArrayList<>();
        Matcher marker = SENDER_LINE.matcher(page == null ? "" : page);
        ArrayList<Integer> starts = new ArrayList<>();
        ArrayList<Integer> ends = new ArrayList<>();
        while (marker.find()) {
            starts.add(marker.start());
            ends.add(marker.end());
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = ends.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : page.length();
            String block = page.substring(start, end).trim();
            if (!block.isEmpty()) blocks.add(block);
        }
        return blocks;
    }

    private static int longestOverlap(List<String> existing, List<String> next) {
        int max = Math.min(existing.size(), next.size());
        for (int size = max; size > 0; size--) {
            boolean same = true;
            for (int i = 0; i < size; i++) {
                String a = canonicalBlock(existing.get(existing.size() - size + i));
                String b = canonicalBlock(next.get(i));
                if (!a.equals(b)) { same = false; break; }
            }
            if (same) return size;
        }
        return 0;
    }

    private static String canonicalBlock(String block) {
        return normalizeDigits(block == null ? "" : block)
                .replace('\u200f', ' ').replace('\u200e', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static String extractTime(String text) {
        Matcher matcher = TIME.matcher(normalizeDigits(text));
        String result = "";
        while (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            String suffix = matcher.group(3) == null ? "" : matcher.group(3).toLowerCase(Locale.ROOT);
            if (minute > 59) continue;
            if (suffix.equals("م") || suffix.equals("pm")) {
                if (hour < 1 || hour > 12) continue;
                hour = (hour % 12) + 12;
            } else if (suffix.equals("ص") || suffix.equals("am")) {
                if (hour < 1 || hour > 12) continue;
                hour = hour % 12;
            } else if (hour > 23) {
                continue;
            }
            result = String.format(Locale.ROOT, "%02d:%02d:00", hour, minute);
        }
        return result;
    }

    private static String extractName(String text) {
        Matcher labeled = NAME_LABEL.matcher(text);
        while (labeled.find()) {
            String candidate = cleanName(labeled.group(1));
            if (validName(candidate)) return candidate;
        }
        String best = "";
        int bestScore = -1;
        for (String line : text.split("[\\r\\n]+")) {
            String candidate = cleanName(line);
            if (!validName(candidate)) continue;
            int score = candidate.split("\\s+").length * 100 + candidate.length();
            if (score > bestScore) { best = candidate; bestScore = score; }
        }
        return best;
    }

    private static String cleanName(String value) {
        if (value == null) return "";
        String s = normalizeDigits(value).replace('\u200f', ' ').replace('\u200e', ' ')
                .replace(String.valueOf((char) 0xFEFF), " ");
        s = s.replaceAll("^(?:(?:تسويها|سويها|اعملها|اعمل|حولها|حوله|ارسلها|ارسليها)\\s+)?(?:لنا\\s+)?", "");
        s = s.replaceAll("(?m)^\\s*[0-9]{6,}\\s*(?=[\\p{IsArabic}])", "");
        s = s.replaceAll("(?i)\\s*(?:المرسل|اسم\\s*المرسل|المبلغ|مبلغ|القيمة|العملة|المنطقة|الموقع|العنوان|جوال\\s*المستلم|رقم\\s*(?:الحساب|الهاتف|الجوال)).*$", " ");
        s = s.replaceAll("^\\s*[0-9]{2,7}\\s*[.)،:-]\\s*", "");
        s = s.replaceAll("[0-9٠-٩۰-۹].*$", " ");
        s = s.replaceAll("(?:رقم\\s*)?حساب(?:ي)?(?:\\s+العمقي|\\s+بن\\s*دول)?", " ")
                .replaceAll("(?i)ريال\\s*سعودي|ريال|سعودي|يمني|صافي|المبلغ|مبلغ|المرسل", " ")
                .replaceAll("حوالة|حواله|حوله|تحويل|ايداع|إيداع|إيداعه|في\\s+الحساب|بالحساب|الحساب|رقم", " ")
                .replaceAll("[^\\p{IsArabic}\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        return s;
    }

    private static boolean validName(String value) {
        if (value == null || value.length() < 7 || value.matches(".*[0-9].*")) return false;
        String v = value.replaceAll("\\s+", " ").trim();
        String[] words = v.split(" ");
        if (words.length < 2) return false;
        String[] reject = {"السلام", "صباح", "مساء", "شكرا", "تم", "مرفق", "يرجى",
                "الرجاء", "رقم", "حساب", "دول", "ريال", "سعودي", "يمني",
                "حواله", "حوالة", "حوله", "تحويل", "ايداع", "المبلغ", "مبلغ",
                "اليوم", "معه", "في"};
        for (String token : words) {
            String normalizedToken = normalizeArabic(token);
            for (String blocked : reject) if (normalizedToken.equals(blocked)) return false;
        }
        if (normalizeArabic(v).contains("ابو علوي")) return false;
        return v.replaceAll("[^\\p{IsArabic}]", "").length() >= 6
                && v.matches(".*[\\p{IsArabic}].*");
    }

    private static String extractAmount(String text) {
        String s = normalizeDigits(text).replace("٬", "").replace(",", "").trim();
        s = s.replaceAll("(?:رقم\\s*(?:الحساب|الهاتف|الجوال)|الرقم|جوال المستلم)\\s*[:：]?\\s*[0-9]{6,}", " ");
        Matcher m = LABELED_AMOUNT.matcher(s);
        String value = "";
        while (m.find()) value = m.group(1);
        if (!value.isEmpty()) return value;
        m = CURRENCY_AMOUNT.matcher(s);
        while (m.find()) value = m.group(1);
        if (!value.isEmpty()) return value;
        m = LEADING_AMOUNT.matcher(s);
        if (m.find()) return m.group(1);
        m = SLASH_AMOUNT.matcher(s);
        while (m.find()) value = m.group(1);
        if (!value.isEmpty()) return value;
        m = ATTACHED_PLACE.matcher(s);
        while (m.find()) value = m.group(1);
        if (!value.isEmpty()) return value;
        m = ATTACHED_ARABIC.matcher(s);
        if (m.find()) return m.group(1);
        for (String line : s.split("[\\r\\n]+")) {
            m = STANDALONE_AMOUNT.matcher(line.trim());
            if (m.matches()) return m.group(1);
        }
        return "";
    }

    private static String normalizeDigits(String s) {
        if (s == null) return "";
        return s.replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
                .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
                .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
                .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9');
    }

    private static String normalizeArabic(String s) {
        return s == null ? "" : s.replace('أ','ا').replace('إ','ا').replace('آ','ا')
                .replace('ى','ي').replaceAll("[\\u064B-\\u065F\\u0670]", "");
    }
}
