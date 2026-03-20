package edu.wisc.cs.sdn.vnet.rt;

import java.util.Timer;
import java.util.TimerTask;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	private static final int    RIP_MULTICAST_IP = IPv4.toIPv4Address("224.0.0.9");
	private static final byte[] BROADCAST_MAC    = {(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff};
	private static final int    RIP_TIMEOUT_MS   = 30 * 1000;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host, logfile);
		this.routeTable = new RouteTable();
		this.arpCache   = new ArpCache();
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }

	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}

		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	/**
	 * Start RIP on the router when no static route table is provided.
	 * Populates directly-connected routes, sends an initial RIP request out
	 * every interface, then sends unsolicited RIP responses every 10 seconds.
	 * Also starts the 30-second route-timeout sweep.
	 */
	public void startRip()
	{
		// Add directly-connected subnet routes (metric 1, no gateway)
		for (Iface iface : this.interfaces.values())
		{
			int subnet = iface.getIpAddress() & iface.getSubnetMask();
			this.routeTable.insert(subnet, 0, iface.getSubnetMask(), iface, 1);
		}

		// Send a RIP request out every interface
		for (Iface iface : this.interfaces.values())
		{ sendRipPacket(RIPv2.COMMAND_REQUEST, iface, RIP_MULTICAST_IP, BROADCAST_MAC); }

		// Every 10 seconds send an unsolicited RIP response out every interface
		Timer ripTimer = new Timer(true);
		ripTimer.scheduleAtFixedRate(new TimerTask() {
			@Override public void run()
			{
				for (Iface iface : interfaces.values())
				{ sendRipPacket(RIPv2.COMMAND_RESPONSE, iface, RIP_MULTICAST_IP, BROADCAST_MAC); }
			}
		}, 10000, 10000);

		// Every second, expire learned routes that haven't been refreshed in 30 s
		Timer timeoutTimer = new Timer(true);
		timeoutTimer.scheduleAtFixedRate(new TimerTask() {
			@Override public void run()
			{
				long now = System.currentTimeMillis();
				for (RouteEntry entry : routeTable.getEntries())
				{
					// Never remove directly-connected routes (gateway == 0)
					if (entry.getGatewayAddress() == 0) { continue; }
					if (now - entry.getLastUpdated() > RIP_TIMEOUT_MS)
					{ routeTable.remove(entry.getDestinationAddress(), entry.getMaskAddress()); }
				}
			}
		}, 1000, 1000);
	}

	/**
	 * Build and send a RIPv2 packet.
	 * @param command  RIPv2.COMMAND_REQUEST or RIPv2.COMMAND_RESPONSE
	 * @param outIface interface to send out
	 * @param dstIp    destination IP address
	 * @param dstMac   destination MAC address
	 */
	private void sendRipPacket(byte command, Iface outIface, int dstIp, byte[] dstMac)
	{
		// RIPv2 payload
		RIPv2 rip = new RIPv2();
		rip.setCommand(command);
		if (command == RIPv2.COMMAND_RESPONSE)
		{
			for (RouteEntry entry : this.routeTable.getEntries())
			{
				rip.addEntry(new RIPv2Entry(
						entry.getDestinationAddress(),
						entry.getMaskAddress(),
						entry.getMetric()));
			}
		}

		else
		{
			rip.addEntry(new RIPv2Entry(0, 0, 16));
		}

		// UDP wrapper
		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(rip);

		// IPv4 wrapper
		IPv4 ip = new IPv4();
		ip.setSourceAddress(outIface.getIpAddress());
		ip.setDestinationAddress(dstIp);
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setTtl((byte)64);
		ip.setPayload(udp);

		// Ethernet wrapper
		Ethernet ether = new Ethernet();
		ether.setEtherType((short)0x0800);
		ether.setSourceMACAddress(outIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(dstMac);
		ether.setPayload(ip);

		this.sendPacket(ether, outIface);
	}

	/**
	 * Process an incoming RIPv2 packet.
	 * Requests get a unicast response; responses update the route table.
	 */
	private void handleRipPacket(Ethernet etherPacket, IPv4 ipPacket,
			UDP udpPacket, Iface inIface)
	{
		RIPv2 rip = (RIPv2) udpPacket.getPayload();

		if (rip.getCommand() == RIPv2.COMMAND_REQUEST)
		{
			// Unicast response back to the requester
			sendRipPacket(RIPv2.COMMAND_RESPONSE, inIface,
					ipPacket.getSourceAddress(),
					etherPacket.getSourceMACAddress());
		}
		else if (rip.getCommand() == RIPv2.COMMAND_RESPONSE)
		{
			int srcIp = ipPacket.getSourceAddress();

			for (RIPv2Entry entry : rip.getEntries())
			{
				int dst     = entry.getAddress();
				int mask    = entry.getSubnetMask();
				int newMetric = Math.min(entry.getMetric() + 1, 16);

				// Metric 16 means unreachable — skip
				if (newMetric >= 16) { continue; }

				// Find if we already have an exact route for this prefix
				RouteEntry existing = null;
				for (RouteEntry e : this.routeTable.getEntries())
				{
					if (e.getDestinationAddress() == dst && e.getMaskAddress() == mask)
					{ existing = e; break; }
				}

				if (existing == null)
				{
					// New route — insert it
					this.routeTable.insert(dst, srcIp, mask, inIface, newMetric);
				}
				else if (newMetric < existing.getMetric()
						|| existing.getGatewayAddress() == srcIp)
				{
					// Better path, or a refresh from the same neighbour
					this.routeTable.update(dst, mask, srcIp, inIface, newMetric);
				}
			}
		}
	}

	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}

		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		if (etherPacket.getEtherType() != 0x0800) { return; }

		IPv4 ipPacket = (IPv4) etherPacket.getPayload();

		// Verify checksum
		short originalChecksum = ipPacket.getChecksum();
		ipPacket.resetChecksum();
		byte[] serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		if (ipPacket.getChecksum() != originalChecksum) { return; }

		// Check for RIP (UDP port 520) before normal forwarding
		if (ipPacket.getProtocol() == IPv4.PROTOCOL_UDP)
		{
			UDP udpPacket = (UDP) ipPacket.getPayload();
			if (udpPacket.getDestinationPort() == UDP.RIP_PORT)
			{
				handleRipPacket(etherPacket, ipPacket, udpPacket, inIface);
				return;
			}
		}

		// Decrement TTL; drop if expired
		ipPacket.setTtl((byte)(ipPacket.getTtl() - 1));
		if (ipPacket.getTtl() == 0) { return; }

		// Drop packets destined for one of our own interfaces
		for (Iface iface : this.interfaces.values())
		{
			if (ipPacket.getDestinationAddress() == iface.getIpAddress()) { return; }
		}

		// Look up next-hop
		RouteEntry match = this.routeTable.lookup(ipPacket.getDestinationAddress());
		if (match == null) { return; }

		// Never route a packet back out the same interface it arrived on
		if (match.getInterface() == inIface) { return; }

		int nextHopIp = match.getGatewayAddress();
		if (nextHopIp == 0) { nextHopIp = ipPacket.getDestinationAddress(); }

		ArpEntry arpEntry = this.arpCache.lookup(nextHopIp);
		if (arpEntry == null) { return; }

		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
		etherPacket.setSourceMACAddress(match.getInterface().getMacAddress().toBytes());
		ipPacket.resetChecksum();

		this.sendPacket(etherPacket, match.getInterface());
	}
}
