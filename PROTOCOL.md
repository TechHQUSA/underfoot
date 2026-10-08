# Pad protocol notes

What is known about the UREVO URTM059 over Bluetooth LE, and how sure we are. Everything below marked "measured" comes from a real
capture (nRF Connect, 2026-10-07, see `captures/`).

Credit: the handshake and the `fff1` frame idea come from the TreadSpan project's E1L (URTM041) analysis and the urevo-darwin
project's SpaceWalk 5L (URTM054) analysis; the three models share an OEM firmware family. No code was copied from either project.

## GATT layout (measured)
`0x180A` Device Information, `0xFFF0` (`fff1` notify, `fff2` write), `0xFEE0` (`fee1` notify, `fee2` write-no-response, unused),
`0x1826` Fitness Machine (`2ACD` Treadmill Data notify, `2AD3` Training Status, `2ADA` Fitness Machine Status, `2AD9` Control
Point, `2ACC` Features). The advertisement lists `0x1826` and the name `URTM059`. While a central is connected the pad stops
advertising, so a second app cannot see it.

## Handshake (measured)
Write `02 51 0B 03`, then `02 50 03 09 03`, to `fff2` (plain Write works). The pad answers the second one on `fff1` with
`02 50 03 00 00 59 F6 03`. After that `fff1` sends a frame every second and `2ACD` about five times a second. `2ACD` data also
flowed once the handshake had been done earlier on the same connection; whether it needs the handshake is unconfirmed.

## fff1 frames, header `02 51 <status> ...`, last byte `03` (measured)
Status byte: `00` idle, `02` start countdown (data byte counts 3, 2, 1), `03` running, `04` pausing (belt slowing), `0A` paused.
A short press of play/pause on the remote pauses (`04` then `0A`, belt stopped, counters frozen) and a second press resumes with the 3-2-1 countdown; the counters carry on. A long press is "End": the pad sends status `01` (stopped) with the final values, then idles, then shows `06` (6-byte `02 51 06 01 02 03`, about to power off). The pad powers itself off after a long pause, which drops the connection. Pause with the remote and resume from the app (`07`) keeps the counters too (measured 2026-10-08).
- 6 bytes while idle or counting down: `02 51 00 01 08 03`.
- 25 bytes while running, pausing and paused: `02 51 <st> <speed u16> <elapsed u16> <dist u16> <kcal u16> <?? u16> 00 00 00 00 39 00 <mac4> <chk> 03`.
  - bytes 5-6: elapsed seconds (matches the console and `2ACD`).
  - bytes 9-10: energy in tenths of a kcal (`0x24` = 3.6; `2ACD` shows the integer part, 3).
  - bytes 3-4: speed, but in **0.1 mph**, not km/h (raw 6 = 0.6 mph = 0.96 km/h on `2ACD`). Max seen: 40 = 4.0 mph = 6.43 km/h.
  - bytes 7-8: distance in **0.01 mile** (steps every 16.09 m), too coarse to use.
  - bytes 11-12: **steps** (u16), the pad's own count (about 1 per second at 0.6 mph: 104 at 111 s). Confirmed against the console on 2026-10-08: the app's live step count (this field) stayed in lock step with the console during a walk and across pause and resume (60 on resume). The console hides the final value behind the End countdown. The first capture had nobody on the belt, hence the tiny values. Resets with the walk.
  - bytes 19-22: the pad's own MAC address, reversed. Byte 23: checksum, algorithm unknown, ignored.
The app takes status, elapsed and kcal from `fff1`, and speed and distance from `2ACD` in SI units, because `fff1`'s own speed and
distance are in miles. Whether `fff1`'s unit changes if the console is switched to km is untested; the app does not depend on it.

## 2ACD Treadmill Data (measured, standard FTMS)
Flags `0x0484`: speed (0.01 km/h), total distance (u24 metres), expended energy (total kcal u16; per-hour and per-minute fields
read `FFFF` and `FF`, meaning not available), elapsed time (u16 s). The distance and elapsed time reset to 0 when a walk starts.
No step count here: Treadmill Data has no steps field. The pad's step count is in `fff1` bytes 11-12; the app falls back to an
estimate from distance and height only when that is missing.

## Other notifications (measured, unused)
`2ADA` sends events such as "started or resumed", "stopped or paused by the user" and "target speed changed". `2AD3` sends
Pre-Workout (`01 0E`), Manual Mode (`01 0D`) and Other (`01 00`).

## Control point (measured with nobody on the belt, nRF Connect, 2026-10-07)
Standard FTMS Control Point `2AD9` (write; replies come as indications `80 <opcode> <result>`; in nRF the write dialog only opened
with indications turned off). All three worked after `00` (request control):
- `08 02` pause: the belt slows and holds in paused state (`fff1` status `04` then `0A`).
- `07` resume: the belt starts moving again.
- `08 01` stop: the belt slows down and the console shows END, which is the pad's own end-of-workout, unlike the console's Stop
  button, which only pauses.
- `02 <u16 LE>` set target speed, in 0.01 km/h (`02 40 01` = 3.20 km/h): the belt changes speed (measured with the app's -/+ buttons, 0.1 mph steps).
Start from idle (`07` after the walk ended) also works: the pad runs its countdown and starts the belt (measured with the app's Start
button). Not yet measured: the exact indications the pad sends back, and the `fff1` status after stop.

## Pause, End and sleep (measured 2026-10-08)
- Paused from the app (`08 02`) or from the remote (`2ADA` `02 02` with no preceding command), the pad stays in `0A` with counters frozen for 10+ minutes (the longest remote pause logged was 603 s); it does not sleep or time out while the app is connected, whoever paused it.
- A long press of play/pause on the remote is End (`2ADA` `02 01`, then `fff1` status `01`, then idle `00`). Once, the pad then went to `06` (standby, BLE still connected) within 7 s and stayed there until the remote woke it. Twice more, End left the pad in idle `00` for 10+ minutes with no `06`, so End alone does not put it to sleep. What triggers `06` is unknown.
- Pause on the remote, then Resume (`07`) from the app after 73-100 s: the pad resumes with its counters intact.

## Connection
Request an MTU of 247 right after connecting. The 25-byte running frame on `fff1` does not fit the default 23-byte payload, and without
the request the pad connects and shows idle pings but never delivers a running frame (measured on a URTM059).

## Safety
The app writes only: the two handshake frames, and, on a button tap, `00` (once per connection), `08 02` (Pause), `07` (Start or Resume),
`08 01` (Stop) and `02 <speed>` (Set Target Speed, only while the belt runs, clamped to 0.6-4.0 mph, at most every 250 ms; the dial sends it once on release, never mid-drag). It never sends a command on its own. Start, Pause and Resume are rate-limited to one per second and a speed change to one per 250 ms; Stop never is. A speed change is accepted only while the belt runs. Which
buttons are active depends on the belt status (`FtmsControl.allowed`), and Settings can turn the buttons off entirely.

## Still unknown
- How long the pad stays paused before it powers off, and whether `0A` ever ends on its own while connected.
- Bytes 17-18 of the running frame were `39 00` on 2026-10-07 and `48 00` on 2026-10-08 (not the elapsed time; not the owner's weight, which is about 77 kg; meaning unknown).
- Whether the pad keeps the handshake across a disconnect.
- The checksum.
- Whether a pad set to km (console unit) changes the units of `fff1` speed and distance.
