import java.util.*;
import java.util.concurrent.*;

class GameThread extends Thread {
    private final String gameId;
    private int MAX_PLAYERS = 4;

    // common inputQueue
    private final BlockingQueue<PlayerCommand> commandQueue = new LinkedBlockingQueue<>();
    private final Map<String, BlockingQueue<String>> playerOutputQueues = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    //  game instance
    // game parameters
	private String answer;
    private final String host;
    private char[] revealed;
    private final List<Player> players = Collections.synchronizedList(new ArrayList<>());
    private int currentTurnIndex;
    private boolean isFinished;
    private Difficulty difficulty;
    private Timer turnTimer = new Timer();
    private Player currentTurnPlayer;

    private boolean isStart = false;

    public GameThread(String gameId, String host) {
        this.gameId = gameId;
        this.difficulty = Difficulty.MEDIUM;
        this.host = host;
    }

    public String[] getPlayers() {
        String[] playersList = new String[this.players.size()];
        for (int i = 0; i < this.players.size(); i++) {
            playersList[i] = this.players.get(i).getId();
        }
        return playersList;
    }

    public String getHost() {
        return this.host;
    }

    public String getGameId() {
        return this.gameId;
    }

    public int getPlayerCount() {
        return this.players.size();
    }

    public void setMaxPlayers(int maxPlayers) {
        this.MAX_PLAYERS = maxPlayers;
    }

    public int getMaxPlayers() {
        return this.MAX_PLAYERS;
    }

    public void putInput(String input) {
        String[] tokens = input.split("\\s+", 2);
        String playerId = tokens[0];
        String command = tokens.length > 1 ? tokens[1] : "";
        commandQueue.add(new PlayerCommand(playerId, command));
    }

    public synchronized void addPlayer(String playerId, BlockingQueue<String> outQueue) {
        // @@ check player 중복
        if (playerOutputQueues.containsKey(playerId)) {
            broadcast(playerId + "는 다른 게임에 합류했습니다.");
            return;
        }
        playerOutputQueues.put(playerId, outQueue);
        // add player
        players.add(new Player(playerId));
        broadcast(" [" + playerId + "] 님이 게임에 합류했습니다.");
    }

    public synchronized void removePlayer(String playerId) {
        playerOutputQueues.remove(playerId);
        // remove player
        players.removeIf(p -> p.getId().equals(playerId));
        broadcast(" [" + playerId + "] 님이 게임에서 나갔습니다.");
    }

    public synchronized boolean isEmpty() {
        return playerOutputQueues.isEmpty();
    }

    private void broadcast(String message) {
        for (BlockingQueue<String> outQ : playerOutputQueues.values()) {
            try {
                outQ.put("GAME_MESSAGE " + message);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean startGame(Difficulty difficulty) {
        if (players.size() < 2) {
            return false;
        }
    
        this.difficulty = difficulty;
        this.answer = WordGenerator.getRandomWordByDifficulty(difficulty).toUpperCase();
        this.revealed = new char[answer.length()];
        Arrays.fill(this.revealed, '_');
        this.currentTurnIndex = 0;
        this.isFinished = false;
        this.isStart = true;

        for (Player p : players) {
            p.addCoins(10);
        }

        Collections.shuffle(players);
        broadcast("게임이 시작됩니다! 정답은 " + revealed.length + "글자입니다.");
        nextTurn();
        return true;
    }

    private void broadcastStatus() {
        for (Player p : players) {
            StringBuilder sb = new StringBuilder("GAME_STATUS ");
            sb.append(new String(revealed != null ? revealed : new char[0]));
            sb.append(" | ")
              .append(p.getId())
              .append(":Life ").append(p.getLivesLeft())
              .append(":Coins ").append(p.getCoins())
              .append(":Score ").append(p.getScore());
            playerOutputQueues.get(p.getId()).add(sb.toString());
        }
    }

    // game logic
    public Player getPlayerById(String playerId) {
        for (Player player : players) {
            if (player.getId().equals(playerId)) {
                return player;
            }
        }
        return null;
    }

    public void handleGuess(Player player, char guess) {
        if (player != currentTurnPlayer) {
            broadcast(player.getId() + "는 현재 차례가 아닙니다.");
            return;
        }

        guess = Character.toUpperCase(guess);
        boolean correct = false;

        for (int i = 0; i < answer.length(); i++) {
            if (answer.charAt(i) == guess && revealed[i] == '_') {
                revealed[i] = guess;
                correct = true;
            }
        }

        if (correct) {
            player.incrementCorrectGuess();
            broadcast(player.getId() + "가 '" + guess + "'를 맞혔습니다!");
        } else {
            player.decreaseLife();
            broadcast(player.getId() + "가 '" + guess + "'를 틀렸습니다! 목숨 -1");
        }

        checkGameStatus(player);
        //broadcastStatus();
    }

    public void handleHint(Player player) {
        if (player != currentTurnPlayer) {
            broadcast(player.getId() + "는 현재 차례가 아닙니다.");
            return;
        }

        if (!player.useCoins(3)) {
            broadcast(player.getId() + "는 코인이 부족해 힌트를 사용할 수 없습니다.");
            return;
        }

        List<Integer> unrevealed = new ArrayList<>();
        for (int i = 0; i < revealed.length; i++) {
            if (revealed[i] == '_') unrevealed.add(i);
        }

        if (!unrevealed.isEmpty()) {
            int idx = unrevealed.get(new Random().nextInt(unrevealed.size()));
            revealed[idx] = answer.charAt(idx);
            broadcast(player.getId() + "가 힌트를 사용했습니다! '" + answer.charAt(idx) + "' 공개!");
        } else {
            broadcast("이미 모든 글자가 공개되어 힌트를 사용할 수 없습니다.");
        }

        checkGameStatus(player);
        //broadcastStatus();
    }

    public void handleAnswerAttempt(Player player, String guess) {
        if (player.getLivesLeft() < 2) {
            broadcast(player.getId() + "는 목숨이 부족해 정답 시도를 할 수 없습니다.");
            return;
        }

        player.decreaseLife();
        player.decreaseLife();

        if (guess.equalsIgnoreCase(answer)) {
            revealed = answer.toCharArray();
            broadcast(player.getId() + "가 정답을 맞혔습니다!");
            player.addScore(10);
            isFinished = true;
            running = false;
            rewardPlayers();
        } else {
            broadcast(player.getId() + "의 정답 시도 실패! 목숨 -2");
            checkGameStatus(null);
        }

        //broadcastStatus();
    }

    public void nextTurn() {
        turnTimer.cancel();
        if (isFinished) return;
        turnTimer = new Timer();

        currentTurnIndex = (currentTurnIndex + 1) % players.size();
        currentTurnPlayer = players.get(currentTurnIndex);

        if (!currentTurnPlayer.isAlive()) {
            nextTurn();
            return;
        }

        broadcast(currentTurnPlayer.getId() + "의 턴입니다. (15초 이내 입력하세요)");
        broadcastStatus();
        turnTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                broadcast(currentTurnPlayer.getId() + "의 시간이 초과되었습니다. 턴이 넘어갑니다.");
                nextTurn();
            }
        }, 15000);
    }

    private void checkGameStatus(Player lastCorrectPlayer) {
        if (new String(revealed).equals(answer)) {
            isFinished = true;
            turnTimer.cancel();
            broadcast("정답이 모두 공개되었습니다!");
            if (lastCorrectPlayer != null) {
                lastCorrectPlayer.addScore(10);
                //broadcast(lastCorrectPlayer.getId() + "가 게임을 승리했습니다!");
            }
            rewardPlayers();
            running = false;
        } else if (players.stream().noneMatch(Player::isAlive)) {
            isFinished = true;
            broadcast("모든 플레이어가 탈락했습니다. 게임 종료!");
            rewardPlayers();
            running = false;
        } else {
            nextTurn();
        }
    }

    private void rewardPlayers() {
        players.sort(Comparator.comparing(Player::getLivesLeft).reversed()
                               .thenComparing(Player::getCorrectGuessCount).reversed());

        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            int rank = i + 1;
            int coins = getReward(rank, difficulty);
            p.addCoins(coins);
            broadcast(p.getId() + "는 " + rank + "위로 " + coins + " 코인을 획득했습니다!");
        }
        broadcastStatus();
    }

    private int getReward(int rank, Difficulty diff) {
        switch (diff) {
            case EASY:
                return rank == 1 ? 1 : 0;
            case MEDIUM:
                if (rank == 1) return 3;
                else if (rank == 2) return 2;
                else if (rank == 3) return 1;
                else return 0;
            case HARD:
                if (rank == 1) return 5;
                else if (rank == 2) return 3;
                else if (rank == 3) return 2;
                else return 0;
            default:
                return 0;
        }
    }

    @Override
    public void run() {
            while (running) {
                PlayerCommand playerCommand;
                try {
                    playerCommand = commandQueue.take();
                } catch (InterruptedException e) {
                    break;
                }
                String playerId = playerCommand.playerId;
                String command = playerCommand.command;
                String tokens[] = command.split("\\s+");

                if (tokens[0].equals("GAME_START") && !this.isStart) {
                    //@@ set broad cast handler
                    
                    if (!playerId.equals(this.host)) {
                        broadcast("SYSTEM " + playerId + "는 게임을 시작하는 수 없습니다.");
                        continue;
                    }

                    if (tokens[1].equals("EASY")) difficulty = Difficulty.EASY;
                    else if (tokens[1].equals("MEDIUM")) difficulty = Difficulty.MEDIUM;
                    else if (tokens[1].equals("HARD")) difficulty = Difficulty.HARD;

                    if (!startGame(difficulty)) {
                        broadcast("SYSTEM 최소 2명이 필요합니다.");
                    };
                    continue;
                }

                if (this.isStart) {
                    Player p = getPlayerById(playerId);
                    if (p == null) continue;

                    String token[] = command.split("\\s+"); 
                    switch (token[0]) {
                        case "GAME_GUESS"  -> handleGuess(p, token[1].charAt(0));
                        case "GAME_HINT"   -> handleHint(p);
                        case "GAME_ANSWER"->  handleAnswerAttempt(p, token[1]);
                        default            -> broadcast("CHAT " + p.getId() + ": " + command);
                    }
                }
            }
            System.out.println("Game " + gameId + " is over.");
            broadcast("GAME_END");
            shutdown();
            //GameManager.removeGame(gameId);
    }

    public void shutdown() {
        this.interrupt();
    }

    private static class PlayerCommand {
        final String playerId;
        final String command;

        public PlayerCommand(String playerId, String command) {
            this.playerId = playerId;
            this.command = command;
        }
    }
}

class GameManager {
    private final static Map<String, GameThread> gameMaps = new ConcurrentHashMap<>();

    public static synchronized GameThread getGameThread(String gameID, String host) {
        GameThread game = gameMaps.get(gameID);
        if (game == null) {
            game = new GameThread(gameID, host);
            gameMaps.put(gameID, game);
            game.start();
        }
        return game;
    }

    public static synchronized void removeGame(String gameID) {
        GameThread game = gameMaps.remove(gameID);
        if (game != null) {
            game.shutdown();
        }
    }

    public static synchronized Map<String, GameThread> getGameThreads() {
        return gameMaps;
    }

    public static boolean exists(String gameId) {
        return gameMaps.containsKey(gameId);
    }
}