#!/usr/bin/env python3
"""
test_dynamic_patches.py - Tests masked/dynamic AArch64 scanners in crash_suppressor and pidfd.
"""
import unittest
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
sys.path.insert(0, os.path.join(MODULES_DIR, "groups", "failfast"))
sys.path.insert(0, os.path.join(MODULES_DIR, "groups", "syscalls"))

import crash_suppressor
import pidfd


class TestDynamicPatches(unittest.TestCase):
    def test_dynamic_cpu_and_atomic_shifted_immediates(self):
        # Synthetic AArch64 instructions with unseen adrp/add/ldr immediates
        cpu_unseen = (
            bytes.fromhex("93000090")          # adrp x19, #0 (unseen offset)
            + bytes.fromhex("0a609052")        # mov w10, #0x8300
            + bytes.fromhex("680a40f9")        # ldr x8, [x19, #0x10] (unseen offset)
            + crash_suppressor._CPU_INVARIANT_TAIL
            + crash_suppressor._CPU_PATCHED_AFTER
        )
        atomic_elf1 = (
            bytes.fromhex("080000b0")          # adrp x8, ... (unseen)
            + bytes.fromhex("08450091")        # add x8, x8, #0x11 (unseen)
            + crash_suppressor._ATOMIC_INVARIANT_TAIL
        )
        atomic_elf2 = (
            bytes.fromhex("28000090")          # adrp x8, ... (unseen)
            + bytes.fromhex("08890091")        # add x8, x8, #0x22 (unseen)
            + crash_suppressor._ATOMIC_INVARIANT_TAIL
        )
        blob = bytearray(b"\x00" * 16 + cpu_unseen + b"\x00" * 16 + atomic_elf1 + b"\x00" * 16 + atomic_elf2)

        ok, info = crash_suppressor.apply(blob, dry_run=False)
        self.assertTrue(ok, info)
        self.assertEqual(info["status"], "patched")
        self.assertEqual(len(info["cpu_raw"]), 1)
        self.assertEqual(len(info["atomic_raw"]), 2)

        # Verify idempotent and verify=True
        ok_re, info_re = crash_suppressor.apply(blob, dry_run=False)
        self.assertTrue(ok_re)
        self.assertEqual(info_re["status"], "already_patched")

        ok_ver, info_ver = crash_suppressor.apply(blob, verify=True)
        self.assertTrue(ok_ver, info_ver)
        self.assertEqual(info_ver["status"], "verified")

    def test_dynamic_pidfd_shifted_bl(self):
        # Unseen bl offset (0x97ffff00) and unseen cbz x2 target (0xb4000242)
        unseen_pidfd = bytes.fromhex("e10300aa403680d200ffff97420200b4")
        blob = bytearray(b"\x00" * 16 + unseen_pidfd + b"\x00" * 16)

        ok, info = pidfd.apply(blob, dry_run=False)
        self.assertTrue(ok, info)
        self.assertEqual(info["status"], "patched")
        self.assertIn(pidfd.REPLACEMENT, blob)

        ok_ver, info_ver = pidfd.apply(blob, verify=True)
        self.assertTrue(ok_ver)
        self.assertEqual(info_ver["status"], "verified")


if __name__ == "__main__":
    unittest.main()
