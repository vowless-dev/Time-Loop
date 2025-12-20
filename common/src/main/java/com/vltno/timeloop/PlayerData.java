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
    private Skin skin;
    private Vec3 startPosition;
    private Vec3 joinPosition;
    private String inventoryTag;
    private ResourceKey<Level> lastDimensionKey;
    private int activeRecordingIndex;
    private int activeSubsceneIndex;
    private ArrayList<Float> tempOffsets;
    private boolean active;

    public PlayerData(String name, String nickname, String skin, Vec3 joinPosition, CompoundTag inventoryTag) {
        this.name = name;
        this.nickname = nickname;
        this.skin = new Skin();
        this.skin.value = name;
        this.startPosition = null;
        this.joinPosition = joinPosition;
        this.setInventoryTag(inventoryTag);
        this.lastDimensionKey = null;
        this.activeRecordingIndex = 1;
        this.activeSubsceneIndex = 0;
        this.tempOffsets = new ArrayList<Float>();
        this.tempOffsets.add(0f);
        this.active = true;
    }

    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Skin getSkin() {
        return skin;
    }

    public void setSkin(Skin skin) {
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

    public void addTempOffset(float value) {
        this.tempOffsets.add(value);
    }

    public float getTempOffset(int index) {
        if (index == -1) {
            return tempOffsets.getLast();
        }
        return tempOffsets.get(index);
    }

    public void resetTempOffsets() {
        this.tempOffsets.clear();
    }

    public ArrayList<Float> getTempOffsets() {
        return tempOffsets;
    }

    public boolean getActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
