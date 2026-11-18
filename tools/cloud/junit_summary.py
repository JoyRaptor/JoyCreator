#!/usr/bin/env python3
"""Cloud tests only (tools/cloud/run-tests.sh): reads one suite's JUnit XML results and

  * prints "tests failures errors skipped" on stdout (run-tests.sh reads it),
  * appends one line per failing test to LIST  ("- `suite` Class.method: first line of the message"),
  * appends each failing test's class, method, message and the first lines of its stack trace to DETAILS,
  * copies the XML files that hold a failure into JUNIT_OUT/<suite>/ (output trimmed), for the small artifact.

usage: junit_summary.py SUITE LIST DETAILS JUNIT_OUT RESULTS_DIR...
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_SHOWN = 40         # failing tests written out in full; the rest are only listed
MAX_FRAMES = 12        # stack frames kept per failing test (framework frames skipped)
MAX_OUTPUT = 4000      # characters of a failing class's captured stdout/stderr kept in the copied XML
FRAMEWORK = (
    "org.junit.", "junit.framework.", "org.robolectric.", "org.gradle.", "worker.org.gradle.",
    "java.base/jdk.internal.reflect.", "java.base/java.lang.reflect.", "jdk.internal.reflect.",
    "java.lang.reflect.", "sun.reflect.", "jdk.proxy", "com.sun.proxy.", "kotlin.coroutines.",
    "kotlinx.coroutines.", "java.base/java.util.concurrent.", "java.base/java.lang.Thread.",
)


def short_trace(message, text):
    """The message once, then exception and 'Caused by' lines plus the first frames outside the test framework."""
    lines = [l.rstrip() for l in (text or "").splitlines() if l.strip()]
    msg = (message or "").strip()
    out = []
    if msg and not (lines and msg.splitlines()[0] in lines[0]):
        out.extend(msg.splitlines()[:5])
    frames = skipped = 0
    for l in lines:
        s = l.strip()
        if s.startswith("at "):
            if s[3:].startswith(FRAMEWORK):
                skipped += 1
                continue
            if frames >= MAX_FRAMES:
                skipped += 1
                continue
            frames += 1
            out.append("    " + s)
        elif s.startswith("..."):
            continue
        else:
            out.append(s if len(s) <= 400 else s[:400] + " ...")
    if skipped:
        out.append("    (%d framework or later frames cut)" % skipped)
    return out[:40]


def first_line(message, text):
    for l in ((message or "") + "\n" + (text or "")).splitlines():
        if l.strip():
            l = l.strip()
            return l if len(l) <= 180 else l[:180] + " ..."
    return "(no message)"


def trim_output(root):
    for tag in ("system-out", "system-err"):
        for el in root.iter(tag):
            if el.text and len(el.text) > MAX_OUTPUT:
                el.text = "(%d characters cut)\n" % (len(el.text) - MAX_OUTPUT) + el.text[-MAX_OUTPUT:]


def main():
    suite, list_file, details_file, junit_out = sys.argv[1:5]
    dirs = sys.argv[5:]
    t = f = e = s = 0
    listed, shown = [], []
    for d in dirs:
        for p in sorted(glob.glob(os.path.join(d, "**", "TEST-*.xml"), recursive=True)):
            try:
                tree = ET.parse(p)
            except ET.ParseError:
                listed.append("- `%s` %s: results file could not be read" % (suite, os.path.basename(p)))
                continue
            root = tree.getroot()
            bad_here = False
            for case in root.iter("testcase"):
                t += 1
                if case.find("skipped") is not None:
                    s += 1
                for kind in ("failure", "error"):
                    bad = case.find(kind)
                    if bad is None:
                        continue
                    bad_here = True
                    if kind == "failure":
                        f += 1
                    else:
                        e += 1
                    name = "%s.%s" % (case.get("classname"), case.get("name"))
                    listed.append("- `%s` %s: %s" % (suite, name, first_line(bad.get("message"), bad.text)))
                    if len(shown) < MAX_SHOWN:
                        body = short_trace(bad.get("message"), bad.text)
                        shown.append("#### %s / %s\n```\n%s\n```\n" % (suite, name, "\n".join(body)))
            if bad_here:
                os.makedirs(os.path.join(junit_out, suite), exist_ok=True)
                trim_output(root)
                tree.write(os.path.join(junit_out, suite, os.path.basename(p)), encoding="utf-8", xml_declaration=True)
    with open(list_file, "a", encoding="utf-8") as fh:
        for l in listed:
            fh.write(l + "\n")
    with open(details_file, "a", encoding="utf-8") as fh:
        for chunk in shown:
            fh.write(chunk + "\n")
        if f + e > len(shown):
            fh.write("(%d more failing tests in `%s` are only listed above)\n\n" % (f + e - len(shown), suite))
    print(t, f, e, s)


if __name__ == "__main__":
    main()
