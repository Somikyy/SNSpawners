package ru.snplugins.snspawners.util;

/**
 * Мелкая замена плейсхолдеров без регулярок и без {@code Map}.
 *
 * <p>Плейсхолдеров в одном сообщении единицы, поэтому линейный проход по
 * плоскому массиву быстрее и не мусорит объектами. Экземпляр одноразовый:
 * создаётся, наполняется, применяется, выбрасывается.
 */
public final class Replacer {

    private String[] pairs;
    private int size;

    private Replacer(int capacity) {
        this.pairs = new String[Math.max(4, capacity) * 2];
    }

    public static Replacer of() {
        return new Replacer(4);
    }

    public static Replacer of(String key, Object value) {
        return new Replacer(4).with(key, value);
    }

    public Replacer with(String key, Object value) {
        if (size * 2 == pairs.length) {
            String[] grown = new String[pairs.length * 2];
            System.arraycopy(pairs, 0, grown, 0, pairs.length);
            pairs = grown;
        }
        pairs[size * 2] = key;
        pairs[size * 2 + 1] = String.valueOf(value);
        size++;
        return this;
    }

    public String apply(String input) {
        if (input == null || input.isEmpty() || size == 0 || input.indexOf('%') < 0) {
            return input;
        }
        String result = input;
        for (int i = 0; i < size; i++) {
            String key = pairs[i * 2];
            if (result.contains(key)) {
                result = result.replace(key, pairs[i * 2 + 1]);
            }
        }
        return result;
    }
}
