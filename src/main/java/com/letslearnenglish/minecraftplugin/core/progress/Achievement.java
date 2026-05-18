package com.letslearnenglish.minecraftplugin.core.progress;

/**
 * Achievement - represents an unlockable achievement.
 */
public class Achievement {

    private final String id;
    private final String name;
    private final String description;
    private final String category;
    private final int targetValue;

    public Achievement(String id, String name, String description, String category, int targetValue) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.targetValue = targetValue;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public int getTargetValue() {
        return targetValue;
    }
}