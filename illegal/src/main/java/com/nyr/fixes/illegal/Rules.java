package com.nyr.fixes.illegal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Which checks run, what each does when it finds something, and what is never touched. */
record Rules(Map<Check, Action> actions, Map<String, Integer> maxLevels, Set<Material> unobtainable, List<String> unknownMaterials,
             boolean exemptPluginData, boolean exemptCustomModels, Set<Material> exemptMaterials, List<String> exemptNameContains,
             List<String> exemptLoreContains, boolean ignoreCreative, boolean tellPlayer, Scan scan) {

    enum Check {
        UNOBTAINABLE("unobtainable"),
        OVERSTACKED("overstacked"),
        STACK_SIZE("stack-size-component"),
        OVER_ENCHANTED("over-enchanted"),
        WRONG_ENCHANTMENT("enchantment-not-for-this-item"),
        CONFLICTING_ENCHANTMENTS("conflicting-enchantments"),
        UNBREAKABLE("unbreakable"),
        ATTRIBUTES("attribute-modifiers"),
        POTION_EFFECTS("custom-potion-effects"),
        ENTITY_DATA("entity-data");

        final String key;

        Check(String key) {
            this.key = key;
        }
    }

    /** fix repairs the item, remove takes it away (into quarantine), report only alerts staff, off skips the check. */
    enum Action {
        FIX, REMOVE, REPORT, OFF
    }

    /** Where items are looked at. */
    record Scan(boolean onJoin, boolean onOpen, boolean onClick, boolean onPickup, boolean onDroppedItems, boolean onCreative,
                boolean onPlace, int periodicSeconds, boolean enderChest) {
    }

    static Rules from(ConfigurationSection root) {
        Map<Check, Action> actions = new java.util.EnumMap<>(Check.class);
        for (Check check : Check.values()) {
            String raw = root.getString("checks." + check.key, "fix");
            Action action;
            try {
                action = Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException typo) {
                action = Action.REPORT;
            }
            actions.put(check, action);
        }
        Map<String, Integer> levels = new HashMap<>();
        ConfigurationSection overrides = root.getConfigurationSection("max-levels");
        if (overrides != null) {
            for (String key : overrides.getKeys(false)) {
                levels.put(key.toLowerCase(Locale.ROOT).replace("minecraft:", ""), Math.max(1, overrides.getInt(key)));
            }
        }
        List<String> unknown = new ArrayList<>();
        Set<Material> unobtainable = materials(root.getStringList("unobtainable-items"), unknown);
        Set<Material> exemptMaterials = materials(root.getStringList("exempt.materials"), unknown);
        Scan scan = new Scan(
            root.getBoolean("scan.on-join", true),
            root.getBoolean("scan.on-open", true),
            root.getBoolean("scan.on-click", true),
            root.getBoolean("scan.on-pickup", true),
            root.getBoolean("scan.dropped-items", true),
            root.getBoolean("scan.creative-inventory", true),
            root.getBoolean("scan.on-place", true),
            Math.max(0, root.getInt("scan.every-seconds", 60)),
            root.getBoolean("scan.ender-chest", true));
        return new Rules(actions, levels, unobtainable, unknown,
            root.getBoolean("exempt.items-with-plugin-data", true),
            root.getBoolean("exempt.items-with-custom-models", true),
            exemptMaterials,
            lower(root.getStringList("exempt.name-contains")),
            lower(root.getStringList("exempt.lore-contains")),
            root.getBoolean("ignore-creative-players", true),
            root.getBoolean("tell-player", true),
            scan);
    }

    Action action(Check check) {
        return actions.getOrDefault(check, Action.OFF);
    }

    private static List<String> lower(List<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).filter(v -> !v.isBlank()).toList();
    }

    /** Material names, ids or wildcards (infested_*); names this version does not have are listed in {@code unknown}. */
    static Set<Material> materials(List<String> patterns, List<String> unknown) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String raw : patterns) {
            String pattern = raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
            if (pattern.isEmpty()) {
                continue;
            }
            Pattern glob = Pattern.compile(Pattern.quote(pattern).replace("*", "\\E.*\\Q"));
            boolean matched = false;
            for (Material material : Material.values()) {
                if (!material.isLegacy() && material.isItem() && glob.matcher(material.name().toLowerCase(Locale.ROOT)).matches()) {
                    out.add(material);
                    matched = true;
                }
            }
            if (!matched) {
                unknown.add(raw);
            }
        }
        return out;
    }
}
