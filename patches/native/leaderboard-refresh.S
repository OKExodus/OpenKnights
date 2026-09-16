// Documentation source for the arm64 instructions in libhelloworld.json.
// Addresses below are ELF virtual addresses (equal to file offsets here).
// Ghidra's analyzed image adds 0x100000 to these addresses.
//
// OnEnter has already saved x29/x30 and x19/x20 and copied its this pointer
// to x19. Replace its first BL GetChangeNotifier at 0x1fd62dc with:
//     b 0x60cd8c
// The helper below occupies 24 bytes in the RX segment, after the terminator
// of the shortened, already-redacted logout message. No ELF layout changes.
//
// NativePatchSet checks the complete source hash, each original site and the
// complete result hash. These instructions are recorded as bytes in JSON;
// the normal build needs no assembler or original native source.
//
// Assemble at 0x60cd8c:
    bl  0x2bf86bc                 // PlayerInfo::GetPlayerInfo()
    add x0, x0, #4, lsl #12
    add x0, x0, #0xbd8            // &playerInfo.rankSystem
    bl  0x2c83770                 // RankSystem::Offline(): clear cached rows
    bl  0x3c6b5c0                 // Original GetChangeNotifier PLT call
    b   0x1fd62e0                 // Resume OnEnter with notifier in x0
//
// Runs once per screen entry, before response callbacks are registered.
// The existing request, response, paging and rendering paths stay intact.
// Do not invalidate from CreateRankList or IsDataReady: response handling
// also invokes those paths, so invalidating there would cause a reload loop.
