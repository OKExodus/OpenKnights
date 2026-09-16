// Documentation source for the chat branch in libhelloworld.json.
// ELF virtual addresses equal file offsets here; Ghidra adds 0x100000.
//
// GameStateChat::HandleMenuSend reads PlayerInfo's level, calls
// PropertySystem::GetProperty(298), then compares the two at 0x15a14a0.
// The original conditional branch at 0x15a14a4 sends only at or above
// that level. Otherwise it displays localized message 1783 and returns.
// Replace that branch with an unconditional branch to the normal send path:
    b 0x15a14ec
//
// This changes no register or stack value, packet framing, input handling,
// or cooldown. The server intercepts slash commands before reapplying the
// configured level restriction to ordinary chat. Thus a new character can
// use commands without opening ordinary chat earlier than the game allows.
// Source hash, original branch bytes, and final library hash are checked
// by NativePatchSet. No assembler or proprietary code is bundled.
