package dev.winter.seedlod.gen;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.Arrays;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Exact O(N) AABB replacement for vanilla Jigsaw's O(N³) fragmented {@code VoxelShape} during Seed LOD structure
 * generation. Represents {@code outer \ (cut_0 ∪ cut_1 ∪ ... ∪ cut_{k-1})}: testing {@code ONLY_SECOND(this, box)}
 * is equivalent to checking whether {@code box} extends outside {@code outer} or intersects any cutout.
 */
public final class JigsawBounds extends VoxelShape {
	private static final BitSetDiscreteVoxelShape DUMMY = new BitSetDiscreteVoxelShape(1, 1, 1);
	private static final DoubleList ZERO_ONE = DoubleArrayList.of(0.0, 1.0);

	private final double oMinX, oMinY, oMinZ, oMaxX, oMaxY, oMaxZ;
	/** Packed cutouts: 6 doubles per box (minX, minY, minZ, maxX, maxY, maxZ). */
	private double[] cuts = new double[6 * 32];
	private int len;

	public JigsawBounds(AABB outer) {
		super(DUMMY);
		this.oMinX = outer.minX;
		this.oMinY = outer.minY;
		this.oMinZ = outer.minZ;
		this.oMaxX = outer.maxX;
		this.oMaxY = outer.maxY;
		this.oMaxZ = outer.maxZ;
	}

	public void subtract(AABB box) {
		if (this.len + 6 > this.cuts.length) this.cuts = Arrays.copyOf(this.cuts, this.cuts.length * 2);
		int i = this.len;
		this.cuts[i] = box.minX;
		this.cuts[i + 1] = box.minY;
		this.cuts[i + 2] = box.minZ;
		this.cuts[i + 3] = box.maxX;
		this.cuts[i + 4] = box.maxY;
		this.cuts[i + 5] = box.maxZ;
		this.len = i + 6;
	}

	/** Equivalent to {@code Shapes.joinIsNotEmpty(this, Shapes.create(box), BooleanOp.ONLY_SECOND)}. */
	public boolean conflicts(AABB box) {
		if (box.minX < this.oMinX || box.maxX > this.oMaxX
			|| box.minY < this.oMinY || box.maxY > this.oMaxY
			|| box.minZ < this.oMinZ || box.maxZ > this.oMaxZ) {
			return true;
		}
		double[] c = this.cuts;
		for (int i = 0, n = this.len; i < n; i += 6) {
			if (box.minX < c[i + 3] && box.maxX > c[i]
				&& box.minY < c[i + 4] && box.maxY > c[i + 1]
				&& box.minZ < c[i + 5] && box.maxZ > c[i + 2]) {
				return true;
			}
		}
		return false;
	}

	@Override
	public DoubleList getCoords(Direction.Axis axis) {
		return ZERO_ONE;
	}
}
