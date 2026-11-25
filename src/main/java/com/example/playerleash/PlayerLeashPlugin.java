package com.example.playerleash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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

import java.io.File;
import java.io.IOException;
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

    /** A saved pet whose owner or pet is offline right now. */
    private record Bond(UUID masterId, UUID petId, String petName, String masterName,
                        boolean leashed, boolean freeWill, boolean dress, int dressStyle) {}

    private NamespacedKey anchorKey;
    private NamespacedKey remoteKey;

    private double pullStart, tetherDistance, maxPullSpeed, anchorHeight;
    private int timeoutSeconds, maxPets;
    private boolean allowSelfRelease;
    private int defaultDressStyle;
    private boolean dressByDefault;
    private boolean internalTp; // true while the plugin itself teleports a caged pet
    private boolean blockFlying;
    private int tickCount;
    private File dataFile;
    private final Map<UUID, Bond> dormant = new LinkedHashMap<>(); // saved pets, key = pet

    // ------------------------------------------------------------------ setup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        anchorKey = new NamespacedKey(this, "anchor");
        remoteKey = new NamespacedKey(this, "remote");
        loadSettings();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        for (Player online : Bukkit.getOnlinePlayers()) tryActivate(online);
    }

    @Override
    public void onDisable() {
        for (Pet p : new ArrayList<>(pets.values())) suspend(p);
    }

    private void loadSettings() {
        reloadConfig();
        timeoutSeconds = getConfig().getInt("request-timeout-seconds", 30);
        maxPets = getConfig().getInt("max-pets-per-master", 3);
        pullStart = getConfig().getDouble("pull-start-distance", 4.0);
        tetherDistance = getConfig().getDouble("tether-teleport-distance", 25.0);
        maxPullSpeed = getConfig().getDouble("max-pull-speed", 0.6);
        anchorHeight = getConfig().getDouble("anchor-height-offset", 1.1);
        allowSelfRelease = getConfig().getBoolean("pet-can-unleash-self", false);
        int style = getConfig().getInt("dress-style", 1) - 1;
        defaultDressStyle = Math.max(0, Math.min(style, DressStyles.STYLES.length - 1));
        dressByDefault = getConfig().getBoolean("dress-on-by-default", false);
        blockFlying = getConfig().getBoolean("block-flying-while-leashed", true);
        Dress.followPassenger = !"teleport".equalsIgnoreCase(getConfig().getString("dress-follow", "passenger"));
        Dress.heightAdjust = (float) getConfig().getDouble("dress-height-adjust", 0.0);
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
        UUID me = player.getUniqueId();

        // The player is a pet (on the leash now, or waiting for their owner to come online)
        Pet asPet = pets.get(me);
        Bond waiting = dormant.get(me);
        if (asPet != null || waiting != null) {
            if (!allowSelfRelease) {
                String owner = asPet != null ? nameOf(asPet.masterId) : waiting.masterName();
                send(player, "<red>Only " + owner + " can disown you.");
                return;
            }
            if (asPet != null) {
                release(asPet, true);
            } else {
                dormant.remove(me);
                saveData();
                send(player, "<green>You're free.");
            }
            return;
        }

        // Staff can free anyone by name
        if (args.length >= 1 && player.hasPermission("playerleash.admin")) {
            if (freeByName(args[0], null) != 0) {
                send(player, "<gray>Freed " + args[0] + ".");
                return;
            }
        }

        // Owners free their own pets
        List<Pet> mine = petsOf(me);
        List<Bond> waitingMine = dormantOf(me);
        if (mine.isEmpty() && waitingMine.isEmpty()) {
            send(player, "<red>You have no pets.");
            return;
        }
        if (args.length == 0) {
            for (Pet p : mine) release(p, true);
            for (Bond b : waitingMine) {
                dormant.remove(b.petId());
                send(player, "<gray>" + b.petName() + " is no longer your pet.");
            }
            saveData();
            return;
        }
        int result = freeByName(args[0], me);
        if (result == 0) send(player, "<red>That player isn't your pet.");
        else if (result == 2) send(player, "<gray>" + args[0] + " is no longer your pet.");
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
        send(target, "<gold>" + n + " wants to put you on a leash. "
                + "<click:run_command:'/leash accept " + n + "'><hover:show_text:'Accept'><green>[Accept]</green></hover></click> "
                + "<click:run_command:'/leash decline " + n + "'><hover:show_text:'Decline'><red>[Decline]</red></hover></click>");
    }

    private void startLeash(Player master, Player target) {
        if (!master.isOnline() || !target.isOnline()) return;
        if (isPet(master.getUniqueId())) {
            send(master, "<red>You're on a leash yourself.");
            return;
        }
        if (isPet(target.getUniqueId()) || isOwner(target.getUniqueId())) {
            send(master, "<red>That player is already part of a leash.");
            return;
        }
        if (ownedCount(master.getUniqueId()) >= maxPets) {
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
        pets.put(target.getUniqueId(), pet);
        anchorIndex.put(pet.anchor.getUniqueId(), target.getUniqueId());
        pet.dressStyle = defaultDressStyle;
        if (dressByDefault) pet.dress = new Dress(pet.dressStyle);
        saveData();
        giveRemote(master);
        send(master, "<green>" + target.getName() + " is now on your leash. Use the compass (Pet Controller) to command them.");
        send(target, allowSelfRelease
                ? "<yellow>You're on " + master.getName() + "'s leash. Type <white>/unleash<yellow> any time to free yourself."
                : "<yellow>You're on " + master.getName() + "'s leash. Only they can disown you.");
    }

    /** Takes the pet apart (rope, cage, dress, flight lock) without touching the saved records. */
    private void teardown(Pet p) {
        p.leashed = false;
        removeAnchor(p);
        if (p.cage != null) {
            p.cage.restore();
            p.cage = null;
        }
        if (p.dress != null) {
            p.dress.remove();
            p.dress = null;
        }
        LeashHitch h = p.hitch;
        p.hitch = null;
        cleanupHitch(h);
        if (p.flightBlocked) {
            p.flightBlocked = false;
            Player pl = Bukkit.getPlayer(p.petId);
            if (pl != null) pl.setAllowFlight(p.hadFlight);
        }
    }

    /** Frees the pet for good. */
    private void release(Pet p, boolean notify) {
        pets.remove(p.petId);
        dormant.remove(p.petId);
        teardown(p);

        Player master = Bukkit.getPlayer(p.masterId);
        Player pet = Bukkit.getPlayer(p.petId);
        if (notify) {
            if (pet != null) send(pet, "<green>You're free.");
            if (master != null && pet != null) send(master, "<gray>" + pet.getName() + " is no longer your pet.");
        }
        if (master != null && petsOf(p.masterId).isEmpty()) {
            removeRemotes(master);
        }
        saveData();
    }

    /** One of the two players went offline: take the leash apart but remember the pet. */
    private void suspend(Pet p) {
        Bond bond = snapshot(p);
        pets.remove(p.petId);
        teardown(p);
        dormant.put(p.petId, bond);
        Player master = Bukkit.getPlayer(p.masterId);
        if (master != null && petsOf(p.masterId).isEmpty()) {
            removeRemotes(master);
        }
        saveData();
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

    /** Free will: the pet can do anything, but is still owned (the leash ownership and remote stay). */
    private void setFreeWill(Pet p, boolean on) {
        p.freeWill = on;
        if (on) {
            p.sitting = false;
            setFree(p, true);
        } else if (p.cage != null) {
            p.wasLeashed = true; // the leash comes back when the cage opens
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
        tickCount++;

        for (Pet p : new ArrayList<>(pets.values())) {
            Player pet = Bukkit.getPlayer(p.petId);
            Player master = Bukkit.getPlayer(p.masterId);
            if (pet == null || master == null) {
                suspend(p);
                continue;
            }

            if (tickCount % 10 == 0) refreshInventoryView(p, master, pet);
            if (p.dress != null) p.dress.update(pet);

            // No flying while on the leash (creative flight, /fly, elytra).
            boolean noFly = blockFlying && p.leashed
                    && pet.getGameMode() != GameMode.CREATIVE && pet.getGameMode() != GameMode.SPECTATOR;
            if (noFly) {
                if (!p.flightBlocked) {
                    p.flightBlocked = true;
                    p.hadFlight = pet.getAllowFlight();
                }
                if (pet.getAllowFlight() || pet.isFlying()) {
                    pet.setFlying(false);
                    pet.setAllowFlight(false);
                }
                if (pet.isGliding()) pet.setGliding(false);
            } else if (p.flightBlocked) {
                p.flightBlocked = false;
                pet.setAllowFlight(p.hadFlight);
            }

            // Caged: pull them back to the middle if they somehow get outside.
            if (p.cage != null && !p.cage.inside(pet.getLocation())) {
                Location c = p.cage.center();
                c.setYaw(pet.getLocation().getYaw());
                c.setPitch(pet.getLocation().getPitch());
                teleportInternal(pet, c);
            }

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
                // Pull sideways only. Up and down is left to gravity, so a pet can never be dragged into the air.
                Vector dir = target.toVector().subtract(pet.getLocation().toVector());
                dir.setY(0);
                double flat = dir.length();
                if (flat > 0.01) {
                    double speed = Math.min(maxPullSpeed, 0.1 + (dist - pullStart) * 0.12);
                    Vector v = dir.multiply(speed / flat);
                    double vy = pet.getVelocity().getY();
                    // Hop up a step only from the ground, when the owner is clearly higher up.
                    if (pet.isOnGround() && target.getY() - pet.getLocation().getY() > 0.6) vy = 0.42;
                    v.setY(vy);
                    pet.setVelocity(v);
                }
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
        ItemStack remote = new ItemStack(Material.COMPASS);
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
        boolean slid = from.getX() != to.getX() || from.getZ() != to.getZ();
        boolean jumped = to.getY() > from.getY();
        if (slid || jumped) {
            // Hold them in place sideways and stop jumping, but let gravity still work
            // (freezing the height made pets hover in the air).
            Location stay = to.clone();
            stay.setX(from.getX());
            stay.setZ(from.getZ());
            if (jumped) stay.setY(from.getY());
            e.setTo(stay);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        requests.remove(id);
        // They stay owned: the leash is taken apart now and put back when both are online again.
        Pet asPet = pets.get(id);
        if (asPet != null) suspend(asPet);
        for (Pet p : petsOf(id)) suspend(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joined = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (joined.isOnline()) tryActivate(joined);
        }, 20L);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isRemote(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    // ------------------------------------------------------------- saved pets

    private boolean isPet(UUID id) {
        return pets.containsKey(id) || dormant.containsKey(id);
    }

    private boolean isOwner(UUID id) {
        return !petsOf(id).isEmpty() || !dormantOf(id).isEmpty();
    }

    private int ownedCount(UUID masterId) {
        return petsOf(masterId).size() + dormantOf(masterId).size();
    }

    private List<Bond> dormantOf(UUID masterId) {
        List<Bond> list = new ArrayList<>();
        for (Bond b : dormant.values()) if (b.masterId().equals(masterId)) list.add(b);
        return list;
    }

    private String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : "unknown";
    }

    private Bond snapshot(Pet p) {
        boolean leashed = p.cage != null ? p.wasLeashed : p.leashed;
        return new Bond(p.masterId, p.petId, nameOf(p.petId), nameOf(p.masterId),
                leashed, p.freeWill, p.dress != null, p.dressStyle);
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "pets.yml");
        dormant.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = cfg.getConfigurationSection("bonds");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                ConfigurationSection b = sec.getConfigurationSection(key);
                if (b == null) continue;
                UUID petId = UUID.fromString(key);
                UUID masterId = UUID.fromString(b.getString("master", ""));
                dormant.put(petId, new Bond(masterId, petId,
                        b.getString("pet-name", "unknown"), b.getString("master-name", "unknown"),
                        b.getBoolean("leashed", true), b.getBoolean("free-will", false),
                        b.getBoolean("dress", false), b.getInt("dress-style", 0)));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Skipping a bad entry in pets.yml: " + key);
            }
        }
    }

    private void saveData() {
        if (dataFile == null) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Pet p : pets.values()) writeBond(cfg, snapshot(p));
        for (Bond b : dormant.values()) writeBond(cfg, b);
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Couldn't save pets.yml: " + ex.getMessage());
        }
    }

    private void writeBond(YamlConfiguration cfg, Bond b) {
        String base = "bonds." + b.petId() + ".";
        cfg.set(base + "master", b.masterId().toString());
        cfg.set(base + "pet-name", b.petName());
        cfg.set(base + "master-name", b.masterName());
        cfg.set(base + "leashed", b.leashed());
        cfg.set(base + "free-will", b.freeWill());
        cfg.set(base + "dress", b.dress());
        cfg.set(base + "dress-style", b.dressStyle());
    }

    /** When a saved pet and their owner are both online, put the leash back on. */
    private void tryActivate(Player joined) {
        for (Bond b : new ArrayList<>(dormant.values())) {
            if (!b.masterId().equals(joined.getUniqueId()) && !b.petId().equals(joined.getUniqueId())) continue;
            Player master = Bukkit.getPlayer(b.masterId());
            Player pet = Bukkit.getPlayer(b.petId());
            if (master == null || pet == null) continue;
            activate(b, master, pet);
        }
    }

    private void activate(Bond b, Player master, Player target) {
        dormant.remove(b.petId());
        if (pets.containsKey(b.petId())) return;
        Pet pet = new Pet(b.masterId(), b.petId());
        pet.dressStyle = Math.max(0, Math.min(b.dressStyle(), DressStyles.STYLES.length - 1));
        pet.freeWill = b.freeWill();
        pet.leashed = b.leashed() && !b.freeWill();
        pets.put(b.petId(), pet);
        if (b.dress()) pet.dress = new Dress(pet.dressStyle);
        if (pet.leashed) relink(pet);
        giveRemote(master);
        send(master, "<green>" + target.getName() + " is on your leash again.");
        send(target, "<yellow>You're back on " + master.getName() + "'s leash.");
        saveData();
    }

    /** 0 = nobody found, 1 = freed a pet who is online, 2 = freed a saved pet who was waiting. */
    private int freeByName(String name, UUID ownerOnly) {
        for (Pet p : new ArrayList<>(pets.values())) {
            if (!nameOf(p.petId).equalsIgnoreCase(name)) continue;
            if (ownerOnly != null && !p.masterId.equals(ownerOnly)) continue;
            release(p, true);
            return 1;
        }
        for (Bond b : new ArrayList<>(dormant.values())) {
            if (!b.petName().equalsIgnoreCase(name)) continue;
            if (ownerOnly != null && !b.masterId().equals(ownerOnly)) continue;
            dormant.remove(b.petId());
            saveData();
            Player pet = Bukkit.getPlayer(b.petId());
            if (pet != null) send(pet, "<green>You're free.");
            return 2;
        }
        return 0;
    }

    /** Keeps an open "view inventory" screen up to date. */
    private void refreshInventoryView(Pet p, Player master, Player pet) {
        Inventory top = master.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof PetInvView v && v.petId.equals(p.petId)) {
            v.refresh(pet);
        }
    }

    // ------------------------------------------------------------------- cage

    private void teleportInternal(Player pet, Location to) {
        internalTp = true;
        try {
            pet.teleport(to);
        } finally {
            internalTp = false;
        }
    }

    private void toggleCage(Player master, Player pet, Pet p) {
        if (p.cage == null) {
            p.wasLeashed = p.leashed;
            p.sitting = false;
            setFree(p, true);
            p.cage = Cage.build(pet.getLocation());
            send(master, "<green>" + pet.getName() + " is locked in a cage.");
            send(pet, "<yellow>You were put in a cage. You can still use commands, but you can't get out.");
        } else {
            p.cage.restore();
            p.cage = null;
            if (!p.freeWill && p.wasLeashed) setFree(p, false);
            send(master, "<green>" + pet.getName() + "'s cage is open.");
            send(pet, "<green>The cage is open.");
        }
    }

    private boolean isCageBlock(Block b) {
        for (Pet p : pets.values()) {
            if (p.cage != null && p.cage.protects(b)) return true;
        }
        return false;
    }

    private boolean isCaged(Player player) {
        Pet p = pets.get(player.getUniqueId());
        return p != null && p.cage != null;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (internalTp || !isCaged(e.getPlayer())) return;
        e.setCancelled(true);
        send(e.getPlayer(), "<red>You can't teleport out of the cage.");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (isCaged(e.getPlayer()) || isCageBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isCaged(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isCageBlock);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isCageBlock);
    }

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (isCageBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (isCageBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (isCageBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------- menu clicks

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Object holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof PetMenu || holder instanceof PetInvView) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof PetInvView) {
            e.setCancelled(true);
            return;
        }
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

        boolean locked = (p.freeWill || p.cage != null) && (slot == PetMenu.SLOT_WALK || slot == PetMenu.SLOT_SIT
                || slot == PetMenu.SLOT_TELEPORT || slot == PetMenu.SLOT_FACE);
        if (locked) {
            send(master, p.cage != null ? "<gray>Open the cage first." : "<gray>Turn off free will first.");
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
            case PetMenu.SLOT_CAGE -> {
                toggleCage(master, pet, p);
                openControl(master, p);
            }
            case PetMenu.SLOT_DRESS -> {
                String styleName;
                if (e.getClick().isRightClick()) {
                    p.dressStyle = (p.dressStyle + 1) % DressStyles.STYLES.length;
                    if (p.dress == null) p.dress = new Dress(p.dressStyle);
                    else p.dress.setStyle(p.dressStyle);
                    styleName = DressStyles.NAMES[p.dressStyle];
                    send(master, "<green>" + pet.getName() + " is wearing the " + styleName + " maid dress.");
                    send(pet, "<yellow>You're wearing a maid dress (" + styleName + ").");
                } else if (p.dress == null) {
                    p.dress = new Dress(p.dressStyle);
                    styleName = DressStyles.NAMES[p.dressStyle];
                    send(master, "<green>" + pet.getName() + " is wearing the " + styleName + " maid dress.");
                    send(pet, "<yellow>You're wearing a maid dress (" + styleName + ").");
                } else {
                    p.dress.remove();
                    p.dress = null;
                    send(master, "<green>" + pet.getName() + "'s dress is off.");
                    send(pet, "<green>Your dress is off.");
                }
                openControl(master, p);
            }
            case PetMenu.SLOT_INVENTORY -> {
                master.openInventory(new PetInvView(master, pet).getInventory());
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
        saveData();
    }
}
