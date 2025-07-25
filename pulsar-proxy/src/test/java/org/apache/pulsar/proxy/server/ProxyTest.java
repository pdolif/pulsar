/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.proxy.server;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.mockito.Mockito.doReturn;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lombok.Cleanup;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.avro.reflect.Nullable;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.pulsar.PulsarVersion;
import org.apache.pulsar.broker.BrokerTestUtil;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.apache.pulsar.broker.service.ServerCnx;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.InjectedClientCnxClientBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageRoutingMode;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.ClientBuilderImpl;
import org.apache.pulsar.client.impl.ClientCnx;
import org.apache.pulsar.client.impl.ConnectionPool;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.client.impl.metrics.InstrumentProvider;
import org.apache.pulsar.common.api.AuthData;
import org.apache.pulsar.common.api.proto.BaseCommand;
import org.apache.pulsar.common.api.proto.CommandActiveConsumerChange;
import org.apache.pulsar.common.api.proto.FeatureFlags;
import org.apache.pulsar.common.api.proto.ProtocolVersion;
import org.apache.pulsar.common.configuration.PulsarConfigurationLoader;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.policies.data.TopicType;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.common.util.netty.EventLoopUtil;
import org.apache.pulsar.metadata.impl.ZKMetadataStore;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ProxyTest extends MockedPulsarServiceBaseTest {

    private static final Logger log = LoggerFactory.getLogger(ProxyTest.class);

    protected ProxyService proxyService;
    protected ProxyConfiguration proxyConfig = new ProxyConfiguration();
    protected Authentication proxyClientAuthentication;

    @Data
    @ToString
    @EqualsAndHashCode
    public static class Foo {
        @Nullable
        private String field1;
        @Nullable
        private String field2;
        private int field3;
    }

    @Override
    @BeforeClass
    protected void setup() throws Exception {
        internalSetup();

        initializeProxyConfig();

        proxyService = Mockito.spy(new ProxyService(proxyConfig, new AuthenticationService(
                PulsarConfigurationLoader.convertFrom(proxyConfig)), proxyClientAuthentication));
        doReturn(registerCloseable(new ZKMetadataStore(mockZooKeeper))).when(proxyService).createLocalMetadataStore();
        doReturn(registerCloseable(new ZKMetadataStore(mockZooKeeperGlobal))).when(proxyService)
                .createConfigurationMetadataStore();

        proxyService.start();

        // create default resources.
        admin.clusters().createCluster(conf.getClusterName(),
                ClusterData.builder().serviceUrl(pulsar.getWebServiceAddress()).build());
        TenantInfo tenantInfo = new TenantInfoImpl(Collections.emptySet(),
                Collections.singleton(conf.getClusterName()));
        admin.tenants().createTenant("public", tenantInfo);
        admin.namespaces().createNamespace("public/default");
    }

    protected void initializeProxyConfig() throws Exception {
        proxyConfig.setServicePort(Optional.ofNullable(0));
        proxyConfig.setBrokerProxyAllowedTargetPorts("*");
        proxyConfig.setMetadataStoreUrl(DUMMY_VALUE);
        proxyConfig.setConfigurationMetadataStoreUrl(GLOBAL_DUMMY_VALUE);
        proxyConfig.setClusterName(configClusterName);

        proxyClientAuthentication = AuthenticationFactory.create(
                proxyConfig.getBrokerClientAuthenticationPlugin(),
                proxyConfig.getBrokerClientAuthenticationParameters());
        proxyClientAuthentication.start();
    }

    @Override
    protected void doInitConf() throws Exception {
        super.doInitConf();
        conf.setAllowAutoTopicCreationType(TopicType.PARTITIONED);
        conf.setDefaultNumPartitions(1);
    }

    @Override
    @AfterClass(alwaysRun = true)
    protected void cleanup() throws Exception {
        proxyService.close();
        if (proxyClientAuthentication != null) {
            proxyClientAuthentication.close();
        }
        internalCleanup();
    }

    @Test
    public void testProducer() throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl())
                .build();

        @Cleanup
        Producer<byte[]> producer = client.newProducer()
            .topic("persistent://sample/test/local/producer-topic")
            .create();

        for (int i = 0; i < 10; i++) {
            producer.send("test".getBytes());
        }
    }

    @Test
    public void testProxyConnectionClientConfig() throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl())
                .build();

        @Cleanup
        Producer<byte[]> producer = client.newProducer()
                .topic("persistent://sample/test/local/producer-topic2")
                .create();

        MutableBoolean found = new MutableBoolean(false);
        proxyService.getClientCnxs().forEach(proxyConnection -> {
            if (proxyConnection.getConnectionPool() != null) {
                try {
                    found.setTrue();
                    assertEquals(-1,
                            FieldUtils.readDeclaredField(proxyConnection.getConnectionPool(),
                                    "connectionMaxIdleSeconds",
                                    true));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        assertTrue(found.isTrue(), "No proxy connection found with connectionMaxIdleSeconds set to -1");
    }

    @Test
    public void testProducerConsumer() throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl())
                .build();

        @Cleanup
        Producer<byte[]> producer = client.newProducer(Schema.BYTES)
            .topic("persistent://sample/test/local/producer-consumer-topic")
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();

        // Create a consumer directly attached to broker
        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic("persistent://sample/test/local/producer-consumer-topic").subscriptionName("my-sub").subscribe();

        for (int i = 0; i < 10; i++) {
            producer.send("test".getBytes());
        }

        for (int i = 0; i < 10; i++) {
            Message<byte[]> msg = consumer.receive(1, TimeUnit.SECONDS);
            requireNonNull(msg);
            consumer.acknowledge(msg);
        }

        Message<byte[]> msg = consumer.receive(0, TimeUnit.SECONDS);
        checkArgument(msg == null);
    }

    @Test
    public void testPartitions() throws Exception {
        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("sample", tenantInfo);
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl())
                .build();
        admin.topics().createPartitionedTopic("persistent://sample/test/local/partitioned-topic", 2);

        @Cleanup
        Producer<byte[]> producer = client.newProducer(Schema.BYTES)
            .topic("persistent://sample/test/local/partitioned-topic")
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.RoundRobinPartition).create();

        // Create a consumer directly attached to broker
        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic("persistent://sample/test/local/partitioned-topic")
                .subscriptionName("my-sub").subscribe();

        for (int i = 0; i < 10; i++) {
            producer.send("test".getBytes());
        }

        for (int i = 0; i < 10; i++) {
            Message<byte[]> msg = consumer.receive(1, TimeUnit.SECONDS);
            requireNonNull(msg);
        }
    }

    /**
     * test auto create partitioned topic by proxy.
     **/
    @Test
    public void testAutoCreateTopic() throws Exception{
        TopicType originalAllowAutoTopicCreationType = pulsar.getConfiguration().getAllowAutoTopicCreationType();
        int originalDefaultNumPartitions = pulsar.getConfiguration().getDefaultNumPartitions();
        int defaultPartition = 2;
        int defaultNumPartitions = pulsar.getConfiguration().getDefaultNumPartitions();
        pulsar.getConfiguration().setAllowAutoTopicCreationType(TopicType.PARTITIONED);
        pulsar.getConfiguration().setDefaultNumPartitions(defaultPartition);
        try {
            @Cleanup
            PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl())
              .build();
            String topic = "persistent://sample/test/local/partitioned-proxy-topic";
            CompletableFuture<List<String>> partitionNamesFuture = client.getPartitionsForTopic(topic);
            List<String> partitionNames = partitionNamesFuture.get(30000, TimeUnit.MILLISECONDS);
            assertEquals(partitionNames.size(), defaultPartition);
        } finally {
            pulsar.getConfiguration().setAllowAutoTopicCreationType(originalAllowAutoTopicCreationType);
            pulsar.getConfiguration().setDefaultNumPartitions(originalDefaultNumPartitions);
        }
    }

    @Test
    public void testRegexSubscription() throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl())
            .connectionsPerBroker(5).ioThreads(5).build();

        // create two topics by subscribing to a topic and closing it
        try (Consumer<byte[]> ignored = client.newConsumer()
            .topic("persistent://sample/test/local/regex-sub-topic1")
            .subscriptionName("proxy-ignored")
            .subscribe()) {
        }
        try (Consumer<byte[]> ignored = client.newConsumer()
            .topic("persistent://sample/test/local/regex-sub-topic2")
            .subscriptionName("proxy-ignored")
            .subscribe()) {
        }

        String subName = "regex-sub-proxy-test-" + System.currentTimeMillis();

        // make sure regex subscription
        String regexSubscriptionPattern = "persistent://sample/test/local/regex-sub-topic.*";
        log.info("Regex subscribe to topics {}", regexSubscriptionPattern);
        try (Consumer<byte[]> consumer = client.newConsumer()
            .topicsPattern(regexSubscriptionPattern)
            .subscriptionName(subName)
            .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
            .subscribe()) {
            log.info("Successfully subscribe to topics using regex {}", regexSubscriptionPattern);

            final int numMessages = 20;

            try (Producer<byte[]> producer = client.newProducer(Schema.BYTES)
                .topic("persistent://sample/test/local/regex-sub-topic1")
                .create()) {
                for (int i = 0; i < numMessages; i++) {
                    producer.send(("message-" + i).getBytes(UTF_8));
                }
            }

            for (int i = 0; i < numMessages; i++) {
                Message<byte[]> msg = consumer.receive();
                assertEquals("message-" + i, new String(msg.getValue(), UTF_8));
            }
        }
    }

    @DataProvider(name = "topicTypes")
    public Object[][] topicTypes() {
        return new Object[][]{
                {TopicType.PARTITIONED},
                {TopicType.NON_PARTITIONED}
        };
    }

    @Test(timeOut = 60_000, dataProvider = "topicTypes")
    public void testRegexSubscriptionWithTopicDiscovery(TopicType topicType) throws Exception {
        @Cleanup
        final PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl()).build();
        final int topics = 10;
        final String subName = "s1";
        final String namespace = "public/default";
        final String topicPrefix = BrokerTestUtil.newUniqueName("persistent://" + namespace + "/tp");
        final String regexSubscriptionPattern = topicPrefix + ".*";
        // Set retention policy to avoid flaky.
        RetentionPolicies retentionPolicies = new RetentionPolicies(3600, 1024);
        admin.namespaces().setRetention(namespace, retentionPolicies);
        final List<String> topicList = new ArrayList<>();
        // create topics
        for (int i = 0; i < topics; i++) {
            String topic = topicPrefix + i;
            topicList.add(topic);
            if (TopicType.PARTITIONED.equals(topicType)) {
                admin.topics().createPartitionedTopic(topic, 2);
            } else {
                admin.topics().createNonPartitionedTopic(topic);
            }
        }
        // Create consumer.
        Consumer<String> consumer = client.newConsumer(Schema.STRING)
                .topicsPattern(regexSubscriptionPattern)
                .subscriptionName(subName)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .patternAutoDiscoveryPeriod(10, TimeUnit.MINUTES)
                .isAckReceiptEnabled(true)
                .subscribe();
        // Pub & Sub -> Verify
        for (String topic : topicList) {
            String msgPublished = topic + " -> msg";
            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .create();
            producer.send(msgPublished);
            producer.close();
            // Verify.
            Message<String> msg = consumer.receive(10, TimeUnit.SECONDS);
            assertNotNull(msg);
            assertEquals(msg.getValue(), msgPublished);
            consumer.acknowledge(msg);
        }
        // cleanup.
        consumer.close();
        for (String topic : topicList) {
            if (TopicType.PARTITIONED.equals(topicType)) {
                admin.topics().deletePartitionedTopic(topic);
            } else {
                admin.topics().delete(topic);
            }
        }
    }

    @Test
    public void testGetSchema() throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl())
                .build();
        Schema<Foo> schema = Schema.AVRO(Foo.class);
        try {
            try (Producer<Foo> ignored = client.newProducer(schema).topic("persistent://sample/test/local/get-schema")
                .create()) {
            }
        } catch (Exception ex) {
            Assert.fail("Should not have failed since can acquire LookupRequestSemaphore");
        }
        byte[] schemaVersion = new byte[8];
        byte b = Long.valueOf(0L).byteValue();
        for (int i = 0; i < 8; i++){
            schemaVersion[i] = b;
        }
        SchemaInfo schemaInfo = ((PulsarClientImpl) client).getLookup()
                .getSchema(TopicName.get("persistent://sample/test/local/get-schema"), schemaVersion)
                .get().orElse(null);
        assertEquals(new String(schemaInfo.getSchema()), new String(schema.getSchemaInfo().getSchema()));
    }

    @Test
    public void testProtocolVersionAdvertisement() throws Exception {
        final String topic = "persistent://sample/test/local/protocol-version-advertisement";
        final String sub = "my-sub";

        ClientConfigurationData conf = new ClientConfigurationData();
        conf.setServiceUrl(proxyService.getServiceUrl());

        @Cleanup
        PulsarClient client = getClientActiveConsumerChangeNotSupported(conf);

        @Cleanup
        Producer<byte[]> producer = client.newProducer().topic(topic).create();

        @Cleanup
        Consumer<byte[]> consumer = client.newConsumer().topic(topic).subscriptionName(sub)
                .subscriptionType(SubscriptionType.Failover).subscribe();

        for (int i = 0; i < 10; i++) {
            producer.send("test-msg".getBytes());
        }

        for (int i = 0; i < 10; i++) {
            Message<byte[]> msg = consumer.receive(10, TimeUnit.SECONDS);
            requireNonNull(msg);
            consumer.acknowledge(msg);
        }
    }

    @Test
    public void testGetPartitionedMetadataErrorCode() throws Exception {
        final String topic = BrokerTestUtil.newUniqueName("persistent://public/default/tp");
        // Trigger partitioned metadata creation.
        PulsarClientImpl brokerClient = (PulsarClientImpl) pulsarClient;
        PartitionedTopicMetadata brokerMetadata =
                brokerClient.getPartitionedTopicMetadata(topic, true, true).get();
        assertEquals(brokerMetadata.partitions, 1);
        assertEquals(pulsar.getPulsarResources().getNamespaceResources().getPartitionedTopicResources()
                .getPartitionedTopicMetadataAsync(TopicName.get(topic)).get().get().partitions, 1);
        // Verify: Proxy never rewrite error code.
        ClientConfigurationData proxyClientConf = new ClientConfigurationData();
        proxyClientConf.setServiceUrl(proxyService.getServiceUrl());
        PulsarClientImpl proxyClient =
                (PulsarClientImpl) getClientActiveConsumerChangeNotSupported(proxyClientConf);
        PartitionedTopicMetadata proxyMetadata =
                proxyClient.getPartitionedTopicMetadata(topic, false, false).get();
        assertEquals(proxyMetadata.partitions, 1);
        try {
            proxyClient.getPartitionedTopicMetadata(topic + "-partition-0", false, false).get();
            fail("expected a TopicDoesNotExistException");
        } catch (Exception ex) {
            assertTrue(FutureUtil.unwrapCompletionException(ex)
                    instanceof PulsarClientException.TopicDoesNotExistException);
        }
        // cleanup.
        proxyClient.close();
        admin.topics().deletePartitionedTopic(topic);
    }

    @Test
    public void testGetClientVersion() throws Exception {
        @Cleanup
        PulsarClient client = PulsarClient.builder().serviceUrl(proxyService.getServiceUrl())
                .build();

        String topic = BrokerTestUtil.newUniqueName("persistent://sample/test/local/testGetClientVersion");
        String subName = "test-sub";

        @Cleanup
        Consumer<byte[]> consumer = client.newConsumer()
                .topic(topic)
                .subscriptionName(subName)
                .subscribe();

        consumer.receiveAsync();

        String partition = TopicName.get(topic).getPartition(0).toString();
        assertEquals(admin.topics().getStats(partition).getSubscriptions().get(subName).getConsumers()
                .get(0).getClientVersion(), String.format("Pulsar-Java-v%s", PulsarVersion.getVersion()));
    }

    @DataProvider
    public Object[][] booleanValues() {
        return new Object[][]{
                {true},
                {false}
        };
    }

    @Test(dataProvider = "booleanValues")
    public void testConnectedWithClientSideFeatures(boolean supported) throws Exception {
        final String topic = BrokerTestUtil.newUniqueName("persistent://public/default/tp");
        admin.topics().createNonPartitionedTopic(topic);

        // Create a client as a old version, which does not support "supportsReplDedupByLidAndEid".
        ClientBuilderImpl clientBuilder2 =
                (ClientBuilderImpl) PulsarClient.builder().serviceUrl(proxyService.getServiceUrl());
        PulsarClientImpl injectedClient = InjectedClientCnxClientBuilder.create(clientBuilder2,
            (conf, eventLoopGroup) -> {
                return new ClientCnx(InstrumentProvider.NOOP, conf, eventLoopGroup) {

                    @Override
                    protected ByteBuf newConnectCommand() throws Exception {
                        authenticationDataProvider = authentication.getAuthData(remoteHostName);
                        AuthData authData = authenticationDataProvider.authenticate(AuthData.INIT_AUTH_DATA);
                        BaseCommand cmd =
                                Commands.newConnectWithoutSerialize(authentication.getAuthMethodName(), authData,
                                        this.protocolVersion, clientVersion, proxyToTargetBrokerAddress,
                                        null, null, null, null, null);
                        FeatureFlags featureFlags = cmd.getConnect().getFeatureFlags();
                        featureFlags.setSupportsAuthRefresh(supported);
                        featureFlags.setSupportsBrokerEntryMetadata(supported);
                        featureFlags.setSupportsPartialProducer(supported);
                        featureFlags.setSupportsTopicWatchers(supported);
                        featureFlags.setSupportsReplDedupByLidAndEid(supported);
                        featureFlags.setSupportsGetPartitionedMetadataWithoutAutoCreation(supported);
                        return Commands.serializeWithSize(cmd);
                    }
                };
            });

        // Verify: the broker will create a connection, which disabled "supportsReplDedupByLidAndEid".
        Producer<byte[]> producer = injectedClient.newProducer().topic(topic).create();
        ServerCnx serverCnx = (ServerCnx) pulsar.getBrokerService().getTopic(topic, false).get().get()
                .getProducers().values().iterator().next().getCnx();
        FeatureFlags featureFlags = serverCnx.getFeatures();
        assertEquals(featureFlags.isSupportsAuthRefresh(), supported);
        assertEquals(featureFlags.isSupportsBrokerEntryMetadata(), supported);
        assertEquals(featureFlags.isSupportsPartialProducer(), supported);
        assertEquals(featureFlags.isSupportsTopicWatchers(), supported);
        assertEquals(featureFlags.isSupportsReplDedupByLidAndEid(), supported);
        assertEquals(featureFlags.isSupportsGetPartitionedMetadataWithoutAutoCreation(), supported);

        // cleanup.
        producer.close();
        injectedClient.close();
        admin.topics().delete(topic);
    }

    private PulsarClient getClientActiveConsumerChangeNotSupported(ClientConfigurationData conf)
            throws Exception {
        ThreadFactory threadFactory = new DefaultThreadFactory("pulsar-client-io", Thread.currentThread().isDaemon());
        EventLoopGroup eventLoopGroup = EventLoopUtil.newEventLoopGroup(conf.getNumIoThreads(), false, threadFactory);
        registerCloseable(() -> eventLoopGroup.shutdownNow());

        ConnectionPool cnxPool = new ConnectionPool(InstrumentProvider.NOOP, conf, eventLoopGroup, () -> {
            return new ClientCnx(InstrumentProvider.NOOP, conf, eventLoopGroup, ProtocolVersion.v11_VALUE) {
                @Override
                protected void handleActiveConsumerChange(CommandActiveConsumerChange change) {
                    throw new UnsupportedOperationException();
                }
            };
        }, null);
        registerCloseable(cnxPool);

        return new PulsarClientImpl(conf, eventLoopGroup, cnxPool);
    }

}
