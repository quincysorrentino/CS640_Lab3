package edu.wisc.cs.sdn.vnet.rt;

import net.floodlightcontroller.packet.IPv4;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * An entry in a route table.
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class RouteEntry 
{
	/** Destination IP address */
	private int destinationAddress;

	/** Gateway IP address */
	private int gatewayAddress;

	/** Subnet mask */
	private int maskAddress;

	/** Router interface out which packets should be sent to reach
	 * the destination or gateway */
	private Iface iface;

	/** RIP distance-vector metric (hop count) */
	private int metric;

	/** Time (ms since epoch) when this entry was last updated by RIP */
	private long lastUpdated;

	/**
	 * Create a new route table entry with default metric of 1.
	 */
	public RouteEntry(int destinationAddress, int gatewayAddress,
			int maskAddress, Iface iface)
	{
		this(destinationAddress, gatewayAddress, maskAddress, iface, 1);
	}

	/**
	 * Create a new route table entry with a specified metric.
	 */
	public RouteEntry(int destinationAddress, int gatewayAddress,
			int maskAddress, Iface iface, int metric)
	{
		this.destinationAddress = destinationAddress;
		this.gatewayAddress = gatewayAddress;
		this.maskAddress = maskAddress;
		this.iface = iface;
		this.metric = metric;
		this.lastUpdated = System.currentTimeMillis();
	}
	
	/**
	 * @return destination IP address
	 */
	public int getDestinationAddress()
	{ return this.destinationAddress; }
	
	/**
	 * @return gateway IP address
	 */
	public int getGatewayAddress()
	{ return this.gatewayAddress; }

	public void setGatewayAddress(int gatewayAddress)
	{ this.gatewayAddress = gatewayAddress; }
	
	/**
	 * @return subnet mask 
	 */
	public int getMaskAddress()
	{ return this.maskAddress; }
	
	/**
	 * @return the router interface out which packets should be sent to 
	 *         reach the destination or gateway
	 */
	public Iface getInterface()
	{ return this.iface; }

	public void setInterface(Iface iface)
	{ this.iface = iface; }

	public int getMetric()
	{ return this.metric; }

	public void setMetric(int metric)
	{ this.metric = metric; }

	public long getLastUpdated()
	{ return this.lastUpdated; }

	public void setLastUpdated(long lastUpdated)
	{ this.lastUpdated = lastUpdated; }

	public String toString()
	{
		return String.format("%s \t%s \t%s \t%s",
				IPv4.fromIPv4Address(this.destinationAddress),
				IPv4.fromIPv4Address(this.gatewayAddress),
				IPv4.fromIPv4Address(this.maskAddress),
				this.iface.getName());
	}
}
