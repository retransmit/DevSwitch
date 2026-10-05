#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Lennox
"""Checks every translated strings.xml against the English one.

Fails on a missing or unknown string name, on format placeholders that differ from the English
text, on two folders holding the same translation, and on dash characters the project avoids.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

RES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]")
DASHES = re.compile("[" + chr(0x2013) + chr(0x2014) + "]")


def load(path):
    return {e.get("name"): "".join(e.itertext()) for e in ET.parse(path).getroot() if e.tag == "string"}


def main():
    english = load(RES / "values/strings.xml")
    problems = []
    seen = {}
    folders = sorted(p.parent for p in RES.glob("values-*/strings.xml"))
    for folder in folders:
        name = folder.name
        path = folder / "strings.xml"
        strings = load(path)
        for key in sorted(set(english) - set(strings)):
            problems.append(f"{name}: missing {key}")
        for key in sorted(set(strings) - set(english)):
            problems.append(f"{name}: unknown string {key}")
        for key, text in strings.items():
            if key in english and sorted(PLACEHOLDER.findall(text)) != sorted(PLACEHOLDER.findall(english[key])):
                problems.append(f"{name}: placeholders differ in {key}")
        raw = path.read_text(encoding="utf-8")
        if DASHES.search(raw):
            problems.append(f"{name}: contains an em or en dash")
        if raw in seen:
            problems.append(f"{name}: identical to {seen[raw]}, keep only one")
        seen[raw] = name
    for line in problems:
        print("FAIL", line)
    print(f"{len(folders)} translation(s) checked against {len(english)} strings, {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
