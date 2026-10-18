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
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import io.netty.buffer.ByteBuf;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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

  /**
   * What a backend sends during configuration that the client cannot change without a
   * reconfiguration. Two backends with equal fingerprints can be switched without one.
   */
  public static final class Fingerprint {

    private @Nullable List<KnownPacksPacket.KnownPack> knownPacks;
    private final TreeMap<String, byte[]> registries = new TreeMap<>();
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
      byte[] hash;
      try {
        hash = canonicalRegistryHash(peek);
      } catch (RuntimeException | IOException e) {
        // Unknown layout: raw bytes still work, they only make a match less likely.
        hash = sha256(content);
      }
      registries.put(id, hash);
    }

    private static byte[] canonicalRegistryHash(ByteBuf buf) throws IOException {
      MessageDigest digest = newDigest();
      try (DataOutputStream out = new DataOutputStream(
          new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
        int entries = ProtocolUtils.readVarInt(buf);
        out.writeInt(entries);
        for (int i = 0; i < entries; i++) {
          out.writeUTF(ProtocolUtils.readString(buf));
          boolean hasData = buf.readBoolean();
          out.writeBoolean(hasData);
          if (hasData) {
            writeCanonical(ProtocolUtils.readBinaryTag(buf, ProtocolVersion.MINECRAFT_1_21_4, null), out);
          }
        }
        if (buf.isReadable()) {
          throw new IOException("trailing bytes");
        }
      }
      return digest.digest();
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
     * Returns the ids of the registries that differ, empty when both match.
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
        byte[] a = registries.get(id);
        byte[] b = other.registries.get(id);
        if (a == null || b == null || !Arrays.equals(a, b)) {
          differing.add(id);
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
