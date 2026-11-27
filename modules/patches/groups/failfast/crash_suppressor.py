"""
crash_suppressor.py - Bypasses preinit CPU feature check and preinit atomic flag initialization for ARMv8.0-A CPUs.
"""
from typing import Tuple, Dict, Any, List, Union

CPU_SIGNATURES = [
    # v2.36.0
    (bytes.fromhex("b31301b00a609052680245f9e903282a3f010aeae1020054e8033836e8045036"),
     bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")),
    # v2.35.2
    (bytes.fromhex("f30d01d00a609052683e40f9e903282a3f010aeae1020054e8033836e8045036"),
     bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")),
    # v2.34.0
    (bytes.fromhex("b30701d00a60905268be40f9e903282a3f010aeae1020054e8033836e8045036"),
     bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")),
    # v2.33.0
    (bytes.fromhex("b30401900a609052681e44f9e903282a3f010aeae1020054e8033836e8045036"),
     bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")),
    # v2.4.0 / v2.4.1
    (bytes.fromhex("d3f800900a60905268de41f9e903282a3f010aeae1020054e8033836e8045036"),
     bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")),
    # legacy
    (bytes.fromhex("13f100b00a609052689e42f9e903282a3f010aeae1020054e8033836e8045036"),
     bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")),
]

ATOMIC_SIGNATURES = [
    # v2.36.0 (ELF1 google_find_phdr / setup_dl_debug_hook)
    (bytes.fromhex("4810019008a10e91290080520801e9f8480000b4c0035fd6"),
     bytes.fromhex("4810019008a10e91090140f9490000b4c0035fd6080100f9")),
    # v2.36.0 (ELF2 setup_dl_debug_hook)
    (bytes.fromhex("a82c00b008a13691290080520801e9f8480000b4c0035fd6"),
     bytes.fromhex("a82c00b008a13691090140f9490000b4c0035fd6080100f9")),
    # v2.35.2
    (bytes.fromhex("a80a01f008a12891290080520801e9f8480000b4c0035fd61f2003d5"),
     bytes.fromhex("a80a01f008a12891090140f92a008052490000b4c0035fd60a0100f9")),
    # v2.34.0
    (bytes.fromhex("680401f008a12c91290080520801e9f8480000b4c0035fd61f2003d5"),
     bytes.fromhex("680401f008a12c91090140f92a008052490000b4c0035fd60a0100f9")),
    # v2.33.0
    (bytes.fromhex("680101d008a10791290080520801e9f8480000b4c0035fd61f2003d5"),
     bytes.fromhex("680101d008a10791090140f92a008052490000b4c0035fd60a0100f9")),
    # v2.4.0 / v2.4.1
    (bytes.fromhex("88f500f008a13591290080520801e9f8480000b4c0035fd61f2003d5"),
     bytes.fromhex("88f500f008a13591090140f92a008052490000b4c0035fd60a0100f9")),
    # legacy
    (bytes.fromhex("c8ed00f008a13b91290080520801e9f8480000b4c0035fd61f2003d5"),
     bytes.fromhex("c8ed00f008a13b91090140f92a008052490000b4c0035fd60a0100f9")),
]

TARGET_CPU = CPU_SIGNATURES[0][0]
REPLACEMENT_CPU = CPU_SIGNATURES[0][1]
TARGET_ATOMIC = ATOMIC_SIGNATURES[0][0]
REPLACEMENT_ATOMIC = ATOMIC_SIGNATURES[0][1]

def find_all(data: Union[bytes, bytearray, memoryview], pattern: bytes) -> List[int]:
    matches = []
    pos = 0
    while True:
        pos = data.find(pattern, pos)
        if pos == -1:
            break
        matches.append(pos)
        pos += len(pattern)
    return matches

def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    selected_cpu = None
    raw_cpu, repl_cpu = [], []
    for tgt, rpl in CPU_SIGNATURES:
        rc = find_all(data, tgt)
        rpc = find_all(data, rpl)
        if rc or rpc:
            selected_cpu = (tgt, rpl)
            raw_cpu, repl_cpu = rc, rpc
            break

    atomic_matches = []
    raw_atomic, repl_atomic = [], []
    for tgt, rpl in ATOMIC_SIGNATURES:
        ra = find_all(data, tgt)
        rpa = find_all(data, rpl)
        if ra or rpa:
            atomic_matches.append((tgt, rpl, ra, rpa))
            raw_atomic.extend(ra)
            repl_atomic.extend(rpa)

    info = {
        "name": "failfast_cpu_and_atomic",
        "cpu_raw": [hex(x) for x in raw_cpu],
        "cpu_patched": [hex(x) for x in repl_cpu],
        "atomic_raw": [hex(x) for x in raw_atomic],
        "atomic_patched": [hex(x) for x in repl_atomic]
    }

    cpu_total = len(raw_cpu) + len(repl_cpu)
    atomic_total = len(raw_atomic) + len(repl_atomic)

    if (cpu_total == 0 or atomic_total == 0):
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        v_ok = (len(raw_cpu) == 0 and len(repl_cpu) >= 1 and len(raw_atomic) == 0 and len(repl_atomic) >= 1)
        info["status"] = "verified" if v_ok else "unverified"
        return v_ok, info

    if len(raw_cpu) == 0 and len(raw_atomic) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for pos in raw_cpu:
            data[pos:pos + len(selected_cpu[1])] = selected_cpu[1]
        for _, rpl, ra, _ in atomic_matches:
            for pos in ra:
                data[pos:pos + len(rpl)] = rpl
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
