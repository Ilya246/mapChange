package mapChange;

import arc.Core;
import arc.Events;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.maps.Map;
import mindustry.mod.Plugin;
import mindustry.server.ServerControl;

import static mindustry.Vars.*;

public class mapChange extends Plugin{
    public Map currentlyVoting = null;
    public int currectSelectVotes = 0;
    public Vote skipVote = null;
    public Vote selectVote = null;

    public static String configPrefix = "map-";

    public enum Config{
        selectVoteFraction("The fraction of players that need to vote for map selection.", 0.35f),
        selectVoteLength("The length, in seconds, of a map selection vote.", 45f),
        allowBuiltinMaps("Whether to allow built-in maps in selection votes.", true),
        skipVoteFraction("The fraction of players that need to vote to skip the current map.", 0.45f),
        skipVoteLength("The length, in seconds, of a map skip vote.", 60f);

        public static final Config[] all = values();

        public final Object defaultValue;
        public String description;

        Config(String description, Object value){
            this.description = description;
            this.defaultValue = value;
        }
        public String getName(){
            return configPrefix + name();
        }
        public boolean b(){
            return Core.settings.getBool(getName(), (boolean)defaultValue);
        }
        public float f(){
            return Core.settings.getFloat(getName(), (float)defaultValue);
        }
        public String s(){
            return Core.settings.get(getName(), defaultValue).toString();
        }
        public void set(Object value){
            Core.settings.put(getName(), value);
        }
    }

    public class Vote{
        public ObjectMap<String, Boolean> voted = new ObjectMap<>();
        public Timer.Task task;
        public Object voteObject;

        public Vote(Timer.Task task){
            this.task = task;
        }
        public Vote(Timer.Task task, Object voteObject){
            this.task = task;
            this.voteObject = voteObject;
        }
        public int votes(){
            int val = 0;
            for(boolean v : voted.values()){
                val += v ? 1 : -1;
            }
            return val;
        }
    }

    public void registerClientCommands(CommandHandler handler){
        handler.<Player>register("voteselect", "[y/n/name...]", "Vote to change the next map to be played, specified by name or #id. Does not end the game. Use without arguments to display maplist.", (args, player) -> {
            if(args.length == 0){
                StringBuilder builder = new StringBuilder();
                builder.append("[orange]Maps: \n");
                int id = 0;
                for(Map m : availableMaps()){
                    builder.append("[accent]id:").append(id).append("[white] ").append(m.name()).append(" ");
                    id++;
                }
                builder.append("\n[accent]Example usage: [lightgray]/voteselect #5");
                player.sendMessage(builder.toString());
                return;
            }
            if(selectVote != null){
                boolean vote = !(args[0].equalsIgnoreCase("no") || args[0].equalsIgnoreCase("n"));
                if(selectVote.voted.containsKey(player.uuid()) && selectVote.voted.get(player.uuid()) == vote){
                    player.sendMessage("[scarlet]You can't vote twice.");
                    return;
                }
                selectVote.voted.put(player.uuid(), vote);
                Call.sendMessage(player.coloredName() + " [lightgray]has voted to select " + ((Map)selectVote.voteObject).name() + " [lightgray]as the next map (" + selectVote.votes() + "/" + selectVoteCap() + "). Use '/voteselect [y/n]' to vote. This does not end the game.");
                return;
            }
            Map found;
            if(args[0].startsWith("#") && Strings.canParseInt(args[0].substring(1))){
                int id = Strings.parseInt(args[0].substring(1));
                found = availableMaps().get(id);
            }else{
                found = availableMaps().find(map -> Strings.stripColors(map.name().replace('_', ' ')).equalsIgnoreCase(Strings.stripColors(args[0]).replace('_', ' ')));
            }
            if(found == null){
                player.sendMessage("[scarlet]No map [orange]'" + args[0] + "'[scarlet] found.");
                return;
            }
            selectVote = new Vote(
                Timer.schedule(() -> {
                    boolean succeeded = selectVote.votes() >= selectVoteCap();
                    Call.sendMessage(succeeded ? "[accent]Next map override vote successful.\nSwitching next map to " + ((Map)selectVote.voteObject).name() : "[scarlet]Next map override vote failed.");
                    if(succeeded){
                        Reflect.set(Core.app.getListeners().find(lst -> lst instanceof ServerControl), "nextMapOverride", (Map)selectVote.voteObject);
                    }
                    selectVote = null;
                },
            Config.selectVoteLength.f()), found);
            selectVote.voted.put(player.uuid(), true);
            Call.sendMessage(player.coloredName() + " [lightgray]has voted to select " + found.name() + " [lightgray]as the next map (1/" + selectVoteCap() + "). Use '/voteselect [y/n]' to vote. This does not end the game.");
        });
        handler.<Player>register("voteskip", "[y/n]","Vote to skip the current map. Using without arguments counts as a 'yes' vote.", (args, player) -> {
            if(skipVote == null){
                skipVote = new Vote(Timer.schedule(() -> {
                    boolean succeeded = skipVote.votes() >= skipVoteCap();
                    Call.sendMessage(succeeded ? "[accent]Map skip vote successful." : "[scarlet]Map skip vote failed.");
                    if(succeeded){
                        Events.fire(new GameOverEvent(Team.derelict));
                    }
                    skipVote = null;
                }, Config.skipVoteLength.f()));
            }
            boolean vote = args.length == 0 || !(args[0].equalsIgnoreCase("no") || args[0].equalsIgnoreCase("n"));
            if(skipVote.voted.containsKey(player.uuid()) && skipVote.voted.get(player.uuid()) == vote){
                player.sendMessage("[scarlet]You can't vote twice.");
                return;
            }
            skipVote.voted.put(player.uuid(), vote);
            Call.sendMessage(player.coloredName() + " [lightgray]has voted to skip the current map (" + skipVote.votes() + "/" + skipVoteCap() + "). Use '/voteskip [y/n]' to vote.");
        });
    }
    public Seq<Map> availableMaps(){
        return Config.allowBuiltinMaps.b() ? maps.all() : maps.customMaps();
    }
    public int selectVoteCap(){
        return (int)(Groups.player.size() * Config.selectVoteFraction.f());
    }
    public int skipVoteCap(){
        return (int)(Groups.player.size() * Config.skipVoteFraction.f());
    }

    public void registerServerCommands(CommandHandler handler){
        handler.register("mapchangeconfig", "[name] [value]", "Configure bot plugin settings. Run with no arguments to list values.", args -> {
            if(args.length == 0){
                Log.info("All config values:");
                for(Config c : Config.all){
                    Log.info("&lk| @: @", c.name(), "&lc&fi" + c.s());
                    Log.info("&lk| | &lw" + c.description);
                    Log.info("&lk|");
                }
                return;
            }
            try{
                Config c = Config.valueOf(args[0]);
                if(args.length == 1){
                    Log.info("'@' is currently @.", c.name(), c.s());
                }else{
                    if(args[1].equals("default")){
                        c.set(c.defaultValue);
                    }else{
                        try{
                            if(c.defaultValue instanceof Float){
                                c.set(Float.parseFloat(args[1]));
                            }else{
                                c.set(Boolean.parseBoolean(args[1]));
                            }
                        }catch(NumberFormatException e){
                            Log.err("Not a valid number: @", args[1]);
                            return;
                        }
                    }
                    Log.info("@ set to @.", c.name(), c.s());
                    Core.settings.forceSave();
                }
            }catch(IllegalArgumentException e){
                Log.err("Unknown config: '@'. Run the command with no arguments to get a list of valid configs.", args[0]);
            }
        });
    }
}
