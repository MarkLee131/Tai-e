package pta.llm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class PromptCacheTest {
    @Test
    void roundTrips(@TempDir Path dir) {
        PromptCache c = new PromptCache(dir);
        assertTrue(c.get("gemini-2.0-flash", "p").isEmpty());
        c.put("gemini-2.0-flash", "p", "answer");
        assertEquals(Optional.of("answer"), c.get("gemini-2.0-flash", "p"));
    }
    @Test
    void keyIsStableAndModelSensitive() {
        assertEquals(PromptCache.key("m1", "p"), PromptCache.key("m1", "p"));
        assertNotEquals(PromptCache.key("m1", "p"), PromptCache.key("m2", "p"));
    }
    @Test
    void survivesNewInstance(@TempDir Path dir) {
        new PromptCache(dir).put("m", "p", "v");
        assertEquals(Optional.of("v"), new PromptCache(dir).get("m", "p"));
    }
}
