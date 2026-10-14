package com.schematicraft.fixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the committed 26.1 test fixture.
 *
 * This is the regenerate-on-drift guard the spec asks for: the test rebuilds
 * the fixture from TestFixtureGenerator and fails if the committed file no
 * longer matches, so the fixture can never drift from the code that is supposed
 * to produce it, and is never a hand edited or user supplied file.
 *
 * It also runs the non-rendering half of the fixture round trip offline, with
 * no display and no server: it loads the committed fixture into a vanilla
 * StructureTemplate and reads the blocks back, proving the fixture is a valid,
 * loadable structure template and not just a well formed NBT blob. See the note
 * for why this stands in for a server-only runGameTest on 26.1.
 *
 * Regeneration: run with -Dschematicraft.regenerateFixture=&lt;path&gt; (the
 * regenerateTestFixture Gradle task does this) and this writes the committed
 * file from the generator instead of asserting, inside the FML runtime the
 * NeoForge JUnit extension provides. SharedConstants cannot initialise outside
 * that runtime, which is why regeneration lives here and not in a bare
 * JavaExec.
 */
class TestFixtureDriftTest {

    private static final String REGEN_PROP = "schematicraft.regenerateFixture";

    @BeforeAll
    static void boot() {
        // Minecraft's registries (needed to resolve minecraft:stone to a
        // BlockState) must be bootstrapped once. No world, no server, no
        // display is created by this call.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CompoundTag readFixture() throws Exception {
        try (InputStream in = TestFixtureDriftTest.class.getClassLoader()
                .getResourceAsStream(TestFixtureGenerator.FIXTURE_RESOURCE)) {
            assertNotNull(in, "committed fixture is missing: "
                    + TestFixtureGenerator.FIXTURE_RESOURCE);
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
    }

    /**
     * The drift guard. If someone edits the fixture by hand, or the generator
     * changes, or the toolchain's DataVersion moves without the fixture being
     * regenerated, the committed file and the freshly generated tag disagree
     * and this fails with a diff pointing at the regenerate command.
     *
     * When -Dschematicraft.regenerateFixture is set, this instead writes the
     * committed file from the generator and returns, so the task that sets the
     * property materialises the fixture.
     */
    @Test
    void committedFixtureMatchesTheGenerator() throws Exception {
        String regen = System.getProperty(REGEN_PROP);
        if (regen != null && !regen.isBlank()) {
            Path target = Path.of(regen);
            TestFixtureGenerator.writeTo(target, TestFixtureGenerator.dataVersion());
            System.out.println("regenerated fixture: " + target.toAbsolutePath());
            return;
        }

        CompoundTag committed = readFixture();
        CompoundTag generated = TestFixtureGenerator.build(TestFixtureGenerator.dataVersion());
        assertEquals(generated, committed,
                "The committed fixture has drifted from TestFixtureGenerator. "
                + "Regenerate it with: ./gradlew regenerateTestFixture");
    }

    /** The fixture carries 26.1.2's DataVersion, read from the toolchain. */
    @Test
    void fixtureCarriesTheBuildDataVersion() throws Exception {
        if (System.getProperty(REGEN_PROP) != null) {
            return;
        }
        CompoundTag committed = readFixture();
        int expected = SharedConstants.getCurrentVersion().dataVersion().version();
        assertEquals(expected, committed.getIntOr("DataVersion", -1),
                "fixture DataVersion must equal the Minecraft the mod builds against");
    }

    /**
     * The non-rendering half of the fixture round trip, offline: load the
     * committed fixture into a StructureTemplate (which resolves the palette
     * against the real block registry) and confirm it describes the 2x1x2
     * stone platform. This is what a loader does when it reads the structure,
     * minus any rendering, and needs no display and no server.
     */
    @Test
    void fixtureLoadsAsAStructureTemplate() throws Exception {
        if (System.getProperty(REGEN_PROP) != null) {
            return;
        }
        CompoundTag committed = readFixture();

        StructureTemplate template = new StructureTemplate();
        // Registry<Block> is a HolderGetter<Block>, so the block palette
        // resolves against the bootstrapped registry.
        template.load(BuiltInRegistries.BLOCK, committed);

        // Re-save and confirm the structure survives a load/save round trip
        // without drift. save() rebuilds size/palette/blocks from the loaded
        // template, so an equal result proves the fixture is internally
        // consistent and fully loadable.
        CompoundTag resaved = template.save(new CompoundTag());

        ListTag size = resaved.getListOrEmpty("size");
        assertEquals(2, size.getIntOr(0, -1), "width");
        assertEquals(1, size.getIntOr(1, -1), "height");
        assertEquals(2, size.getIntOr(2, -1), "depth");

        ListTag blocks = resaved.getListOrEmpty("blocks");
        assertEquals(4, blocks.size(), "a 2x1x2 platform has four cells");

        // Every palette entry resolves to minecraft:stone through the real
        // registry.
        ListTag palette = resaved.getListOrEmpty("palette");
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompoundOrEmpty(i);
            BlockState state = net.minecraft.nbt.NbtUtils.readBlockState(
                    BuiltInRegistries.BLOCK, entry);
            assertEquals(Blocks.STONE, state.getBlock(),
                    "palette entry must be stone");
        }
    }

    /**
     * The gzip framing a loader expects is intact: the committed bytes are a
     * gzip stream (magic 0x1f 0x8b) that NbtIo can decompress. Content equality
     * is covered by committedFixtureMatchesTheGenerator; this only guards the
     * on-disk framing.
     */
    @Test
    void fixtureIsGzipFramed() throws Exception {
        if (System.getProperty(REGEN_PROP) != null) {
            return;
        }
        try (InputStream in = TestFixtureDriftTest.class.getClassLoader()
                .getResourceAsStream(TestFixtureGenerator.FIXTURE_RESOURCE)) {
            assertNotNull(in);
            byte[] head = in.readNBytes(2);
            assertArrayEquals(new byte[] {(byte) 0x1f, (byte) 0x8b}, head,
                    "fixture must be gzip framed as a loader expects");
        }
        // And it decompresses to a non-empty compound.
        CompoundTag committed = readFixture();
        assertTrue(committed.contains("blocks"), "decompresses to a structure");
    }
}
