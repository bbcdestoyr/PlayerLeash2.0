package com.example.playerleash;

import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A maid dress drawn with block displays, so it works for vanilla clients
 * (no resource pack). The pieces come from {@link DressStyles}.
 */
final class Dress {

    private int style;
    private final List<BlockDisplay> displays = new ArrayList<>();

    private float bodyYaw;
    private boolean haveYaw;
    private double lastX, lastZ;
    private int mode = -1; // 0 standing, 1 sneaking, 2 hidden

    Dress(int style) {
        this.style = style;
    }

    void setStyle(int style) {
        this.style = style;
        remove(); // rebuilt on the next update
    }

    /** Call every tick: keeps the dress on the player. */
    void update(Player pet) {
        Location loc = pet.getLocation();
        updateYaw(loc);

        Pose pose = pet.getPose();
        int newMode = (pet.isDead() || pet.isInvisible()) ? 2
                : pose == Pose.STANDING ? 0
                : pose == Pose.SNEAKING ? 1 : 2;

        DressStyles.Part[] parts = DressStyles.STYLES[style];
        boolean broken = displays.size() != parts.length;
        for (BlockDisplay d : displays) {
            if (!d.isValid() || !d.getWorld().equals(loc.getWorld())) {
                broken = true;
                break;
            }
        }
        if (broken) {
            remove();
            build(loc, parts);
            mode = -1;
        }
        if (newMode != mode) {
            mode = newMode;
            apply(parts);
        }

        for (int i = 0; i < displays.size(); i++) {
            Location t = loc.clone();
            t.setYaw(parts[i].group() == DressStyles.HEAD ? loc.getYaw() : bodyYaw);
            t.setPitch(0f);
            displays.get(i).teleport(t);
        }
    }

    void remove() {
        for (BlockDisplay d : displays) {
            d.remove();
        }
        displays.clear();
    }

    private void build(Location loc, DressStyles.Part[] parts) {
        for (DressStyles.Part p : parts) {
            BlockDisplay d = loc.getWorld().spawn(loc, BlockDisplay.class, e -> {
                e.setBlock(p.mat().createBlockData());
                e.setPersistent(false);
                e.setInvulnerable(true);
                e.setTeleportDuration(1);
            });
            displays.add(d);
        }
    }

    private void apply(DressStyles.Part[] parts) {
        for (int i = 0; i < displays.size() && i < parts.length; i++) {
            DressStyles.Part p = parts[i];
            float off = 0f;
            if (mode == 1) {
                off = p.group() == DressStyles.HEAD ? -0.30f : p.group() == DressStyles.TORSO ? -0.20f : 0f;
            }
            BlockDisplay d = displays.get(i);
            d.setTransformation(new Transformation(
                    new Vector3f(p.x() - p.w() / 2f, p.y() + off, p.z() - p.d() / 2f),
                    new AxisAngle4f(),
                    new Vector3f(p.w(), p.h(), p.d()),
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
