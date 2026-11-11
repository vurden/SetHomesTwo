package com.samleighton.sethomestwo.events;

import com.samleighton.sethomestwo.dao.HomesDao;
import com.samleighton.sethomestwo.models.Home;
import com.samleighton.sethomestwo.support.HomeFixtures;
import com.samleighton.sethomestwo.support.ServerTestBase;
import com.samleighton.sethomestwo.support.TestPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HomeSignsTest extends ServerTestBase {

    private void selectLink(TestPlayer player) {
        assertTrue(server.execute("homes", player).hasSucceeded());
        clickMenu(player, 0, ClickType.RIGHT);
        clickMenu(player, 3, ClickType.LEFT);
        server.getScheduler().performOneTick();
        assertTrue(player.nextMessage().contains("Right-click a sign"));
    }

    private void clickMenu(TestPlayer player, int slot, ClickType click) {
        server.getPluginManager().callEvent(new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, click, InventoryAction.PICKUP_ALL));
    }

    private Block existingSign() {
        Block block = overworld.getBlockAt(4, 70, 4);
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        sign.getSide(Side.FRONT).setLine(0, "Existing text");
        assertTrue(sign.update());
        return block;
    }

    private void clickBlock(TestPlayer player, Block block) {
        server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                null, block, BlockFace.UP, EquipmentSlot.HAND));
    }

    private PlayerSignOpenEvent openSign(TestPlayer player, Block block, boolean cancelled) {
        PlayerSignOpenEvent event = new PlayerSignOpenEvent(player, (Sign) block.getState(), Side.FRONT,
                PlayerSignOpenEvent.Cause.INTERACT);
        event.setCancelled(cancelled);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private void linkSign(TestPlayer player, Block block) {
        selectLink(player);
        clickBlock(player, block);
        assertTrue(openSign(player, block, false).isCancelled());
        assertTrue(player.nextMessage().contains("Sign linked to"));
    }

    private String savedLink(Block block) {
        return ((Sign) block.getState()).getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "home-sign"), PersistentDataType.STRING);
    }

    @Test
    void aVisitorUsesTheLatestDestinationAndTheSignKeepsItsText() {
        TestPlayer owner = addPlayer("owner");
        TestPlayer visitor = addPlayer("visitor");
        Home home = HomeFixtures.persist(owner, "base");
        Block sign = existingSign();
        linkSign(owner, sign);
        assertEquals("Existing text", ((Sign) sign.getState()).getSide(Side.FRONT).getLine(0));
        assertNotNull(savedLink(sign));

        home.setX(41.25);
        HomesDao dao = new HomesDao();
        assertTrue(dao.update(home));
        assertTrue(dao.rename(home, "camp"));
        plugin.getConfig().set("delay", 0);
        plugin.getConfig().set("teleportSafety", false);

        clickBlock(visitor, sign);
        server.getScheduler().performOneTick();
        assertEquals(41.25, visitor.getLocation().getX());
        assertTrue(visitor.nextMessage().contains("camp"));
    }

    @Test
    void clickingAnotherBlockOrEntityCancelsLinking() {
        TestPlayer owner = addPlayer("owner");
        TestPlayer other = addPlayer("other");
        HomeFixtures.persist(owner, "base");
        Block sign = existingSign();
        Block stone = overworld.getBlockAt(5, 70, 4);
        stone.setType(Material.STONE);

        selectLink(owner);
        clickBlock(owner, stone);
        assertTrue(owner.nextMessage().contains("cancelled"));
        assertFalse(openSign(owner, sign, false).isCancelled());

        selectLink(owner);
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(owner, other, EquipmentSlot.HAND));
        assertTrue(owner.nextMessage().contains("cancelled"));
        assertFalse(openSign(owner, sign, false).isCancelled());
        assertNull(savedLink(sign));
    }

    @Test
    void aDeniedSignEditorCannotLinkTheSign() {
        TestPlayer owner = addPlayer("owner");
        HomeFixtures.persist(owner, "base");
        Block sign = existingSign();
        selectLink(owner);
        clickBlock(owner, sign);
        openSign(owner, sign, true);
        server.getScheduler().performOneTick();

        assertTrue(owner.nextMessage().contains("cannot edit"));
        assertNull(savedLink(sign));
        assertFalse(openSign(owner, sign, false).isCancelled());
    }

    @Test
    void visitorsCannotRedirectAnExistingLinkAndMustPassTeleportChecks() {
        TestPlayer owner = addPlayer("owner");
        TestPlayer visitor = addPlayer("visitor");
        HomeFixtures.persist(owner, "base");
        HomeFixtures.persist(visitor, "other");
        Block sign = existingSign();
        linkSign(owner, sign);
        String link = savedLink(sign);

        selectLink(visitor);
        clickBlock(visitor, sign);
        openSign(visitor, sign, false);
        assertTrue(visitor.nextMessage().contains("linked by another player"));
        assertEquals(link, savedLink(sign));

        Location start = visitor.getLocation();
        var permission = visitor.addAttachment(plugin, "sh2.teleport", false);
        clickBlock(visitor, sign);
        assertTrue(visitor.nextMessage().contains("do not have permission"));
        visitor.removeAttachment(permission);
        HomeFixtures.blacklist(overworld.getName());
        clickBlock(visitor, sign);
        assertTrue(visitor.nextMessage().contains("blacklisted"));
        assertEquals(start, visitor.getLocation());
    }

    @Test
    void aDeletedHomeCannotLeaveAWorkingSignForAReplacementHome() {
        TestPlayer owner = addPlayer("owner");
        TestPlayer visitor = addPlayer("visitor");
        Home home = HomeFixtures.persist(owner, "base");
        Block sign = existingSign();
        linkSign(owner, sign);

        assertTrue(new HomesDao().delete(home));
        Home replacement = HomeFixtures.persist(owner, "replacement");
        assertEquals(home.getId(), replacement.getId());
        clickBlock(visitor, sign);
        assertTrue(visitor.nextMessage().contains("no longer available"));
    }
}
