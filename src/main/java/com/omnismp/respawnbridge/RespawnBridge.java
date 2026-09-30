package com.omnismp.respawnbridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClientStatus;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

public final class RespawnBridge extends JavaPlugin implements Listener, PacketListener {

    private static final class Pending {
        final Player player;
        final AtomicBoolean clicked = new AtomicBoolean();
        ScheduledTask loop;
        boolean fired;
        Location saved;
        Location target;

        Pending(Player player) {
            this.player = player;
        }
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> firing = ThreadLocal.withInitial(() -> false);
    private PacketListenerCommon packetListener;
    private volatile boolean realEventSeen;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        debug = getConfig().getBoolean("debug");
        getServer().getPluginManager().registerEvents(this, this);
        packetListener = PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
        getLogger().info("RespawnBridge: firing PlayerRespawnEvent on Folia death respawns");
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (realEventSeen)
            return;
        Player p = e.getPlayer();
        Pending old = pending.remove(p.getUniqueId());
        if (old != null)
            drop(old);

        Pending pend = new Pending(p);
        pend.loop = p.getScheduler().runAtFixedRate(this, t -> tick(pend),
                () -> pending.remove(p.getUniqueId(), pend), 1, 1);
        if (pend.loop != null)
            pending.put(p.getUniqueId(), pend);
    }

    // Netty thread: only the map and the atomic flag are touched here.
    @Override
    public void onPacketReceive(PacketReceiveEvent e) {
        if (e.getPacketType() != PacketType.Play.Client.CLIENT_STATUS || realEventSeen)
            return;
        UUID id = e.getUser().getUUID();
        Pending pend = id == null ? null : pending.get(id);
        if (pend == null)
            return;
        if (new WrapperPlayClientClientStatus(e).getAction() != WrapperPlayClientClientStatus.Action.PERFORM_RESPAWN)
            return;
        e.setCancelled(true);
        if (pend.clicked.compareAndSet(false, true))
            pend.player.getScheduler().run(this, t -> respawnClicked(pend), null);
    }

    private void respawnClicked(Pending pend) {
        Player p = pend.player;
        if (pending.get(p.getUniqueId()) != pend || !p.isDead())
            return;
        // We swallowed the client's packet, so the player must respawn even if a listener throws.
        try {
            fireBeforePlacement(pend);
        } finally {
            p.spigot().respawn();
        }
    }

    private void fireBeforePlacement(Pending pend) {
        Player p = pend.player;
        Location stored = storedPoint(p);
        Location origin = stored != null ? stored : Bukkit.getWorlds().getFirst().getSpawnLocation();
        boolean anchor = stored != null && stored.getWorld().getEnvironment() == World.Environment.NETHER;
        Location to = callEvent(p, origin, stored != null && !anchor, anchor);
        pend.fired = true;
        pend.saved = stored;
        if (to == null || near(origin, to, 0.5))
            return;
        pend.target = to;
        // Folia reads this point when placing the player, so they land there with no flash.
        p.setRespawnLocation(to, true);
    }

    private void tick(Pending pend) {
        Player p = pend.player;
        if (p.isDead() || !p.isValid())
            return;
        pend.loop.cancel();
        pending.remove(p.getUniqueId(), pend);

        if (!pend.fired) {
            fireAfterPlacement(pend);
            return;
        }
        if (pend.target == null) {
            log(p, "click", pend.saved, null, false);
            return;
        }
        p.setRespawnLocation(pend.saved, false);
        // Respawn points are stored per block and placed at the block centre.
        boolean fallback = !near(p.getLocation(), pend.target, 1);
        if (fallback)
            p.teleportAsync(pend.target, TeleportCause.PLUGIN);
        log(p, "click", pend.saved, pend.target, fallback);
    }

    // For clients that never sent the respawn packet through us, e.g. Bedrock via Geyser.
    private void fireAfterPlacement(Pending pend) {
        Player p = pend.player;
        Location here = p.getLocation();
        Location stored = storedPoint(p);
        boolean atPoint = stored != null && near(here, stored, 2);
        boolean anchor = atPoint && stored.getWorld().getEnvironment() == World.Environment.NETHER;
        Location to = callEvent(p, here, atPoint && !anchor, anchor);
        pend.fired = true;
        boolean moved = to != null && !near(here, to, 0.5);
        if (moved)
            p.teleportAsync(to, TeleportCause.PLUGIN);
        log(p, "alive", stored, moved ? to : null, moved);
    }

    private Location callEvent(Player p, Location origin, boolean bed, boolean anchor) {
        PlayerRespawnEvent ev = new PlayerRespawnEvent(p, origin.clone(), bed, anchor, false,
                PlayerRespawnEvent.RespawnReason.DEATH);
        firing.set(true);
        try {
            Bukkit.getPluginManager().callEvent(ev);
        } finally {
            firing.set(false);
        }
        return ev.getRespawnLocation();
    }

    // If Folia ever fires the event itself, stop bridging so nothing fires twice.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRealRespawn(PlayerRespawnEvent e) {
        if (firing.get() || realEventSeen)
            return;
        Pending pend = pending.get(e.getPlayer().getUniqueId());
        if (pend == null)
            return;
        pend.fired = true;
        realEventSeen = true;
        getLogger().warning("A PlayerRespawnEvent was fired by someone else for " + e.getPlayer().getName()
                + ", RespawnBridge stops bridging; remove it if Folia now fires the event");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Pending pend = pending.remove(e.getPlayer().getUniqueId());
        if (pend != null)
            drop(pend);
    }

    private void drop(Pending pend) {
        pend.loop.cancel();
        if (pend.target != null)
            pend.player.setRespawnLocation(pend.saved, false);
    }

    // No block reads: validating a bed from here would throw on Folia.
    private static Location storedPoint(Player p) {
        Location l = p.getPotentialRespawnLocation();
        return l != null && l.getWorld() != null ? l : null;
    }

    private static boolean near(Location a, Location b, double range) {
        return Objects.equals(a.getWorld(), b.getWorld()) && a.distanceSquared(b) <= range * range;
    }

    private void log(Player p, String path, Location old, Location target, boolean fallback) {
        if (debug)
            getLogger().info(String.format("%s: path=%s old=%s target=%s fallback=%s",
                    p.getName(), path, fmt(old), target == null ? "unchanged" : fmt(target), fallback));
    }

    private static String fmt(Location l) {
        if (l == null)
            return "none";
        return String.format("%s %.1f,%.1f,%.1f", l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }
}
