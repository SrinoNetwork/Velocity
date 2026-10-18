/*
 * Copyright (C) 2019-2023 Velocity Contributors
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

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreTransferEvent;
import com.velocitypowered.api.event.player.CookieRequestEvent;
import com.velocitypowered.api.event.player.CookieStoreEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerResourcePackRemoveEvent;
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientConfigSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.client.SeamlessSwitchState;
import com.velocitypowered.proxy.connection.player.resourcepack.VelocityResourcePackInfo;
import com.velocitypowered.proxy.connection.player.resourcepack.handler.ResourcePackHandler;
import com.velocitypowered.proxy.connection.util.ConnectionMessages;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundStoreCookiePacket;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.RemoveResourcePackPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.TransferPacket;
import com.velocitypowered.proxy.protocol.packet.config.ActiveFeaturesPacket;
import com.velocitypowered.proxy.protocol.packet.config.ClientboundCustomReportDetailsPacket;
import com.velocitypowered.proxy.protocol.packet.config.ClientboundServerLinksPacket;
import com.velocitypowered.proxy.protocol.packet.config.CodeOfConductPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A special session handler that catches "last minute" disconnects. This version is to accommodate
 * 1.20.2+ switching. Yes, some of this is exceptionally stupid.
 */
public class ConfigSessionHandler implements MinecraftSessionHandler {
  private static final boolean BACKPRESSURE_LOG =
      Boolean.getBoolean("velocity.log-server-backpressure");

  private static final Logger logger = LogManager.getLogger(ConfigSessionHandler.class);
  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final CompletableFuture<Impl> resultFuture;

  private ResourcePackInfo resourcePackToApply;

  private State state;

  // Srino: seamless server switch. SPECULATIVE = the client stays in play while the proxy runs
  // this configuration for it; everything meant for the client is held back until FinishedUpdate
  // shows whether the client already has these registries. FALLBACK = they differ, the client is
  // being sent into the configuration state and gets the held back packets once it is there.
  private final SeamlessSwitchState.Fingerprint fingerprint = new SeamlessSwitchState.Fingerprint();
  private SeamlessMode seamlessMode = SeamlessMode.OFF;
  private final List<MinecraftPacket> clientBuffer = new ArrayList<>();
  private boolean answeredKnownPacks;
  private boolean finishPending;

  /**
   * Creates the new transition handler.
   *
   * @param server       the Velocity server instance
   * @param serverConn   the server connection
   * @param resultFuture the result future
   */
  ConfigSessionHandler(VelocityServer server, VelocityServerConnection serverConn,
                       CompletableFuture<Impl> resultFuture) {
    this.server = server;
    this.serverConn = serverConn;
    this.resultFuture = resultFuture;
    this.state = State.START;
  }

  @Override
  public void activated() {
    ConnectedPlayer player = serverConn.getPlayer();
    if (player.getProtocolVersion() == ProtocolVersion.MINECRAFT_1_20_2) {
      resourcePackToApply = player.resourcePackHandler().getFirstAppliedPack();
      player.resourcePackHandler().clearAppliedResourcePacks();
    }
  }

  /**
   * Srino: runs this configuration without the client, see {@link SeamlessMode}.
   */
  public void startSeamlessAttempt() {
    this.seamlessMode = SeamlessMode.SPECULATIVE;
  }

  private void toClient(MinecraftPacket packet) {
    if (seamlessMode != SeamlessMode.OFF) {
      clientBuffer.add(packet);
    } else {
      serverConn.getPlayer().getConnection().write(packet);
    }
  }

  private void releaseClientBuffer() {
    for (MinecraftPacket packet : clientBuffer) {
      ReferenceCountUtil.release(packet);
    }
    clientBuffer.clear();
  }

  private void startFallback(String reason) {
    if (seamlessMode != SeamlessMode.SPECULATIVE) {
      return;
    }
    final ConnectedPlayer player = serverConn.getPlayer();
    if (!(player.getConnection().getActiveSessionHandler() instanceof ClientPlaySessionHandler playHandler)) {
      // The client left the play state on its own; nothing seamless left to keep.
      seamlessMode = SeamlessMode.OFF;
      return;
    }
    seamlessMode = SeamlessMode.FALLBACK;
    logger.info("[seamless] {} -> {}: reconfiguring ({})", player.getUsername(),
        serverConn.getServerInfo().getName(), reason);
    player.seamlessSwitch().setSwallowKnownPacksResponse(answeredKnownPacks);
    final MinecraftConnection smc = serverConn.ensureConnected();
    playHandler.startSeamlessFallback().thenRunAsync(this::finishFallback, smc.eventLoop());
  }

  private void finishFallback() {
    if (!serverConn.isActive()) {
      releaseClientBuffer();
      return;
    }
    seamlessMode = SeamlessMode.OFF;
    final MinecraftConnection playerConnection = serverConn.getPlayer().getConnection();
    for (MinecraftPacket packet : clientBuffer) {
      playerConnection.delayedWrite(packet);
    }
    clientBuffer.clear();
    playerConnection.flush();
    if (finishPending) {
      finishPending = false;
      completeFinishedUpdate();
    }
  }

  private void completeSeamless() {
    final MinecraftConnection smc = serverConn.ensureConnected();
    final ConnectedPlayer player = serverConn.getPlayer();
    seamlessMode = SeamlessMode.OFF;

    // The client never sees this configuration, so its brand has to be replayed from here.
    final String brand = player.getClientBrand();
    if (brand != null) {
      final ByteBuf buf = Unpooled.buffer();
      ProtocolUtils.writeString(buf, brand);
      smc.write(new PluginMessagePacket("minecraft:brand", buf));
    }

    // Registries, feature flags and the known packs request only exist in the configuration
    // state and match what the client has; everything else is sent in play after JoinGame.
    final List<MinecraftPacket> deferred = new ArrayList<>();
    for (MinecraftPacket packet : clientBuffer) {
      if (packet instanceof RegistrySyncPacket || packet instanceof ActiveFeaturesPacket
          || packet instanceof KnownPacksPacket) {
        ReferenceCountUtil.release(packet);
      } else {
        deferred.add(packet);
      }
    }
    clientBuffer.clear();
    player.seamlessSwitch().setDeferredForJoin(deferred);
    logger.info("[seamless] {} -> {}: skipped reconfiguration", player.getUsername(),
        serverConn.getServerInfo().getName());

    smc.write(FinishedUpdatePacket.INSTANCE);
    smc.setActiveSessionHandler(StateRegistry.PLAY, new TransitionSessionHandler(server, serverConn, resultFuture));
  }

  @Override
  public boolean beforeHandle() {
    if (!serverConn.isActive()) {
      // Obsolete connection
      serverConn.disconnect();
      return true;
    }
    return false;
  }

  @Override
  public boolean handle(StartUpdatePacket packet) {
    serverConn.ensureConnected().write(packet);
    return true;
  }

  @Override
  public boolean handle(TagsUpdatePacket packet) {
    toClient(packet);
    return true;
  }

  @Override
  public boolean handle(ClientboundCustomReportDetailsPacket packet) {
    toClient(packet);
    return true;
  }

  @Override
  public boolean handle(ClientboundServerLinksPacket packet) {
    toClient(packet);
    return true;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    if (seamlessMode != SeamlessMode.OFF) {
      // The client is not part of this configuration (yet), answer for it.
      serverConn.ensureConnected().write(packet);
      return true;
    }
    serverConn.getPendingPings().put(packet.getRandomId(), System.nanoTime());
    serverConn.getPlayer().getConnection().write(packet);
    return true;
  }

  @Override
  public boolean handle(final ResourcePackRequestPacket packet) {
    final MinecraftConnection playerConnection = serverConn.getPlayer().getConnection();

    final ResourcePackInfo resourcePackInfo = packet.toServerPromptedPack();
    final ServerResourcePackSendEvent event =
        new ServerResourcePackSendEvent(resourcePackInfo, this.serverConn);

    server.getEventManager().fire(event).thenAcceptAsync(serverResourcePackSendEvent -> {
      if (playerConnection.isClosed()) {
        return;
      }
      if (serverResourcePackSendEvent.getResult().isAllowed()) {
        final ResourcePackInfo toSend = serverResourcePackSendEvent.getProvidedResourcePack();
        boolean modifiedPack = false;
        if (toSend != serverResourcePackSendEvent.getReceivedResourcePack()) {
          ((VelocityResourcePackInfo) toSend).setOriginalOrigin(
              ResourcePackInfo.Origin.DOWNSTREAM_SERVER);
          modifiedPack = true;
        }
        if (serverConn.getPlayer().resourcePackHandler().hasPackAppliedByHash(toSend.getHash())) {
          // Do not apply a resource pack that has already been applied
          if (serverConn.getConnection() != null) {
            // We can technically skip these first 2 states, however, for conformity to normal state flow expectations...
            serverConn.getConnection().write(new ResourcePackResponsePacket(
                    packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.ACCEPTED));
            serverConn.getConnection().write(new ResourcePackResponsePacket(
                packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.DOWNLOADED));
            serverConn.getConnection().write(new ResourcePackResponsePacket(
                packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.SUCCESSFUL));
          }
          if (modifiedPack) {
            logger.warn("A plugin has tried to modify a ResourcePack provided by the backend server "
                    + "with a ResourcePack already applied, the applying of the resource pack will be skipped.");
          }
        } else {
          resourcePackToApply = null;
          serverConn.getPlayer().resourcePackHandler().queueResourcePack(toSend);
        }
      } else if (serverConn.getConnection() != null) {
        serverConn.getConnection().write(new ResourcePackResponsePacket(
                packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.DECLINED));
      }
    }, playerConnection.eventLoop()).exceptionally((ex) -> {
      if (serverConn.getConnection() != null) {
        serverConn.getConnection().write(new ResourcePackResponsePacket(
                packet.getId(), packet.getHash(), PlayerResourcePackStatusEvent.Status.DECLINED));
      }
      logger.error("Exception while handling resource pack send for {}", playerConnection, ex);
      return null;
    });

    return true;
  }

  @Override
  public boolean handle(RemoveResourcePackPacket packet) {
    final MinecraftConnection playerConnection = this.serverConn.getPlayer().getConnection();

    final ServerResourcePackRemoveEvent event = new ServerResourcePackRemoveEvent(
            packet.getId(), this.serverConn);
    server.getEventManager().fire(event).thenAcceptAsync(serverResourcePackRemoveEvent -> {
      if (playerConnection.isClosed()) {
        return;
      }
      if (serverResourcePackRemoveEvent.getResult().isAllowed()) {
        final ConnectedPlayer player = serverConn.getPlayer();
        final ResourcePackHandler handler = player.resourcePackHandler();
        if (packet.getId() != null) {
          handler.remove(packet.getId());
        } else {
          handler.clearAppliedResourcePacks();
        }
        playerConnection.write(packet);
      }
    }, playerConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception while handling resource pack remove for {}", playerConnection, ex);
      return null;
    });
    return true;
  }

  @Override
  public boolean handle(FinishedUpdatePacket packet) {
    final MinecraftConnection smc = serverConn.ensureConnected();

    smc.getChannel().pipeline().get(MinecraftVarintFrameDecoder.class).setState(StateRegistry.PLAY);
    smc.getChannel().pipeline().get(MinecraftDecoder.class).setState(StateRegistry.PLAY);

    if (seamlessMode == SeamlessMode.SPECULATIVE) {
      final SeamlessSwitchState.Fingerprint clientFingerprint =
          serverConn.getPlayer().seamlessSwitch().clientFingerprint();
      final Set<String> differing = clientFingerprint == null
          ? Set.of("<no client fingerprint>") : fingerprint.diff(clientFingerprint);
      if (differing.isEmpty()) {
        completeSeamless();
        return true;
      }
      finishPending = true;
      startFallback("differs: " + String.join(", ", differing));
      return true;
    }
    if (seamlessMode == SeamlessMode.FALLBACK) {
      finishPending = true;
      return true;
    }
    completeFinishedUpdate();
    return true;
  }

  @Override
  public boolean handle(DisconnectPacket packet) {
    releaseClientBuffer();
    serverConn.disconnect();
    // If the player receives a DisconnectPacket without a connection to a server in progress,
    // it means that the backend server has kicked the player during reconfiguration
    if (serverConn.getPlayer().getConnectionInFlight() != null) {
      resultFuture.complete(ConnectionRequestResults.forDisconnect(packet, serverConn.getServer()));
    } else {
      serverConn.getPlayer().handleConnectionException(serverConn.getServer(), packet, true);
    }
    return true;
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    if (PluginMessageUtil.isMcBrand(packet)) {
      toClient(PluginMessageUtil.rewriteMinecraftBrand(packet, server.getVersion(),
              serverConn.getPlayer().getProtocolVersion()));
    } else {
      ChannelIdentifier id = this.server.getChannelRegistrar().getFromId(packet.getChannel());

      if (id == null) {
        toClient(packet.retain());
        return true;
      }

      // Handling this stuff async means that we should probably pause
      // the connection while we toss this off into another pool
      byte[] bytes = ByteBufUtil.getBytes(packet.content());
      this.serverConn.getConnection().setAutoReading(false);
      this.server.getEventManager()
          .fire(new PluginMessageEvent(serverConn, serverConn.getPlayer(), id, bytes))
          .thenAcceptAsync(pme -> {
            if (pme.getResult().isAllowed() && !serverConn.getPlayer().getConnection().isClosed()) {
              toClient(new PluginMessagePacket(
                  pme.getIdentifier().getId(), Unpooled.wrappedBuffer(bytes)));
            }
            this.serverConn.getConnection().setAutoReading(true);
          },  serverConn.ensureConnected().eventLoop()).exceptionally((ex) -> {
            logger.error("Exception while handling plugin message {}", packet, ex);
            return null;
          });
    }
    return true;
  }

  @Override
  public boolean handle(RegistrySyncPacket packet) {
    fingerprint.registry(packet.content());
    packet.retain();
    toClient(packet);
    return true;
  }

  @Override
  public boolean handle(ActiveFeaturesPacket packet) {
    fingerprint.features(packet.getActiveFeatures());
    toClient(packet);
    return true;
  }

  @Override
  public boolean handle(KnownPacksPacket packet) {
    fingerprint.knownPacks(packet.getPacks());
    if (seamlessMode == SeamlessMode.SPECULATIVE) {
      final SeamlessSwitchState state = serverConn.getPlayer().seamlessSwitch();
      final SeamlessSwitchState.Fingerprint clientFingerprint = state.clientFingerprint();
      final KnownPacksPacket clientResponse = state.clientKnownPacksResponse();
      if (clientFingerprint != null && clientResponse != null
          && packet.getPacks().equals(clientFingerprint.knownPacks())) {
        // Same request the client answered last time: answer with its answer.
        serverConn.ensureConnected().write(new KnownPacksPacket(clientResponse.getPacks()));
        answeredKnownPacks = true;
        toClient(packet);
        return true;
      }
      toClient(packet);
      startFallback("known packs differ");
      return true;
    }
    toClient(packet);
    return true;
  }

  @Override
  public boolean handle(TransferPacket packet) {
    final InetSocketAddress originalAddress = packet.address();
    if (originalAddress == null) {
      logger.error("""
          Unexpected nullable address received in TransferPacket \
          from Backend Server in Configuration State""");
      return true;
    }
    this.server.getEventManager()
            .fire(new PreTransferEvent(this.serverConn.getPlayer(), originalAddress))
            .thenAcceptAsync(event -> {
              if (event.getResult().isAllowed()) {
                InetSocketAddress resultedAddress = event.getResult().address();
                if (resultedAddress == null) {
                  resultedAddress = originalAddress;
                }
                serverConn.getPlayer().getConnection().write(new TransferPacket(
                        resultedAddress.getHostName(), resultedAddress.getPort()));
              }
            }, serverConn.ensureConnected().eventLoop());
    return true;
  }

  @Override
  public boolean handle(ClientboundStoreCookiePacket packet) {
    server.getEventManager()
        .fire(new CookieStoreEvent(serverConn.getPlayer(), packet.getKey(), packet.getPayload()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final Key resultedKey = event.getResult().getKey() == null
                ? event.getOriginalKey() : event.getResult().getKey();
            final byte[] resultedData = event.getResult().getData() == null
                ? event.getOriginalData() : event.getResult().getData();

            serverConn.getPlayer().getConnection()
                .write(new ClientboundStoreCookiePacket(resultedKey, resultedData));
          }
        }, serverConn.ensureConnected().eventLoop());

    return true;
  }

  @Override
  public boolean handle(ClientboundCookieRequestPacket packet) {
    // The answer has to come back in the configuration state.
    startFallback("cookie request");
    server.getEventManager().fire(new CookieRequestEvent(serverConn.getPlayer(), packet.getKey()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            final Key resultedKey = event.getResult().getKey() == null
                ? event.getOriginalKey() : event.getResult().getKey();

            toClient(new ClientboundCookieRequestPacket(resultedKey));
          }
        }, serverConn.ensureConnected().eventLoop());

    return true;
  }

  @Override
  public boolean handle(CodeOfConductPacket packet) {
    startFallback("code of conduct");
    packet.retain();
    toClient(packet);
    return true;
  }

  @Override
  public void disconnected() {
    releaseClientBuffer();
    resultFuture.complete(ConnectionRequestResults.forDisconnect(
        ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR, serverConn.getServer()));
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    // Anything without a play state counterpart we know of needs the real configuration.
    startFallback(packet.getClass().getSimpleName());
    toClient(packet);
  }

  @Override
  public void writabilityChanged() {
    Channel serverChan = serverConn.ensureConnected().getChannel();
    boolean writable = serverChan.isWritable();

    if (BACKPRESSURE_LOG) {
      if (writable) {
        logger.info("{} is writable, will auto-read player connection data", this.serverConn);
      } else {
        logger.info("{} is not writable, not auto-reading player connection data", this.serverConn);
      }
    }

    serverConn.getPlayer().getConnection().setAutoReading(writable);
  }

  private void switchFailure(Throwable cause) {
    logger.error("Unable to switch to new server {} for {}", serverConn.getServerInfo().getName(),
        serverConn.getPlayer().getUsername(), cause);
    serverConn.getPlayer().disconnect(ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR);
    resultFuture.completeExceptionally(cause);
  }

  private void completeFinishedUpdate() {
    final MinecraftConnection smc = serverConn.ensureConnected();
    final ConnectedPlayer player = serverConn.getPlayer();
    final ClientConfigSessionHandler configHandler = (ClientConfigSessionHandler) player.getConnection().getActiveSessionHandler();
    player.seamlessSwitch().commitClientConfiguration(fingerprint);

    //noinspection DataFlowIssue
    configHandler.handleBackendFinishUpdate(serverConn).thenRunAsync(() -> {
      smc.write(FinishedUpdatePacket.INSTANCE);
      if (serverConn == player.getConnectedServer()) {
        smc.setActiveSessionHandler(StateRegistry.PLAY);
        player.sendPlayerListHeaderAndFooter(player.getPlayerListHeader(), player.getPlayerListFooter());
        // The client cleared the tab list. TODO: Restore changes done via TabList API
        player.getTabList().clearAllSilent();
      } else {
        smc.setActiveSessionHandler(StateRegistry.PLAY, new TransitionSessionHandler(server, serverConn, resultFuture));
      }
      if (player.resourcePackHandler().getFirstAppliedPack() == null && resourcePackToApply != null) {
        player.resourcePackHandler().queueResourcePack(resourcePackToApply);
      }
    }, smc.eventLoop());
  }

  /**
   * Represents the state of the configuration stage.
   */
  public enum State {
    START, NEGOTIATING, PLUGIN_MESSAGE_INTERRUPT, RESOURCE_PACK_INTERRUPT, COMPLETE
  }

  /**
   * Srino: how this configuration relates to the client, see {@link #startSeamlessAttempt()}.
   */
  private enum SeamlessMode {
    OFF, SPECULATIVE, FALLBACK
  }
}
