package net.keyber.sync.data.impl;

import java.util.List;

public record StateData(double health, double maxHealth, int foodLevel, float saturation, float exhaustion, int level,
                        float experience, int totalExperience, String gameMode, boolean allowFlight, boolean flying,
                        float walkSpeed, float flySpeed, int fireTicks, float fallDistance, List<EffectData> effects) {
}
