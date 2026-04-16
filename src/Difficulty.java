
public enum Difficulty {
	EASY("하", 1),
    MEDIUM("중", 3),
    HARD("상", 5);

    private final String label;
    private final int baseReward;

    Difficulty(String label, int baseReward) {
        this.label = label;
        this.baseReward = baseReward;
    }

    public String getLabel() {
        return label;
    }

    public int getBaseReward() {
        return baseReward;
    }
}
