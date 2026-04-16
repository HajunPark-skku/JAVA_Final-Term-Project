
public class Player {
    private final String id;
    private int lives;
    private int coins;
    private int score;
    private int correctGuessCount;
    private boolean shieldBoost;

    public Player(String id) {
        this.id = id;
        this.lives = 6;        // 기본 목숨 6회
        this.coins = 0;
        this.score = 0;
        this.correctGuessCount = 0;
        this.shieldBoost = false;
    }

    public String getId() {
        return id;
    }

    public int getLivesLeft() {
        return lives;
    }

    public boolean isAlive() {
        return lives > 0;
    }

    public void decreaseLife() {
        if (lives > 0) lives--;
    }

    public void addCoins(int amount) {
        this.coins += amount;
    }

    public int getCoins() {
        return coins;
    }

    public boolean useCoins(int amount) {
        if (this.coins >= amount) {
            this.coins -= amount;
            return true;
        }
        return false;
    }

    public void addScore(int amount) {
        this.score += amount;
    }

    public int getScore() {
        return score;
    }

    public int getCorrectGuessCount() {
        return correctGuessCount;
    }

    public void incrementCorrectGuess() {
        correctGuessCount++;
    }

    // 부스트 관련
    public void activateShieldBoost() {
        if (useCoins(5)) {
            shieldBoost = true;
        }
    }

    public boolean tryUseShield() {
        if (shieldBoost) {
            shieldBoost = false;
            return true;
        }
        return false;
    }

    // 정답 시도 시 목숨 2개 필요
    public boolean tryUseAnswer() {
        return lives >= 2;
    }

    public String getStatus() {
        return String.format("%s | 목숨: %d | 코인: %d | 점수: %d", id, lives, coins, score);
    }
}

