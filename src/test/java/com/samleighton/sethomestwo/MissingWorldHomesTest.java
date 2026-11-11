package com.samleighton.sethomestwo;

import com.samleighton.sethomestwo.dao.HomesDao;
import com.samleighton.sethomestwo.dao.TeleportAttemptsDao;
import com.samleighton.sethomestwo.items.HomeItem;
import com.samleighton.sethomestwo.models.Home;
import com.samleighton.sethomestwo.support.HomeFixtures;
import com.samleighton.sethomestwo.support.ServerTestBase;
import com.samleighton.sethomestwo.support.TestPlayer;
import com.samleighton.sethomestwo.utils.DatabaseUtil;
import org.bukkit.Location;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MissingWorldHomesTest extends ServerTestBase {

    private Home saveHome(TestPlayer player, String name) throws SQLException {
        Home home = HomeFixtures.home(player, name, new Location(nether, 123.25, 70, -35.75, 80, 12));
        home.setDescription("Original description");
        HomesDao dao = new HomesDao();
        assertTrue(dao.save(home));
        try (Statement statement = dao.conn.createStatement();
             ResultSet rs = statement.executeQuery("select max(id) from players_homes")) {
            assertTrue(rs.next());
            home.setId(rs.getInt(1));
        }
        return home;
    }

    private List<List<Object>> savedRows() throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Statement statement = plugin.getConnectionManager().getConnection("homes").createStatement();
             ResultSet rs = statement.executeQuery("select id, player_uuid, material, world, name, description,"
                     + " x, y, z, pitch, yaw, dimension from players_homes order by id")) {
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int column = 1; column <= 12; column++) row.add(rs.getObject(column));
                rows.add(row);
            }
        }
        return rows;
    }

    @Test
    void everyLookupPreservesHomesWhoseWorldIsUnloaded() throws SQLException {
        TestPlayer player = addPlayer();
        Home missing = saveHome(player, "oldbase");
        HomeFixtures.persist(player, "current");
        List<List<Object>> before = savedRows();
        assertTrue(server.unloadWorld(nether, false));

        HomesDao dao = new HomesDao();
        assertEquals(2, dao.getAll(player.getUniqueId()).size());
        for (Home loaded : List.of(dao.get(player.getUniqueId(), "oldbase"),
                dao.getById(player.getUniqueId(), missing.getId()),
                dao.getAll(player.getUniqueId()).stream().filter(h -> h.getName().equals("oldbase")).findFirst().orElseThrow())) {
            assertEquals(missing.getWorld(), loaded.getWorld());
            assertEquals(123.25, loaded.getX());
            assertEquals(70, loaded.getY());
            assertEquals(-35.75, loaded.getZ());
            assertEquals(80, loaded.getYaw());
            assertEquals(12, loaded.getPitch());
            assertEquals("Original description", loaded.getDescription());
        }
        assertEquals(before, savedRows());
    }

    @Test
    void menusAndChatListStillShowOldHomes() throws SQLException {
        TestPlayer owner = addPlayer("owner");
        TestPlayer admin = addPlayer("admin");
        admin.setOp(true);
        saveHome(owner, "oldbase");
        HomeFixtures.persist(owner, "current");
        List<List<Object>> before = savedRows();
        assertTrue(server.unloadWorld(nether, false));

        assertTrue(server.execute("homes", owner).hasSucceeded());
        assertEquals("oldbase", owner.getOpenInventory().getTopInventory().getItem(0).getItemMeta().getDisplayName());
        server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                new HomeItem(owner), null, null));
        assertEquals("current", owner.getOpenInventory().getTopInventory().getItem(1).getItemMeta().getDisplayName());
        assertTrue(server.execute("get-player-homes", admin, "owner").hasSucceeded());
        assertEquals("oldbase", admin.getOpenInventory().getTopInventory().getItem(0).getItemMeta().getDisplayName());
        assertTrue(server.execute("list-homes", owner).hasSucceeded());
        String message;
        boolean warned = false;
        while ((message = owner.nextMessage()) != null) warned |= message.contains("Unavailable");
        assertTrue(warned);
        assertEquals(before, savedRows());
    }

    @Test
    void missingWorldCannotTeleportEvenWithSafetyDisabledOrAdminBypass() throws SQLException {
        TestPlayer player = addPlayer();
        player.setOp(true);
        saveHome(player, "oldbase");
        List<List<Object>> before = savedRows();
        Location start = player.getLocation();
        assertTrue(server.unloadWorld(nether, false));

        for (boolean safety : List.of(true, false)) {
            plugin.getConfig().set("teleportSafety", safety);
            assertTrue(server.execute("home", player, "oldbase").hasSucceeded());
            assertTrue(player.nextMessage().contains("world is not loaded"));
            assertNull(new TeleportAttemptsDao().get(player));
            assertEquals(start, player.getLocation());
        }
        assertEquals(before, savedRows());
    }

    @Test
    void worldUnloadingDuringCountdownCancelsWithoutChangingHomes() throws SQLException {
        TestPlayer player = addPlayer();
        plugin.getConfig().set("teleportSafety", false);
        plugin.getConfig().set("delay", 1);
        Home home = saveHome(player, "oldbase");
        List<List<Object>> before = savedRows();
        Location start = player.getLocation();
        home.teleport(player);
        server.getScheduler().performOneTick();
        assertTrue(server.unloadWorld(nether, false));
        server.getScheduler().performTicks(40);

        assertTrue(player.nextMessage().contains("world is not loaded"));
        assertNull(new TeleportAttemptsDao().get(player));
        assertEquals(start, player.getLocation());
        assertEquals(before, savedRows());
    }

    @Test
    void restoringTheOriginalWorldMakesTheHomeUsableAgain() throws SQLException {
        TestPlayer player = addPlayer();
        player.setOp(true);
        plugin.getConfig().set("teleportSafety", false);
        Home home = saveHome(player, "oldbase");
        List<List<Object>> before = savedRows();
        assertTrue(server.unloadWorld(nether, false));
        Home unloaded = new HomesDao().getById(player.getUniqueId(), home.getId());
        assertNull(unloaded.asLocation().getWorld());
        server.addWorld(nether);
        unloaded.teleport(player);
        server.getScheduler().performOneTick();

        assertEquals(nether, player.getWorld());
        assertEquals(123.25, player.getLocation().getX());
        assertEquals(80, player.getLocation().getYaw());
        assertEquals(12, player.getLocation().getPitch());
        assertEquals(before, savedRows());
    }

    @Test
    void upgradingTheOldSchemaKeepsEverySavedFieldIncludingMissingWorldHomes() throws SQLException {
        TestPlayer player = addPlayer();
        saveHome(player, "oldbase");
        HomeFixtures.persist(player, "current");
        var connection = plugin.getConnectionManager().getConnection("homes");
        assertTrue(DatabaseUtil.execute(connection, "alter table players_homes drop column player_name"));
        List<List<Object>> before = savedRows();
        assertTrue(server.unloadWorld(nether, false));

        assertTrue(DatabaseUtil.initTables(connection));
        assertEquals(2, new HomesDao().getAll(player.getUniqueId()).size());
        assertEquals(before, savedRows());
    }

    @Test
    void consoleRemapsOnlyTheWorldOfEveryMatchingHome() throws SQLException {
        TestPlayer first = addPlayer();
        TestPlayer second = addPlayer();
        Home oldHome = saveHome(first, "oldbase");
        saveHome(second, "oldmine");
        HomeFixtures.persist(first, "current");
        var destination = server.addSimpleWorld("PaleSky");
        String oldWorld = oldHome.getWorld();
        assertTrue(server.unloadWorld(nether, false));
        List<List<Object>> expected = savedRows();
        for (List<Object> row : expected) {
            if (oldWorld.equals(row.get(3))) row.set(3, destination.getUID().toString());
        }

        assertTrue(server.executeConsole("remap-home-world", oldWorld, "PaleSky").hasSucceeded());
        assertTrue(server.getConsoleSender().nextMessage().contains("Updated 2 home(s)"));
        assertEquals(expected, savedRows());
        assertEquals(destination, new HomesDao().get(first.getUniqueId(), "oldbase").asLocation().getWorld());

        assertTrue(server.executeConsole("remap-home-world", oldWorld, "PaleSky").hasSucceeded());
        assertTrue(server.getConsoleSender().nextMessage().contains("Updated 0 home(s)"));
        assertEquals(expected, savedRows());
    }

    @Test
    void invalidRemapsAndInGameOperatorsCannotChangeHomes() throws SQLException {
        TestPlayer player = addPlayer();
        player.setOp(true);
        Home oldHome = saveHome(player, "oldbase");
        List<List<Object>> before = savedRows();

        assertTrue(server.executeConsole("remap-home-world", "not-a-uuid", "world").hasSucceeded());
        assertTrue(server.getConsoleSender().nextMessage().contains("Invalid old world UUID"));
        assertTrue(server.executeConsole("remap-home-world", oldHome.getWorld(), "MissingWorld").hasSucceeded());
        assertTrue(server.getConsoleSender().nextMessage().contains("is not loaded"));
        assertTrue(server.execute("remap-home-world", player, oldHome.getWorld(), "world").hasSucceeded());
        assertTrue(player.nextMessage().contains("server console"));
        assertEquals(before, savedRows());
    }
}
