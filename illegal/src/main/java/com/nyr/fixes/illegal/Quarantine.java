package com.nyr.fixes.illegal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * Every stack taken from a player or a container is kept here, one small file per stack under quarantine/, with who had it,
 * where and why. Staff can list them and give any of them back, so a wrong call by the rules never destroys anything.
 */
final class Quarantine {

    /** One kept stack, as listed. */
    record Entry(String id, String time, String player, String where, String reasons, String item, boolean restored) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final File directory;
    private final Logger logger;
    private final AtomicInteger counter = new AtomicInteger();
    private final ExecutorService writer;

    Quarantine(File directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NYR-ItemGuard-quarantine");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Keeps a stack and returns its id. The item is serialised on the calling thread, which owns it; only the write happens
     * on the quarantine thread.
     */
    String keep(ItemStack stack, String player, Location location, String where, String reasons) {
        LocalDateTime now = LocalDateTime.now();
        String id = now.format(ID_TIME) + "-" + Integer.toString(counter.incrementAndGet() % 46656, 36);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", id);
        yaml.set("time", now.format(STAMP));
        yaml.set("player", player);
        yaml.set("where", where + (location == null || location.getWorld() == null ? ""
            : String.format(Locale.ROOT, " at %s %d %d %d", location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ())));
        yaml.set("reasons", reasons);
        yaml.set("summary", Inspector.describe(stack));
        yaml.set("restored-by", null);
        // The stack is kept as its own YAML document inside the entry, so listing entries never has to rebuild items
        // (a stack saved by a newer server version can still be listed after a downgrade).
        YamlConfiguration item = new YamlConfiguration();
        item.set("item", stack.clone());
        yaml.set("item-yaml", item.saveToString());
        String text = yaml.saveToString();
        writer.execute(() -> {
            try {
                File file = new File(directory, id + ".yml");
                Files.createDirectories(directory.toPath());
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException cannotWrite) {
                logger.severe("Could not keep quarantined item " + id + ": " + cannotWrite.getMessage());
            }
        });
        return id;
    }

    /** The newest entries first. */
    List<Entry> list(int skip, int limit) {
        flush();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return List.of();
        }
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        List<Entry> entries = new ArrayList<>();
        for (int i = skip; i < files.length && entries.size() < limit; i++) {
            YamlConfiguration yaml = read(files[i]);
            if (yaml != null) {
                entries.add(new Entry(yaml.getString("id", files[i].getName()), yaml.getString("time", "?"), yaml.getString("player", "?"),
                    yaml.getString("where", "?"), yaml.getString("reasons", "?"), yaml.getString("summary", "?"), yaml.getString("restored-by") != null));
            }
        }
        return entries;
    }

    int size() {
        flush();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        return files == null ? 0 : files.length;
    }

    /** The kept stack for an id, or null when there is none (or the id is not a plain id). */
    ItemStack item(String id) {
        flush();
        if (!id.matches("[0-9a-z-]{8,32}")) {
            return null;
        }
        YamlConfiguration yaml = read(new File(directory, id + ".yml"));
        if (yaml == null || yaml.getString("item-yaml") == null) {
            return null;
        }
        YamlConfiguration item = new YamlConfiguration();
        try {
            item.loadFromString(yaml.getString("item-yaml"));
            return item.getItemStack("item");
        } catch (InvalidConfigurationException | RuntimeException unreadable) {
            logger.warning("Quarantined item " + id + " cannot be rebuilt on this server: " + unreadable.getMessage());
            return null;
        }
    }

    /** Marks an entry as given back, so the list shows it and nobody gives it out twice by mistake. */
    boolean markRestored(String id, String by) {
        File file = new File(directory, id + ".yml");
        YamlConfiguration yaml = read(file);
        if (yaml == null || yaml.getString("restored-by") != null) {
            return false;
        }
        yaml.set("restored-by", by + " at " + LocalDateTime.now().format(STAMP));
        try {
            yaml.save(file);
            return true;
        } catch (IOException cannotWrite) {
            logger.warning("Could not mark " + id + " as restored: " + cannotWrite.getMessage());
            return false;
        }
    }

    boolean isRestored(String id) {
        YamlConfiguration yaml = read(new File(directory, id + ".yml"));
        return yaml != null && yaml.getString("restored-by") != null;
    }

    private YamlConfiguration read(File file) {
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException | RuntimeException unreadable) {
            logger.warning("Could not read quarantine file " + file.getName() + ": " + unreadable.getMessage());
            return null;
        }
    }

    /** Waits until queued writes are on disk, so a listing includes the stack that was just taken. */
    void flush() {
        try {
            writer.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (Exception interruptedOrSlow) {
            // a listing without the newest entry is still correct later
        }
    }

    void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
