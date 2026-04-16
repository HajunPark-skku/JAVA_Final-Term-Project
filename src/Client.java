import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Client {
    private static final String SERVER_HOST = "localhost";
    private static final int    SERVER_PORT = 5000;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    private String userName = null;
    private boolean isRunning = true;
    private final Scanner scanner = new Scanner(System.in);
    private Thread readerThread;

    private String gameID = null;

    // 서버 응답 대기용 큐
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    public static void main(String[] args) {
        Client client = new Client();
        try {
            client.connect();
            client.commandLoop();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            client.shutdown();
            client.closeAll();
        }
    }

    private void shutdown() {
        System.out.println("종료 중...");
        isRunning = false;
        try {
            if (this.gameID != null) {
                out.println("LEAVE_GAME");
                responseQueue.poll(500, TimeUnit.MILLISECONDS);
            }
            out.println("LOGOUT");
            responseQueue.poll(500, TimeUnit.MILLISECONDS);

            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 4) 리더 쓰레드 인터럽트
            if (this.readerThread != null && this.readerThread.isAlive()) {
                this.readerThread.interrupt();
            }
            System.out.println("셧다운 완료...");
        }
    }

    private void connect() throws IOException {
        socket = new Socket(SERVER_HOST, SERVER_PORT);
        out    = new PrintWriter(socket.getOutputStream(), true);
        in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        System.out.println("서버에 연결되었습니다: " + SERVER_HOST + ":" + SERVER_PORT);

        // 서버 메시지 리더 스레드
        this.readerThread = new Thread(this::readMessages);
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    private void commandLoop() {
        printHelp();
        while (isRunning) {
            System.out.print("> ");
            String line;
            try {
                if (!scanner.hasNextLine()) break;
                line = scanner.nextLine().trim(); 
            } catch (NoSuchElementException e) {
                break;
            }

            if (line.isEmpty()) continue;

            String[] toks = line.split("\\s+");
            String cmd = toks[0].toUpperCase();

            try {
                switch (cmd) {
                    case "LOGIN":   // LOGIN ID PW
                        if (this.gameID != null) { System.out.println("You are already in a game."); break; }
                        if (toks.length != 3) { System.out.println("Usage: LOGIN <ID> <PW>"); break; }
                        sendCommand(line);
                        handleLogin();
                        break;

                    case "SIGNUP":  // SIGNUP ID PW NAME
                        if (this.gameID != null) { System.out.println("You are already in a game."); break; }
                        if (toks.length != 4) { System.out.println("Usage: SIGNUP <ID> <PW> <NAME>"); break; }
                        sendCommand(line);
                        handleSignup();
                        break;

                    case "LOGOUT":
                        if (this.gameID != null) { System.out.println("You are already in a game."); break; }
                        sendCommand("LOGOUT");
                        handleGenericResponse("LOGOUT_SUCCESS", "Logged out.", "Logout failed.");
                        userName = null;
                        break;

                    case "ROOMLIST":
                        if (this.gameID != null) { System.out.println("You are already in a game."); break; }
                        sendCommand("ROOMLIST");
                        handleRoomList();
                        break;

                    case "CREATE_GAME":  // CREATE_GAME ID MAX
                        if (this.gameID != null) { System.out.println("You are already in a game."); break; }
                        if (toks.length != 3) { System.out.println("Usage: CREATE_GAME <GameID> <MaxPlayers>"); break; }
                        sendCommand(line);
                        handleCreateGame();
                        break;

                    case "JOIN_GAME":    // JOIN_GAME ID
                        if (this.gameID != null) { System.out.println("You are already in a game."); break; }
                        if (toks.length != 2) { System.out.println("Usage: JOIN_GAME <GameID>"); break; }
                        sendCommand(line);
                        handleJoinGame();
                        break;
                    
                    case "ROOM_INFO": // ROOM_INFO
                        if (this.gameID == null) { System.out.println("You are not in a game."); break; }
                        sendCommand("ROOM_INFO ");
                        handleRoomInfo();
                        break;

                    case "LEAVE_GAME":
                        if (this.gameID == null) { System.out.println("You are not in a game."); break; }
                        sendCommand("LEAVE_GAME " + (toks.length>1?toks[1]:""));
                        handleLeaveGame();
                        break;

                    case "GAME_START":
                    case "GAME_GUESS":
                    case "GAME_HINT":
                    case "GAME_ANSWER":
                    case "CHAT":
                        if (this.gameID == null) { System.out.println("You are not in a game."); break; }
                        sendCommand(line);
                        break;

                    case "EXIT":
                        shutdown();
                        closeAll();
                        break;

                    default:
                        System.out.println("알 수 없는 명령: " + cmd);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void printHelp() {
        System.out.println("명령어:");
        System.out.println("  SIGNUP <ID> <PW> <NAME>");
        System.out.println("  LOGIN <ID> <PW>");
        System.out.println("  LOGOUT");
        System.out.println("  ROOMLIST");
        System.out.println("  CREATE_GAME <GameID> <MaxPlayers>");
        System.out.println("  JOIN_GAME <GameID>");
        System.out.println("  LEAVE_GAME <GameID>");
        System.out.println("  GAME_START (Only host)");
        System.out.println("  GAME_GUESS <letter>");
        System.out.println("  GAME_HINT");
        System.out.println("  GAME_ANSWER <word>");
        System.out.println("  CHAT <message>");
        System.out.println("  EXIT");
    }

    private void sendCommand(String cmd) {
        out.println(cmd);
    }

    private void readMessages() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                // 게임 메시지와 채팅은 바로 콘솔에 출력
                if (line.startsWith("GAME_") || line.startsWith("CHAT") || line.startsWith("GAME_MESSAGE") || line.startsWith("SYSTEM")) {
                    System.out.println(line);

                    if (line.equals("GAME_MESSAGE GAME_END")) {
                        // 서버 응답 소비
                        this.gameID = null;
                        System.out.println("게임이 종료되었습니다. 메인 메뉴로 돌아갑니다.");
                    }

                    System.out.print("> ");
                    System.out.flush();
                } else {
                    responseQueue.put(line);
                }
            }
        } catch (IOException | InterruptedException e) {
            // 연결 종료 or 인터럽트
        }
    }

    private void handleRoomInfo() throws InterruptedException {
        String resp = responseQueue.take();
        if (resp.startsWith("ROOM_INFO_SUCCESS")) {
            String[] p = resp.split("\\s+", 5);
            System.out.printf("Game Info: name=%s (host=%s, max=%s)%n", p[1], p[2], p[3]);
            System.out.println("Players: " + p[4].replace(",", ", "));
        }
        responseQueue.clear();
    }

    private void handleLogin() throws InterruptedException {
        String resp = responseQueue.take();
        if (resp.startsWith("LOGIN_SUCCESS")) {
            String[] p = resp.split("\\s+", 2);
            userName = p[1];
            System.out.println("로그인 성공! Welcome, " + userName);
        } else {
            System.out.println("로그인 실패: " + resp);
        }
        responseQueue.clear();
    }

    private void handleSignup() throws InterruptedException {
        String resp = responseQueue.take();
        if (resp.startsWith("SIGNUP_SUCCESS")) {
            System.out.println("회원가입 성공! 이제 로그인 해주세요.");
        } else {
            System.out.println("회원가입 실패: " + resp);
        }
        responseQueue.clear();
    }

    private void handleRoomList() throws InterruptedException {
        String resp = responseQueue.take();
        if (resp.startsWith("ROOMLIST_SUCCESS")) {
            String[] parts = resp.split("\\s+");
            int count = Integer.parseInt(parts[1]);
            System.out.println("총 방 수: " + count);
            int idx = 2;
            for (int i = 0; i < count; i++) {
                String id = parts[idx++];
                String cur = parts[idx++];
                String max = parts[idx++];
                System.out.printf("  %s (%s/%s)%n", id, cur, max);
            }
        } else {
            System.out.println("Room list failed: " + resp);
        }
        responseQueue.clear();
    }

    private void handleLeaveGame() throws InterruptedException {
        String resp = responseQueue.take();
        if (resp.startsWith("LEAVE_GAME_SUCCESS")) {
            System.out.println("Game left.");
            this.gameID = null;
        } else {
            System.out.println("Game leave failed: " + resp);
        }
        responseQueue.clear();
    }

    private void handleCreateGame() throws InterruptedException {
        String resp = responseQueue.take();
        if (resp.startsWith("CREATE_GAME_SUCCESS")) {
            String p[] = resp.split("\\s+", 2);
            System.out.println("Game created. " + p[1]);
            this.gameID = p[1];
        } else {
            System.out.println("Game creation failed: " + resp);
        }
        responseQueue.clear();
    }

    private void handleJoinGame() throws InterruptedException {
        String resp = responseQueue.take();
        if (resp.startsWith("JOIN_GAME_SUCCESS")) {
            // e.g. "JOIN_GAME_SUCCESS gameId host max p1,p2"
            String[] p = resp.split("\\s+", 5);
            System.out.printf("Joined %s (host=%s, max=%s)%n", p[1], p[2], p[3]);
            System.out.println("Players: " + p[4].replace(",", ", "));
            this.gameID = p[1];
        } else {
            System.out.println("Join failed: " + resp);
        }
        responseQueue.clear();
    }

    private void handleGenericResponse(String successKey, String successMsg, String failMsg) throws InterruptedException {
        String resp = responseQueue.take();
        if (resp.startsWith(successKey)) {
            System.out.println(successMsg);
        } else {
            System.out.println(failMsg + ": " + resp);
        }
    }

    public void closeAll() {
        try {
            if (out    != null) out.close();
            if (in     != null) in.close();
            if (socket != null) socket.close();
            System.out.println("자원 정리 완료.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
