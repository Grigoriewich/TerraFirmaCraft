/*
 * Work under Copyright. Licensed under the EUPL.
 * See the project README.md and LICENSE.txt for more information.
 */

package net.dries007.tfc.tests;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import net.dries007.tfc.Artist;
import net.dries007.tfc.TerraFirmaCraft;
import net.dries007.tfc.world.chunkdata.ChunkDataProvider;
import net.dries007.tfc.world.chunkdata.IChunkDataProvider;
import net.dries007.tfc.world.feature.TFCFeatures;
import net.dries007.tfc.world.feature.trees.ForestConfig;
import net.dries007.tfc.world.noise.INoise2D;
import net.dries007.tfc.world.noise.SimplexNoise2D;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Disabled
class ForestNoiseTests
{
    static final Artist.Noise<INoise2D> NOISE = Artist.<INoise2D>forNoise(target -> (x, y) -> target.noise((float) x, (float) y)).scale(Artist.Scales.DYNAMIC_RANGE).color(Artist.Colors.LINEAR_BLUE_RED).center(20_000);
    static final Artist.Raw COLOR = Artist.raw().center(20_000);

    @Test
    void testTreeDistributions()
    {
        long seed = System.currentTimeMillis();

        ChunkDataProvider provider = (ChunkDataProvider) IChunkDataProvider.getOrThrow();
        ConfiguredFeature<?, ?> forestFeature = ServerLifecycleHooks.getCurrentServer().registryAccess().registryOrThrow(Registry.CONFIGURED_FEATURE_REGISTRY).get(new ResourceLocation(TerraFirmaCraft.MOD_ID, "forest"));

        assertNotNull(forestFeature);
        assertSame(forestFeature.feature(), TFCFeatures.FOREST.get());
        assertTrue(forestFeature.config() instanceof ForestConfig);

        INoise2D temperature = provider.getTemperatureNoise();
        INoise2D rainfall = provider.getRainfallNoise();

        ForestConfig forestConfig = (ForestConfig) forestFeature.config();
        Random random = new Random(seed);
        Map<ForestConfig.Entry, Color> colorPalette = forestConfig.getEntries().stream().collect(Collectors.toMap(x -> x, x -> new Color(100 + random.nextInt(155), 100 + random.nextInt(155), 100 + random.nextInt(155))));

        COLOR.draw("forest_all_possible_trees", (x, z) -> {
            float temp = temperature.noise((float) x, (float) z);
            float rain = rainfall.noise((float) x, (float) z);

            List<ForestConfig.Entry> possibleTrees = new ArrayList<>();
            for (ForestConfig.Entry t : forestConfig.getEntries())
            {
                if (t.isValid(rain, temp))
                {
                    possibleTrees.add(t);
                }
            }

            ForestConfig.Entry entry = possibleTrees.isEmpty() ? null : possibleTrees.get(random.nextInt(possibleTrees.size()));
            if (entry != null)
            {
                return colorPalette.get(entry);
            }
            return Color.BLACK;
        });
    }

    @Test
    void testForestBaseNoise()
    {
        long seed = System.currentTimeMillis();
        INoise2D forestBase = new SimplexNoise2D(seed).octaves(4).spread(0.002f).abs();

        Color darkGreen = new Color(0, 100, 0);
        Color midGreen = new Color(0, 160, 0);
        Color midGreenGray = new Color(120, 150, 120);
        Color gray = new Color(140, 140, 140);

        COLOR.draw("forest_base", (x, z) -> {
            float value = forestBase.noise((float) x, (float) z);
            if (value > 0.4) return darkGreen;
            if (value > 0.18) return midGreen;
            if (value > 0.06) return midGreenGray;
            return gray;
        });
    }

    @Test
    void testForestDensityNoise()
    {
        ChunkDataProvider provider = (ChunkDataProvider) IChunkDataProvider.getOrThrow();

        NOISE.draw("forest_density", provider.getForestDensityNoise());
    }
}