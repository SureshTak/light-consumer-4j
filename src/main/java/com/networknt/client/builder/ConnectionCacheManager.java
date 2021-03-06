/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.client.builder;

import com.networknt.client.model.TimeoutDef;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

public class ConnectionCacheManager {
    private static Logger logger = LoggerFactory.getLogger(ConnectionCacheManager.class);
    private static ExecutorService executorService = Executors.newFixedThreadPool(2);
    private static Map<String, CacheableConnection> clientConnectionMap = new HashMap<>();
    private static Map<String, List<CacheableConnection>> clientConnectionParkedMap = new HashMap<>();

    private static OptionMap http2OptionMap = OptionMap.create(UndertowOptions.ENABLE_HTTP2, true);
    private static OptionMap http1OptionMap = OptionMap.EMPTY;

    /**
     * Entry point to fetch a connection.
     *
     * Should be synchronized over all instances since if different threads concurrently check if the same connection is
     * cached, they could both see that it's not available, then attempt to establish 2 connections. The correct flow
     * in this case would be that one thread would be blocked while checking, while the other thread establishes and
     * caches the connection. Then the other thread is unblocked, and sees the cached connection available.
     *
     * @param hostUri The protocol/host/{port} to connect to.
     * @param connectionTTL How long a connection will remain available if a new one needs to be made.
     * @param connectionRequestTimeout How long to wait for a connection to be established.
     * @param isHttp2Enabled Whether an http2 or http1 connection will be made.
     * @return A connection to the host.
     */
    public ClientConnection getConnection(URI hostUri, long connectionTTL, TimeoutDef connectionRequestTimeout, boolean isHttp2Enabled, int requestCount) throws InterruptedException, ExecutionException, TimeoutException {
        synchronized (ConnectionCacheManager.class) {
            CacheableConnection connection = clientConnectionMap.get(hostUri.toString());
            if (connection != null && connection.isOpen()) {
                logger.debug("Reusing open connection to: " + hostUri);
                return connection.getCachedConnection();
            }
            handleParkedConnection(hostUri.toString(), connection);
            logger.debug("Creating a new connection to: " + hostUri);
            CacheableConnection cacheableConnection = new CacheableConnection(this.connect(hostUri, connectionRequestTimeout, isHttp2Enabled), connectionTTL,requestCount);

            clientConnectionMap.put(hostUri.toString(), cacheableConnection);
            return cacheableConnection.getCachedConnection();
        }
    }

    private void handleParkedConnection(String host, CacheableConnection connection) {
        if(connection == null) {
            return;
        }
        List<CacheableConnection> parkedConList = clientConnectionParkedMap.get(host);
        if(parkedConList == null) {
            parkedConList = new ArrayList<CacheableConnection>();
            clientConnectionParkedMap.put(host, parkedConList);
        }
        logger.info("Total parked connection for the host {} is {}", host, parkedConList.size());
        Iterator<CacheableConnection> iter = parkedConList.iterator();
        while(iter.hasNext()) {
            // the isParkedConnectionExpired will close the connection.
            if(iter.next().isParkedConnectionExpired()) {
                iter.remove();
                logger.info("Removing the expired Parked Connection for the host {}", host);
            }
        }
        try {
            if(connection.getCachedConnection().isOpen()) {
                parkedConList.add(connection);
                logger.info("Parked the coonection for the host {}, total parked count is {}", host, parkedConList.size());
            }
        } catch(Exception ignored) {
            //ignore any exceptions in this catch. Exception logged on info level.
            logger.info("Exception while handling the parked connection. Exception is :", ignored);
        }
    }

    /**
     * Get the connection before issuing the request. This method will spawn a thread within the cached thread pool
     * for waiting before timing out.
     *
     * @return The connection.
     * @throws TimeoutException
     * @throws ExecutionException
     * @throws InterruptedException
     */
    private ClientConnection connect(URI host, TimeoutDef connectionRequestTimeout, boolean isHttp2Enabled) throws TimeoutException, ExecutionException, InterruptedException {
        OptionMap options = isHttp2Enabled ? http2OptionMap : http1OptionMap;
        Future<ClientConnection> connectionFuture = executorService.submit(new ClientConnectionCallable(host, options));
        try {
            return connectionFuture.get(connectionRequestTimeout.getTimeout(), connectionRequestTimeout.getUnit());
        } catch (TimeoutException e) {
            logger.error("Timeout occurred when getting connection to: " + host, e);
            throw e;
        } catch (Exception e) {
            logger.error("Error occurred when getting connection.", e);
            throw e;
        }
    }


}
