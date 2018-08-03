package me.deprilula28.gamesrob.baseFramework;

import lombok.Data;
import me.deprilula28.gamesrob.Language;
import me.deprilula28.gamesrob.achievements.AchievementType;
import me.deprilula28.gamesrob.commands.CommandManager;
import me.deprilula28.gamesrob.data.GuildProfile;
import me.deprilula28.gamesrob.data.Statistics;
import me.deprilula28.gamesrob.data.UserProfile;
import me.deprilula28.gamesrob.utility.Cache;
import me.deprilula28.gamesrob.utility.Constants;
import me.deprilula28.gamesrob.utility.Log;
import me.deprilula28.gamesrob.utility.Utility;
import me.deprilula28.jdacmdframework.Command;
import me.deprilula28.jdacmdframework.CommandContext;
import me.deprilula28.jdacmdframework.RequestPromise;
import me.deprilula28.jdacmdframework.exceptions.CommandArgsException;
import me.deprilula28.jdacmdframework.exceptions.InvalidCommandSyntaxException;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.Event;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

import java.awt.event.HierarchyBoundsAdapter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/*
THIS CLASS IS A MESS HELP
 */

@Data
public class Match extends Thread {
    public static final Map<TextChannel, Match> GAMES = new HashMap<>();
    public static final Map<TextChannel, Match> REMATCH_GAMES = new HashMap<>();

    public static final Map<JDA, List<Match>> ACTIVE_GAMES = new HashMap<>();
    public static final Map<User, Match> PLAYING = new HashMap<>();
    private static final long MATCH_TIMEOUT_PERIOD = TimeUnit.MINUTES.toMillis(5);
    private static final long REMATCH_TIMEOUT_PERIOD = TimeUnit.MINUTES.toMillis(1);

    protected TextChannel channelIn;
    private final Map<User, Map<AchievementType, Integer>> addAchievement = new HashMap<>();
    private GamesInstance game;
    private GameState gameState;
    private List<Optional<User>> players = new ArrayList<>();
    private User creator;
    private transient MatchHandler matchHandler;
    private transient RequestPromise<Message> matchMessage;

    private String language;
    private Map<String, String> options;
    public int matchesPlayed = 0;
    private boolean canReact;
    private Optional<Integer> betting = Optional.empty();
    private boolean multiplayer;
    public int iteration;
    private boolean allowRematch;
    private Map<String, Integer> settings = new HashMap<>();
    public boolean playMore = false;

    // Rematch multiplayer
    public Match(GamesInstance game, User creator, TextChannel channel, List<Optional<User>> players,
                 Map<String, String> options, int matchesPlayed) {

        multiplayer = true;
        String guildLang = GuildProfile.get(channel.getGuild()).getLanguage();
        language = guildLang == null ? Constants.DEFAULT_LANGUAGE : guildLang;
        channelIn = channel;
        Member member = channelIn.getGuild().getMember(channelIn.getJDA().getSelfUser());
        canReact = Utility.hasPermission(channelIn, member, Permission.MESSAGE_ADD_REACTION);

        this.creator = creator;
        this.players = players;
        this.game = game;
        this.options = options;

        matchHandler = game.getMatchHandlerSupplier().get();
        updateSettings(options);

        gameState = GameState.MATCH;

        GAMES.put(channel, this);
        ACTIVE_GAMES.get(channel.getJDA()).add(this);
        this.matchesPlayed = matchesPlayed;

        setName("Game timeout thread for " + game.getName(language));
        setDaemon(true);
        start();

        matchHandler.begin(this, n -> {
            MessageBuilder builder = new MessageBuilder().append(Language.transl(language, "gameFramework.begin",
                    game.getName(language), game.getLongDescription(language)
            ));
            matchHandler.updatedMessage(false, builder);
            return RequestPromise.forAction(channelIn.sendMessage(builder.build())).then(no -> gameState = GameState.MATCH);
        });
        players.stream().filter(Optional::isPresent).forEach(it -> {
            PLAYING.put(it.get(), this);
            achievement(it.get(), AchievementType.PLAY_GAMES, 1);
        });
    }

    // Collective
    public Match(GamesInstance game, User creator, TextChannel channel, Map<String, String> options) {
        multiplayer = true;
        String guildLang = GuildProfile.get(channel.getGuild()).getLanguage();
        language = guildLang == null ? Constants.DEFAULT_LANGUAGE : guildLang;
        channelIn = channel;
        Member member = channelIn.getGuild().getMember(channelIn.getJDA().getSelfUser());
        canReact = Utility.hasPermission(channelIn, member, Permission.MESSAGE_ADD_REACTION);
        players.add(Optional.of(creator));

        this.betting = Optional.empty();
        this.creator = creator;
        this.game = game;
        this.options = options;
        matchHandler = game.getMatchHandlerSupplier().get();
        gameState = GameState.MATCH;
        updateSettings(options);

        GAMES.put(channel, this);
        PLAYING.put(creator, this);
        achievement(creator, AchievementType.PLAY_GAMES, 1);
        ACTIVE_GAMES.get(channel.getJDA()).add(this);

        Statistics.get().registerGame(game);

        matchHandler.begin(this, no -> {
            MessageBuilder builder = new MessageBuilder().append(Language.transl(language, "gameFramework.collectiveMatch",
                    game.getName(language), game.getLongDescription(language)
            ));
            matchHandler.updatedMessage(false, builder);
            matchMessage = RequestPromise.forAction(channel.sendMessage(builder.build()));
            matchMessage.then(message -> message.addReaction("\uD83D\uDC65").queue());

            return matchMessage;
        });

        setName("Game timeout thread for " + game.getName(language));
        setDaemon(true);
        start();
    }

    // Hybrid/Multiplayer
    public Match(GamesInstance game, User creator, TextChannel channel, Map<String, String> options, Optional<Integer> bet) {
        multiplayer = true;
        String guildLang = GuildProfile.get(channel.getGuild()).getLanguage();
        language = guildLang == null ? Constants.DEFAULT_LANGUAGE : guildLang;
        channelIn = channel;
        Member member = channelIn.getGuild().getMember(channelIn.getJDA().getSelfUser());
        canReact = Utility.hasPermission(channelIn, member, Permission.MESSAGE_ADD_REACTION);
        players.add(Optional.of(creator));

        this.betting = betting;
        this.creator = creator;
        this.game = game;
        this.options = options;
        matchHandler = game.getMatchHandlerSupplier().get();
        updateSettings(options);

        gameState = GameState.PRE_GAME;

        GAMES.put(channel, this);
        PLAYING.put(creator, this);
        ACTIVE_GAMES.get(channel.getJDA()).add(this);

        updatePreMessage();

        setName("Game timeout thread for " + game.getName(language));
        setDaemon(true);
        start();
    }

    // Game Timeout
    @Override
    public void run() {
        while (gameState != GameState.POST_MATCH)
            try {
                Thread.sleep(MATCH_TIMEOUT_PERIOD);
                if (gameState != GameState.POST_MATCH)
                    onEnd(Language.transl(language, "gameFramework.timeout", Utility.formatPeriod(MATCH_TIMEOUT_PERIOD)),
                            true);
            } catch (InterruptedException e) {}
        try {
            Thread.sleep(REMATCH_TIMEOUT_PERIOD);
            if (this.equals(REMATCH_GAMES.get(channelIn))) REMATCH_GAMES.remove(channelIn);
            else if (REMATCH_GAMES.containsKey(channelIn)) REMATCH_GAMES.get(channelIn).matchesPlayed = matchesPlayed + 1;
            if (Utility.hasPermission(channelIn, channelIn.getGuild().getMember(channelIn.getJDA().getSelfUser()),
                    Permission.MESSAGE_MANAGE)) matchMessage.then(it -> it.clearReactions().queue());
        } catch (InterruptedException e) {}
    }

    private byte[] getImage(int iteration) {
        if (this.iteration != iteration) throw new RuntimeException("Invalid iteration.");
        if (!(matchHandler instanceof MatchHandler.ImageMatchHandler)) throw new RuntimeException("Not an image game.");
        return Cache.get(matchHandler.hashCode() + "_" + iteration, n -> ((MatchHandler.ImageMatchHandler) matchHandler).getImage());
    }

    private void updateSettings(Map<String, String> options) {
        CommandManager.matchHandlerSettings.get(game.getMatchHandlerClass()).forEach(cur -> {
            String name = cur.getField().getName();
            Optional<Integer> intOpt = options.containsKey(name) ? GameUtil.safeParseInt(options.get(name)) : Optional.empty();
            intOpt.ifPresent(num -> {
                if (num < cur.getAnnotation().min() || num > cur.getAnnotation().max())
                    throw new CommandArgsException(Language.transl(language, "gameFramework.settingOutOfRange",
                            name, cur.getAnnotation().min(), cur.getAnnotation().max()
                    ));
            });

            try {
                int setting = intOpt.orElse(cur.getAnnotation().defaultValue());
                cur.getField().set(matchHandler, setting);
                settings.put(name, setting);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set value for setting", e);
            }
        });
    }

    private String getPregameText() {
        StringBuilder builder = new StringBuilder(Language.transl(language, "gameFramework.multiplayerMatch",
                game.getName(language), game.getShortDescription(language)
        ));
        players.forEach(cur -> builder.append(cur.map(it -> "☑ **" + it.getName() + "**").orElse("AI")).append("\n"));
        for (int i = players.size(); i <= game.getMinTargetPlayers(); i ++) builder.append("⏰ Waiting\n\n");

        betting.ifPresent(amount -> builder.append(Language.transl(language, "gameFramework.betting", amount)).append("\n"));
        builder.append(Language.transl(language, canReact ? "gameFramework.joinToPlay" : "gameFramework.joinToPlayNoReaction",
                Constants.getPrefix(channelIn.getGuild()), players.size(), game.getMaxTargetPlayers()
        ));

        if (game.getGameType() == GameType.HYBRID || getPlayers().size() >= game.getMinTargetPlayers() + 1)
            builder.append("\n").append(Language.transl(language, "gameFramework.playButton", game.getName(language)));

        return builder.toString();
    }

    private void updatePreMessage() {
        if (matchMessage == null) matchMessage = RequestPromise.forAction(channelIn.sendMessage(getPregameText()));
        else matchMessage.then(it -> it.editMessage(getPregameText()).queue());
        if (canReact) {
            matchMessage.then(msg -> msg.addReaction("\uD83D\uDEAA").queue());
            if (game.getGameType() == GameType.HYBRID || getPlayers().size() >= game.getMinTargetPlayers() + 1) matchMessage.then(it -> {
                if (it.getReactions().stream().noneMatch(react -> react.getReactionEmote().getName().equals("▶")))
                    it.addReaction("▶").queue();
            });
        }
    }

    public void achievement(User user, AchievementType type, int amount) {
        if (!addAchievement.containsKey(user)) addAchievement.put(user, new HashMap<>());
        if (!addAchievement.get(user).containsKey(type)) addAchievement.get(user).put(type, 0);
        addAchievement.get(user).put(type, amount + addAchievement.get(user).get(type));
    }

    public void messageEvent(MessageReceivedEvent event) {
        try {
            if (event.getGuild() == null) getMatchHandler().receivedDM(event.getMessage().getContentRaw(),
                    event.getAuthor(), event.getMessage());
            else getMatchHandler().receivedMessage(event.getMessage().getContentRaw(),
                    event.getAuthor(), event.getMessage());
        } catch (Exception e) {
            Optional<String> trelloUrl = Log.exception("Game of " + getGame().getName(Constants.DEFAULT_LANGUAGE) + " had an error", e);
            onEnd("⛔ Oops! Something spoopy happened and I had to stop this game.\n" +
                    "You can send this: " + trelloUrl.orElse("*No trello info found*") + " to our support server at https://discord.gg/gJKQPkN !", false);
        }
    }

    public void joined(User user) {
        interrupt(); // Keep the match alive
        PLAYING.put(user, this);
        players.add(Optional.of(user));
        achievement(user, AchievementType.PLAY_GAMES, 1);
        if (gameState == GameState.PRE_GAME) {
            if (players.size() == game.getMaxTargetPlayers() + 1) gameStart();
            else updatePreMessage();
        } else if (game.getGameType() != GameType.COLLECTIVE) channelIn.sendMessage(Language.transl(language, "gameFramework.join",
            user.getAsMention(),
            players.size(), game.getMaxTargetPlayers()
        )).queue();
    }
    /*
    Reactions
     */
    public void collectiveReacion(CommandContext context) {
        if (!game.getGameType().equals(GameType.COLLECTIVE) || !context.getAuthor().equals(creator)) {
            throw new InvalidCommandSyntaxException();
        }

        playMore = true;
        matchMessage.then(it -> it.getReactions().stream().filter(reaction -> reaction.getReactionEmote().getName().equals("\uD83D\uDC65"))
                .findFirst().ifPresent(joystick -> joystick.removeReaction(joystick.getJDA().getSelfUser()).queue()));
        MessageBuilder builder = new MessageBuilder();
        matchHandler.updatedMessage(false, builder);
        matchMessage.then(it -> it.editMessage(builder.build()).queue());
    }

    public void joinReaction(CommandContext context) {
        if (Match.PLAYING.containsKey(context.getAuthor()) || getPlayers().contains(Optional.of(context.getAuthor()))
                || (betting.isPresent() &&
                !UserProfile.get(context.getAuthor()).transaction(betting.get(), "transactions.betting"))
                || gameState != GameState.PRE_GAME) {
            throw new InvalidCommandSyntaxException();
        }

        joined(context.getAuthor());
    }

    public void startReaction(CommandContext context) {
        if (!gameState.equals(GameState.PRE_GAME) || !context.getAuthor().equals(creator))
            throw new InvalidCommandSyntaxException();
        if (game.getGameType().equals(GameType.HYBRID) && players.size() == 1) {
            multiplayer = false;
            matchMessage.then(it -> it.delete().queue());
            Statistics.get().registerGame(game);

            matchHandler.begin(this, no -> {
                MessageBuilder builder = new MessageBuilder().append(Language.transl(language, "gameFramework.singleplayerMatch",
                        game.getName(language), game.getLongDescription(language)
                ));
                matchHandler.updatedMessage(false, builder);
                return matchMessage = RequestPromise.forAction(channelIn.sendMessage(builder.build())).then(no2 -> gameState = GameState.MATCH);
            });
            players.stream().filter(Optional::isPresent).forEach(it -> achievement(it.get(), AchievementType.PLAY_GAMES, 1));
        } else {
            if (getPlayers().size() < game.getMinTargetPlayers() + 1) throw new InvalidCommandSyntaxException();

            gameStart();
        }
    }

    public void gameStart() {
        matchMessage.then(it -> it.delete().queue());
        Statistics.get().registerGame(game);

        matchHandler.begin(this, no -> {
            MessageBuilder builder = new MessageBuilder().append(Language.transl(language, "gameFramework.begin",
                    game.getName(language), game.getLongDescription(language)
            ));
            matchHandler.updatedMessage(false, builder);
            return matchMessage = RequestPromise.forAction(channelIn.sendMessage(builder.build())).then(no2 -> gameState = GameState.MATCH);
        });
    }

    public void rematchReaction(CommandContext context) {
        if (!allowRematch) throw new InvalidCommandSyntaxException();
        if (GAMES.containsKey(context.getChannel()) || PLAYING.containsKey(context.getAuthor()) ||
                !players.stream().filter(Optional::isPresent).map(Optional::get).allMatch(context.getReactionUsers()::contains)) return;
        matchMessage.then(it -> it.delete().queue());

        if (game.getGameType().equals(GameType.HYBRID)) new Match(game, creator, channelIn, options)
                .matchesPlayed = matchesPlayed + 1;
        else new Match(game, creator, channelIn, players, options, matchesPlayed + 1);
        interrupt();
    }

    public <T extends Event> void on(String name, Consumer<T> handler) {

    }

    public void onEnd(int tokens) {
        onEnd(getPlayers().get(0), tokens);
    }

    public void onEnd(Optional<User> winner) {
        onEnd(winner, Constants.MATCH_WIN_TOKENS);
    }

    public void onEnd(Optional<User> winner, int tokens) {
        players.forEach(cur ->
            cur.ifPresent(user -> {
                UserProfile userProfile = UserProfile.get(user);
                boolean victory = winner.equals(Optional.of(user));
                userProfile.registerGameResult(channelIn.getGuild(), user, victory, !victory, game);

                if (winner.equals(cur)) {
                    int won = betting.map(it -> it * players.size()).orElse(tokens);
                    userProfile.addTokens(won, "transactions.winGamePrize");
                    achievement(user, AchievementType.REACH_TOKENS, won);
                }
            })
        );
        winner.ifPresent(it -> achievement(it, AchievementType.WIN_GAMES, 1));

        onEnd(Language.transl(language, "gameFramework.winner", winner.map(User::getAsMention).orElse("**AI**"))
                + Language.transl(language, "gameFramework.winnerTokens", betting.map(it -> it * players.size())
                        .orElse(tokens), Constants.getPrefix(channelIn.getGuild())), false);
    }

    public void onEnd(String reason, boolean registerPoints) {
        players.forEach(cur ->
            cur.ifPresent(user -> {
                if (registerPoints) {
                    UserProfile.get(user).registerGameResult(channelIn.getGuild(), user, false, false, game);
                    betting.ifPresent(amount -> {
                        UserProfile.get(user).addTokens(amount, "transactions.winGamePrize");
                        achievement(user, AchievementType.REACH_TOKENS, amount);
                    });
                }
                PLAYING.remove(user);
            })
        );

        allowRematch = gameState == GameState.MATCH;

        MessageBuilder gameOver = new MessageBuilder().append(Language.transl(language, "gameFramework.gameOver",
                reason));
        Log.wrapException("Getting message for match end", () -> {
            if (gameState != GameState.PRE_GAME) matchHandler.updatedMessage(true, gameOver);
        });

        if (Utility.hasPermission(channelIn, channelIn.getGuild().getMember(channelIn.getJDA().getSelfUser()),
                Permission.MESSAGE_ADD_REACTION) && allowRematch)
            gameOver.append("\n").append(Language.transl(language, "gameFramework.rematch"));

        players.forEach(cur -> cur.ifPresent(user -> {
            if (addAchievement.containsKey(user)) addAchievement.get(user).forEach((type, amount) ->
                type.addAmount(true, amount, gameOver, user, channelIn.getGuild(), language));
        }));

        ACTIVE_GAMES.get(channelIn.getJDA()).remove(this);
        GAMES.remove(channelIn);

        if (matchMessage != null) matchMessage.then(it -> it.editMessage(gameOver.build()).queue());
        else matchMessage = RequestPromise.forAction(channelIn.sendMessage(gameOver.build()));
        
        if (allowRematch) matchMessage.then(msg -> msg.addReaction("\uD83D\uDD04").queue());

        gameState = GameState.POST_MATCH;
        interrupt(); // Stop the timeout
        REMATCH_GAMES.put(channelIn, this);
    }

    private static final int MIN_BET = 10;
    private static final int MAX_BET = 1500;

    public static Command.Executor createCommand(GamesInstance game) {
        return CommandManager.permissionLock(context -> {
            String prefix = Constants.getPrefix(context.getGuild());
            if (GAMES.containsKey(context.getChannel())) {
                Match match = GAMES.get(context.getChannel());
                return Language.transl(context, "gameFramework.activeGame") + (match.getPlayers()
                            .contains(Optional.of(context.getAuthor()))
                        ? GuildProfile.get(context.getGuild()).canStop(context)
                            ? Language.transl(context, "gameFramework.viewPlayersStop", prefix, prefix)
                            : Language.transl(context, "gameFramework.viewPlayers", prefix)
                        : Language.transl(context, "gameFramework.typeToJoin", prefix));
            }
            if (PLAYING.containsKey(context.getAuthor())) {
                return Language.transl(context, "genericMessages.alreadyPlaying",
                        PLAYING.get(context.getAuthor()).getChannelIn().getAsMention(), prefix
                );
            }

            Map<String, String> settings = new HashMap<>();
            context.remaining().forEach(it -> {
                String[] split = it.split("=");
                if (split.length == 2) settings.put(split[0], split[1]);
            });
            if (game.getGameType() == GameType.COLLECTIVE) {
                new Match(game, context.getAuthor(), context.getChannel(), settings);
                return null;
            }

            Optional<Integer> bet = settings.containsKey("bet") ? GameUtil.safeParseInt(settings.get("bet")) : Optional.empty();

            if (bet.isPresent()) {
                int amount = bet.get();
                if (amount < MIN_BET || amount > MAX_BET) return Language.transl(context,
                        "command.slots.invalidTokens", MIN_BET, MAX_BET);
                if (!UserProfile.get(context.getAuthor()).transaction(amount, "transactions.betting"))
                    return Constants.getNotEnoughTokensMessage(context, amount);
            } else if (game.isRequireBetting()) return Language.transl(context, "gameFramework.requireBetting");

            new Match(game, context.getAuthor(), context.getChannel(), settings, bet);
            return null;
        }, ctx -> GuildProfile.get(ctx.getGuild()).canStart(ctx));
    }
}
