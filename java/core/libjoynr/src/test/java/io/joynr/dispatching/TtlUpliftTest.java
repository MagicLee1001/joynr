package io.joynr.dispatching;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2016 BMW Car IT GmbH
 * %%
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
 * #L%
 */

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import com.google.inject.util.Modules;

import io.joynr.common.ExpiryDate;
import io.joynr.common.JoynrPropertiesModule;
import io.joynr.dispatching.subscription.AttributePollInterpreter;
import io.joynr.dispatching.subscription.PublicationManager;
import io.joynr.dispatching.subscription.PublicationManagerImpl;
import io.joynr.messaging.ConfigurableMessagingSettings;
import io.joynr.messaging.JsonMessageSerializerModule;
import io.joynr.messaging.MessagingQos;
import io.joynr.provider.AbstractSubscriptionPublisher;
import io.joynr.provider.Deferred;
import io.joynr.provider.Promise;
import io.joynr.provider.ProviderContainer;
import io.joynr.pubsub.SubscriptionQos;
import io.joynr.runtime.JoynrInjectionConstants;
import joynr.BroadcastSubscriptionRequest;
import joynr.JoynrMessage;
import joynr.OnChangeSubscriptionQos;
import joynr.Request;
import joynr.SubscriptionPublication;
import joynr.SubscriptionReply;
import joynr.SubscriptionRequest;
import joynr.tests.testProvider;

import org.hamcrest.Description;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TtlUpliftTest {
    private static final long TTL = 1000;
    private static final long TTL_UPLIFT_MS = 10000;
    private static final long SUBSCRIPTION_UPLIFT_MS = 300;

    private static final String PROVIDER_PARTICIPANT_ID = "providerParticipantId";
    private static final String PROXY_PARTICIPANT_ID = "proxyParticipantId";
    private static final String SUBSCRIPTION_ID = "PublicationTest_id";

    private String fromParticipantId;
    private String toParticipantId;
    private Request request;
    private String payload;
    private ExpiryDate expiryDate;
    private MessagingQos messagingQos;

    private JoynrMessageFactory joynrMessageFactory;
    private JoynrMessageFactory joynrMessageFactoryWithTtlUplift;

    private ScheduledExecutorService cleanupScheduler;
    private ScheduledExecutorService cleanupSchedulerSpy;
    private RequestCaller requestCaller;
    private PublicationManagerImpl publicationManager;
    private PublicationManagerImpl publicationManagerWithTtlUplift;

    @Mock
    private AttributePollInterpreter attributePollInterpreter;
    @Mock
    private ProviderDirectory providerDirectory;
    @Mock
    private DispatcherImpl dispatcher;
    @Mock
    private testProvider provider;
    @Mock
    private AbstractSubscriptionPublisher subscriptionPublisher;
    @Mock
    private ProviderContainer providerContainer;

    @Captor
    ArgumentCaptor<MessagingQos> messagingQosCaptor;
    @Captor
    ArgumentCaptor<Long> longCaptor;

    private String valueToPublish = "valuePublished";

    @Before
    public void setUp() throws NoSuchMethodException, SecurityException {
        fromParticipantId = "sender";
        toParticipantId = "receiver";
        cleanupScheduler = new ScheduledThreadPoolExecutor(1);
        cleanupSchedulerSpy = Mockito.spy(cleanupScheduler);

        Module defaultModule = Modules.override(new JoynrPropertiesModule(new Properties()))
                                      .with(new JsonMessageSerializerModule(), new AbstractModule() {

                                          @Override
                                          protected void configure() {
                                              requestStaticInjection(Request.class);
                                              Multibinder<JoynrMessageProcessor> joynrMessageProcessorMultibinder = Multibinder.newSetBinder(binder(),
                                                                                                                                             new TypeLiteral<JoynrMessageProcessor>() {
                                                                                                                                             });
                                              joynrMessageProcessorMultibinder.addBinding()
                                                                              .toInstance(new JoynrMessageProcessor() {
                                                                                  @Override
                                                                                  public JoynrMessage process(JoynrMessage joynrMessage) {
                                                                                      joynrMessage.getHeader()
                                                                                                  .put("test", "test");
                                                                                      return joynrMessage;
                                                                                  }
                                                                              });
                                              bind(PublicationManager.class).to(PublicationManagerImpl.class);
                                              bind(AttributePollInterpreter.class).toInstance(attributePollInterpreter);
                                              bind(Dispatcher.class).toInstance(dispatcher);
                                              bind(ProviderDirectory.class).toInstance(providerDirectory);
                                              bind(ScheduledExecutorService.class).annotatedWith(Names.named(JoynrInjectionConstants.JOYNR_SCHEDULER_CLEANUP))
                                                                                  .toInstance(cleanupSchedulerSpy);
                                          }

                                      });
        Injector injector = Guice.createInjector(defaultModule);

        joynrMessageFactory = injector.getInstance(JoynrMessageFactory.class);

        Module ttlUpliftModule = Modules.override(defaultModule).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_TTL_UPLIFT_MS))
                                .toInstance(TTL_UPLIFT_MS);
            }
        });
        Injector injectorWithTtlUplift = Guice.createInjector(ttlUpliftModule);
        joynrMessageFactoryWithTtlUplift = injectorWithTtlUplift.getInstance(JoynrMessageFactory.class);

        requestCaller = new RequestCallerFactory().create(provider);
        when(providerContainer.getRequestCaller()).thenReturn(requestCaller);
        when(providerContainer.getSubscriptionPublisher()).thenReturn(subscriptionPublisher);
        Deferred<String> valueToPublishDeferred = new Deferred<String>();
        valueToPublishDeferred.resolve(valueToPublish);
        Promise<Deferred<String>> valueToPublishPromise = new Promise<Deferred<String>>(valueToPublishDeferred);
        doReturn(valueToPublishPromise).when(attributePollInterpreter).execute(any(ProviderContainer.class),
                                                                               any(Method.class));

        Module subcriptionUpliftModule = Modules.override(defaultModule).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_TTL_UPLIFT_MS))
                                .toInstance(SUBSCRIPTION_UPLIFT_MS);
            }
        });
        Injector injectorWithPublicationUplift = Guice.createInjector(subcriptionUpliftModule);
        publicationManager = (PublicationManagerImpl) injector.getInstance(PublicationManager.class);
        publicationManagerWithTtlUplift = (PublicationManagerImpl) injectorWithPublicationUplift.getInstance(PublicationManager.class);

        payload = "payload";
        Method method = TestRequestCaller.class.getMethod("respond", new Class[]{ String.class });
        request = new Request(method.getName(), new String[]{ payload }, method.getParameterTypes());
        messagingQos = new MessagingQos(TTL);
        expiryDate = DispatcherUtils.convertTtlToExpirationDate(messagingQos.getRoundTripTtl_ms());
    }

    @Test
    public void testDefaultTtlUpliftMs() {
        expiryDate = DispatcherUtils.convertTtlToExpirationDate(messagingQos.getRoundTripTtl_ms());
        JoynrMessage message = joynrMessageFactory.createRequest(fromParticipantId,
                                                                 toParticipantId,
                                                                 request,
                                                                 messagingQos);

        long expiryDateValue = expiryDate.getValue();
        JoynrMessageFactoryTest.assertExpiryDateEquals(expiryDateValue, message);
    }

    @Test
    public void testTtlUpliftMs() {
        expiryDate = DispatcherUtils.convertTtlToExpirationDate(messagingQos.getRoundTripTtl_ms());
        JoynrMessage message = joynrMessageFactoryWithTtlUplift.createRequest(fromParticipantId,
                                                                              toParticipantId,
                                                                              request,
                                                                              messagingQos);

        long expiryDateValue = expiryDate.getValue() + TTL_UPLIFT_MS;
        JoynrMessageFactoryTest.assertExpiryDateEquals(expiryDateValue, message);
    }

    private static class MessagingQosMatcher extends ArgumentMatcher<MessagingQos> {

        private long expectedPublicationTtlMs;
        private String describeTo;

        private MessagingQosMatcher(long expectedPublicationTtlMs) {
            this.expectedPublicationTtlMs = expectedPublicationTtlMs;
            describeTo = "";
        }

        @Override
        public boolean matches(Object argument) {
            if (argument == null) {
                describeTo = "argument was null";
                return false;
            }
            if (!argument.getClass().equals(MessagingQos.class)) {
                describeTo = "unexpected class: " + argument.getClass();
                return false;
            }
            MessagingQos actual = (MessagingQos) argument;
            if (actual.getRoundTripTtl_ms() == expectedPublicationTtlMs) {
                return true;
            }
            describeTo = "expected roundTripTtlMs: " + expectedPublicationTtlMs + ", actual: "
                    + actual.getRoundTripTtl_ms();
            return false;
        }

        @Override
        public void describeTo(Description description) {
            super.describeTo(description);
            description.appendText(": " + describeTo);
        }
    }

    private void verifySubscriptionReplyTtl(long expectedSubscriptionReplyTtl, long toleranceMs) {
        verify(dispatcher, times(1)).sendSubscriptionReply(eq(PROVIDER_PARTICIPANT_ID),
                                                           eq(PROXY_PARTICIPANT_ID),
                                                           any(SubscriptionReply.class),
                                                           messagingQosCaptor.capture());
        MessagingQos capturedMessagingQos = messagingQosCaptor.getValue();
        long diff = Math.abs(expectedSubscriptionReplyTtl - capturedMessagingQos.getRoundTripTtl_ms());
        assertTrue("TTL of subscriptionReply=" + capturedMessagingQos.getRoundTripTtl_ms() + " differs " + diff
                           + "ms (more than " + toleranceMs + "ms) from the expected value="
                           + expectedSubscriptionReplyTtl,
                   (diff <= toleranceMs));
    }

    private void verifyCleanupSchedulerDelay(long expectedDelay, long toleranceMs) {
        verify(cleanupSchedulerSpy, times(1)).schedule(any(Runnable.class), longCaptor.capture(), any(TimeUnit.class));
        long capturedLong = longCaptor.getValue();
        long diff = expectedDelay - capturedLong;
        assertTrue("Delay for cleanupScheduler=" + capturedLong + " differs " + diff + "ms (more than " + toleranceMs
                + "ms) from the expected value=" + expectedDelay, (diff <= toleranceMs));
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 3000)
    public void testAttributeSubscriptionWithoutTtlUplift() throws Exception {
        long validityMs = 300;
        long publicationTtlMs = 1000;
        long toleranceMs = 50;

        OnChangeSubscriptionQos qos = new OnChangeSubscriptionQos();
        qos.setMinIntervalMs(0);
        qos.setValidityMs(validityMs);
        qos.setPublicationTtlMs(publicationTtlMs);

        SubscriptionRequest subscriptionRequest = new SubscriptionRequest(SUBSCRIPTION_ID, "location", qos);

        when(providerDirectory.get(eq(PROVIDER_PARTICIPANT_ID))).thenReturn(providerContainer);
        when(providerDirectory.contains(eq(PROVIDER_PARTICIPANT_ID))).thenReturn(true);

        publicationManager.addSubscriptionRequest(PROXY_PARTICIPANT_ID, PROVIDER_PARTICIPANT_ID, subscriptionRequest);

        verifySubscriptionReplyTtl(validityMs, toleranceMs);

        verifyCleanupSchedulerDelay(validityMs, toleranceMs);

        publicationManager.attributeValueChanged(SUBSCRIPTION_ID, valueToPublish);

        // sending initial value plus the attributeValueChanged
        verify(dispatcher, times(2)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                 (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                 any(SubscriptionPublication.class),
                                                                 argThat(new MessagingQosMatcher(publicationTtlMs)));

        Thread.sleep(validityMs + toleranceMs);
        reset(dispatcher);

        publicationManager.attributeValueChanged(SUBSCRIPTION_ID, valueToPublish);

        verify(dispatcher, timeout(300).times(0)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                              (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                              any(SubscriptionPublication.class),
                                                                              any(MessagingQos.class));
    }

    @SuppressWarnings("unchecked")
    private void testAttributeSubscriptionWithTtlUplift(OnChangeSubscriptionQos qos,
                                                        long sleepDurationMs,
                                                        long expectedSubscriptionReplyTtl,
                                                        long expectedPublicationTtlMs) throws InterruptedException {
        final long toleranceMs = 50;
        SubscriptionRequest subscriptionRequest = new SubscriptionRequest(SUBSCRIPTION_ID, "location", qos);

        when(providerDirectory.get(eq(PROVIDER_PARTICIPANT_ID))).thenReturn(providerContainer);
        when(providerDirectory.contains(eq(PROVIDER_PARTICIPANT_ID))).thenReturn(true);

        publicationManagerWithTtlUplift.addSubscriptionRequest(PROXY_PARTICIPANT_ID,
                                                               PROVIDER_PARTICIPANT_ID,
                                                               subscriptionRequest);

        verifySubscriptionReplyTtl(expectedSubscriptionReplyTtl, toleranceMs);
        if (qos.getExpiryDateMs() != SubscriptionQos.NO_EXPIRY_DATE) {
            verifyCleanupSchedulerDelay(expectedSubscriptionReplyTtl, toleranceMs);
        } else {
            verify(cleanupSchedulerSpy, times(0)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        }

        publicationManagerWithTtlUplift.attributeValueChanged(SUBSCRIPTION_ID, valueToPublish);

        Thread.sleep(sleepDurationMs + toleranceMs);

        publicationManagerWithTtlUplift.attributeValueChanged(SUBSCRIPTION_ID, valueToPublish);
        // sending initial value plus 2 times the attributeValueChanged
        verify(dispatcher, times(3)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                 (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                 any(SubscriptionPublication.class),
                                                                 argThat(new MessagingQosMatcher(expectedPublicationTtlMs)));

        Thread.sleep(SUBSCRIPTION_UPLIFT_MS);
        reset(dispatcher);
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 3000)
    public void testAttributeSubscriptionWitTtlUplift() throws Exception {
        long validityMs = 300;
        long publicationTtlMs = 1000;

        OnChangeSubscriptionQos qos = new OnChangeSubscriptionQos();
        qos.setMinIntervalMs(0);
        qos.setValidityMs(validityMs);
        qos.setPublicationTtlMs(publicationTtlMs);

        long expectedSubscriptionReplyTtl = validityMs;
        long expectedPublicationTtlMs = publicationTtlMs;

        testAttributeSubscriptionWithTtlUplift(qos, validityMs, expectedSubscriptionReplyTtl, expectedPublicationTtlMs);

        publicationManagerWithTtlUplift.attributeValueChanged(SUBSCRIPTION_ID, valueToPublish);

        verify(dispatcher, timeout(300).times(0)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                              (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                              any(SubscriptionPublication.class),
                                                                              any(MessagingQos.class));
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 3000)
    public void testAttributeSubscriptionWitTtlUpliftWithNoExpiryDate() throws Exception {
        long validityMs = 300;
        long publicationTtlMs = 1000;

        OnChangeSubscriptionQos qos = new OnChangeSubscriptionQos();
        qos.setMinIntervalMs(0);
        qos.setExpiryDateMs(SubscriptionQos.NO_EXPIRY_DATE);
        qos.setPublicationTtlMs(publicationTtlMs);

        long expectedSubscriptionReplyTtl = Long.MAX_VALUE;
        long expectedPublicationTtlMs = publicationTtlMs;

        testAttributeSubscriptionWithTtlUplift(qos, validityMs, expectedSubscriptionReplyTtl, expectedPublicationTtlMs);

        publicationManagerWithTtlUplift.attributeValueChanged(SUBSCRIPTION_ID, valueToPublish);

        verify(dispatcher, timeout(300).times(1)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                              (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                              any(SubscriptionPublication.class),
                                                                              argThat(new MessagingQosMatcher(expectedPublicationTtlMs)));
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 3000)
    public void testBroadcastSubscriptionWithoutTtlUplift() throws Exception {
        long validityMs = 300;
        long toleranceMs = 50;
        long publicationTtlMs = 1000;

        OnChangeSubscriptionQos qos = new OnChangeSubscriptionQos();
        qos.setMinIntervalMs(0);
        qos.setValidityMs(validityMs);
        qos.setPublicationTtlMs(publicationTtlMs);

        SubscriptionRequest subscriptionRequest = new BroadcastSubscriptionRequest(SUBSCRIPTION_ID,
                                                                                   "location",
                                                                                   null,
                                                                                   qos);

        when(providerDirectory.get(eq(PROVIDER_PARTICIPANT_ID))).thenReturn(providerContainer);
        when(providerDirectory.contains(eq(PROVIDER_PARTICIPANT_ID))).thenReturn(true);

        publicationManager.addSubscriptionRequest(PROXY_PARTICIPANT_ID, PROVIDER_PARTICIPANT_ID, subscriptionRequest);

        verifySubscriptionReplyTtl(validityMs, toleranceMs);
        verifyCleanupSchedulerDelay(validityMs, toleranceMs);

        publicationManager.broadcastOccurred(SUBSCRIPTION_ID, null, valueToPublish);

        // sending the broadcastOccurred
        verify(dispatcher, times(1)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                 (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                 any(SubscriptionPublication.class),
                                                                 argThat(new MessagingQosMatcher(publicationTtlMs)));

        Thread.sleep(validityMs + toleranceMs);
        reset(dispatcher);

        publicationManager.broadcastOccurred(SUBSCRIPTION_ID, null, valueToPublish);

        verify(dispatcher, timeout(300).times(0)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                              (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                              any(SubscriptionPublication.class),
                                                                              any(MessagingQos.class));
    }

    @SuppressWarnings("unchecked")
    private void testBroadcastSubscriptionWithTtlUplift(OnChangeSubscriptionQos qos,
                                                        long sleepDurationMs,
                                                        long expectedSubscriptionReplyTtl,
                                                        long expectedPublicationTtlMs) throws InterruptedException {
        final long toleranceMs = 50;
        SubscriptionRequest subscriptionRequest = new BroadcastSubscriptionRequest(SUBSCRIPTION_ID,
                                                                                   "location",
                                                                                   null,
                                                                                   qos);

        when(providerDirectory.get(eq(PROVIDER_PARTICIPANT_ID))).thenReturn(providerContainer);
        when(providerDirectory.contains(eq(PROVIDER_PARTICIPANT_ID))).thenReturn(true);

        publicationManagerWithTtlUplift.addSubscriptionRequest(PROXY_PARTICIPANT_ID,
                                                               PROVIDER_PARTICIPANT_ID,
                                                               subscriptionRequest);

        verifySubscriptionReplyTtl(expectedSubscriptionReplyTtl, toleranceMs);
        if (qos.getExpiryDateMs() != SubscriptionQos.NO_EXPIRY_DATE) {
            verifyCleanupSchedulerDelay(expectedSubscriptionReplyTtl, toleranceMs);
        } else {
            verify(cleanupSchedulerSpy, times(0)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        }

        publicationManagerWithTtlUplift.broadcastOccurred(SUBSCRIPTION_ID, null, valueToPublish);

        Thread.sleep(sleepDurationMs + toleranceMs);

        publicationManagerWithTtlUplift.broadcastOccurred(SUBSCRIPTION_ID, null, valueToPublish);
        // sending 2 times the broadcastOccurred
        verify(dispatcher, times(2)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                 (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                 any(SubscriptionPublication.class),
                                                                 argThat(new MessagingQosMatcher(expectedPublicationTtlMs)));

        Thread.sleep(SUBSCRIPTION_UPLIFT_MS);
        reset(dispatcher);
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 3000)
    public void testBroadcastSubscriptionWitTtlUplift() throws Exception {
        long validityMs = 300;
        long publicationTtlMs = 1000;

        OnChangeSubscriptionQos qos = new OnChangeSubscriptionQos();
        qos.setMinIntervalMs(0);
        qos.setValidityMs(validityMs);
        qos.setPublicationTtlMs(publicationTtlMs);

        long expectedSubscriptionReplyTtl = validityMs;
        long expectedPublicationTtlMs = publicationTtlMs;

        testBroadcastSubscriptionWithTtlUplift(qos, validityMs, expectedSubscriptionReplyTtl, expectedPublicationTtlMs);

        publicationManagerWithTtlUplift.broadcastOccurred(SUBSCRIPTION_ID, null, valueToPublish);

        verify(dispatcher, timeout(300).times(0)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                              (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                              any(SubscriptionPublication.class),
                                                                              any(MessagingQos.class));
    }

    @SuppressWarnings("unchecked")
    @Test(timeout = 3000)
    public void testBroadcastSubscriptionWitTtlUpliftWithNoExpiryDate() throws Exception {
        long validityMs = 300;
        long publicationTtlMs = 1000;

        OnChangeSubscriptionQos qos = new OnChangeSubscriptionQos();
        qos.setMinIntervalMs(0);
        qos.setExpiryDateMs(SubscriptionQos.NO_EXPIRY_DATE);
        qos.setPublicationTtlMs(publicationTtlMs);

        long expectedSubscriptionReplyTtl = Long.MAX_VALUE;
        long expectedPublicationTtlMs = publicationTtlMs;

        testBroadcastSubscriptionWithTtlUplift(qos, validityMs, expectedSubscriptionReplyTtl, expectedPublicationTtlMs);

        publicationManagerWithTtlUplift.broadcastOccurred(SUBSCRIPTION_ID, null, valueToPublish);

        verify(dispatcher, timeout(300).times(1)).sendSubscriptionPublication(eq(PROVIDER_PARTICIPANT_ID),
                                                                              (Set<String>) argThat(contains(PROXY_PARTICIPANT_ID)),
                                                                              any(SubscriptionPublication.class),
                                                                              argThat(new MessagingQosMatcher(expectedPublicationTtlMs)));
    }

}