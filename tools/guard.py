#!/usr/bin/env python3
"""Repository guard: keeps game files, private material and AI references out of OpenKnights.

    python tools/guard.py --staged            check the files staged for commit (pre-commit hook)
    python tools/guard.py --all               check every tracked file (CI)
    python tools/guard.py --commit-msg FILE   check one commit message (commit-msg hook)
    python tools/guard.py --commits RANGE     check the commit messages of a range such as base..head (CI)

A maintainer may also keep a local, never-committed marker file (one literal per line, '#' starts a comment). Its path
comes from `git config openknights.guardMarkers` or the OPENKNIGHTS_GUARD_MARKERS environment variable. Markers made of
letters, digits and '_' match whole words; any other marker matches anywhere. Matching ignores case.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

MAX_BYTES = 5 * 1024 * 1024

# Game packages, native code, captures, saves, backups and signing keys.
BLOCKED_SUFFIXES = {
    ".apk", ".xapk", ".apkm", ".apks", ".aab", ".so", ".dex", ".odex", ".vdex", ".oat",
    ".pcap", ".pcapng", ".bin", ".sqlite", ".sqlite3", ".db", ".okbackup",
    ".keystore", ".jks", ".p12", ".pfx", ".pem", ".key",
    ".plist", ".spcc", ".flcc", ".scene", ".mp3", ".ogg",
}
# Game tables are allowed only where we keep our own text rows.
CSV_DIRS = ("patches/text/",)
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
IMAGE_DIRS = (".github/assets/", "patches/branding/", "site/")
BLOCKED_DIRS = ("private/", "captures/", "original/", "patched/")

DRIVE_PATH = re.compile(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/][A-Za-z0-9_.-]")
TOKEN = re.compile(r"(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])")
AI_REFERENCE = re.compile(r"\b(claude|anthropic|chatgpt|openai|codex|copilot|co-authored-by)\b", re.IGNORECASE)
# Emoji blocks: pictographs, symbols and dingbats, arrows-and-stars supplement, variation selector 16.
EMOJI = re.compile("[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F]")
# The guard names the words it refuses, so it is exempt from that one check.
AI_CHECK_EXEMPT = {"tools/guard.py"}


def git(*args: str, binary: bool = False):
    result = subprocess.run(["git", *args], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8")


def load_markers() -> list[re.Pattern]:
    path = os.environ.get("OPENKNIGHTS_GUARD_MARKERS")
    if not path:
        try:
            path = git("config", "--get", "openknights.guardMarkers").strip()
        except subprocess.CalledProcessError:
            path = ""
    if not path:
        return []
    patterns = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        marker = line.strip()
        if not marker or marker.startswith("#"):
            continue
        body = re.escape(marker)
        if re.fullmatch(r"\w+", marker):
            body = rf"\b{body}\b"
        patterns.append(re.compile(body, re.IGNORECASE))
    return patterns


def looks_like_token(text: str) -> bool:
    """Long base64url-looking strings with mixed case and digits. Plain hex (hashes) is allowed."""
    if re.fullmatch(r"[0-9a-fA-F]+", text):
        return False
    return any(c.islower() for c in text) and any(c.isupper() for c in text) and any(c.isdigit() for c in text)


def check_text(label: str, text: str, markers: list[re.Pattern], *, ai_check: bool, emoji_check: bool) -> list[str]:
    problems = []
    for number, line in enumerate(text.splitlines(), 1):
        where = f"{label}:{number}"
        if DRIVE_PATH.search(line):
            problems.append(f"{where}: local drive path")
        for match in TOKEN.finditer(line):
            if looks_like_token(match.group()):
                problems.append(f"{where}: token-like string")
        if ai_check and AI_REFERENCE.search(line):
            problems.append(f"{where}: AI reference")
        if emoji_check and EMOJI.search(line):
            problems.append(f"{where}: emoji (use a game icon instead)")
        if any(pattern.search(line) for pattern in markers):
            problems.append(f"{where}: private marker")
    return problems


def check_file(path: str, data: bytes, markers: list[re.Pattern]) -> list[str]:
    posix = PurePosixPath(path)
    suffix = posix.suffix.lower()
    problems = []
    if any(f"/{d}" in f"/{path}" for d in BLOCKED_DIRS):
        problems.append(f"{path}: private or game-file folder")
    if suffix in BLOCKED_SUFFIXES:
        problems.append(f"{path}: {suffix} files are never committed")
    if suffix == ".csv" and not path.startswith(CSV_DIRS):
        problems.append(f"{path}: game tables are never committed (own text rows go in patches/text/)")
    if suffix in IMAGE_SUFFIXES and not path.startswith(IMAGE_DIRS):
        problems.append(f"{path}: images belong in {', '.join(IMAGE_DIRS)}")
    if len(data) > MAX_BYTES:
        problems.append(f"{path}: {len(data):,} bytes is over the {MAX_BYTES:,}-byte limit")
    if b"\0" in data[:8192]:
        return problems
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return problems + [f"{path}: not UTF-8 text"]
    problems += check_text(path, text, markers, ai_check=path not in AI_CHECK_EXEMPT, emoji_check=suffix == ".md")
    return problems


def staged_files() -> list[tuple[str, bytes]]:
    names = [n for n in git("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z").split("\0") if n]
    return [(name, git("show", f":{name}", binary=True)) for name in names]


def tracked_files() -> list[tuple[str, bytes]]:
    names = [n for n in git("ls-files", "-z").split("\0") if n]
    return [(name, Path(name).read_bytes()) for name in names]


def check_message(label: str, message: str, markers: list[re.Pattern]) -> list[str]:
    body = "\n".join(line for line in message.splitlines() if not line.startswith("#"))
    return check_text(label, body, markers, ai_check=True, emoji_check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--staged", action="store_true")
    mode.add_argument("--all", action="store_true")
    mode.add_argument("--commit-msg", metavar="FILE")
    mode.add_argument("--commits", metavar="RANGE")
    args = parser.parse_args()

    markers = load_markers()
    problems: list[str] = []
    if args.staged or args.all:
        for name, data in staged_files() if args.staged else tracked_files():
            problems += check_file(name, data, markers)
    elif args.commit_msg:
        problems += check_message("commit message", Path(args.commit_msg).read_text(encoding="utf-8"), markers)
    else:
        log = git("log", "--format=%H%x00%B%x00", args.commits)
        fields = log.split("\0")
        for sha, message in zip(fields[0::2], fields[1::2]):
            problems += check_message(f"commit {sha.strip()[:12]}", message, markers)

    if problems:
        print("guard: refused. Fix these before committing or pushing:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
