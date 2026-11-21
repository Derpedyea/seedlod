package dev.winter.seedlod.gen;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

/**
 * Concrete {@link WorldGenLevel} base shared by {@link RealTrees} and {@link StructureBaker}. Avoids
 * {@link java.lang.reflect.Proxy} reflection and boxing overhead in hot block/height loops.
 */
abstract class StagedLevel implements WorldGenLevel, RealTrees.Staged {
	protected final WorldgenView view;
	protected final BiomeResolver biomes;
	protected final BiomeManager biomeManager;
	protected final RandomSource random = RandomSource.create(0);
	private final WorldBorder border = new WorldBorder();
	private final DifficultyInstance difficulty = new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0.0F);

	StagedLevel(WorldgenView view, BiomeResolver biomes) {
		this.view = view;
		this.biomes = biomes;
		this.biomeManager = new BiomeManager(biomes, BiomeManager.obfuscateSeed(view.seed));
	}

	@Override
	public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
		return this.setBlock(pos, Blocks.AIR.defaultBlockState(), 0, 0);
	}

	@Override
	public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
		return this.setBlock(pos, Blocks.AIR.defaultBlockState(), 0, recursionLeft);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return this.getBlockState(pos).getFluidState();
	}

	@Override
	public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
		return predicate.test(this.getBlockState(pos));
	}

	@Override
	public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
		return predicate.test(this.getFluidState(pos));
	}

	@Override
	public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
		return new BlockPos(pos.getX(), this.getHeight(type, pos.getX(), pos.getZ()), pos.getZ());
	}

	@Override
	public int getMinY() {
		return this.view.minY;
	}

	@Override
	public int getHeight() {
		return this.view.height;
	}

	@Override
	public int getSeaLevel() {
		return this.view.seaLevel;
	}

	@Override
	public long getSeed() {
		return this.view.seed;
	}

	@Override
	public RandomSource getRandom() {
		return this.random;
	}

	@Override
	public RegistryAccess registryAccess() {
		return this.view.registries;
	}

	@Override
	public FeatureFlagSet enabledFeatures() {
		return FeatureFlags.DEFAULT_FLAGS;
	}

	@Override
	public BiomeManager getBiomeManager() {
		return this.biomeManager;
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int qx, int qy, int qz) {
		return this.biomes.getNoiseBiome(qx, qy, qz);
	}

	@Override
	public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
		return this.biomes.getNoiseBiome(qx, qy, qz);
	}

	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		return layer == LightLayer.SKY ? 15 : 0;
	}

	@Override
	public int getRawBrightness(BlockPos pos, int amount) {
		return Math.max(0, 15 - amount);
	}

	@Override
	public boolean canSeeSky(BlockPos pos) {
		return true;
	}

	@Override
	public boolean isClientSide() {
		return false;
	}

	@Override
	public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
		return this.difficulty;
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
		return Optional.empty();
	}

	@Override
	public boolean addFreshEntity(Entity entity) {
		return false;
	}

	@Override
	public void addFreshEntityWithPassengers(Entity entity) {
	}

	@Override
	public LevelTickAccess<Block> getBlockTicks() {
		return BlackholeTickAccess.emptyLevelList();
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return BlackholeTickAccess.emptyLevelList();
	}

	@Override
	public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, TickPriority priority) {
		return new ScheduledTick<>(type, pos, 0L, priority, 0L);
	}

	@Override
	public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) {
		return new ScheduledTick<>(type, pos, 0L, TickPriority.NORMAL, 0L);
	}

	@Override
	public long nextSubTickCount() {
		return 0L;
	}

	@Override
	public int getSkyDarken() {
		return 0;
	}

	@Override
	public WorldBorder getWorldBorder() {
		return this.border;
	}

	@Override
	public @Nullable BlockGetter getChunkForCollisions(int cx, int cz) {
		return this;
	}

	@Override
	public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
		return List.of();
	}

	@Override
	public List<Entity> getEntities(@Nullable Entity except, AABB box, Predicate<? super Entity> predicate) {
		return List.of();
	}

	@Override
	public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB box, Predicate<? super T> predicate) {
		return List.of();
	}

	@Override
	public List<? extends Player> players() {
		return List.of();
	}

	@Override
	public boolean hasChunk(int cx, int cz) {
		return true;
	}

	@Override
	public @Nullable ChunkAccess getChunk(int cx, int cz, ChunkStatus status, boolean create) {
		return null;
	}

	@Override
	public LevelLightEngine getLightEngine() {
		throw new UnsupportedOperationException("Seed LOD stage has no light engine");
	}

	@Override
	public DimensionType dimensionType() {
		throw new UnsupportedOperationException("Seed LOD stage has no dimensionType");
	}

	@Override
	public EnvironmentAttributeReader environmentAttributes() {
		return EnvironmentAttributeReader.EMPTY;
	}

	@Override
	public @Nullable LevelData getLevelData() {
		return null;
	}

	@Override
	public @Nullable MinecraftServer getServer() {
		return null;
	}

	@Override
	public ChunkSource getChunkSource() {
		throw new UnsupportedOperationException("Seed LOD stage has no chunk source");
	}

	@Override
	public void playSound(@Nullable Entity source, BlockPos pos, SoundEvent sound, SoundSource category, float volume, float pitch) {
	}

	@Override
	public void addParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {
	}

	@Override
	public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
	}

	@Override
	public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
	}
}
