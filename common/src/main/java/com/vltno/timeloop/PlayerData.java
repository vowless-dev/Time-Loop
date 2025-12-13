package com.vltno.timeloop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.phys.Vec3;

public class PlayerData {
    private final String name;
    private String nickname;
    private String skin;
    private Vec3 startPosition;
    private Vec3 joinPosition;
    private String inventoryTag;
    
    public PlayerData(String name, String nickname, String skin, Vec3 joinPosition, CompoundTag inventoryTag) {
        this.name = name;
        this.nickname = nickname;
        this.skin = skin;
        this.startPosition = null;
        this.joinPosition = joinPosition;
        this.setInventoryTag(inventoryTag);
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
            Tag tag = TagParser.parseTag(this.inventoryTag);
            if (tag instanceof CompoundTag compoundTag) {
                return compoundTag;
            }
        } catch (Exception e) {
            TimeLoop.LOOP_LOGGER.error("Failed to parse inventoryTagNbt: " + inventoryTag, e);
        }
        return new CompoundTag();
    }

    @Override
    public String toString() {
        return "PlayerData{" +
                "name='" + name + '\'' +
                ", nickname='" + nickname + '\'' +
                ", skin='" + skin + '\'' +
                ", join-position='" + joinPosition + '\'' +
                ", start-position='" + startPosition + '\'' +
                ", inventory-tag='" + inventoryTag + '\'' +
                '}';
    }
}
