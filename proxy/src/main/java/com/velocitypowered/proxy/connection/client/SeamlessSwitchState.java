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
import com.velocitypowered.proxy.connection.registry.DimensionInfo;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagType;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Srino: per player state for seamless server switches.
 *
 * <p>Holds the fingerprint of the configuration the client currently runs with (known packs,
 * registries, feature flags) and what the current backend created on the client that survives a
 * switch without reconfiguration (scoreboard objectives, teams, boss bars).</p>
 */
public final class SeamlessSwitchState {

  private @Nullable Fingerprint clientFingerprint;
  private @Nullable KnownPacksPacket clientKnownPacksResponse;
  private @Nullable KnownPacksPacket pendingKnownPacksResponse;
  private boolean swallowKnownPacksResponse;

  private final Set<String> objectives = new LinkedHashSet<>();
  private final Set<String> teams = new LinkedHashSet<>();
  private final Set<UUID> bossBars = new LinkedHashSet<>();
  private @Nullable List<MinecraftPacket> deferredForJoin;

  public synchronized @Nullable Fingerprint clientFingerprint() {
    return clientFingerprint;
  }

  public synchronized @Nullable KnownPacksPacket clientKnownPacksResponse() {
    return clientKnownPacksResponse;
  }

  /**
   * Stores what the client was configured with, called when a configuration reaches the client.
   */
  public synchronized void commitClientConfiguration(Fingerprint fingerprint) {
    this.clientFingerprint = fingerprint;
    this.clientKnownPacksResponse = pendingKnownPacksResponse;
  }

  /**
   * Records the client's answer to the last known packs request.
   *
   * @return true if the answer must not reach the backend because the proxy already answered
   */
  public synchronized boolean onClientKnownPacksResponse(KnownPacksPacket packet) {
    this.pendingKnownPacksResponse = packet;
    if (swallowKnownPacksResponse) {
      swallowKnownPacksResponse = false;
      return true;
    }
    return false;
  }

  public synchronized void setSwallowKnownPacksResponse(boolean swallow) {
    this.swallowKnownPacksResponse = swallow;
  }

  /**
   * Marks the next JoinGame as the end of a seamless switch; the packets are what the skipped
   * configuration had for the client that also exists in play.
   */
  public void setDeferredForJoin(List<MinecraftPacket> packets) {
    this.deferredForJoin = packets;
  }

  /**
   * Returns and clears the packets from {@link #setDeferredForJoin}, null if the next JoinGame
   * does not end a seamless switch.
   */
  public @Nullable List<MinecraftPacket> takeDeferredForJoin() {
    List<MinecraftPacket> packets = deferredForJoin;
    deferredForJoin = null;
    return packets;
  }

  public Set<String> objectives() {
    return objectives;
  }

  public Set<String> teams() {
    return teams;
  }

  public Set<UUID> bossBars() {
    return bossBars;
  }

  /**
   * The client drops all of these when it enters the configuration state.
   */
  public void clearClientLeftovers() {
    objectives.clear();
    teams.clear();
    bossBars.clear();
  }

  // Srino: what the client holds right now, for a switch that sends neither JoinGame nor Respawn
  // (both open the client's loading screen). Updated on the player's event loop only.

  private int clientEntityId = Integer.MIN_VALUE;
  private @Nullable ClientSpawn clientSpawn;
  private final LongOpenHashSet chunks = new LongOpenHashSet();
  private final IntOpenHashSet entities = new IntOpenHashSet();
  private final IntOpenHashSet effects = new IntOpenHashSet();
  // waypoint identifier bytes -> the waypoint as the backend sent it last
  private final Map<ByteBuffer, byte[]> waypoints = new HashMap<>();

  /**
   * The world the client is in: two backends with the same spawn and the same entity id for the
   * player can be switched without JoinGame.
   *
   * @param dimensionType dimension type registry id
   * @param levelName     dimension key, e.g. minecraft:overworld
   * @param flat          flat world (sky)
   * @param debug         debug world
   * @param seaLevel      sea level
   * @param hardcore      hardcore hearts
   */
  public record ClientSpawn(int dimensionType, @Nullable String levelName, boolean flat,
      boolean debug, int seaLevel, boolean hardcore) {

    static ClientSpawn of(JoinGamePacket joinGame) {
      DimensionInfo info = joinGame.getDimensionInfo();
      return new ClientSpawn(joinGame.getDimension(), info == null ? null : info.getLevelName(),
          info != null && info.isFlat(), info != null && info.isDebugType(), joinGame.getSeaLevel(),
          joinGame.getIsHardcore());
    }
  }

  public int clientEntityId() {
    return clientEntityId;
  }

  public @Nullable ClientSpawn clientSpawn() {
    return clientSpawn;
  }

  /**
   * The client got a JoinGame: new level, new player entity.
   */
  public void onClientJoinGame(JoinGamePacket joinGame) {
    clientEntityId = joinGame.getEntityId();
    clientSpawn = ClientSpawn.of(joinGame);
    clearClientWorld();
  }

  /**
   * The client got a Respawn: always a new player entity (no effects), a new level when the
   * dimension changed.
   */
  public void onClientRespawn(RespawnPacket respawn) {
    effects.clear();
    if (clientSpawn == null) {
      return;
    }
    DimensionInfo info = respawn.getDimensionInfo();
    ClientSpawn next = new ClientSpawn(respawn.getDimension(),
        info == null ? null : info.getLevelName(), info != null && info.isFlat(),
        info != null && info.isDebugType(), respawn.getSeaLevel(), clientSpawn.hardcore());
    if (!Objects.equals(next.levelName(), clientSpawn.levelName())) {
      chunks.clear();
      entities.clear();
    }
    clientSpawn = next;
  }

  /**
   * The client went to the configuration state, which drops its level.
   */
  public void onClientReconfigure() {
    clientEntityId = Integer.MIN_VALUE;
    clientSpawn = null;
    clearClientWorld();
  }

  private int lastRemovedChunks;
  private int lastRemovedEntities;

  /**
   * The proxy removed the old backend's chunks, entities, effects and waypoints from the client;
   * entity id and spawn stay.
   */
  public void onSwitchWithoutJoinGame() {
    lastRemovedChunks = chunks.size();
    lastRemovedEntities = entities.size();
    clearClientWorld();
  }

  public int lastRemovedChunks() {
    return lastRemovedChunks;
  }

  public int lastRemovedEntities() {
    return lastRemovedEntities;
  }

  private void clearClientWorld() {
    chunks.clear();
    entities.clear();
    effects.clear();
    waypoints.clear();
  }

  /**
   * Peeks a play packet a backend sends to the client (packet id first, not consumed) and records
   * chunks, entities, the player's effects and waypoints.
   */
  public void trackClientbound(ByteBuf packet, SeamlessPacketIds ids) {
    ByteBuf buf = packet.duplicate();
    try {
      int id = ProtocolUtils.readVarInt(buf);
      if (id == ids.levelChunkWithLight()) {
        int x = buf.readInt();
        int z = buf.readInt();
        chunks.add(packChunk(x, z));
      } else if (id == ids.forgetLevelChunk()) {
        chunks.remove(buf.readLong());
      } else if (id == ids.addEntity() || id == ids.addExperienceOrb()) {
        int entityId = ProtocolUtils.readVarInt(buf);
        if (entityId != clientEntityId) {
          entities.add(entityId);
        }
      } else if (id == ids.removeEntities()) {
        int count = ProtocolUtils.readVarInt(buf);
        for (int i = 0; i < count; i++) {
          entities.remove(ProtocolUtils.readVarInt(buf));
        }
      } else if (id == ids.updateMobEffect() || id == ids.removeMobEffect()) {
        if (ProtocolUtils.readVarInt(buf) == clientEntityId) {
          int effect = ProtocolUtils.readVarInt(buf);
          if (id == ids.updateMobEffect()) {
            effects.add(effect);
          } else {
            effects.remove(effect);
          }
        }
      } else if (id == ids.trackedWaypoint()) {
        int operation = ProtocolUtils.readVarInt(buf);
        int start = buf.readerIndex();
        if (buf.readBoolean()) {
          buf.skipBytes(16); // uuid
        } else {
          buf.skipBytes(ProtocolUtils.readVarInt(buf)); // string
        }
        byte[] key = new byte[buf.readerIndex() - start];
        buf.getBytes(start, key);
        if (operation == WAYPOINT_UNTRACK) {
          waypoints.remove(ByteBuffer.wrap(key));
        } else {
          byte[] waypoint = new byte[buf.writerIndex() - start];
          buf.getBytes(start, waypoint);
          waypoints.put(ByteBuffer.wrap(key), waypoint);
        }
      }
    } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
      // A packet we cannot read is not tracked; a switch then leaves a little behind at worst.
    }
  }

  static final int WAYPOINT_UNTRACK = 1;

  static long packChunk(int x, int z) {
    return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
  }

  public LongOpenHashSet chunks() {
    return chunks;
  }

  public IntOpenHashSet entities() {
    return entities;
  }

  public IntOpenHashSet effects() {
    return effects;
  }

  public Map<ByteBuffer, byte[]> waypoints() {
    return waypoints;
  }

  /**
   * What a backend sends during configuration that the client cannot change without a
   * reconfiguration. Two backends with equal fingerprints can be switched without one.
   */
  public static final class Fingerprint {

    private static final byte[] NO_DATA = new byte[0];

    private @Nullable List<KnownPacksPacket.KnownPack> knownPacks;
    // registry id -> entry id -> hash of the entry's data (empty when the entry has none), in
    // packet order, because the order gives the entries their network ids
    private final TreeMap<String, LinkedHashMap<String, byte[]>> registries = new TreeMap<>();
    private @Nullable Set<Key> features;

    public void knownPacks(List<KnownPacksPacket.KnownPack> packs) {
      this.knownPacks = List.copyOf(packs);
    }

    public @Nullable List<KnownPacksPacket.KnownPack> knownPacks() {
      return knownPacks;
    }

    /**
     * Adds one registry data packet; registries are compared by id, not by arrival order, and
     * NBT compounds by sorted keys, because two equal servers do not write keys in the same order.
     */
    public void registry(ByteBuf content) {
      ByteBuf peek = content.duplicate();
      String id = ProtocolUtils.readString(peek);
      LinkedHashMap<String, byte[]> entries;
      try {
        entries = readEntries(peek);
      } catch (RuntimeException | IOException e) {
        // Unknown layout: raw bytes still work, they only make a match less likely.
        entries = new LinkedHashMap<>();
        entries.put("<raw>", sha256(content));
      }
      registries.put(id, entries);
    }

    private static LinkedHashMap<String, byte[]> readEntries(ByteBuf buf) throws IOException {
      LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
      int count = ProtocolUtils.readVarInt(buf);
      for (int i = 0; i < count; i++) {
        String entry = ProtocolUtils.readString(buf);
        byte[] hash = NO_DATA;
        if (buf.readBoolean()) {
          MessageDigest digest = newDigest();
          try (DataOutputStream out = new DataOutputStream(
              new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            writeCanonical(ProtocolUtils.readBinaryTag(buf, ProtocolVersion.MINECRAFT_1_21_4, null), out);
          }
          hash = digest.digest();
        }
        entries.put(entry, hash);
      }
      if (buf.isReadable()) {
        throw new IOException("trailing bytes");
      }
      return entries;
    }

    @SuppressWarnings("unchecked")
    private static void writeCanonical(BinaryTag tag, DataOutputStream out) throws IOException {
      out.writeByte(tag.type().id());
      if (tag instanceof CompoundBinaryTag compound) {
        List<String> keys = new ArrayList<>(compound.keySet());
        keys.sort(null);
        out.writeInt(keys.size());
        for (String key : keys) {
          out.writeUTF(key);
          writeCanonical(compound.get(key), out);
        }
      } else if (tag instanceof ListBinaryTag list) {
        out.writeByte(list.elementType().id());
        out.writeInt(list.size());
        for (BinaryTag element : list) {
          writeCanonical(element, out);
        }
      } else {
        ((BinaryTagType<BinaryTag>) tag.type()).write(tag, out);
      }
    }

    public void features(Key[] keys) {
      this.features = Set.of(keys);
    }

    /**
     * Describes what differs (registry ids with up to a few entries: + only here, - only there,
     * ~ other data, order = same entries in another order), empty when both match.
     */
    public Set<String> diff(Fingerprint other) {
      Set<String> differing = new LinkedHashSet<>();
      if (!Objects.equals(knownPacks, other.knownPacks)) {
        differing.add("<known packs>");
      }
      if (!Objects.equals(features, other.features)) {
        differing.add("<features>");
      }
      Set<String> ids = new TreeSet<>(registries.keySet());
      ids.addAll(other.registries.keySet());
      for (String id : ids) {
        LinkedHashMap<String, byte[]> a = registries.get(id);
        LinkedHashMap<String, byte[]> b = other.registries.get(id);
        if (a == null || b == null) {
          differing.add(id + (a == null ? " (missing here)" : " (missing there)"));
          continue;
        }
        List<String> details = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : a.entrySet()) {
          byte[] there = b.get(e.getKey());
          if (there == null) {
            details.add("+" + e.getKey());
          } else if (!Arrays.equals(e.getValue(), there)) {
            details.add("~" + e.getKey());
          }
        }
        for (String key : b.keySet()) {
          if (!a.containsKey(key)) {
            details.add("-" + key);
          }
        }
        if (details.isEmpty() && !new ArrayList<>(a.keySet()).equals(new ArrayList<>(b.keySet()))) {
          details.add("order");
        }
        if (!details.isEmpty()) {
          int shown = Math.min(details.size(), 6);
          differing.add(id + " [" + String.join(" ", details.subList(0, shown))
              + (details.size() > shown ? " +" + (details.size() - shown) + " more" : "") + "]");
        }
      }
      return differing;
    }

    private static byte[] sha256(ByteBuf buf) {
      MessageDigest digest = newDigest();
      digest.update(buf.nioBuffer(buf.readerIndex(), buf.readableBytes()));
      return digest.digest();
    }

    private static MessageDigest newDigest() {
      try {
        return MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
