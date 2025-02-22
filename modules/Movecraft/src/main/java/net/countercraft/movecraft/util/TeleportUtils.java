package net.countercraft.movecraft.util;

import net.countercraft.movecraft.craft.PlayerCraftImpl;
import net.countercraft.movecraft.craft.BaseCraft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.GameRule;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

/**
 * Code taken with permission from MicleBrick
 * https://www.spigotmc.org/threads/teleport-player-smoothly.317416/
 */
public class TeleportUtils {

    public static void teleportEntity(Entity entity, Location location) {
      teleportEntity(entity,location,0.0f);
    }

    public static void teleportEntity(Entity entity, Location location, float yawChange) {
        Location to = location;
        boolean tp = false;
        BaseCraft pcraft = null;
        if (entity instanceof Player) pcraft = CraftManager.getInstance().getCraftFromPlayer((Player)entity);
        if ((entity).getVehicle() != null) {
          entity = entity.getVehicle();
        }
        try {
            if (entity instanceof Player && pcraft instanceof BaseCraft) {
                if (entity.getWorld().equals(location.getWorld())) {
                    InventoryView inventoryView = null;
                    Location iLoc = null;
                    MovecraftLocation invMoveLoc = null;
                    if (entity instanceof HumanEntity) {
                      inventoryView = ((HumanEntity) entity).getOpenInventory();
                      if (inventoryView.getType() != InventoryType.CRAFTING && inventoryView.getTopInventory().getHolder() != null) {
                          iLoc = Movecraft.getInstance().getWorldHandler().getAccessLocation(inventoryView);
                          if (iLoc != null) {
                              invMoveLoc = new MovecraftLocation(iLoc.getBlockX(), iLoc.getBlockY(), iLoc.getBlockZ());
                              if (inventoryView.getTopInventory().getHolder() == null) {
                                  invMoveLoc = null;
                              }
                          }
                      }
                      if ((invMoveLoc != null && iLoc != null) && (inventoryView.getType() != InventoryType.CRAFTING) && (!((PlayerCraftImpl)pcraft).getPilotLocked())) {
                        Movecraft.getInstance().getSmoothTeleport().teleport((Player) entity, location, yawChange, 0.0f);
                        tp = true;
                      } else {
                        tp = (entity).teleport(to,io.papermc.paper.entity.TeleportFlag.Relative.values());
                      }
                      if (tp) return;
                  } else {
                    tp = (entity).teleport(to,io.papermc.paper.entity.TeleportFlag.Relative.values());
                  }
                } else {
                  tp = (entity).teleport(to,io.papermc.paper.entity.TeleportFlag.EntityState.values());
                }
            } else {
              if (tp) return;
              tp = (entity).teleport(to,io.papermc.paper.entity.TeleportFlag.EntityState.values());
            }
        } catch (Exception exc) {
            if (tp) return;
            tp = (entity).teleport(to);
        }
    }

    public static void teleport(Entity player, Location location, float yawChange) {
        if (!player.getWorld().equals(location.getWorld())) {
          teleportEntity(player,location,0.0f);
          return;
        }
        if (player.getVehicle()!=null) {
          Entity vehicle = player.getVehicle();
          teleportEntity(vehicle,location,yawChange);
          return;
        }
        if (yawChange != 0.0f) {
          teleportEntity(player,location,yawChange);
          return;
        }
        teleportEntity(player,location,0.0f);
    }
}
