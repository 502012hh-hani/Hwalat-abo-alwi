#!/usr/bin/env python3
"""Regression fixtures for the seven 2026-11-14 Al-Amqi transfers.

These fixtures mirror the normalization and extraction rules in
WhatsAppNotificationListener.java. The Android build remains a separate check.
"""
import re
import unicodedata

def normalize(s):
    return s.translate(str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789"))

def clean_name(value):
    s = normalize(value or "").replace("\u200f", " ").replace("\u200e", " ").replace("\ufeff", " ")
    s = re.sub(r"^(?:(?:تسويها|سويها|اعملها|اعمل|حولها|حوله|ارسلها|ارسليها)\s+)?(?:لنا\s+)?", "", s)
    s = re.sub(r"(?m)^\s*[0-9]{6,}\s*(?=[\u0600-\u06ff])", "", s)
    s = re.sub(r"(?i)\s*(?:المرسل|اسم\s*المرسل|المبلغ|مبلغ|القيمة|العملة|المنطقة|الموقع|العنوان|جوال\s*المستلم|رقم\s*(?:الحساب|الهاتف|الجوال)).*$", " ", s)
    s = re.sub(r"^\s*[0-9]{2,7}\s*[.)،:-]\s*", "", s)
    s = re.sub(r"[0-9].*$", " ", s)
    s = re.sub(r"(?:رقم\s*)?حساب(?:ي)?(?:\s+العمقي|\s+بن\s*دول)?", " ", s)
    s = re.sub(r"ريال\s*سعودي|ريال|سعودي|يمني|صافي|المبلغ|مبلغ|المرسل", " ", s)
    s = re.sub(r"حوالة|حواله|حوله|تحويل|ايداع|إيداع|إيداعه|في\s+الحساب|بالحساب|الحساب|رقم", " ", s)
    return re.sub(r"[^\u0600-\u06ff\s]", " ", s)
    
def valid_name(v):
    v = re.sub(r"\s+", " ", v).strip()
    if len(v) < 7 or len(v.split()) < 2:
        return False
    if any(x in v for x in ("السلام", "صباح", "مساء", "شكرا", "رقم", "حساب", "ريال", "حوالة", "تحويل", "ايداع")):
        return False
    return len(re.sub(r"[^\u0600-\u06ff]", "", v)) >= 6

def extract_name(text):
    s = normalize(text)
    for m in re.finditer(r"(?:اسم\s*المستلم|المستلم|مستلم|الاسم|الإسم|باسم|بااسم)\s*[:：/\-]?\s*([^\n\r]+)", s):
        c = re.sub(r"\s+", " ", clean_name(m.group(1))).strip()
        if valid_name(c):
            return c
    best = ""
    for line in re.split(r"[\r\n]+", s):
        c = re.sub(r"\s+", " ", clean_name(line)).strip()
        if valid_name(c) and len(c.split()) * 100 + len(c) > len(best.split()) * 100 + len(best):
            best = c
    return best

def extract_amount(text):
    s = normalize(text).replace("٬", "").replace(",", "").strip()
    s = re.sub(r"(?:رقم\s*(?:الحساب|الهاتف|الجوال)|الرقم|جوال المستلم)\s*[:：]?\s*[0-9]{6,}", " ", s)
    for pattern in (r"(?:المبلغ|مبلغ|القيمة)\s*[:：]?\s*([0-9]{2,7})",
                    r"([0-9]{2,7})\s*(?:﷼|ريال(?:\s+يمني)?|سعودي|يمني|ر\.?س|ر\.?ي)",
                    r"(?m)^\s*([0-9]{2,7})\s*[.)،:-]\s*(?=[\u0600-\u06ff])",
                    r"(?<![0-9])([0-9]{2,7})\s*/\s*[\u0600-\u06ff]+",
                    r"(?<![0-9])([0-9]{2,7})(?=(?:حضرموت|المكلا|عتق|القطن|شبوه|شبوة|عدن))",
                    r"(?m)^\s*([0-9]{2,7})(?=[\u0600-\u06ff])",
                    r"(?m)^([0-9]{2,7})(?:\s+.*)?$"):
        matches = list(re.finditer(pattern, s))
        if matches:
            return matches[-1].group(1)
    return ""

cases = [
    ("سعيد صالح عبود بن سميدع\n730 ريال", "سعيد صالح عبود بن سميدع", "730"),
    ("254093946عامر حيدر عمر الصقير\n20000", "عامر حيدر عمر الصقير", "20000"),
    ("عبدالله محمد حسين الحامد\n3000", "عبدالله محمد حسين الحامد", "3000"),
    ("انور هود عبيد سكران1000/ايداع\n254123437", "انور هود عبيد سكران", "1000"),
    ("حاتميه علي سعيد الصيعري1000/ايداع254278067", "حاتميه علي سعيد الصيعري", "1000"),
    ("100 ايداع في الحساب\nباسم : سالمين مرعي سالمين المشجري\nرقم الحساب : 254169686", "سالمين مرعي سالمين المشجري", "100"),
    ("المرسل عبدالله ناصر مهدي بجنف\nالمستلم عدنان عبدالله مهدي بجنف\n1000بيعث العمقي\n7774426321", "عدنان عبدالله مهدي بجنف", "1000"),
]
for i, (raw, expected_name, expected_amount) in enumerate(cases, 1):
    name, amount = extract_name(raw), extract_amount(raw)
    assert name == expected_name, f"case {i}: name={name!r}, expected={expected_name!r}"
    assert amount == expected_amount, f"case {i}: amount={amount!r}, expected={expected_amount!r}"
    print(f"PASS {i}: {name} | {amount}")
print("PASS: all 7 Al-Amqi regression fixtures")
