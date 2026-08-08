# PlayerSync

Paper 1.21.x plugin that synchronises player data across the servers of a network.

A player who leaves one server and joins another arrives with their inventory, state,
experience, effects, statistics and advancements exactly as they left them.

## Requirements

Java 21, Paper 1.21.x, MongoDB 4.4+, and Redis (mandatory behind a proxy).

## Setup

Drop the jar into `plugins/`, start once to generate `config.yml`, fill in the connection
and server-id, and restart.

```yaml
storage:
  mongo:
    uri: "mongodb://user:password@host:27017/?authSource=admin"
    database: playersync
    collection: players

sync:
  server-id: "lobby-1"   # unique per server: the session lock depends on it
```

To try it locally, `docker compose up -d` brings up MongoDB (27017), Redis (6379) and
mongo-express (8081) already matching the default `config.yml`, so the plugin connects
without touching anything.

## What is synchronised

| Block            | Contents                                                                                                                        |
| ---              | ---                                                                                                                             |
| **Profile**      | UUID, last name, first login, last seen, last server                                                                            |
| **State**        | Health and max health, food, saturation, exhaustion, experience, game mode, flight, speeds, fire, fall distance, potion effects |
| **Locations**    | Last location, per-world location, respawn point                                                                                |
| **Inventory**    | Main inventory, off hand, armor, held slot, ender chest                                                                         |
| **Statistics**   | Configurable list of statistics                                                                                                 |
| **Advancements** | Awarded criteria of each advancement, recipes always excluded                                                                   |

## Session lock

Each document carries a `lock` subdocument naming the server that holds it. That is what
stops a player joining a second server with a stale inventory and duplicating their items.
The lock is a lease, so a server that dies does not strand anyone.

Redis is mandatory behind a proxy because the proxy connects the player to the target server
while the origin still has them, and neither side can progress until one is told to release.

### Safeguards

- **A lock won't strand a player.** It is a lease, not a latch: once `lease-duration-ms`
  passes without a renewal, any server may take it, so a crashed server releases everything
  it held on its own. If a proxy transfer starts and then fails, the origin notices the
  player is still there and takes ownership back.
- **Stale data never overwrites good data.** Every write is conditional on this server still
  holding the lock, so a write that arrives late, even after the player already moved elsewhere,
  matches nothing and is discarded instead of overwriting. 
- **A player is never let in without their data.** If the lock cannot be taken in time, the
  login is rejected. Joining with an empty inventory would be worse: the next save would
  write that emptiness over the real data. 
- **A MongoDB outage does not lose sessions.** The final write of a disconnecting player is
  the one that matters, so those are held in a queue and retried until MongoDB is
  back.
- **No lag spikes.** The periodic save does not capture every online player at once, it
  runs every second and takes only the share due, covering everyone exactly once per
  interval. Leases renew three times per duration, so one lag spike does not lose them.

## Extras

Beyond the required scope:

- **Session lock** with a lease, plus a Redis handshake for instant proxy transfers, see below.
- **Ender chest**, respawn point and per-world locations
- **Admin command** `/playersync info | save | unlock`, with per-subcommand permissions.
- **API events** for other plugins: `PlayerDataLoadEvent`, `PlayerDataApplyEvent`, `PlayerDataSaveEvent` (mutable snapshot, saves cancellable).
- **Retry queue** for writes that fail while MongoDB is down; retried shutdown write.
- **Staggered auto-save** spread across the interval, no lag spikes.
- **Per-field toggles** in `config.yml`, down to individual inventory pieces.
- **`docker compose up -d`** for a ready-to-use local environment.
- **Schema version** stored in every document for future migrations.