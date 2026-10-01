package com.nyr.fixes.illegal;

import com.nyr.fixes.illegal.Rules.Action;
import com.nyr.fixes.illegal.Rules.Check;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

/**
 * Decides whether one item stack is legal and what it becomes. Pure: it never touches an inventory, so the same answer comes
 * back for the same item wherever it is found. Shulker boxes, bundles and other containers carried as items are inspected
 * too, one level down.
 */
final class Inspector {

    /** One thing wrong with an item, and what the rules do about it. */
    record Finding(Check check, Action action, String detail) {
    }

    /**
     * What an item becomes: {@code item} is the stack to keep (null when the whole stack goes), {@code taken} the stacks
     * that go to quarantine (the whole item, the excess of an overstack, or items removed from inside a container).
     */
    record Outcome(ItemStack item, List<ItemStack> taken, List<Finding> findings, boolean changed) {
        boolean clean() {
            return findings.isEmpty();
        }

        boolean removed() {
            return item == null;
        }
    }

    private static final Outcome CLEAN = new Outcome(null, List.of(), List.of(), false);
    private static final int MAX_DEPTH = 2;
    private static final MethodHandle COMPONENT_STRING = optional("getAsComponentString", "getAsString");
    private static final MethodHandle HAS_ITEM_MODEL = optionalBoolean("hasItemModel");
    /** entity_data as a component name, not as the end of bucket_entity_data or block_entity_data. */
    private static final java.util.regex.Pattern ENTITY_DATA = java.util.regex.Pattern.compile("(^|[^a-z_])entity_data");

    private final Rules rules;
    private final String ownNamespace;
    private final ReviewMark reviewed;

    /** {@code reviewed} may be null (tests); items carrying a valid review mark are then never exempt by it. */
    Inspector(Rules rules, String ownNamespace, ReviewMark reviewed) {
        this.rules = rules;
        this.ownNamespace = ownNamespace;
        this.reviewed = reviewed;
    }

    private static MethodHandle optional(String... names) {
        for (String name : names) {
            try {
                return MethodHandles.publicLookup().findVirtual(ItemMeta.class, name, MethodType.methodType(String.class));
            } catch (NoSuchMethodException | IllegalAccessException absent) {
                // older API: try the next name
            }
        }
        return null;
    }

    private static MethodHandle optionalBoolean(String name) {
        try {
            return MethodHandles.publicLookup().findVirtual(ItemMeta.class, name, MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException | IllegalAccessException absent) {
            return null;
        }
    }

    /** The outcome for a stack; a clean outcome keeps the original stack untouched. */
    Outcome inspect(ItemStack item) {
        Outcome outcome = inspect(item, 0);
        return outcome == CLEAN ? new Outcome(item, List.of(), List.of(), false) : outcome;
    }

    private Outcome inspect(ItemStack original, int depth) {
        if (original == null || original.getType().isAir() || original.getAmount() <= 0) {
            return CLEAN;
        }
        Material type = original.getType();
        if (rules.exemptMaterials().contains(type)) {
            return CLEAN;
        }
        // Most stacks carry no data at all; those are clean unless listed or overstacked, and need no copy to find out.
        if (!original.hasItemMeta() && !rules.unobtainable().contains(type) && original.getAmount() <= type.getMaxStackSize()) {
            return CLEAN;
        }
        // Work on a copy: whatever the server hands out as meta, the stack the caller holds is never changed here.
        ItemStack working = original.clone();
        ItemMeta meta = working.hasItemMeta() ? working.getItemMeta() : null;
        if (meta != null && (exempt(meta) || reviewed != null && reviewed.marked(meta, type))) {
            return CLEAN;
        }

        List<Finding> findings = new ArrayList<>();
        List<ItemStack> taken = new ArrayList<>();
        boolean removeWhole = false;

        if (rules.unobtainable().contains(type)) {
            removeWhole |= found(findings, Check.UNOBTAINABLE, describe(original) + " cannot be obtained in survival");
        }

        boolean metaChanged = false;
        if (meta != null && !removeWhole) {
            if (hasEntityData(meta, type)) {
                removeWhole |= found(findings, Check.ENTITY_DATA, describe(original) + " carries entity data");
            }
            if (meta.hasMaxStackSize() && meta.getMaxStackSize() > type.getMaxStackSize()) {
                if (found(findings, Check.STACK_SIZE, "stack size raised to " + meta.getMaxStackSize() + " (normally " + type.getMaxStackSize() + ")")) {
                    removeWhole |= rules.action(Check.STACK_SIZE) == Action.REMOVE;
                    meta.setMaxStackSize(null);
                    metaChanged = true;
                }
            }
            EnchantResult enchants = enchantments(original, meta, findings);
            removeWhole |= enchants.remove();
            metaChanged |= enchants.changed();
            if (meta.isUnbreakable() && found(findings, Check.UNBREAKABLE, "unbreakable")) {
                removeWhole |= rules.action(Check.UNBREAKABLE) == Action.REMOVE;
                meta.setUnbreakable(false);
                metaChanged = true;
            }
            if (customAttributes(meta, type) && found(findings, Check.ATTRIBUTES, "custom attribute modifiers")) {
                removeWhole |= rules.action(Check.ATTRIBUTES) == Action.REMOVE;
                restoreDefaultAttributes(meta, type);
                metaChanged = true;
            }
            if (meta instanceof PotionMeta potion && potion.hasCustomEffects()
                && found(findings, Check.POTION_EFFECTS, potion.getCustomEffects().size() + " custom potion effect(s)")) {
                removeWhole |= rules.action(Check.POTION_EFFECTS) == Action.REMOVE;
                potion.clearCustomEffects();
                metaChanged = true;
            }
            if (depth < MAX_DEPTH && !removeWhole) {
                metaChanged |= contents(meta, depth, findings, taken);
            }
        }

        if (removeWhole) {
            return new Outcome(null, List.of(original.clone()), findings, true);
        }

        boolean changed = metaChanged;
        if (metaChanged) {
            working.setItemMeta(meta);
        }
        int max = working.getMaxStackSize();
        if (working.getAmount() > max && found(findings, Check.OVERSTACKED, working.getAmount() + " in one stack (at most " + max + ")")) {
            if (rules.action(Check.OVERSTACKED) == Action.REMOVE) {
                return new Outcome(null, List.of(original.clone()), findings, true);
            }
            ItemStack excess = working.clone();
            excess.setAmount(working.getAmount() - max);
            taken.add(excess);
            working.setAmount(max);
            changed = true;
        }

        if (findings.isEmpty()) {
            return CLEAN;
        }
        return new Outcome(changed ? working : original, taken, findings, changed);
    }

    /**
     * Records a finding unless its check is off.
     *
     * @return true when the rules change the item for it (fix or remove); false when they only report it
     */
    private boolean found(List<Finding> findings, Check check, String detail) {
        Action action = rules.action(check);
        if (action == Action.OFF) {
            return false;
        }
        findings.add(new Finding(check, action, detail));
        return action == Action.FIX || action == Action.REMOVE;
    }

    private record EnchantResult(boolean changed, boolean remove) {
    }

    private EnchantResult enchantments(ItemStack item, ItemMeta meta, List<Finding> findings) {
        boolean stored = meta instanceof EnchantmentStorageMeta;
        Map<Enchantment, Integer> enchants = stored ? ((EnchantmentStorageMeta) meta).getStoredEnchants() : meta.getEnchants();
        if (enchants.isEmpty()) {
            return new EnchantResult(false, false);
        }
        boolean changed = false;
        boolean remove = false;
        List<Map.Entry<Enchantment, Integer>> ordered = new ArrayList<>(enchants.entrySet());
        ordered.sort(Comparator.<Map.Entry<Enchantment, Integer>>comparingInt(Map.Entry::getValue).reversed()
            .thenComparing(entry -> key(entry.getKey())));
        List<Enchantment> kept = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> entry : ordered) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();
            String name = key(enchantment);

            if (!stored && !enchantment.canEnchantItem(plain(item))) {
                if (found(findings, Check.WRONG_ENCHANTMENT, name + " does not go on " + pretty(item.getType()))) {
                    remove |= rules.action(Check.WRONG_ENCHANTMENT) == Action.REMOVE;
                    removeEnchant(meta, enchantment, stored);
                    changed = true;
                    continue;
                }
            }
            Enchantment clash = null;
            for (Enchantment other : kept) {
                if (other.conflictsWith(enchantment) || enchantment.conflictsWith(other)) {
                    clash = other;
                    break;
                }
            }
            if (clash != null && found(findings, Check.CONFLICTING_ENCHANTMENTS, name + " conflicts with " + key(clash))) {
                remove |= rules.action(Check.CONFLICTING_ENCHANTMENTS) == Action.REMOVE;
                removeEnchant(meta, enchantment, stored);
                changed = true;
                continue;
            }
            int max = rules.maxLevels().getOrDefault(name, enchantment.getMaxLevel());
            if (level > max && found(findings, Check.OVER_ENCHANTED, name + " " + level + " is over its maximum of " + max)) {
                remove |= rules.action(Check.OVER_ENCHANTED) == Action.REMOVE;
                removeEnchant(meta, enchantment, stored);
                addEnchant(meta, enchantment, max, stored);
                changed = true;
            }
            kept.add(enchantment);
        }
        return new EnchantResult(changed, remove);
    }

    /** Explicit attribute modifiers that differ from the ones the item type has anyway. */
    private static boolean customAttributes(ItemMeta meta, Material type) {
        return meta.hasAttributeModifiers() && !signature(meta.getAttributeModifiers()).equals(defaultSignature(type));
    }

    /**
     * Puts back the item type's own modifiers. Clearing them is not enough: on 1.20.6 an item then keeps an empty modifier
     * list, which replaces its normal attributes and leaves a sword with the damage of a bare hand. Modifiers equal to the
     * type's defaults are not reported again.
     */
    private static void restoreDefaultAttributes(ItemMeta meta, Material type) {
        Multimap<Attribute, AttributeModifier> defaults = defaults(type);
        meta.setAttributeModifiers(defaults.isEmpty() ? null : defaults);
    }

    private static Multimap<Attribute, AttributeModifier> defaults(Material type) {
        Multimap<Attribute, AttributeModifier> all = LinkedHashMultimap.create();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try {
                all.putAll(type.getDefaultAttributeModifiers(slot));
            } catch (IllegalArgumentException slotNotForThisItem) {
                // body and saddle slots are refused for most items
            }
        }
        return all;
    }

    private static Set<String> defaultSignature(Material type) {
        return signature(defaults(type));
    }

    /** Modifiers compared by what they do (attribute, amount, operation, slot), not by their ids. */
    private static Set<String> signature(Multimap<Attribute, AttributeModifier> modifiers) {
        Set<String> out = new HashSet<>();
        if (modifiers == null) {
            return out;
        }
        for (Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
            Object attribute = entry.getKey();
            AttributeModifier modifier = entry.getValue();
            Object operation = modifier.getOperation();
            Object slot = modifier.getSlotGroup();
            out.add(attribute + "|" + modifier.getAmount() + "|" + operation + "|" + slot);
        }
        return out;
    }

    /** The item's type alone, so applicability is judged by what the item is, not by what is already on it. */
    private static ItemStack plain(ItemStack item) {
        return new ItemStack(item.getType());
    }

    private static void removeEnchant(ItemMeta meta, Enchantment enchantment, boolean stored) {
        if (stored) {
            ((EnchantmentStorageMeta) meta).removeStoredEnchant(enchantment);
        } else {
            meta.removeEnchant(enchantment);
        }
    }

    private static void addEnchant(ItemMeta meta, Enchantment enchantment, int level, boolean stored) {
        if (stored) {
            ((EnchantmentStorageMeta) meta).addStoredEnchant(enchantment, level, true);
        } else {
            meta.addEnchant(enchantment, level, true);
        }
    }

    /** Inspects what a shulker box, bundle or other container item holds; true when its contents changed. */
    private boolean contents(ItemMeta meta, int depth, List<Finding> findings, List<ItemStack> taken) {
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            List<ItemStack> items = new ArrayList<>(bundle.getItems());
            boolean changed = false;
            for (int i = 0; i < items.size(); i++) {
                Outcome inner = inspect(items.get(i), depth + 1);
                if (inner != CLEAN) {
                    inside(findings, inner, "bundle");
                    taken.addAll(inner.taken());
                    if (inner.changed()) {
                        items.set(i, inner.item());
                        changed = true;
                    }
                }
            }
            if (changed) {
                items.removeIf(stack -> stack == null);
                bundle.setItems(items);
            }
            return changed;
        }
        if (meta instanceof BlockStateMeta holder && holder.hasBlockState()) {
            BlockState state = holder.getBlockState();
            if (!(state instanceof Container container)) {
                return false;
            }
            Inventory inventory = container.getSnapshotInventory();
            boolean changed = false;
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                Outcome inner = inspect(inventory.getItem(slot), depth + 1);
                if (inner != CLEAN) {
                    inside(findings, inner, pretty(state.getType()));
                    taken.addAll(inner.taken());
                    if (inner.changed()) {
                        inventory.setItem(slot, inner.item());
                        changed = true;
                    }
                }
            }
            if (changed) {
                holder.setBlockState(state);
            }
            return changed;
        }
        return false;
    }

    private static void inside(List<Finding> findings, Outcome inner, String container) {
        for (Finding finding : inner.findings()) {
            findings.add(new Finding(finding.check(), finding.action(), "inside the " + container + ": " + finding.detail()));
        }
    }

    private boolean exempt(ItemMeta meta) {
        if (rules.exemptPluginData()) {
            for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                if (!key.getNamespace().equals(ownNamespace)) {
                    return true;
                }
            }
        }
        if (rules.exemptCustomModels() && (hasCustomModelData(meta) || hasItemModel(meta))) {
            return true;
        }
        if (!rules.exemptNameContains().isEmpty() && meta.hasDisplayName()) {
            String name = plainText(displayName(meta));
            for (String needle : rules.exemptNameContains()) {
                if (name.contains(needle)) {
                    return true;
                }
            }
        }
        if (!rules.exemptLoreContains().isEmpty() && meta.hasLore()) {
            for (String line : lore(meta)) {
                String text = plainText(line);
                for (String needle : rules.exemptLoreContains()) {
                    if (text.contains(needle)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static boolean hasCustomModelData(ItemMeta meta) {
        return meta.hasCustomModelData();
    }

    private static boolean hasItemModel(ItemMeta meta) {
        if (HAS_ITEM_MODEL == null) {
            return false;
        }
        try {
            return (boolean) HAS_ITEM_MODEL.invokeExact(meta);
        } catch (Throwable unsupported) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static String displayName(ItemMeta meta) {
        return meta.getDisplayName();
    }

    @SuppressWarnings("deprecation")
    private static List<String> lore(ItemMeta meta) {
        List<String> lore = meta.getLore();
        return lore == null ? List.of() : lore;
    }

    private static String plainText(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-orx]", "").toLowerCase(Locale.ROOT);
    }

    /** Entity data on anything but a painting (creative paintings carry their variant that way) is creative-only. */
    private static boolean hasEntityData(ItemMeta meta, Material type) {
        if (COMPONENT_STRING == null || type == Material.PAINTING) {
            return false;
        }
        try {
            String components = (String) COMPONENT_STRING.invokeExact(meta);
            return components != null && ENTITY_DATA.matcher(components).find();
        } catch (Throwable unsupported) {
            return false;
        }
    }

    static String key(Enchantment enchantment) {
        return enchantment.getKey().getKey();
    }

    static String pretty(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static String describe(ItemStack item) {
        return (item.getAmount() > 1 ? item.getAmount() + " " : "") + pretty(item.getType());
    }
}
