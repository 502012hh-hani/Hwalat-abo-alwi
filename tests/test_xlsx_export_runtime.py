#!/usr/bin/env python3
"""Execute the exact pure-Java XLSX writer/validator methods from MainActivity.

This verifies a real workbook ZIP/XML round-trip on the JVM. It does not test
Android's public Downloads permissions or the Redmi/MIUI filesystem provider.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

SOURCE = Path("patched-src/MainActivity.java").read_text(encoding="utf-8")


def extract_method(signature: str) -> str:
    start = SOURCE.find(signature)
    if start < 0:
        raise AssertionError(f"Production method not found: {signature}")
    opening = SOURCE.find("{", start)
    if opening < 0:
        raise AssertionError(f"Method body not found: {signature}")
    depth = 0
    in_string = in_char = line_comment = block_comment = escaped = False
    i = opening
    while i < len(SOURCE):
        ch = SOURCE[i]
        nxt = SOURCE[i + 1] if i + 1 < len(SOURCE) else ""
        if line_comment:
            if ch == "\n":
                line_comment = False
        elif block_comment:
            if ch == "*" and nxt == "/":
                block_comment = False
                i += 1
        elif in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        elif in_char:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == "'":
                in_char = False
        elif ch == "/" and nxt == "/":
            line_comment = True
            i += 1
        elif ch == "/" and nxt == "*":
            block_comment = True
            i += 1
        elif ch == '"':
            in_string = True
        elif ch == "'":
            in_char = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return SOURCE[start:i + 1]
        i += 1
    raise AssertionError(f"Unbalanced method: {signature}")


signatures = [
    "private byte[] buildXlsxBytes(",
    "private void validateXlsx(",
    "private String[] readXlsxRow(",
    "private String normalizeDuplicateName(",
    "private String normalizeDuplicateAmount(",
    "private void appendXlsxRow(",
    "private String xmlEscape(",
    "private void restoreWorkbookBackup(File target, File backup)",
    "private File retainPreviousWorkbookBackup(File backup, String fileName)",
    "private boolean shouldRestoreWorkbookBackup(boolean hadTarget, boolean movedTargetToBackup,",
    "private void putZipText(",
]
methods = "\n\n".join(extract_method(x) for x in signatures)

harness = r'''
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class XlsxRuntimeProbe {
__METHODS__
    public static void main(String[] args) throws Exception {
        ArrayList<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"علي & <خالد>", "500", "2026-11-15", "12:02:00"});
        rows.add(new String[]{"علي & <خالد>", "٥٠٠", "2026-11-15", "12:01:00"});
        rows.add(new String[]{"سالم", "200", "2026-11-15", "11:59:00"});
        XlsxRuntimeProbe probe = new XlsxRuntimeProbe();
        byte[] bytes = probe.buildXlsxBytes(rows);
        File output = new File(args[0]);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(bytes);
            stream.flush();
            stream.getFD().sync();
        }
        probe.validateXlsx(output, rows);
        System.out.println("PASS: production Java XLSX writer and validator round-trip " + rows.size() + " rows");

        // Reproduce a crash after target -> .bak but before .tmp -> target.
        File recoveryDir = new File(args[1]);
        if (!recoveryDir.mkdirs() && !recoveryDir.isDirectory()) {
            throw new IOException("Could not create recovery test directory");
        }
        File missingTarget = new File(recoveryDir, "العمقي-2026-11-19.xlsx");
        File interruptedBackup = new File(recoveryDir, "العمقي-2026-11-19.xlsx.bak");
        try (FileOutputStream backupOut = new FileOutputStream(interruptedBackup)) {
            backupOut.write("last-known-good-workbook".getBytes(StandardCharsets.UTF_8));
            backupOut.getFD().sync();
        }
        probe.restoreWorkbookBackup(missingTarget, interruptedBackup);
        String restored = new String(java.nio.file.Files.readAllBytes(missingTarget.toPath()),
                StandardCharsets.UTF_8);
        if (!"last-known-good-workbook".equals(restored) || interruptedBackup.exists()) {
            throw new AssertionError("Interrupted export backup was not restored");
        }
        System.out.println("PASS: missing target + surviving .bak restores the last known workbook");

        // A stale backup may remain after a successful replacement if cleanup was interrupted.
        // If the current attempt fails before moving its existing target, preserve that target.
        if (probe.shouldRestoreWorkbookBackup(true, false, true)) {
            throw new AssertionError("A stale backup must not replace an untouched existing target");
        }
        if (!probe.shouldRestoreWorkbookBackup(true, true, true)) {
            throw new AssertionError("A backup created by this failed replacement must be restored");
        }
        if (!probe.shouldRestoreWorkbookBackup(false, false, true)) {
            throw new AssertionError("A sole surviving backup must be restored when target was absent");
        }
        if (probe.shouldRestoreWorkbookBackup(true, true, false)) {
            throw new AssertionError("Cannot restore a backup that does not exist");
        }
        System.out.println("PASS: stale backup cannot overwrite a still-valid target after a failed retry");

        // When both target and a leftover .bak exist, preserve the .bak instead of
        // deleting it before the current target is moved and the new workbook is verified.
        File retainedDir = new File(args[2]);
        if (!retainedDir.mkdirs() && !retainedDir.isDirectory()) {
            throw new IOException("Could not create retained-backup test directory");
        }
        File currentTarget = new File(retainedDir, "العمقي-2026-11-20.xlsx");
        File staleBackup = new File(retainedDir, "العمقي-2026-11-20.xlsx.bak");
        try (FileOutputStream currentOut = new FileOutputStream(currentTarget)) {
            currentOut.write("newer-current-target".getBytes(StandardCharsets.UTF_8));
        }
        try (FileOutputStream staleOut = new FileOutputStream(staleBackup)) {
            staleOut.write("older-last-known-good-backup".getBytes(StandardCharsets.UTF_8));
        }
        File retainedBackup = probe.retainPreviousWorkbookBackup(
                staleBackup, currentTarget.getName());
        String currentBytes = new String(java.nio.file.Files.readAllBytes(currentTarget.toPath()),
                StandardCharsets.UTF_8);
        String retainedBytes = new String(java.nio.file.Files.readAllBytes(retainedBackup.toPath()),
                StandardCharsets.UTF_8);
        if (staleBackup.exists() || !currentTarget.isFile()
                || !"newer-current-target".equals(currentBytes)
                || !"older-last-known-good-backup".equals(retainedBytes)) {
            throw new AssertionError("Existing target or previous backup was not preserved");
        }
        System.out.println("PASS: existing target and stale .bak are both preserved before replacement");
    }
}
'''.replace("__METHODS__", methods)

with tempfile.TemporaryDirectory(prefix="hawalat-xlsx-") as tmp:
    root = Path(tmp)
    java_file = root / "XlsxRuntimeProbe.java"
    output = root / "probe.xlsx"
    java_file.write_text(harness, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", str(java_file)], check=True)
    subprocess.run(["java", "-cp", str(root), "XlsxRuntimeProbe", str(output), str(root / "recovery"), str(root / "retained")], check=True)

    with zipfile.ZipFile(output) as workbook:
        assert workbook.testzip() is None, "XLSX ZIP CRC check failed"
        required = {
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/worksheets/sheet1.xml",
            "xl/styles.xml",
        }
        assert required.issubset(set(workbook.namelist())), "Required OOXML part missing"
        ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
        sheet = ET.fromstring(workbook.read("xl/worksheets/sheet1.xml"))
        rows_xml = sheet.findall(".//m:sheetData/m:row", ns)
        assert len(rows_xml) == 4, f"expected header + 3 data rows, got {len(rows_xml)}"

        def cell_value(cell):
            if cell.get("t") == "inlineStr":
                return "".join(node.text or "" for node in cell.findall(".//m:t", ns))
            value = cell.find("m:v", ns)
            return "" if value is None else (value.text or "")

        parsed = []
        for row in rows_xml:
            by_col = {}
            for cell in row.findall("m:c", ns):
                by_col[cell.get("r")[0]] = cell_value(cell)
            parsed.append([by_col.get(c, "") for c in "ABCD"])

        expected = [
            ["اسم المستلم", "المبلغ", "التاريخ", "الوقت"],
            ["علي & <خالد>", "500", "2026-11-15", "12:02:00"],
            ["علي & <خالد>", "٥٠٠", "2026-11-15", "12:01:00"],
            ["سالم", "200", "2026-11-15", "11:59:00"],
        ]
        assert parsed == expected, f"round-trip mismatch: {parsed!r}"

        # The later repeated transfer is the first row in the newest-first sheet.
        duplicate_row = rows_xml[1]
        assert all(cell.get("s") == "1" for cell in duplicate_row.findall("m:c", ns)), (
            "Repeated name+amount row was not styled yellow"
        )
        normal_row = rows_xml[2]
        assert all(cell.get("s") != "1" for cell in normal_row.findall("m:c", ns)), (
            "First occurrence should remain unhighlighted"
        )
        styles = ET.fromstring(workbook.read("xl/styles.xml"))
        assert styles.find(".//m:fgColor[@rgb='FFFFFF00']", ns) is not None, (
            "Yellow duplicate fill is missing from styles.xml"
        )

print("PASS: interrupted replacement recovery preserves the previous workbook")
print("PASS: generated XLSX ZIP and OOXML parts are valid")
print("PASS: Arabic text, XML escaping, numeric/text amounts, and all four columns round-trip")
print("PASS: later duplicate row is yellow; first occurrence remains normal")
