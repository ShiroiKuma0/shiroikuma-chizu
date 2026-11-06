---
name: recalibrate-driving
description: Recalibrate 白い熊 地図's routing to how 白い熊 actually drives — rerun the statistics on every drive logged so far and propose updates to the my-speeds table (ChizuSpeeds rows), the time lost at a traffic light and the time lost at a turn. Use whenever 白い熊 says "recalibrate-driving", "/recalibrate-driving", "recalibrate my speeds", "rerun the driving stats", "update my-speeds from my drives", or asks whether the speed table, traffic light time or turn time still fit the driving.
---

# recalibrate-driving

Fits the routing's time model to 白い熊's own driving, from the drive logs the phone keeps. The result
is a **proposal** — a table of rows with what was driven and a recommended value — that 白い熊 OKs or
corrects row by row. Only then are the values built in.

## What the model has (where it lives)

| Knob | Where | Applied to |
| --- | --- | --- |
| Speed per road type and posted limit (rows 1–55) | `OsmAnd/src/net/osmand/plus/chizu/ChizuSpeeds.java` → `ROWS` (preset = last number), `FITTED`, `TABLE_VERSION` | router speed rules, route times |
| Time lost at a traffic light | `ChizuSpeeds.getTrafficLightSeconds` (default 20 s), UI slider | router obstacle time |
| Time lost at a turn (over 60°; slight turns half) | `ChizuSpeeds.getTurnSeconds` (default 10 s), UI slider | router `slightTurn`, candidate card times |

The UI (地図 → UI settings → My speeds) shows every row and both sliders; 白い熊 may have changed values
there, so the stored values on the phone can differ from the presets. The road log carries the value in
use at each drive (`mine_kmh`).

## Data

- On the phone: “`/sdcard/Android/data/shiroikuma.chizu/files/diag/`”
  - `chizu-roads_<day>.tsv` — one line per road section driven: highway, tunnel, limit, traffic colour,
    length, time, average and top speed, OsmAnd's and 白い熊's table speed, navigating, short.
    Written while Diagnostics → “Road log” is on.
  - `chizu-drive_<day>.txt` — the drive log; the calibration of lights and turns needs its
    “`loc aa`” car fixes (Diagnostics → “Positions”).
- The project keeps everything pulled so far in the gitignored “`.scratch/drive-data/`”:
  `logs/` (all days, old ones too — the phone's keep-N-days purge may already have dropped them) and
  `obf/` (the Prague and Central Bohemia maps, to find the traffic lights and match fixes to roads).
  The maps on the phone are in “`/storage/emulated/0/〇/[60] 地図/`”; refresh `obf/` from there after a
  map update.

## Steps

1. **Pull the new logs** (adb unsandboxed, USB first, per the global delivery chain), adding to what is
   kept — never deleting older days:
   ```bash
   R=$(git rev-parse --show-toplevel); D=$R/.scratch/drive-data
   adb pull /sdcard/Android/data/shiroikuma.chizu/files/diag/. $D/pull/
   cp -n $D/pull/chizu-roads_*.tsv $D/pull/chizu-drive_*.txt $D/logs/   # -n: keep what is there
   cp $D/pull/chizu-drive_$(date +%F).txt $D/logs/                      # today's grows: refresh it
   ```
   A day's file on the phone is complete once the day is over; re-copy the current day.

2. **Speeds per road type and limit:**
   ```bash
   python3 -I $R/.claude/skills/recalibrate-driving/speeds.py $D/logs $R/OsmAnd/src/net/osmand/plus/chizu/ChizuSpeeds.java | column -t -s $'\t'
   ```
   Columns: the row it falls in (`None` = a limit between rows), OsmAnd's speed, 白い熊's value at the
   time, free-flowing sections only (green or grey traffic, not short, ≥ 50 m): count, km, average,
   85th percentile; all sections' average; km in yellow/red; days; and a first-guess `suggest`
   (0.6 × average + 0.4 × p85, rounded to 5; blank under 1 km of data). Simulated drives (test
   receiver “`sim`”, the head unit) are left out automatically.

3. **Traffic lights and turns:**
   ```bash
   $R/.claude/skills/recalibrate-driving/calib.sh                  # -Dqueues=true counts the queue before a light too
   ```
   (If the OsmAnd-java jar is missing, any `./gradlew buildApk` builds it.) The tool matches the car
   fixes to roads, finds the traffic-signal nodes on them, and at each junction passed measures the time
   over ±150 m against the cruising speed 150–450 m before and after; turns of 45°–150° with no light
   within 60 m likewise over ±100 m. It reports mean, median, quartiles, the share of stops, and the
   stopped vs. rolled-through split.
   - The light setting is charged **per signal node** the route crosses; the tool prints nodes run over
     against junctions passed (2026-11-06: 75 / 65), so per node = per junction ÷ that ratio.
   - The turn setting is one value for left and right: the router has no side.

4. **Judge, don't just copy `suggest`.**
   - Section averages include stops at lights, which the light setting charges separately — where lights
     are common, recommend somewhat above the average.
   - Under ~1 km of free data a row is low confidence: move it only partly toward what was driven.
   - Keep each type monotonic in the limit (a lower limit never faster than a higher one); fix unmeasured
     neighbour rows that would break it, and say so.
   - Rows with no data stay as they are.

5. **Present to 白い熊** — one table: row, road and limit, now, driven (avg / p85), data (km, days),
   recommendation; then lights (stopped / rolled / per node) and turns, each with the recommendation.
   **Wait for OK or corrections by row number.** Nothing is built before that.

6. **Build it in** (after the OK):
   - presets: the last number of each changed `new Row(...)` in `ChizuSpeeds.ROWS`;
   - `FITTED`: the OK'd rows (only those), and **bump `TABLE_VERSION`** — `ChizuSpeeds.migrate` writes
     `FITTED` once over the stored rows, so the new values take effect even where the UI had stored a
     value; any row stays changeable afterwards;
   - light / turn defaults (`getTrafficLightSeconds`, `getTurnSeconds`) if they change — note these are
     defaults only; a value 白い熊 set with the slider stays (write it in `migrate` too if it must change);
   - then the normal build (build-apk skill) and delivery.

## History

- 2026-11-06, table v2 (+050): 110 km of free road over 5 days (10-30 – 11-05). City presets were far
  above the driving (trunk 70 at 110 → 75, primary 50 at 90 → 70, residential 50 at 90 → 40); tertiary
  slower than OsmAnd (50 → 40, no limit 45 → 35). Lights: 65 junctions, stopped at 43 % losing 46 s,
  rolled through losing 2.5 s, 21 s per junction ≈ 18 s per node → kept 20 s. Turns without a light
  ≈ 10 s (right 13, left 7, 17 turns) against the profile's 15 s → 10 s.
