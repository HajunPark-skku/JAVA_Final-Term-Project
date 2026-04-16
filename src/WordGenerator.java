
import java.util.*;

public class WordGenerator {
    private static final List<String> EASY_WORDS = List.of("dog", "cat", "sun", "pen", "egg");
    private static final List<String> MEDIUM_WORDS = List.of("banana", "monkey", "orange", "guitar", "window");
    private static final List<String> HARD_WORDS = List.of("architecture", "university", "television", "revolution", "development");

    public static String getRandomWordByDifficulty(Difficulty diff) {
        List<String> pool;
        switch (diff) {
            case EASY -> pool = EASY_WORDS;
            case MEDIUM -> pool = MEDIUM_WORDS;
            case HARD -> pool = HARD_WORDS;
            default -> pool = EASY_WORDS;
        }
        return pool.get(new Random().nextInt(pool.size()));
    }
}
