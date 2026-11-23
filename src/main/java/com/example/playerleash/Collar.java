package com.example.playerleash;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A baby-pink collar drawn with block displays, so it works for vanilla clients
 * (no resource pack). Four thin strips form a ring at the neck, plus an optional
 * little tag at the front.
 */
final class Collar {

    // Ring geometry, in blocks, relative to the player's feet
    private static final float A = 0.245f;  // half width
    private static final float B = 0.15f;   // half depth
    private static final float T = 0.03f;   // strip thickness
    private static final float H = 0.07f;   // strip height
    private static final float Y_STAND = 1.37f;
    private static final float Y_SNEAK = 1.12f;

    private record Part(boolean tag, float x, float yOff, float z, float sx, float sy, float sz) {}

    private static final List<Part> PARTS = List.of(
            new Part(false, -A, 0f, B - T, 2 * A, H, T),            // front
            new Part(false, -A, 0f, -B, 2 * A, H, T),               // back
            new Part(false, -A, 0f, -B + T, T, H, 2 * B - 2 * T),   // left
            new Part(false, A - T, 0f, -B + T, T, H, 2 * B - 2 * T),// right
            new Part(true, -0.04f, -0.04f, B, 0.08f, 0.08f, 0.02f)  // tag on the front
    );

    private final Material ringBlock;
    private final Material tagBlock;
    private final boolean showTag;
    private final List<BlockDisplay> displays = new ArrayList<>();

    private float bodyYaw;
    private boolean haveYaw;
    private double lastX, lastZ;
    private int mode = -1; // 0 standing, 1 sneaking, 2 hidden

    Collar(Material ringBlock, Material tagBlock, boolean showTag) {
        this.ringBlock = ringBlock;
        this.tagBlock = tagBlock;
        this.showTag = showTag;
    }

    /** Call every tick: keeps the collar on the player's neck. */
    void update(Player pet) {
        Location loc = pet.getLocation();
        updateYaw(loc);

        Pose pose = pet.getPose();
        int newMode = pet.isDead() ? 2 : pose == Pose.STANDING ? 0 : pose == Pose.SNEAKING ? 1 : 2;

        boolean broken = displays.isEmpty();
        for (BlockDisplay d : displays) {
            if (!d.isValid() || !d.getWorld().equals(loc.getWorld())) {
                broken = true;
                break;
            }
        }
        if (broken) {
            remove();
            build(loc);
            mode = -1;
        }
        if (newMode != mode) {
            mode = newMode;
            applyMode();
        }

        Location t = loc.clone();
        t.setYaw(bodyYaw);
        t.setPitch(0f);
        for (BlockDisplay d : displays) {
            d.teleport(t);
        }
    }

    void remove() {
        for (BlockDisplay d : displays) {
            d.remove();
        }
        displays.clear();
    }

    private void build(Location loc) {
        for (Part p : PARTS) {
            if (p.tag() && !showTag) continue;
            Material mat = p.tag() ? tagBlock : ringBlock;
            BlockDisplay d = loc.getWorld().spawn(loc, BlockDisplay.class, e -> {
                e.setBlock(mat.createBlockData());
                e.setPersistent(false);
                e.setInvulnerable(true);
                e.setTeleportDuration(1);
            });
            displays.add(d);
        }
    }

    private void applyMode() {
        float y = mode == 1 ? Y_SNEAK : Y_STAND;
        int i = 0;
        for (Part p : PARTS) {
            if (p.tag() && !showTag) continue;
            if (i >= displays.size()) break;
            BlockDisplay d = displays.get(i++);
            d.setTransformation(new Transformation(
                    new Vector3f(p.x(), y + p.yOff(), p.z()),
                    new AxisAngle4f(),
                    new Vector3f(p.sx(), p.sy(), p.sz()),
                    new AxisAngle4f()));
            d.setViewRange(mode == 2 ? 0f : 1f);
        }
    }

    /** Rough guess of the body's facing: follows the head when moving, lags up to 50 degrees when idle. */
    private void updateYaw(Location loc) {
        float head = loc.getYaw();
        if (!haveYaw) {
            bodyYaw = head;
            haveYaw = true;
            lastX = loc.getX();
            lastZ = loc.getZ();
            return;
        }
        double dx = loc.getX() - lastX;
        double dz = loc.getZ() - lastZ;
        lastX = loc.getX();
        lastZ = loc.getZ();
        if (dx * dx + dz * dz > 0.0004) {
            bodyYaw = head;
        } else {
            float d = wrap(head - bodyYaw);
            if (d > 50f) bodyYaw = head - 50f;
            else if (d < -50f) bodyYaw = head + 50f;
        }
    }

    private static float wrap(float degrees) {
        return ((degrees + 180f) % 360f + 360f) % 360f - 180f;
    }
}
