package com.samleighton.sethomestwo.events;

import com.samleighton.sethomestwo.SetHomesTwo;
import com.samleighton.sethomestwo.dao.HomesDao;
import com.samleighton.sethomestwo.gui.GuiSession;
import com.samleighton.sethomestwo.models.Home;
import com.samleighton.sethomestwo.utils.ChatUtils;
import com.samleighton.sethomestwo.utils.ServerUtil;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class HomeSigns implements Listener {

    private final SetHomesTwo plugin;
    private final NamespacedKey linkKey;
    private final NamespacedKey ownerKey;

    public HomeSigns(SetHomesTwo plugin) {
        this.plugin = plugin;
        linkKey = new NamespacedKey(plugin, "home-sign");
        ownerKey = new NamespacedKey(plugin, "home-sign-owner");
    }

    /** Handle sign clicks before the homes compass gets a chance to open a menu. */
    public boolean handleRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return false;

        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        boolean clickedSign = block != null && Tag.ALL_SIGNS.isTagged(block.getType());
        GuiSession session = plugin.getGuiSessionMap().get(player.getUniqueId());
        GuiSession.SignLinkAttempt attempt = session == null ? null : session.getSignLinkAttempt();
        if (attempt != null) {
            if (event.getHand() == EquipmentSlot.OFF_HAND) return true;
            if (!clickedSign) {
                cancelLink(player, session);
            } else {
                // Let the server open the editor first so normal sign edit checks run.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (session.getSignLinkAttempt() == attempt) {
                        session.clearSignLink();
                        ChatUtils.sendError(player, "You cannot edit that sign. Sign linking cancelled.");
                    }
                });
            }
            return true;
        }

        if (!clickedSign || !(block.getState() instanceof Sign sign)) return false;
        String link = sign.getPersistentDataContainer().get(linkKey, PersistentDataType.STRING);
        if (link == null) return false;

        if (event.useInteractedBlock() == Event.Result.DENY) return true;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND) return true;

        Home home = new HomesDao().getBySignLink(link);
        if (home == null) {
            ChatUtils.sendError(player, "This sign's home is no longer available.");
            return true;
        }
        if (!player.hasPermission("sh2.bypass-blacklist") && ServerUtil.isWorldBlacklisted(home.asLocation().getWorld())) {
            home.setCanTeleport(false);
        }
        home.teleport(player);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignOpen(PlayerSignOpenEvent event) {
        if (event.getCause() != PlayerSignOpenEvent.Cause.INTERACT) return;

        Player player = event.getPlayer();
        GuiSession session = plugin.getGuiSessionMap().get(player.getUniqueId());
        if (session == null || session.getSignLinkAttempt() == null) return;
        GuiSession.SignLinkAttempt attempt = session.getSignLinkAttempt();
        session.clearSignLink();
        event.setCancelled(true);

        if (!player.hasPermission("sh2.manage-homes")) {
            ChatUtils.invalidPermissions(player);
            return;
        }

        Sign sign = event.getSign();
        PersistentDataContainer data = sign.getPersistentDataContainer();
        String playerId = player.getUniqueId().toString();
        String owner = data.get(ownerKey, PersistentDataType.STRING);
        if (owner != null && !owner.equals(playerId)) {
            ChatUtils.sendError(player, "This sign was linked by another player.");
            return;
        }

        Home home = new HomesDao().getBySignLink(attempt.homeLink());
        if (home == null || !playerId.equals(home.getUUIDBelongingTo())) {
            ChatUtils.sendError(player, "That home is no longer available to link.");
            return;
        }

        data.set(linkKey, PersistentDataType.STRING, attempt.homeLink());
        data.set(ownerKey, PersistentDataType.STRING, playerId);
        if (!sign.update()) {
            ChatUtils.pluginError(player);
            return;
        }
        ChatUtils.sendSuccess(player, "Sign linked to " + home.getName() + ".");
    }

    @EventHandler
    public void onEntityRightClick(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        Player player = event.getPlayer();
        GuiSession session = plugin.getGuiSessionMap().get(player.getUniqueId());
        if (session != null && session.getSignLinkAttempt() != null) cancelLink(player, session);
    }

    @EventHandler
    public void onEntityRightClickAt(PlayerInteractAtEntityEvent event) {
        onEntityRightClick(event);
    }

    private void cancelLink(Player player, GuiSession session) {
        session.clearSignLink();
        ChatUtils.sendInfo(player, "Sign linking cancelled.");
    }
}
