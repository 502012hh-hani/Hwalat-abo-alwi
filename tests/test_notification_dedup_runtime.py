#!/usr/bin/env python3
"""Run the production notification-message dedup helpers on the JVM."""
from pathlib import Path
import subprocess
import tempfile

SOURCE = Path("patched-src/WhatsAppNotificationListener.java").read_text(encoding="utf-8")


def extract_method(signature: str) -> str:
    start = SOURCE.find(signature)
    if start < 0:
        raise AssertionError(f"Production method not found: {signature}")
    opening = SOURCE.find("{", start)
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


methods = "\n\n".join(
    extract_method(signature)
    for signature in (
        "private String processedMessageDigest(",
        "private boolean hasProcessedMessageDigest(",
        "private String rememberProcessedMessageDigest(",
    )
)
harness = r'''
import java.util.*;

public class NotificationDedupProbe {
__METHODS__
    public static void main(String[] args) {
        NotificationDedupProbe probe = new NotificationDedupProbe();
        String first = "العمقي|1730000000000|Aa";
        String collision = "العمقي|1730000000000|BB";
        if (first.hashCode() != collision.hashCode()) {
            throw new AssertionError("Test strings must reproduce a Java hashCode collision");
        }
        String firstDigest = probe.processedMessageDigest(first);
        String secondDigest = probe.processedMessageDigest(collision);
        if (firstDigest.equals(secondDigest) || firstDigest.length() != 64 || secondDigest.length() != 64) {
            throw new AssertionError("SHA-256 message identities are not distinct 64-character digests");
        }

        StringBuilder initial = new StringBuilder();
        for (int i = 0; i < 2048; i++) {
            if (i > 0) initial.append("\n");
            initial.append(String.format(Locale.ROOT, "%064x", i));
        }
        String oldest = String.format(Locale.ROOT, "%064x", 0);
        String newest = String.format(Locale.ROOT, "%064x", 2047);
        String cache = initial.toString();
        if (!probe.hasProcessedMessageDigest(cache, newest) ||
                !probe.hasProcessedMessageDigest(cache, oldest)) {
            throw new AssertionError("Cache setup failed");
        }
        cache = probe.rememberProcessedMessageDigest(cache, secondDigest);
        int count = cache.isEmpty() ? 0 : cache.split("\\n").length;
        if (count != 2048 || probe.hasProcessedMessageDigest(cache, oldest) ||
                !probe.hasProcessedMessageDigest(cache, secondDigest)) {
            throw new AssertionError("Dedup cache did not evict the oldest item at its configured bound");
        }
        cache = probe.rememberProcessedMessageDigest(cache, secondDigest);
        if (cache.split("\\n").length != 2048 ||
                !probe.hasProcessedMessageDigest(cache, secondDigest)) {
            throw new AssertionError("Remembering the same digest created a duplicate cache entry");
        }
        System.out.println("PASS: SHA-256 distinguishes two strings with the same Java hashCode");
        System.out.println("PASS: dedup cache remains bounded at 2048 signatures");
        System.out.println("PASS: cache retains recent signatures and does not duplicate an existing digest");
    }
}
'''.replace("__METHODS__", methods)

with tempfile.TemporaryDirectory(prefix="hawalat-dedup-") as tmp:
    root = Path(tmp)
    java_file = root / "NotificationDedupProbe.java"
    java_file.write_text(harness, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", str(java_file)], check=True)
    subprocess.run(["java", "-cp", str(root), "NotificationDedupProbe"], check=True)
