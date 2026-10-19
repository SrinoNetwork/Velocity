/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import com.velocitypowered.api.network.ProtocolVersion;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Srino: play packet ids the switch without loading screen reads from backends (to track what a
 * backend created on the client) and writes to the client. The proxy does not decode these
 * packets, so they are peeked from or written as raw bytes. Ids come from the ViaVersion packet
 * enums (ClientboundPackets1_21_2 .. 26_3); -1 means the packet
 * does not exist in that version.
 *
 * @param forgetLevelChunk     clientbound, long chunk pos
 * @param levelChunkWithLight  clientbound, int x, int z, ...
 * @param addEntity            clientbound, varint entity id, ...
 * @param addExperienceOrb     clientbound, varint entity id, ... (until 1.21.4)
 * @param removeEntities       clientbound, varint count, varint ids
 * @param updateMobEffect      clientbound, varint entity id, varint effect, ...
 * @param removeMobEffect      clientbound, varint entity id, varint effect
 * @param gameEvent            clientbound, ubyte event, float param
 * @param setChunkCacheRadius  clientbound, varint
 * @param setSimulationDistance clientbound, varint
 * @param entityEvent          clientbound, int entity id, byte event
 * @param containerClose       clientbound, varint container id
 * @param stopSound            clientbound, byte flags
 * @param trackedWaypoint      clientbound, varint operation, waypoint (from 1.21.6)
 * @param setCamera            clientbound, varint entity id
 */
public record SeamlessPacketIds(int forgetLevelChunk, int levelChunkWithLight, int addEntity,
    int addExperienceOrb, int removeEntities, int updateMobEffect, int removeMobEffect,
    int gameEvent, int setChunkCacheRadius, int setSimulationDistance, int entityEvent,
    int containerClose, int stopSound, int trackedWaypoint, int setCamera) {

  private static final SeamlessPacketIds V1_21_4 = new SeamlessPacketIds(0x22, 0x28, 0x01, 0x02,
      0x47, 0x7D, 0x48, 0x23, 0x59, 0x69, 0x1F, 0x12, 0x71, -1, 0x57);
  private static final SeamlessPacketIds V1_21_5 = new SeamlessPacketIds(0x21, 0x27, 0x01, -1,
      0x46, 0x7D, 0x47, 0x22, 0x58, 0x68, 0x1E, 0x11, 0x70, -1, 0x56);
  private static final SeamlessPacketIds V1_21_6 = new SeamlessPacketIds(0x21, 0x27, 0x01, -1,
      0x46, 0x7D, 0x47, 0x22, 0x58, 0x68, 0x1E, 0x11, 0x70, 0x83, 0x56);
  private static final SeamlessPacketIds V1_21_9 = new SeamlessPacketIds(0x25, 0x2C, 0x01, -1,
      0x4B, 0x82, 0x4C, 0x26, 0x5D, 0x6D, 0x22, 0x11, 0x75, 0x88, 0x5B);
  private static final SeamlessPacketIds V26_1 = new SeamlessPacketIds(0x25, 0x2D, 0x01, -1,
      0x4D, 0x84, 0x4E, 0x26, 0x5F, 0x6F, 0x22, 0x11, 0x77, 0x8A, 0x5D);
  private static final SeamlessPacketIds V26_3 = new SeamlessPacketIds(0x26, 0x2E, 0x01, -1,
      0x4E, 0x87, 0x4F, 0x27, 0x61, 0x71, 0x22, 0x11, 0x79, 0x8D, 0x5F);

  /**
   * Returns the ids for a protocol version, null when the version is not supported (older than
   * 1.21.4 or newer than 26.3).
   */
  public static @Nullable SeamlessPacketIds forVersion(ProtocolVersion version) {
    if (version.lessThan(ProtocolVersion.MINECRAFT_1_21_4)
        || version.greaterThan(ProtocolVersion.MINECRAFT_26_3)) {
      return null;
    }
    if (version.noLessThan(ProtocolVersion.MINECRAFT_26_3)) {
      return V26_3;
    }
    if (version.noLessThan(ProtocolVersion.MINECRAFT_26_1)) {
      return V26_1;
    }
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_21_9)) {
      return V1_21_9;
    }
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_21_6)) {
      return V1_21_6;
    }
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_21_5)) {
      return V1_21_5;
    }
    return V1_21_4;
  }
}
