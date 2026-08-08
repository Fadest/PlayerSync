package net.keyber.sync.service;

import net.keyber.sync.data.PlayerSnapshot;

public record PlayerSession(State state, PlayerSnapshot snapshot) {

    public enum State {
        PENDING_LOGIN,
        MANAGED,
        TRANSFERRED
    }

    public static PlayerSession pending(PlayerSnapshot snapshot) {
        return new PlayerSession(State.PENDING_LOGIN, snapshot);
    }

    public boolean is(State other) {
        return state == other;
    }

    public PlayerSession withState(State newState) {
        return new PlayerSession(newState, snapshot);
    }

    public PlayerSession withSnapshot(PlayerSnapshot newSnapshot) {
        return new PlayerSession(state, newSnapshot);
    }
}
