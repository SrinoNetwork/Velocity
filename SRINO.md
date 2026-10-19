# Srino Velocity fork

Upstream PaperMC/Velocity (`origin`, branch `dev/4.0.0`) plus Srino patches on `srino/dev`.
Update: `git fetch origin && git rebase <upstream release tag>`, then build and run the e2e below.
Build: `./gradlew shadowJar` -> `proxy/build/libs/velocity-proxy-<ver>-all.jar`.

## Patches

### Seamless server switch (`seamless-server-switch = true` in `[advanced]`)
1.21.4+ clients skip the `Reconfiguring` screen when the target server sends the same registries
the client already has.
- The proxy runs the target's configuration phase itself and holds back everything for the client
  (`ConfigSessionHandler`, SPECULATIVE). Known packs are answered with the client's last answer.
- At `FinishedUpdate` it compares a fingerprint (known packs, feature flags, every registry hashed
  with sorted NBT keys) with the client's (`SeamlessSwitchState`). Equal: the client gets the old
  pre-1.20.2 switch (JoinGame + Respawn) plus the held back play packets (tags, brand, ...).
  Different, or a cookie request / code of conduct / unknown config packet: normal reconfiguration,
  the held back packets are replayed (FALLBACK).
- The client keeps scoreboard objectives, teams and boss bars across that switch, so the proxy tracks
  what the backend created (`SetObjectivePacket`, `SetPlayerTeamPacket`, boss bars) and removes it.
- `PlayerConfigurationEvent` and friends do not fire for a skipped reconfiguration.
- Packet ids for the new play packets come from ViaVersion's `ClientboundPackets*` enums; add a
  mapping for every new Minecraft version.
- Test: `srino/e2e/seamless.mjs` (two local Papers + PicoLimbo + mineflayer bot, see the header).

### No loading screen (`seamless-no-loading-screen = true` in `[advanced]`, needs the switch above)
The 26.1 client opens `Loading terrain` on every JoinGame and every Respawn (even into the same
dimension, `ClientPacketListener.startWaitingForNewLevel`), so a switch without it sends neither.
- Only when the new backend's JoinGame has the client's entity id and the same spawn (dimension type,
  dimension key, flat, debug, sea level, hardcore); otherwise the switch above runs and the proxy
  logs `[seamless] X -> Y: loading screen (<reason>)`. Same entity id needs SparklySlimePaper
  `stable-player-entity-ids` (id = `0x40000000 | uuid hash`, galsever/SparklySlimePaper 5b14e6c2).
- The proxy peeks raw backend packets (`SeamlessSwitchState.trackClientbound`) for chunks, entities,
  the player's effects and waypoints, and on the switch sends forget chunk, remove entities, remove
  effects, untrack waypoints, close container, stop sounds, stop rain, gamemode, death screen,
  limited crafting, debug info, view and simulation distance, camera as raw packets
  (`ClientPlaySessionHandler.doSwitchWithoutLoadingScreen`), and PlayerLoaded to the backend.
  Everything else (chunks, position, inventory, attributes, abilities, health, time, border,
  difficulty, tab list) the new backend sends on join.
- The client stops ticking its player while no chunk is loaded under it, so it waits in place until
  the new chunks arrive (no fall).
- Packet ids: `SeamlessPacketIds` (ViaVersion enums, 1.21.4 to 26.3); add a column for every new
  Minecraft version, older clients keep the loading screen.
- Test: `srino/e2e/seamless-noscreen.mjs` (two local SparklySlimePaper 26.1.2 + PicoLimbo + mineflayer
  26.1 bot, 58/58 on 2026-10-19).
