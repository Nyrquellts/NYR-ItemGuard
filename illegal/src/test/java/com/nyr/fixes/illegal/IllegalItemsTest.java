package com.nyr.fixes.illegal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IllegalItemsTest {

    private ServerMock server;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void scenariosOnTheCompiledClasses() {
        IllegalItemsPlugin plugin = MockBukkit.load(IllegalItemsPlugin.class);
        new IllegalScenarios(server, plugin).all();
    }

    @Test
    void aServerThatRanItAsIllegalItemsKeepsItsFolder(@TempDir File plugins) throws IOException {
        Logger logger = Logger.getAnonymousLogger();
        File former = new File(plugins, IllegalItemsPlugin.FORMER_NAME);
        File current = new File(plugins, "NYR-ItemGuard");
        IllegalItemsPlugin.adoptFormerFolder(former, current, logger);
        assertFalse(current.exists(), "with no former folder nothing is made");

        File kept = new File(former, "quarantine/q-1.yml");
        assertTrue(kept.getParentFile().mkdirs());
        Files.writeString(kept.toPath(), "id: q-1\n");
        IllegalItemsPlugin.adoptFormerFolder(former, current, logger);
        assertFalse(former.exists(), "the former folder is moved, not copied");
        assertEquals("id: q-1\n", Files.readString(new File(current, "quarantine/q-1.yml").toPath()));

        assertTrue(former.mkdirs());
        Files.writeString(new File(former, "config.yml").toPath(), "enabled: false\n");
        IllegalItemsPlugin.adoptFormerFolder(former, current, logger);
        assertTrue(new File(former, "config.yml").exists(), "a folder already under the new name wins; the former one is left");
        assertFalse(new File(current, "config.yml").exists());
    }

    private static Inspector inspector(String yaml) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        return new Inspector(Rules.from(config), "nyr-itemguard", null);
    }

    @Test
    void actionsFixRemoveReportAndOff() {
        ItemStack sharp = IllegalScenarios.enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 50);

        Inspector.Outcome fixed = inspector("checks: {over-enchanted: fix}").inspect(sharp);
        assertEquals(5, fixed.item().getEnchantmentLevel(Enchantment.SHARPNESS));
        assertTrue(fixed.changed() && fixed.taken().isEmpty());

        assertEquals(50, sharp.getEnchantmentLevel(Enchantment.SHARPNESS), "inspecting never changes the stack it was given");
        Inspector.Outcome removed = inspector("checks: {over-enchanted: remove}").inspect(sharp);
        assertNull(removed.item());
        assertEquals(1, removed.taken().size());

        Inspector.Outcome reported = inspector("checks: {over-enchanted: report}").inspect(sharp);
        assertSame(sharp, reported.item());
        assertFalse(reported.changed());
        assertEquals(1, reported.findings().size());

        Inspector.Outcome off = inspector("checks: {over-enchanted: 'off'}").inspect(sharp);
        assertTrue(off.clean());

        Inspector.Outcome raised = inspector("checks: {over-enchanted: fix}\nmax-levels: {sharpness: 10}").inspect(sharp);
        assertEquals(10, raised.item().getEnchantmentLevel(Enchantment.SHARPNESS), "max-levels raises the cap");
    }

    @Test
    void conflictsKeepTheHigherEnchantment() {
        ItemStack item = IllegalScenarios.enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 5);
        item.addUnsafeEnchantment(Enchantment.SMITE, 3);
        Inspector.Outcome outcome = inspector("checks: {}").inspect(item);
        assertEquals(5, outcome.item().getEnchantmentLevel(Enchantment.SHARPNESS));
        assertEquals(0, outcome.item().getEnchantmentLevel(Enchantment.SMITE));
    }

    @Test
    void overstackKeepsAFullStackAndQuarantinesTheRest() {
        ItemStack pearls = new ItemStack(Material.ENDER_PEARL, 1);
        pearls.setAmount(50);
        Inspector.Outcome outcome = inspector("checks: {}").inspect(pearls);
        assertEquals(16, outcome.item().getAmount());
        assertEquals(34, outcome.taken().get(0).getAmount());
    }
}
