#!/usr/bin/env python3
"""Fail-closed, stdlib-only manifest and artifact checks. Never imports user code."""
import argparse
import csv
import hashlib
import json
import math
import re
import struct
from pathlib import Path

LIMIT = 20 * 1024 * 1024
HASH = re.compile(r"^[0-9a-fA-F]{64}$")


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(65536), b""):
            digest.update(part)
    return digest.hexdigest()


def read_json(path):
    if not path.is_file() or path.stat().st_size > LIMIT:
        raise ValueError(f"missing/oversized JSON: {path.name}")
    def pairs(items):
        obj = {}
        for key, value in items:
            if key in obj:
                raise ValueError(f"duplicate JSON key: {key}")
            obj[key] = value
        return obj
    def reject(value):
        raise ValueError(f"non-finite JSON constant: {value}")
    with path.open(encoding="utf-8-sig") as stream:
        return json.load(stream, object_pairs_hook=pairs, parse_constant=reject)


def safe_file(root, name):
    if not isinstance(name, str) or not name or "\\" in name or ":" in name:
        raise ValueError("unsafe file path")
    bits = name.split("/")
    if any(bit in ("", ".", "..") for bit in bits):
        raise ValueError("unsafe file path")
    path = root
    for bit in bits:
        path = path / bit
        if path.is_symlink():
            raise ValueError("symlink forbidden")
    if not path.is_file() or path.stat().st_size > LIMIT:
        raise ValueError("file missing/oversized")
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("outside return directory")
    return path


def finite(value):
    if isinstance(value, dict):
        return all(finite(v) for v in value.values())
    if isinstance(value, list):
        return all(finite(v) for v in value)
    if isinstance(value, float):
        return math.isfinite(value)
    if isinstance(value, str) and value.strip().lower() in {"nan", "inf", "-inf", "infinity", "-infinity"}:
        return False
    return True


def check_body(path, item):
    kind = item["kind"]
    if kind in ("json", "csv"):
        fields = item.get("fields")
        if not isinstance(fields, list) or not fields or any(not isinstance(f, dict) or not f.get("name") or not f.get("unit") for f in fields):
            raise ValueError("missing field/unit contract")
        names = [f["name"] for f in fields]
        if len(set(names)) != len(names):
            raise ValueError("duplicate expected fields")
        if kind == "json":
            value = read_json(path)
            rows = value if isinstance(value, list) else [value]
            if any(not isinstance(row, dict) or not set(names).issubset(row) for row in rows):
                raise ValueError("missing JSON fields")
            if not finite(value):
                raise ValueError("non-finite JSON values")
        else:
            with path.open(encoding="utf-8-sig", newline="") as stream:
                reader = csv.DictReader(stream)
                if not reader.fieldnames or not set(names).issubset(reader.fieldnames) or len(reader.fieldnames) != len(set(reader.fieldnames)):
                    raise ValueError("missing/duplicate CSV columns")
                rows = list(reader)
                if any(None in row or not finite(row) for row in rows):
                    raise ValueError("invalid CSV row")
        minimum = item.get("min_rows", 1)
        if type(minimum) is not int or minimum < 1:
            raise ValueError("invalid min_rows contract")
        if len(rows) < minimum:
            raise ValueError("too few rows")
    elif kind == "png":
        with path.open("rb") as stream:
            header = stream.read(24)
        if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR" or not all(struct.unpack(">II", header[16:24])):
            raise ValueError("invalid PNG header")
    elif kind == "jpeg":
        with path.open("rb") as stream:
            header = stream.read(3)
            stream.seek(-2, 2)
            trailer = stream.read(2)
        if header[:2] != b"\xff\xd8" or trailer != b"\xff\xd9":
            raise ValueError("invalid JPEG framing")
    else:
        raise ValueError(f"unsupported kind: {kind}")


def verify(spec, expected, manifest, root):
    checks = []
    def check(code, ok, detail=""):
        checks.append({"item": code, "status": "PASS" if ok else "FAIL", "detail": detail})
    for key in ("case_id", "code_revision", "source_code_sha256", "input_data_sha256"):
        a, b = spec.get(key), manifest.get(key)
        check(key, bool(a and b and a == b) and (not key.endswith("sha256") or bool(HASH.fullmatch(str(a)))))
    for key in ("case_id", "code_revision"):
        check("expected_" + key, bool(expected.get(key) and expected.get(key) == spec.get(key)))
    check("run_exit", manifest.get("exit_code") == 0)
    check("run_timestamp", isinstance(manifest.get("run_timestamp"), str) and bool(manifest["run_timestamp"].strip()))
    try:
        log = safe_file(root, "run_log.txt")
        check("run_log", bool(log.stat().st_size))
    except (ValueError, OSError) as error:
        check("run_log", False, str(error))
    outputs, files = expected.get("outputs"), manifest.get("files")
    if not isinstance(outputs, list) or not outputs or not isinstance(files, list):
        check("output_contract", False, "outputs/files must be arrays")
        return checks
    lookup = {f.get("path"): f for f in files if isinstance(f, dict) and isinstance(f.get("path"), str)}
    check("manifest_duplicates", len(lookup) == len(files))
    seen = set()
    for item in outputs:
        if not isinstance(item, dict):
            check("output_item", False, "expected output must be an object")
            continue
        name = item.get("path")
        if not isinstance(name, str) or name in seen:
            check("output_path", False, "missing/duplicate path")
            continue
        seen.add(name)
        data = lookup.get(name, {})
        try:
            if not item.get("question_id") or item.get("question_id") != data.get("question_id") or item.get("kind") != data.get("kind"):
                raise ValueError("question/kind mismatch")
            if item.get("kind") in ("json", "csv"):
                fields = item.get("fields")
                units = data.get("units")
                if (not isinstance(fields, list) or not isinstance(units, dict)
                        or set(units) != {f.get("name") for f in fields if isinstance(f, dict)}
                        or any(not isinstance(f, dict) or not isinstance(f.get("name"), str)
                               or not isinstance(f.get("unit"), str) or not f["unit"].strip()
                               or units.get(f["name"]) != f["unit"] for f in fields)):
                    raise ValueError("missing/mismatched field units")
            if not HASH.fullmatch(str(data.get("sha256", ""))):
                raise ValueError("invalid declared sha256")
            actual = safe_file(root, name)
            if sha(actual).lower() != data["sha256"].lower():
                raise ValueError("sha256 mismatch")
            check_body(actual, item)
            check("output:" + name, True)
        except (ValueError, OSError, KeyError, TypeError) as error:
            check("output:" + name, False, str(error))
    check("unexpected_files", set(lookup) == seen)
    return checks


def main():
    parser = argparse.ArgumentParser()
    for arg in ("spec", "expected", "return-dir", "out"):
        parser.add_argument("--" + arg, required=True)
    args = parser.parse_args()
    root = Path(args.return_dir).resolve()
    try:
        spec = read_json(Path(args.spec))
        expected = read_json(Path(args.expected))
        manifest = read_json(safe_file(root, "return_manifest.json"))
        if not all(isinstance(v, dict) for v in (spec, expected, manifest)):
            raise ValueError("contracts must be JSON objects")
        checks = verify(spec, expected, manifest, root)
    except (ValueError, OSError, json.JSONDecodeError, UnicodeError, TypeError) as error:
        checks = [{"item": "contract", "status": "FAIL", "detail": str(error)}]
    status = "PASS" if checks and all(c["status"] == "PASS" for c in checks) else "FAIL"
    report = {"status": status, "checks": checks, "note": "File/manifest checks only; does not prove scientific correctness or actual execution."}
    output = Path(args.out)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": status, "report": str(output)}, ensure_ascii=False))
    return 0 if status == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())