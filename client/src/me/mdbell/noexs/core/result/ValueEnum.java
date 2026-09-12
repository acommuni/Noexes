package me.mdbell.noexs.core.result;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//Interface générique pour les énumérations avec une valeur
public interface ValueEnum<T extends Enum<T> & ValueEnum<T>> {
    public static final Logger logger = LogManager.getLogger(ValueEnum.class);

    int getValue();

    // Méthode statique pour retrouver l'énumération à partir de sa valeur
    static <T extends Enum<T> & ValueEnum<T>> T fromValue(Class<T> enumClass, int value) {
        T res = null;
        if (value > 0) {
            T[] array = enumClass.getEnumConstants();
            for (T enm : array) {
                if (enm.getValue() == value) {
                    res = enm;
                    break;
                }
            }
            if (res == null) {
                logger.info("Value : {} not found for class :{} ", value, enumClass);
            }
        }

        return res;
    }
}
