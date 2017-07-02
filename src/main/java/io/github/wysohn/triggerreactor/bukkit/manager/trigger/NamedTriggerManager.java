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
package io.github.wysohn.triggerreactor.bukkit.manager.trigger;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import io.github.wysohn.triggerreactor.core.main.TriggerReactor;
import io.github.wysohn.triggerreactor.core.manager.trigger.AbstractNamedTriggerManager;
import io.github.wysohn.triggerreactor.core.script.lexer.LexerException;
import io.github.wysohn.triggerreactor.core.script.parser.ParserException;
import io.github.wysohn.triggerreactor.tools.FileUtil;

public class NamedTriggerManager extends AbstractNamedTriggerManager {
    private final File folder;

    public NamedTriggerManager(TriggerReactor plugin) {
        super(plugin);

        folder = new File(plugin.getDataFolder(), "NamedTriggers");
        if(!folder.exists())
            folder.mkdirs();

        reload();
    }

    @Override
    public void reload() {
        triggers.clear();

        for (File file : folder.listFiles()) {
            try {
                load(file);
            } catch (IOException | LexerException | ParserException e) {
                e.printStackTrace();
                continue;
            }
        }
    }

    @Override
    public void saveAll() {
        Set<Entry<String, Trigger>> failed = new HashSet<>();

        for(Entry<String, Trigger> entry : triggers.entrySet()){
            String key = entry.getKey().replaceAll(":", File.separator);
            Trigger trigger = entry.getValue();

            File file = new File(folder, key);
            if(!file.getParentFile().exists())
                file.getParentFile().mkdirs();

            try{
                FileUtil.writeToFile(file, trigger.getScript());
            }catch(IOException e){
                e.printStackTrace();
                plugin.getLogger().warning("Failed to save file "+key);
                failed.add(entry);
            }
        }
    }

    /**
     * The naming follows this rule: triggers saved in the NamedTriggers folder will have name exactly same as the file name.
     * However, if you have another folder under NamedTriggers, and the trigger is saved under that folder,
     * you need to specify the folder in front of the file name.
     * For example, if you have your trigger path NamedTriggers/SomeFolder/AnotherFolder/yourtrigger, then the name will be like
     * this: <b>NamedTriggers:SomeFolder:AnotherFolder:yourtrigger</b>.
     * @param name the trigger name including path if any exists
     * @return the Trigger; null if no such trigger
     */
    @Override
    public Trigger getTriggerForName(String name){
        return triggers.get(name);
    }

    @Override
    protected void deleteInfo(Trigger trigger) {
        //We don't delete named triggers in-game
    }
}
