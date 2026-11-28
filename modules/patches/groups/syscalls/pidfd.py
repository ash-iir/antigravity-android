"""
pidfd.py - Disables Android-seccomp-trapped internal/syscall/unix.PidFDOpen (syscall 434)
by returning (-1, syscall.ENOSYS) immediately without issuing the syscall.
Prevents SIGSYS crashes on older Android kernels and seccomp filters.
"""
import sys
import os
import re
from typing import Tuple, Dict, Any, List

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PATCHES_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
sys.path.insert(0, os.path.join(PATCHES_ROOT, "engine"))

import byte_replacer

# mov x1, x0; mov x0, #434; bl syscall.Syscall; cbz x2, ...
TARGETS = [
    # v2.35.2
    bytes.fromhex("e10300aa403680d21ac7fe97420100b4"),
    # v2.34.0
    bytes.fromhex("e10300aa403680d2fac6fe97420100b4"),
    # v2.33.0
    bytes.fromhex("e10300aa403680d2e2c6fe97420100b4"),
]
TARGET = TARGETS[0]
# Masked fallback matching any bl imm26 (bytes 8..11) and cbz x2, imm19 (bytes 12..15)
SIG_DYNAMIC = re.compile(
    rb"\xe1\x03\x00\xaa\x40\x36\x80\xd2.{3}[\x94-\x97][\x02\x22\x42\x62\x82\xa2\xc2\xe2].{2}\xb4",
    re.S,
)
# mov x1, x0; mov x2, #38 (ENOSYS); b #8; nop
REPLACEMENT = bytes.fromhex("e10300aac20480d2020000141f2003d5")
EXPECTED_COUNT = 1

def _find_dynamic_raw(data: bytearray, known: List[int]) -> List[int]:
    seen = set(known)
    found = []
    for m in SIG_DYNAMIC.finditer(data):
        off = m.start()
        if off % 4 == 0 and off not in seen:
            seen.add(off)
            found.append(off)
    return found

def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    raw_offsets = []
    for tgt in TARGETS:
        raw_offsets.extend(byte_replacer.find_offsets(data, tgt))
    if not raw_offsets:
        raw_offsets.extend(_find_dynamic_raw(data, raw_offsets))
    pat_offsets = byte_replacer.find_offsets(data, REPLACEMENT)
    
    total = len(raw_offsets) + len(pat_offsets)
    info = {
        "name": "pidfd",
        "expected": EXPECTED_COUNT,
        "raw_count": len(raw_offsets),
        "patched_count": len(pat_offsets),
        "raw_offsets": [hex(x) for x in raw_offsets],
        "patched_offsets": [hex(x) for x in pat_offsets]
    }

    if total < 1:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        ok = (len(raw_offsets) == 0 and len(pat_offsets) == EXPECTED_COUNT)
        info["status"] = "verified" if ok else "unverified"
        return ok, info

    if len(raw_offsets) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for off in raw_offsets:
            data[off:off + len(REPLACEMENT)] = REPLACEMENT
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
