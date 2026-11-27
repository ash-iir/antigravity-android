"""
resolv_redirect.py - Relative resolver path patch: /etc/resolv.conf -> etc//resolv.conf (length 16 in-place),
and resilient public DNS fallback patch: defaultNS [127.0.0.1:53, [::1]:53] -> [8.8.8.8:53].
"""
import struct
from typing import Tuple, Dict, Any, List, Optional, Union

TARGET = b"/etc/resolv.conf"
LEGACY_TARGET = b"/sdcard/rslv.cnf"
REPLACEMENT = b"etc//resolv.conf"
ADJACENT_REJECT = b"/.\\"
EXPECTED_COUNT = 1

DNS_FALLBACK_TARGET = b"127.0.0.1:53"
DNS_FALLBACK_PATCHED_PREFIX = b"8.8.8.8:53"
DNS_FALLBACK_REPL = b"8.8.8.8:53\x00\x00"

# Known (str_off, array_len_off, slice_len_off) tuples for fast O(1) lookup across core versions.
KNOWN_DNS_FALLBACK_OFFSETS = [
    # v2.35.2
    (0x4b2b703, 0xa45c3a8, 0xa45ba28),
    # v2.34.0
    (0x4c75955, 0xa39b808, 0xa39af18),
]


def _resolve_dns_offsets_from_elf(data: Union[bytes, bytearray, memoryview]) -> Optional[Tuple[int, int, int]]:
    """Dynamically locate net.defaultNS string/array/slice offsets via ELF PT_LOAD + .rela.dyn."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    try:
        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_shoff = struct.unpack_from("<Q", data, 40)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 58)
        if e_shoff == 0 or e_shstrndx >= e_shnum:
            return None

        segs = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from("<IIQQQQQQ", data, off)
            if p_type == 1:
                segs.append((p_offset, p_vaddr, p_filesz))

        def off_to_vaddr(file_off: int) -> Optional[int]:
            for p_off, p_va, p_fsz in segs:
                if p_off <= file_off < p_off + p_fsz:
                    return p_va + (file_off - p_off)
            return None

        def vaddr_to_off(va: int) -> Optional[int]:
            for p_off, p_va, p_fsz in segs:
                if p_va <= va < p_va + p_fsz:
                    return p_off + (va - p_va)
            return None

        shstr_off, shstr_size = struct.unpack_from("<QQ", data, e_shoff + e_shstrndx * e_shentsize + 24)
        shstr = bytes(data[shstr_off:shstr_off + shstr_size])

        rela_off = rela_size = 0
        for i in range(e_shnum):
            off = e_shoff + i * e_shentsize
            sh_name, _, _, _, sh_offset, sh_size = struct.unpack_from("<IIQQQQ", data, off)
            name = shstr[sh_name:].split(b"\x00", 1)[0]
            if name == b".rela.dyn":
                rela_off, rela_size = sh_offset, sh_size
                break
        if rela_off == 0 or rela_size == 0:
            return None

        str_vaddrs = {}
        pos = 0
        while True:
            pos = data.find(DNS_FALLBACK_TARGET, pos)
            if pos < 0:
                break
            va = off_to_vaddr(pos)
            if va is not None:
                str_vaddrs[va] = pos
            pos += len(DNS_FALLBACK_TARGET)
        pos = 0
        while True:
            pos = data.find(DNS_FALLBACK_PATCHED_PREFIX, pos)
            if pos < 0:
                break
            va = off_to_vaddr(pos)
            if va is not None:
                str_vaddrs[va] = pos
            pos += len(DNS_FALLBACK_PATCHED_PREFIX)

        if not str_vaddrs:
            return None

        arr_candidates = {}
        for r in range(rela_off, rela_off + rela_size, 24):
            r_offset, _, r_addend = struct.unpack_from("<QQq", data, r)
            if r_addend in str_vaddrs:
                arr_off = vaddr_to_off(r_offset)
                if arr_off is not None and arr_off + 32 <= len(data):
                    len1 = struct.unpack_from("<Q", data, arr_off + 8)[0]
                    len2 = struct.unpack_from("<Q", data, arr_off + 24)[0]
                    if len1 in (10, 12) and len2 == 8:
                        arr_candidates[r_offset] = (str_vaddrs[r_addend], arr_off + 8)

        if not arr_candidates:
            return None

        for r in range(rela_off, rela_off + rela_size, 24):
            r_offset, _, r_addend = struct.unpack_from("<QQq", data, r)
            if r_addend in arr_candidates:
                slice_off = vaddr_to_off(r_offset)
                if slice_off is not None and slice_off + 24 <= len(data):
                    s_len, s_cap = struct.unpack_from("<QQ", data, slice_off + 8)
                    if s_len in (1, 2) and s_cap == 2:
                        str_off, arr_len_off = arr_candidates[r_addend]
                        return (str_off, arr_len_off, slice_off + 8)
    except Exception:
        return None
    return None


def _find_dns_fallback_offsets(data: Union[bytes, bytearray, memoryview]) -> Optional[Tuple[int, int, int]]:
    for str_off, arr_len_off, slice_len_off in KNOWN_DNS_FALLBACK_OFFSETS:
        if len(data) <= max(str_off + 12, arr_len_off + 8, slice_len_off + 8):
            continue
        slice_cap = struct.unpack_from("<Q", data, slice_len_off + 8)[0]
        if slice_cap != 2:
            continue
        head = bytes(data[str_off:str_off + len(DNS_FALLBACK_TARGET)])
        if head == DNS_FALLBACK_TARGET or head[:10] == DNS_FALLBACK_PATCHED_PREFIX:
            return (str_off, arr_len_off, slice_len_off)
    return _resolve_dns_offsets_from_elf(data)


def scan(data: Union[bytes, bytearray, memoryview]) -> Tuple[List[int], List[int], List[int]]:
    clean = []
    rejected = []
    for tgt in (TARGET, LEGACY_TARGET):
        pos = 0
        while True:
            pos = data.find(tgt, pos)
            if pos < 0:
                break
            prev_b = data[pos - 1] if pos > 0 else None
            next_b = data[pos + len(tgt)] if pos + len(tgt) < len(data) else None
            adjacent = (prev_b is not None and bytes([prev_b]) in ADJACENT_REJECT) or (
                next_b is not None and bytes([next_b]) in ADJACENT_REJECT)
            (rejected if adjacent else clean).append(pos)
            pos += len(tgt)

    repl = []
    pos = 0
    while True:
        pos = data.find(REPLACEMENT, pos)
        if pos < 0:
            break
        repl.append(pos)
        pos += len(REPLACEMENT)
    return clean, rejected, repl


def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    clean, rejected, repl = scan(data)
    total = len(clean) + len(repl)

    dns_offsets = _find_dns_fallback_offsets(data)
    dns_fallback_clean = False
    dns_fallback_patched = False
    if dns_offsets is not None:
        str_off, arr_len_off, slice_len_off = dns_offsets
        dns_fallback_clean = (
            bytes(data[str_off:str_off + len(DNS_FALLBACK_TARGET)]) == DNS_FALLBACK_TARGET
        )
        dns_fallback_patched = (
            bytes(data[str_off:str_off + 10]) == DNS_FALLBACK_PATCHED_PREFIX
        )

    info = {
        "name": "resolv_redirect",
        "expected": EXPECTED_COUNT,
        "clean_count": len(clean),
        "rejected_count": len(rejected),
        "replacement_count": len(repl),
        "clean_offsets": [hex(x) for x in clean],
        "replacement_offsets": [hex(x) for x in repl],
        "dns_fallback_clean": dns_fallback_clean,
        "dns_fallback_patched": dns_fallback_patched
    }

    if total < 1:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        path_ok = (len(clean) == 0 and len(repl) >= 1)
        dns_ok = dns_fallback_patched or (allow_missing and not dns_fallback_clean)
        ok = path_ok and dns_ok
        info["status"] = "verified" if ok else "unverified"
        return ok, info

    if len(clean) == 0 and dns_fallback_patched:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for pos in clean:
            data[pos:pos + len(REPLACEMENT)] = REPLACEMENT
        if dns_fallback_clean and dns_offsets is not None:
            str_off, arr_len_off, slice_len_off = dns_offsets
            data[str_off:str_off + len(DNS_FALLBACK_REPL)] = DNS_FALLBACK_REPL
            struct.pack_into("<Q", data, arr_len_off, 10)
            struct.pack_into("<Q", data, slice_len_off, 1)
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
