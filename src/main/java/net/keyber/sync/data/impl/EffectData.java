package net.keyber.sync.data.impl;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

public record EffectData(String type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {

    @Nullable
    public static EffectData of(PotionEffect effect) {
        if (effect == null) {
            return null;
        }

        return new EffectData(
                effect.getType().getKey().toString(),
                effect.getDuration(),
                effect.getAmplifier(),
                effect.isAmbient(),
                effect.hasParticles(),
                effect.hasIcon());
    }

    @Nullable
    public PotionEffect toPotionEffect() {
        NamespacedKey key = NamespacedKey.fromString(type);
        if (key == null) {
            return null;
        }

        PotionEffectType effectType = Registry.MOB_EFFECT.get(key);
        if (effectType == null) {
            return null;
        }

        return new PotionEffect(effectType, duration, amplifier, ambient, particles, icon);
    }
}
