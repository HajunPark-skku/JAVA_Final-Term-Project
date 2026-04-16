import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    private static ServerSocket ss = null;

    private static final ArrayList<Thread> threadList = new ArrayList<>();
    public static final Map<String, String> loginDB = Collections.synchronizedMap(new HashMap<>());
    public static final Map<String, String> isLoginDB = Collections.synchronizedMap(new HashMap<>());
    public static final Map<String, String> nameDB = Collections.synchronizedMap(new HashMap<>());

    public static void main(String[] args) throws IOException {
        int id = 0;
        try {
            ss = new ServerSocket(5000);
        } catch (IOException e) {
            e.printStackTrace();
        }

        while (true) {
            try {
                Socket soc = ss.accept();
                Thread t = new ServerThread(soc);
                t.start();
                threadList.add(t);

                Iterator<Thread> iter = threadList.iterator();
                while (iter.hasNext()) {
                    Thread t1 = iter.next();
                    if (!t1.isAlive()) {
                        iter.remove();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

class ServerThread extends Thread {
    private Socket soc = null;
    private BufferedReader in = null;
    private PrintWriter out = null;

    private String userID = null;
    private String gameID = null;
    private boolean isLogin = false;

    // communicate with game thread
    private final BlockingQueue<String> ownOutputQueue = new LinkedBlockingQueue<>();

    public ServerThread(Socket soc) throws IOException {
        this.soc = soc;
        this.isLogin = false;
        this.in = new BufferedReader(new InputStreamReader(soc.getInputStream()));
        this.out = new PrintWriter(soc.getOutputStream(), true);
        System.out.println("Client Connected");
    }

    @Override
    public void run() {
        Thread clientHandler = new Thread(()->{
            try {
                // run
                String line;
                while((line = in.readLine()) != null) {
                    String[] token = line.split("\\s+");
                    String command = token[0];
                    String[] params = Arrays.copyOfRange(token, 1, token.length);

                    // game logic
                    if (command.startsWith("GAME_")) {
                        if (!isLogin || gameID == null) {
                            out.println("GAME_ACCESS_FAIL");
                        } else {
                            GameThread game = GameManager.getGameThread(gameID, "");
                            game.putInput(this.userID + " " + line);
                        }
                        continue;
                    }

                    switch (command) {
                        case "LOGIN":
                            // check user name
                            if (Server.loginDB.containsKey(params[0]) && Server.loginDB.get(params[0]).equals(params[1])) {
                                // check password
                                if(Server.isLoginDB.get(params[0]).equals("true")) {
                                    out.println("LOGIN_FAIL_ALREADY_LOGIN");
                                    break;
                                }
                                String name = Server.nameDB.get(params[0]);
                                this.userID = name;
                                Server.isLoginDB.put(params[0], "true");
                                out.println("LOGIN_SUCCESS" + " " + name);
                                this.isLogin = true;
                            } else {
                                out.println("LOGIN_FAIL");
                            }
                            break;
                        case "ROOMLIST":
                            Map<String, GameThread> rooms = GameManager.getGameThreads();
                            String roomList = "";
                            roomList += (rooms.size() + " ");
                            for (GameThread game : rooms.values()) {
                                roomList += game.getGameId() + " " + game.getPlayerCount() + " " + game.getMaxPlayers() + " ";
                            }
                            out.println("ROOMLIST_SUCCESS" + " " + roomList);
                            break;
                        case "SIGNUP":
                            // check user name
                            if (!Server.loginDB.containsKey(params[0])) {
                                Server.loginDB.put(params[0], params[1]);
                                Server.nameDB.put(params[0], params[2]);
                                Server.isLoginDB.put(params[0], "false");
                                out.println("SIGNUP_SUCCESS");
                            } else {
                                out.println("SIGNUP_FAIL");
                            }
                            break;
                        case "LOGOUT":
                            if (this.isLogin) {
                                out.println("LOGOUT_SUCCESS");
                                Server.isLoginDB.put(this.userID, "false");
                                this.isLogin = false;
                            } else {
                                out.println("LOGOUT_FAIL");
                            }
                            break;
                        case "CREATE_GAME":
                            if (!isLogin) {
                                out.println("CREATE_GAME_FAIL_NOT_LOGGED_IN");
                            } else if (GameManager.exists(params[0])) {
                                out.println("CREATE_GAME_FAIL_ALREADY_EXISTS");
                            } else {
                                // GameManager가 없으므로 새 GameThread 생성
                                GameThread game = GameManager.getGameThread(params[0], this.userID);
                                game.setMaxPlayers(Integer.parseInt(params[1]));
                                game.addPlayer(this.userID, ownOutputQueue);
                                this.gameID = params[0];
                                out.println("CREATE_GAME_SUCCESS " + this.gameID);
                            }
                            break;
                        case "JOIN_GAME":
                            if (!isLogin) {
                                out.println("JOIN_GAME_FAIL_NOT_LOGGED_IN");
                            } else if (!GameManager.exists(params[0])) {
                                out.println("JOIN_GAME_FAIL_NOT_EXISTS");
                            } else {
                                GameThread game = GameManager.getGameThread(params[0], "");
                                if (game.getMaxPlayers() <= game.getPlayerCount()) {
                                    out.println("JOIN_GAME_FAIL_FULL");
                                    break;
                                }
                                game.addPlayer(this.userID, ownOutputQueue);
                                this.gameID = params[0];
                                String[] players = game.getPlayers();
                                String resp = "";
                                resp += ("JOIN_GAME_SUCCESS " + this.gameID + " " + game.getHost() + " " + game.getMaxPlayers() + " ");
                                for (int i = 0; i < players.length; i++) {
                                    resp += players[i] + ",";
                                }
                                out.println(resp);
                            }
                            break;
                        case "ROOM_INFO":
                            if (!isLogin) {
                                out.println("ROOM_INFO_FAIL_NOT_LOGGED_IN");
                            } else if (this.gameID == null) {
                                out.println("ROOM_INFO_FAIL_NOT_IN_ROOM");
                            } else {
                                GameThread game = GameManager.getGameThread(this.gameID, "");
                                String[] players = game.getPlayers();
                                String resp = "";
                                resp += ("ROOM_INFO_SUCCESS " + this.gameID + " " + game.getHost() + " " + game.getMaxPlayers() + " ");
                                for (int i = 0; i < players.length; i++) {
                                    resp += players[i] + ",";
                                }
                                out.println(resp);
                            }
                            break;
                        case "LEAVE_GAME":
                            if (this.gameID == null) {
                                out.println("LEAVE_GAME_FAIL_NOT_IN_ROOM");
                            } else {
                                String curGameID = this.gameID;
                                GameThread game = GameManager.getGameThread(curGameID, "");
                                game.removePlayer(this.userID);
                                this.gameID = null;
                                out.println("LEAVE_GAME_SUCCESS " + curGameID);
                                if (game.isEmpty() || game.getHost().equals(this.userID)) {
                                    GameManager.removeGame(curGameID);
                                }
                            }
                            break;
                        default:
                            out.println("ERROR");
                        }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    soc.close();
                    this.isLogin = false;
                    if (this.userID != null) Server.isLoginDB.put(this.userID, "false");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        clientHandler.start();

        try {
            while(true) {
                String msgFromGame = ownOutputQueue.take();
                if (msgFromGame.equals("GAME_MESSAGE GAME_END")) {
                    if (this.gameID != null) {
                        GameThread game = GameManager.getGameThread(this.gameID, "");
                        String curGameID = this.gameID;
                        game.removePlayer(this.userID);
                        this.gameID = null;
                        if (game.isEmpty()) {
                            GameManager.removeGame(curGameID);
                        }
                    }
                }
                out.println(msgFromGame);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        try {
            if (soc != null) soc.close();
        } catch (IOException e) {
            System.out.println("cleanup error");
            e.printStackTrace();
        }
    }
}
