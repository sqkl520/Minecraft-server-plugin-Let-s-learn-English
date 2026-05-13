package com.letslearnenglish.minecraftplugin.core.dialogue;

/**
 * NPC - represents a non-player character in a dialogue scene.
 */
public class NPC {

    private final String name;
    private final String role;
    private final String greeting;

    public NPC(String name, String role, String greeting) {
        this.name = name;
        this.role = role;
        this.greeting = greeting;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public String getGreeting() {
        return greeting;
    }
}