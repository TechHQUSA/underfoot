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
`01` (stopped) is assumed from the 5L notes and has not been seen. Pressing Stop on the console leaves the pad in `0A` (paused) with the belt stopped and elapsed time frozen (measured twice); the app therefore ends a walk after 60 s of pause.
- 6 bytes while idle or counting down: `02 51 00 01 08 03`.
- 25 bytes while running, pausing and paused: `02 51 <st> <speed u16> <elapsed u16> <dist u16> <kcal u16> <?? u16> 00 00 00 00 39 00 <mac4> <chk> 03`.
  - bytes 5-6: elapsed seconds (matches the console and `2ACD`).
  - bytes 9-10: energy in tenths of a kcal (`0x24` = 3.6; `2ACD` shows the integer part, 3).
  - bytes 3-4: speed, but in **0.1 mph**, not km/h (raw 6 = 0.6 mph = 0.96 km/h on `2ACD`). Max seen: 40 = 4.0 mph = 6.43 km/h.
  - bytes 7-8: distance in **0.01 mile** (steps every 16.09 m), too coarse to use.
  - bytes 11-12: unknown, slowly counts up while the belt ramps. Not steps, not kcal. Left undecoded.
  - bytes 19-22: the pad's own MAC address, reversed. Byte 23: checksum, algorithm unknown, ignored.
The app takes status, elapsed and kcal from `fff1`, and speed and distance from `2ACD` in SI units, because `fff1`'s own speed and
distance are in miles. Whether `fff1`'s unit changes if the console is switched to km is untested; the app does not depend on it.

## 2ACD Treadmill Data (measured, standard FTMS)
Flags `0x0484`: speed (0.01 km/h), total distance (u24 metres), expended energy (total kcal u16; per-hour and per-minute fields
read `FFFF` and `FF`, meaning not available), elapsed time (u16 s). The distance and elapsed time reset to 0 when a walk starts.
No step count: FTMS Features says steps are "supported" but Treadmill Data has no steps field. The app estimates steps from distance
and height (marked as estimated).

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

## Connection
Request an MTU of 247 right after connecting. The 25-byte running frame on `fff1` does not fit the default 23-byte payload, and without
the request the pad connects and shows idle pings but never delivers a running frame (measured on a URTM059).

## Safety
The app writes only: the two handshake frames, and, on a button tap, `00` (once per connection), `08 02` (Pause), `07` (Start or Resume),
`08 01` (Stop) and `02 <speed>` (Set Target Speed, only while the belt runs, clamped to 0.6-4.0 mph, at most every 250 ms; the dial sends it once on release, never mid-drag). It never sends a command on its own. Start, Pause and Resume are rate-limited to one per second and a speed change to one per 250 ms; Stop never is. A speed change is accepted only while the belt runs. Which
buttons are active depends on the belt status (`FtmsControl.allowed`), and Settings can turn the buttons off entirely.

## Still unknown
- Whether the pad ever leaves `0A` by itself (it stayed there for over a minute), and what `01` (stopped) means.
- Whether the pad keeps the handshake across a disconnect.
- The meaning of `fff1` bytes 11-12 and the checksum.
- Whether a pad set to km (console unit) changes the units of `fff1` speed and distance.
