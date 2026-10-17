package com.schematicraft.fixtures;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regenerate-on-drift guard for the committed test fixture, the Fabric form of
 * the NeoForge and Forge fixture pattern. It rebuilds the fixture in memory from
 * TestFixtureGenerator and fails if the committed file disagrees, so the fixture
 * can never be hand edited or drift from the code that produces it. It also loads
 * the committed file as a vanilla StructureTemplate, offline, with no client and
 * no server, proving it is a valid loadable structure an editor can receive.
 *
 * Runs on fabric-loader-junit, which boots the Fabric loader and lets the vanilla
 * registries bootstrap headlessly, the same harness the engine's Litematica
 * conformance uses on this host.
 *
 * Regeneration: gradlew test -PregenerateTestFixture rewrites the committed file
 * from the generator. Idempotent; a clean git status after a run proves the
 * committed file already matched.
 */
public class TestFixtureDriftTest {

    private static final Path COMMITTED =
            Path.of("src/test/resources/" + TestFixtureGenerator.FIXTURE_RESOURCE);

    @BeforeAll
    static void boot() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void committedFixtureMatchesTheGenerator() throws IOException {
        if (Boolean.getBoolean("regenerateTestFixture")) {
            Files.createDirectories(COMMITTED.getParent());
            NbtIo.writeCompressed(TestFixtureGenerator.build(), COMMITTED);
            System.out.println("Regenerated " + COMMITTED);
        }

        assertTrue(Files.exists(COMMITTED),
                "committed fixture missing: " + COMMITTED
                        + " (run gradlew test -PregenerateTestFixture)");

        NbtCompound committed = readCompressed(Files.readAllBytes(COMMITTED));
        NbtCompound regenerated = TestFixtureGenerator.build();

        // Compare the decompressed tags, not the gzip bytes, so a different gzip
        // encoder cannot cause a false drift while a real content change always does.
        assertEquals(regenerated, committed,
                "the committed fixture has drifted from TestFixtureGenerator; "
                        + "run gradlew test -PregenerateTestFixture to rewrite it");
    }

    @Test
    void fixtureLoadsAsAStructureTemplate() throws IOException {
        NbtCompound tag = readCompressed(Files.readAllBytes(COMMITTED));

        StructureTemplate template = new StructureTemplate();
        template.readNbt(Registries.BLOCK.getReadOnlyWrapper(), tag);

        Vec3i size = template.getSize();
        assertNotNull(size, "structure has no size");
        assertEquals(2, size.getX(), "fixture X extent");
        assertEquals(1, size.getY(), "fixture Y extent");
        assertEquals(2, size.getZ(), "fixture Z extent");
    }

    @Test
    void dataVersionTracksTheToolchain() {
        // The fixture's DataVersion is the toolchain's, not a copied literal.
        NbtCompound regenerated = TestFixtureGenerator.build();
        assertEquals(SharedConstants.getGameVersion().getSaveVersion().getId(),
                regenerated.getInt("DataVersion"),
                "fixture DataVersion must match the build's Minecraft version");
    }

    private static NbtCompound readCompressed(byte[] bytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }
    }

    // Kept for parity with the generator's byte path; not used by the asserts.
    private static byte[] writeCompressed(NbtCompound tag) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(tag, out);
            return out.toByteArray();
        }
    }
}
