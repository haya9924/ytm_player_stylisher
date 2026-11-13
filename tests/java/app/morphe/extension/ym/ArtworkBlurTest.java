package app.morphe.extension.ym;

import java.util.Arrays;

public final class ArtworkBlurTest {
    public static void main(String[] args) {
        int[] uniform = new int[32 * 32];
        Arrays.fill(uniform, 0xff123456);
        int[] result = ArtworkBlur.blur(uniform, 32, 4, 3);
        if (!Arrays.equals(uniform, result) || result == uniform) throw new AssertionError("uniform or alias");
        int[] pattern = new int[32 * 32];
        Arrays.fill(pattern, 0xff000000);
        for (int y = 13; y <= 19; y++) {
            for (int x = 13; x <= 19; x++) pattern[y * 32 + x] = 0xffffffff;
        }
        result = ArtworkBlur.blur(pattern, 32, 4, 3);
        if (result[16 * 32 + 16] == 0xffffffff || (result[16 * 32 + 10] & 255) == 0) {
            throw new AssertionError("blur must spread the bright point");
        }
        if (pattern[16 * 32 + 16] != 0xffffffff) throw new AssertionError("input was mutated");
        try {
            ArtworkBlur.blur(new int[2], 32, 4, 3);
            throw new AssertionError("invalid size accepted");
        } catch (IllegalArgumentException expected) { /* expected */ }
        System.out.println("ArtworkBlur tests passed");
    }
}
