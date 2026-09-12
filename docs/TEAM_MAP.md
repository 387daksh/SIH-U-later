# SUTRA Team map

The previous map placed unknown peers and alerts at generated offsets around the local phone, then calculated distances to those offsets. The Team map removes those placeholders. A geographic point now needs an actual reported location; an unlocated teammate still appears in the list.

## What works

- Each updated phone publishes its stable terminal ID, model name, process session and sequence every 10 seconds over existing TCP/Bluetooth links. Phones are counted by ID, not transport connections.
- Location sharing is opt-in on the Map page and only operates while the activity is resumed. Pausing, disabling sharing, or losing a fresh fix publishes presence without coordinates. There is no background location service or stored location history. A lost withdrawal packet cannot erase a peer's previous copy; that copy ages visibly and disappears with presence expiry.
- Fixes include Android's reported horizontal accuracy and sample age. Local and received ages use monotonic clocks. Remote wall clocks are never subtracted. App queue/relay dwell ages the fix; unmeasured network transit time is not included. This is recent best-effort telemetry, not synchronized ranging.
- HELLO carries a versioned `SPO1` binary payload. Old apps already ignore HELLO but cannot publish or forward these positions. The feature does not change the speech envelope, speech ACKs or outbox. All participating phones need the update for map relay.
- Each new broadcast is relayed once per phone, with at most four forwarding steps (five received links). A 2,048-ID in-memory receipt set suppresses loops; session/sequence checks reject reordered samples within a session. At most 128 peers are held, with 10-minute expiry. `Direct receipt` / hop count describe the first accepted beacon path, not a verified current connection or shortest route.
- Fixes are fresh for 30 seconds; contact is recent for 35 seconds. These are product thresholds, not field-calibrated guarantees. Stale locations are labelled, faded, and excluded from the two-device measurement picker.
- Distances are horizontal great-circle distances between fixes, not walking routes, radio ranges, floor distances or obstacle-aware navigation. The displayed envelope is `[max(0, d - r1 - r2), d + r1 + r2]`. Direction is unresolved when `d <= r1 + r2`. The envelope is not a guaranteed bound or a statistical confidence interval.
- The north-up field view works without internet or map tiles, displays accuracy halos, supports zoom, and connects selected measurement endpoints with a dashed line. That line is a measurement, not a radio link. Numbered rows remain accessible when pins overlap.
- Street map is an explicit online option using the existing osmdroid dependency and OpenStreetMap, with visible attribution. No bulk downloads or prefetching. Selecting it sends tile-area requests to the tile provider. Offline field needs no such requests.
- Alerts without matching fresh location metadata remain visible as unverified-location reports. No guessed emergency coordinates. Bluetooth's older schematic now shows signal dBm instead of uncalibrated metre labels.

## Research and hardware boundary

This is a product improvement, not a claim of a new localization algorithm. Existing mesh products already share GPS positions. SUTRA's focus here is bringing fix uncertainty, age, and receipt path into its offline multilingual team workflow.

1. [Android Wi-Fi RTT](https://developer.android.com/develop/connectivity/wifi/wifi-rtt): ranging requires supported hardware and RTT-capable APs or Wi-Fi Aware peers. Ordinary Wi-Fi Direct/TCP connection latency is not an RTT range measurement. The connected CPH2717 advertised GPS, Bluetooth and Wi-Fi Direct, but neither `android.hardware.wifi.rtt` nor `android.hardware.wifi.aware` in `pm list features` on 2026-09-12. No RTT/UWB capability is claimed.
2. [Android Location.getAccuracy](https://developer.android.com/reference/android/location/Location#getAccuracy()): accuracy is a reported horizontal radius at the 68th percentile. Summing two reported radii does not create a guaranteed error bound. GPS can be poor indoors and cannot reliably distinguish phones a few metres apart when their uncertainty overlaps.
3. [Inferring proximity from Bluetooth Low Energy RSSI with Unscented Kalman Smoothers](https://arxiv.org/abs/2007.05057): RSSI varies with device, orientation, body position and environment. A fixed uncalibrated path-loss model is insufficient evidence for accurate metres. This paper informs the limitation, not an implemented Kalman filter.
4. [Meshtastic position configuration](https://meshtastic.org/docs/configuration/radio/position/): established prior art for shared positions, periodic updates and movement-sensitive broadcast policies. GPS exchange itself is not novel.
5. [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/): attribution, an identifying User-Agent and caching; no offline bulk downloading from the public standard tile service.

## Verification and demo

Build: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline` from `android`, with the Android Studio JBR as JAVA_HOME.

`TeamPositionTest` covers binary wire round trips with different clock origins, malformed/truncated input, invalid coordinates and hop limits, three-node relay/duplicate ageing, multiple IDs, out-of-order samples, withdrawal, expiry, overlapping accuracy and unknown location. These are deterministic tests, not physical range measurements.

The optional `scripts/check_team_map_peer.py` uses two clearly named synthetic TEST nodes through an ADB-forwarded TCP connection. It checks encrypted receive/relay and duplicate suppression on the actual Android service. It does not move the phone's GPS or prove radio discovery, two-phone GPS accuracy, or physical multi-hop coverage. Restart the app after the probe to remove the temporary in-memory TEST nodes.

Physical acceptance still requires two updated phones: enable sharing on both; grant location permission; obtain fresh outdoor fixes; compare separation against a measured reference at several distances; repeat with a phone in a pocket, indoors, sharing disabled, and a dropped link. A three-phone relay check requires simultaneous A-B and B-C links with A-C absent. Do not infer relay from physical separation alone.

Position claims are peer-reported. This uses existing session encryption, including the existing shared demo key and compatibility behavior; it is not a trusted-identity or spoof-resistant tracking system.
