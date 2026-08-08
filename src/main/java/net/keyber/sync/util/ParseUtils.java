package net.keyber.sync.util;

import lombok.experimental.UtilityClass;
import org.bson.Document;
import org.bson.types.Binary;

@UtilityClass
public class ParseUtils {
    public Number numberOf(Document document, String key) {
        Object value = document.get(key);

        return value instanceof Number number ? number : null;
    }

    public int intOf(Document document, String key, int fallback) {
        Number number = numberOf(document, key);

        return number == null ? fallback : number.intValue();
    }

    public long longOf(Document document, String key, long fallback) {
        Number number = numberOf(document, key);

        return number == null ? fallback : number.longValue();
    }

    public double doubleOf(Document document, String key, double fallback) {
        Number number = numberOf(document, key);

        return number == null ? fallback : number.doubleValue();
    }

    public float floatOf(Document document, String key, float fallback) {
        Number number = numberOf(document, key);

        return number == null ? fallback : number.floatValue();
    }

    public byte[] binaryOf(Object value) {
        if (value instanceof Binary binary) {
            return binary.getData();
        }

        return value instanceof byte[] bytes ? bytes : null;
    }
}
