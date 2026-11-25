package com.example.playerleash;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

/**
 * A read-only picture of a pet's inventory. It is a copy that refreshes while
 * it is open, and every click in it is cancelled, so nothing can be taken or put in.
 */
final class PetInvView implements InventoryHolder {

    final UUID masterId;
    final UUID petId;
    private final Inventory inventory;

    PetInvView(Player master, Player pet) {
        this.masterId = master.getUniqueId();
        this.petId = pet.getUniqueId();
        this.inventory = Bukkit.createInventory(this, 54, Component.text(pet.getName() + "'s inventory"));
        refresh(pet);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Rows 1-3: backpack. Row 4: hotbar. Bottom row: helmet, chestplate, leggings, boots and off-hand. */
    void refresh(Player pet) {
        PlayerInventory pi = pet.getInventory();
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, copy(pi.getItem(9 + i)));
        }
        for (int i = 0; i < 9; i++) {
            inventory.setItem(27 + i, copy(pi.getItem(i)));
        }
        for (int i = 36; i < 54; i++) {
            inventory.setItem(i, filler());
        }
        inventory.setItem(45, copy(pi.getHelmet()));
        inventory.setItem(46, copy(pi.getChestplate()));
        inventory.setItem(47, copy(pi.getLeggings()));
        inventory.setItem(48, copy(pi.getBoots()));
        inventory.setItem(50, copy(pi.getItemInOffHand()));
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static ItemStack filler() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" "));
        pane.setItemMeta(meta);
        return pane;
    }
}
