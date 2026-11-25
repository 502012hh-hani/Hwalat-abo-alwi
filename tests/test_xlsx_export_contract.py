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
export_one = method(MAIN, "private void exportOneGroup(")
xlsx = method(MAIN, "private byte[] buildXlsxBytes(")
save = method(LISTENER, "private void saveTransfer(")
process_message = method(LISTENER, "private boolean processMessage(")

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
# A backup from an earlier crash must survive when the target is absent at retry.
assert "retainPreviousWorkbookBackup(backup, fileName)" in write
assert write.index("retainPreviousWorkbookBackup(backup, fileName)") < write.index("target.renameTo(backup)")
assert write.index("validateXlsx(target, rows)") < write.index("boolean currentBackupRemoved")
assert 'name.startsWith(fileName + ".bak.previous.")' in write
assert "private File retainPreviousWorkbookBackup(File backup, String fileName)" in MAIN
assert "boolean movedTargetToBackup = false;" in write
assert "movedTargetToBackup = true;" in write
assert "shouldRestoreWorkbookBackup(hadTarget, movedTargetToBackup, backup.exists())" in write
assert "restoreWorkbookBackup(target, backup)" in write
assert "private boolean shouldRestoreWorkbookBackup(boolean hadTarget, boolean movedTargetToBackup," in MAIN
assert "return backupExists && (!hadTarget || movedTargetToBackup);" in MAIN
assert "private void restoreWorkbookBackup(File target, File backup)" in MAIN


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
assert 'putString(key, row + (old.isEmpty() ? "" : "\\n" + old))' in save
assert "duplicateRows.contains(i)" in xlsx
assert "normalizeDuplicateName(row[0])" in xlsx
assert "normalizeDuplicateAmount(row[1])" in xlsx
assert "FFFFFF00" in xlsx

# Both export and archive cleanup must use the same public Downloads subfolder.
assert 'new File(downloads, "أبو علوي")' in write
assert 'Environment.getExternalStoragePublicDirectory(' in cleanup
assert '"أبو علوي")' in cleanup

# Android 10 compatibility is enforced by the validation build itself.
assert 'requestLegacyExternalStorage", "true"' in BUILD
assert 'android.permission.WRITE_EXTERNAL_STORAGE' in BUILD
assert 'android:maxSdkVersion="29"' in BUILD
assert "targetSdkVersion 28" in BUILD

# Notification appends and archive cleanup share one process lock.
# Without this, cleanup could commit an older SharedPreferences snapshot after
# a new notification was appended, silently dropping that newly received transfer.
assert "public static final Object TRANSFER_DATA_LOCK" in MAIN
assert "synchronized (MainActivity.TRANSFER_DATA_LOCK)" in cleanup
assert "synchronized (MainActivity.TRANSFER_DATA_LOCK)" in save

# Notification writes remain append-only: duplicate rows are not discarded.
assert 'row + (old.isEmpty() ? "" : "\\n" + old)' in save

# Every export is built from a stable snapshot captured under the same lock
# used by WhatsApp notification writes. The all-groups export captures both
# groups together so a notification cannot split the batch between two states.
assert "synchronized (TRANSFER_DATA_LOCK)" in export_one
assert "getStoredDatesFromData(data)" in export_one
assert "writeGroupToDownloads(label, prefKey, date, data)" in export_one
assert "synchronized (TRANSFER_DATA_LOCK)" in export
assert "String[] snapshots = new String[2]" in export
assert "snapshots[0] = prefs.getString(keys[0], \"\")" in export
assert "snapshots[1] = prefs.getString(keys[1], \"\")" in export
assert "getStoredDatesFromData(snapshots[i])" in export
assert "writeGroupToDownloads(labels[i], keys[i], date, snapshots[i])" in export
assert "Download/أبو علوي" in export

# Extract active polling methods before asserting their retry ordering.
process_once = method(LISTENER, "private boolean processMessageOnce(")
active_probe = method(LISTENER, "private void processIfActiveNotificationChanged(")
active_handler = method(LISTENER, "private boolean handleNotificationPosted(")

# Active notification polling must retry a failed durable save instead of
# recording the notification signature before its transfer has been committed.
assert "activeNotificationPreferenceKey(sbn.getKey())" in active_probe
assert "boolean handled = handleNotificationPosted(sbn, false);" in active_probe
assert active_probe.index("boolean handled = handleNotificationPosted(sbn, false);") < active_probe.index("if (handled)")
assert active_probe.rindex('prefs.edit().putString(key, signature).remove(legacyKey).commit()') > active_probe.index("if (handled)")
assert "return false;" in process_once
assert "return true;" in process_once
assert "if (!processMessageOnce(structuredTexts.get(i), group, structuredTimes.get(i)))" in active_handler
assert "return processMessageOnce(body, group, posted);" in active_handler

# Reposted notification dedup uses a collision-resistant digest and bounded cache.
assert "synchronized (MainActivity.TRANSFER_DATA_LOCK)" in process_once
assert "processedMessageDigest(signature)" in process_once
assert '"processed_message_digests_v2"' in process_once
assert "rememberProcessedMessageDigest(stored, digest)" in process_once
assert "transferSaved = processMessage(body, group, posted, digest, legacyKey)" in process_once
assert "if (!transferSaved)" in process_once
assert "boolean committed = prefs.edit()" in process_once
assert ".remove(legacyKey).commit()" in process_once
assert "private boolean processMessage(" in LISTENER
assert "saveTransfer(group, name, amount, posted, digest, legacyKey)" in method(LISTENER, "private boolean processMessage(")
assert "saveTransfer(group, name, pendingAmount, pendingTime, digest, legacyKey)" in method(LISTENER, "private boolean processMessage(")
assert "saveTransfer(group, pendingName, amount, pendingTime, digest, legacyKey)" in method(LISTENER, "private boolean processMessage(")
assert 'putString("processed_message_digests_v2", updatedDigests)' in save
assert ".commit()" in save
assert "if (!committed)" in save
assert "throw new IllegalStateException" in save

# Completing a split transfer must clear its pending fragment in the same durable
# SharedPreferences commit as the row and dedup digest. A crash between two
# separate apply() calls could otherwise pair a future message with stale data.
assert '.remove("pending_" + group)' in save
assert save.index('.remove("pending_" + group)') < save.index('.commit()')
assert process_message.count('prefs.edit().remove(pendingKey).apply();') == 1

assert 'String legacyKey = "processed_message_" + Integer.toHexString(signature.hashCode())' in process_once
assert "legacyDuplicate = signature.equals(prefs.getString(legacyKey, null))" in process_once
assert "values.size() > 2048" in LISTENER
assert "private String processedMessageDigest(" in LISTENER
assert "private boolean hasProcessedMessageDigest(" in LISTENER
assert "private String rememberProcessedMessageDigest(" in LISTENER

print("PASS: daily per-group/date XLSX snapshot contract")
print("PASS: exported worksheet is checked against every stored cell")
print("PASS: archived rows are pruned only after exact archive validation")
print("PASS: duplicate transfers remain stored and are highlighted")
print("PASS: Android 10 legacy Downloads compatibility is enforced")
