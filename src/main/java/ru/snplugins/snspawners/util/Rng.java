package ru.snplugins.snspawners.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Статистический сэмплер лута — сердце оптимизации плагина.
 *
 * <p>Наивная реализация стакающихся спавнеров бросает кубик на каждого моба:
 * стак 2048 × 4 моба за цикл × 180 циклов простоя = 1.5 миллиона бросков
 * на один спавнер при загрузке чанка. Это и есть тот самый фриз, за который
 * ругают стакеры.
 *
 * <p>Здесь вместо перебора берётся выборка из распределения целиком:
 * <ul>
 *   <li>сколько раз выпал предмет — биномиальное {@code B(n, p)};</li>
 *   <li>сколько всего штук — сумма {@code n} равномерных величин.</li>
 * </ul>
 *
 * <p>Пока попыток мало, считается честно перебором — результат
 * математически неотличим от ванильного. Выше порога включается
 * аппроксимация: нормальная при большом среднем, пуассоновская при малом.
 * Относительное отклонение при {@code n > 64} не превышает долей процента
 * и полностью тонет в дисперсии самого распределения.
 */
public final class Rng {

    private Rng() {
    }

    /** Среднее, ниже которого нормальная аппроксимация уже неточна. */
    private static final double POISSON_LIMIT = 12.0d;

    /**
     * Число успехов в {@code trials} испытаниях с вероятностью {@code p}.
     *
     * @param exactThreshold до скольких испытаний считать честным перебором
     */
    public static long binomial(long trials, double p, int exactThreshold) {
        if (trials <= 0L || p <= 0.0d) {
            return 0L;
        }
        if (p >= 1.0d) {
            return trials;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (trials <= exactThreshold) {
            long hits = 0L;
            for (long i = 0; i < trials; i++) {
                if (random.nextDouble() < p) {
                    hits++;
                }
            }
            return hits;
        }

        double mean = trials * p;

        if (mean < POISSON_LIMIT) {
            // Редкое событие на большом числе испытаний — предел Пуассона.
            return Math.min(poisson(mean, random), trials);
        }

        double deviation = Math.sqrt(mean * (1.0d - p));
        long sampled = Math.round(mean + deviation * random.nextGaussian());
        return clamp(sampled, 0L, trials);
    }

    /**
     * Сумма {@code draws} независимых целых из отрезка {@code [min, max]}.
     * Именно так набирается «сколько всего штук выпало».
     */
    public static long sumUniform(long draws, int min, int max, int exactThreshold) {
        if (draws <= 0L || max < min) {
            return 0L;
        }
        if (min == max) {
            return saturatedMultiply(draws, min);
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (draws <= exactThreshold) {
            long total = 0L;
            for (long i = 0; i < draws; i++) {
                total += random.nextInt(min, max + 1);
            }
            return total;
        }

        int span = max - min + 1;
        double mean = draws * (min + max) / 2.0d;
        // Дисперсия дискретного равномерного распределения: (span² − 1) / 12.
        double deviation = Math.sqrt(draws * (span * (double) span - 1.0d) / 12.0d);
        long sampled = Math.round(mean + deviation * random.nextGaussian());
        return clamp(sampled, saturatedMultiply(draws, min), saturatedMultiply(draws, max));
    }

    /**
     * Пуассоновская выборка методом Кнута. Вызывается только при малом
     * среднем, поэтому цикл короткий и {@code exp(-mean)} не вырождается.
     */
    private static long poisson(double mean, ThreadLocalRandom random) {
        double limit = Math.exp(-mean);
        double product = random.nextDouble();
        long count = 0L;
        while (product > limit) {
            count++;
            product *= random.nextDouble();
            if (count > 1_000L) {
                // Страховка от зацикливания на патологическом входе.
                return count;
            }
        }
        return count;
    }

    private static long saturatedMultiply(long a, long b) {
        long result = a * b;
        if (a != 0 && (result / a != b || (a == -1 && b == Long.MIN_VALUE))) {
            return b >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return result;
    }

    private static long clamp(long value, long min, long max) {
        return value < min ? min : Math.min(value, max);
    }
}
