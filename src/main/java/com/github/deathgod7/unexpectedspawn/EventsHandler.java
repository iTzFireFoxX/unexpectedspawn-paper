/*
 * Modified EventsHandler.java for Persistent Random Spawn
 * Based on UnexpectedSpawn by DeathGOD7
 */

package com.github.deathgod7.unexpectedspawn;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.github.deathgod7.unexpectedspawn.Utils.*;

public class EventsHandler implements Listener {

    private final UnexpectedSpawn plugin;
    private final HashSet<World> blacklistedWorlds = new HashSet<>();

    // Clave única para guardar la coordenada en el archivo del jugador
    private final NamespacedKey spawnKey;

    public EventsHandler(UnexpectedSpawn plugin) {
        this.plugin = plugin;
        // Inicializamos la clave de persistencia
        this.spawnKey = new NamespacedKey(plugin, "original_spawn_loc");

        List<String> worldList = plugin.config.getConfig().getStringList("blacklisted-worlds");
        for (String name : worldList) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                LogConsole.warn("Couldn't find world " + name + ". Either it doesn't exist or is not valid.", LogConsole.logTypes.log);
                continue;
            }
            blacklistedWorlds.add(world);
        }
    }

    // Variables temporales para lógica de muerte (Mantenido del original)
    World deathWorld;
    Player deadPlayer;
    Location deathLocation;

    // --- NUEVOS MÉTODOS DE PERSISTENCIA ---

    /**
     * Guarda la ubicación actual del jugador como su "Spawn Original".
     * Se almacena permanentemente en el archivo de datos del jugador (NBT).
     */
    private void saveOriginalSpawn(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) return;

        // Formato simple: Mundo;X;Y;Z;Yaw;Pitch
        String data = loc.getWorld().getName() + ";" +
                loc.getX() + ";" +
                loc.getY() + ";" +
                loc.getZ() + ";" +
                loc.getYaw() + ";" +
                loc.getPitch();

        player.getPersistentDataContainer().set(spawnKey, PersistentDataType.STRING, data);
        LogConsole.info("Spawn original guardado para " + player.getName() + " en " + loc.toVector(), LogConsole.logTypes.debug);
    }

    /**
     * Recupera el "Spawn Original" guardado.
     * Retorna null si el jugador no tiene uno guardado.
     */
    private Location getOriginalSpawn(Player player) {
        PersistentDataContainer container = player.getPersistentDataContainer();
        if (container.has(spawnKey, PersistentDataType.STRING)) {
            String data = container.get(spawnKey, PersistentDataType.STRING);
            if (data != null) {
                try {
                    String[] parts = data.split(";");
                    if (parts.length >= 6) {
                        World w = Bukkit.getWorld(parts[0]);
                        if (w != null) {
                            double x = Double.parseDouble(parts[1]);
                            double y = Double.parseDouble(parts[2]);
                            double z = Double.parseDouble(parts[3]);
                            float yaw = Float.parseFloat(parts[4]);
                            float pitch = Float.parseFloat(parts[5]);
                            return new Location(w, x, y, z, yaw, pitch);
                        }
                    }
                } catch (Exception e) {
                    LogConsole.warn("Error al cargar spawn original para " + player.getName(), LogConsole.logTypes.debug);
                }
            }
        }
        return null;
    }

    // --------------------------------------

    @EventHandler
    public void onDeath(PlayerDeathEvent event){
        deathWorld = event.getEntity().getWorld();
        deadPlayer = event.getEntity();
        deathLocation = deadPlayer.getLocation();

        LogConsole.info("Player " + deadPlayer.getName() + " died at (X "
                + deathLocation.getBlockX() + ", Y " + deathLocation.getBlockY() + ", Z " + deathLocation.getBlockZ() +
                ") at world (" + deathWorld.getName() + ").", LogConsole.logTypes.debug);

        if (deadPlayer != null && deadPlayer.hasPermission("unexpectedspawn.notify")) {
            String msg = String.format("Your death location (&4X %s&r, &2Y %s&r, &1Z %s&r) in world (%s).", deathLocation.getBlockX(), deathLocation.getBlockY(), deathLocation.getBlockZ(), deathWorld.getName());
            String out = ChatColor.translateAlternateColorCodes('&', msg);
            deadPlayer.sendMessage(out);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World joinWorld = player.getWorld();

        if (player.hasPermission("unexpectedspawn.bypass")) {
            return;
        }

        if (joinWorld.getEnvironment().equals(World.Environment.NETHER)
                || joinWorld.getEnvironment().equals(World.Environment.THE_END)) {
            return;
        }

        if (blacklistedWorlds.contains(joinWorld)) {
            return;
        }

        String useCustomOnFirstJoin = checkWorldConfig(joinWorld, "random-respawn.on-first-join");
        String useCustomAlwaysOnJoin = checkWorldConfig(joinWorld, "random-respawn.always-on-join");

        boolean firstJoinEnabled = plugin.config.getConfig().getBoolean(useCustomOnFirstJoin + "random-respawn.on-first-join");
        boolean alwaysJoinEnabled = plugin.config.getConfig().getBoolean(useCustomAlwaysOnJoin + "random-respawn.always-on-join");

        // LÓGICA MODIFICADA:
        // Si entra por primera vez Y está activado en config
        if (!player.hasPlayedBefore() && firstJoinEnabled) {
            Location joinLocation = getRandomSpawnLocation(joinWorld);
            player.teleport(joinLocation);

            // ¡IMPORTANTE! Guardamos esta ubicación para siempre
            saveOriginalSpawn(player, joinLocation);

            addInvulnerable(player, joinWorld);
        }
        // Si "always-on-join" está activo (cuidado con esto, sobrescribiría el spawn cada vez)
        else if (alwaysJoinEnabled) {
            Location joinLocation = getRandomSpawnLocation(joinWorld);
            player.teleport(joinLocation);
            addInvulnerable(player, joinWorld);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World respawnWorld = event.getRespawnLocation().getWorld();

        // Permiso de bypass
        if (player.hasPermission("unexpectedspawn.bypass")) {
            return;
        }

        // 1. COMPROBACIÓN DE CAMAS Y NEXOS (PRIORIDAD ALTA)
        // Verificamos la configuración de camas
        String useCustomBedRespawn = checkWorldConfig(respawnWorld, "random-respawn.bed-respawn-enabled");
        boolean bedEnabled = plugin.config.getConfig().getBoolean(useCustomBedRespawn + "random-respawn.bed-respawn-enabled");

        // Si el jugador tiene cama válida y el config lo permite, NO hacemos nada (Vanilla manda)
        if (event.isBedSpawn() && bedEnabled) {
            LogConsole.info("Jugador " + player.getName() + " reapareciendo en su Cama.", LogConsole.logTypes.debug);
            return;
        }

        // Soporte para Nexo de Reaparición (Respawn Anchor)
        if (ApiUtil.isAvailable(PlayerRespawnEvent.class, "isAnchorSpawn") && event.isAnchorSpawn() && bedEnabled) {
            LogConsole.info("Jugador " + player.getName() + " reapareciendo en su Nexo.", LogConsole.logTypes.debug);
            return;
        }

        // Si llegamos aquí, el jugador NO tiene cama (o la rompió).

        // 2. BUSCAR SPAWN ORIGINAL GUARDADO
        Location originalSpawn = getOriginalSpawn(player);
        if (originalSpawn != null) {
            LogConsole.info("Cama no encontrada. Enviando a " + player.getName() + " a su Spawn Original guardado.", LogConsole.logTypes.debug);
            event.setRespawnLocation(originalSpawn);
            addInvulnerable(player, originalSpawn.getWorld());
            return;
        }

        // 3. GENERAR NUEVO SPAWN (FALLBACK)
        // Si no tiene cama NI spawn original guardado (ej. jugador muy viejo o error), generamos uno nuevo.

        // Lógica original para determinar mundo de respawn
        String wName = respawnWorld.getName();
        if (deathWorld != null) {
            String useCustomWorld = checkWorldConfig(deathWorld, "respawn-world");
            String obtainedData = plugin.config.getConfig().getString(useCustomWorld + "respawn-world");

            if (obtainedData != null && !obtainedData.isEmpty()) {
                World obtainedWorld = Bukkit.getWorld(obtainedData);
                if (obtainedWorld != null) {
                    respawnWorld = obtainedWorld;
                }
            }
            deathWorld = null; // Reset
        }

        if (blacklistedWorlds.contains(respawnWorld)) {
            return;
        }

        String useCustomOnDeath = checkWorldConfig(respawnWorld, "random-respawn.on-death");
        if (plugin.config.getConfig().getBoolean(useCustomOnDeath + "random-respawn.on-death")) {

            Location respawnLocation = getRandomSpawnLocation(respawnWorld);
            event.setRespawnLocation(respawnLocation);

            // Guardamos este nuevo lugar como su original para la próxima vez
            saveOriginalSpawn(player, respawnLocation);

            addInvulnerable(player, respawnWorld);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player
                && plugin.preventDmg.contains(event.getEntity().getUniqueId())
        ) {
            event.setCancelled(true);
        }
    }
}