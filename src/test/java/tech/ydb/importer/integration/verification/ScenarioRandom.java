package tech.ydb.importer.integration.verification;

public final class ScenarioRandom {

    public static final long SALT_TIME        = 0x9E3779B97F4A7C15L;
    public static final long SALT_FK          = 0xBF58476D1CE4E5B9L;
    public static final long SALT_CATEGORICAL = 0x94D049BB133111EBL;
    public static final long SALT_TEXT        = 0xC4CEB9FE1A85EC53L;
    public static final long SALT_BOOL        = 0xFF51AFD7ED558CCDL;
    public static final long SALT_NULL_GATE   = 0x85EBCA77C2B2AE63L;

    private ScenarioRandom() {
    }

    public static long stableHash(long id) {
        long h = id;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    public static long stableHash(long id, long salt) {
        return stableHash(id ^ salt);
    }

    public static double uniform(long id, long salt) {
        long bits = stableHash(id, salt) & 0x3FFFFFFFL;
        return (bits + 1.0) / (1L << 30);
    }

    public static long zipfLikePick(long id, long n, double alpha, long salt) {
        if (n <= 1) {
            return 1L;
        }
        return 1L + (long) ((n - 1) * Math.pow(uniform(id, salt), alpha));
    }

    public static <T> T weighted(long id, T[] values, int[] weights, long salt) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int x = (int) Math.floorMod(stableHash(id, salt), total);
        int acc = 0;
        for (int i = 0; i < values.length; i++) {
            acc += weights[i];
            if (x < acc) {
                return values[i];
            }
        }
        return values[values.length - 1];
    }

    public static <T> T pickFromArray(long id, T[] dict, long salt) {
        int idx = (int) Math.floorMod(stableHash(id, salt), dict.length);
        return dict[idx];
    }
}
