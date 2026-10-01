package com.nyr.fixes.illegal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The places illegal items are caught: a player's inventory and ender chest when they join and every minute, any container as
 * it opens, both inventories right after a click, items as they are picked up or dropped into the world, creative-inventory
 * actions, and unobtainable blocks as they are placed.
 *
 * <p>The open container is remembered from its open event rather than read back through the player's open view, whose API
 * type changed between versions.
 */
final class ScanListener implements Listener {

    /** The pickup delay the game gives items no one may ever pick up. */
    private static final int NEVER_PICKED_UP = 32767;

    private final IllegalItemsPlugin plugin;
    private final Scanner scanner;
    private final Rules rules;
    private final Map<UUID, Inventory> openTop = new ConcurrentHashMap<>();
    private final Set<UUID> clickScanQueued = ConcurrentHashMap.newKeySet();

    ScanListener(IllegalItemsPlugin plugin, Scanner scanner, Rules rules) {
        this.plugin = plugin;
        this.scanner = scanner;
        this.rules = rules;
    }

    /** Scans a player's inventory and ender chest on the player's own thread; returns how many stacks changed. */
    int scanPlayer(Player player) {
        if (!player.isOnline() || scanner.exempt(player)) {
            return 0;
        }
        int changed = scanner.scan(player.getInventory(), player, "inventory", player.getLocation());
        if (rules.scan().enderChest()) {
            changed += scanner.scan(player.getEnderChest(), player, "ender chest", player.getLocation());
        }
        return changed;
    }

    void startPeriodic() {
        int seconds = rules.scan().periodicSeconds();
        if (seconds <= 0) {
            return;
        }
        long ticks = seconds * 20L;
        plugin.scheduler().runTimer(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                plugin.scheduler().runAtEntity(player, task -> scanPlayer(player));
            }
        }, ticks, ticks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent event) {
        if (rules.scan().onJoin()) {
            Player player = event.getPlayer();
            plugin.scheduler().runAtEntityLater(player, () -> scanPlayer(player), 10L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onQuit(PlayerQuitEvent event) {
        openTop.remove(event.getPlayer().getUniqueId());
        clickScanQueued.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        openTop.put(player.getUniqueId(), top);
        if (!rules.scan().onOpen() || scanner.exempt(player)) {
            return;
        }
        scanner.scan(top, player, where(top, player), location(top, player));
        scanner.scan(player.getInventory(), player, "inventory", player.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onClose(InventoryCloseEvent event) {
        HumanEntity human = event.getPlayer();
        openTop.remove(human.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            queueClickScan(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            queueClickScan(player);
        }
    }

    /** One scan on the next tick, however many clicks arrive in this one. */
    private void queueClickScan(Player player) {
        if (!rules.scan().onClick() || scanner.exempt(player) || !clickScanQueued.add(player.getUniqueId())) {
            return;
        }
        plugin.scheduler().runAtEntityLater(player, () -> {
            clickScanQueued.remove(player.getUniqueId());
            if (!player.isOnline() || scanner.exempt(player)) {
                return;
            }
            scanner.scan(player.getInventory(), player, "inventory", player.getLocation());
            Inventory top = openTop.get(player.getUniqueId());
            if (top != null && top != player.getInventory()) {
                scanner.scan(top, player, where(top, player), location(top, player));
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onPickup(EntityPickupItemEvent event) {
        if (!rules.scan().onPickup() || !(event.getEntity() instanceof Player player) || scanner.exempt(player)) {
            return;
        }
        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack();
        ItemStack result = scanner.check(stack, player, "picked up", entity.getLocation());
        if (result == stack) {
            return;
        }
        // The server hands over the stack it read before this event, so the fixed one is picked up on a later tick.
        event.setCancelled(true);
        if (result == null) {
            entity.remove();
        } else {
            entity.setItemStack(result);
        }
    }

    /**
     * Items dropped into the world are checked one tick after they appear. /give spawns a copy of every given stack that
     * nobody can ever pick up and that is gone a tick later, only for the pickup animation; checking at once would report
     * and quarantine that copy every time staff give out an item.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onItemSpawn(ItemSpawnEvent event) {
        if (!rules.scan().onDroppedItems() || !plugin.worlds().allows(event.getLocation().getWorld().getName())) {
            return;
        }
        Item entity = event.getEntity();
        if (!scanner.worthChecking(entity.getItemStack())) {
            return;
        }
        plugin.scheduler().runAtEntityLater(entity, () -> checkDropped(entity), 1L);
    }

    private void checkDropped(Item entity) {
        if (!entity.isValid() || entity.getPickupDelay() >= NEVER_PICKED_UP) {
            return;
        }
        UUID thrower = entity.getThrower();
        Player player = thrower == null ? null : plugin.getServer().getPlayer(thrower);
        if (player != null && scanner.exempt(player)) {
            return;
        }
        ItemStack stack = entity.getItemStack();
        ItemStack result = scanner.check(stack, player, "dropped", entity.getLocation());
        if (result == stack) {
            return;
        }
        if (result == null) {
            entity.remove();
        } else {
            entity.setItemStack(result);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onCreative(InventoryCreativeEvent event) {
        if (!rules.scan().onCreative() || !(event.getWhoClicked() instanceof Player player) || scanner.exempt(player)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }
        ItemStack result = scanner.check(cursor, player, "creative inventory", player.getLocation());
        if (result == cursor) {
            return;
        }
        if (result == null) {
            event.setCancelled(true);
        } else {
            event.setCursor(result);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!rules.scan().onPlace() || scanner.exempt(player)) {
            return;
        }
        Inspector.Outcome outcome = scanner.dryRun(event.getItemInHand());
        if (!outcome.clean() && outcome.removed()) {
            event.setCancelled(true);
            plugin.scheduler().runAtEntityLater(player, () -> scanner.scan(player.getInventory(), player, "inventory", player.getLocation()), 1L);
        }
    }

    private static String where(Inventory inventory, Player viewer) {
        if (inventory.equals(viewer.getEnderChest())) {
            return "ender chest";
        }
        return inventory.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Location location(Inventory inventory, Player viewer) {
        try {
            Location location = inventory.getLocation();
            return location != null ? location : viewer.getLocation();
        } catch (RuntimeException noLocation) {
            // Some plugin-made inventories cannot say where they are; the viewer's place is close enough for an alert.
            return viewer.getLocation();
        }
    }
}
