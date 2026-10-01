package com.nyr.fixes.illegal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.opentest4j.TestAbortedException;

/** What Item Guard must do, through the Bukkit API only, so the same checks run on compiled classes and on the built jar. */
public final class IllegalScenarios {

    private final ServerMock server;
    private final Plugin plugin;

    public IllegalScenarios(ServerMock server, Plugin plugin) {
        this.server = server;
        this.plugin = plugin;
    }

    public void all() {
        try {
            survivalItemsAreNeverTouched();
            hackedItemsInAChestAreFixedOnOpen();
            bypassAndPluginItemsAreLeftAlone();
            quarantineListsAndForgedMarksDoNotProtect();
        } catch (TestAbortedException unimplemented) {
            fail("MockBukkit could not run part of the scenario, so it proved nothing: " + unimplemented.getMessage(), unimplemented);
        }
    }

    static ItemStack enchanted(Material type, Enchantment enchantment, int level) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(enchantment, level, true);
        item.setItemMeta(meta);
        return item;
    }

    private Inventory chestOpenedBy(PlayerMock player, ItemStack... items) {
        Inventory chest = server.createInventory(null, InventoryType.CHEST);
        for (int i = 0; i < items.length; i++) {
            chest.setItem(i, items[i]);
        }
        player.openInventory(chest);
        server.getPluginManager().callEvent(new InventoryOpenEvent(player.getOpenInventory()));
        return chest;
    }

    void survivalItemsAreNeverTouched() {
        PlayerMock kai = server.addPlayer("Kai");
        kai.setGameMode(GameMode.SURVIVAL);
        ItemStack sword = enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 5);
        ItemStack pickaxe = enchanted(Material.NETHERITE_PICKAXE, Enchantment.EFFICIENCY, 5);
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 64);
        ItemStack pearls = new ItemStack(Material.ENDER_PEARL, 16);
        Inventory chest = chestOpenedBy(kai, sword.clone(), pickaxe.clone(), book.clone(), diamonds.clone(), pearls.clone());
        assertEquals(sword, chest.getItem(0), "a Sharpness V diamond sword is legal");
        assertEquals(pickaxe, chest.getItem(1));
        assertEquals(book, chest.getItem(2));
        assertEquals(diamonds, chest.getItem(3));
        assertEquals(pearls, chest.getItem(4));
        kai.closeInventory();
    }

    void hackedItemsInAChestAreFixedOnOpen() {
        PlayerMock luna = server.addPlayer("Luna");
        luna.setGameMode(GameMode.SURVIVAL);
        drain(luna);
        ItemStack sharp255 = enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 255);
        ItemStack stick = enchanted(Material.STICK, Enchantment.SHARPNESS, 3);
        ItemStack unbreakable = new ItemStack(Material.NETHERITE_CHESTPLATE);
        ItemMeta meta = unbreakable.getItemMeta();
        meta.setUnbreakable(true);
        unbreakable.setItemMeta(meta);
        ItemStack bedrock = new ItemStack(Material.BEDROCK, 64);
        ItemStack totems = new ItemStack(Material.TOTEM_OF_UNDYING, 1);
        totems.setAmount(64);

        Inventory chest = chestOpenedBy(luna, sharp255, stick, unbreakable, bedrock, totems);
        ItemStack sword = chest.getItem(0);
        assertEquals(5, sword.getEnchantmentLevel(Enchantment.SHARPNESS), "Sharpness 255 becomes Sharpness 5");
        assertEquals(0, chest.getItem(1).getEnchantmentLevel(Enchantment.SHARPNESS), "Sharpness comes off a stick");
        assertFalse(chest.getItem(2).getItemMeta().isUnbreakable(), "the chestplate can break again");
        assertNull(chest.getItem(3), "bedrock is taken");
        assertEquals(1, chest.getItem(4).getAmount(), "64 totems become one; the rest go to quarantine");
        List<String> told = drain(luna);
        assertTrue(told.stream().anyMatch(line -> line.contains("cannot exist in survival")), told.toString());
        luna.closeInventory();
    }

    void bypassAndPluginItemsAreLeftAlone() {
        PlayerMock staff = server.addPlayer("Staff");
        staff.setGameMode(GameMode.SURVIVAL);
        staff.addAttachment(plugin, "nyritemguard.bypass", true);
        ItemStack hacked = enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 100);
        Inventory seen = chestOpenedBy(staff, hacked.clone());
        assertEquals(100, seen.getItem(0).getEnchantmentLevel(Enchantment.SHARPNESS), "nyritemguard.bypass opens chests untouched");
        staff.closeInventory();

        PlayerMock kai = server.getPlayerExact("Kai") instanceof PlayerMock existing ? existing : server.addPlayer("Kai");
        ItemStack custom = enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 10);
        ItemMeta meta = custom.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey("mycustomitems", "id"), PersistentDataType.STRING, "excalibur");
        custom.setItemMeta(meta);
        Inventory crate = chestOpenedBy(kai, custom.clone());
        assertEquals(10, crate.getItem(0).getEnchantmentLevel(Enchantment.SHARPNESS), "another plugin's item keeps its Sharpness X");
        kai.closeInventory();

        PlayerMock builder = server.addPlayer("Builder");
        builder.setGameMode(GameMode.CREATIVE);
        Inventory creative = chestOpenedBy(builder, new ItemStack(Material.BARRIER));
        assertEquals(Material.BARRIER, creative.getItem(0).getType(), "creative players are not checked");
        builder.closeInventory();
    }

    /**
     * Listing and the review mark. Giving an item back needs the server to rebuild a stack from YAML, which MockBukkit's
     * item stacks cannot do; the live scenario (testbed/scenarios/illegal.mjs) restores items on real servers.
     */
    void quarantineListsAndForgedMarksDoNotProtect() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        admin.setGameMode(GameMode.SURVIVAL);
        drain(admin);
        assertTrue(admin.performCommand("itemguard quarantine"));
        List<String> listed = drain(admin);
        assertTrue(listed.stream().anyMatch(line -> line.contains("Quarantine:") && line.contains("2 stack(s)")), listed.toString());
        String bedrockLine = listed.stream().filter(line -> line.contains("64 bedrock")).findFirst().orElseThrow(() -> new AssertionError(listed.toString()));
        String id = bedrockLine.replaceAll("§.", "").trim().split(" ")[0];
        assertTrue(id.matches("[0-9]{12}-[0-9a-z]+"), "quarantine ids look like " + id);
        assertTrue(listed.stream().anyMatch(line -> line.contains("63 totem of undying")), "the 63 extra totems are kept too: " + listed);

        ItemStack forged = new ItemStack(Material.BARRIER);
        ItemMeta meta = forged.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "reviewed"), PersistentDataType.STRING, id + ":00000000000000000000000000000000");
        forged.setItemMeta(meta);
        Inventory forgedChest = chestOpenedBy(admin, forged);
        assertNull(forgedChest.getItem(0), "a forged review mark does not protect an item");
        admin.closeInventory();

        assertTrue(admin.performCommand("itemguard restore nope-not-an-id"));
        assertTrue(drain(admin).stream().anyMatch(line -> line.contains("No quarantined item")));

        assertTrue(admin.performCommand("itemguard status"));
        List<String> status = drain(admin);
        assertTrue(status.stream().anyMatch(line -> line.contains("NYR Item Guard")), status.toString());
        assertTrue(status.stream().anyMatch(line -> line.contains("in quarantine: ") && line.contains("3")), status.toString());
    }

    private static List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            lines.add(line);
        }
        return lines;
    }
}
