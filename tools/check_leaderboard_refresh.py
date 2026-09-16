"""Execute the entry helper and original cache reset, using a player-supplied ELF.

Optional developer check: python -m pip install unicorn==2.1.4
Run: python tools/check_leaderboard_refresh.py path/to/libhelloworld.so
No game files are written or bundled. All player memory is synthetic.
"""

import argparse
from pathlib import Path
import hashlib
import json
import struct
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0,
    UC_ARM64_REG_X1,
    UC_ARM64_REG_X2,
    UC_ARM64_REG_X19,
    UC_ARM64_REG_X20,
    UC_ARM64_REG_X21,
    UC_ARM64_REG_X22,
    UC_ARM64_REG_X23,
    UC_ARM64_REG_X24,
    UC_ARM64_REG_X25,
    UC_ARM64_REG_X26,
    UC_ARM64_REG_X27,
    UC_ARM64_REG_X28,
    UC_ARM64_REG_X29,
    UC_ARM64_REG_SP,
    UC_ARM64_REG_LR,
    UC_ARM64_REG_PC,
)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("library", type=Path, help="Your supported arm64 libhelloworld.so")
args = parser.parse_args()
original = args.library.read_bytes()
d = json.loads(
    (
        Path(__file__).resolve().parents[1] / "patches/native/libhelloworld.json"
    ).read_text()
)
assert hashlib.sha256(original).hexdigest() == d["source_sha256"]
# The helper reuses string storage. Verify that the Android loader maps it
# executable, with the same virtual-address/file-offset relationship.
phoff = struct.unpack_from("<Q", original, 32)[0]
phsize, phcount = struct.unpack_from("<HH", original, 54)
segments = [
    struct.unpack_from("<IIQQQQQQ", original, phoff + i * phsize)
    for i in range(phcount)
]
assert any(
    kind == 1
    and flags & 1
    and offset == address
    and offset <= 0x60CD8C
    and 0x60CDA4 <= offset + file_size
    for kind, flags, offset, address, _, file_size, _, _ in segments
), "the helper must be mapped executable"
b = bytearray(original)
for s in d["patches"]:
    a = int(s["offset"], 16)

    def decode(v):
        return bytes.fromhex(v["hex"]) if "hex" in v else v["text"].encode() + b"\0"

    before = decode(s["before"])
    after = decode(s["after"]).ljust(len(before), b"\0")
    assert original[a : a + len(before)] == before
    b[a : a + len(after)] = after
assert hashlib.sha256(b).hexdigest() == d["result_sha256"]
BASE = 0x100000
PLAYER = 0x10000000
RANK = PLAYER + 0x4BD8
STACK = 0x20000000
ROWS = 0x30000000
for populated in [False, True]:
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    u.mem_map(BASE, 0x4000000)
    u.mem_write(BASE, bytes(b))
    u.mem_map(PLAYER, 0x10000)
    u.mem_write(PLAYER, b"\xa5" * 0x10000)
    u.mem_map(STACK, 0x10000)
    u.mem_map(ROWS, 0x10000)
    pack = lambda x: struct.pack("<Q", x)
    frees = []
    writes = []
    for category in range(13):
        base = RANK + category * 0x158
        for page in range(13):
            ptr = ROWS + (category * 13 + page) * 0x100
            end = ptr + 128 if populated else ptr
            u.mem_write(
                base + 0x28 + page * 24, pack(ptr) + pack(end) + pack(ptr + 128)
            )
            u.mem_write(base + 0x160 + page, bytes([int(populated)]))
            if populated:
                u.mem_write(ptr + 64 + 8, b"\x01")
                u.mem_write(ptr + 64 + 24, pack(ptr + 0xC0))
        u.mem_write(
            base + 0x170,
            struct.pack("<II", 13 if populated else 0, 4 if populated else 0),
        )
        u.mem_write(base + 0x178, bytes([int(populated)]))
    expected = bytearray(u.mem_read(PLAYER, 0x10000))
    for category in range(13):
        off = 0x4BD8 + category * 0x158
        for page in range(13):
            vec = off + 0x28 + page * 24
            expected[vec + 8 : vec + 16] = expected[vec : vec + 8]
            expected[off + 0x160 + page] = 0
        expected[off + 0x170 : off + 0x178] = b"\0" * 8
        expected[off + 0x178] = 0
    regs = [
        UC_ARM64_REG_X19,
        UC_ARM64_REG_X20,
        UC_ARM64_REG_X21,
        UC_ARM64_REG_X22,
        UC_ARM64_REG_X23,
        UC_ARM64_REG_X24,
        UC_ARM64_REG_X25,
        UC_ARM64_REG_X26,
        UC_ARM64_REG_X27,
        UC_ARM64_REG_X28,
    ]
    saved = {r: 0x60000000 + r for r in regs}
    for r, v in saved.items():
        u.reg_write(r, v)
    sp = STACK + 0xF000
    screen = 0x55555555
    returnaddr = 0x77777777
    u.reg_write(UC_ARM64_REG_SP, sp)
    u.reg_write(UC_ARM64_REG_X0, screen)
    u.reg_write(UC_ARM64_REG_LR, returnaddr)
    calls = []

    def hook(uc, addr, size, _):
        if addr == 0x2CF86BC:
            calls.append("player")
            uc.reg_write(UC_ARM64_REG_X0, PLAYER)
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))
        elif addr == 0x3D6B290:
            frees.append(uc.reg_read(UC_ARM64_REG_X0))
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))
        elif addr == 0x3D6B5C0:
            calls.append("notifier")
            uc.reg_write(UC_ARM64_REG_X0, 0x12345678)
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))

    u.hook_add(UC_HOOK_CODE, hook)
    u.hook_add(
        UC_HOOK_MEM_WRITE,
        lambda uc, a, address, size, value, data: writes.append((address, size)),
    )
    u.emu_start(0x20D62CC, 0x20D62E0, count=50000)
    assert u.reg_read(UC_ARM64_REG_PC) == 0x20D62E0
    assert calls == ["player", "notifier"], calls
    assert (
        bytes(u.mem_read(PLAYER, 0x10000)) == expected
    ), "unexpected player memory mutation"
    assert frees == (
        [ROWS + i * 0x100 + 0xC0 for i in range(169)] if populated else []
    ), "free each long name exactly once"
    assert all(
        (STACK <= a and a + n <= STACK + 0x10000)
        or (RANK <= a and a + n <= RANK + 13 * 0x158 + 0x28)
        for a, n in writes
    )
    assert u.reg_read(UC_ARM64_REG_SP) == sp - 32
    assert u.reg_read(UC_ARM64_REG_X29) == sp - 32
    assert struct.unpack("<Q", u.mem_read(sp - 24, 8))[0] == returnaddr
    assert u.reg_read(UC_ARM64_REG_X19) == screen
    assert u.reg_read(UC_ARM64_REG_X0) == 0x12345678
    for r, v in saved.items():
        if r != UC_ARM64_REG_X19:
            assert u.reg_read(r) == v
    # Original IsDataReady stays false after invalidation, and true once a page arrives.
    for category in range(13):
        for ready in [False, True]:
            u.mem_write(RANK + category * 0x158 + 0x178, bytes([int(ready)]))
            u.mem_write(RANK + category * 0x158 + 0x160, bytes([int(ready)]))
            u.mem_write(RANK + category * 0x158 + 0x170, struct.pack("<I", 1))
            u.reg_write(UC_ARM64_REG_X0, RANK)
            u.reg_write(UC_ARM64_REG_X1, category)
            u.reg_write(UC_ARM64_REG_X2, 0)
            u.reg_write(UC_ARM64_REG_LR, 0x20D62E0)
            u.emu_start(0x2D83734, 0x20D62E0, count=100)
            assert u.reg_read(UC_ARM64_REG_X0) == int(ready)
    print(
        json.dumps(
            {
                "populated": populated,
                "categories": 13,
                "pages": 169,
                "freed_long_names": len(frees),
                "player_memory_exact": True,
                "registers_preserved": True,
                "ready_after_response": True,
            }
        )
    )
