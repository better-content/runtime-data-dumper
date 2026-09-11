# Runtime Data Dumper

Server-authoritative Forge 1.20.1 diagnostic mod. An operator can run
`/runtimedata dump` to export the final live recipe manager, registries, tags,
effective loot tables, sampled effective villager offers, worldgen registries,
lighting, loaded dimensions, the live Creating Space rocket-accessible-dimension registry,
and mod list under `generated/runtime-dumps/` in the server directory. Every
file shares one snapshot ID so offline tooling can reject mixed evidence.

`dimensions.json` uses `bc.dimensions.v1`. It records sorted loaded dimension IDs and, when
Creating Space is loaded, the sorted contents of its dynamic
`creatingspace:rocket_accessible_dimension` registry. A missing registry makes the surface and
aggregate snapshot incomplete instead of silently producing an empty inventory.

`lighting.json` enumerates every live registered block state and fluid with positive
light emission. When Sodium Dynamic Lights is loaded, it also records automatic
luminous block items and accepted portable-item declarations from loaded mod
resources. Missing compatibility targets are retained as rejected diagnostics.
Portable results do not prove a particular client's configuration, resource-pack
overrides, entity/spell lighting, or shader-only emissive effects.

Trade output is explicitly sampled evidence: each effective listing is invoked
with 16 deterministic seeds for every villager type. It preserves dynamic
listing classes and representative offer NBT without claiming that a finite
sample enumerates every possible randomized offer.

`snapshot.json` and `completion.json` (`bc.runtime_dump_completion.v3`) are complete only when
every live recipe has a fully normalized
machine edge, every serializer payload succeeds, and the exact loot/worldgen
plus sampled-trade export contracts report no errors. Incomplete rows and raw
serializer payloads are retained for adapter work, but the command returns a
failure result and pack tooling must not promote or fingerprint that snapshot
as authoritative. Worldgen registry data proves configured live state, not
placement frequency or occurrence in a generated world; loaded loot tables
likewise do not prove that their runtime context is reachable.

Optional recipe adapters only emit edges backed by live accessors or pinned
serializer state. In addition to item and fluid edges, the graph can therefore
name block transformations, TConstruct materials/modifiers/modifier slots, and
enchantments without pretending those state transitions are ordinary items.
Context-dependent rules use an explicit `operation_kind`, typed `effects`, and
typed `requirements`: Blood Magic flask state changes, TConstruct material-cost
melting and recycling/tool mutations, and AE2 matter-cannon ammo profiles are
therefore navigable without fake static outputs. The same schema represents
Malum spirit repairs, PneumaticCraft heat/fuel properties, Ars scry mappings,
Blood Magic living-armour mutations, and Goety brewing/soul operations.
AlmostUnified client recipe trackers are explicit non-gameplay metadata
exclusions carrying their exact linked recipe flags, never fabricated crafting
edges. Dynamic special crafting is represented by typed runtime selectors and
copy/mutation effects; placement policies, meteor world effects, smeltery fuel
profiles, and modifier application/removal likewise remain distinct from
ordinary static item transforms. Clockwork gas crafting retains exact gas-mass
maps, reaction requirements, and energy, while TConstruct part swapping and
modifier-set worktable recipes retain their live tool predicates and runtime
mutation semantics. Modded state-copy, dye, binding, repair, and container
recipes use the same runtime-selector model. Fluid brewing, uncrafting result
grids, entity/ritual effects, entropy world-state changes, and intentional
outputless processing are represented as their own typed operations.

The command is intentionally the only trigger. The mod performs no work during
startup, reload, or player synchronization.

`/runtimedata combat` separately exports `combat-profile.json`. It samples the
default armor, toughness, and health of every constructible entity identified
by either the hostile mob category or Minecraft's Enemy contract,
excludes 100-health boss-class entries from percentile selection, and derives
the pack's Trash/Elite/Boss armor representatives at P50/P75/P90. It changes no
entities and performs no automatic difficulty scaling.

Build, test, and stage the reobfuscated runtime JAR with:

```sh
./gradlew build stageRuntimeJar
```

## Canonical identity

- Repository and release artifact: `runtime-data-dumper`
- Mod ID and resource namespace: `runtime_data_dumper`
- Java package: `com.bettercontent.runtimedatadumper`
- Validation: `./gradlew build`

This normalization is a clean break. Worlds, configuration files, and integrations created for earlier identities are not migrated or aliased.
