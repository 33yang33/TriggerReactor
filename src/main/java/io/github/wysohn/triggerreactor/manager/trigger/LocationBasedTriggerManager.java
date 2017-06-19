/*******************************************************************************
 *     Copyright (C) 2017 wysohn
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package io.github.wysohn.triggerreactor.manager.trigger;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import io.github.wysohn.triggerreactor.core.lexer.LexerException;
import io.github.wysohn.triggerreactor.core.parser.ParserException;
import io.github.wysohn.triggerreactor.main.TriggerReactor;
import io.github.wysohn.triggerreactor.manager.TriggerManager;
import io.github.wysohn.triggerreactor.manager.TriggerManager.Trigger;
import io.github.wysohn.triggerreactor.manager.location.SimpleChunkLocation;
import io.github.wysohn.triggerreactor.manager.location.SimpleLocation;
import io.github.wysohn.triggerreactor.tools.FileUtil;

public abstract class LocationBasedTriggerManager<T extends Trigger> extends TriggerManager {
    public static final Material INSPECTION_TOOL = Material.BONE;
    public static final Material CUT_TOOL = Material.SHEARS;
    public static final Material COPY_TOOL = Material.PAPER;

    private Map<SimpleChunkLocation, Map<SimpleLocation, T>> locationTriggers = new ConcurrentHashMap<>();
    private List<SimpleLocation> indexMap = new ArrayList<>();

    private File folder;
    public LocationBasedTriggerManager(TriggerReactor plugin, String folderName) {
        super(plugin);

        File dataFolder = plugin.getDataFolder();
        if(!dataFolder.exists())
            dataFolder.mkdirs();

        folder = new File(dataFolder, folderName);
        if(!folder.exists())
            folder.mkdirs();

        reload();
    }

    @Override
    public void reload(){
        locationTriggers.clear();

        File[] fileList = folder.listFiles();
        Arrays.sort(fileList, new Comparator<File>(){
            @Override
            public int compare(File o1, File o2) {
                int indexo1 = Integer.parseInt(o1.getName().substring(0, o1.getName().indexOf('.')));
                int indexo2 = Integer.parseInt(o2.getName().substring(0, o2.getName().indexOf('.')));

                return indexo1 - indexo2;
            }
        });

        for(File file : fileList){
            if(file.isDirectory()){
                throw new RuntimeException(file+" Directory is not allowed! cause by "+this.getClass().getSimpleName());
            }

            String fileName = file.getName();

            SimpleLocation sloc = null;
            try{
                sloc = stringToSloc(fileName);
            }catch(Exception e){
                e.printStackTrace();
                synchronized(indexMap){
                    indexMap.add(null);
                }
                continue;
            }

            String script = null;
            try {
                script = FileUtil.readFromFile(file);
            } catch (IOException e1) {
                e1.printStackTrace();
                synchronized(indexMap){
                    indexMap.add(null);
                }
                continue;
            }

            T trigger = null;
            try {
                trigger = constructTrigger(script);
            } catch (LexerException | ParserException | IOException e) {
                e.printStackTrace();
                synchronized(indexMap){
                    indexMap.add(null);
                }
                continue;
            }

            if(sloc != null && trigger != null){
                SimpleChunkLocation scloc = new SimpleChunkLocation(sloc);

                Map<SimpleLocation, T> triggerMap = locationTriggers.get(scloc);
                if(!locationTriggers.containsKey(scloc)){
                    triggerMap = new ConcurrentHashMap<>();
                    locationTriggers.put(scloc, triggerMap);
                }

                triggerMap.put(sloc, trigger);

                int index = stringToIndex(fileName);
                synchronized(indexMap){
                    while(indexMap.size() <= index){
                        //add null to match the index
                        indexMap.add(null);
                    }

                    indexMap.set(index, sloc);
                }
            } else {
                synchronized(indexMap){
                    indexMap.add(null);
                }
            }
        }
    }

    @Override
    public void saveAll(){
        for(Entry<SimpleChunkLocation, Map<SimpleLocation, T>> chunkEntry : locationTriggers.entrySet()){
            SimpleChunkLocation cloc = chunkEntry.getKey();
            Map<SimpleLocation, T> slocMap = chunkEntry.getValue();

            Set<SimpleLocation> failed = new HashSet<>();

            for(Entry<SimpleLocation, T> entry : slocMap.entrySet()){
                SimpleLocation sloc = entry.getKey();
                T trigger = entry.getValue();

                int index = -1;

                synchronized(indexMap){
                    index = indexMap.indexOf(sloc);

                    if(index == -1){
                        plugin.getLogger().severe("location "+sloc+" had no index!");
                        plugin.getLogger().severe("Cannot save "+sloc+". caused by "+getClass().getSimpleName());
                        continue;
                    }
                }

                String fileName = slocToString(index, sloc);
                String script = trigger.getScript();

                File file = new File(folder, fileName);
                try{
                    FileUtil.writeToFile(file, script);
                }catch(Exception e){
                    e.printStackTrace();
                    plugin.getLogger().severe("Could not save a trigger at "+sloc);
                    failed.add(sloc);
                }
            }

            for(SimpleLocation sloc : failed){
                slocMap.remove(sloc);
            }
        }
    }

    private String slocToString(int index, SimpleLocation sloc){
        return index+". "+sloc.getWorld()+"@"+sloc.getX()+","+sloc.getY()+","+sloc.getZ();
    }

    private SimpleLocation stringToSloc(String str){
        String[] isplit = str.split("\\.");

        String[] wsplit = isplit[1].trim().split("@");
        String world = wsplit[0];
        String[] lsplit = wsplit[1].split(",");
        int x = Integer.parseInt(lsplit[0]);
        int y = Integer.parseInt(lsplit[1]);
        int z = Integer.parseInt(lsplit[2]);
        return new SimpleLocation(world, x, y, z);
    }

    private int stringToIndex(String str){
        String[] isplit = str.split("\\.");
        return Integer.parseInt(isplit[0]);
    }

    private Map<UUID, Long> lastClick = new HashMap<>();
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEvent e){
        if(e.getHand() != EquipmentSlot.HAND)
            return;

        Player player = e.getPlayer();

/*        long current = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());

        if(last == null || current > last + 400L){
            lastClick.put(player.getUniqueId(), current + 400L);
        }else{
            return;
        }*/

        ItemStack IS = player.getInventory().getItemInMainHand();
        Block clicked = e.getClickedBlock();
        if(clicked == null)
            return;

        T trigger = getTriggerForLocation(clicked.getLocation());

        if(IS != null
                &&!e.isCancelled()
                && player.hasPermission("triggerreactor.admin")){

            if(IS.getType() == INSPECTION_TOOL){
                if(trigger != null && e.getAction() == Action.LEFT_CLICK_BLOCK){
                    removeTriggerForLocation(clicked.getLocation());

                    player.sendMessage(ChatColor.GREEN+"A trigger has deleted.");
                    e.setCancelled(true);
                }else if(trigger != null && e.getAction() == Action.RIGHT_CLICK_BLOCK){
                    this.showTriggerInfo(player, clicked);
                    e.setCancelled(true);
                }
            }else if(IS.getType() == CUT_TOOL){
                if(e.getAction() == Action.LEFT_CLICK_BLOCK){
                    if(pasteTrigger(player, clicked.getLocation())){
                        player.sendMessage(ChatColor.GREEN+"Successfully pasted the trigger!");
                        this.showTriggerInfo(player, clicked);
                        e.setCancelled(true);
                    }
                }else if(trigger != null && e.getAction() == Action.RIGHT_CLICK_BLOCK){
                    if(cutTrigger(player, clicked.getLocation())){
                        player.sendMessage(ChatColor.GREEN+"Cut Complete!");
                        player.sendMessage(ChatColor.GREEN+"Now you can paste it by left click on any block!");
                        e.setCancelled(true);
                    }
                }
            }else if(IS.getType() == COPY_TOOL){
                if(e.getAction() == Action.LEFT_CLICK_BLOCK){
                    if(pasteTrigger(player, clicked.getLocation())){
                        player.sendMessage(ChatColor.GREEN+"Successfully pasted the trigger!");
                        this.showTriggerInfo(player, clicked);
                        e.setCancelled(true);
                    }
                }else if(e.getAction() == Action.RIGHT_CLICK_BLOCK){
                    if(trigger != null && copyTrigger(player, clicked.getLocation())){
                        player.sendMessage(ChatColor.GREEN+"Copy Complete!");
                        player.sendMessage(ChatColor.GREEN+"Now you can paste it by left click on any block!");
                        e.setCancelled(true);
                    }
                }
            }
        }

        if(!e.isCancelled() && isLocationSetting(player)){
            handleLocationSetting(clicked, player);
            e.setCancelled(true);
        }
    }

    private void handleLocationSetting(Block clicked, Player player){
        Location loc = clicked.getLocation();
        T trigger = getTriggerForLocation(loc);
        if(trigger != null){
            player.sendMessage(ChatColor.RED+"Another trigger is set at there!");
            showTriggerInfo(player, clicked);
            return;
        }

        String script = getSettingLocationScript(player);
        if(script == null){
            player.sendMessage(ChatColor.RED+"Could not find script... but how?");
            return;
        }

        try {
            trigger = constructTrigger(script);
        } catch (IOException | LexerException | ParserException e1) {
            player.sendMessage(ChatColor.RED+"Encounterd an error!");
            player.sendMessage(ChatColor.RED+e1.getMessage());
            player.sendMessage(ChatColor.RED+"If you are an administrator, check console to see details.");
            e1.printStackTrace();
            return;
        }

        setTriggerForLocation(loc, trigger);

        showTriggerInfo(player, clicked);

        stopLocationSet(player);

        plugin.saveAsynchronously(this);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSignBreak(BlockBreakEvent e){
        if(e.isCancelled())
            return;

        Block block = e.getBlock();
        Block above = block.getRelative(BlockFace.UP);

        if(above.getType() != Material.SIGN && above.getType() != Material.SIGN_POST)
            return;

        BlockBreakEvent bbe = new BlockBreakEvent(above, e.getPlayer());
        onBreak(bbe);
        e.setCancelled(bbe.isCancelled());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e){
        Block block = e.getBlock();

        T trigger = getTriggerForLocation(block.getLocation());
        if(trigger == null)
            return;

        Player player = e.getPlayer();

        player.sendMessage(ChatColor.GRAY+"Cannot break trigger block.");
        player.sendMessage(ChatColor.GRAY+"To remove trigger, hold inspection tool "+INSPECTION_TOOL.name());
        e.setCancelled(true);
    }

    protected abstract T constructTrigger(String script) throws IOException, LexerException, ParserException;
    protected abstract String getTriggerTypeName();

    protected T getTriggerForLocation(Location loc) {
        SimpleLocation sloc = new SimpleLocation(loc);
        return getTriggerForLocation(sloc);
    }

    protected T getTriggerForLocation(SimpleLocation sloc) {
        SimpleChunkLocation scloc = new SimpleChunkLocation(sloc);

        if(!locationTriggers.containsKey(scloc))
            return null;

        Map<SimpleLocation, T> triggerMap = locationTriggers.get(scloc);
        if(!triggerMap.containsKey(sloc))
            return null;

        T trigger = triggerMap.get(sloc);
        return trigger;
    }

    protected void setTriggerForLocation(Location loc, T trigger) {
        SimpleLocation sloc = new SimpleLocation(loc);
        setTriggerForLocation(sloc, trigger);
    }

    protected void setTriggerForLocation(SimpleLocation sloc, T trigger) {
        SimpleChunkLocation scloc = new SimpleChunkLocation(sloc);

        Map<SimpleLocation, T> triggerMap = locationTriggers.get(scloc);
        if(!locationTriggers.containsKey(scloc)){
            triggerMap = new ConcurrentHashMap<>();
            locationTriggers.put(scloc, triggerMap);
        }

        triggerMap.put(sloc, trigger);

        plugin.saveAsynchronously(this);
    }

    protected T removeTriggerForLocation(Location loc) {
        SimpleLocation sloc = new SimpleLocation(loc);
        return removeTriggerForLocation(sloc);
    }

    protected T removeTriggerForLocation(SimpleLocation sloc) {
        SimpleChunkLocation scloc = new SimpleChunkLocation(sloc);

        Map<SimpleLocation, T> triggerMap = locationTriggers.get(scloc);
        if(!locationTriggers.containsKey(scloc)){
            return null;
        }

        T result = triggerMap.remove(sloc);

        int index = -1;

        synchronized(indexMap){
            index = indexMap.indexOf(sloc);

            if(index == -1){
                plugin.getLogger().severe("location "+sloc+" had no index!");
                plugin.getLogger().severe("Cannot delete file for "+sloc+". caused by "+getClass().getSimpleName());
                return result;
            }

            indexMap.set(index, null);
        }


        File file = new File(folder, this.slocToString(index, sloc));
        file.delete();

        plugin.saveAsynchronously(this);
        return result;
    }

    public void showTriggerInfo(Player player, Block clicked) {
        Trigger trigger = getTriggerForLocation(clicked.getLocation());
        if(trigger == null){
            return;
        }

        player.sendMessage("- - - - - - - - - - - - - -");
        player.sendMessage("Trigger: "+getTriggerTypeName());
        player.sendMessage("Block Type: " + clicked.getType().name());
        player.sendMessage("Location: " + clicked.getWorld().getName() + "@" + clicked.getLocation().getBlockX() + ","
                + clicked.getLocation().getBlockY() + "," + clicked.getLocation().getBlockZ());
        player.sendMessage("");
        player.sendMessage("Script:");
        player.sendMessage("  "+trigger.getScript());
        player.sendMessage("- - - - - - - - - - - - - -");
    }

    private Map<UUID, String> settingLocation = new HashMap<>();
    public boolean isLocationSetting(Player player){
        return settingLocation.containsKey(player.getUniqueId());
    }

    public boolean startLocationSet(Player player, String script){
        if(settingLocation.containsKey(player.getUniqueId()))
            return false;

        settingLocation.put(player.getUniqueId(), script);

        return true;
    }

    public boolean stopLocationSet(Player player){
        if(!settingLocation.containsKey(player.getUniqueId()))
            return false;

        settingLocation.remove(player.getUniqueId());

        return true;
    }

    public String getSettingLocationScript(Player player){
        return settingLocation.get(player.getUniqueId());
    }

    private final Map<UUID, ClipBoard> clipboard = new HashMap<>();

    @EventHandler
    public void onItemSwap(PlayerItemHeldEvent e){
        clipboard.remove(e.getPlayer().getUniqueId());
    }

    /**
    *
    * @param player
    * @param loc
    * @return true if cut ready; false if no trigger found at the location
    */
    private boolean cutTrigger(Player player, Location loc){
        SimpleLocation sloc = new SimpleLocation(loc);
        return cutTrigger(player, sloc);
    }

    /**
     *
     * @param player
     * @param sloc
     * @return true if cut ready; false if no trigger found at the location
     */
    private boolean cutTrigger(Player player, SimpleLocation sloc) {
        T trigger = getTriggerForLocation(sloc);
        if(trigger == null){
            return false;
        }

        clipboard.put(player.getUniqueId(), new ClipBoard(ClipBoard.BoardType.CUT, sloc));
        return true;
    }

    /**
    *
    * @param player
    * @param loc
    * @return true if copy ready; false if no trigger found at the location
    */
    private boolean copyTrigger(Player player, Location loc){
        SimpleLocation sloc = new SimpleLocation(loc);
        return copyTrigger(player, sloc);
    }

    /**
     *
     * @param player
     * @param sloc
     * @return true if copy ready; false if no trigger found at the location
     */
    private boolean copyTrigger(Player player, SimpleLocation sloc) {
        T trigger = getTriggerForLocation(sloc);
        if(trigger == null){
            return false;
        }

        clipboard.put(player.getUniqueId(), new ClipBoard(ClipBoard.BoardType.COPY, sloc));
        return true;
    }

    /**
    *
    * @param player
    * @param loc
    * @return true if pasted; false if nothing in the clipboard
    */
    private boolean pasteTrigger(Player player, Location loc){
        SimpleLocation sloc = new SimpleLocation(loc);
        return pasteTrigger(player, sloc);
    }

    /**
     *
     * @param player
     * @param sloc
     * @return true if pasted; false if nothing in the clipboard
     */
    private boolean pasteTrigger(Player player, SimpleLocation sloc){
        ClipBoard board = clipboard.remove(player.getUniqueId());
        if(board == null)
            return false;

        SimpleLocation from = board.location;
        if(from == null){
            return false;
        }

        T trigger = getTriggerForLocation(from);
        if(trigger == null){
            player.sendMessage(ChatColor.RED+"Could not find the trigger.");
            player.sendMessage(ChatColor.RED+"Your previous cut/copy seems deleted by other user.");
            return true;
        }

        T previous = null;
        try{
            if(board.type == ClipBoard.BoardType.CUT)
                previous = removeTriggerForLocation(from);

            setTriggerForLocation(sloc, trigger);
        }catch(Exception e){
            e.printStackTrace();
            //put it back if failed
            if(board.type == ClipBoard.BoardType.CUT && previous != null){
                setTriggerForLocation(sloc, (T) previous.clone());
            }
        }

        return true;
    }

    public Set<Map.Entry<SimpleLocation, Trigger>> getTriggersInChunk(Chunk chunk){
        SimpleChunkLocation scloc = new SimpleChunkLocation(chunk);
        return getTriggersInChunk(scloc);
    }

    public Set<Map.Entry<SimpleLocation, Trigger>> getTriggersInChunk(SimpleChunkLocation scloc){
        Set<Map.Entry<SimpleLocation, Trigger>> triggers = new HashSet<>();
        if(!locationTriggers.containsKey(scloc))
            return triggers;

        for(Entry<SimpleChunkLocation, Map<SimpleLocation, T>> entry : locationTriggers.entrySet()){
            for(Entry<SimpleLocation, T> entryIn : entry.getValue().entrySet()){
                triggers.add(new SimpleEntry<SimpleLocation, Trigger>(entryIn.getKey(), entryIn.getValue()));
            }
        }

        return triggers;
    }

    private static class ClipBoard{
        final BoardType type;
        final SimpleLocation location;
        public ClipBoard(BoardType type, SimpleLocation location) {
            this.type = type;
            this.location = location;
        }

        enum BoardType{
            CUT, COPY;
        }
    }
}
