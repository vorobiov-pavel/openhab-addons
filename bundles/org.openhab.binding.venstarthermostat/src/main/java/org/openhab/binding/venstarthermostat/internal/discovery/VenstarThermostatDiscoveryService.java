/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.venstarthermostat.internal.discovery;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Scanner;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.venstarthermostat.internal.VenstarThermostatBindingConstants;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link VenstarThermostatDiscoveryService} is responsible for discovery of
 * Venstar thermostats on the local network
 *
 * @author William Welliver - Initial contribution
 * @author Dan Cunningham - Refactoring and Improvements
 */

@Component(service = DiscoveryService.class, configurationPid = "discovery.venstarthermostat")
public class    VenstarThermostatDiscoveryService extends AbstractDiscoveryService {
    private final Logger logger = LoggerFactory.getLogger(VenstarThermostatDiscoveryService.class);
    private static final String COLOR_TOUCH_DISCOVERY_MESSAGE = "M-SEARCH * HTTP/1.1\r\n"
            + "Host: 239.255.255.250:1900\r\n" + "Man: ssdp:discover\r\n" + "ST: colortouch:ecp\r\n" + "\r\n";
    private static final Pattern USN_PATTERN = Pattern
            .compile("^(colortouch:)?ecp((?::[0-9a-fA-F]{2}){6}):name:(.+)(?::type:(\\w+))");
    private static final String SSDP_MATCH = "colortouch:ecp";
    private static final int BACKGROUND_SCAN_INTERVAL_SECONDS = 300;

    private ScheduledFuture<?> scheduledFuture = null;

    public VenstarThermostatDiscoveryService() {
        super(VenstarThermostatBindingConstants.SUPPORTED_THING_TYPES, 30, true);
    }

    @Override
    protected void startBackgroundDiscovery() {
        logger.debug("Starting Background Scan");
        stopBackgroundDiscovery();
        scheduledFuture = scheduler.scheduleAtFixedRate(this::doRunRun, 0, BACKGROUND_SCAN_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    @Override
    protected void stopBackgroundDiscovery() {
        if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
            scheduledFuture.cancel(true);
        }
    }

    @Override
    protected void startScan() {
        logger.debug("Starting Interactive Scan");
        doRunRun();
    }

    protected synchronized void doRunRun() {
        logger.trace("Sending SSDP discover.");
        for (int i = 0; i < 5; i++) {
            try {
                Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
                while (nets.hasMoreElements()) {
                    NetworkInterface ni = nets.nextElement();
                    MulticastSocket socket = sendDiscoveryBroacast(ni);
                    if (socket != null) {
                        scanResposesForKeywords(socket);
                    }
                }
            } catch (IOException e) {
                logger.debug("Error discoverying devices", e);
            }
        }
    }

    /**
     * Broadcasts a SSDP discovery message into the network to find provided
     * services.
     *
     * @return The Socket the answers will arrive at.
     * @throws UnknownHostException
     * @throws IOException
     * @throws SocketException
     * @throws UnsupportedEncodingException
     */
    private MulticastSocket sendDiscoveryBroacast(NetworkInterface ni)
            throws UnknownHostException, SocketException, UnsupportedEncodingException {
        InetAddress m = InetAddress.getByName("239.255.255.250");
        final int port = 1900;

        logger.trace("Considering {}", ni.getName());
        try {
            if (!ni.isUp() || !ni.supportsMulticast()) {
                logger.trace("skipping interface {}", ni.getName());
                return null;
            }

            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            InetAddress a = null;
            while (addrs.hasMoreElements()) {
                a = addrs.nextElement();
                if (a instanceof Inet4Address) {
                    break;
                } else {
                    a = null;
                }
            }
            if (a == null) {
                logger.trace("no ipv4 address on {}", ni.getName());
                return null;
            }

            // for whatever reason, the venstar thermostat responses will not be seen
            // if we bind this socket to a particular address.
            // this seems to be okay on linux systems, but osx apparently prefers ipv6, so this
            // prevents responses from being received unless the ipv4 stack is given preference.
            MulticastSocket socket = new MulticastSocket(new InetSocketAddress(port));
            socket.setSoTimeout(2000);
            socket.setReuseAddress(true);
            socket.setNetworkInterface(ni);
            socket.joinGroup(m);

            logger.trace("Joined UPnP Multicast group on Interface: {}", ni.getName());
            byte[] requestMessage = COLOR_TOUCH_DISCOVERY_MESSAGE.getBytes("UTF-8");
            DatagramPacket datagramPacket = new DatagramPacket(requestMessage, requestMessage.length, m, port);
            socket.send(datagramPacket);
            return socket;
        } catch (IOException e) {
            logger.trace("got ioexception: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Scans all messages that arrive on the socket and scans them for the
     * search keywords. The search is not case sensitive.
     *
     * @param socket
     *            The socket where the answers arrive.
     * @param keywords
     *            The keywords to be searched for.
     * @return
     * @throws IOException
     */
    private void scanResposesForKeywords(MulticastSocket socket, String... keywords) throws IOException {
        // In the worst case a SocketTimeoutException raises
        do {
            byte[] rxbuf = new byte[8192];
            DatagramPacket packet = new DatagramPacket(rxbuf, rxbuf.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                logger.trace("Got exception while trying to receive UPnP packets: {}", e.getMessage());
                return;
            }
            String response = new String(packet.getData());
            if (response.contains(SSDP_MATCH)) {
                logger.trace("Match: {} ", response);
                parseResponse(response);
            }
        } while (true);
    }

    protected void parseResponse(String response) {
        DiscoveryResult result;

        String name = null;
        String url = null;
        String uuid = null;

        Scanner scanner = new Scanner(response);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            String[] pair = line.split(":", 2);
            if (pair.length != 2) {
                continue;
            }
            String key = pair[0].toLowerCase();
            String value = pair[1].trim();
            logger.trace("key: {} value: {}.", key, value);
            switch (key) {
                case "location":
                    url = value;
                    break;
                case "usn":
                    Matcher m = USN_PATTERN.matcher(value);
                    if (m.find()) {
                        uuid = m.group(2);
                        name = m.group(3);
                    }
                    break;
                default:
                    break;
            }
        }
        scanner.close();

        logger.trace("Found thermostat, name: {} uuid: {} url: {}", name, uuid, url);

        if (name == null || uuid == null || url == null) {
            logger.trace("Bad Format from thermostat");
            return;
        }

        uuid = uuid.replace(":", "").toLowerCase();

        ThingUID thingUid = new ThingUID(VenstarThermostatBindingConstants.THING_TYPE_COLOR_TOUCH, uuid);

        logger.trace("Got discovered device.");

        String label = String.format("Venstar Thermostat (%s)", name);
        result = DiscoveryResultBuilder.create(thingUid).withLabel(label).withRepresentationProperty(uuid)
                .withProperty(VenstarThermostatBindingConstants.PROPERTY_UUID, uuid)
                .withProperty(VenstarThermostatBindingConstants.PROPERTY_URL, url).build();
        logger.trace("New venstar thermostat discovered with ID=<{}>", uuid);
        this.thingDiscovered(result);
    }

}