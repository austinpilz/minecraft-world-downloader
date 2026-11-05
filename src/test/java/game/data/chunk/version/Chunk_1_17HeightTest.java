package game.data.chunk.version;

import game.data.WorldManager;
import game.data.chunk.palette.BlockColors;
import game.data.coordinates.CoordinateDim2D;
import game.data.dimension.Dimension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Bots of a fleet can be in dimensions of different heights at the same time.
 */
class Chunk_1_17HeightTest {
    private static Chunk_1_17 chunkIn(Dimension dimension) {
        WorldManager mock = mock(WorldManager.class);
        when(mock.getBlockColors()).thenReturn(mock(BlockColors.class));
        WorldManager.setInstance(mock);
        return new Chunk_1_17(new CoordinateDim2D(0, 0, dimension), 2730);
    }

    @AfterEach
    void tearDown() {
        Chunk_1_17.setWorldHeight(0, 256);
    }

    @Test
    void everyDimensionKeepsItsOwnHeight() {
        Chunk_1_17.setWorldHeight(Dimension.OVERWORLD, -64, 384);
        Chunk_1_17.setWorldHeight(Dimension.NETHER, 0, 256);

        Chunk_1_17 overworld = chunkIn(Dimension.OVERWORLD);
        Chunk_1_17 nether = chunkIn(Dimension.NETHER);
        assertThat(overworld.getMinBlockSection()).isEqualTo(-4);
        assertThat(overworld.getMaxBlockSection()).isEqualTo(19);
        assertThat(nether.getMinBlockSection()).isEqualTo(0);
        assertThat(nether.getMaxBlockSection()).isEqualTo(15);
    }

    @Test
    void unseenDimensionsUseTheLastHeight() {
        Chunk_1_17.setWorldHeight(Dimension.OVERWORLD, -64, 384);

        assertThat(chunkIn(Dimension.END).getMinBlockSection()).isEqualTo(-4);
    }

    @Test
    void settingTheHeightOfEveryDimensionForgetsTheOthers() {
        Chunk_1_17.setWorldHeight(Dimension.OVERWORLD, -64, 384);
        Chunk_1_17.setWorldHeight(0, 256);

        assertThat(chunkIn(Dimension.OVERWORLD).getMaxBlockSection()).isEqualTo(15);
    }
}
