package io.joynr.messaging.routing;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2015 BMW Car IT GmbH
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

import joynr.JoynrMessage;
import joynr.system.RoutingProvider;
import joynr.system.RoutingTypes.Address;

public interface MessageRouter extends RoutingProvider {
    static final String ROUTER_GLOBAL_ADDRESS = "io.joynr.messaging.globalAddress";
    static final String SCHEDULEDTHREADPOOL = "io.joynr.messaging.scheduledthreadpool";

    public void route(JoynrMessage message);

    public void addNextHop(String participantId, Address address);

    public void shutdown();

}
