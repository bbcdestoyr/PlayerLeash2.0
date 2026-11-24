package com.example.playerleash;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A 3x3x3 cage of iron bars around a player's feet. Only air and replaceable
 * blocks are swapped for bars (and put back afterwards); solid blocks that are
 * already part of the shell stay as they are and are protected too.
 */
final class Cage {

    private record Saved(Block block, BlockData data) {}

    private final World world;
    private final int bx, by, bz;
    private final List<Saved> saved = new ArrayList<>();
    private final Set<Block> shell = new HashSet<>();

    private Cage(Location feet) {
        this.world = feet.getWorld();
        this.bx = feet.getBlockX();
        this.by = feet.getBlockY();
        this.bz = feet.getBlockZ();
    }

    static Cage build(Location feet) {
        Cage c = new Cage(feet);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean boundary = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == -1 || dy == 3;
                    if (!boundary) continue;
                    Block b = c.world.getBlockAt(c.bx + dx, c.by + dy, c.bz + dz);
                    c.shell.add(b);
                    if (b.getType().isAir() || b.isReplaceable()) {
                        c.saved.add(new Saved(b, b.getBlockData()));
                        b.setBlockData(barsFor(dx, dy, dz), true);
                    }
                }
            }
        }
        return c;
    }

    private static boolean onShell(int dx, int dy, int dz) {
        if (Math.abs(dx) > 2 || Math.abs(dz) > 2 || dy < -1 || dy > 3) return false;
        return Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == -1 || dy == 3;
    }

    /** Iron bars already joined to their neighbours, so there are no gaps to squeeze through. */
    private static BlockData barsFor(int dx, int dy, int dz) {
        BlockData data = Material.IRON_BARS.createBlockData();
        if (data instanceof MultipleFacing mf) {
            mf.setFace(BlockFace.NORTH, onShell(dx, dy, dz - 1));
            mf.setFace(BlockFace.SOUTH, onShell(dx, dy, dz + 1));
            mf.setFace(BlockFace.EAST, onShell(dx + 1, dy, dz));
            mf.setFace(BlockFace.WEST, onShell(dx - 1, dy, dz));
        }
        return data;
    }

    /** Puts every swapped block back the way it was. */
    void restore() {
        for (int i = saved.size() - 1; i >= 0; i--) {
            Saved s = saved.get(i);
            s.block().setBlockData(s.data(), true);
        }
        saved.clear();
        shell.clear();
    }

    boolean protects(Block b) {
        return shell.contains(b);
    }

    boolean inside(Location l) {
        if (l.getWorld() == null || !l.getWorld().equals(world)) return false;
        return l.getX() >= bx - 1 && l.getX() < bx + 2
                && l.getZ() >= bz - 1 && l.getZ() < bz + 2
                && l.getY() >= by - 0.5 && l.getY() <= by + 3;
    }

    Location center() {
        return new Location(world, bx + 0.5, by, bz + 0.5);
    }
}
