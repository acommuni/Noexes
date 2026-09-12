package me.mdbell.noexs.core.result;

//Exemple d'énumération implémentant ValueEnum
public enum EModule implements ValueEnum<EModule> {
    Kernel(1), Libnx(345), HomebrewAbi(346), HomebrewLoader(347), LibnxNvidia(348), LibnxBinder(349);

    private final int value;

    EModule(int value) {
        this.value = value;
    }

    @Override
    public int getValue() {
        return value;
    }

    // Méthode statique pour retrouver l'énumération à partir de sa valeur
    public static EModule fromValue(int value) {
        return ValueEnum.fromValue(EModule.class, value);
    }
}