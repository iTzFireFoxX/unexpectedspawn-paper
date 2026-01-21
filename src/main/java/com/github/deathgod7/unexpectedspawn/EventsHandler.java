/*
 * This file is part of UnexpectedSpawn
 * (see https://github.com/DeathGOD7/unexpectedspawn-paper).
 *
 * Copyright (c) 2021 Shivelight.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.deathgod7.unexpectedspawn;

import org.bukkit.*;
        import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.deathgod7.unexpectedspawn.Utils.*;

public class EventsHandler implements Listener {

    private final UnexpectedSpawn plugin;
    private final HashSet<World> blacklistedWorlds = new HashSet<>();
    private final NamespacedKey spawnKey;

    // Configuración dinámica
    private final Set<Material> configBlacklist = new HashSet<>();
    private final List<Integer> searchRadii = new ArrayList<>(); // Nueva lista

    public EventsHandler(UnexpectedSpawn plugin) {
        this.plugin = plugin;
        this.spawnKey = new NamespacedKey(plugin, "original_spawn_loc");

        // 1. Cargar mundos ignorados
        List<String> worldList = plugin.config.getConfig().getStringList("blacklisted-worlds");
        for (String name : worldList) {
            World world = Bukkit.getWorld(name);
            if (world != null) {
                blacklistedWorlds.add(world);
            }
        }

        // 2. Cargar Blacklist de bloques
        List<String> blockList = plugin.config.getConfig().getStringList("global.spawn-block-blacklist");
        if (blockList != null && !blockList.isEmpty()) {
            for (String blockName : blockList) {
                Material mat = Material.getMaterial(blockName);
                if (mat != null) configBlacklist.add(mat);
            }
        } else {
            // Fallback
            configBlacklist.add(Material.LAVA);
            configBlacklist.add(Material.WATER);
            configBlacklist.add(Material.FIRE);
            configBlacklist.add(Material.MAGMA_BLOCK);
            configBlacklist.add(Material.CACTUS);
        }

        // 3. CARGAR RADIOS DE BÚSQUEDA DESDE CONFIG
        List<Integer> radiiList = plugin.config.getConfig().getIntegerList("global.search-radii");
        searchRadii.addAll(radiiList);
    } // <--- ¡AQUÍ FALTABA ESTA LLAVE DE CIERRE!

    // --- PERSISTENCIA ---
    private void saveOriginalSpawn(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        double x = Math.floor(loc.getX()) + 0.5;
        double z = Math.floor(loc.getZ()) + 0.5;

        String data = loc.getWorld().getName() + ";" + x + ";" + loc.getY() + ";" + z + ";" + loc.getYaw() + ";" + loc.getPitch();
        player.getPersistentDataContainer().set(spawnKey, PersistentDataType.STRING, data);
        LogConsole.info("Spawn guardado para " + player.getName(), LogConsole.logTypes.debug);
    }

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
                            return new Location(w,
                                    Double.parseDouble(parts[1]),
                                    Double.parseDouble(parts[2]),
                                    Double.parseDouble(parts[3]),
                                    Float.parseFloat(parts[4]),
                                    Float.parseFloat(parts[5]));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    // --- SEGURIDAD ---
    private boolean isDangerousBody(Block block) {
        return block.getType().isSolid() || configBlacklist.contains(block.getType());
    }

    private boolean isDangerousFloor(Block block) {
        return configBlacklist.contains(block.getType());
    }

    private Location findSafeVertical(Location original) {
        Location check = original.clone();
        World w = check.getWorld();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        int originalY = original.getBlockY(); // Guardamos la altura original

        // 1. BAJAR: Si flotamos (AIRE), bajamos
        while (check.getBlock().getRelative(BlockFace.DOWN).getType().isAir() && check.getY() > minY) {
            check.subtract(0, 1, 0);

            // NUEVO: Si bajamos más de 64 bloques respecto al original -> CANCELAR
            if (originalY - check.getY() > 64) return null;
        }

        if (check.getY() <= minY) return null; // Llegó al vacío

        // 2. SUBIR: Si estamos asfixiados o quemándonos, subimos
        while ((isDangerousBody(check.getBlock()) || isDangerousBody(check.getBlock().getRelative(BlockFace.UP))) && check.getY() < maxY) {
            check.add(0, 1, 0);

            // NUEVO: Si subimos más de 64 bloques respecto al original -> CANCELAR
            if (check.getY() - originalY > 64) return null;
        }

        Block ground = check.getBlock().getRelative(BlockFace.DOWN);
        if (isDangerousFloor(ground)) return null;

        return check;
    }

    private Location findSafeNearby(Location origin, int radius) {
        World world = origin.getWorld();
        for (int i = 0; i < 15; i++) {
            int dx = ThreadLocalRandom.current().nextInt(radius * 2) - radius;
            int dz = ThreadLocalRandom.current().nextInt(radius * 2) - radius;

            int newX = origin.getBlockX() + dx;
            int newZ = origin.getBlockZ() + dz;
            int highestY = world.getHighestBlockYAt(newX, newZ);

            Location candidate = new Location(world, newX + 0.5, highestY + 1, newZ + 0.5);
            Block ground = world.getBlockAt(newX, highestY, newZ);

            if (!isDangerousFloor(ground)) {
                return candidate;
            }
        }
        return null;
    }
    
    private void notifyPlayer(Player p, String mainMessage, String subMessage) {
        p.sendMessage(""); // Espacio arriba

        p.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + mainMessage);
        if (subMessage != null) {
            p.sendMessage(ChatColor.GRAY + subMessage);
        }

        p.sendMessage(""); // Espacio abajo
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World joinWorld = player.getWorld();

        if (player.hasPermission("unexpectedspawn.bypass") || blacklistedWorlds.contains(joinWorld)) return;

        Location existingSpawn = getOriginalSpawn(player);
        String useCustomOnFirstJoin = checkWorldConfig(joinWorld, "random-respawn.on-first-join");
        boolean firstJoinEnabled = plugin.config.getConfig().getBoolean(useCustomOnFirstJoin + "random-respawn.on-first-join");

        if (!player.hasPlayedBefore() && firstJoinEnabled) {
            Location joinLocation = getRandomSpawnLocation(joinWorld);
            player.teleport(joinLocation);
            saveOriginalSpawn(player, joinLocation);
            addInvulnerable(player, joinWorld);

            notifyPlayer(player, "Bienvenido", "Tu spawnpoint aleatorio ha sido guardado.");
        }
        else if (existingSpawn == null && firstJoinEnabled) {
            Location fixLocation = getRandomSpawnLocation(joinWorld);
            saveOriginalSpawn(player, fixLocation);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        if (player.hasPermission("unexpectedspawn.bypass")) return;

        if (event.isBedSpawn() || (ApiUtil.isAvailable(PlayerRespawnEvent.class, "isAnchorSpawn") && event.isAnchorSpawn())) {
            return;
        }

        Location originalSpawn = getOriginalSpawn(player);

        if (originalSpawn != null) {
            // INTENTO 1: Vertical
            Location safeVertical = findSafeVertical(originalSpawn);

            if (safeVertical != null) {
                event.setRespawnLocation(safeVertical);
                addInvulnerable(player, safeVertical.getWorld());
            } else {
                // INTENTO 2: Búsqueda Incremental
                Location nearbySpawn = null;
                int foundRange = 0;

                for (int range : searchRadii) {
                    nearbySpawn = findSafeNearby(originalSpawn, range);
                    if (nearbySpawn != null) {
                        foundRange = range;
                        break;
                    }
                }

                if (nearbySpawn != null) {
                    event.setRespawnLocation(nearbySpawn);
                    saveOriginalSpawn(player, nearbySpawn);
                    addInvulnerable(player, nearbySpawn.getWorld());

                    notifyPlayer(player, "Nuevo Spawnpoint", "Se te ha generado un nuevo spawnpoint en un radio de" + ChatColor.WHITE + foundRange + ChatColor.GRAY + " bloques del anterior porque era inseguro.");
                } else {
                    // INTENTO 3: Random
                    Location emergencySpawn = getRandomSpawnLocation(player.getWorld());
                    event.setRespawnLocation(emergencySpawn);
                    saveOriginalSpawn(player, emergencySpawn);
                    addInvulnerable(player, emergencySpawn.getWorld());

                    notifyPlayer(player, "Nuevo Spawnpoint Aleatorio", "Han fallado todos los intentos de generar cerca un nuevo spawnpoint, se te ha generado un nuevo spawnpoint aleatorio.");
                }
            }
        } else {
            Location emergencySpawn = getRandomSpawnLocation(player.getWorld());
            event.setRespawnLocation(emergencySpawn);
            saveOriginalSpawn(player, emergencySpawn);
            notifyPlayer(player, "Nuevo Spawnpoint Aleatorio", "Se te ha generado un nuevo spawnpoint aleatorio.");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event){
        World deathWorld = event.getEntity().getWorld();
        Player deadPlayer = event.getEntity();
        Location deathLocation = deadPlayer.getLocation();
        if (deadPlayer != null && deadPlayer.hasPermission("unexpectedspawn.notify")) {
            String msg = String.format("Muerte en: &c%s, %s, %s&r (%s)",
                    deathLocation.getBlockX(), deathLocation.getBlockY(), deathLocation.getBlockZ(), deathWorld.getName());
            deadPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && plugin.preventDmg.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}