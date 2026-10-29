# Seed LOD

Fills Voxy's distant terrain straight from the world seed, without generating chunks. Written from scratch for the
Winter server pack (Minecraft 26.3, Fabric).

## How it works

- Samples the dimension's own `final_density` on a grid of 1 to 16 blocks per voxel, so datapack and mod terrain
  (oldworld) come out as they would in game. For 4+ block voxels vanilla's `interpolated` wrappers are stripped; they
  only sample cheaply at their own cell size.
- Runs the dimension's material (surface) rules on the top voxels: grass, sand, snow, badlands bands, mod biomes.
- Adds trees from each biome's own vegetation features (count, height, crown shape, leaf and log blocks).
- Writes each area at the level of detail Voxy will draw it at, worked out from your window size, field of view and
  Voxy's subdivision setting, down to one block per voxel close up. Level 4 covers the whole render distance first,
  then everything sharpens nearest-first. Close up, levels 0-1 use vanilla's interpolated density, so terrain matches
  real chunks; trees are carved with their crown shape. Further out, canopy follows Voxy's own mipping rule.
- When real chunks arrive next to generated data, the coarse sections are split so no holes show, and stand-ins
  are replaced by generated detail later.
- Use it instead of a Voxy worldgen mod (Voxygen): both at once fight over the CPU.

## Singleplayer and servers

Singleplayer works on its own. On a server, put the same jar on the server too: it sends joining players the seed,
which datapacks are enabled and each dimension's generator. The client rebuilds the worldgen from its own mods and
only starts if a test tile matches the server's exactly. Players without the mod are unaffected.

## Config (`config/seedlod.json`)

- `threads`: generation threads, 0 = half the cores minus one. They run at a lower OS priority on Linux.
- `detail`: 1 generates what Voxy draws; lower fills faster and is coarser up close.
- `finestLevel`: 0 is block detail; 1 or 2 skips the finest level(s) for speed.

## Commands

- `/seedlod status`: progress.
- `/seedlod regen`: forget what's been generated and do it again.

## Build

`./gradlew build`. Voxy and Sodium aren't on a maven: copy their jars from the pack into `libs/` (see `build.gradle`).

## License

[MIT](LICENSE). Voxy and Sodium are all rights reserved and aren't included; the build only compiles against
your own copies.
