/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2017 BMW Car IT GmbH
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
package itest.io.joynr.jeeintegration.multicast;

import javax.ejb.Stateless;
import javax.inject.Inject;

import io.joynr.jeeintegration.api.ServiceProvider;
import io.joynr.jeeintegration.api.SubscriptionPublisher;
import joynr.exceptions.ApplicationException;
import joynr.jeeintegration.servicelocator.MyServiceSubscriptionPublisher;
import joynr.jeeintegration.servicelocator.MyServiceSync;

@Stateless
@ServiceProvider(serviceInterface = MyServiceSync.class)
public class BeanWithSubscriptionPublisher implements MyServiceSync {

    private MyServiceSubscriptionPublisher subscriptionPublisher;

    @Inject
    public BeanWithSubscriptionPublisher(@SubscriptionPublisher MyServiceSubscriptionPublisher subscriptionPublisher) {
        this.subscriptionPublisher = subscriptionPublisher;
    }

    public MyServiceSubscriptionPublisher getSubscriptionPublisher() {
        return subscriptionPublisher;
    }

    @Override
    public String callMe(String parameterOne) {
        subscriptionPublisher.fireMyMulticast(parameterOne);
        return null;
    }

    @Override
    public void callMeWithException() throws ApplicationException {
    }
}
