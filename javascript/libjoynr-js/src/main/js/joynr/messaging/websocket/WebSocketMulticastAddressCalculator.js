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

define("joynr/messaging/websocket/WebSocketMulticastAddressCalculator", [ "joynr/util/UtilInternal"
], function(Util) {

    /**
     * @constructor WebSocketMulticastAddressCalculator
     * @param {Object}
     *            settings
     * @param {WebSocketAddress}
     *            settings.globalAddress
     */
    var WebSocketMulticastAddressCalculator =
            function WebSocketMulticastAddressCalculator(settings) {
                Util.checkProperty(settings, "Object", "settings");
                Util.checkProperty(
                        settings.globalAddress,
                        "WebSocketAddress",
                        "settings.globalAddress");

                /**
                 * Calculates the multicast address for the submitted joynr message
                 * @function WebSocketMulticastAddressCalculator#calculate
                 *
                 * @param {JoynrMessage}
                 *            message
                 * @return {Address} the multicast address
                 */
                this.calculate = function calculate(message) {
                    return settings.globalAddress;
                };
            };

    return WebSocketMulticastAddressCalculator;

});