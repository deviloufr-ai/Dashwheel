#!/usr/bin/env python3
"""Checks the app's string translations are complete and consistent.

Every translatable string / plural in res/values/*.xml must exist in each
values-<lang>/ folder with the same format placeholders, and no translation
may exist without its English original. Lint reports the same problems, but
only after a full build; this runs in a second.

Usage: python tools/check_translations.py [res_dir]
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

LOCALES = ["fr", "de", "es", "it", "pt", "nl", "pl", "ru"]
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([sdfxXc])")


def load(folder: Path):
    """name -> ("string", text) or ("plurals", {quantity: text}), plus untranslatable names."""
    entries, fixed = {}, set()
    for f in sorted(folder.glob("*.xml")):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            print(f"XML error in {f}: {e}")
            sys.exit(1)
        for el in root:
            name = el.get("name")
            if el.tag == "string":
                if el.get("translatable") == "false":
                    fixed.add(name)
                    continue
                entries[name] = ("string", "".join(el.itertext()), f.name)
            elif el.tag == "plurals":
                items = {i.get("quantity"): "".join(i.itertext()) for i in el.findall("item")}
                entries[name] = ("plurals", items, f.name)
    return entries, fixed


def placeholders(text: str):
    text = text.replace("%%", "")
    found = PLACEHOLDER.findall(text)
    return sorted((idx or str(i + 1), conv.replace("X", "x")) for i, (idx, conv) in enumerate(found))


def raw_problems(folder: Path):
    """Unescaped apostrophes break aapt; check the raw text, not the parsed one."""
    out = []
    for f in sorted(folder.glob("strings*.xml")):
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            m = re.search(r'<(?:string|item)[^>]*>(.*)</(?:string|item)>', line)
            if not m:
                continue
            body = m.group(1)
            if re.search(r"(?<!\\)'", body) and not (body.startswith('"') and body.endswith('"')):
                out.append(f"{f.name}:{n}: unescaped apostrophe: {body}")
            if re.match(r"[@?]", body):
                out.append(f"{f.name}:{n}: leading @/? must be escaped: {body}")
    return out


def main():
    res = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res")
    base, fixed = load(res / "values")
    errors = raw_problems(res / "values")
    for loc in LOCALES:
        folder = res / f"values-{loc}"
        if not folder.is_dir():
            errors.append(f"values-{loc}: missing folder")
            continue
        errors += raw_problems(folder)
        tr, _ = load(folder)
        for name, (kind, value, src) in base.items():
            if name not in tr:
                errors.append(f"values-{loc}: missing '{name}' ({src})")
                continue
            tkind, tvalue, _ = tr[name]
            if tkind != kind:
                errors.append(f"values-{loc}: '{name}' is a {tkind}, English is a {kind}")
                continue
            if kind == "string":
                if placeholders(value) != placeholders(tvalue):
                    errors.append(f"values-{loc}: '{name}' placeholders differ: {value!r} vs {tvalue!r}")
            else:
                if "other" not in tvalue:
                    errors.append(f"values-{loc}: plural '{name}' has no 'other'")
                want = placeholders(value.get("other", ""))
                for q, t in tvalue.items():
                    if q != "one" and placeholders(t) != want:
                        errors.append(f"values-{loc}: plural '{name}' [{q}] placeholders differ: {t!r}")
        for name in tr:
            if name not in base:
                where = "untranslatable" if name in fixed else "not in values/"
                errors.append(f"values-{loc}: extra '{name}' ({where})")
    for e in errors:
        print(e)
    print(f"{len(base)} strings x {len(LOCALES)} languages: {'OK' if not errors else f'{len(errors)} problem(s)'}")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
