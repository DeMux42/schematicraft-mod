package com.schematicraft.fixtures;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * Builds the committed test fixture from code, so the fixture is never a hand
 * authored or user supplied file. The generator is the single source of truth
 * for what the fixture contains; the drift test (TestFixtureDriftTest)
 * regenerates it in memory and fails if the committed file disagrees.
 *
 * The fixture is the smallest Minecraft structure template that still exercises
 * the three fields a loader reads on a round trip: size, a palette, and a
 * blocks list keyed into that palette. It is a 2x1x2 platform of
 * minecraft:stone, with no block entities and no entities. The structure
 * template NBT shape (size / palette / blocks / entities / DataVersion) is the
 * vanilla StructureTemplate format, so any loader that reads a vanilla
 * structure reads this.
 *
 * The DataVersion is the Minecraft the mod is built against, read from
 * SharedConstants by the caller. SharedConstants can only initialise inside the
 * FML runtime the NeoForge JUnit extension sets up, so this class takes the
 * DataVersion as a parameter and does not reference SharedConstants at class
 * init; the test and the regeneration path, which both run under FML, supply
 * it via dataVersion().
 */
public final class TestFixtureGenerator {

    /** Fixture path relative to the test resources root. */
    public static final String FIXTURE_RESOURCE = "fixtures/schematicraft_test_platform.nbt";

    private TestFixtureGenerator() {
    }

    /** The DataVersion of the Minecraft the mod is built against. */
    public static int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /**
     * Build the fixture structure template NBT in memory for a given
     * DataVersion. Deterministic: the same DataVersion always produces the same
     * tag, so the drift test can compare it against the committed file.
     */
    public static CompoundTag build(int dataVersion) {
        CompoundTag root = new CompoundTag();

        // size: 2 wide, 1 tall, 2 deep
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(2));
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(2));
        root.put("size", size);

        // palette: a single state, minecraft:stone (no properties)
        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        root.put("palette", palette);

        // blocks: the four cells of the 2x1x2 platform, all palette index 0,
        // emitted in a fixed (x, then z) order so the list is stable
        ListTag blocks = new ListTag();
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                CompoundTag block = new CompoundTag();
                ListTag pos = new ListTag();
                pos.add(IntTag.valueOf(x));
                pos.add(IntTag.valueOf(0));
                pos.add(IntTag.valueOf(z));
                block.put("pos", pos);
                block.putInt("state", 0);
                blocks.add(block);
            }
        }
        root.put("blocks", blocks);

        // entities: none
        root.put("entities", new ListTag());

        // DataVersion: the version the mod is built against
        root.putInt("DataVersion", dataVersion);

        return root;
    }

    /** Build for the Minecraft the mod is built against. */
    public static CompoundTag build() {
        return build(dataVersion());
    }

    /**
     * Write the fixture to a file with vanilla NbtIo.writeCompressed, the same
     * gzip framing a loader expects. Used to (re)materialise the committed file;
     * the drift test does not depend on gzip byte identity, it compares the
     * decompressed tag.
     */
    public static void writeTo(Path file, int dataVersion) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            NbtIo.writeCompressed(build(dataVersion), out);
        }
    }
}
