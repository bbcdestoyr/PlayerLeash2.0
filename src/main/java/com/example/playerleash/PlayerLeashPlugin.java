package com.example.playerleash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlayerLeashPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<UUID, Pet> pets = new LinkedHashMap<>();      // key = pet player
    private final Map<UUID, UUID> anchorIndex = new HashMap<>();    // anchor entity -> pet player
    private final Map<UUID, Request> requests = new HashMap<>();    // key = player being asked

    private record Request(UUID masterId, long expiresAt) {}

    private NamespacedKey anchorKey;
    private NamespacedKey remoteKey;

    private double pullStart, tetherDistance, maxPullSpeed, anchorHeight;
    private int timeoutSeconds, maxPets;
    private Material remoteMaterial, collarBlock, collarTagBlock;
    private boolean collarTag, allowSelfRelease;

    // ------------------------------------------------------------------ setup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        anchorKey = new NamespacedKey(this, "anchor");
        remoteKey = new NamespacedKey(this, "remote");
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Pet p : new ArrayList<>(pets.values())) release(p, false);
    }

    private void loadSettings() {
        reloadConfig();
        timeoutSeconds = getConfig().getInt("request-timeout-seconds", 30);
        maxPets = getConfig().getInt("max-pets-per-master", 3);
        pullStart = getConfig().getDouble("pull-start-distance", 4.0);
        tetherDistance = getConfig().getDouble("tether-teleport-distance", 25.0);
        maxPullSpeed = getConfig().getDouble("max-pull-speed", 0.6);
        anchorHeight = getConfig().getDouble("anchor-height-offset", 1.1);
        allowSelfRelease = getConfig().getBoolean("allow-pet-self-release", true);
        remoteMaterial = material(getConfig().getString("remote-item"), Material.STICK);
        collarBlock = material(getConfig().getString("collar-block"), Material.PINK_CONCRETE_POWDER);
        collarTagBlock = material(getConfig().getString("collar-tag-block"), Material.GOLD_BLOCK);
        collarTag = getConfig().getBoolean("collar-tag", true);
    }

    private Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        if (m == null || m.isAir() || !m.isItem()) return fallback;
        // Collar parts are drawn as blocks, so they must be real blocks
        if (fallback.isBlock() && !m.isBlock()) return fallback;
        return m;
    }

    /** All messages go only to the people involved, never to the whole server. */
    private void send(Player p, String mini) {
        p.sendMessage(MM.deserialize(mini));
    }

    // --------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("playerleash")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                loadSettings();
                sender.sendMessage(Component.text("PlayerLeash reloaded."));
            } else {
                sender.sendMessage(Component.text("Usage: /playerleash reload"));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }

        switch (name) {
            case "leash" -> handleLeashCommand(player, args);
            case "unleash" -> handleUnleashCommand(player, args);
            case "petremote" -> {
                if (petsOf(player.getUniqueId()).isEmpty()) {
                    send(player, "<red>You have no pets.");
                } else {
                    giveRemote(player);
                    send(player, "<green>Here is your Pet Controller.");
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private void handleLeashCommand(Player player, String[] args) {
        if (args.length == 0) {
            send(player, "<yellow>Usage: /leash <player>");
            return;
        }
        String sub = args[0].toLowerCase();
        if ((sub.equals("accept") || sub.equals("decline")) && args.length >= 2) {
            Player master = Bukkit.getPlayerExact(args[1]);
            Request r = requests.get(player.getUniqueId());
            if (master == null || r == null || !r.masterId().equals(master.getUniqueId())
                    || r.expiresAt() < System.currentTimeMillis()) {
                send(player, "<red>No open request from that player.");
                return;
            }
            requests.remove(player.getUniqueId());
            if (sub.equals("accept")) {
                startLeash(master, player);
            } else {
                send(player, "<gray>You declined.");
                send(master, "<red>" + player.getName() + " declined your request.");
            }
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(player, "<red>Player not found.");
            return;
        }
        requestLeash(player, target);
    }

    private void handleUnleashCommand(Player player, String[] args) {
        Pet asPet = pets.get(player.getUniqueId());
        if (asPet != null) {
            if (!allowSelfRelease) {
                send(player, "<red>You can't free yourself.");
                return;
            }
            release(asPet, true);
            return;
        }
        List<Pet> mine = petsOf(player.getUniqueId());
        if (mine.isEmpty()) {
            send(player, "<red>You have no pets.");
            return;
        }
        if (args.length == 0) {
            for (Pet p : mine) release(p, true);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        Pet p = target == null ? null : pets.get(target.getUniqueId());
        if (p == null || !p.masterId.equals(player.getUniqueId())) {
            send(player, "<red>That player isn't your pet.");
            return;
        }
        release(p, true);
    }

    // ------------------------------------------------------- leash lifecycle

    private List<Pet> petsOf(UUID masterId) {
        List<Pet> list = new ArrayList<>();
        for (Pet p : pets.values()) if (p.masterId.equals(masterId)) list.add(p);
        return list;
    }

    private void requestLeash(Player master, Player target) {
        if (!master.hasPermission("playerleash.use")) return;
        if (master.equals(target)) {
            send(master, "<red>You can't do that to yourself.");
            return;
        }
        if (master.hasPermission("playerleash.force") || master.isOp()) {
            startLeash(master, target);
            return;
        }
        requests.put(target.getUniqueId(),
                new Request(master.getUniqueId(), System.currentTimeMillis() + timeoutSeconds * 1000L));
        send(master, "<gray>Asked <white>" + target.getName() + "<gray> if you can leash them.");
        String n = master.getName();
        send(target, "<gold>" + n + " wants to put a collar and leash on you. "
                + "<click:run_command:'/leash accept " + n + "'><hover:show_text:'Accept'><green>[Accept]</green></hover></click> "
                + "<click:run_command:'/leash decline " + n + "'><hover:show_text:'Decline'><red>[Decline]</red></hover></click>");
    }

    private void startLeash(Player master, Player target) {
        if (!master.isOnline() || !target.isOnline()) return;
        if (pets.containsKey(master.getUniqueId())) {
            send(master, "<red>You're on a leash yourself.");
            return;
        }
        if (pets.containsKey(target.getUniqueId()) || !petsOf(target.getUniqueId()).isEmpty()) {
            send(master, "<red>That player is already part of a leash.");
            return;
        }
        if (petsOf(master.getUniqueId()).size() >= maxPets) {
            send(master, "<red>You already have the maximum number of pets.");
            return;
        }
        if (!master.getWorld().equals(target.getWorld())
                || master.getLocation().distanceSquared(target.getLocation()) > 64) {
            send(master, "<red>You need to be closer to " + target.getName() + ".");
            send(target, "<red>You need to be closer to " + master.getName() + ".");
            return;
        }

        Pet pet = new Pet(master.getUniqueId(), target.getUniqueId());
        try {
            pet.anchor = spawnAnchor(neck(target));
        } catch (Exception ex) {
            send(master, "<red>Couldn't create the leash here.");
            return;
        }
        if (!pet.anchor.setLeashHolder(master)) {
            pet.anchor.remove();
            send(master, "<red>Couldn't attach the leash.");
            return;
        }
        pet.collar = new Collar(collarBlock, collarTagBlock, collarTag);
        pets.put(target.getUniqueId(), pet);
        anchorIndex.put(pet.anchor.getUniqueId(), target.getUniqueId());
        pet.collar.update(target);
        giveRemote(master);
        send(master, "<green>" + target.getName() + " now wears your collar. Use the Pet Controller to command them.");
        send(target, "<yellow>You're wearing " + master.getName() + "'s collar. Type <white>/unleash<yellow> any time to free yourself.");
    }

    private void release(Pet p, boolean notify) {
        pets.remove(p.petId);
        p.leashed = false;
        removeAnchor(p);
        if (p.collar != null) {
            p.collar.remove();
            p.collar = null;
        }
        LeashHitch h = p.hitch;
        p.hitch = null;
        cleanupHitch(h);

        Player master = Bukkit.getPlayer(p.masterId);
        Player pet = Bukkit.getPlayer(p.petId);
        if (notify) {
            if (pet != null) send(pet, "<green>You're free.");
            if (master != null && pet != null) send(master, "<gray>" + pet.getName() + " is no longer your pet.");
        }
        if (master != null && petsOf(p.masterId).isEmpty()) {
            removeRemotes(master);
        }
    }

    private Chicken spawnAnchor(Location loc) {
        return loc.getWorld().spawn(loc, Chicken.class, c -> {
            c.setInvisible(true);
            c.setSilent(true);
            c.setInvulnerable(true);
            c.setAI(false);
            c.setGravity(false);
            c.setCollidable(false);
            c.setPersistent(false);
            c.setBaby();
            c.setAgeLock(true);
            c.getPersistentDataContainer().set(anchorKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void removeAnchor(Pet p) {
        if (p.anchor == null) return;
        anchorIndex.remove(p.anchor.getUniqueId());
        try {
            p.anchor.setLeashHolder(null);
        } catch (Exception ignored) {
            // already gone
        }
        p.anchor.remove();
        p.anchor = null;
    }

    private Location neck(Player pet) {
        return pet.getLocation().add(0, anchorHeight, 0);
    }

    private void ensureAnchor(Pet p, Player pet) {
        if (p.anchor != null && p.anchor.isValid()) return;
        if (p.anchor != null) anchorIndex.remove(p.anchor.getUniqueId());
        p.anchor = spawnAnchor(neck(pet));
        anchorIndex.put(p.anchor.getUniqueId(), p.petId);
    }

    /** Puts the rope back on the master (or fence knot), pulling the pet close first if needed. */
    private void relink(Pet p) {
        if (!pets.containsKey(p.petId) || !p.leashed) return;
        Player master = Bukkit.getPlayer(p.masterId);
        Player pet = Bukkit.getPlayer(p.petId);
        if (master == null || pet == null) return;

        if (p.hitch != null && !p.hitch.isValid()) p.hitch = null;
        Entity holder = p.hitch != null ? p.hitch : master;
        Location hl = holder.getLocation();

        if (!pet.getWorld().equals(hl.getWorld()) || pet.getLocation().distanceSquared(hl) > 64) {
            pet.teleport(hl);
        }
        ensureAnchor(p, pet);
        p.anchor.teleport(neck(pet));
        p.anchor.setLeashHolder(holder);
    }

    /** free = true takes the rope off completely: no pull, no distance limit. */
    private void setFree(Pet p, boolean free) {
        if (free) {
            p.leashed = false;
            removeAnchor(p);
            LeashHitch h = p.hitch;
            p.hitch = null;
            cleanupHitch(h);
        } else {
            p.leashed = true;
            relink(p);
        }
    }

    /** Free will: the pet can do anything, but is still owned (collar and remote stay). */
    private void setFreeWill(Pet p, boolean on) {
        p.freeWill = on;
        if (on) {
            p.sitting = false;
            setFree(p, true);
        } else {
            setFree(p, false);
        }
    }

    private void cleanupHitch(LeashHitch h) {
        if (h == null || !h.isValid()) return;
        for (Pet q : pets.values()) if (h.equals(q.hitch)) return;
        for (Entity en : h.getNearbyEntities(12, 12, 12)) {
            if (en instanceof Mob m && m.isLeashed() && h.equals(m.getLeashHolder())) return;
        }
        h.remove();
    }

    // ------------------------------------------------------------------- tick

    private void tick() {
        long now = System.currentTimeMillis();
        requests.values().removeIf(r -> r.expiresAt() < now);

        for (Pet p : new ArrayList<>(pets.values())) {
            Player pet = Bukkit.getPlayer(p.petId);
            Player master = Bukkit.getPlayer(p.masterId);
            if (pet == null || master == null) {
                release(p, false);
                continue;
            }

            if (p.collar != null) p.collar.update(pet);

            // Walking free: no rope, no pull, no distance limit.
            if (!p.leashed) continue;

            ensureAnchor(p, pet);
            p.anchor.teleport(neck(pet));

            if (p.hitch != null && !p.hitch.isValid()) p.hitch = null;
            if (!p.anchor.isLeashed()) relink(p);

            Location target = (p.hitch != null ? p.hitch : master).getLocation();
            if (!pet.getWorld().equals(target.getWorld())) {
                pet.teleport(target);
                continue;
            }
            double dist = pet.getLocation().distance(target);
            if (dist > tetherDistance) {
                pet.teleport(target);
                continue;
            }
            if (!p.sitting && !pet.isInsideVehicle() && dist > pullStart) {
                Vector dir = target.toVector().subtract(pet.getLocation().toVector());
                double speed = Math.min(maxPullSpeed, 0.1 + (dist - pullStart) * 0.12);
                double dy = dir.getY();
                Vector v = dir.clone().normalize().multiply(speed);
                double up = dy > 0.5 ? Math.min(v.getY(), 0.42) : 0.0;
                v.setY(Math.max(pet.getVelocity().getY(), up));
                pet.setVelocity(v);
            }
        }
    }

    // ---------------------------------------------------------------- remote

    private boolean isRemote(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(remoteKey, PersistentDataType.BYTE);
    }

    private void giveRemote(Player master) {
        for (ItemStack it : master.getInventory().getContents()) if (isRemote(it)) return;
        ItemStack remote = new ItemStack(remoteMaterial);
        ItemMeta meta = remote.getItemMeta();
        meta.displayName(MM.deserialize("<!italic><light_purple>Pet Controller"));
        meta.lore(List.of(MM.deserialize("<!italic><gray>Right-click to command your pets")));
        meta.getPersistentDataContainer().set(remoteKey, PersistentDataType.BYTE, (byte) 1);
        remote.setItemMeta(meta);
        master.getInventory().addItem(remote).values()
                .forEach(left -> master.getWorld().dropItemNaturally(master.getLocation(), left));
    }

    private void removeRemotes(Player master) {
        Inventory inv = master.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isRemote(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    private void openRemote(Player master) {
        List<Pet> mine = petsOf(master.getUniqueId());
        if (mine.isEmpty()) {
            send(master, "<red>You have no pets.");
            return;
        }
        if (mine.size() == 1) openControl(master, mine.get(0));
        else master.openInventory(PetMenu.select(master, mine).getInventory());
    }

    private void openControl(Player master, Pet p) {
        Player pet = Bukkit.getPlayer(p.petId);
        if (pet == null) return;
        master.openInventory(PetMenu.control(master, pet, p).getInventory());
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity clicked = e.getRightClicked();
        Player player = e.getPlayer();

        if (isAnchor(clicked)) {
            e.setCancelled(true);
            return;
        }

        if (clicked instanceof LeashHitch h) {
            boolean ours = false;
            for (Pet p : new ArrayList<>(pets.values())) {
                if (h.equals(p.hitch)) {
                    ours = true;
                    if (p.masterId.equals(player.getUniqueId())) {
                        p.hitch = null;
                        relink(p);
                    }
                }
            }
            if (ours) {
                e.setCancelled(true);
                cleanupHitch(h);
            }
            return;
        }

        if (clicked instanceof Player target
                && player.getInventory().getItemInMainHand().getType() == Material.LEAD) {
            e.setCancelled(true);
            requestLeash(player, target);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        Player player = e.getPlayer();

        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && Tag.FENCES.isTagged(e.getClickedBlock().getType())) {
            Block fence = e.getClickedBlock();
            boolean hitched = false;
            for (Pet p : petsOf(player.getUniqueId())) {
                if (!p.leashed || p.hitch != null || p.anchor == null) continue;
                Player pet = Bukkit.getPlayer(p.petId);
                if (pet == null || !pet.getWorld().equals(fence.getWorld())) continue;
                if (pet.getLocation().distanceSquared(fence.getLocation().add(0.5, 0.5, 0.5)) > 64) {
                    send(player, "<red>" + pet.getName() + " is too far from that fence.");
                    continue;
                }
                p.hitch = getOrCreateHitch(fence);
                p.anchor.setLeashHolder(p.hitch);
                hitched = true;
            }
            if (hitched) {
                e.setCancelled(true);
                return;
            }
        }

        if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && isRemote(e.getItem())) {
            e.setCancelled(true);
            openRemote(player);
        }
    }

    private LeashHitch getOrCreateHitch(Block b) {
        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        for (Entity en : b.getWorld().getNearbyEntities(center, 0.6, 0.6, 0.6)) {
            if (en instanceof LeashHitch h) return h;
        }
        return b.getWorld().spawn(b.getLocation(), LeashHitch.class);
    }

    private boolean isAnchor(Entity en) {
        return anchorIndex.containsKey(en.getUniqueId())
                || en.getPersistentDataContainer().has(anchorKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onUnleash(EntityUnleashEvent e) {
        if (isAnchor(e.getEntity())) e.setDropLeash(false); // the tick loop re-links when needed
    }

    @EventHandler
    public void onPlayerLeash(PlayerLeashEntityEvent e) {
        if (isAnchor(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerUnleash(PlayerUnleashEntityEvent e) {
        if (isAnchor(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (isAnchor(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Pet p = pets.get(e.getPlayer().getUniqueId());
        if (p == null || !p.sitting) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location stay = from.clone();
            stay.setYaw(to.getYaw());
            stay.setPitch(to.getPitch());
            e.setTo(stay);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        requests.remove(id);
        Pet asPet = pets.get(id);
        if (asPet != null) release(asPet, true);
        for (Pet p : petsOf(id)) release(p, true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isRemote(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    // ------------------------------------------------------------- menu clicks

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PetMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof PetMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player master)) return;
        if (!master.getUniqueId().equals(menu.masterId)) return;
        if (e.getClickedInventory() != top) return;
        int slot = e.getSlot();

        if (menu.kind == PetMenu.Kind.SELECT) {
            List<Pet> mine = petsOf(master.getUniqueId());
            if (slot >= 0 && slot < mine.size()) openControl(master, mine.get(slot));
            return;
        }

        Pet p = pets.get(menu.petId);
        Player pet = Bukkit.getPlayer(menu.petId);
        if (p == null || pet == null || !p.masterId.equals(master.getUniqueId())) {
            master.closeInventory();
            return;
        }

        boolean locked = p.freeWill && (slot == PetMenu.SLOT_WALK || slot == PetMenu.SLOT_SIT
                || slot == PetMenu.SLOT_TELEPORT || slot == PetMenu.SLOT_FACE);
        if (locked) {
            send(master, "<gray>Turn off free will first.");
            return;
        }

        switch (slot) {
            case PetMenu.SLOT_WALK -> {
                setFree(p, p.leashed);
                send(master, p.leashed ? "<green>" + pet.getName() + " is back on the leash."
                        : "<green>" + pet.getName() + " can walk free.");
                send(pet, p.leashed ? "<yellow>You're back on the leash."
                        : "<green>You can walk free.");
                openControl(master, p);
            }
            case PetMenu.SLOT_SIT -> {
                p.sitting = !p.sitting;
                send(pet, p.sitting ? "<yellow>You were told to stay." : "<yellow>You may move again.");
                openControl(master, p);
            }
            case PetMenu.SLOT_TELEPORT -> {
                p.sitting = false;
                pet.teleport(master.getLocation());
            }
            case PetMenu.SLOT_FACE -> {
                Location l = pet.getLocation();
                l.setDirection(master.getEyeLocation().toVector().subtract(pet.getEyeLocation().toVector()));
                pet.teleport(l);
            }
            case PetMenu.SLOT_FREEWILL -> {
                setFreeWill(p, !p.freeWill);
                send(master, p.freeWill ? "<green>" + pet.getName() + " has free will. You still own them."
                        : "<green>Free will is off. " + pet.getName() + " is back on the leash.");
                send(pet, p.freeWill ? "<green>You can do anything now. " + master.getName() + " still owns you."
                        : "<yellow>Free will is off. You're back on the leash.");
                openControl(master, p);
            }
            case PetMenu.SLOT_RELEASE -> {
                master.closeInventory();
                release(p, true);
            }
            default -> { }
        }
    }
}
