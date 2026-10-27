package dev.winter.seedlod.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;

/**
 * How far out each level of detail is worth generating, by the rule Voxy's renderer uses to pick a level: it splits
 * a section into its children once the section covers more than {@code subDivisionSize}² pixels on screen. That
 * depends on the window height and field of view, so a 4K screen wants detail about twice as far as 1080p.
 *
 * @param reach blocks from the camera, indexed by level; 0 means the level isn't generated
 */
record DetailRule(int[] reach) {
	/**
	 * Voxy sums the projected area of all six faces of a section's cube, about 2.8x its side squared for a cube seen
	 * at a typical angle, so it splits when 2.8 (side * focal / distance)² > subDivisionSize², i.e. inside
	 * 1.67 * side * focal / subDivisionSize.
	 */
	private static final double SPLIT = Math.sqrt(2.8);

	static DetailRule current(Minecraft mc, SeedLodConfig config) {
		int renderReach = (int) (VoxyConfig.CONFIG.sectionRenderDistance * (32 << WorldEngine.MAX_LOD_LAYER));
		double focal = mc.getWindow().getHeight() / 2.0 / Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
		int[] reach = new int[WorldEngine.MAX_LOD_LAYER + 1];
		reach[WorldEngine.MAX_LOD_LAYER] = renderReach;
		for (int lvl = WorldEngine.MAX_LOD_LAYER - 1; lvl >= config.finestLevel; lvl--) {
			// A level is drawn wherever its parent level gets split.
			int parentSide = 32 << (lvl + 1);
			double split = SPLIT * parentSide * focal / VoxyConfig.CONFIG.subDivisionSize * config.detail;
			reach[lvl] = (int) Math.min(split, renderReach);
		}
		return new DetailRule(reach);
	}
}
