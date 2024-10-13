package org.apache.pulsar.broker.service.nonpersistent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.SubscriptionTestBase;
import org.apache.pulsar.broker.testcontext.PulsarTestContext;
import org.apache.pulsar.common.api.proto.KeySharedMeta;
import org.testng.annotations.Test;

@Test(groups = "broker")
public class NonPersistentSubscriptionTest extends SubscriptionTestBase {

    @Test(dataProvider = "incompatibleKeySharedPolicies")
    public void testIncompatibleKeySharedPoliciesNotAllowed(KeySharedMeta consumer1Ksm, KeySharedMeta consumer2Ksm,
                                                            String expectedErrorMessage) throws Exception {
        PulsarTestContext context = PulsarTestContext.builderForNonStartableContext().build();
        NonPersistentTopic topic = new NonPersistentTopic(successTopicName, context.getBrokerService());
        NonPersistentSubscription sub = new NonPersistentSubscription(topic, subName, Map.of());

        // two consumers with incompatible key_shared policies
        Consumer keySharedConsumerMock1 = createKeySharedMockConsumer("consumer-1", consumer1Ksm);
        Consumer keySharedConsumerMock2 = createKeySharedMockConsumer("consumer-2", consumer2Ksm);

        // first consumer defines key_shared mode of subscription and whether out of order delivery is allowed
        sub.addConsumer(keySharedConsumerMock1).get(5, TimeUnit.SECONDS);

        try {
            // add second consumer with incompatible key_shared policy
            sub.addConsumer(keySharedConsumerMock2).get(5, TimeUnit.SECONDS);
            fail(BrokerServiceException.SubscriptionBusyException.class.getSimpleName() + " not thrown");
        } catch (Exception e) {
            // subscription throws exception when consumer with incompatible key_shared policy is added
            Throwable cause = e.getCause();
            assertTrue(cause instanceof BrokerServiceException.SubscriptionBusyException);
            assertEquals(cause.getMessage(), expectedErrorMessage);
        }

        context.close();
    }

}
