package dev.winter.seedlod.api;

/**
 * Entry point for mods that change world generation and want Seed LOD's distant terrain to match. List the class
 * under the {@code "seedlod"} entrypoint in {@code fabric.mod.json}:
 *
 * <pre>{@code
 * "entrypoints": {
 *   "seedlod": ["com.example.mymod.compat.MySeedLodPlugin"]
 * }
 * }</pre>
 *
 * <p>Fabric only loads the class when Seed LOD is installed, so a mod can ship it without depending on Seed LOD.
 * {@link #register} is called once, the first time Seed LOD generates anything (after every mod has initialized), on
 * whichever thread that happens.
 *
 * @see <a href="https://github.com/Derpedyea/seedlod/blob/main/docs/api.md">docs/api.md</a>
 */
@FunctionalInterface
public interface SeedLodPlugin {
	void register(SeedLodRegistrar registrar);
}
