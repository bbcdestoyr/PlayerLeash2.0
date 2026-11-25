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

    /** True while the rope is attached (false = walking free, no distance limit). */
    boolean leashed = true;

    /** True while the pet is told to stay in place. */
    boolean sitting;

    /** The maid dress the pet is wearing, or null when it is off. */
    Dress dress;

    /** Which dress style the owner picked (index into DressStyles). */
    int dressStyle;

    /** The cage around the pet, or null when not caged. */
    Cage cage;

    /** Whether the pet was on the leash before being caged. */
    boolean wasLeashed;

    /** True while the pet may do anything. They are still owned. */
    boolean freeWill;

    /** True while we are holding the pet's flight ability off (they are on the leash). */
    boolean flightBlocked;

    /** Whether the pet could fly before we switched it off, so we can give it back. */
    boolean hadFlight;

    Pet(UUID masterId, UUID petId) {
        this.masterId = masterId;
        this.petId = petId;
    }
}
