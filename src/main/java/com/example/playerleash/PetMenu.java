package com.example.playerleash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Inventory menus: a pet picker and the per-pet control panel. */
final class PetMenu implements InventoryHolder {

    enum Kind { SELECT, CONTROL }

    static final int SLOT_WALK = 10;
    static final int SLOT_SIT = 11;
    static final int SLOT_TELEPORT = 12;
    static final int SLOT_FACE = 13;
    static final int SLOT_CAGE = 14;
    static final int SLOT_FREEWILL = 15;
    static final int SLOT_DRESS = 16;
    static final int SLOT_INVENTORY = 20;
    static final int SLOT_RELEASE = 22;

    final Kind kind;
    final UUID masterId;
    final UUID petId; // only for CONTROL
    private Inventory inventory;

    private PetMenu(Kind kind, UUID masterId, UUID petId) {
        this.kind = kind;
        this.masterId = masterId;
        this.petId = petId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    static PetMenu select(Player master, List<Pet> pets) {
        PetMenu menu = new PetMenu(Kind.SELECT, master.getUniqueId(), null);
        int size = 9 * Math.max(1, (int) Math.ceil(pets.size() / 9.0));
        menu.inventory = Bukkit.createInventory(menu, Math.min(size, 54), Component.text("Choose a pet"));
        for (int i = 0; i < pets.size() && i < 54; i++) {
            Player pet = Bukkit.getPlayer(pets.get(i).petId);
            if (pet == null) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(pet);
            meta.displayName(label(pet.getName(), NamedTextColor.GOLD));
            head.setItemMeta(meta);
            menu.inventory.setItem(i, head);
        }
        return menu;
    }

    static PetMenu control(Player master, Player pet, Pet data) {
        PetMenu menu = new PetMenu(Kind.CONTROL, master.getUniqueId(), pet.getUniqueId());
        menu.inventory = Bukkit.createInventory(menu, 27, Component.text("Pet: " + pet.getName()));
        Inventory inv = menu.inventory;

        if (data.freeWill || data.cage != null) {
            String why = data.cage != null ? "Locked while caged." : "Locked while free will is on.";
            inv.setItem(SLOT_WALK, item(Material.GRAY_DYE, "Walk free", NamedTextColor.DARK_GRAY, why));
            inv.setItem(SLOT_SIT, item(Material.GRAY_DYE, "Sit / stay", NamedTextColor.DARK_GRAY, why));
            inv.setItem(SLOT_TELEPORT, item(Material.GRAY_DYE, "Teleport to me", NamedTextColor.DARK_GRAY, why));
            inv.setItem(SLOT_FACE, item(Material.GRAY_DYE, "Face me", NamedTextColor.DARK_GRAY, why));
        } else {
            if (data.leashed) {
                inv.setItem(SLOT_WALK, item(Material.LEAD, "Walk free", NamedTextColor.GREEN,
                        "Takes the rope off.", "They can go as far as they like."));
            } else {
                inv.setItem(SLOT_WALK, item(Material.LEAD, "Put back on leash", NamedTextColor.GREEN,
                        "Puts the rope back on.", "Teleports them to you if they are far away."));
            }
            inv.setItem(SLOT_SIT, item(Material.OAK_STAIRS,
                    data.sitting ? "Stand" : "Sit / stay", NamedTextColor.YELLOW,
                    data.sitting ? "Lets them move again." : "They can't move until you say so."));
            inv.setItem(SLOT_TELEPORT, item(Material.ENDER_PEARL, "Teleport to me", NamedTextColor.LIGHT_PURPLE));
            inv.setItem(SLOT_FACE, item(Material.ENDER_EYE, "Face me", NamedTextColor.AQUA));
        }

        if (data.freeWill) {
            inv.setItem(SLOT_FREEWILL, item(Material.NETHER_STAR, "Free will: ON", NamedTextColor.GREEN,
                    "They can do anything.", "You still own them.", "Click to turn off (puts the leash back on)."));
        } else {
            inv.setItem(SLOT_FREEWILL, item(Material.NETHER_STAR, "Free will: OFF", NamedTextColor.GRAY,
                    "Click to let them do anything.", "You will still own them."));
        }

        if (data.cage != null) {
            inv.setItem(SLOT_CAGE, item(Material.GLASS, "Cage: ON", NamedTextColor.GREEN,
                    "They are locked in.", "Click to open the cage."));
        } else {
            inv.setItem(SLOT_CAGE, item(Material.GLASS, "Cage: OFF", NamedTextColor.GRAY,
                    "Locks them in a 3x3 glass cage.", "They can still use commands.",
                    "They can't break out or teleport out."));
        }
        String styleName = DressStyles.NAMES[data.dressStyle];
        if (data.dress != null) {
            inv.setItem(SLOT_DRESS, item(Material.PINK_DYE, "Maid dress: ON (" + styleName + ")", NamedTextColor.LIGHT_PURPLE,
                    "Click to take it off.", "Right-click to change the style."));
        } else {
            inv.setItem(SLOT_DRESS, item(Material.PINK_DYE, "Maid dress: OFF", NamedTextColor.GRAY,
                    "Click to put it on.", "Right-click to change the style.", "Style: " + styleName));
        }

        inv.setItem(SLOT_INVENTORY, item(Material.CHEST, "View inventory", NamedTextColor.AQUA,
                "See what they are carrying.", "View only: you can't take or add items."));

        inv.setItem(SLOT_RELEASE, item(Material.BARRIER, "Disown", NamedTextColor.DARK_RED,
                "Frees them. They are no longer yours."));
        return menu;
    }

    private static ItemStack item(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(label(name, color));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component label(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
