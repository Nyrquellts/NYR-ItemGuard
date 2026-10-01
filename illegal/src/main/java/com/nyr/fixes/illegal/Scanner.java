package com.nyr.fixes.illegal;

import com.nyr.fixes.illegal.Rules.Action;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Applies the inspector's answers where items live: replaces fixed stacks, takes removed ones into quarantine, tells staff
 * what changed and where, and tells the player once.
 */
final class Scanner {

    /** What the fix did since the server started; kept across reloads. */
    static final class Stats {
        final LongAdder fixed = new LongAdder();
        final LongAdder removed = new LongAdder();
        final LongAdder reported = new LongAdder();
        final LongAdder quarantined = new LongAdder();
    }

    /** What one scan found, to be reported once. */
    private static final class Report {
        final Set<String> lines = new LinkedHashSet<>();
        final List<String> ids = new ArrayList<>();
        int fixed;
        int removed;
        int reported;
    }

    private final IllegalItemsPlugin plugin;
    private final Inspector inspector;
    private final Rules rules;
    private final Quarantine quarantine;
    private final Stats stats;

    Scanner(IllegalItemsPlugin plugin, Inspector inspector, Rules rules, Quarantine quarantine, Stats stats) {
        this.plugin = plugin;
        this.inspector = inspector;
        this.rules = rules;
        this.quarantine = quarantine;
        this.stats = stats;
    }

    /** Players the rules leave alone: nyritemguard.bypass, creative mode (when so configured), disabled worlds. */
    boolean exempt(Player player) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission(plugin.permission("bypass"))) {
            return true;
        }
        if (rules.ignoreCreative() && player.getGameMode() == GameMode.CREATIVE) {
            return true;
        }
        return !plugin.worlds().allows(player.getWorld().getName());
    }

    /** Inspects every slot of an inventory; returns how many stacks changed. */
    int scan(Inventory inventory, Player player, String where, Location location) {
        if (inventory == null) {
            return 0;
        }
        Report report = new Report();
        int changed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Inspector.Outcome outcome = inspector.inspect(item);
            if (outcome.clean()) {
                continue;
            }
            record(outcome, player, where, location, report);
            if (outcome.changed()) {
                inventory.setItem(slot, outcome.item());
                changed++;
            }
        }
        finish(report, player, where, location);
        return changed;
    }

    /** Inspects one stack outside an inventory (a picked-up or dropped item); returns what it becomes, null when removed. */
    ItemStack check(ItemStack item, Player player, String where, Location location) {
        Inspector.Outcome outcome = inspector.inspect(item);
        if (outcome.clean()) {
            return item;
        }
        Report report = new Report();
        record(outcome, player, where, location, report);
        finish(report, player, where, location);
        return outcome.item();
    }

    /**
     * A cheap first look: plain stacks of ordinary items (no data, not on the unobtainable list, not overstacked) are clean
     * without building an outcome. Mob farms drop thousands of those a minute.
     */
    boolean worthChecking(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        return item.hasItemMeta() || rules.unobtainable().contains(item.getType()) || item.getAmount() > item.getType().getMaxStackSize();
    }

    /** What the inspector would say about an item, without changing anything, for /itemguard inspect. */
    Inspector.Outcome dryRun(ItemStack item) {
        return inspector.inspect(item);
    }

    private void record(Inspector.Outcome outcome, Player player, String where, Location location, Report report) {
        String reasons = String.join("; ", outcome.findings().stream().map(Inspector.Finding::detail).toList());
        for (Inspector.Finding finding : outcome.findings()) {
            if (finding.action() == Action.REPORT) {
                report.reported++;
                stats.reported.increment();
            }
        }
        for (ItemStack taken : outcome.taken()) {
            String id = quarantine.keep(taken, player == null ? "nobody" : player.getName(), location, where, reasons);
            report.ids.add(id);
            stats.quarantined.increment();
        }
        if (outcome.changed()) {
            if (outcome.removed()) {
                report.removed++;
                stats.removed.increment();
            } else {
                report.fixed++;
                stats.fixed.increment();
            }
        }
        report.lines.add(reasons);
    }

    private void finish(Report report, Player player, String where, Location location) {
        if (report.lines.isEmpty()) {
            return;
        }
        String owner = player == null ? "nobody" : player.getName();
        String summary = summarize(report.lines);
        String action = report.removed + report.fixed == 0 ? "found" : report.removed == 0 ? "fixed" : report.fixed == 0 ? "removed" : "fixed and removed";
        String counts = counts(report);
        String place = location == null || location.getWorld() == null ? "" : location.getWorld().getName() + " "
            + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
        String quarantined = report.ids.isEmpty() ? "" : plugin.messages().format("alert-quarantine-suffix", "ids", String.join(", ", report.ids));
        plugin.alerts().send(owner + ":" + where + ":" + summary.hashCode(), plugin.messages().format("alert",
            "player", owner, "counts", counts, "action", action, "count", report.fixed + report.removed + report.reported, "where", where,
            "place", place, "summary", summary) + quarantined);
        if (player != null && rules.tellPlayer() && report.fixed + report.removed > 0) {
            plugin.messages().send(player, "player-told", "counts", counts, "action", action, "where", where, "summary", summary);
        }
    }

    /** "4 fixed, 2 removed": how many stacks each action touched, leaving out the ones that did not happen. */
    private static String counts(Report report) {
        List<String> parts = new ArrayList<>();
        if (report.fixed > 0) {
            parts.add(report.fixed + " fixed");
        }
        if (report.removed > 0) {
            parts.add(report.removed + " removed");
        }
        if (report.reported > 0) {
            parts.add(report.reported + " reported");
        }
        return String.join(", ", parts);
    }

    private static String summarize(Set<String> lines) {
        List<String> all = new ArrayList<>(lines);
        if (all.size() <= 3) {
            return String.join("; ", all);
        }
        return String.join("; ", all.subList(0, 3)) + "; and " + (all.size() - 3) + " more";
    }
}
