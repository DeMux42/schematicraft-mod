package com.schematicraft.fixtures;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

/**
 * Builds the committed test fixture in code, so it can never drift from the
 * structure it is meant to be. Mirrors the NeoForge and Forge pieces' fixture:
 * the smallest vanilla structure that still exercises what an editor hand-off
 * reads on a round trip.
 *
 * The fixture is a 2x1x2 platform of minecraft:stone in the vanilla
 * StructureTemplate NBT shape (size / palette / blocks / entities / DataVersion),
 * no block entities, no entities. Any loader that reads a vanilla structure reads
 * this. The DataVersion is read from the toolchain
 * (SharedConstants.getGameVersion().getSaveVersion().getId()), which is 3955 for
 * Minecraft 1.21.1, so the fixture tracks the Minecraft version the mod builds
 * against rather than carrying a copied literal.
 */
public final class TestFixtureGenerator {
    private TestFixtureGenerator() {}

    public static final String FIXTURE_RESOURCE = "fixtures/schematicraft_test_platform.nbt";

    /** The 2x1x2 stone platform, as a vanilla structure template tag. */
    public static NbtCompound build() {
        NbtCompound root = new NbtCompound();

        // size: [2, 1, 2]
        NbtList size = new NbtList();
        size.add(NbtInt.of(2));
        size.add(NbtInt.of(1));
        size.add(NbtInt.of(2));
        root.put("size", size);

        // palette: one entry, minecraft:stone (no properties).
        NbtList palette = new NbtList();
        NbtCompound stone = new NbtCompound();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        root.put("palette", palette);

        // blocks: the four positions of the 2x1x2 platform, all palette index 0.
        NbtList blocks = new NbtList();
        int[][] positions = {{0, 0, 0}, {1, 0, 0}, {0, 0, 1}, {1, 0, 1}};
        for (int[] p : positions) {
            NbtCompound block = new NbtCompound();
            NbtList pos = new NbtList();
            pos.add(NbtInt.of(p[0]));
            pos.add(NbtInt.of(p[1]));
            pos.add(NbtInt.of(p[2]));
            block.put("pos", pos);
            block.putInt("state", 0);
            blocks.add(block);
        }
        root.put("blocks", blocks);

        // entities: empty, as a vanilla structure requires the key present.
        root.put("entities", new NbtList());

        // DataVersion: read from the toolchain, not a copied literal.
        root.putInt("DataVersion", dataVersion());

        return root;
    }

    /** Minecraft's own save data version for the version the mod builds against. */
    public static int dataVersion() {
        return SharedConstants.getGameVersion().getSaveVersion().getId();
    }
}
