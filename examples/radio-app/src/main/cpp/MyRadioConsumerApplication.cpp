/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2013 BMW Car IT GmbH
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

#include <QFileInfo>
#include <string>
#include <stdint.h>
#include <memory>

#include "MyRadioHelper.h"
#include "joynr/vehicle/RadioProxy.h"
#include "joynr/vehicle/RadioNewStationDiscoveredBroadcastFilterParameters.h"
#include "joynr/JoynrRuntime.h"
#include "joynr/RequestStatus.h"
#include "joynr/ISubscriptionListener.h"
#include "joynr/SubscriptionListener.h"
#include "joynr/OnChangeWithKeepAliveSubscriptionQos.h"
#include <cassert>
#include <limits>
#include "joynr/JsonSerializer.h"
#include "joynr/TypeUtil.h"

using namespace joynr;
using joynr_logging::Logger;
using joynr_logging::Logging;

// A class that listens to messages generated by subscriptions
class RadioStationListener : public SubscriptionListener<vehicle::RadioStation>
{
public:
    RadioStationListener()
            : logger(Logging::getInstance()->getLogger(
                      "DEMO",
                      "MyRadioConsumerApplication::RadioStationListener"))
    {
    }

    ~RadioStationListener()
    {
        if (logger)
            delete logger;
    }

    void onReceive(const vehicle::RadioStation& value)
    {
        MyRadioHelper::prettyLog(logger,
                                 QString("ATTRIBUTE SUBSCRIPTION current station: %1")
                                         .arg(QString::fromStdString(value.toString())));
    }

    void onError(const exceptions::JoynrRuntimeException& error)
    {
        if (error.getTypeName() == exceptions::PublicationMissedException::TYPE_NAME) {
            MyRadioHelper::prettyLog(
                    logger,
                    QString("ATTRIBUTE SUBSCRIPTION Publication Missed, subscriptionId: %1")
                            .arg(QString::fromStdString(error.getMessage())));
        } else {
            MyRadioHelper::prettyLog(logger,
                                     QString("ATTRIBUTE SUBSCRIPTION error: %1")
                                             .arg(QString::fromStdString(error.getMessage())));
        }
    }

private:
    Logger* logger;
};

// A class that listens to messages generated by subscriptions
class WeakSignalBroadcastListener : public SubscriptionListener<vehicle::RadioStation>
{
public:
    WeakSignalBroadcastListener()
            : logger(Logging::getInstance()->getLogger(
                      "DEMO",
                      "MyRadioConsumerApplication::WeakSignalBroadcastListener"))
    {
    }

    ~WeakSignalBroadcastListener()
    {
        if (logger)
            delete logger;
    }

    void onReceive(const vehicle::RadioStation& value)
    {
        MyRadioHelper::prettyLog(logger,
                                 QString("BROADCAST SUBSCRIPTION weak signal: %1")
                                         .arg(QString::fromStdString(value.toString())));
    }

private:
    Logger* logger;
};

// A class that listens to messages generated by subscriptions
class NewStationDiscoveredBroadcastListener
        : public SubscriptionListener<vehicle::RadioStation, vehicle::GeoPosition>
{
public:
    NewStationDiscoveredBroadcastListener()
            : logger(Logging::getInstance()->getLogger(
                      "DEMO",
                      "MyRadioConsumerApplication::NewStationDiscoveredBroadcastListener"))
    {
    }

    ~NewStationDiscoveredBroadcastListener()
    {
        if (logger)
            delete logger;
    }

    void onReceive(const vehicle::RadioStation& discoveredStation,
                   const vehicle::GeoPosition& geoPosition)
    {
        MyRadioHelper::prettyLog(logger,
                                 QString("BROADCAST SUBSCRIPTION new station discovered: %1 at %2")
                                         .arg(QString::fromStdString(discoveredStation.toString()))
                                         .arg(QString::fromStdString(geoPosition.toString())));
    }

private:
    Logger* logger;
};

//------- Main entry point -------------------------------------------------------

int main(int argc, char* argv[])
{
    using joynr::vehicle::Radio::AddFavoriteStationErrorEnum;
    // Get a logger
    Logger* logger = Logging::getInstance()->getLogger("DEMO", "MyRadioConsumerApplication");

    // Check the usage
    QString programName(argv[0]);
    if (argc != 2) {
        LOG_ERROR(logger, QString("USAGE: %1 <provider-domain>").arg(programName));
        return 1;
    }

    // Get the provider domain
    std::string providerDomain(argv[1]);
    LOG_INFO(logger,
             QString("Creating proxy for provider on domain \"%1\"")
                     .arg(TypeUtil::toQt(providerDomain)));

    // Get the current program directory
    QString dir(QFileInfo(programName).absolutePath());

    // Initialise the JOYn runtime
    QString pathToMessagingSettings(dir + QString("/resources/radio-app-consumer.settings"));
    QString pathToLibJoynrSettings(dir +
                                   QString("/resources/radio-app-consumer.libjoynr.settings"));
    JoynrRuntime* runtime = JoynrRuntime::createRuntime(
            TypeUtil::toStd(pathToLibJoynrSettings), TypeUtil::toStd(pathToMessagingSettings));

    // Create proxy builder
    ProxyBuilder<vehicle::RadioProxy>* proxyBuilder =
            runtime->createProxyBuilder<vehicle::RadioProxy>(providerDomain);

    // Messaging Quality of service
    qlonglong qosMsgTtl = 30000;                // Time to live is 30 secs in one direction
    qlonglong qosCacheDataFreshnessMs = 400000; // Only consider data cached for < 400 secs

    // Find the provider with the highest priority set in ProviderQos
    DiscoveryQos discoveryQos;
    // As soon as the discovery QoS is set on the proxy builder, discovery of suitable providers
    // is triggered. If the discovery process does not find matching providers within the
    // arbitration timeout duration it will be terminated and you will get an arbitration exception.
    discoveryQos.setDiscoveryTimeout(40000);
    // Provider entries in the global capabilities directory are cached locally. Discovery will
    // consider entries in this cache valid if they are younger as the max age of cached
    // providers as defined in the QoS. All valid entries will be processed by the arbitrator when
    // searching
    // for and arbitrating the "best" matching provider.
    // NOTE: Valid cache entries might prevent triggering a lookup in the global capabilities
    //       directory. Therefore, not all providers registered with the global capabilities
    //       directory might be taken into account during arbitration.
    discoveryQos.setCacheMaxAge(std::numeric_limits<qint64>::max());
    // The discovery process outputs a list of matching providers. The arbitration strategy then
    // chooses one or more of them to be used by the proxy.
    discoveryQos.setArbitrationStrategy(DiscoveryQos::ArbitrationStrategy::HIGHEST_PRIORITY);

    // Build a proxy
    vehicle::RadioProxy* proxy = proxyBuilder->setMessagingQos(MessagingQos(qosMsgTtl))
                                         ->setCached(false)
                                         ->setDiscoveryQos(discoveryQos)
                                         ->build();

    vehicle::RadioStation currentStation;
    try {
        proxy->getCurrentStation(currentStation);
    } catch (exceptions::JoynrException& e) {
        assert(false);
    }
    MyRadioHelper::prettyLog(
            logger,
            QString("ATTRIBUTE GET: %1").arg(QString::fromStdString(currentStation.toString())));
    // Run a short subscription using the proxy
    // Set the Quality of Service parameters for the subscription

    // The provider will send a notification whenever the value changes. The number of sent
    // notifications may be limited by the min interval QoS.
    // NOTE: The provider must support on-change notifications in order to use this feature by
    //       calling the <attribute>Changed method of the <interface>Provider class whenever the
    //       <attribute> value changes.
    OnChangeWithKeepAliveSubscriptionQos subscriptionQos;
    // The provider will maintain at least a minimum interval idle time in milliseconds between
    // successive notifications, even if on-change notifications are enabled and the value changes
    // more often. This prevents the consumer from being flooded by updated values. The filtering
    // happens on the provider's side, thus also preventing excessive network traffic.
    subscriptionQos.setMinInterval(5 * 1000);
    // The provider will send notifications every maximum interval in milliseconds, even if the
    // value didn't change. It will send notifications more often if on-change notifications are
    // enabled, the value changes more often, and the minimum interval QoS does not prevent it. The
    // maximum interval can thus be seen as a sort of heart beat.
    subscriptionQos.setMaxInterval(8 * 1000);
    // The provider will send notifications until the end date is reached. The consumer will not
    // receive any notifications (neither value notifications nor missed publication notifications)
    // after this date.
    // setValidity_ms will set the end date to current time millis + validity_ms
    subscriptionQos.setValidity(60 * 1000);
    // Notification messages will be sent with this time-to-live. If a notification message can not
    // be delivered within its TTL, it will be deleted from the system.
    // NOTE: If a notification message is not delivered due to an expired TTL, it might raise a
    //       missed publication notification (depending on the value of the alert interval QoS).
    subscriptionQos.setAlertAfterInterval(10 * 1000);

    // Subscriptions go to a listener object
    std::shared_ptr<ISubscriptionListener<vehicle::RadioStation>> listener(
            new RadioStationListener());

    // Subscribe to the radio station.
    std::string currentStationSubscriptionId =
            proxy->subscribeToCurrentStation(listener, subscriptionQos);

    // broadcast subscription

    // The provider will send a notification whenever the value changes. The number of sent
    // notifications may be limited by the min interval QoS.
    // NOTE: The provider must support on-change notifications in order to use this feature by
    //       calling the <broadcast>EventOccurred method of the <interface>Provider class whenever
    //       the <broadcast> should be triggered.
    OnChangeSubscriptionQos weakSignalBroadcastSubscriptionQos;
    // The provider will maintain at least a minimum interval idle time in milliseconds between
    // successive notifications, even if on-change notifications are enabled and the value changes
    // more often. This prevents the consumer from being flooded by updated values. The filtering
    // happens on the provider's side, thus also preventing excessive network traffic.
    weakSignalBroadcastSubscriptionQos.setMinInterval(1 * 1000);
    // The provider will send notifications until the end date is reached. The consumer will not
    // receive any notifications (neither value notifications nor missed publication notifications)
    // after this date.
    // setValidity_ms will set the end date to current time millis + validity_ms
    weakSignalBroadcastSubscriptionQos.setValidity(60 * 1000);
    std::shared_ptr<ISubscriptionListener<vehicle::RadioStation>> weakSignalBroadcastListener(
            new WeakSignalBroadcastListener());
    std::string weakSignalBroadcastSubscriptionId = proxy->subscribeToWeakSignalBroadcast(
            weakSignalBroadcastListener, weakSignalBroadcastSubscriptionQos);

    // selective broadcast subscription

    OnChangeSubscriptionQos newStationDiscoveredBroadcastSubscriptionQos;
    newStationDiscoveredBroadcastSubscriptionQos.setMinInterval(2 * 1000);
    newStationDiscoveredBroadcastSubscriptionQos.setValidity(180 * 1000);
    std::shared_ptr<ISubscriptionListener<vehicle::RadioStation, vehicle::GeoPosition>>
            newStationDiscoveredBroadcastListener(new NewStationDiscoveredBroadcastListener());
    vehicle::RadioNewStationDiscoveredBroadcastFilterParameters
            newStationDiscoveredBroadcastFilterParams;
    newStationDiscoveredBroadcastFilterParams.setHasTrafficService("true");
    vehicle::GeoPosition positionOfInterest(48.1351250, 11.5819810); // Munich
    std::string positionOfInterestJson(JsonSerializer::serialize(positionOfInterest));
    newStationDiscoveredBroadcastFilterParams.setPositionOfInterest(positionOfInterestJson);
    newStationDiscoveredBroadcastFilterParams.setRadiusOfInterestArea("200000"); // 200 km
    std::string newStationDiscoveredBroadcastSubscriptionId =
            proxy->subscribeToNewStationDiscoveredBroadcast(
                    newStationDiscoveredBroadcastFilterParams,
                    newStationDiscoveredBroadcastListener,
                    newStationDiscoveredBroadcastSubscriptionQos);
    // add favorite radio station
    vehicle::RadioStation favoriteStation("99.3 The Fox Rocks", false, vehicle::Country::CANADA);
    bool success;
    try {
        proxy->addFavoriteStation(success, favoriteStation);
        MyRadioHelper::prettyLog(logger,
                                 QString("METHOD: added favorite station: %1")
                                         .arg(QString::fromStdString(favoriteStation.toString())));
        proxy->addFavoriteStation(success, favoriteStation);
    } catch (exceptions::ApplicationException& e) {
        if (e.getError<AddFavoriteStationErrorEnum::Enum>() ==
            AddFavoriteStationErrorEnum::DUPLICATE_RADIOSTATION) {
            MyRadioHelper::prettyLog(
                    logger,
                    QString("METHOD: add favorite station a second time failed with the following "
                            "expected exception: %1").arg(QString::fromStdString(e.getName())));
        } else {
            MyRadioHelper::prettyLog(
                    logger,
                    QString("METHOD: add favorite station a second time failed with the following "
                            "UNEXPECTED exception: %1").arg(QString::fromStdString(e.getName())));
        }
    }

    try {
        favoriteStation.setName("");
        proxy->addFavoriteStation(success, favoriteStation);
    } catch (exceptions::ProviderRuntimeException& e) {
        if (e.getMessage() == MyRadioHelper::MISSING_NAME()) {
            MyRadioHelper::prettyLog(
                    logger,
                    QString("METHOD: add favorite station with empty name failed with the "
                            "following "
                            "expected exception: %1").arg(QString::fromStdString(e.getMessage())));
        } else {
            MyRadioHelper::prettyLog(logger,
                                     QString("METHOD: add favorite station with empty name failed "
                                             "with the following "
                                             "UNEXPECTED exception: %1")
                                             .arg(QString::fromStdString(e.getMessage())));
        }
    }

    // shuffle the stations
    MyRadioHelper::prettyLog(logger, QString("METHOD: calling shuffle stations"));
    proxy->shuffleStations();
    // Run until the user hits q
    int key;

    while ((key = MyRadioHelper::getch()) != 'q') {
        joynr::vehicle::GeoPosition location;
        joynr::vehicle::Country::Enum country;
        switch (key) {
        case 's':
            proxy->shuffleStations();
            break;
        case 'm':
            proxy->getLocationOfCurrentStation(country, location);
            MyRadioHelper::prettyLog(
                    logger,
                    QString("METHOD: getLocationOfCurrentStation: country: %1, location: %2")
                            .arg(QString::fromStdString(
                                    joynr::vehicle::Country::getLiteral(country)))
                            .arg(QString::fromStdString(location.toString())));
            break;
        default:
            MyRadioHelper::prettyLog(logger,
                                     QString("USAGE press\n"
                                             " q\tto quit\n"
                                             " s\tto shuffle stations\n"));
            break;
        }
    }

    // unsubscribe
    proxy->unsubscribeFromCurrentStation(currentStationSubscriptionId);
    proxy->unsubscribeFromWeakSignalBroadcast(weakSignalBroadcastSubscriptionId);
    proxy->unsubscribeFromNewStationDiscoveredBroadcast(
            newStationDiscoveredBroadcastSubscriptionId);

    delete proxy;
    delete proxyBuilder;
    delete runtime;
    delete logger;
    return 0;
}
