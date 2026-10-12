package dev.skycraft.worlds;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Home's chunk generator ("skycraft:classic" in data/skycraft/dimension/home.json). Terrain comes from
 * {@link HomeTerrain} with Steve's seed, so it ignores the world seed and Minecraft's noise; trees, flowers
 * and ores come from the biome as usual, except around the camp, which {@link HomeCamp} builds.
 */
public final class HomeChunkGenerator extends ChunkGenerator {
	public static final MapCodec<HomeChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource)
	).apply(i, HomeChunkGenerator::new));

	private static final HomeTerrain TERRAIN = new HomeTerrain(HomeTerrain.SEED);
	private static final BlockState[] STATES = new BlockState[HomeTerrain.Block.values().length];

	static {
		STATES[HomeTerrain.Block.AIR.ordinal()] = Blocks.AIR.defaultBlockState();
		STATES[HomeTerrain.Block.STONE.ordinal()] = Blocks.STONE.defaultBlockState();
		STATES[HomeTerrain.Block.DIRT.ordinal()] = Blocks.DIRT.defaultBlockState();
		STATES[HomeTerrain.Block.GRASS.ordinal()] = Blocks.GRASS_BLOCK.defaultBlockState();
		STATES[HomeTerrain.Block.SAND.ordinal()] = Blocks.SAND.defaultBlockState();
		STATES[HomeTerrain.Block.GRAVEL.ordinal()] = Blocks.GRAVEL.defaultBlockState();
		STATES[HomeTerrain.Block.WATER.ordinal()] = Blocks.WATER.defaultBlockState();
		STATES[HomeTerrain.Block.BEDROCK.ordinal()] = Blocks.BEDROCK.defaultBlockState();
	}

	public HomeChunkGenerator(BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structures,
		BiomeManager biomes, WorldGenRegion region, Set<Holder<Biome>> biomeSet) {
		ChunkPos pos = chunk.getPos();
		HomeTerrain.Block[] blocks = TERRAIN.chunk(pos.x(), pos.z());
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int y = 0; y < HomeTerrain.TOP; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					HomeTerrain.Block block = blocks[(y * 16 + z) * 16 + x];
					if (block == HomeTerrain.Block.AIR) {
						continue;
					}
					BlockState state = STATES[block.ordinal()];
					chunk.setBlockState(at.set(x, HomeTerrain.MIN_Y + y, z), state);
					oceanFloor.update(x, HomeTerrain.MIN_Y + y, z, state);
					worldSurface.update(x, HomeTerrain.MIN_Y + y, z, state);
				}
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
		ChunkPos pos = chunk.getPos();
		boolean nearCamp = Math.abs(pos.x() - SteveCamp.anchorChunkX()) <= 2 && Math.abs(pos.z() - SteveCamp.anchorChunkZ()) <= 2;
		if (!nearCamp) {
			super.applyBiomeDecoration(level, chunk, structures);
		}
		if (pos.x() == SteveCamp.anchorChunkX() && pos.z() == SteveCamp.anchorChunkZ()) {
			HomeCamp.build(level);
		}
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		ChunkPos center = region.getCenter();
		BlockPos top = center.getWorldPosition().atY(region.getMaxY());
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		random.setDecorationSeed(region.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
		NaturalSpawner.spawnMobsForChunkGeneration(region, top, center, random);
	}

	@Override
	public int getGenDepth() {
		return HomeTerrain.HEIGHT;
	}

	@Override
	public int getSeaLevel() {
		return HomeTerrain.SEA_LEVEL;
	}

	@Override
	public int getMinY() {
		return HomeTerrain.MIN_Y;
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		HomeTerrain.Block[] column = TERRAIN.column(x, z);
		for (int y = HomeTerrain.TOP - 1; y >= 0; y--) {
			if (type.isOpaque().test(STATES[column[y].ordinal()])) {
				return HomeTerrain.MIN_Y + y + 1;
			}
		}
		return heightAccessor.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		HomeTerrain.Block[] column = TERRAIN.column(x, z);
		BlockState[] states = new BlockState[heightAccessor.getHeight()];
		for (int i = 0; i < states.length; i++) {
			int y = heightAccessor.getMinY() + i - HomeTerrain.MIN_Y;
			states[i] = y >= 0 && y < HomeTerrain.TOP ? STATES[column[y].ordinal()] : STATES[HomeTerrain.Block.AIR.ordinal()];
		}
		return new NoiseColumn(heightAccessor.getMinY(), states);
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos, SamplerContext context) {
		info.add("Home: Steve's seed" + (FarLands.isFar(pos.getX(), pos.getZ()) ? ", Far Lands" : ""));
	}
}
