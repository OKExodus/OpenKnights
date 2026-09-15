# Claims

**Definition.** The character's local-day claim cycle outside the VIP and monthly-card claims: the monthly check-in grid and its timed gift chain, the title salary flag, and the Royal Door's daily and level-up bonuses and its Essence donation.

## How It Works Inside

Check-in tracks a calendar grid of signed days for the current month plus a four-row timed gift chain that restarts at the first touch of each local day; signing a new day can also cross a milestone in the month's signed-day count for a bonus reward. The title salary is a simple flag: one claim per local day, paying the Gold and Diamond of the character's current title. The Royal Door tracks one local day at a time: a daily bonus claim, a level-up bonus claim available once per Door level, and four donation tasks drawn deterministically for that character's world and day. Donating Gold, in fixed units up to a daily cap, or listed items, each capped per day, toward the day's Donate task earns Essence and raises the world's shared Door experience, written back to the [[World Directory]] after the character's own commit.

## Opcodes

| Opcode | Role |
| --- | --- |
| `C1089` / `S1152` / `S1154` | Check-in and timed gift query |
| `C1091` | Sign in for the day |
| `C1093` | Claim the next timed gift |
| `C481` / `S18` | Claim the title salary; its flag rides the S18 |
| `C2369` / `S2720` | Royal Door query |
| `C2371` | Royal Door daily bonus |
| `C2373` | Royal Door level-up bonus |
| `C2375` | Royal Door donation |

## Data Files

| Table | Role |
| --- | --- |
| `qiandao.csv` | Check-in milestone rewards by signed-day count |
| `timegift.csv` | The timed gift chain's rows |
| `lv_yijiezhimen.csv` | Royal Door level thresholds and their daily/level-up bonuses |
| `quest_yijiezhimen.csv` | Royal Door donation tasks, including the Donate task's rates |

See the [[Data File Index]].

## Persistence

Each claim or donation commits one revision to the character's [[State Store]]; the Royal Door donation additionally writes the shared world Door document. See [[Transactions and Publishing]].

## Determinism

Check-in and the timed gift chain turn at the device's local midnight. The Royal Door's four daily donation tasks are drawn from a seed seeded on the character's world and the local day, not chosen live. See [[Method Determinism]].

## Code

Reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/game/Daily.kt` (the Check In, Salary, and Royal Door sections), routed from `Session.dailyRoute`. The VIP daily reward, AP/Energy buys, and monthly-card claims are a related but separate family, reproduced in `Claims.kt`; see [[VIP and Monthly Cards]].

## How It Was Deciphered

The check-in, salary, and Royal Door messages were captured across their actions and reproduced so the saved grid, chain, and donation totals match the reference, pinned by the [[Method Differential Harness]].

## See Also

- [[Daily Missions]], [[VIP and Monthly Cards]].
