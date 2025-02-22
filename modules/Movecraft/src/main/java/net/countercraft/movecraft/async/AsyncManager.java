/*
 * This file is part of Movecraft.
 *
 *     Movecraft is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Movecraft is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Movecraft.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.countercraft.movecraft.async;

import com.google.common.collect.Lists;
import net.countercraft.movecraft.CruiseDirection;
import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.events.CraftScuttleEvent;
import net.countercraft.movecraft.async.rotation.RotationTask;
import net.countercraft.movecraft.async.translation.TranslationTask;
import net.countercraft.movecraft.config.Settings;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.craft.CraftStatus;
import net.countercraft.movecraft.craft.PilotedCraft;
import net.countercraft.movecraft.craft.PlayerCraft;
import net.countercraft.movecraft.craft.BaseCraft;
import net.countercraft.movecraft.craft.SinkingCraftImpl;
import net.countercraft.movecraft.craft.SinkingCraft;
import net.countercraft.movecraft.craft.type.CraftType;
import net.countercraft.movecraft.craft.type.RequiredBlockEntry;
import net.countercraft.movecraft.events.CraftReleaseEvent;
import net.countercraft.movecraft.events.CraftSinkEvent;
import net.countercraft.movecraft.exception.EmptyHitBoxException;
import net.countercraft.movecraft.localisation.I18nSupport;
import net.countercraft.movecraft.mapUpdater.MapUpdateManager;
import net.countercraft.movecraft.mapUpdater.update.BlockCreateCommand;
import net.countercraft.movecraft.mapUpdater.update.UpdateCommand;
import net.countercraft.movecraft.util.Counter;
import net.countercraft.movecraft.util.Tags;
import net.countercraft.movecraft.util.hitboxes.HitBox;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Deprecated
public class AsyncManager extends BukkitRunnable {
    //private static AsyncManager instance = new AsyncManager();
    private final HashMap<AsyncTask, Craft> ownershipMap = new HashMap<>();
    private final HashMap<Craft, HashMap<Craft, Long>> recentContactTracking = new HashMap<>();
    private final BlockingQueue<AsyncTask> finishedAlgorithms = new LinkedBlockingQueue<>();
    private final HashSet<Craft> clearanceSet = new HashSet<>();
    private final HashMap<HitBox, Long> wrecks = new HashMap<>();
    private final HashMap<HitBox, World> wreckWorlds = new HashMap<>();
    private final HashMap<HitBox, Map<Location, BlockData>> wreckPhases = new HashMap<>();
    private final WeakHashMap<World, Set<MovecraftLocation>> processedFadeLocs = new WeakHashMap<>();
    private final Map<Craft, Integer> cooldownCache = new WeakHashMap<>();

    private long lastFadeCheck = 0;
    private long lastContactCheck = 0;

    public AsyncManager() {}

   /* public static AsyncManager getInstance() {
        return instance;
    }*/

    public void submitTask(AsyncTask task, Craft c) {
        if (c.isNotProcessing()) {
            c.setProcessing(true);
            ownershipMap.put(task, c);
            task.runTask(Movecraft.getInstance());
        }
    }

    public void submitCompletedTask(AsyncTask task) {
        finishedAlgorithms.add(task);
    }

    public void addWreck(Craft craft){
        if(craft.getCollapsedHitBox().isEmpty() || Settings.FadeWrecksAfter == 0){
            return;
        }
        wrecks.put(craft.getCollapsedHitBox(), System.currentTimeMillis());
        wreckWorlds.put(craft.getCollapsedHitBox(), craft.getWorld());
        wreckPhases.put(craft.getCollapsedHitBox(), craft.getPhaseBlocks());
    }

    private void processAlgorithmQueue() {
        int runLength = 10;
        int queueLength = finishedAlgorithms.size();

        runLength = Math.min(runLength, queueLength);

        for (int i = 0; i < runLength; i++) {
            boolean sentMapUpdate = false;
            AsyncTask poll = finishedAlgorithms.poll();
            Craft c = ownershipMap.get(poll);

            if (poll instanceof TranslationTask) {
                // Process translation task

                TranslationTask task = (TranslationTask) poll;
                sentMapUpdate = processTranslation(task, c);

            } else if (poll instanceof RotationTask) {
                // Process rotation task
                RotationTask task = (RotationTask) poll;
                sentMapUpdate = processRotation(task, c);

            }

            ownershipMap.remove(poll);

            // only mark the craft as having finished updating if you didn't
            // send any updates to the map updater. Otherwise the map updater
            // will mark the crafts once it is done with them.
            if (!sentMapUpdate) {
                clear(c);
            }
        }
    }

    /**
     * Processes translation task for its corresponding craft
     * @param task the task to process
     * @param c the craft this task belongs to
     * @return true if translation task succeded to process, otherwise false
     */
    private boolean processTranslation(@NotNull final TranslationTask task, @NotNull final Craft c) {

        // Check that the craft hasn't been sneakily unpiloted

        if (task.failed()) {
            // The craft translation failed
            if (!(c.getSinking())) c.getAudience().sendMessage(Component.text(task.getFailMessage()));

            if (task.isCollisionExplosion()) {
                c.setHitBox(task.getNewHitBox());
                c.setFluidLocations(task.getNewFluidList());
                MapUpdateManager.getInstance().scheduleUpdates(task.getUpdates());
                CraftManager.getInstance().addReleaseTask(c);
                return true;
            }
            return false;
        }
        // The craft is clear to move, perform the block updates
        MapUpdateManager.getInstance().scheduleUpdates(task.getUpdates());

        c.setHitBox(task.getNewHitBox());
        c.setFluidLocations(task.getNewFluidList());
        return true;
    }

    /**
     * Processes rotation task for its corresponding craft
     * @param task the task to process
     * @param c the craft this task belongs to
     * @return true if translation task succeded to process, otherwise false
     */
    private boolean processRotation(@NotNull final RotationTask task, @NotNull final Craft c) {
        // Check that the craft hasn't been sneakily unpiloted
        if (!(c instanceof PilotedCraft) && !task.getIsSubCraft()) {
            if ((c.getNotificationPlayer() == null))
                return false;
        }
        if (task.isFailed()) {
            // The craft translation failed, don't try to notify
            // them if there is no pilot
            c.getAudience().sendMessage(Component.text(task.getFailMessage()));
            return false;
        }


        MapUpdateManager.getInstance().scheduleUpdates(task.getUpdates());

        c.setHitBox(task.getNewHitBox());
        c.setFluidLocations(task.getNewFluidList());
        return true;
    }

    private void processCruise() {
        for (Craft craft : CraftManager.getInstance()) {
            if (craft == null || !craft.isNotProcessing() || !craft.getCruising())
                continue;

            long ticksElapsed = (System.currentTimeMillis() - craft.getLastCruiseUpdate()) / 50;
            World w = craft.getWorld();
            // if the craft should go slower underwater, make
            // time pass more slowly there
            if (craft.getType().getBoolProperty(CraftType.HALF_SPEED_UNDERWATER)
                    && craft.getHitBox().getMinY() < w.getSeaLevel())
                ticksElapsed >>= 1;
            // check direct controls to modify movement
            boolean bankLeft = false;
            boolean bankRight = false;
            boolean dive = false;
            if (craft instanceof PlayerCraft && ((PlayerCraft) craft).getPilotLocked()) {
                Player pilot = ((PlayerCraft) craft).getPilot();
                if (pilot.isSneaking())
                    dive = true;
                if (pilot.getInventory().getHeldItemSlot() == 3)
                    bankLeft = true;
                if (pilot.getInventory().getHeldItemSlot() == 5)
                    bankRight = true;
            }
            int tickCoolDown;
            if (cooldownCache.containsKey(craft)) {
                tickCoolDown = cooldownCache.get(craft);
            }
            else {
                tickCoolDown = craft.getTickCooldown();
                cooldownCache.put(craft,tickCoolDown);
            }

            // Account for banking and diving in speed calculations by changing the tickCoolDown
            int cruiseSkipBlocks = (int) craft.getType().getPerWorldProperty(
                    CraftType.PER_WORLD_CRUISE_SKIP_BLOCKS, w);
            if (craft.getCruiseDirection() != CruiseDirection.UP
                    && craft.getCruiseDirection() != CruiseDirection.DOWN) {
                if (bankLeft || bankRight) {
                    if (!dive) {
                        tickCoolDown *= (Math.sqrt(Math.pow(1 + cruiseSkipBlocks, 2)
                                + Math.pow(cruiseSkipBlocks >> 1, 2)) / (1 + cruiseSkipBlocks));
                    }
                    else {
                        tickCoolDown *= (Math.sqrt(Math.pow(1 + cruiseSkipBlocks, 2)
                                + Math.pow(cruiseSkipBlocks >> 1, 2) + 1) / (1 + cruiseSkipBlocks));
                    }
                }
                else if (dive) {
                    tickCoolDown *= (Math.sqrt(Math.pow(1 + cruiseSkipBlocks, 2) + 1) / (1 + cruiseSkipBlocks));
                }
            }

            if (Math.abs(ticksElapsed) < tickCoolDown)
                continue;

            cooldownCache.remove(craft);
            int dx = 0;
            int dz = 0;
            int dy = 0;

            int vertCruiseSkipBlocks = (int) craft.getType().getPerWorldProperty(CraftType.PER_WORLD_VERT_CRUISE_SKIP_BLOCKS, craft.getWorld());

            // ascend
            if (craft.getCruiseDirection() == CruiseDirection.UP)
                dy = 1 + vertCruiseSkipBlocks;
            // descend
            if (craft.getCruiseDirection() == CruiseDirection.DOWN) {
                dy = -1 - vertCruiseSkipBlocks;
                if (craft.getHitBox().getMinY() <= w.getSeaLevel())
                    dy = -1;
            }
            else if (dive) {
                dy = -((cruiseSkipBlocks + 1) >> 1);
                if (craft.getHitBox().getMinY() <= w.getSeaLevel())
                    dy = -1;
            }
            // ship faces west
            if (craft.getCruiseDirection() == CruiseDirection.WEST) {
                dx = -1 - cruiseSkipBlocks;
                if (bankRight)
                    dz = (-1 - cruiseSkipBlocks) >> 1;
                if (bankLeft)
                    dz = (1 + cruiseSkipBlocks) >> 1;
            }
            // ship faces east
            if (craft.getCruiseDirection() == CruiseDirection.EAST) {
                dx = 1 + cruiseSkipBlocks;
                if (bankLeft)
                    dz = (-1 - cruiseSkipBlocks) >> 1;
                if (bankRight)
                    dz = (1 + cruiseSkipBlocks) >> 1;
            }
            // ship faces north
            if (craft.getCruiseDirection() == CruiseDirection.SOUTH) {
                dz = 1 + cruiseSkipBlocks;
                if (bankRight)
                    dx = (-1 - cruiseSkipBlocks) >> 1;
                if (bankLeft)
                    dx = (1 + cruiseSkipBlocks) >> 1;
            }
            // ship faces south
            if (craft.getCruiseDirection() == CruiseDirection.NORTH) {
                dz = -1 - cruiseSkipBlocks;
                if (bankLeft)
                    dx = (-1 - cruiseSkipBlocks) >> 1;
                if (bankRight)
                    dx = (1 + cruiseSkipBlocks) >> 1;
            }
            if (craft.getType().getBoolProperty(CraftType.CRUISE_ON_PILOT)) {
                dy = craft.getType().getIntProperty(CraftType.CRUISE_ON_PILOT_VERT_MOVE);
            }
            if (craft.getType().getBoolProperty(CraftType.GEAR_SHIFTS_AFFECT_CRUISE_SKIP_BLOCKS)) {
                final int gearshift = craft.getCurrentGear();
                dx *= gearshift;
                dy *= gearshift;
                dz *= gearshift;
            }
            craft.translate(dx, dy, dz);
            craft.setLastTranslation(new MovecraftLocation(dx, dy, dz));
            craft.setLastCruiseUpdate(System.currentTimeMillis());
        }
    }

    private void detectSinking(){
        List<Craft> crafts = Lists.newArrayList(CraftManager.getInstance().getCraftList());
        for(Craft craft : crafts) {
            if (craft.getSinking())
                continue;
            if (craft.getType().getDoubleProperty(CraftType.SINK_PERCENT) < 0.0 || !craft.isNotProcessing())
                continue;
            long ticksElapsed = (System.currentTimeMillis() - craft.getLastBlockCheck()) / 50;

            if (ticksElapsed <= Settings.SinkCheckTicks)
                continue;
            //if (craft instanceof BaseCraft) ((BaseCraft)craft).removeMaterial(Material.AIR);
            CraftStatus status = checkCraftStatus(craft);
            //If the craft is disabled, play a sound and disable it.
            //Only do this if the craft isn't already disabled.
            if (status.isDisabled() && craft.isNotProcessing() && !craft.getDisabled()) {
                craft.setDisabled(true);
                craft.getAudience().playSound(Sound.sound(Key.key("entity.iron_golem.death"), Sound.Source.NEUTRAL, 5.0f, 5.0f));
            }


            // if the craft is sinking, let the player
            // know and release the craft. Otherwise
            // update the time for the next check
            if (status.isSinking()) {
                craft.getAudience().sendMessage(I18nSupport.getInternationalisedComponent("Player - Craft is sinking"));
                craft.setCruising(false);
                craft.setSinking(true);
            } else {
                craft.setLastBlockCheck(System.currentTimeMillis());
            }
        }
    }

    //Controls sinking crafts
    private void processSinking() {
        //copy the crafts before iteration to prevent concurrent modifications
        List<Craft> crafts = Lists.newArrayList(CraftManager.getInstance().getCraftList());
        for (final Craft craft : crafts) {
            if (!craft.getSinking() && !(craft instanceof SinkingCraft)) continue;
            if (craft.getHitBox().isEmpty() || craft.getHitBox().getMinY() < craft.getWorld().getMinHeight()+10 || craft.getHitBox().getMinY() <= craft.getType().getIntProperty(CraftType.MIN_HEIGHT_LIMIT)) {
                CraftManager.getInstance().release(craft, CraftReleaseEvent.Reason.SUNK, false);
                continue;
            }
            int startingY = craft.getHitBox().getMidPoint().getY();
            long ticksElapsed = (System.currentTimeMillis() - craft.getLastCruiseUpdate()) / 50;
            if (Math.abs(ticksElapsed) < craft.getType().getIntProperty(CraftType.SINK_RATE_TICKS))
                continue;

            int dx = 0;
            int dz = 0;
            if (craft.getType().getBoolProperty(CraftType.KEEP_MOVING_ON_SINK)) {
                dx = craft.getLastTranslation().getX();
                dz = craft.getLastTranslation().getZ();
            }
            craft.setLastCruiseUpdate(System.currentTimeMillis());
            craft.translate(dx,-1,dz);
        }
    }

    private void processFadingBlocks() {
        if (Settings.FadeWrecksAfter == 0)
            return;
        long ticksElapsed = (System.currentTimeMillis() - lastFadeCheck) / 50;
        if (ticksElapsed <= Settings.FadeTickCooldown)
            return;

        List<HitBox> processed = new ArrayList<>();
        for(Map.Entry<HitBox, Long> entry : wrecks.entrySet()){
            if (Settings.FadeWrecksAfter * 1000L > System.currentTimeMillis() - entry.getValue())
                continue;

            final HitBox hitBox = entry.getKey();
            final Map<Location, BlockData> phaseBlocks = wreckPhases.get(hitBox);
            final World world = wreckWorlds.get(hitBox);
            List<UpdateCommand> commands = new ArrayList<>();
            int fadedBlocks = 0;
            if (!processedFadeLocs.containsKey(world))
                processedFadeLocs.put(world, new HashSet<>());

            int maxFadeBlocks = (int) (hitBox.size() *  (Settings.FadePercentageOfWreckPerCycle / 100.0));
            //Iterate hitbox as a set to get more random locations
            for (MovecraftLocation location : hitBox.asSet()){
                if (processedFadeLocs.get(world).contains(location))
                    continue;

                if (fadedBlocks >= maxFadeBlocks)
                    break;

                final Location bLoc = location.toBukkit(world);
                if ((Settings.FadeWrecksAfter
                        + Settings.ExtraFadeTimePerBlock.getOrDefault(bLoc.getBlock().getType(), 0))
                        * 1000L > System.currentTimeMillis() - entry.getValue())
                    continue;

                fadedBlocks++;
                processedFadeLocs.get(world).add(location);
                BlockData phaseBlock = phaseBlocks.getOrDefault(bLoc, Movecraft.getInstance().getAirBlockData());
                commands.add(new BlockCreateCommand(world, location, phaseBlock));
            }
            MapUpdateManager.getInstance().scheduleUpdates(commands);
            if (!processedFadeLocs.get(world).containsAll(hitBox.asSet()))
                continue;

            processed.add(hitBox);
            processedFadeLocs.get(world).removeAll(hitBox.asSet());
        }
        for(HitBox hitBox : processed) {
            wrecks.remove(hitBox);
            wreckPhases.remove(hitBox);
            wreckWorlds.remove(hitBox);
        }

        lastFadeCheck = System.currentTimeMillis();
    }

    private void processDetection() {
        long ticksElapsed = (System.currentTimeMillis() - lastContactCheck) / 50;
        if (ticksElapsed > 21) {
            for (World w : Bukkit.getWorlds()) {
                if (w == null)
                    continue;

                for (Craft craft : CraftManager.getInstance().getPlayerCraftsInWorld(w)) {
                    MovecraftLocation craftCenter;
                    try {
                        craftCenter = craft.getHitBox().getMidPoint();
                    }
                    catch (EmptyHitBoxException e) {
                        continue;
                    }
                    if (!recentContactTracking.containsKey(craft))
                        recentContactTracking.put(craft, new HashMap<>());
                    for (Craft target : craft.getContacts()) {
                        MovecraftLocation targetCenter;
                        try {
                            targetCenter = target.getHitBox().getMidPoint();
                        }
                        catch (EmptyHitBoxException e) {
                            continue;
                        }
                        int diffx = craftCenter.getX() - targetCenter.getX();
                        int diffz = craftCenter.getZ() - targetCenter.getZ();
                        int distsquared = craftCenter.distanceSquared(targetCenter);
                        // craft has been detected

                        // has the craft not been seen in the last
                        // minute, or is completely new?
                        if (System.currentTimeMillis()
                                - recentContactTracking.get(craft).getOrDefault(target, 0L) <= 60000)
                            continue;


                        Component notification = I18nSupport.getInternationalisedComponent(
                                "Contact - New Contact").append(Component.text( ": "));

                        if (target.getName().length() >= 1)
                            notification = notification.append(Component.text(target.getName() + " ("));
                        notification = notification.append(Component.text(
                                target.getType().getStringProperty(CraftType.NAME)));
                        if (target.getName().length() >= 1)
                            notification = notification.append(Component.text(")"));
                        notification = notification.append(Component.text(" "))
                                .append(I18nSupport.getInternationalisedComponent("Contact - Commanded By"))
                                .append(Component.text(" "));
                        if (target instanceof PilotedCraft)
                            notification = notification.append(Component.text(
                                    ((PilotedCraft) target).getPilot().getDisplayName()));
                        else
                            notification = notification.append(Component.text("NULL"));
                        notification = notification.append(Component.text(", "))
                                .append(I18nSupport.getInternationalisedComponent("Contact - Size"))
                                .append(Component.text( ": "))
                                .append(Component.text(target.getOrigBlockCount()))
                                .append(Component.text(", "))
                                .append(I18nSupport.getInternationalisedComponent("Contact - Range"))
                                .append(Component.text(": "))
                                .append(Component.text((int) Math.sqrt(distsquared)))
                                .append(Component.text(" "))
                                .append(I18nSupport.getInternationalisedComponent("Contact - To The"))
                                .append(Component.text(" "));
                        if (Math.abs(diffx) > Math.abs(diffz)) {
                            if (diffx < 0)
                                notification = notification.append(I18nSupport.getInternationalisedComponent(
                                        "Contact/Subcraft Rotate - East"));
                            else
                                notification = notification.append(I18nSupport.getInternationalisedComponent(
                                        "Contact/Subcraft Rotate - West"));
                        }
                        else if (diffz < 0)
                            notification = notification.append(I18nSupport.getInternationalisedComponent(
                                    "Contact/Subcraft Rotate - South"));
                        else
                            notification = notification.append(I18nSupport.getInternationalisedComponent(
                                    "Contact/Subcraft Rotate - North"));

                        notification = notification.append(Component.text("."));

                        craft.getAudience().sendMessage(notification);
                        var object = craft.getType().getObjectProperty(CraftType.COLLISION_SOUND);
                        if (!(object instanceof Sound))
                            throw new IllegalStateException("COLLISION_SOUND must be of type Sound");

                        craft.getAudience().playSound((Sound) object);

                        long timestamp = System.currentTimeMillis();
                        recentContactTracking.get(craft).put(target, timestamp);
                    }
                }
            }

            lastContactCheck = System.currentTimeMillis();
        }
    }

    public void run() {
        clearAll();

        processCruise();
        detectSinking();
        processSinking();
        processFadingBlocks();
        processDetection();
        processAlgorithmQueue();
        //processScheduledBlockChanges();
//		if(Settings.CompatibilityMode==false)
//			FastBlockChanger.getInstance().run();

        // now cleanup craft that are bugged and have not moved in the past 60 seconds,
        //  but have no pilot or are still processing
        for (Craft craft : CraftManager.getInstance()) {
            if (craft.getNotificationPlayer() == null)
                continue;
            if (!(craft instanceof PilotedCraft)) {
                if (craft.getLastCruiseUpdate() < System.currentTimeMillis() - 60000)
                    CraftManager.getInstance().release(craft, CraftReleaseEvent.Reason.INACTIVE, true);
            }
            if (!craft.isNotProcessing()) {
                if (craft.getCruising()) {
                    if (craft.getLastCruiseUpdate() < System.currentTimeMillis() - 5000)
                        craft.setProcessing(false);
                }
            }
        }
    }

    private void clear(Craft c) {
        clearanceSet.add(c);
    }

    private void clearAll() {
        for (Craft c : clearanceSet) {
            c.setProcessing(false);
        }

        clearanceSet.clear();
    }

    public CraftStatus checkCraftStatus(@NotNull Craft craft) {
        boolean isSinking = false;
        boolean isDisabled = false;

        // Create counters and populate with required block entries

        if (craft.getOrigBlockCount()>=256000) {
            return CraftStatus.of(false, false);
        }

        // go through each block in the HitBox, and if it's in the FlyBlocks or MoveBlocks, increment the counter
        int totalNonNegligibleBlocks = 0;
        int totalNonNegligibleWaterBlocks = 0;
        double fuel = 0.0d;

        // now see if any of the resulting percentages
        // are below the threshold specified in sinkPercent
        double sinkPercent = 0.725d;
        if (craft.getType().getDoubleProperty(CraftType.SINK_PERCENT) > 0.0d)
            sinkPercent = craft.getType().getDoubleProperty(CraftType.SINK_PERCENT);
        if (craft.getType().getDoubleProperty(CraftType.SINK_PERCENT) > 1.0d)
            sinkPercent = ((double)craft.getType().getDoubleProperty(CraftType.SINK_PERCENT)/100);
        double disabledPercent = 0.0d;
        if (craft.getType().getDoubleProperty(CraftType.DISABLE_PERCENT) > 0.0d)
            disabledPercent = craft.getType().getDoubleProperty(CraftType.DISABLE_PERCENT);
        if (craft.getType().getDoubleProperty(CraftType.DISABLE_PERCENT) > 1.0d)
            disabledPercent = ((double)craft.getType().getDoubleProperty(CraftType.DISABLE_PERCENT)/100);
        int current_lift = 0;
        int current_engine = 0;
        if (!(craft instanceof SinkingCraft)) {
            if (craft instanceof BaseCraft) {
                int origin_lift = 0;
                int origin_engine = 0;
                BaseCraft bcraft = (BaseCraft)craft;
                if (bcraft.getDataTag("origin_lift") == null) {
                    origin_lift = 0;
                    for(RequiredBlockEntry entry : craft.getType().getRequiredBlockProperty(CraftType.FLY_BLOCKS)) {
                        for (Material mat : entry.getMaterials()) {
                            if (mat == Material.NOTE_BLOCK) {
                                origin_lift += (((bcraft).getBlockData(mat.createBlockData("[instrument=pling,note=2,powered=false]"))).size());
                                origin_lift += (((bcraft).getBlockData(mat.createBlockData("[instrument=pling,note=7,powered=false]"))).size());
                                origin_lift += (((bcraft).getBlockData(mat.createBlockData("[instrument=pling,note=8,powered=false]"))).size());
                                origin_lift += (((bcraft).getBlockData(mat.createBlockData("[instrument=pling,note=9,powered=false]"))).size());
                            } else {
                                origin_lift += (((bcraft).getBlockType(mat)).size());
                            }
                        }
                    }
                    bcraft.setDataTag("origin_lift", (int)(origin_lift));
                } else {
                    origin_lift = ((Integer)bcraft.getDataTag("origin_lift"));
                }
                if (bcraft.getDataTag("origin_engine") == null) {
                    origin_engine = 0;
                    for(RequiredBlockEntry entry : craft.getType().getRequiredBlockProperty(CraftType.MOVE_BLOCKS)) {
                        for (Material mat : entry.getMaterials()) {
                            origin_engine += (((bcraft).getBlockType(mat)).size());
                        }
                    }
                    bcraft.setDataTag("origin_engine", (origin_engine));
                } else {
                    origin_engine = ((Integer)bcraft.getDataTag("origin_engine"));
                }
                if (!(bcraft.getSinking())) {
                    for(RequiredBlockEntry entry : craft.getType().getRequiredBlockProperty(CraftType.FLY_BLOCKS)) {
                        for (Material mat : entry.getMaterials()) {
                            if (mat == Material.NOTE_BLOCK) {
                                current_lift += (((bcraft).getBlockData(mat.createBlockData("[instrument=pling,note=2,powered=false]"))).size());
                                current_lift += (((bcraft).getBlockData(mat.createBlockData("[instrument=pling,note=7,powered=false]"))).size());
                                current_lift += (((bcraft).getBlockData(mat.createBlockData("[instrument=pling,note=8,powered=false]"))).size());
                                current_lift += (((bcraft).getBlockData(mat.createBlockData("[instrument=pling,note=9,powered=false]"))).size());
                            } else {
                                current_lift += (((bcraft).getBlockType(mat)).size());
                            }
                        }
                    }
                    bcraft.setDataTag("current_lift", (int)(current_lift));
                    if (craft.isNotProcessing() || !(bcraft.getSinking())) {
                        if ((float)current_lift < ((float)origin_lift)*((float)sinkPercent)) {
                            isSinking = true;
                        }
                    }
                }
                if (!(bcraft.getDisabled()) && disabledPercent > 0.0d) {
                    for(RequiredBlockEntry entry : craft.getType().getRequiredBlockProperty(CraftType.MOVE_BLOCKS)) {
                        for (Material mat : entry.getMaterials()) {
                            current_engine += (((bcraft).getBlockType(mat)).size());
                        }
                    }
                    if (craft.isNotProcessing() || !(bcraft.getDisabled())) {
                        if ((float)current_engine < ((float)current_engine)*((float)disabledPercent)) {
                            isDisabled = true;
                        }
                    }
                    bcraft.setDataTag("current_engine", (int)(current_engine));
                }
                if (!(bcraft.getSinking())) {
                    double overall_sink_percent = 0d;
                    if (craft.getType().getDoubleProperty(CraftType.OVERALL_SINK_PERCENT) > 0.0d)
                        overall_sink_percent = craft.getType().getDoubleProperty(CraftType.OVERALL_SINK_PERCENT);
                    if (craft.getType().getDoubleProperty(CraftType.OVERALL_SINK_PERCENT) > 2.0d)
                        overall_sink_percent = ((double)craft.getType().getDoubleProperty(CraftType.OVERALL_SINK_PERCENT)/100);
                    int origin_overall_size = (int)bcraft.getDataTag("origin_size");
                    int current_overall_size = (int)bcraft.getHitBox().size()-(bcraft.getTrackedLocations("air")).size();
                    if (current_overall_size > origin_overall_size) {
                        current_overall_size = origin_overall_size;
                    }
                    if (overall_sink_percent > 0d) {
                        if ((double)current_overall_size < ((double)origin_overall_size)*((double)overall_sink_percent)) {
                            try {
                                if (bcraft.getNotificationPlayer() != null) bcraft.getNotificationPlayer().sendActionBar(ChatColor.RED+"BLOCKS :"+ChatColor.RESET+"[ "+ChatColor.DARK_RED+current_overall_size+ChatColor.RESET+" / "+ChatColor.RED+ChatColor.BOLD+origin_overall_size+ChatColor.RESET+" ]");
                            } catch (Exception exc) {}
                            isSinking = true;
                        } else {
                            try {
                            if (bcraft.getNotificationPlayer() != null) bcraft.getNotificationPlayer().sendActionBar(ChatColor.AQUA+"BLOCKS :"+ChatColor.RESET+"[ "+ChatColor.DARK_AQUA+current_overall_size+ChatColor.RESET+" / "+ChatColor.AQUA+ChatColor.BOLD+origin_overall_size+ChatColor.RESET+" ]");
                            } catch (Exception exc) {}
                        }
                    } else {
                        try {
                            if (bcraft.getNotificationPlayer() != null) bcraft.getNotificationPlayer().sendActionBar(ChatColor.AQUA+"BLOCKS :"+ChatColor.RESET+"[ "+ChatColor.DARK_AQUA+current_overall_size+ChatColor.RESET+" / "+ChatColor.AQUA+ChatColor.BOLD+origin_overall_size+ChatColor.RESET+" ]");
                        } catch (Exception exc) {}
                    }
                }
            }
        }

        return CraftStatus.of(isSinking, isDisabled);
    }
}
