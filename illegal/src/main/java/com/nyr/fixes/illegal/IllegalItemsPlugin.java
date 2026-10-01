package com.nyr.fixes.illegal;

import com.nyr.fixes.common.FixCommand;
import com.nyr.fixes.common.FixPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** NYR Item Guard. Hacked, duped and admin-only items are fixed or taken into quarantine as they turn up. */
public class IllegalItemsPlugin extends FixPlugin {

    /** The plugin's first name. A server that ran it under that name keeps its folder: config, quarantine, review key. */
    static final String FORMER_NAME = "NYR-IllegalItems";

    private final Scanner.Stats stats = new Scanner.Stats();
    private Rules rules;
    private Quarantine quarantine;
    private ReviewMark reviewMark;
    private Scanner scanner;
    private ScanListener listener;

    @Override
    public String fixId() {
        return "illegal";
    }

    @Override
    public String displayName() {
        return "NYR Item Guard";
    }

    @Override
    protected String commandName() {
        return "itemguard";
    }

    @Override
    public void onLoad() {
        adoptFormerFolder(new File(getDataFolder().getParentFile(), FORMER_NAME), getDataFolder(), getLogger());
    }

    /** Moves the folder the plugin had under its first name to its current one, unless the current one already exists. */
    static void adoptFormerFolder(File former, File current, Logger logger) {
        if (!former.isDirectory()) {
            return;
        }
        if (current.exists()) {
            logger.warning(former + " is still there beside " + current + ", and only the second is read. Move anything you "
                + "still need from the first by hand, then delete it.");
            return;
        }
        try {
            Files.move(former.toPath(), current.toPath());
            logger.info("Moved " + former + " to " + current + ": config, quarantine and review key carry over.");
        } catch (IOException failed) {
            logger.warning("Could not move " + former + " to " + current + " (" + failed.getMessage() + "). Move it by hand to "
                + "keep the old config and quarantine.");
        }
    }

    @Override
    protected Set<String> freeFormSections() {
        return Set.of("max-levels");
    }

    @Override
    protected void startFix() {
        rules = Rules.from(settings());
        if (quarantine == null) {
            quarantine = new Quarantine(new File(getDataFolder(), "quarantine"), getLogger());
        }
        if (reviewMark == null) {
            try {
                reviewMark = ReviewMark.load(new NamespacedKey(this, "reviewed"), new File(getDataFolder(), "review-key"));
            } catch (IOException unreadable) {
                throw new IllegalStateException("could not read or create review-key in the plugin folder", unreadable);
            }
        }
        Inspector inspector = new Inspector(rules, getName().toLowerCase(Locale.ROOT), reviewMark);
        scanner = new Scanner(this, inspector, rules, quarantine, stats);
        listener = new ScanListener(this, scanner, rules);
        listen(listener);
        listener.startPeriodic();
        if (!rules.unknownMaterials().isEmpty()) {
            getLogger().info("These item names match nothing on this server version and are skipped: " + String.join(", ", rules.unknownMaterials()));
        }
    }

    @Override
    protected void stopFix() {
        listener = null;
        scanner = null;
        if (quarantine != null) {
            quarantine.flush();
        }
    }

    @Override
    protected void commands(FixCommand command) {
        command.add("inspect", "", permission("admin"), "Why the item in your hand is or is not illegal (changes nothing)", this::inspect, null);
        command.add("scan", "<player>", permission("admin"), "Scan an online player's inventory and ender chest now", this::scan,
            (sender, args) -> getServer().getOnlinePlayers().stream().map(Player::getName).toList());
        command.add("quarantine", "[page]", permission("admin"), "The newest items taken into quarantine", this::listQuarantine, null);
        command.add("restore", "<id>", permission("admin"), "Give yourself a quarantined item back", this::restore, null);
    }

    private boolean ready(CommandSender sender) {
        if (scanner == null) {
            messages().send(sender, "fix-off");
            return false;
        }
        return true;
    }

    private void inspect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "players-only");
            return;
        }
        if (!ready(sender)) {
            return;
        }
        Scanner current = scanner;
        scheduler().runAtEntity(player, task -> {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                messages().send(player, "inspect-empty");
                return;
            }
            Inspector.Outcome outcome = current.dryRun(hand);
            if (outcome.clean()) {
                messages().send(player, "inspect-clean", "item", Inspector.describe(hand));
                return;
            }
            messages().send(player, "inspect-header", "item", Inspector.describe(hand));
            for (Inspector.Finding finding : outcome.findings()) {
                messages().send(player, "inspect-line", "check", finding.check().key, "action", finding.action().name().toLowerCase(Locale.ROOT),
                    "detail", finding.detail());
            }
        });
    }

    private void scan(CommandSender sender, String[] args) {
        if (!ready(sender)) {
            return;
        }
        if (args.length == 0) {
            messages().send(sender, "scan-usage");
            return;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null) {
            messages().send(sender, "player-not-found", "player", args[0]);
            return;
        }
        ScanListener current = listener;
        scheduler().runAtEntity(target, task -> {
            int changed = current.scanPlayer(target);
            messages().send(sender, "scan-done", "player", target.getName(), "count", changed);
        });
    }

    private void listQuarantine(CommandSender sender, String[] args) {
        if (quarantine == null) {
            messages().send(sender, "fix-off");
            return;
        }
        int page = 1;
        if (args.length > 0) {
            try {
                page = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException notANumber) {
                page = 1;
            }
        }
        int perPage = 8;
        int total = quarantine.size();
        List<Quarantine.Entry> entries = quarantine.list((page - 1) * perPage, perPage);
        messages().send(sender, "quarantine-header", "count", total, "page", page, "pages", Math.max(1, (total + perPage - 1) / perPage));
        for (Quarantine.Entry entry : entries) {
            messages().send(sender, entry.restored() ? "quarantine-line-restored" : "quarantine-line", "id", entry.id(), "time", entry.time(),
                "player", entry.player(), "item", entry.item(), "where", entry.where(), "reasons", entry.reasons());
        }
        if (entries.isEmpty()) {
            messages().send(sender, "quarantine-empty");
        }
    }

    private void restore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "players-only");
            return;
        }
        if (quarantine == null || args.length == 0) {
            messages().send(sender, "restore-usage");
            return;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        ItemStack item = quarantine.item(id);
        if (item == null) {
            messages().send(sender, "restore-missing", "id", id);
            return;
        }
        if (quarantine.isRestored(id)) {
            messages().send(sender, "restore-already", "id", id);
            return;
        }
        if (!quarantine.markRestored(id, player.getName())) {
            messages().send(sender, "restore-missing", "id", id);
            return;
        }
        ItemStack reviewed = reviewMark.mark(item, id);
        scheduler().runAtEntity(player, task -> {
            for (ItemStack left : player.getInventory().addItem(reviewed).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
            }
            messages().send(player, "restored", "id", id, "item", Inspector.describe(item));
        });
    }

    @Override
    protected void status(List<String> lines) {
        if (rules == null) {
            return;
        }
        StringBuilder checks = new StringBuilder();
        for (Rules.Check check : Rules.Check.values()) {
            checks.append(checks.isEmpty() ? "" : "&7, ").append("&f").append(check.key).append(" &8").append(rules.action(check).name().toLowerCase(Locale.ROOT));
        }
        lines.add("&7Checks: " + checks);
        lines.add("&7Since start: &ffixed " + stats.fixed.sum() + " &8| &fremoved " + stats.removed.sum() + " &8| &freported " + stats.reported.sum()
            + " &8| &7in quarantine: &f" + (quarantine == null ? 0 : quarantine.size()));
        lines.add("&7Unobtainable items watched: &f" + rules.unobtainable().size() + " &8| &7plugin items exempt: &f" + rules.exemptPluginData()
            + " &8| &7creative players ignored: &f" + rules.ignoreCreative());
    }
}
