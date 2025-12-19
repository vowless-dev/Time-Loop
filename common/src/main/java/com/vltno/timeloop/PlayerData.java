package com.vltno.timeloop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

public class PlayerData {
    private final String name;
    private String nickname;
    private String skin;
    private Vec3 startPosition;
    private Vec3 joinPosition;
    private String inventoryTag;
    private ResourceKey<Level> lastDimensionKey;
    private int activeRecordingIndex;
    private int activeSubsceneIndex;
    private ArrayList<Float> tempOffsets;
    
    public PlayerData(String name, String nickname, String skin, Vec3 joinPosition, CompoundTag inventoryTag) {
        this.name = name;
        this.nickname = nickname;
        this.skin = skin;
        this.startPosition = null;
        this.joinPosition = joinPosition;
        this.setInventoryTag(inventoryTag);
        this.lastDimensionKey = null;
        this.activeRecordingIndex = 1;
        this.activeSubsceneIndex = 0;
        this.tempOffsets.add(0.0f);
    }

    // Getters and setters for player attributes
    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getSkin() {
        return skin;
    }

    public void setSkin(String skin) {
        this.skin = skin;
    }

    public Vec3 getStartPosition() {
        return startPosition;
    }

    public void setStartPosition(Vec3 newStartPosition) {
        this.startPosition = newStartPosition;
    }

    public Vec3 getJoinPosition() {
        return joinPosition;
    }
    
    public void setJoinPosition(Vec3 newJoinPosition) {
        this.joinPosition = newJoinPosition;
    }

    public void setInventoryTag(CompoundTag inventoryTag) {
        this.inventoryTag = inventoryTag.toString();
    }

    public CompoundTag getInventoryTag() {
        if (inventoryTag == null || inventoryTag.isEmpty()) return new CompoundTag();
        try {
            Tag tag = TagParser.parseCompoundFully(this.inventoryTag);
            if (tag instanceof CompoundTag compoundTag) {
                return compoundTag;
            }
        } catch (Exception e) {
            TimeLoop.LOOP_LOGGER.error("Failed to parse inventoryTagNbt: " + inventoryTag, e);
        }
        return new CompoundTag();
    }

    public ResourceKey<Level> getLastDimensionKey() {
        return lastDimensionKey;
    }

    public void setLastDimensionKey(ResourceKey<Level> lastDimensionKey) {
        this.lastDimensionKey = lastDimensionKey;
    }

    public int getActiveRecordingIndex() {
        return activeRecordingIndex;
    }

    public void incrementActiveRecordingIndex() {
        this.activeRecordingIndex++;
    }

    public void resetActiveRecordingIndex() {
        this.activeRecordingIndex = 1;
    }

    public int getActiveSubsceneIndex() {
        return activeSubsceneIndex;
    }

    public void incrementActiveSubsceneIndex() {
        this.activeSubsceneIndex++;
    }

    public void resetActiveSubsceneIndex() {
        this.activeSubsceneIndex = 1;
    }

    public void addTempOffset(Float value) {
        this.tempOffsets.add(value);
    }

    public void resetTempOffsets() {
        this.tempOffsets.clear();
        this.tempOffsets.add(0.0f);
    }
}
