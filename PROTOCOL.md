# Pad protocol notes

What is known about the UREVO URTM059 over Bluetooth LE, and how sure we are.

Credit: this builds on the TreadSpan project's E1L (URTM041) analysis and the urevo-darwin project's SpaceWalk 5L (URTM054) analysis. The three models share an OEM firmware family. No code was copied from either project; the decoder is written from the documented facts and tested against captured frames.

## Confirmed on URTM059 (nRF Connect, 2026-10-07)
- Advertised name `URTM059`; the advertisement lists service `0x1826` (Fitness Machine).
- After connecting, primary services `0x180A`, `0xFFF0`, `0xFEE0` and `0x1826` are present.

## Taken from the 5L / E1L research, not yet confirmed on URTM059
- `0xFFF0`: `fff1` notifies telemetry, `fff2` is written. `fff1` stays silent until two handshake frames are written to `fff2`: `02 51 0b 03`, then `02 50 03 09 03`.
- `fff1` frames start `02 51`. Byte 2 is the belt status (`00` idle, `01` stopped, `03` running, `04` pausing, `0a` paused). Bytes 3-4, little-endian, are the speed in tenths of km/h and are present only in frames of 19 bytes or more.
- Elapsed time and distance sit later in the frame; their layout is only partly mapped.

## Still to measure (Task 7, needs a capture from the pad)
- Whether `URTM059` uses this frame layout at all.
- Which fields the pad reports: distance, steps, calories, elapsed time.
- Whether pad totals reset at the start of each walk or keep counting.
- Which write type the handshake needs (`WRITE_TYPE_DEFAULT` is the first choice in the code; `WRITE_TYPE_NO_RESPONSE` is the fallback).
- Whether `2acd` (FTMS Treadmill Data) carries distance, energy and elapsed time.

The app's hidden raw-log screen records frames to a file for this purpose (Settings, tap the version row 7 times).

## Safety
Version 0.1 never sends control frames. The only writes are the two handshake frames above.
