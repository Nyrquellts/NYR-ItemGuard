package com.nyr.fixes.illegal.jar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.nyr.fixes.illegal.IllegalScenarios;
import java.io.File;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loads the built NYR-ItemGuard jar, not the compiled classes, and runs the same scenarios through it. */
class IllegalItemsJarTest {

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
    void theShippedJarCleansIllegalItems() throws Exception {
        String path = System.getProperty("nyr.fixes.jar");
        assertNotNull(path, "run through the jarTest task, which names the jar");
        File jar = new File(path);
        Plugin plugin = server.getPluginManager().loadPlugin(jar);
        // A real server registers plugin.yml permissions with their defaults; MockBukkit's loadPlugin(File) does not, and an
        // unregistered permission is granted to every op, bypass nodes included.
        for (org.bukkit.permissions.Permission permission : descriptionPermissions(plugin)) {
            if (server.getPluginManager().getPermission(permission.getName()) == null) {
                server.getPluginManager().addPermission(permission);
            }
        }
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.isEnabled(), "the jar enables");
        assertEquals("com.nyr.fixes.illegal.IllegalItemsPlugin", plugin.getClass().getName());
        assertEquals(jar.toURI().toURL().toString(), plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
        new IllegalScenarios(server, plugin).all();
    }

    @SuppressWarnings("deprecation")
    private static java.util.List<org.bukkit.permissions.Permission> descriptionPermissions(Plugin plugin) {
        return plugin.getDescription().getPermissions();
    }
}
