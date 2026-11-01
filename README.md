# Seed LOD

Fills Voxy's distant terrain straight from the world seed, without generating chunks. Written from scratch for the
Winter server pack (Minecraft 26.3, Fabric).

Inspired by [Voxy SeedGen](https://www.curseforge.com/minecraft/mc-mods/voxy-seedgen) by Mobiusmeansbusiness, which
does the same from the seed but isn't open source. Seed LOD is an open-source take on the idea.

## How it works

- Samples the dimension's own `final_density` on a grid of 1 to 16 blocks per voxel, so datapack and mod terrain
  (oldworld) come out as they would in game. For 4+ block voxels vanilla's `interpolated` wrappers are stripped; they
  only sample cheaply at their own cell size.
- Runs the dimension's material (surface) rules on the top voxels: grass, sand, snow, badlands bands, mod biomes.
- Adds trees from each biome's own vegetation features (count, height, crown shape, leaf and log blocks).
- Writes each area at the level of detail Voxy will draw it at, worked out from your window size, field of view and
  Voxy's subdivision setting, down to one block per voxel close up. It fans out from the player at full detail
  instead of laying down a coarse blockout first; the coarser stand-in levels underneath come last. Close up, levels
  0-1 use vanilla's interpolated density, so terrain matches real chunks; trees are carved with their crown shape. Further out, canopy follows Voxy's own mipping rule.
- When real chunks arrive next to generated data, the coarse sections are split so no holes show, and stand-ins
  are replaced by generated detail later.
- Use it instead of a Voxy worldgen mod (Voxygen): both at once fight over the CPU.

## Singleplayer and servers

Singleplayer works on its own. On a server, put the same jar on the server too: it sends joining players the seed,
which datapacks are enabled and each dimension's generator. The client rebuilds the worldgen from its own mods and
only starts if a test tile matches the server's exactly. Players without the mod are unaffected.

## Config (`config/seedlod.json`)

Also in game: Mod Menu → Seed LOD (needs Cloth Config). Saving applies right away.

- `threads`: generation threads, 0 = half the cores minus one. They run at a lower OS priority on Linux.
- `enabled`: generate at all.
- `detail`: 1 generates what Voxy draws; lower (down to 0.1) fills faster and is coarser up close.
- `finestLevel`: 0 is block detail; 1 or 2 skips the finest level(s) for speed.
- `biomeBlend`: blocks that grass, leaf and water colours are dithered across biome borders (0-32, default 8, 0 is
  off). Voxy colours each voxel from one biome, so this is how borders blend instead of stepping. Wider reads
  smoother from afar and grainier up close. Applies to newly generated terrain; `/seedlod regen` redoes the rest.

## Commands

- `/seedlod status`: progress.
- `/seedlod regen`: forget what's been generated and do it again.

## For mod authors

Worldgen from data packs and registries (density functions, surface rules, biomes, vanilla trees) shows up on its
own. Mods that change terrain in code or grow trees with their own feature class can hook in through the `seedlod`
entrypoint, without depending on Seed LOD: see [docs/api.md](docs/api.md).

## Build

`./gradlew build`. Voxy and Sodium aren't on a maven: copy their jars from the pack into `libs/` (see `build.gradle`).

## License

[MIT](LICENSE). Voxy and Sodium are all rights reserved and aren't included; the build only compiles against
your own copies.
