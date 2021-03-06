//============================================================================//
//                                                                            //
//                         Copyright © 2015 Sandpolis                         //
//                                                                            //
//  This source file is subject to the terms of the Mozilla Public License    //
//  version 2. You may not use this file except in compliance with the MPL    //
//  as published by the Mozilla Foundation.                                   //
//                                                                            //
//============================================================================//
package com.sandpolis.core.agent.init;

import static com.sandpolis.core.instance.plugin.PluginStore.PluginStore;
import static com.sandpolis.core.instance.profile.ProfileStore.ProfileStore;
import static com.sandpolis.core.instance.state.STStore.STStore;
import static com.sandpolis.core.net.connection.ConnectionStore.ConnectionStore;
import static com.sandpolis.core.net.exelet.ExeletStore.ExeletStore;
import static com.sandpolis.core.net.network.NetworkStore.NetworkStore;
import static com.sandpolis.core.net.stream.StreamStore.StreamStore;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;

import com.google.common.eventbus.Subscribe;
import com.sandpolis.core.agent.cmd.AuthCmd;
import com.sandpolis.core.agent.config.CfgAgent;
import com.sandpolis.core.agent.exe.AgentExe;
import com.sandpolis.core.clientagent.cmd.PluginCmd;
import com.sandpolis.core.foundation.Result.Outcome;
import com.sandpolis.core.instance.Entrypoint;
import com.sandpolis.core.instance.InitTask;
import com.sandpolis.core.instance.TaskOutcome;
import com.sandpolis.core.instance.config.CfgInstance;
import com.sandpolis.core.instance.plugin.PluginStore;
import com.sandpolis.core.instance.profile.ProfileStore;
import com.sandpolis.core.instance.state.STStore;
import com.sandpolis.core.instance.state.oid.Oid;
import com.sandpolis.core.instance.state.st.EphemeralDocument;
import com.sandpolis.core.instance.thread.ThreadStore;
import com.sandpolis.core.net.channel.client.ClientChannelInitializer;
import com.sandpolis.core.net.connection.ConnectionStore;
import com.sandpolis.core.net.exelet.ExeletStore;
import com.sandpolis.core.net.network.NetworkStore;
import com.sandpolis.core.net.network.NetworkStore.ServerEstablishedEvent;
import com.sandpolis.core.net.network.NetworkStore.ServerLostEvent;
import com.sandpolis.core.net.stream.StreamStore;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor;

public class AgentLoadStores extends InitTask {

	@Override
	public TaskOutcome run(TaskOutcome outcome) throws Exception {
		ThreadStore.ThreadStore.init(config -> {
			config.defaults.put("net.exelet", new NioEventLoopGroup(2).next());
			config.defaults.put("net.connection.outgoing", new NioEventLoopGroup(2).next());
			config.defaults.put("net.connection.loop", new NioEventLoopGroup(2).next());
			config.defaults.put("net.message.incoming", new UnorderedThreadPoolEventExecutor(2));
			config.defaults.put("store.event_bus", Executors.newSingleThreadExecutor());
			config.defaults.put("attributes", Executors.newScheduledThreadPool(1));
		});

		STStore.init(config -> {
			config.concurrency = 1;
			config.root = new EphemeralDocument(null, null);
		});

		ProfileStore.init(config -> {
			config.collection = Oid.of("/profile").get();
		});

		PluginStore.init(config -> {
			config.collection = Oid.of("/profile//plugin", Entrypoint.data().uuid()).get();
		});

		StreamStore.init(config -> {
		});

		ExeletStore.init(config -> {
			config.exelets = List.of(AgentExe.class);
		});

		ConnectionStore.init(config -> {
			config.collection = Oid.of("/connection").get();
		});

		NetworkStore.init(config -> {
			config.collection = Oid.of("/network_connection").get();
		});

		NetworkStore.register(new Object() {
			@Subscribe
			private void onSrvLost(ServerLostEvent event) {

				// Intentionally wait before reconnecting
				// TODO don't block
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {

				}

				ConnectionStore.connect(config -> {
					config.address(CfgAgent.SERVER_ADDRESS.value().get());
					config.timeout = CfgAgent.SERVER_TIMEOUT.value().orElse(1000);
					config.bootstrap.handler(new ClientChannelInitializer(struct -> {
						struct.clientTlsInsecure();
					}));
				});
			}

			@Subscribe
			private void onSrvEstablished(ServerEstablishedEvent event) {
				CompletionStage<Outcome> future;

				switch (CfgAgent.AUTH_TYPE.value().orElse("none")) {
				case "password":
					future = AuthCmd.async().target(event.cvid()).password(CfgAgent.AUTH_PASSWORD.value().get());
					break;
				default:
					future = AuthCmd.async().target(event.cvid()).none();
					break;
				}

				future = future.thenApply(rs -> {
					if (!rs.getResult()) {
						// Close the connection
						ConnectionStore.getByCvid(event.cvid()).ifPresent(sock -> {
							sock.close();
						});
					}
					return rs;
				});

				if (CfgInstance.PLUGIN_ENABLED.value().orElse(true)) {
					future.thenAccept(rs -> {
						if (rs.getResult()) {
							// Synchronize plugins
							PluginCmd.async().synchronize().thenRun(PluginStore::loadPlugins);
						}
					});
				}
			}
		});

		return outcome.success();
	}

	@Override
	public String description() {
		return "Load static stores";
	}

}
