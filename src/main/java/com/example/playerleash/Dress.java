package com.example.playerleash;

import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A maid dress drawn with block displays, so it works for vanilla clients
 * (no resource pack). The pieces come from {@link DressStyles}.
 *
 * Two ways of sticking it to the player (config "dress-follow"):
 *  - passenger: the pieces ride the player, so the game itself moves them and there is no lag.
 *  - teleport: the pieces are moved to the player every tick (fallback, slightly laggy).
 */
final class Dress {

    /** Set from config.yml. */
    static boolean followPassenger = true;
    static float heightAdjust = 0f;

    private int style;
    private boolean attachFailed;
    private final List<BlockDisplay> displays = new ArrayList<>();
    private float[] shownYaw = new float[0];

    private float bodyYaw;
    private boolean haveYaw;
    private double lastX, lastZ;
    private int mode = -1;          // 0 standing, 1 sneaking, 2 hidden
    private float shownHeight = -1f;

    Dress(int style) {
        this.style = style;
    }

    void setStyle(int style) {
        this.style = style;
        remove(); // rebuilt on the next update
    }

    private boolean riding() {
        return followPassenger && !attachFailed;
    }

    /** Call every tick. */
    void update(Player pet) {
        if (pet.isDead()) {
            remove();
            mode = -1;
            return;
        }
        Location loc = pet.getLocation();
        updateBodyYaw(loc);

        Pose pose = pet.getPose();
        int newMode = pet.isInvisible() ? 2
                : pose == Pose.STANDING ? 0
                : pose == Pose.SNEAKING ? 1 : 2;

        DressStyles.Part[] parts = DressStyles.STYLES[style];
        boolean broken = displays.size() != parts.length;
        for (BlockDisplay d : displays) {
            if (!d.isValid() || !d.getWorld().equals(loc.getWorld()) || (riding() && !d.isInsideVehicle())) {
                broken = true;
                break;
            }
        }
        if (broken) {
            remove();
            build(pet, loc, parts, riding());
            if (displays.isEmpty()) return;
            mode = -1;
        }

        boolean riding = riding();
        float height = riding ? (float) pet.getHeight() : 0f;
        if (newMode != mode || Math.abs(height - shownHeight) > 0.01f) {
            mode = newMode;
            shownHeight = height;
            apply(parts, height, riding);
        }

        for (int i = 0; i < displays.size(); i++) {
            float yaw = parts[i].group() == DressStyles.HEAD ? loc.getYaw() : bodyYaw;
            BlockDisplay d = displays.get(i);
            if (riding) {
                if (Float.isNaN(shownYaw[i]) || Math.abs(wrap(yaw - shownYaw[i])) > 0.5f) {
                    d.setRotation(yaw, 0f);
                    shownYaw[i] = yaw;
                }
            } else {
                Location t = loc.clone();
                t.setYaw(yaw);
                t.setPitch(0f);
                d.teleport(t);
            }
        }
    }

    void remove() {
        for (BlockDisplay d : displays) {
            d.remove();
        }
        displays.clear();
    }

    private void build(Player pet, Location loc, DressStyles.Part[] parts, boolean riding) {
        shownYaw = new float[parts.length];
        Arrays.fill(shownYaw, Float.NaN);
        Entity vehicle = pet;
        for (DressStyles.Part p : parts) {
            BlockDisplay d = loc.getWorld().spawn(loc, BlockDisplay.class, e -> {
                e.setBlock(p.mat().createBlockData());
                e.setPersistent(false);
                e.setInvulnerable(true);
                e.setTeleportDuration(1);
            });
            displays.add(d);
            if (riding) {
                // A player can only carry one passenger, so the pieces ride one another in a chain.
                if (!vehicle.addPassenger(d)) {
                    attachFailed = true;
                    remove();
                    build(pet, loc, parts, false);
                    return;
                }
                vehicle = d;
            }
        }
    }

    /**
     * Sets size, position and visibility of every piece.
     * Riding pieces sit at the top of the player's head, so their offset is measured down from there.
     */
    private void apply(DressStyles.Part[] parts, float attachHeight, boolean riding) {
        for (int i = 0; i < displays.size() && i < parts.length; i++) {
            DressStyles.Part p = parts[i];
            float sneak = 0f;
            if (mode == 1) {
                sneak = p.group() == DressStyles.HEAD ? -0.30f : p.group() == DressStyles.TORSO ? -0.20f : 0f;
            }
            float y = p.y() + sneak;
            if (riding) y = y - attachHeight + heightAdjust;
            BlockDisplay d = displays.get(i);
            d.setTransformation(new Transformation(
                    new Vector3f(p.x() - p.w() / 2f, y, p.z() - p.d() / 2f),
                    new AxisAngle4f(),
                    new Vector3f(p.w(), p.h(), p.d()),
                    new AxisAngle4f()));
            d.setViewRange(mode == 2 ? 0f : 1f);
        }
    }

    /**
     * Follows the game's own rule for which way a player's body faces: it turns towards the
     * direction they are walking (or away from it when walking backwards) and can never be more
     * than 50 degrees away from where the head looks.
     */
    private void updateBodyYaw(Location loc) {
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

        float target = bodyYaw;
        if (dx * dx + dz * dz > 0.0025) {
            float move = (float) (Math.atan2(dz, dx) * 57.29578) - 90f;
            float diff = Math.abs(wrap(head) - move);
            target = (diff > 95f && diff < 265f) ? move - 180f : move;
        }
        bodyYaw += wrap(target - bodyYaw) * 0.3f;

        float off = wrap(head - bodyYaw);
        if (Math.abs(off) > 50f) {
            bodyYaw += off - Math.signum(off) * 50f;
        }
    }

    private static float wrap(float degrees) {
        return ((degrees + 180f) % 360f + 360f) % 360f - 180f;
    }
}
