#!/usr/bin/env python3
"""Static regression contract for the Android XLSX export path.

This deliberately checks the production Java source used by both APK workflows.
It is not a substitute for exercising storage on a physical Android 10 device.
"""
from pathlib import Path

MAIN = Path("patched-src/MainActivity.java").read_text(encoding="utf-8")
LISTENER = Path("patched-src/WhatsAppNotificationListener.java").read_text(encoding="utf-8")
BUILD = Path(".github/workflows/validate-apk.yml").read_text(encoding="utf-8")


def method(source: str, signature: str) -> str:
    start = source.find(signature)
    assert start >= 0, f"missing method: {signature}"
    opening = source.find("{", start)
    assert opening >= 0, f"missing method body: {signature}"
    depth = 0
    for i in range(opening, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
    raise AssertionError(f"unbalanced method: {signature}")


write = method(MAIN, "private int writeGroupToDownloads(")
validate = method(MAIN, "private void validateXlsx(")
cleanup = method(MAIN, "private int removePreviouslyExportedDays(")
export = method(MAIN, "private void exportFiles()")
xlsx = method(MAIN, "private byte[] buildXlsxBytes(")
save = method(LISTENER, "private void saveTransfer(")

# Separate daily workbook per group/date; export is a replacement snapshot, not append.
assert 'String[] labels = {"العمقي", "بن دول"}' in export
assert 'String[] keys = {"rows_العمقي", "rows_بن دول » محمد علوي احمد"}' in export
assert 'label + "-" + date + ".xlsx"' in write
assert 'date.equals(p[0])' in write
assert 'new String[]{p[2], p[3], p[0], p[1]}' in write
assert "buildXlsxBytes(rows)" in write
assert 'new FileOutputStream(temp, false)' in write
assert "validateXlsx(temp, rows)" in write
assert "validateXlsx(target, rows)" in write

# The validator must compare the actual four-column worksheet contents to the store.
for required_part in (
    '"[Content_Types].xml"',
    '"xl/workbook.xml"',
    '"xl/worksheets/sheet1.xml"',
    '"xl/styles.xml"',
    'String[] expectedHeaders = {"اسم المستلم", "المبلغ", "التاريخ", "الوقت"}',
    "actualRows.get(i), expectedRows.get(i)",
    "readXlsxRow",
):
    assert required_part in validate, f"XLSX validation missing: {required_part}"

# Old rows are removed only after a matching archive has passed full validation.
assert "validateXlsx(archived, entry.getValue())" in cleanup
assert "safeToDelete.add(entry.getKey())" in cleanup
assert "if (safeToDelete.isEmpty()) continue;" in cleanup
assert 'p[0].compareTo(today) < 0' in cleanup
assert 'kept.append("\\n")' in cleanup
assert 'kept.append("\\\\n")' not in cleanup

# Repeated transfers are retained in SharedPreferences and highlighted in the workbook.
assert 'prefs.edit().putString(key, row + (old.isEmpty() ? "" : "\\n" + old))' in save
assert "duplicateRows.contains(i)" in xlsx
assert "normalizeDuplicateName(row[0])" in xlsx
assert "normalizeDuplicateAmount(row[1])" in xlsx
assert "FFFFFF00" in xlsx

# Both export and archive cleanup must use the same public Downloads subfolder.
assert MAIN.count('new File(downloads, "أبو طلال")') >= 2

# Android 10 compatibility is enforced by the validation build itself.
assert 'requestLegacyExternalStorage", "true"' in BUILD
assert 'android.permission.WRITE_EXTERNAL_STORAGE' in BUILD
assert 'android:maxSdkVersion="29"' in BUILD
assert "targetSdkVersion 28" in BUILD

# Notification writes remain append-only: duplicate rows are not discarded.
assert 'row + (old.isEmpty() ? "" : "\\n" + old)' in save
print("PASS: daily per-group/date XLSX snapshot contract")
print("PASS: exported worksheet is checked against every stored cell")
print("PASS: archived rows are pruned only after exact archive validation")
print("PASS: duplicate transfers remain stored and are highlighted")
print("PASS: Android 10 legacy Downloads compatibility is enforced")
