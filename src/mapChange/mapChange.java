package mapChange;

import arc.Core;
import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Reflect;
import arc.util.Strings;
import arc.util.Timer;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;
import mindustry.mod.Plugin;
import mindustry.server.ServerControl;

import static mindustry.Vars.*;

public class mapChange extends Plugin{
    public Map currentlyVoting = null;
    public int currectSelectVotes = 0;
    public float selectVoteFraction = 0.6f;
    public float selectVoteLength = 30f;
    public Timer.Task selectVoteTask = null;
    public Seq<String> selectVoted = new Seq<>(); // String - uuid

    public int currentSkipVotes = 0;
    public float skipVoteFraction = 0.6f;
    public float skipVoteLength = 40f;
    public ObjectMap<String, Boolean> skipVoted = new ObjectMap<>(); // String, boolean - uuid, votedPositive

    public void registerClientCommands(CommandHandler handler){
        handler.<Player>register("voteselect", "[name...]", "Vote to select the next map by name or #id. Use without args to display maplist.", (args, player) -> {
            try{
                if(currentlyVoting != null){
                    if(args[0].equalsIgnoreCase("yes") || args[0].equalsIgnoreCase("y")){
                        if(selectVoted.contains(player.uuid())){
                            player.sendMessage("[scarlet]You can't vote twice.");
                        }else{
                            voteSelect(player);
                        }
                    }else{
                        player.sendMessage("[scarlet]A vote is already in progress.");
                    }
                    return;
                }
                if(args.length == 0){
                    StringBuilder builder = new StringBuilder();
                    builder.append("[orange]Maps: \n");
                    int id = 0;
                    for(Map m : maps.all()){
                        builder.append("[accent]id:").append(id).append("[white] ").append(m.name()).append(" ");
                        id++;
                    }
                    player.sendMessage(builder.toString());
                    return;
                }
                Map found;
                if(args[0].startsWith("#") && Strings.canParseInt(args[0].substring(1))){
                    int id = Strings.parseInt(args[0].substring(1));
                    found = maps.all().get(id);
                }else{
                    found = findMap(args[0]);
                }
                if(found == null){
                    player.sendMessage("[scarlet]No map [orange]'" + args[0] + "'[scarlet] found.");
                    return;
                }
                currentlyVoting = found;
                voteSelect(player);
                selectVoteTask = Timer.schedule(() -> {
                    Call.sendMessage("[scarlet]Next map override vote failed.");
                    currentlyVoting = null;
                    selectVoted.clear();
                    currectSelectVotes = 0;
                }, selectVoteLength);
            }catch(Exception badArguments){
                player.sendMessage("[scarlet]Something went wrong. Please check command arguments.");
            }
        });
        handler.<Player>register("voteskip", "<y/n>","Vote to skip the current map.", (args, player) -> {
            if(args[0].equalsIgnoreCase("y") || args[0].equalsIgnoreCase("yes")){
                voteSkip(player, true);
            }else if(args[0].equalsIgnoreCase("n") || args[0].equalsIgnoreCase("no")){
                voteSkip(player, false);
            }else{
                player.sendMessage("[scarlet]Voteskip only accepts 'y' (yes) or 'n' (no) as arguments.");
            }
        });
    }

    public void voteSelect(Player player){
        selectVoted.add(player.uuid());
        currectSelectVotes++;
        int voteCap = (int)Math.ceil((float)Groups.player.size() * selectVoteFraction);
        Call.sendMessage(player.coloredName() + " [lightgray]has voted to select " + currentlyVoting.name() + " [lightgray]as the next map (" + currectSelectVotes + "/" + voteCap + "). Type '/voteselect yes' to agree.");
        if(currectSelectVotes >= voteCap){
            Core.app.getListeners().each(lst -> {
                if(lst instanceof ServerControl){
                    ServerControl scont = (ServerControl)lst;
                    Reflect.set(scont, "nextMapOverride", currentlyVoting);
                }
            });
            Call.sendMessage("[accent]Next map overridden to be " + currentlyVoting.name() + "[accent].");
            currentlyVoting = null;
            selectVoted.clear();
            currectSelectVotes = 0;
            selectVoteTask.cancel();
        }
    }
    public void voteSkip(Player player, boolean agree){
        int voteCap = (int)Math.ceil((float)Groups.player.size() * skipVoteFraction);
        if(skipVoted.isEmpty()){
            Timer.schedule(() -> {
                if(currentSkipVotes < (int)Math.ceil((float)Groups.player.size() * skipVoteFraction)){
                    Call.sendMessage("[scarlet]Map skip vote failed.");
                }else{
                    Call.sendMessage("[lightgray]Map skip vote successful.");
                    Events.fire(new GameOverEvent(Team.derelict));
                }
                skipVoted.clear();
                currentSkipVotes = 0;
            }, skipVoteLength);
        }
        if(skipVoted.containsKey(player.uuid())){
            if(skipVoted.get(player.uuid()) != agree){
                currentSkipVotes += agree ? 1 : -1;
                skipVoted.remove(player.uuid());
                player.sendMessage("[lightgray]Swapped vote.");
            }else{
                player.sendMessage("[scarlet]You can't vote twice.");
                return;
            }
        }
        skipVoted.put(player.uuid(), agree);
        currentSkipVotes += agree ? 1 : -1;
        Call.sendMessage(player.coloredName() + " [lightgray]has voted to skip map (" + currentSkipVotes + "/" + voteCap + "). Type '/voteskip y/n' to agree or disagree.");
    }

    public Map findMap(String mapName){
        return maps.all().find(map -> Strings.stripColors(map.name().replace('_', ' ')).equalsIgnoreCase(Strings.stripColors(mapName).replace('_', ' ')));
    }
}
