# RespawnBridge

Fires `PlayerRespawnEvent` on Folia, so plugins that choose where players respawn work again.

## The problem

Folia never fires `PlayerRespawnEvent` for death respawns. Only `PlayerDeathEvent` fires. Plugins that set the respawn location in that event (MythicDungeons, for example) silently do nothing, and players end up at their bed or the world spawn instead.

## How it works

1. When a player dies, RespawnBridge marks them as pending.
2. When they click **Respawn**, it intercepts the client's respawn packet (via PacketEvents) and fires `PlayerRespawnEvent` on the player's own region thread, before Folia places them.
3. If a listener changed the location, the player's respawn point is set to that target for this one respawn, so Folia places them there directly. There is no flash of the world spawn.
4. Once the player is alive, their original respawn point (bed, anchor or none) is restored. If they are more than a block off the target, they are teleported to it.

If the respawn packet never comes through (Bedrock players via Geyser, for example), the event is fired as soon as the player is alive again and a teleport moves them instead. The player may briefly see the default spawn in that case.

Bed and respawn anchor respawns are left to Folia whenever no listener changes the location. RespawnBridge never reads blocks off the region thread.

If another source fires `PlayerRespawnEvent` for a pending player (for example if a future Folia build starts firing it), RespawnBridge logs a warning and stops bridging, so the event is never fired twice.

## Requirements

- Folia 26.1.2
- [PacketEvents](https://github.com/retrooper/packetevents) 2.14.0 or newer
- Java 25

RespawnBridge is built and checked against Folia 26.1.2. It will not load on older versions (`api-version: 26.1`). Newer 26.x builds will likely work but have not been tested.

## Installation

1. Install PacketEvents.
2. Put `RespawnBridge-1.0.0.jar` in `plugins/`.
3. Restart the server.

There are no commands or permissions.

## Configuration

```yaml
# Log one line per handled death: old respawn point, event target, fallback teleport.
debug: false
```

## Building

```sh
mvn package
```

The jar ends up in `target/`.

## Known limitations

- The forced flag of a respawn point set with `/spawnpoint` is not restored after a redirected respawn.
- If the target location is obstructed, Folia shows the vanilla "no respawn point" message before the fallback teleport corrects the position.
- On hardcore servers, the respawn does not switch the player to spectator.
- A player who is dead with a redirected respawn point when the server shuts down keeps that point.

## License

[MIT](LICENSE)
