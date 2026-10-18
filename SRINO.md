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
