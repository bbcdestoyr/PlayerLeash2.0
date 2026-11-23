package com.example.playerleash;

import org.bukkit.entity.Chicken;
import org.bukkit.entity.LeashHitch;

import java.util.UUID;

/** State for one owned player. */
final class Pet {
    final UUID masterId;
    final UUID petId;

    /** Invisible entity that carries the rope. Null while the pet walks free. */
    Chicken anchor;

    /** Fence knot the pet is tied to, or null when held by the master. */
    LeashHitch hitch;

    /** The pink collar worn by the pet. */
    Collar collar;

    /** True while the rope is attached (false = walking free, no distance limit). */
    boolean leashed = true;

    /** True while the pet is told to stay in place. */
    boolean sitting;

    /** True while the pet may do anything. They are still owned. */
    boolean freeWill;

    Pet(UUID masterId, UUID petId) {
        this.masterId = masterId;
        this.petId = petId;
    }
}
